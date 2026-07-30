package com.mranalyser.infrastructure.gitlab

import com.mranalyser.application.port.MergeRequestProvider
import com.mranalyser.domain.model.Author
import com.mranalyser.domain.model.Commit
import com.mranalyser.domain.model.Discussion
import com.mranalyser.domain.model.DiscussionNote
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest

class GitLabMergeRequestProvider(
    private val client: GitLabClient
) : MergeRequestProvider {
    override suspend fun fetchMergeRequest(project: String, mrIid: Long): MergeRequest {
        val mr = client.getMergeRequest(project, mrIid)
        val changes = client.getMergeRequestChanges(project, mrIid)
        val commits = client.getMergeRequestCommits(project, mrIid)
        val discussions = client.getMergeRequestDiscussions(project, mrIid)
        val approvals = client.getMergeRequestApprovals(project, mrIid)

        return MergeRequest(
            id = mr.id,
            iid = mr.iid,
            title = mr.title,
            description = mr.description,
            author = mr.author.toDomain(),
            sourceBranch = mr.sourceBranch,
            targetBranch = mr.targetBranch,
            labels = mr.labels,
            status = mr.state,
            reviewers = mr.reviewers.map { it.toDomain() },
            approvalsRequired = approvals?.approvalsRequired,
            // O caminho do projeto é necessário para validar a identidade do repositório local
            // antes de usar o context retrieval.
            projectPath = project,
            webUrl = mr.webUrl,
            changes = changes.changes.map { change ->
                val (added, removed) = computeLineStats(change.diff)
                FileChange(
                    oldPath = change.oldPath,
                    newPath = change.newPath,
                    added = change.newFile,
                    deleted = change.deletedFile,
                    renamed = change.renamedFile,
                    diff = change.diff,
                    linesAdded = added,
                    linesRemoved = removed,
                    generated = change.generatedFile
                )
            },
            commits = commits.map { commit ->
                Commit(
                    sha = commit.id,
                    // O corpo do commit costuma trazer a intenção que o título omite.
                    message = commit.message?.takeIf { it.isNotBlank() } ?: commit.title,
                    author = Author(name = commit.authorName)
                )
            },
            discussions = discussions.map { discussion ->
                Discussion(
                    id = discussion.id,
                    notes = discussion.notes.map { note ->
                        DiscussionNote(
                            id = note.id.toString(),
                            author = note.author.toDomain(),
                            body = note.body,
                            system = note.system,
                            resolvable = note.resolvable,
                            resolved = note.resolved,
                            file = note.position?.newPath ?: note.position?.oldPath,
                            line = note.position?.newLine ?: note.position?.oldLine
                        )
                    }
                )
            }
        )
    }

    private fun GitLabUserDto.toDomain(): Author = Author(
        id = id,
        name = name,
        username = username
    )

    private fun computeLineStats(diff: String): Pair<Int, Int> {
        var added = 0
        var removed = 0
        diff.lineSequence().forEach { line ->
            when {
                line.startsWith("+++") || line.startsWith("---") -> Unit
                line.startsWith("+") -> added++
                line.startsWith("-") -> removed++
            }
        }
        return added to removed
    }
}
