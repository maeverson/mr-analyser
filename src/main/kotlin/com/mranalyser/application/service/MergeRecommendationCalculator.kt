package com.mranalyser.application.service

import com.mranalyser.domain.model.FindingScope
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

/**
 * Decide a recomendação de merge (item 12).
 *
 * O critério da V1 era puramente contagem de severidade e internamente contraditório
 * (1 HIGH aprovava com sugestões, 2 HIGH bloqueavam). Aqui a decisão parte de `blocking`,
 * que já é função de severidade × tipo × confiança × cenário de falha
 * ([com.mranalyser.domain.policy.BlockingPolicy]).
 *
 * A sugestão do LLM entra apenas como escalada limitada: se o modelo pede `REQUEST_CHANGES`
 * mas nenhum finding satisfaz os critérios de bloqueio, o resultado é `NEEDS_DISCUSSION` —
 * não se perde o sinal nem se bloqueia sem evidência.
 */
class MergeRecommendationCalculator(
    private val discussionConfidenceThreshold: Double = 0.60
) {
    fun calculate(
        findings: List<ReviewFinding>,
        openQuestions: List<String> = emptyList(),
        llmSuggestion: MergeRecommendation? = null
    ): MergeRecommendation {
        val introduced = findings.filter { it.scope == FindingScope.INTRODUCED }

        if (introduced.any { it.blocking }) {
            return MergeRecommendation.REQUEST_CHANGES
        }

        val deserved = deservedWithoutBlockers(introduced, openQuestions)
        return escalate(deserved, llmSuggestion)
    }

    private fun deservedWithoutBlockers(
        introduced: List<ReviewFinding>,
        openQuestions: List<String>
    ): MergeRecommendation {
        val material = introduced.filter {
            it.severity.atLeast(Severity.MEDIUM) && it.confidence >= discussionConfidenceThreshold
        }

        val highWithoutProof = material.any { it.severity.atLeast(Severity.HIGH) }
        val mediumQuestions = material.count {
            it.type == FindingType.QUESTION || it.type == FindingType.RISK || it.type == FindingType.ARCHITECTURE
        }
        val broadImpact = material.any { it.componentsAffected.size >= 2 }

        return when {
            highWithoutProof -> MergeRecommendation.NEEDS_DISCUSSION
            mediumQuestions >= 2 -> MergeRecommendation.NEEDS_DISCUSSION
            broadImpact -> MergeRecommendation.NEEDS_DISCUSSION
            material.isNotEmpty() -> MergeRecommendation.APPROVE_WITH_SUGGESTIONS
            introduced.isNotEmpty() || openQuestions.isNotEmpty() -> MergeRecommendation.APPROVE_WITH_SUGGESTIONS
            else -> MergeRecommendation.APPROVE
        }
    }

    /**
     * O modelo pode elevar no máximo até `NEEDS_DISCUSSION`. `REQUEST_CHANGES` exige finding
     * bloqueante, verificado deterministicamente.
     */
    private fun escalate(
        deserved: MergeRecommendation,
        llmSuggestion: MergeRecommendation?
    ): MergeRecommendation {
        if (llmSuggestion == null) {
            return deserved
        }
        val ceiling = when (llmSuggestion) {
            MergeRecommendation.REQUEST_CHANGES -> MergeRecommendation.NEEDS_DISCUSSION
            else -> llmSuggestion
        }
        return if (ceiling.ordinal > deserved.ordinal) ceiling else deserved
    }
}
