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

class GeminiLlmProvider(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val settings: LlmTransportSettings = LlmTransportSettings()
) : LlmProvider {
    override val name: String = "gemini:$model"

    private val client = buildLlmHttpClient(settings)

    override suspend fun complete(request: LlmRequest): LlmResponse = safeCompletion(name) {
        if (apiKey.isBlank()) {
            return@safeCompletion LlmResponse.failed("API key do Gemini não configurada")
        }

        val endpoint = "${baseUrl.trimEnd('/')}/v1beta/models/$model:generateContent"
        val response = client.post(endpoint) {
            header("x-goog-api-key", apiKey)
            header("Content-Type", "application/json")
            setBody(
                GeminiRequest(
                    systemInstruction = GeminiContent(parts = listOf(GeminiPart(request.system))),
                    contents = listOf(GeminiContent(parts = listOf(GeminiPart(request.user)))),
                    generationConfig = GeminiGenerationConfig(
                        temperature = request.temperature,
                        maxOutputTokens = request.maxOutputTokens,
                        responseMimeType = if (settings.jsonMode) "application/json" else null
                    )
                )
            )
        }

        response.failureOrNull(name) { raw ->
            runCatching { llmJson.decodeFromString<GeminiErrorResponse>(raw) }.getOrNull()?.error?.message
        }?.let { return@safeCompletion LlmResponse.failed(it) }

        val raw = response.bodyAsText()
        val parsed = runCatching { llmJson.decodeFromString<GeminiResponse>(raw) }.getOrNull()
            ?: return@safeCompletion LlmResponse.failed("resposta do Gemini não pôde ser desserializada")

        val text = parsed.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.joinToString("") { it.text }
            .orEmpty()

        if (text.isBlank()) {
            val reason = parsed.candidates.firstOrNull()?.finishReason
            return@safeCompletion LlmResponse.failed(
                "Gemini retornou conteúdo vazio${reason?.let { " (finishReason=$it)" }.orEmpty()}"
            )
        }

        LlmResponse(text)
    }
}

@Serializable
private data class GeminiRequest(
    @SerialName("system_instruction") val systemInstruction: GeminiContent? = null,
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig
)

@Serializable
private data class GeminiContent(
    val parts: List<GeminiPart>
)

@Serializable
private data class GeminiPart(
    val text: String = ""
)

@Serializable
private data class GeminiGenerationConfig(
    val temperature: Double,
    val maxOutputTokens: Int,
    @SerialName("response_mime_type") val responseMimeType: String? = null
)

@Serializable
private data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList()
)

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent? = null,
    @SerialName("finishReason") val finishReason: String? = null
)

@Serializable
private data class GeminiErrorResponse(
    val error: GeminiErrorPayload? = null
)

@Serializable
private data class GeminiErrorPayload(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)
