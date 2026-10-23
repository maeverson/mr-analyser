package com.mranalyser.infrastructure.render

import com.mranalyser.domain.diff.UnifiedDiffParser
import com.mranalyser.domain.model.CommentPlan
import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.LineAnchor
import com.mranalyser.domain.model.LineOrigin
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewBucket
import com.mranalyser.domain.model.ReviewComment
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.model.bucket
import com.mranalyser.domain.model.effectiveCommentType
import com.mranalyser.domain.policy.NoisePolicy
import java.security.MessageDigest

/**
 * Transforma o relatório nos comentários que o revisor pode escolher publicar. Usa o mesmo
 * filtro de ruído do [GitLabCommentRenderer]: o que não mereceria comentário colado à mão
 * também não vira candidato a publicação.
 */
class CommentPlanBuilder(
    private val noisePolicy: NoisePolicy = NoisePolicy()
) {
    fun build(mergeRequest: MergeRequest, report: ReviewReport, gitlabUrl: String): CommentPlan {
        val drafts = mutableListOf<Draft>()

        report.findings
            .filter { it.bucket() != ReviewBucket.PRE_EXISTING }
            .filter { noisePolicy.deservesGitLabComment(it) }
            .sortedByDescending { GitLabCommentRenderer.commentOrderOf(it.effectiveCommentType()) }
            .forEach { drafts += lineDraft(mergeRequest, it) }

        if (report.questions.isNotEmpty()) {
            drafts += Draft(
                key = "questions-${digest(report.questions.joinToString("\n"))}",
                type = CommentType.QUESTION,
                title = "Perguntas ao autor",
                body = heading(CommentType.QUESTION, "Perguntas ao autor") + "\n\n" +
                    report.questions.joinToString("\n") { "- $it" }
            )
        }

        if (report.positivePoints.isNotEmpty()) {
            drafts += Draft(
                key = "praise-${digest(report.positivePoints.joinToString("\n"))}",
                type = CommentType.PRAISE,
                title = "Pontos positivos",
                body = heading(CommentType.PRAISE, "Pontos positivos") + "\n\n" +
                    report.positivePoints.joinToString("\n") { "- $it" }
            )
        }

        val preExisting = report.preExistingFindings
        if (preExisting.isNotEmpty()) {
            drafts += Draft(
                key = "pre-existing-${digest(preExisting.joinToString("\n") { it.correlationKey })}",
                type = CommentType.OBSERVATION,
                title = "Dívida técnica não introduzida por este MR",
                body = heading(CommentType.OBSERVATION, "Dívida técnica não introduzida por este MR") + "\n\n" +
                    preExisting.joinToString("\n") { "- `${it.location}` — ${it.title}" }
            )
        }

        drafts += summaryDraft(mergeRequest, report)

        return CommentPlan(
            gitlabUrl = gitlabUrl,
            projectPath = mergeRequest.projectPath.orEmpty(),
            mrIid = mergeRequest.iid,
            webUrl = mergeRequest.webUrl,
            diffRefs = mergeRequest.diffRefs,
            recommendation = report.recommendation,
            blockingCount = report.blockingFindings.size,
            partialAnalysis = report.quality?.partial == true,
            comments = drafts.mapIndexed { index, draft ->
                ReviewComment(
                    id = index + 1,
                    key = draft.key,
                    type = draft.type,
                    title = draft.title,
                    location = draft.location,
                    body = draft.body,
                    anchor = draft.anchor
                )
            }
        )
    }

    private fun lineDraft(mergeRequest: MergeRequest, finding: ReviewFinding): Draft {
        val type = finding.effectiveCommentType()
        val anchor = anchorOf(mergeRequest, finding)
        val text = finding.suggestedComment?.trim().orEmpty()
        // Sem âncora, o comentário vai para a thread geral e precisa dizer de onde fala.
        val located = if (anchor == null && finding.file != null) "**`${finding.location}`**\n\n" else ""
        return Draft(
            key = digest(finding.correlationKey),
            type = type,
            title = finding.title,
            location = finding.location,
            body = located + heading(type, finding.category.label) + "\n\n" + text,
            anchor = anchor
        )
    }

    private fun summaryDraft(mergeRequest: MergeRequest, report: ReviewReport): Draft {
        val body = buildString {
            appendLine(heading(CommentType.OBSERVATION, "Resumo da revisão"))
            appendLine()
            report.opinion?.opinion?.takeIf { it.isNotBlank() }?.let {
                appendLine(it)
                appendLine()
            }
            append(
                "Parecer: ${ReviewNarrative.recommendationLabel(report.recommendation)} " +
                    "(${report.blockingFindings.size} bloqueador(es), " +
                    "${report.questionFindings.size} questionamento(s), " +
                    "${report.suggestionFindings.size} sugestão(ões))."
            )
        }
        // A chave inclui o commit analisado: uma nova rodada após novos commits pode publicar outro resumo.
        val head = mergeRequest.diffRefs?.headSha?.take(12) ?: digest(body)
        return Draft(
            key = "summary-$head",
            type = CommentType.OBSERVATION,
            title = "Comentário geral do MR (resumo e parecer)",
            body = body
        )
    }

    /** A linha só pode receber comentário se estiver no diff, e não como linha removida. */
    private fun anchorOf(mergeRequest: MergeRequest, finding: ReviewFinding): LineAnchor? {
        val file = finding.file ?: return null
        val line = finding.line ?: return null
        val change = mergeRequest.changes.firstOrNull { it.newPath == file || it.oldPath == file } ?: return null
        if (change.deleted) {
            return null
        }
        val diffLine = UnifiedDiffParser.parse(change.diff).lines
            .firstOrNull { it.origin != LineOrigin.REMOVED && it.newLine == line }
            ?: return null
        return LineAnchor(
            oldPath = change.oldPath,
            newPath = change.newPath,
            oldLine = diffLine.oldLine.takeIf { diffLine.origin == LineOrigin.CONTEXT },
            newLine = diffLine.newLine
        )
    }

    private fun heading(type: CommentType, subject: String): String = "**${labelOf(type)}** · $subject"

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)

    private data class Draft(
        val key: String,
        val type: CommentType,
        val title: String,
        val body: String,
        val location: String = "(MR)",
        val anchor: LineAnchor? = null
    )

    companion object {
        fun labelOf(type: CommentType): String = when (type) {
            CommentType.BLOCKER -> "🔴 Bloqueador"
            CommentType.QUESTION -> "❓ Pergunta"
            CommentType.SUGGESTION -> "💡 Sugestão"
            CommentType.OBSERVATION -> "📝 Observação"
            CommentType.PRAISE -> "👏 Ponto positivo"
        }
    }
}
