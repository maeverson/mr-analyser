package com.mranalyser.application.usecase

import com.mranalyser.application.port.MergeRequestPublisher
import com.mranalyser.domain.model.CommentPlan
import com.mranalyser.domain.model.PublicationResult
import com.mranalyser.domain.model.ReviewComment

/**
 * Publica no MR apenas o que o revisor escolheu. Nada é publicado por padrão: a seleção vazia
 * é uma resposta legítima, e a aprovação só acontece quando pedida explicitamente.
 */
class PublishReviewUseCase(
    private val publisher: MergeRequestPublisher
) {
    /** O MR recebeu commits depois da análise: linhas e parecer podem não valer mais. */
    suspend fun headChangedSinceAnalysis(plan: CommentPlan): Boolean {
        val analysedHead = plan.diffRefs?.headSha ?: return false
        val currentHead = publisher.currentHeadSha(plan.projectPath, plan.mrIid) ?: return false
        return currentHead != analysedHead
    }

    suspend fun execute(plan: CommentPlan, selectedIds: Set<Int>, approve: Boolean): PublicationResult {
        val selected = plan.comments.filter { it.id in selectedIds }
        val existing = if (selected.isEmpty()) emptyList() else publisher.existingNoteBodies(plan.projectPath, plan.mrIid)

        val posted = mutableListOf<ReviewComment>()
        val fellBack = mutableListOf<ReviewComment>()
        val duplicates = mutableListOf<ReviewComment>()
        val failed = mutableListOf<Pair<ReviewComment, String>>()

        selected.forEach { comment ->
            val marker = markerOf(comment)
            if (existing.any { marker in it }) {
                duplicates += comment
                return@forEach
            }
            val body = "${comment.body.trimEnd()}\n\n$marker"
            try {
                val anchor = comment.anchor
                val diffRefs = plan.diffRefs
                val postedInline = anchor != null && diffRefs != null &&
                    publisher.postLineComment(plan.projectPath, plan.mrIid, body, anchor, diffRefs)
                if (!postedInline) {
                    val located = if (anchor != null) "**`${comment.location}`**\n\n$body" else body
                    publisher.postGeneralComment(plan.projectPath, plan.mrIid, located)
                    if (anchor != null) fellBack += comment
                }
                posted += comment
            } catch (exception: Exception) {
                failed += comment to (exception.message ?: exception::class.simpleName.orEmpty())
            }
        }

        var approved = false
        var approvalError: String? = null
        if (approve) {
            try {
                publisher.approve(plan.projectPath, plan.mrIid, plan.diffRefs?.headSha)
                approved = true
            } catch (exception: Exception) {
                approvalError = exception.message ?: exception::class.simpleName.orEmpty()
            }
        }

        return PublicationResult(
            posted = posted,
            fellBackToGeneral = fellBack,
            skippedAsDuplicate = duplicates,
            failed = failed,
            approved = approved,
            approvalError = approvalError
        )
    }

    companion object {
        /** Comentário HTML: invisível no GitLab, mas identifica o que já foi publicado. */
        fun markerOf(comment: ReviewComment): String = "<!-- mr-analyser:${comment.key} -->"
    }
}
