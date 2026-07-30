package com.mranalyser

import com.mranalyser.application.service.MergeRecommendationCalculator
import com.mranalyser.domain.model.FindingScope
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MergeRecommendationCalculatorTest {
    private val calculator = MergeRecommendationCalculator()

    @Test
    fun `deve solicitar mudancas quando existe finding bloqueante`() {
        val recommendation = calculator.calculate(listOf(finding(Severity.HIGH, blocking = true)))

        assertEquals(MergeRecommendation.REQUEST_CHANGES, recommendation)
    }

    @Test
    fun `CRITICAL nao bloqueante nao deve solicitar mudancas`() {
        val recommendation = calculator.calculate(listOf(finding(Severity.CRITICAL, blocking = false)))

        assertEquals(
            MergeRecommendation.NEEDS_DISCUSSION,
            recommendation,
            "severidade sem bloqueio vira discussão, não REQUEST_CHANGES"
        )
    }

    @Test
    fun `HIGH nao bloqueante deve exigir discussao`() {
        val recommendation = calculator.calculate(listOf(finding(Severity.HIGH, blocking = false)))

        assertEquals(MergeRecommendation.NEEDS_DISCUSSION, recommendation)
    }

    @Test
    fun `dois questionamentos MEDIUM devem exigir discussao`() {
        val recommendation = calculator.calculate(
            listOf(
                finding(Severity.MEDIUM, type = FindingType.QUESTION),
                finding(Severity.MEDIUM, type = FindingType.RISK)
            )
        )

        assertEquals(MergeRecommendation.NEEDS_DISCUSSION, recommendation)
    }

    @Test
    fun `um unico MEDIUM de design aprova com sugestoes`() {
        val recommendation = calculator.calculate(
            listOf(finding(Severity.MEDIUM, type = FindingType.DESIGN))
        )

        assertEquals(MergeRecommendation.APPROVE_WITH_SUGGESTIONS, recommendation)
    }

    @Test
    fun `impacto em multiplos componentes exige discussao`() {
        val recommendation = calculator.calculate(
            listOf(
                finding(Severity.MEDIUM, type = FindingType.DESIGN)
                    .copy(componentsAffected = listOf("A", "B"))
            )
        )

        assertEquals(MergeRecommendation.NEEDS_DISCUSSION, recommendation)
    }

    @Test
    fun `deve aprovar sem findings`() {
        assertEquals(MergeRecommendation.APPROVE, calculator.calculate(emptyList()))
    }

    @Test
    fun `finding pre-existente nao afeta a recomendacao`() {
        val recommendation = calculator.calculate(
            listOf(finding(Severity.CRITICAL, blocking = true).copy(scope = FindingScope.PRE_EXISTING))
        )

        assertEquals(MergeRecommendation.APPROVE, recommendation)
    }

    @Test
    fun `sugestao do modelo pode elevar no maximo ate NEEDS_DISCUSSION`() {
        val recommendation = calculator.calculate(
            findings = emptyList(),
            llmSuggestion = MergeRecommendation.REQUEST_CHANGES
        )

        assertEquals(
            MergeRecommendation.NEEDS_DISCUSSION,
            recommendation,
            "REQUEST_CHANGES exige finding bloqueante verificado deterministicamente"
        )
    }

    @Test
    fun `sugestao do modelo nao pode rebaixar a decisao deterministica`() {
        val recommendation = calculator.calculate(
            findings = listOf(finding(Severity.HIGH, blocking = true)),
            llmSuggestion = MergeRecommendation.APPROVE
        )

        assertEquals(MergeRecommendation.REQUEST_CHANGES, recommendation)
    }

    @Test
    fun `finding abaixo do limite de confianca nao gera discussao`() {
        val recommendation = calculator.calculate(
            listOf(finding(Severity.HIGH, blocking = false).copy(confidence = 0.4))
        )

        assertEquals(MergeRecommendation.APPROVE_WITH_SUGGESTIONS, recommendation)
    }

    private fun finding(
        severity: Severity,
        type: FindingType = FindingType.RISK,
        blocking: Boolean = false
    ): ReviewFinding = ReviewFinding(
        severity = severity,
        category = ReviewCategory.BUG,
        type = type,
        file = "A.kt",
        line = 1,
        title = "título",
        description = "descrição",
        impact = null,
        recommendation = null,
        suggestedComment = null,
        blocking = blocking,
        confidence = 0.9
    )
}
