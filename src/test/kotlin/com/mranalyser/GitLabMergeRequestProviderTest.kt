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
import com.mranalyser.infrastructure.gitlab.GitLabPositionDto
import com.mranalyser.infrastructure.gitlab.GitLabUserDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitLabMergeRequestProviderTest {

    @Test
    fun `deve mapear payload do gitlab para o dominio`() = runBlocking {
        val client = mockk<GitLabClient>()
        coEvery { client.getMergeRequest(PROJECT, 123) } returns GitLabMergeRequestDto(
            id = 99,
            iid = 123,
            title = "MR",
            description = "desc",
            author = GitLabUserDto(1, "Gabriel", "gabriel"),
            sourceBranch = "feature",
            targetBranch = "main",
            webUrl = "https://gitlab.com/group/project/-/merge_requests/123"
        )
        coEvery { client.getMergeRequestChanges(PROJECT, 123) } returns GitLabMergeRequestChangesDto(
            changes = listOf(
                GitLabChangeDto(oldPath = "A.kt", newPath = "A.kt", diff = "@@ -1 +1 @@\n+a\n-b"),
                GitLabChangeDto(
                    oldPath = "Old.kt",
                    newPath = "Old.kt",
                    diff = "@@ -1 +0,0 @@\n-old",
                    deletedFile = true
                ),
                GitLabChangeDto(
                    oldPath = "Gen.kt",
                    newPath = "Gen.kt",
                    diff = "@@ -1 +1 @@\n+gen",
                    generatedFile = true
                )
            )
        )
        coEvery { client.getMergeRequestCommits(PROJECT, 123) } returns listOf(
            GitLabCommitDto(id = "sha", title = "título", message = "título\n\ncorpo com a intenção", authorName = "Gabriel"),
            GitLabCommitDto(id = "sha2", title = "só título", message = null, authorName = "Gabriel")
        )
        coEvery { client.getMergeRequestDiscussions(PROJECT, 123) } returns listOf(
            GitLabDiscussionDto(
                "1",
                listOf(
                    GitLabNoteDto(
                        id = 10,
                        body = "falta timeout",
                        author = GitLabUserDto(2, "R", "r"),
                        resolvable = true,
                        resolved = true,
                        position = GitLabPositionDto(newPath = "A.kt", newLine = 42)
                    )
                )
            ),
            GitLabDiscussionDto(
                "2",
                listOf(GitLabNoteDto(id = 11, body = "changed target branch", author = GitLabUserDto(1, "G"), system = true))
            )
        )
        coEvery { client.getMergeRequestApprovals(PROJECT, 123) } returns GitLabApprovalsDto(1)

        val mr = GitLabMergeRequestProvider(client).fetchMergeRequest(PROJECT, 123)

        assertEquals(123, mr.iid)
        assertEquals(PROJECT, mr.projectPath, "projectPath é necessário para validar o repositório local")
        assertEquals("https://gitlab.com/group/project/-/merge_requests/123", mr.webUrl)
        assertEquals(1, mr.approvalsRequired)

        assertEquals(1, mr.changes[0].linesAdded)
        assertEquals(1, mr.changes[0].linesRemoved)
        assertTrue(mr.changes[1].deleted, "arquivo removido deve ser identificado")
        assertEquals("Old.kt", mr.changes[1].path)
        assertTrue(mr.changes[2].generated)

        assertEquals("título\n\ncorpo com a intenção", mr.commits[0].message, "corpo do commit não deve ser descartado")
        assertEquals("só título", mr.commits[1].message)

        val note = mr.discussions[0].notes.single()
        assertEquals("A.kt", note.file)
        assertEquals(42, note.line)
        assertTrue(note.resolved)
        assertTrue(mr.discussions[0].resolved)

        assertTrue(mr.discussions[1].notes.single().system)
        assertFalse(mr.discussions[1].resolved, "discussão só de nota de sistema não conta como resolvida")
    }

    /**
     * Regressão do defeito mais silencioso da V1: os campos vinham em snake_case do GitLab e o DTO
     * esperava camelCase, então `deleted`/`added`/`renamed` eram sempre `false`.
     */
    @Test
    fun `deve desserializar os flags de arquivo em snake_case`() {
        val payload = """
            {"changes": [{
              "old_path": "A.kt",
              "new_path": "B.kt",
              "diff": "@@ -1 +1 @@\n+x",
              "new_file": true,
              "deleted_file": false,
              "renamed_file": true,
              "generated_file": true
            }]}
        """.trimIndent()

        val change = json.decodeFromString<GitLabMergeRequestChangesDto>(payload).changes.single()

        assertTrue(change.newFile)
        assertTrue(change.renamedFile)
        assertTrue(change.generatedFile)
        assertFalse(change.deletedFile)
    }

    private companion object {
        const val PROJECT = "group/project"
        val json = Json { ignoreUnknownKeys = true }
    }
}
