package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewReport

class GitLabCommentRenderer : ReportRenderer {
    override fun render(mergeRequest: MergeRequest, report: ReviewReport): String {
        val sb = StringBuilder()
        sb.appendLine("SUGGESTED GITLAB COMMENTS")
        sb.appendLine("-------------------------------------------------")

        val comments = report.findings
            .filter { !it.suggestedComment.isNullOrBlank() }

        if (comments.isEmpty()) {
            sb.appendLine("Nenhum comentario sugerido.")
            return sb.toString().trimEnd()
        }

        comments.forEachIndexed { index, finding ->
            sb.appendLine()
            sb.appendLine("${index + 1}. ${finding.file ?: "<unknown-file>"}${finding.line?.let { ":$it" } ?: ""}")
            sb.appendLine("Severity: ${finding.severity} | Category: ${finding.category}")
            sb.appendLine("\"${finding.suggestedComment}\"")
        }

        sb.appendLine()
        sb.appendLine("Merge recommendation: ${report.recommendation}")
        return sb.toString().trimEnd()
    }
}
