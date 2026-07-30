package com.mranalyser.application.review

import com.mranalyser.application.llm.parser.CrossFileResult
import com.mranalyser.application.llm.parser.ReviewResponseParser
import com.mranalyser.application.llm.parser.StageResult
import com.mranalyser.application.llm.prompt.CrossFileReviewPrompt
import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.RelatedFileContext
import com.mranalyser.domain.model.ArchitecturalSignal
import com.mranalyser.domain.model.ChangeUnderstanding
import com.mranalyser.domain.model.ParsedDiff
import com.mranalyser.domain.model.ReviewFinding

/**
 * Etapa 4 do pipeline: análise global (itens 7 e 27).
 *
 * Existe porque a V1 concatenava resultados de chunks independentes. Aqui o modelo recebe a
 * visão conjunta e pode: encontrar inconsistência entre dois arquivos, e invalidar findings que
 * a visão ampliada refuta.
 */
class CrossFileReviewStage(
    private val llmProvider: LlmProvider,
    private val prompt: CrossFileReviewPrompt = CrossFileReviewPrompt(),
    private val parser: ReviewResponseParser = ReviewResponseParser(),
    private val maxOutputTokens: Int = 6_000,
    private val maxCharsPerFile: Int = 2_500,
    private val maxFiles: Int = 30
) {
    data class Outcome(
        val summary: String,
        val newFindings: List<ReviewFinding>,
        val invalidatedTitles: List<String>,
        val questions: List<String>,
        val positivePoints: List<String>
    ) {
        companion object {
            val EMPTY = Outcome("", emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    suspend fun run(
        overview: MergeRequestOverview,
        understanding: ChangeUnderstanding?,
        signals: List<ArchitecturalSignal>,
        confirmedFindings: List<ReviewFinding>,
        relations: List<FileRelation>,
        parsedDiffs: Map<String, ParsedDiff>,
        relatedContext: List<RelatedFileContext>,
        diagnostics: AnalysisDiagnostics
    ): Outcome {
        // Com um único arquivo não há visão cross-file a construir.
        if (overview.files.size < 2) {
            diagnostics.skipStage("análise cross-file", "MR com apenas um arquivo relevante")
            return Outcome.EMPTY
        }

        val input = CrossFileReviewInput(
            overview = overview,
            understanding = understanding,
            architecturalSignals = signals,
            confirmedFindings = confirmedFindings,
            relationEdges = relations,
            addedLinesByFile = addedLinesByFile(overview, parsedDiffs),
            relatedContext = relatedContext
        )

        val response = llmProvider.complete(prompt.build(input, maxOutputTokens))
        if (!response.successful) {
            diagnostics.skipStage("análise cross-file", response.failure ?: "resposta vazia")
            return Outcome.EMPTY
        }

        return when (val parsed = parser.parseCrossFileReview(response.text)) {
            is StageResult.Success -> parsed.value.toOutcome()
            is StageResult.Failure -> {
                diagnostics.skipStage("análise cross-file", parsed.reason)
                Outcome.EMPTY
            }
        }
    }

    private fun CrossFileResult.toOutcome() = Outcome(
        summary = summary,
        newFindings = newFindings,
        invalidatedTitles = invalidatedTitles,
        questions = questions,
        positivePoints = positivePoints
    )

    /**
     * Só as linhas adicionadas, e apenas dos arquivos de produção. Enviar o diff completo aqui
     * duplicaria o custo da etapa local sem melhorar o raciocínio entre arquivos.
     */
    private fun addedLinesByFile(
        overview: MergeRequestOverview,
        parsedDiffs: Map<String, ParsedDiff>
    ): Map<String, String> = overview.files
        .sortedByDescending { it.group.isProductionCode }
        .take(maxFiles)
        .mapNotNull { file ->
            val added = (parsedDiffs[file.path] ?: ParsedDiff.EMPTY)
                .addedLines
                .joinToString("\n") { line -> "${line.newLine?.toString()?.padStart(5).orEmpty()} | ${line.content}" }
                .take(maxCharsPerFile)

            added.takeIf { it.isNotBlank() }?.let { file.path to it }
        }
        .toMap()
}
