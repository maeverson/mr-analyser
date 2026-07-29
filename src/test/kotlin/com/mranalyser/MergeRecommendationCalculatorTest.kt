package com.mranalyser

import com.mranalyser.application.service.MergeRecommendationCalculator
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MergeRecommendationCalculatorTest {
    private val calculator = MergeRecommendationCalculator()

    @Test
    fun `should request changes when critical exists`() {
        val recommendation = calculator.calculate(listOf(finding(Severity.CRITICAL)))
        assertEquals(MergeRecommendation.REQUEST_CHANGES, recommendation)
    }

    @Test
    fun `should approve with suggestions when medium exists`() {
        val recommendation = calculator.calculate(listOf(finding(Severity.MEDIUM)))
        assertEquals(MergeRecommendation.APPROVE_WITH_SUGGESTIONS, recommendation)
    }

    @Test
    fun `should approve with no findings`() {
        val recommendation = calculator.calculate(emptyList())
        assertEquals(MergeRecommendation.APPROVE, recommendation)
    }

    private fun finding(severity: Severity): ReviewFinding {
        return ReviewFinding(
            severity = severity,
            category = ReviewCategory.BUG,
            file = "A.kt",
            line = 1,
            title = "title",
            description = "description",
            impact = null,
            recommendation = null,
            suggestedComment = null,
            confidence = 0.9
        )
    }
}
