package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.model.Severity

class ConsoleReportRenderer(
    private val showLowConfidence: Boolean,
    private val minimumConfidence: Double
) : ReportRenderer {
    override fun render(mergeRequest: MergeRequest, report: ReviewReport): String {
        val sb = StringBuilder()
        val totalAdded = mergeRequest.changes.sumOf { it.linesAdded }
        val totalRemoved = mergeRequest.changes.sumOf { it.linesRemoved }

        sb.appendLine("📋 MR REVIEW REPORT")
        sb.appendLine("════════════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("🔀 Merge request")
        sb.appendLine("   !${mergeRequest.iid} - ${mergeRequest.title}")
        sb.appendLine("   Branch: ${mergeRequest.sourceBranch} → ${mergeRequest.targetBranch}")
        sb.appendLine("   Author: ${mergeRequest.author.name}")
        sb.appendLine()
        sb.appendLine("📊 Change summary")
        sb.appendLine("   Files changed: ${mergeRequest.changes.size}")
        sb.appendLine("   Lines added: $totalAdded")
        sb.appendLine("   Lines removed: $totalRemoved")
        sb.appendLine()

        sb.appendLine("🧾 Summary")
        sb.appendLine("   ${report.summary}")
        sb.appendLine()

        renderSection(sb, "🔴 Critical", report, Severity.CRITICAL)
        renderSection(sb, "🟠 High", report, Severity.HIGH)
        renderSection(sb, "🟡 Warnings", report, Severity.MEDIUM)
        renderSection(sb, "🟢 Suggestions", report, Severity.LOW)

        val infoFindings = report.findings.filter { it.severity == Severity.INFO }
        if (infoFindings.isNotEmpty()) {
            sb.appendLine("ℹ️  Informational")
            infoFindings.forEachIndexed { idx, finding ->
                sb.appendLine("   ${idx + 1}. ${finding.title}")
                sb.appendLine("      ${finding.description}")
                sb.appendLine()
            }
        }

        if (report.questions.isNotEmpty()) {
            sb.appendLine("❓ Questions for author")
            report.questions.forEachIndexed { idx, question ->
                sb.appendLine("   ${idx + 1}. $question")
                sb.appendLine()
            }
        }

        if (report.positivePoints.isNotEmpty()) {
            sb.appendLine("✅ Positive points")
            report.positivePoints.forEachIndexed { idx, point ->
                sb.appendLine("   ${idx + 1}. $point")
            }
            sb.appendLine()
        }

        if (showLowConfidence) {
            val lowConfidence = report.findings.filter { it.confidence < minimumConfidence }
            if (lowConfidence.isNotEmpty()) {
                sb.appendLine("⚪ Low confidence")
                lowConfidence.forEach {
                    sb.appendLine("   - ${it.title} (${it.confidence})")
                }
                sb.appendLine()
            }
        }

        sb.appendLine("🚦 Merge recommendation")
        sb.appendLine("   ${report.recommendation.name}")

        return sb.toString().trimEnd()
    }

    private fun renderSection(
        sb: StringBuilder,
        title: String,
        report: ReviewReport,
        severity: Severity
    ) {
        val findings = report.findings.filter { it.severity == severity }
        if (findings.isEmpty()) {
            return
        }

        sb.appendLine(title)

        findings.forEachIndexed { index, finding ->
            sb.appendLine("   ${index + 1}. ${finding.title}")
            finding.file?.let {
                sb.appendLine("      File: $it")
            }
            finding.line?.let {
                sb.appendLine("      Line: $it")
            }
            sb.appendLine("      Issue: ${finding.description}")
            finding.impact?.let {
                sb.appendLine("      Impact: $it")
            }
            finding.recommendation?.let {
                sb.appendLine("      Recommendation: $it")
            }
            finding.suggestedComment?.let {
                sb.appendLine("      Suggested comment: \"$it\"")
            }
            sb.appendLine()
        }
    }
}
