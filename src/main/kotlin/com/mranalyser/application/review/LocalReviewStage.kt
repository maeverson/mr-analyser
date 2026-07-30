package com.mranalyser.application.review

import com.mranalyser.application.llm.parser.LlmReviewResult
import com.mranalyser.application.llm.parser.ReviewResponseParser
import com.mranalyser.application.llm.parser.StageResult
import com.mranalyser.application.llm.prompt.LocalReviewPrompt
import com.mranalyser.application.port.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Etapa 2 do pipeline: deep review local, um prompt por chunk, em paralelo.
 *
 * Falha de um chunk é isolada: registra-se o motivo no diagnóstico e a análise continua com os
 * demais, marcada como parcial (item 38). Na V1, uma exceção em qualquer chunk propagava por
 * `awaitAll` e abortava a análise inteira.
 */
class LocalReviewStage(
    private val llmProvider: LlmProvider,
    private val prompt: LocalReviewPrompt = LocalReviewPrompt(),
    private val parser: ReviewResponseParser = ReviewResponseParser(),
    private val maxConcurrency: Int = 4,
    private val maxOutputTokens: Int = 6_000
) {
    suspend fun run(
        inputs: List<ChunkReviewInput>,
        diagnostics: AnalysisDiagnostics
    ): List<LlmReviewResult> = coroutineScope {
        if (inputs.isEmpty()) {
            return@coroutineScope emptyList()
        }

        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))

        inputs.map { input ->
            async(Dispatchers.IO) {
                semaphore.withPermit { reviewChunk(input, diagnostics) }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun reviewChunk(
        input: ChunkReviewInput,
        diagnostics: AnalysisDiagnostics
    ): LlmReviewResult? {
        val label = "chunk ${input.chunkIndex}/${input.chunkCount} [${input.group.name}]"
        val response = llmProvider.complete(prompt.build(input, maxOutputTokens))

        if (!response.successful) {
            diagnostics.chunkFailed(label, response.failure ?: "resposta vazia")
            return null
        }

        return when (val parsed = parser.parseLocalReview(response.text)) {
            is StageResult.Success -> {
                diagnostics.chunkSucceeded()
                if (parsed.value.droppedFindings > 0) {
                    diagnostics.warn(
                        "${parsed.value.droppedFindings} finding(s) do $label descartado(s) por estrutura inválida"
                    )
                }
                parsed.value
            }

            is StageResult.Failure -> {
                diagnostics.chunkFailed(label, parsed.reason)
                null
            }
        }
    }
}
