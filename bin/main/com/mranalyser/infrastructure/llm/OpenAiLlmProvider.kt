package com.mranalyser.infrastructure.llm

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.domain.model.LlmReviewResult
import com.mranalyser.domain.model.ReviewContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OpenAiLlmProvider(
    private val baseUrl: String = "https://api.openai.com/v1",
    private val apiKey: String,
    private val model: String,
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    private val parser: LlmResponseParser = LlmResponseParser()
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    override suspend fun analyse(context: ReviewContext): LlmReviewResult {
        if (apiKey.isBlank()) {
            return LlmReviewResult(
                summary = "Analise de IA nao executada: API key ausente.",
                findings = emptyList(),
                questions = emptyList(),
                positivePoints = emptyList()
            )
        }

        val request = ChatCompletionsRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = "You are a strict and practical code reviewer."),
                ChatMessage(role = "user", content = promptBuilder.build(context))
            ),
            temperature = 0.2
        )

        return runCatching {
            val response = client.post("${baseUrl.trimEnd('/')}/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                header("Content-Type", "application/json")
                setBody(request)
            }

            val rawBody = response.bodyAsText()

            if (!response.status.isSuccess()) {
                val errorPayload = runCatching { json.decodeFromString<OpenAiErrorResponse>(rawBody) }.getOrNull()
                val errorMessage = errorPayload?.error?.message ?: rawBody
                return LlmReviewResult(
                    summary = "Analise de IA nao executada: falha na API OpenAI (${response.status.value}). $errorMessage",
                    findings = emptyList(),
                    questions = emptyList(),
                    positivePoints = emptyList()
                )
            }

            val parsed = runCatching { json.decodeFromString<ChatCompletionsResponse>(rawBody) }.getOrNull()
            val content = parsed?.choices?.firstOrNull()?.message?.content

            if (content.isNullOrBlank()) {
                return LlmReviewResult(
                    summary = "Analise de IA concluida sem conteudo estruturado retornado pelo provedor.",
                    findings = emptyList(),
                    questions = emptyList(),
                    positivePoints = emptyList()
                )
            }

            parser.parse(content)
        }.getOrElse { exception ->
            LlmReviewResult(
                summary = "Analise de IA nao executada por erro de comunicacao com o provedor: ${exception.message}",
                findings = emptyList(),
                questions = emptyList(),
                positivePoints = emptyList()
            )
        }
    }
}

@Serializable
data class ChatCompletionsRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatCompletionsResponse(
    val choices: List<Choice> = emptyList()
)

@Serializable
data class Choice(
    val message: ChatMessage
)

@Serializable
data class OpenAiErrorResponse(
    val error: OpenAiErrorPayload? = null
)

@Serializable
data class OpenAiErrorPayload(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)
