package com.mranalyser.application.review

import com.mranalyser.application.llm.parser.FinalAssessmentResult
import com.mranalyser.application.llm.parser.ReviewResponseParser
import com.mranalyser.application.llm.parser.StageResult
import com.mranalyser.application.llm.prompt.FinalAssessmentPrompt
import com.mranalyser.application.port.LlmProvider
import com.mranalyser.domain.model.AnalysisConfidence
import com.mranalyser.domain.model.ArchitecturalSignal
import com.mranalyser.domain.model.ChangeUnderstanding
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.TechnicalOpinion

/**
 * Etapa 5 do pipeline: parecer técnico executivo (itens 21 e 23).
 *
 * Recebe apenas os findings já validados, nunca o diff — o objetivo é sintetizar a posição do
 * revisor, não abrir uma nova rodada de detecção que escaparia da validação.
 */
class FinalAssessmentStage(
    private val llmProvider: LlmProvider,
    private val prompt: FinalAssessmentPrompt = FinalAssessmentPrompt(),
    private val parser: ReviewResponseParser = ReviewResponseParser(),
    private val maxOutputTokens: Int = 1_600
) {
    suspend fun run(
        overview: MergeRequestOverview,
        understanding: ChangeUnderstanding?,
        signals: List<ArchitecturalSignal>,
        findings: List<ReviewFinding>,
        positivePoints: List<String>,
        openQuestions: List<String>,
        diagnostics: AnalysisDiagnostics
    ): FinalAssessmentResult {
        val input = FinalAssessmentInput(
            overview = overview,
            understanding = understanding,
            architecturalSignals = signals,
            findings = findings,
            positivePoints = positivePoints,
            openQuestions = openQuestions,
            degraded = diagnostics.degraded,
            degradationReasons = diagnostics.degradationReasons
        )

        val response = llmProvider.complete(prompt.build(input, maxOutputTokens))
        if (!response.successful) {
            diagnostics.skipStage("parecer técnico", response.failure ?: "resposta vazia")
            return deterministicFallback(findings, diagnostics)
        }

        return when (val parsed = parser.parseFinalAssessment(response.text)) {
            is StageResult.Success -> parsed.value.withConfidenceCappedByDegradation(diagnostics)
            is StageResult.Failure -> {
                diagnostics.skipStage("parecer técnico", parsed.reason)
                deterministicFallback(findings, diagnostics)
            }
        }
    }

    /** Análise parcial não pode declarar confiança alta, mesmo que o modelo diga o contrário. */
    private fun FinalAssessmentResult.withConfidenceCappedByDegradation(
        diagnostics: AnalysisDiagnostics
    ): FinalAssessmentResult {
        if (!diagnostics.degraded || opinion.analysisConfidence != AnalysisConfidence.HIGH) {
            return this
        }
        return copy(opinion = opinion.copy(analysisConfidence = AnalysisConfidence.MEDIUM))
    }

    /**
     * Parecer determinístico quando o LLM não está disponível. Descreve o que foi encontrado
     * sem inventar julgamento que não pode ser sustentado.
     */
    private fun deterministicFallback(
        findings: List<ReviewFinding>,
        diagnostics: AnalysisDiagnostics
    ): FinalAssessmentResult {
        val blockers = findings.count { it.blocking }
        val opinion = when {
            findings.isEmpty() ->
                "Não foi possível produzir parecer com apoio do modelo. As verificações determinísticas " +
                    "executadas não apontaram problema material, mas a análise é parcial e não substitui revisão manual."

            blockers > 0 ->
                "Não foi possível produzir parecer com apoio do modelo. Foram registrados $blockers ponto(s) " +
                    "bloqueante(s) e ${findings.size - blockers} ponto(s) não bloqueante(s) que merecem revisão manual."

            else ->
                "Não foi possível produzir parecer com apoio do modelo. Foram registrados ${findings.size} " +
                    "ponto(s) não bloqueante(s) para revisão manual."
        }

        return FinalAssessmentResult(
            opinion = TechnicalOpinion(
                opinion = opinion,
                mainRisk = findings.firstOrNull { it.blocking }?.title
                    ?: findings.maxByOrNull { it.severity.weight }?.title,
                analysisConfidence = AnalysisConfidence.LOW
            ),
            suggestedRecommendation = null,
            questions = emptyList(),
            positivePoints = emptyList()
        ).also {
            diagnostics.warn("parecer técnico gerado sem apoio do modelo")
        }
    }
}
