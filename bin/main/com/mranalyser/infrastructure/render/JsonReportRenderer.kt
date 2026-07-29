package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class JsonReportRenderer : ReportRenderer {
    private val json = Json { prettyPrint = true }

    override fun render(mergeRequest: MergeRequest, report: ReviewReport): String {
        val payload = JsonReportPayload(
            mergeRequestIid = mergeRequest.iid,
            mergeRequestTitle = mergeRequest.title,
            author = mergeRequest.author.name,
            sourceBranch = mergeRequest.sourceBranch,
            targetBranch = mergeRequest.targetBranch,
            filesChanged = mergeRequest.changes.size,
            linesAdded = mergeRequest.changes.sumOf { it.linesAdded },
            linesRemoved = mergeRequest.changes.sumOf { it.linesRemoved },
            summary = report.summary,
            findings = report.findings.map {
                JsonFinding(
                    severity = it.severity.name,
                    category = it.category.name,
                    file = it.file,
                    line = it.line,
                    title = it.title,
                    description = it.description,
                    impact = it.impact,
                    recommendation = it.recommendation,
                    suggestedComment = it.suggestedComment,
                    confidence = it.confidence
                )
            },
            questions = report.questions,
            positivePoints = report.positivePoints,
            recommendation = report.recommendation.name
        )
        return json.encodeToString(payload)
    }
}

@Serializable
data class JsonReportPayload(
    val mergeRequestIid: Long,
    val mergeRequestTitle: String,
    val author: String,
    val sourceBranch: String,
    val targetBranch: String,
    val filesChanged: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
    val summary: String,
    val findings: List<JsonFinding>,
    val questions: List<String>,
    val positivePoints: List<String>,
    val recommendation: String
)

@Serializable
data class JsonFinding(
    val severity: String,
    val category: String,
    val file: String? = null,
    val line: Int? = null,
    val title: String,
    val description: String,
    val impact: String? = null,
    val recommendation: String? = null,
    val suggestedComment: String? = null,
    val confidence: Double
)
