package com.mranalyser.infrastructure.llm

import com.mranalyser.domain.model.LlmReviewResult
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class LlmResponseParser(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun parse(content: String): LlmReviewResult {
        val clean = extractJson(content)
        val parsed = runCatching { json.decodeFromString<LlmResponseDto>(clean) }.getOrNull()
            ?: return LlmReviewResult(
                summary = "Analise de IA concluida, mas sem payload estruturado valido.",
                findings = emptyList(),
                questions = emptyList(),
                positivePoints = emptyList()
            )

        return LlmReviewResult(
            summary = parsed.summary,
            findings = parsed.findings.mapNotNull { f ->
                val severity = runCatching { Severity.valueOf(f.severity) }.getOrNull() ?: return@mapNotNull null
                val category = runCatching { ReviewCategory.valueOf(f.category) }.getOrNull() ?: return@mapNotNull null
                ReviewFinding(
                    severity = severity,
                    category = category,
                    file = f.file,
                    line = f.line,
                    title = f.title,
                    description = f.description,
                    impact = f.impact,
                    recommendation = f.recommendation,
                    suggestedComment = f.suggestedComment,
                    confidence = f.confidence.coerceIn(0.0, 1.0)
                )
            },
            questions = parsed.questions,
            positivePoints = parsed.positivePoints,
            suggestedRecommendation = parsed.suggestedRecommendation?.let {
                runCatching { MergeRecommendation.valueOf(it) }.getOrNull()
            }
        )
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return trimmed
        }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1)
        }
        return trimmed
    }
}

@Serializable
data class LlmResponseDto(
    val summary: String,
    val findings: List<LlmFindingDto> = emptyList(),
    val questions: List<String> = emptyList(),
    val positivePoints: List<String> = emptyList(),
    val suggestedRecommendation: String? = null
)

@Serializable
data class LlmFindingDto(
    val severity: String,
    val category: String,
    val file: String? = null,
    val line: Int? = null,
    val title: String,
    val description: String,
    val impact: String? = null,
    val recommendation: String? = null,
    val suggestedComment: String? = null,
    val confidence: Double = 0.0
)
