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

class OpenAiLlmProvider(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val settings: LlmTransportSettings = LlmTransportSettings()
) : LlmProvider {
    override val name: String = "openai:$model"

    private val client = buildLlmHttpClient(settings)

    override suspend fun complete(request: LlmRequest): LlmResponse = safeCompletion(name) {
        if (apiKey.isBlank()) {
            return@safeCompletion LlmResponse.failed("API key da OpenAI não configurada")
        }

        val response = client.post("${baseUrl.trimEnd('/')}/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            header("Content-Type", "application/json")
            setBody(
                ChatCompletionsRequest(
                    model = model,
                    messages = listOf(
                        ChatMessage(role = "system", content = request.system),
                        ChatMessage(role = "user", content = request.user)
                    ),
                    temperature = request.temperature,
                    maxTokens = request.maxOutputTokens,
                    responseFormat = if (settings.jsonMode) ResponseFormat("json_object") else null
                )
            )
        }

        response.failureOrNull(name) { raw ->
            runCatching { llmJson.decodeFromString<OpenAiErrorResponse>(raw) }.getOrNull()?.error?.message
        }?.let { return@safeCompletion LlmResponse.failed(it) }

        val raw = response.bodyAsText()
        val parsed = runCatching { llmJson.decodeFromString<ChatCompletionsResponse>(raw) }.getOrNull()
            ?: return@safeCompletion LlmResponse.failed("resposta da OpenAI não pôde ser desserializada")

        val content = parsed.choices.firstOrNull()?.message?.content
        if (content.isNullOrBlank()) {
            return@safeCompletion LlmResponse.failed("OpenAI retornou conteúdo vazio")
        }

        LlmResponse(content)
    }
}

@Serializable
data class ChatCompletionsRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null
)

@Serializable
data class ResponseFormat(val type: String)

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
