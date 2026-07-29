package com.mranalyser.application.service

import com.mranalyser.domain.model.Discussion
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding

class FindingDeduplicator {
    fun deduplicate(findings: List<ReviewFinding>, discussions: List<Discussion>): List<ReviewFinding> {
        val existingTexts = discussions
            .flatMap { it.notes }
            .map { normalize(it.body) }
            .toSet()

        val filtered = findings
            .filterNot { isLikelyFalsePositive(it) }
            .sortedByDescending { it.confidence }

        val unique = mutableListOf<ReviewFinding>()

        filtered.forEach { finding ->
            val similarToDiscussion = finding.suggestedComment
                ?.let { comment ->
                    existingTexts.any { discussionText -> semanticSimilarity(normalize(comment), discussionText) >= 0.84 }
                }
                ?: false

            if (similarToDiscussion) {
                return@forEach
            }

            val duplicated = unique.any { existing -> isSemanticDuplicate(existing, finding) }
            if (!duplicated) {
                unique += finding
            }
        }

        return unique
    }

    private fun isSemanticDuplicate(a: ReviewFinding, b: ReviewFinding): Boolean {
        if (a.category != b.category) {
            return false
        }

        if (a.file != b.file) {
            return false
        }

        if (a.line != null && b.line != null && kotlin.math.abs(a.line - b.line) > 3) {
            return false
        }

        val textA = normalize("${a.title} ${a.description}")
        val textB = normalize("${b.title} ${b.description}")
        val semantic = semanticSimilarity(textA, textB)
        if (semantic >= 0.72) {
            return true
        }

        val recA = normalize(a.recommendation.orEmpty())
        val recB = normalize(b.recommendation.orEmpty())
        val recommendationAligned = recA.isNotBlank() && recA == recB

        val titleTokenOverlap = tokenOverlap(normalize(a.title), normalize(b.title))
        return recommendationAligned && titleTokenOverlap >= 0.40
    }

    private fun isLikelyFalsePositive(finding: ReviewFinding): Boolean {
        if (finding.confidence < 0.35) {
            return true
        }

        val text = normalize("${finding.title} ${finding.description}")
        if (text.length < 24) {
            return true
        }

        val hasImpact = !finding.impact.isNullOrBlank()
        val vaguePattern = Regex("(?i)(talvez|poderia|considerar|melhorar nome|adicionar comentario|criar constante)")

        return when (finding.category) {
            ReviewCategory.CODE_STYLE -> !hasImpact
            ReviewCategory.DOCUMENTATION -> !hasImpact && finding.confidence < 0.80
            ReviewCategory.MAINTAINABILITY -> vaguePattern.containsMatchIn(text) && !hasImpact
            else -> false
        }
    }

    private fun semanticSimilarity(a: String, b: String): Double {
        if (a == b) {
            return 1.0
        }

        val tokensA = tokens(a)
        val tokensB = tokens(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) {
            return 0.0
        }

        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val union = tokensA.union(tokensB).size.toDouble()
        val jaccard = if (union == 0.0) 0.0 else intersection / union

        val levenshtein = normalizedLevenshtein(a, b)
        return (jaccard * 0.7) + (levenshtein * 0.3)
    }

    private fun tokenOverlap(a: String, b: String): Double {
        val tokensA = tokens(a)
        val tokensB = tokens(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) {
            return 0.0
        }
        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val minSize = minOf(tokensA.size, tokensB.size).toDouble().coerceAtLeast(1.0)
        return intersection / minSize
    }

    private fun tokens(text: String): Set<String> {
        val stopwords = setOf("a", "o", "de", "da", "do", "the", "and", "or", "to", "em", "para")
        return text
            .split("[^a-zA-Z0-9]+".toRegex())
            .map { it.lowercase() }
            .filter { it.length > 2 && it !in stopwords }
            .toSet()
    }

    private fun normalizedLevenshtein(a: String, b: String): Double {
        val distance = levenshteinDistance(a, b)
        val maxLen = maxOf(a.length, b.length).coerceAtLeast(1)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    private fun normalize(text: String): String {
        return text.lowercase().replace("\\s+".toRegex(), " ").trim()
    }
}
