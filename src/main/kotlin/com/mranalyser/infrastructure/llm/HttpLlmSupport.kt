package com.mranalyser.infrastructure.llm

import com.mranalyser.application.port.LlmResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Configuração de transporte comum aos providers. A V1 tinha timeout infinito em Anthropic e
 * Gemini e nenhum tratamento de erro, o que fazia uma única falha de chunk derrubar a análise
 * inteira via `awaitAll`.
 */
data class LlmTransportSettings(
    val timeoutSeconds: Long = 180,
    val connectTimeoutSeconds: Long = 30,
    /**
     * Ativa o modo JSON nativo do provider (`response_format` / `format`). Desligado por padrão:
     * vários gateways "OpenAI-compatible" e proxies de Ollama respondem 400 ao receber o campo,
     * e um 400 é falha permanente. O parsing robusto cobre o caso sem esse risco.
     */
    val jsonMode: Boolean = false
)

internal val llmJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun buildLlmHttpClient(settings: LlmTransportSettings): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    install(ContentNegotiation) { json(llmJson) }
    install(HttpTimeout) {
        requestTimeoutMillis = settings.timeoutSeconds * 1_000
        connectTimeoutMillis = settings.connectTimeoutSeconds * 1_000
        socketTimeoutMillis = settings.timeoutSeconds * 1_000
    }
}

/**
 * Executa a chamada convertendo qualquer exceção em [LlmResponse.failed]. Garante o contrato
 * da porta: o provider nunca lança.
 */
internal suspend fun safeCompletion(
    providerName: String,
    block: suspend () -> LlmResponse
): LlmResponse = runCatching { block() }.getOrElse { throwable ->
    LlmResponse.failed("$providerName indisponível: ${throwable::class.simpleName}: ${throwable.message}")
}

internal suspend fun HttpResponse.failureOrNull(providerName: String, errorField: (String) -> String?): String? {
    if (status.isSuccess()) {
        return null
    }
    val raw = runCatching { bodyAsText() }.getOrElse { "" }
    val detail = errorField(raw) ?: raw.take(500)
    return "$providerName retornou HTTP ${status.value}: $detail"
}
