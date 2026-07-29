package com.mranalyser.infrastructure.llm

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.domain.model.LlmReviewResult
import com.mranalyser.domain.model.ReviewContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AnthropicLlmProvider(
    private val baseUrl: String,
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
        val response = client.post("${baseUrl.trimEnd('/')}/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            header("content-type", "application/json")
            setBody(
                AnthropicRequest(
                    model = model,
                    maxTokens = 2048,
                    temperature = 0.2,
                    messages = listOf(
                        AnthropicMessage(
                            role = "user",
                            content = listOf(AnthropicText(type = "text", text = promptBuilder.build(context)))
                        )
                    )
                )
            )
        }.body<AnthropicResponse>()

        val text = response.content.firstOrNull { it.type == "text" }?.text.orEmpty()
        return parser.parse(text)
    }
}

@Serializable
private data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double,
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
    val text: String
)

@Serializable
private data class AnthropicResponse(
    val content: List<AnthropicText>
)
