package com.mranalyser.infrastructure.llm

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.port.LlmResponse
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Decorator que anuncia cada chamada ao modelo antes de esperar por ela e reporta o custo real
 * depois.
 *
 * Existe por um motivo concreto: o pipeline faz uma dezena de chamadas **sequenciais** quando o
 * provider é self-hosted, e sem nenhuma saída entre `Running AI review...` e o relatório o
 * processo fica indistinguível de um travamento — um MR de 50 arquivos passou uma hora sem
 * imprimir uma linha. `LlmPurpose` e `LlmRequest.label` já existiam para telemetria; aqui eles
 * finalmente viram saída.
 *
 * Fica na **borda externa** da cadeia de decorators: uma linha por chamada lógica, com a duração
 * incluindo eventuais retentativas (que o [ResilientLlmProvider] loga por conta própria).
 */
class ProgressLoggingLlmProvider(
    private val delegate: LlmProvider,
    private val clock: () -> Long = System::nanoTime
) : LlmProvider {
    private val logger = LoggerFactory.getLogger(ProgressLoggingLlmProvider::class.java)

    override val name: String get() = delegate.name

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val target = request.label.ifBlank { request.purpose.label }
        logger.info("[{}] {} -> enviando...", request.purpose.label, target)

        val started = clock()
        val response = delegate.complete(request)
        val elapsed = (clock() - started).nanoseconds

        if (!response.successful) {
            logger.warn(
                "[{}] {} -> falhou em {}: {}",
                request.purpose.label,
                target,
                format(elapsed),
                response.failure ?: "resposta vazia"
            )
            return response
        }

        logger.info(
            "[{}] {} -> ok em {}{}",
            request.purpose.label,
            target,
            format(elapsed),
            response.usage?.let { usage ->
                val perSecond = usage.outputTokens / elapsed.inWholeMilliseconds.coerceAtLeast(1).toDouble() * 1_000
                " (%d tok prompt, %d tok saída, %.1f tok/s)".format(
                    usage.promptTokens,
                    usage.outputTokens,
                    perSecond
                )
            }.orEmpty()
        )

        return response
    }

    private fun format(elapsed: Duration): String {
        val seconds = elapsed.inWholeSeconds
        return if (seconds >= 60) "${seconds / 60}m${(seconds % 60).toString().padStart(2, '0')}s" else "${seconds}s"
    }
}
