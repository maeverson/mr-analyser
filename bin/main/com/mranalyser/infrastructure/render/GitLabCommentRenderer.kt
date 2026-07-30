package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.model.bucket
import com.mranalyser.domain.model.ReviewBucket
import com.mranalyser.domain.policy.NoisePolicy

/**
 * Renderiza os comentários prontos para colar no GitLab, classificados por tipo (item 17).
 *
 * Aplica o filtro do item 18: preferência de nomenclatura, estilo e observação sem impacto não
 * geram comentário — aparecem no relatório, mas não interrompem o autor. Findings pré-existentes
 * também não geram comentário de linha, e sim uma nota separada de dívida técnica (item 19).
 */
class GitLabCommentRenderer(
    private val noisePolicy: NoisePolicy = NoisePolicy()
) : ReportRenderer {
    override fun render(mergeRequest: MergeRequest, report: ReviewReport): String {
        val sb = StringBuilder()
        sb.appendLine("COMENTÁRIOS SUGERIDOS PARA O MR !${mergeRequest.iid}")
        sb.appendLine(DIVIDER)

        val commentable = report.findings
            .filter { it.bucket() != ReviewBucket.PRE_EXISTING }
            .filter { noisePolicy.deservesGitLabComment(it) }
            .sortedByDescending { orderOf(it) }

        if (commentable.isEmpty()) {
            sb.appendLine()
            sb.appendLine("Nenhum comentário de linha recomendado.")
        } else {
            commentable.forEach { finding ->
                sb.appendLine()
                sb.appendLine("[${commentTypeOf(finding).name}]")
                sb.appendLine()
                sb.appendLine(finding.location)
                sb.appendLine()
                sb.appendLine(finding.suggestedComment?.trim().orEmpty())
                sb.appendLine()
                sb.appendLine(DIVIDER)
            }
        }

        renderPraise(sb, report)
        renderPreExistingNote(sb, report)
        renderSummaryComment(sb, report)

        return sb.toString().trimEnd()
    }

    /**
     * Comentário único para colar na thread geral do MR, resumindo a posição do revisor.
     * Um MR raramente merece só comentários de linha: a conclusão precisa aparecer em algum lugar.
     */
    private fun renderSummaryComment(sb: StringBuilder, report: ReviewReport) {
        sb.appendLine()
        sb.appendLine("[COMENTÁRIO GERAL DO MR]")
        sb.appendLine()
        report.opinion?.opinion?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine(it)
            sb.appendLine()
        }
        sb.appendLine(
            "Parecer: ${ReviewNarrative.recommendationLabel(report.recommendation)} " +
                "(${report.blockingFindings.size} bloqueador(es), " +
                "${report.questionFindings.size} questionamento(s), " +
                "${report.suggestionFindings.size} sugestão(ões))."
        )
    }

    private fun renderPraise(sb: StringBuilder, report: ReviewReport) {
        if (report.positivePoints.isEmpty()) {
            return
        }
        sb.appendLine()
        sb.appendLine("[PRAISE]")
        sb.appendLine()
        report.positivePoints.forEach { sb.appendLine("- $it") }
        sb.appendLine()
        sb.appendLine(DIVIDER)
    }

    private fun renderPreExistingNote(sb: StringBuilder, report: ReviewReport) {
        val preExisting = report.preExistingFindings
        if (preExisting.isEmpty()) {
            return
        }
        sb.appendLine()
        sb.appendLine("[OBSERVATION] Dívida técnica não introduzida por este MR")
        sb.appendLine()
        preExisting.forEach { sb.appendLine("- ${it.location} — ${it.title}") }
        sb.appendLine()
        sb.appendLine(DIVIDER)
    }

    private fun commentTypeOf(finding: ReviewFinding): CommentType =
        finding.commentType ?: when (finding.bucket()) {
            ReviewBucket.BLOCKING -> CommentType.BLOCKER
            ReviewBucket.QUESTION -> CommentType.QUESTION
            ReviewBucket.SUGGESTION -> CommentType.SUGGESTION
            ReviewBucket.PRE_EXISTING -> CommentType.OBSERVATION
        }

    private fun orderOf(finding: ReviewFinding): Int = when (commentTypeOf(finding)) {
        CommentType.BLOCKER -> 4
        CommentType.QUESTION -> 3
        CommentType.SUGGESTION -> 2
        CommentType.OBSERVATION -> 1
        CommentType.PRAISE -> 0
    }

    private companion object {
        const val DIVIDER = "────────────────────────────────────────────────────────"
    }
}
