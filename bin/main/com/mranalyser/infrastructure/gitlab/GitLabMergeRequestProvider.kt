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
                    linesRemoved = removed
                )
            },
            commits = commits.map {
                Commit(
                    sha = it.id,
                    message = it.title,
                    author = Author(name = it.authorName)
                )
            },
            discussions = discussions.map { discussion ->
                Discussion(
                    id = discussion.id,
                    notes = discussion.notes.map { note ->
                        DiscussionNote(
                            id = note.id.toString(),
                            author = note.author.toDomain(),
                            body = note.body
                        )
                    }
                )
            }
        )
    }

    private fun GitLabUserDto.toDomain(): Author {
        return Author(
            id = id,
            name = name,
            username = username
        )
    }

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
