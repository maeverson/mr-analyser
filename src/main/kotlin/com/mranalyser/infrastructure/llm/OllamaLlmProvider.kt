package com.mranalyser.infrastructure.llm

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.domain.model.LlmReviewResult
import com.mranalyser.domain.model.ReviewContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OllamaLlmProvider(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String? = null,
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    private val parser: LlmResponseParser = LlmResponseParser()
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            // Self-hosted inference may take significantly longer on large diffs.
            requestTimeoutMillis = 10 * 60 * 1000
            connectTimeoutMillis = 30 * 1000
            socketTimeoutMillis = 10 * 60 * 1000
        }
    }

    override suspend fun analyse(context: ReviewContext): LlmReviewResult {
        return runCatching {
            val endpoint = buildGenerateEndpoint(baseUrl)
            val response = client.post(endpoint) {
                header("Content-Type", "application/json")
                if (!apiKey.isNullOrBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
                setBody(
                    OllamaRequest(
                        model = model,
                        prompt = promptBuilder.build(context),
                        stream = false
                    )
                )
            }

            val rawBody = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val errorPayload = runCatching { json.decodeFromString<OllamaErrorResponse>(rawBody) }.getOrNull()
                val errorMessage = errorPayload?.error ?: rawBody
                return LlmReviewResult(
                    summary = "Analise de IA nao executada: falha no Ollama (${response.status.value}). $errorMessage",
                    findings = emptyList(),
                    questions = emptyList(),
                    positivePoints = emptyList()
                )
            }

            val parsed = runCatching { json.decodeFromString<OllamaResponse>(rawBody) }.getOrNull()
            val content = parsed?.response
            if (content.isNullOrBlank()) {
                return LlmReviewResult(
                    summary = "Analise de IA concluida sem conteudo estruturado retornado pelo Ollama.",
                    findings = emptyList(),
                    questions = emptyList(),
                    positivePoints = emptyList()
                )
            }

            parser.parse(content)
        }.getOrElse { exception ->
            LlmReviewResult(
                summary = "Analise de IA nao executada por erro de comunicacao com Ollama: ${exception.message}",
                findings = emptyList(),
                questions = emptyList(),
                positivePoints = emptyList()
            )
        }
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
    val stream: Boolean
)

@Serializable
private data class OllamaResponse(
    val response: String = ""
)

@Serializable
private data class OllamaErrorResponse(
    val error: String? = null
)
