package com.mranalyser.infrastructure.llm

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.port.LlmResponse
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class AnthropicLlmProvider(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val settings: LlmTransportSettings = LlmTransportSettings()
) : LlmProvider {
    override val name: String = "anthropic:$model"

    private val client = buildLlmHttpClient(settings)

    override suspend fun complete(request: LlmRequest): LlmResponse = safeCompletion(name) {
        if (apiKey.isBlank()) {
            return@safeCompletion LlmResponse.failed("API key da Anthropic não configurada")
        }

        val response = client.post("${baseUrl.trimEnd('/')}/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            header("content-type", "application/json")
            setBody(
                AnthropicRequest(
                    model = model,
                    maxTokens = request.maxOutputTokens,
                    temperature = request.temperature,
                    system = request.system,
                    messages = listOf(
                        AnthropicMessage(
                            role = "user",
                            content = listOf(AnthropicText(type = "text", text = request.user))
                        )
                    )
                )
            )
        }

        response.failureOrNull(name) { raw ->
            runCatching { llmJson.decodeFromString<AnthropicErrorResponse>(raw) }.getOrNull()?.error?.message
        }?.let { return@safeCompletion LlmResponse.failed(it) }

        val raw = response.bodyAsText()
        val parsed = runCatching { llmJson.decodeFromString<AnthropicResponse>(raw) }.getOrNull()
            ?: return@safeCompletion LlmResponse.failed("resposta da Anthropic não pôde ser desserializada")

        val text = parsed.content.filter { it.type == "text" }.joinToString("") { it.text }
        if (text.isBlank()) {
            return@safeCompletion LlmResponse.failed("Anthropic retornou conteúdo vazio")
        }

        LlmResponse(text)
    }
}

@Serializable
private data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double,
    val system: String? = null,
    val messages: List<AnthropicMessage>
)

@Serializable
private data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicText>
)

@Serializable
private data class AnthropicText(
    val type: String,
    val text: String = ""
)

@Serializable
private data class AnthropicResponse(
    val content: List<AnthropicText> = emptyList()
)

@Serializable
private data class AnthropicErrorResponse(
    val error: AnthropicErrorPayload? = null
)

@Serializable
private data class AnthropicErrorPayload(
    val type: String? = null,
    val message: String? = null
)
