package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.AnalysisConfidence
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport

/**
 * Texto compartilhado pelos renderizadores. Centralizado para que console, markdown e
 * comentários de GitLab não divirjam na forma de descrever o mesmo parecer.
 */
internal object ReviewNarrative {

    fun recommendationLabel(recommendation: MergeRecommendation): String = when (recommendation) {
        MergeRecommendation.APPROVE -> "APPROVE"
        MergeRecommendation.APPROVE_WITH_SUGGESTIONS -> "APPROVE_WITH_SUGGESTIONS"
        MergeRecommendation.NEEDS_DISCUSSION -> "NEEDS_DISCUSSION"
        MergeRecommendation.REQUEST_CHANGES -> "REQUEST_CHANGES"
    }

    fun recommendationExplanation(recommendation: MergeRecommendation): String = when (recommendation) {
        MergeRecommendation.APPROVE ->
            "Nenhum problema material identificado."
        MergeRecommendation.APPROVE_WITH_SUGGESTIONS ->
            "Somente melhorias não bloqueantes."
        MergeRecommendation.NEEDS_DISCUSSION ->
            "Sem evidência suficiente para solicitar mudança, mas existem decisões que precisam de esclarecimento."
        MergeRecommendation.REQUEST_CHANGES ->
            "Existe ao menos um ponto bloqueante com evidência e cenário de falha."
    }

    fun confidenceLabel(confidence: AnalysisConfidence?): String = when (confidence) {
        AnalysisConfidence.HIGH -> "HIGH"
        AnalysisConfidence.MEDIUM -> "MEDIUM"
        AnalysisConfidence.LOW -> "LOW"
        null -> "não avaliada"
    }

    fun mainRisk(report: ReviewReport): String? {
        report.opinion?.mainRisk?.takeIf { it.isNotBlank() }?.let { return it }
        return report.blockingFindings.firstOrNull()?.title
            ?: report.questionFindings.firstOrNull()?.title
    }

    /**
     * Resumo de 30 segundos. Vem do modelo quando a etapa final rodou; senão, é montado a partir
     * dos itens de "onde ter cuidado", agrupados por arquivo — pior redação, mesma informação.
     */
    fun attentionAreas(report: ReviewReport, max: Int = 4): List<String> {
        report.opinion?.attentionAreas?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let {
            return it.take(max)
        }
        return report.carefulFindings
            .groupBy { it.file?.substringAfterLast('/') ?: "geral" }
            .entries
            .take(max)
            .map { (file, findings) -> "$file — ${findings.joinToString("; ") { it.title }}" }
    }

    /** "O que conferir": a recomendação já vem no imperativo; sem ela, a descrição. */
    fun whatToCheck(finding: ReviewFinding): String = finding.recommendation?.takeIf { it.isNotBlank() }
        ?: finding.description

    /** "Por quê": o impacto; sem ele, a descrição (quando ela não foi usada como "o que conferir"). */
    fun why(finding: ReviewFinding): String? = finding.impact?.takeIf { it.isNotBlank() }
        ?: finding.description.takeIf { !finding.recommendation.isNullOrBlank() }

    /** Uma linha de item menor: `arquivo:linha — título`. */
    fun minorLine(finding: ReviewFinding): String = "${finding.location} — ${finding.title}"

    /** Cabeçalho de um finding: `[HIGH/BUG] arquivo:linha`. */
    fun findingHeader(finding: ReviewFinding): String =
        "[${finding.severity.name}/${finding.type.name}] ${finding.location}"

    /** Numera as etapas do cenário de falha preservando quebras já presentes no texto. */
    fun failureScenarioLines(failureScenario: String): List<String> =
        failureScenario
            .split('\n', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    fun formatConfidence(value: Double): String = "%.0f%%".format(value * 100)
}
