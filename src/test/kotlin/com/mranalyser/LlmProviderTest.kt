package com.mranalyser

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.port.LlmResponse
import com.mranalyser.application.port.LlmUsage
import com.mranalyser.infrastructure.llm.AnthropicLlmProvider
import com.mranalyser.infrastructure.llm.GeminiLlmProvider
import com.mranalyser.infrastructure.llm.LlmTransportSettings
import com.mranalyser.infrastructure.llm.NoOpLlmProvider
import com.mranalyser.infrastructure.llm.OllamaLlmProvider
import com.mranalyser.infrastructure.llm.OpenAiLlmProvider
import com.mranalyser.infrastructure.llm.ProgressLoggingLlmProvider
import com.mranalyser.infrastructure.llm.ResilientLlmProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * O contrato central da porta: **o provider nunca lança**. Uma falha em uma etapa não pode
 * abortar a análise inteira (item 38).
 */
class LlmProviderTest {
    private lateinit var server: WireMockServer

    private val request = LlmRequest(
        purpose = LlmPurpose.LOCAL_REVIEW,
        system = "instruções de sistema",
        user = "diff para revisar"
    )

    @BeforeEach
    fun setUp() {
        server = WireMockServer(options().dynamicPort())
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    private fun baseUrl() = "http://localhost:${server.port()}"

    @Test
    fun `openai deve devolver o conteudo e enviar o system prompt`() = runBlocking {
        server.stubFor(
            post(urlPathEqualTo("/chat/completions")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"choices": [{"message": {"role": "assistant", "content": "{\"summary\":\"ok\"}"}}]}""")
            )
        )

        val response = OpenAiLlmProvider(apiKey = "k", model = "m", baseUrl = baseUrl()).complete(request)

        assertTrue(response.successful)
        assertEquals("""{"summary":"ok"}""", response.text)

        val received = server.allServeEvents.single().request
        assertTrue(received.bodyAsString.contains("instruções de sistema"))
        assertEquals("Bearer k", received.getHeader("Authorization"))
    }

    @Test
    fun `openai deve converter erro http em falha e nao lancar`() = runBlocking {
        server.stubFor(
            post(urlPathEqualTo("/chat/completions")).willReturn(
                aResponse().withStatus(429).withBody("""{"error": {"message": "rate limit exceeded"}}""")
            )
        )

        val response = OpenAiLlmProvider(apiKey = "k", model = "m", baseUrl = baseUrl()).complete(request)

        assertFalse(response.successful)
        assertTrue(response.failure!!.contains("429"))
        assertTrue(response.failure!!.contains("rate limit exceeded"))
    }

    @Test
    fun `anthropic deve enviar system separado e tratar erro sem lancar`() = runBlocking {
        server.stubFor(
            post(urlPathEqualTo("/v1/messages")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"content": [{"type": "text", "text": "{\"summary\":\"ok\"}"}]}""")
            )
        )

        val provider = AnthropicLlmProvider(apiKey = "k", model = "m", baseUrl = baseUrl())
        val response = provider.complete(request)

        assertTrue(response.successful)
        val received = server.allServeEvents.single().request
        assertTrue(received.bodyAsString.contains("\"system\":\"instruções de sistema\""))
        assertEquals("2023-06-01", received.getHeader("anthropic-version"))

