package com.mranalyser.infrastructure.llm

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.domain.model.LlmReviewResult
import com.mranalyser.domain.model.ReviewContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GeminiLlmProvider(
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
        val endpoint = "${baseUrl.trimEnd('/')}/v1beta/models/$model:generateContent?key=$apiKey"
        val response = client.post(endpoint) {
            setBody(
                GeminiRequest(
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(GeminiPart(text = promptBuilder.build(context)))
                        )
                    ),
                    generationConfig = GeminiGenerationConfig(
                        temperature = 0.2,
                        maxOutputTokens = 2048
                    )
                )
            )
        }.body<GeminiResponse>()

        val text = response.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            .orEmpty()

        return parser.parse(text)
    }
}

@Serializable
private data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig
)

@Serializable
private data class GeminiContent(
    val parts: List<GeminiPart>
)

@Serializable
private data class GeminiPart(
    val text: String
)

@Serializable
private data class GeminiGenerationConfig(
    val temperature: Double,
    val maxOutputTokens: Int
)

@Serializable
private data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList()
)

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent
)
