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
import kotlinx.coroutines.CancellationException

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
    val jsonMode: Boolean = false,
    /**
     * Janela de contexto pedida ao provider self-hosted (`num_ctx` no Ollama). É o cliente que
     * sabe o tamanho do prompt que produz, então é ele quem deve dimensionar a janela — deixar o
     * default do Modelfile decidir foi o que estourou a VRAM do servidor e jogou um terço das
     * camadas do modelo para a CPU. `null` mantém o valor do próprio modelo.
     */
    val numCtx: Int? = null,
    val thinking: ThinkingMode = ThinkingMode.UNSET,
    /** Tokens somados ao `num_predict` quando o raciocínio está ligado: o pensamento conta no limite. */
    val reasoningTokens: Int = 4_000
)

/**
 * Controle do modo de raciocínio de modelos que o suportam (qwen3, gpt-oss, deepseek-r1).
 *
 * [UNSET] não envia o campo — obrigatório para modelos sem suporte, como o qwen2.5-coder, que
 * podem recusar a requisição. [PER_STAGE] liga só onde [com.mranalyser.application.port.LlmRequest.reasoning]
 * pede: nas etapas de volume (um chunk por chamada) o raciocínio multiplicaria o tempo e
 * consumiria o `num_predict` antes do JSON.
 */
enum class ThinkingMode {
    UNSET,
    OFF,
    PER_STAGE,
    ON;

    fun enabledFor(reasoningRequested: Boolean): Boolean? = when (this) {
        UNSET -> null
        OFF -> false
        ON -> true
        PER_STAGE -> reasoningRequested
    }

    companion object {
        fun parse(raw: String?): ThinkingMode = when (raw?.trim()?.lowercase()) {
            null, "", "none", "unset" -> UNSET
            "off", "false" -> OFF
            "on", "true" -> ON
            "stage", "per-stage", "per_stage", "validation" -> PER_STAGE
            else -> UNSET
        }
    }
}

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
 * Cliente para respostas em streaming. Não impõe teto de **duração total**: o limite passa a ser
 * o intervalo *sem dados* (socket timeout).
 *
 * Um teto total é a medida errada para inferência self-hosted. Um 14B parcialmente em CPU gera
 * na ordem de 10 tokens/s; com `num_predict` de alguns milhares, a geração legítima ultrapassa
 * qualquer `requestTimeout` plausível, e a análise falhava por lentidão — não por indisponibilidade.
 * O servidor travado continua detectado, porque nesse caso ele para de emitir tokens.
 */
internal fun buildStreamingLlmHttpClient(settings: LlmTransportSettings): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    install(ContentNegotiation) { json(llmJson) }
    install(HttpTimeout) {
        requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
        connectTimeoutMillis = settings.connectTimeoutSeconds * 1_000
        socketTimeoutMillis = settings.timeoutSeconds * 1_000
    }
}

/**
 * Executa a chamada convertendo qualquer exceção em [LlmResponse.failed]. Garante o contrato
 * da porta para falhas de transporte. Cancelamento é propagado para interromper o pipeline.
 */
internal suspend fun safeCompletion(
    providerName: String,
    block: suspend () -> LlmResponse
): LlmResponse = runCatching { block() }.getOrElse { throwable ->
    if (throwable is CancellationException) throw throwable
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
