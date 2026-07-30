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

class OllamaLlmProvider(
    private val model: String,
    private val baseUrl: String = "http://localhost:11434",
    private val apiKey: String? = null,
    // Inferência self-hosted em diffs grandes é significativamente mais lenta.
    private val settings: LlmTransportSettings = LlmTransportSettings(timeoutSeconds = 600)
) : LlmProvider {
    override val name: String = "ollama:$model"

    private val client = buildLlmHttpClient(settings)

    override suspend fun complete(request: LlmRequest): LlmResponse = safeCompletion(name) {
        val response = client.post(buildGenerateEndpoint(baseUrl)) {
            header("Content-Type", "application/json")
            if (!apiKey.isNullOrBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
            setBody(
                OllamaRequest(
                    model = model,
                    system = request.system,
                    prompt = request.user,
                    stream = false,
                    format = if (settings.jsonMode) "json" else null,
                    options = OllamaOptions(
                        temperature = request.temperature,
                        numPredict = request.maxOutputTokens
                    )
                )
            )
        }

        response.failureOrNull(name) { raw ->
            runCatching { llmJson.decodeFromString<OllamaErrorResponse>(raw) }.getOrNull()?.error
        }?.let { return@safeCompletion LlmResponse.failed(it) }

        val raw = response.bodyAsText()
        val parsed = runCatching { llmJson.decodeFromString<OllamaResponse>(raw) }.getOrNull()
            ?: return@safeCompletion LlmResponse.failed("resposta do Ollama não pôde ser desserializada")

        if (parsed.response.isBlank()) {
            return@safeCompletion LlmResponse.failed("Ollama retornou conteúdo vazio")
        }

        LlmResponse(parsed.response)
    }

    private fun buildGenerateEndpoint(base: String): String {
        val normalized = base.trimEnd('/')
        return if (normalized.endsWith("/api")) "$normalized/generate" else "$normalized/api/generate"
    }
}

@Serializable
private data class OllamaRequest(
    val model: String,
    val prompt: String,
    val system: String? = null,
    val stream: Boolean,
    val format: String? = null,
    val options: OllamaOptions? = null
)

@Serializable
private data class OllamaOptions(
    val temperature: Double,
    @SerialName("num_predict") val numPredict: Int
)

@Serializable
private data class OllamaResponse(
    val response: String = ""
)

@Serializable
private data class OllamaErrorResponse(
    val error: String? = null
)
