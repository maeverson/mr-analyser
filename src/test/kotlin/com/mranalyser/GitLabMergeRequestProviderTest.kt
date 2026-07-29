package com.mranalyser

import com.mranalyser.infrastructure.gitlab.GitLabApprovalsDto
import com.mranalyser.infrastructure.gitlab.GitLabChangeDto
import com.mranalyser.infrastructure.gitlab.GitLabClient
import com.mranalyser.infrastructure.gitlab.GitLabCommitDto
import com.mranalyser.infrastructure.gitlab.GitLabDiscussionDto
import com.mranalyser.infrastructure.gitlab.GitLabMergeRequestChangesDto
import com.mranalyser.infrastructure.gitlab.GitLabMergeRequestDto
import com.mranalyser.infrastructure.gitlab.GitLabMergeRequestProvider
import com.mranalyser.infrastructure.gitlab.GitLabNoteDto
import com.mranalyser.infrastructure.gitlab.GitLabUserDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitLabMergeRequestProviderTest {
    @Test
    fun `should map gitlab payload to domain merge request`() = runBlocking {
        val client = mockk<GitLabClient>()
        coEvery { client.getMergeRequest("group/project", 123) } returns GitLabMergeRequestDto(
            id = 99,
            iid = 123,
            title = "MR",
            description = "desc",
            author = GitLabUserDto(1, "Gabriel", "gabriel"),
            sourceBranch = "feature",
            targetBranch = "main"
        )
        coEvery { client.getMergeRequestChanges("group/project", 123) } returns GitLabMergeRequestChangesDto(
            changes = listOf(
                GitLabChangeDto(
                    oldPath = "A.kt",
                    newPath = "A.kt",
                    diff = "+a\n-b"
                )
            )
        )
        coEvery { client.getMergeRequestCommits("group/project", 123) } returns listOf(
            GitLabCommitDto("sha", "msg", "Gabriel")
        )
        coEvery { client.getMergeRequestDiscussions("group/project", 123) } returns listOf(
            GitLabDiscussionDto("1", listOf(GitLabNoteDto(10, "note", GitLabUserDto(2, "R", "r"))))
        )
        coEvery { client.getMergeRequestApprovals("group/project", 123) } returns GitLabApprovalsDto(1)

        val provider = GitLabMergeRequestProvider(client)
        val mr = provider.fetchMergeRequest("group/project", 123)

        assertEquals(123, mr.iid)
        assertEquals(1, mr.changes.first().linesAdded)
        assertEquals(1, mr.changes.first().linesRemoved)
        assertEquals(1, mr.approvalsRequired)
    }
}
