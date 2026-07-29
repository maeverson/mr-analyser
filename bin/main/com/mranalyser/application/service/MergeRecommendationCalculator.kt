package com.mranalyser.application.service

import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

class MergeRecommendationCalculator {
    fun calculate(findings: List<ReviewFinding>): MergeRecommendation {
        if (findings.any { it.severity == Severity.CRITICAL }) {
            return MergeRecommendation.REQUEST_CHANGES
        }
        if (findings.count { it.severity == Severity.HIGH } >= 2) {
            return MergeRecommendation.REQUEST_CHANGES
        }
        if (findings.any { it.severity == Severity.HIGH }) {
            return MergeRecommendation.APPROVE_WITH_SUGGESTIONS
        }
        if (findings.any { it.severity == Severity.MEDIUM || it.severity == Severity.LOW }) {
            return MergeRecommendation.APPROVE_WITH_SUGGESTIONS
        }
        return MergeRecommendation.APPROVE
    }
}