        server.resetAll()
        server.stubFor(
            post(urlPathEqualTo("/v1/messages")).willReturn(
                aResponse().withStatus(500).withBody("""{"error": {"message": "overloaded"}}""")
            )
        )
        val failure = provider.complete(request)
        assertFalse(failure.successful)
        assertTrue(failure.failure!!.contains("overloaded"))
    }

    @Test
    fun `gemini deve concatenar partes e reportar finishReason quando vazio`() = runBlocking {
        server.stubFor(
            post(urlPathEqualTo("/v1beta/models/m:generateContent")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"candidates": [{"content": {"parts": [{"text": "{\"a\":"}, {"text": "1}"}]}}]}""")
            )
        )

        val provider = GeminiLlmProvider(apiKey = "k", model = "m", baseUrl = baseUrl())
        assertEquals("""{"a":1}""", provider.complete(request).text)

        server.resetAll()
        server.stubFor(
            post(urlPathEqualTo("/v1beta/models/m:generateContent")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"candidates": [{"finishReason": "MAX_TOKENS"}]}""")
            )
        )
        val failure = provider.complete(request)
        assertFalse(failure.successful)
        assertTrue(failure.failure!!.contains("MAX_TOKENS"))
    }

    @Test
    fun `ollama deve enviar system e limite de tokens`() = runBlocking {
        server.stubFor(
            post(urlPathEqualTo("/api/generate")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"response": "{\"summary\":\"ok\"}"}""")
            )
        )

        val response = OllamaLlmProvider(model = "m", baseUrl = baseUrl())
            .complete(request.copy(maxOutputTokens = 4096))

        assertTrue(response.successful)
        val body = server.allServeEvents.single().request.bodyAsString
        assertTrue(body.contains("\"system\":\"instruções de sistema\""))
        assertTrue(body.contains("\"num_predict\":4096"))
    }

    /**
     * O streaming é o que permite geração longa em modelo self-hosted: o critério de falha passa a
     * ser o servidor parar de responder, não a duração total da geração.
     */
    @Test
    fun `ollama deve concatenar os fragmentos do stream`() = runBlocking {
        server.stubFor(
            post(urlPathEqualTo("/api/generate")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/x-ndjson")
                    .withBody(
                        """
                        {"response":"{\"summary\":"}
                        {"response":"\"ok\"}"}
                        {"response":"","done":true}
                        """.trimIndent()
                    )
            )
        )

        val response = OllamaLlmProvider(model = "m", baseUrl = baseUrl()).complete(request)

        assertTrue(response.successful)
        assertEquals("""{"summary":"ok"}""", response.text)
        assertTrue(server.allServeEvents.single().request.bodyAsString.contains("\"stream\":true"))
    }

    /**
     * `num_ctx` é o parâmetro que decide se o modelo cabe inteiro na GPU, então precisa chegar ao
     * servidor — e precisa ficar de fora quando não configurado, para não sobrescrever o Modelfile.
     */
    @Test
    fun `ollama deve enviar num_ctx quando configurado`() = runBlocking {
        server.stubFor(
            post(urlPathEqualTo("/api/generate")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"response": "ok", "done": true}""")
            )
        )

        OllamaLlmProvider(
            model = "m",
            baseUrl = baseUrl(),
            settings = LlmTransportSettings(numCtx = 24_576)
        ).complete(request)

        assertTrue(server.allServeEvents.single().request.bodyAsString.contains("\"num_ctx\":24576"))
    }

    @Test
    fun `ollama nao deve enviar num_ctx quando ausente`() = runBlocking {
        server.stubFor(
            post(urlPathEqualTo("/api/generate")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"response": "ok", "done": true}""")
            )
        )

        OllamaLlmProvider(model = "m", baseUrl = baseUrl()).complete(request)

        assertFalse(server.allServeEvents.single().request.bodyAsString.contains("num_ctx"))
    }

    @Test
    fun `ollama deve reportar o consumo de tokens do chunk final`() = runBlocking {
        server.stubFor(
            post(urlPathEqualTo("/api/generate")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/x-ndjson")
                    .withBody(
                        """
                        {"response":"ok"}
                        {"response":"","done":true,"prompt_eval_count":19343,"eval_count":1480}
                        """.trimIndent()
                    )
            )
        )

        val response = OllamaLlmProvider(
            model = "m",
            baseUrl = baseUrl(),
            settings = LlmTransportSettings(numCtx = 24_576)
        ).complete(request.copy(maxOutputTokens = 3_000))

        val usage = response.usage!!
        assertEquals(19_343, usage.promptTokens)
        assertEquals(1_480, usage.outputTokens)
        assertFalse(usage.exceedsContextWindow(3_000), "19343 + 3000 cabe em 24576")
        assertTrue(usage.exceedsContextWindow(6_000), "19343 + 6000 não cabe: prompt seria truncado")
    }

    @Test
    fun `ollama deve tratar erro no meio do stream como falha`() = runBlocking {
        server.stubFor(
            post(urlPathEqualTo("/api/generate")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/x-ndjson")
                    .withBody(
                        """
                        {"response":"parcial"}
                        {"error":"model requires more system memory"}
                        """.trimIndent()
                    )
            )
        )

        val response = OllamaLlmProvider(model = "m", baseUrl = baseUrl()).complete(request)

        assertFalse(response.successful)
        assertTrue(response.failure!!.contains("more system memory"))
    }

    @Test
    fun `provider indisponivel deve virar falha em vez de excecao`() = runBlocking {
        val url = baseUrl()
        server.stop()

        val response = OpenAiLlmProvider(apiKey = "k", model = "m", baseUrl = url).complete(request)

        assertFalse(response.successful)
        assertTrue(response.failure!!.contains("indisponível"))
    }

    @Test
    fun `api key ausente deve virar falha permanente`() = runBlocking {
        val response = OpenAiLlmProvider(apiKey = "", model = "m", baseUrl = baseUrl()).complete(request)

        assertFalse(response.successful)
        assertTrue(response.failure!!.contains("não configurada"))
    }

    @Test
    fun `noop deve informar que nenhum provider esta configurado`() = runBlocking {
        val response = NoOpLlmProvider().complete(request)

        assertFalse(response.successful)
        assertTrue(response.failure!!.contains("não configurado"))
    }
}

class ResilientLlmProviderTest {
    private val request = LlmRequest(LlmPurpose.LOCAL_REVIEW, "s", "u")

    @Test
    fun `deve repetir falha transitoria e devolver o sucesso`() = runBlocking {
        val provider = CountingProvider(
            listOf(
                LlmResponse.failed("openai indisponível: SocketTimeoutException"),
                LlmResponse("ok")
            )
        )

        val response = ResilientLlmProvider(provider, maxRetries = 2, sleep = {}).complete(request)

        assertTrue(response.successful)
        assertEquals(2, provider.calls)
    }

    @Test
    fun `nao deve repetir falha permanente`() = runBlocking {
        val provider = CountingProvider(listOf(LlmResponse.failed("API key da OpenAI não configurada")))

        val response = ResilientLlmProvider(provider, maxRetries = 3, sleep = {}).complete(request)

        assertFalse(response.successful)
        assertEquals(1, provider.calls, "erro de configuração não melhora com repetição")
    }

    @Test
    fun `nao deve repetir erro 4xx`() = runBlocking {
        val provider = CountingProvider(listOf(LlmResponse.failed("openai retornou HTTP 400: bad request")))

        ResilientLlmProvider(provider, maxRetries = 3, sleep = {}).complete(request)

        assertEquals(1, provider.calls)
    }

    @Test
    fun `deve respeitar o limite de tentativas`() = runBlocking {
        val provider = CountingProvider(emptyList(), fallback = LlmResponse.failed("timeout"))

        val response = ResilientLlmProvider(provider, maxRetries = 2, sleep = {}).complete(request)

        assertFalse(response.successful)
        assertEquals(3, provider.calls, "1 tentativa inicial + 2 retentativas")
    }

    private class CountingProvider(
        private val responses: List<LlmResponse>,
        private val fallback: LlmResponse = LlmResponse("ok")
    ) : LlmProvider {
        override val name: String = "counting"
        var calls: Int = 0
            private set

        override suspend fun complete(request: LlmRequest): LlmResponse {
            val response = responses.getOrElse(calls) { fallback }
            calls++
            return response
        }
    }
}

/**
 * O decorator não pode alterar a resposta: ele só existe para dar sinal de vida durante uma
 * dezena de chamadas sequenciais que somam minutos cada.
 */
class ProgressLoggingLlmProviderTest {
    private val request = LlmRequest(LlmPurpose.LOCAL_REVIEW, "s", "u", label = "chunk 3/10 (DOMAIN)")

    @Test
    fun `deve repassar sucesso e falha sem alterar a resposta`() = runBlocking {
        val success = LlmResponse("conteúdo", usage = LlmUsage(promptTokens = 12_300, outputTokens = 1_480))
        assertEquals(success, ProgressLoggingLlmProvider(FixedProvider(success)).complete(request))

        val failure = LlmResponse.failed("ollama indisponível: SocketTimeoutException")
        assertEquals(failure, ProgressLoggingLlmProvider(FixedProvider(failure)).complete(request))
    }

    @Test
    fun `nao deve quebrar quando o provider nao reporta consumo`() = runBlocking {
        val response = ProgressLoggingLlmProvider(FixedProvider(LlmResponse("sem usage"))).complete(request)

        assertTrue(response.successful)
    }

    private class FixedProvider(private val response: LlmResponse) : LlmProvider {
        override val name: String = "fixed"
        override suspend fun complete(request: LlmRequest): LlmResponse = response
    }
}
