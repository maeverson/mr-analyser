package com.mranalyser.infrastructure.gitlab

import com.mranalyser.application.port.MergeRequestPublisher
import com.mranalyser.domain.model.DiffRefs
import com.mranalyser.domain.model.LineAnchor

class GitLabMergeRequestPublisher(
    private val client: GitLabClient
) : MergeRequestPublisher {
    override suspend fun currentHeadSha(project: String, mrIid: Long): String? {
        val mr = client.getMergeRequest(project, mrIid)
        return mr.diffRefs?.headSha ?: mr.sha
    }

    override suspend fun existingNoteBodies(project: String, mrIid: Long): List<String> =
        client.getMergeRequestDiscussions(project, mrIid)
            .flatMap { it.notes }
            .filterNot { it.system }
            .map { it.body }

    override suspend fun postLineComment(
        project: String,
        mrIid: Long,
        body: String,
        anchor: LineAnchor,
        diffRefs: DiffRefs
    ): Boolean {
        val request = GitLabDiscussionRequest(
            body = body,
            position = GitLabNewPositionDto(
                positionType = "text",
                baseSha = diffRefs.baseSha,
                startSha = diffRefs.startSha,
                headSha = diffRefs.headSha,
                oldPath = anchor.oldPath,
                newPath = anchor.newPath,
                oldLine = anchor.oldLine,
                newLine = anchor.newLine
            )
        )
        return try {
            withFriendlyErrors { client.createMergeRequestDiscussion(project, mrIid, request) }
            true
        } catch (exception: GitLabApiException) {
            // 400: a linha não faz parte do diff atual. Quem chama decide publicar na thread geral.
            if (exception.statusCode == 400) false else throw exception
        }
    }

    override suspend fun postGeneralComment(project: String, mrIid: Long, body: String) {
        withFriendlyErrors { client.createMergeRequestNote(project, mrIid, GitLabNoteRequest(body)) }
    }

    override suspend fun approve(project: String, mrIid: Long, headSha: String?) {
        try {
            client.approveMergeRequest(project, mrIid, GitLabApproveRequest(sha = headSha))
        } catch (exception: GitLabApiException) {
            throw IllegalStateException(approvalError(exception), exception)
        }
    }

    /** 400 segue como [GitLabApiException]: é o sinal de posição inválida, não um erro de acesso. */
    private suspend fun withFriendlyErrors(block: suspend () -> Unit) {
        try {
            block()
        } catch (exception: GitLabApiException) {
            when (exception.statusCode) {
                401, 403 -> throw IllegalStateException(
                    "sem permissão para comentar (${exception.statusCode}). O GITLAB_TOKEN precisa do escopo 'api'.",
                    exception
                )
                else -> throw exception
            }
        }
    }

    private fun approvalError(exception: GitLabApiException): String = when (exception.statusCode) {
        401 -> "aprovação recusada (401): o MR já foi aprovado por você, é de sua autoria, " +
            "ou o token não tem o escopo 'api'."
        403 -> "aprovação recusada (403): sua conta não pode aprovar este MR."
        409 -> "aprovação recusada (409): o MR recebeu commits depois da análise. Rode a análise de novo."
        else -> "aprovação recusada (${exception.statusCode}): ${exception.responseBody.take(200)}"
    }
}
