package com.mranalyser.application.review

import com.mranalyser.application.llm.parser.ReviewResponseParser
import com.mranalyser.application.llm.parser.StageResult
import com.mranalyser.application.llm.prompt.UnderstandingPrompt
import com.mranalyser.application.port.LlmProvider
import com.mranalyser.domain.model.ArchitecturalSignal
import com.mranalyser.domain.model.ChangeUnderstanding
import com.mranalyser.domain.model.ParsedDiff
import org.slf4j.LoggerFactory

/**
 * Etapa 1 do pipeline: entendimento da alteração (item 4).
 *
 * Roda antes do review para que cada chunk seja avaliado com noção de intenção e blast radius.
 * Se falhar, o pipeline continua sem entendimento — a análise fica pior, não interrompida.
 */
class ChangeUnderstandingStage(
    private val llmProvider: LlmProvider,
    private val prompt: UnderstandingPrompt = UnderstandingPrompt(),
    private val parser: ReviewResponseParser = ReviewResponseParser(),
    private val maxOutputTokens: Int = 2_000,
    private val maxDigestCharsPerFile: Int = 1_200,
    private val maxDigestFiles: Int = 40
) {
    private val logger = LoggerFactory.getLogger(ChangeUnderstandingStage::class.java)

    suspend fun run(
        overview: MergeRequestOverview,
        signals: List<ArchitecturalSignal>,
        parsedDiffs: Map<String, ParsedDiff>,
        diagnostics: AnalysisDiagnostics
    ): ChangeUnderstanding? {
        val request = prompt.build(
            overview = overview,
            signals = signals,
            diffDigest = buildDigest(overview, parsedDiffs),
            maxOutputTokens = maxOutputTokens
        )

        val response = llmProvider.complete(request)
        if (!response.successful) {
            diagnostics.skipStage("entendimento da alteração", response.failure ?: "resposta vazia")
            return null
        }

        return when (val parsed = parser.parseUnderstanding(response.text)) {
            is StageResult.Success -> parsed.value.understanding
            is StageResult.Failure -> {
                logger.debug("Entendimento não interpretado: {}", parsed.reason)
                diagnostics.skipStage("entendimento da alteração", parsed.reason)
                null
            }
        }
    }

    /**
     * Resumo das linhas adicionadas por arquivo. Enviar o diff completo aqui gastaria contexto
     * sem melhorar a resposta: a etapa precisa de intenção e escopo, não de detalhe de linha.
     */
    private fun buildDigest(overview: MergeRequestOverview, parsedDiffs: Map<String, ParsedDiff>): String =
        overview.files.take(maxDigestFiles).joinToString("\n\n") { file ->
            val parsed = parsedDiffs[file.path] ?: ParsedDiff.EMPTY
            val added = parsed.addedLines
                .map { it.content.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .take(maxDigestCharsPerFile)

            buildString {
                appendLine("### ${file.path} [${file.group.name}]")
                if (added.isBlank()) {
                    appendLine("(sem linhas adicionadas)")
                } else {
                    appendLine(added)
                }
            }.trimEnd()
        }
}
