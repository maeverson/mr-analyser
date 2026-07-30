package com.mranalyser.application.llm.parser

import com.mranalyser.domain.model.AnalysisConfidence
import com.mranalyser.domain.model.ChangeUnderstanding
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import com.mranalyser.domain.model.TechnicalOpinion

/** Resultado de uma etapa LLM, com falha explícita em vez de exceção. */
sealed interface StageResult<out T> {
    data class Success<T>(val value: T) : StageResult<T>
    data class Failure(val reason: String) : StageResult<Nothing>

    fun valueOrNull(): T? = (this as? Success)?.value
    fun failureOrNull(): String? = (this as? Failure)?.reason
}

/** Saída da etapa de review local por chunk. */
data class LlmReviewResult(
    val summary: String = "",
    val findings: List<ReviewFinding> = emptyList(),
    val questions: List<String> = emptyList(),
    val positivePoints: List<String> = emptyList(),
    val suggestedRecommendation: MergeRecommendation? = null,
    /** Findings ignorados no parsing (ex.: sem título). Alimenta o quality gate. */
    val droppedFindings: Int = 0
)

/** Saída da etapa de entendimento. */
data class UnderstandingResult(
    val understanding: ChangeUnderstanding
)

enum class ValidationDecision {
    KEEP,
    DOWNGRADE_TO_QUESTION,
    DISCARD
}

data class FindingVerdict(
    val candidateId: String,
    val decision: ValidationDecision,
    val rationale: String? = null,
    val severity: Severity? = null,
    val confidence: Double? = null,
    val blocking: Boolean? = null,
    val evidence: String? = null,
    val failureScenario: String? = null,
    val impact: String? = null,
    val recommendation: String? = null,
    val suggestedComment: String? = null,
    val commentTypeRaw: String? = null,
    val scopeRaw: String? = null
)

data class ValidationResult(
    val verdicts: List<FindingVerdict>
)

data class CrossFileResult(
    val summary: String = "",
    val newFindings: List<ReviewFinding> = emptyList(),
    val invalidatedTitles: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
    val positivePoints: List<String> = emptyList()
)

data class FinalAssessmentResult(
    val opinion: TechnicalOpinion,
    val suggestedRecommendation: MergeRecommendation? = null,
    val questions: List<String> = emptyList(),
    val positivePoints: List<String> = emptyList()
) {
    companion object {
        fun fallback(reason: String): FinalAssessmentResult = FinalAssessmentResult(
            opinion = TechnicalOpinion(
                opinion = reason,
                mainRisk = null,
                analysisConfidence = AnalysisConfidence.LOW
            )
        )
    }
}
