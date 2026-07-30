package com.mranalyser.infrastructure.llm

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.port.LlmResponse
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * Decorator de retry com backoff exponencial. Separado do transporte para que cada provider
 * permaneça uma classe trivial e para que a política de retry seja única.
 *
 * Só tenta novamente falhas plausivelmente transitórias — erro de configuração (API key
 * ausente) ou rejeição de payload (HTTP 4xx) não melhoram com repetição e apenas custam tempo.
 */
class ResilientLlmProvider(
    private val delegate: LlmProvider,
    private val maxRetries: Int = 2,
    private val initialBackoffMillis: Long = 1_500,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) : LlmProvider {
    private val logger = LoggerFactory.getLogger(ResilientLlmProvider::class.java)

    override val name: String get() = delegate.name

    override suspend fun complete(request: LlmRequest): LlmResponse {
        var attempt = 0
        var last = delegate.complete(request)

        while (!last.successful && attempt < maxRetries && isTransient(last.failure)) {
            val backoff = initialBackoffMillis shl attempt
            attempt++
            logger.warn(
                "Etapa {} falhou ({}). Tentativa {}/{} em {}ms.",
                request.purpose.label,
                last.failure,
                attempt,
                maxRetries,
                backoff
            )
            sleep(backoff)
            last = delegate.complete(request)
        }

        return last
    }

    private fun isTransient(failure: String?): Boolean {
        if (failure == null) {
            return false
        }
        if (PERMANENT_MARKERS.any { failure.contains(it, ignoreCase = true) }) {
            return false
        }
        return true
    }

    private companion object {
        val PERMANENT_MARKERS = listOf(
            "não configurada",
            "não configurado",
            "HTTP 400",
            "HTTP 401",
            "HTTP 403",
            "HTTP 404",
            "HTTP 422"
        )
    }
}
