package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewReport

class MarkdownReportRenderer : ReportRenderer {
    override fun render(mergeRequest: MergeRequest, report: ReviewReport): String {
        val added = mergeRequest.changes.sumOf { it.linesAdded }
        val removed = mergeRequest.changes.sumOf { it.linesRemoved }

        val sb = StringBuilder()
        sb.appendLine("# 📋 MR Analysis")
        sb.appendLine()
        sb.appendLine("## 🔀 Merge request")
        sb.appendLine("- IID: !${mergeRequest.iid}")
        sb.appendLine("- Title: ${mergeRequest.title}")
        sb.appendLine("- Author: ${mergeRequest.author.name}")
        sb.appendLine("- Branches: ${mergeRequest.sourceBranch} -> ${mergeRequest.targetBranch}")
        sb.appendLine("- Files changed: ${mergeRequest.changes.size}")
        sb.appendLine("- Lines added: $added")
        sb.appendLine("- Lines removed: $removed")
        sb.appendLine()

        sb.appendLine("## 🧾 Summary")
        sb.appendLine(report.summary)
        sb.appendLine()

        if (report.findings.isNotEmpty()) {
            sb.appendLine("## 🔎 Findings")
            report.findings.forEachIndexed { index, finding ->
                sb.appendLine("### ${index + 1}. [${finding.severity}] ${finding.title}")
                sb.appendLine("- Category: ${finding.category}")
                finding.file?.let { sb.appendLine("- File: $it") }
                finding.line?.let { sb.appendLine("- Line: $it") }
                sb.appendLine("- Confidence: ${"%.2f".format(finding.confidence)}")
                sb.appendLine("- Description: ${finding.description}")
                finding.impact?.let { sb.appendLine("- Impact: $it") }
                finding.recommendation?.let { sb.appendLine("- Recommendation: $it") }
                finding.suggestedComment?.let {
                    sb.appendLine("- Suggested comment:")
                    sb.appendLine()
                    sb.appendLine("> $it")
                }
                sb.appendLine()
            }
        }

        if (report.questions.isNotEmpty()) {
            sb.appendLine("## ❓ Questions for author")
            report.questions.forEachIndexed { i, question ->
                sb.appendLine("${i + 1}. $question")
            }
            sb.appendLine()
        }

        if (report.positivePoints.isNotEmpty()) {
            sb.appendLine("## ✅ Positive points")
            report.positivePoints.forEachIndexed { i, point ->
                sb.appendLine("${i + 1}. $point")
            }
            sb.appendLine()
        }

        sb.appendLine("## 🚦 Merge Recommendation")
        sb.appendLine(report.recommendation.name)

        return sb.toString().trimEnd()
    }
}
