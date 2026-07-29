package com.mranalyser

import com.mranalyser.application.service.ReviewChunker
import com.mranalyser.domain.model.Author
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReviewChunkerTest {
    @Test
    fun `should chunk by max diff lines`() {
        val mr = sampleMr(
            listOf(
                change("A.kt", 4),
                change("B.kt", 4),
                change("C.kt", 4)
            )
        )
        val chunker = ReviewChunker(maxDiffLines = 8, maxFileLines = 100)

        val chunks = chunker.chunk(mr)

        assertEquals(2, chunks.size)
        assertEquals(2, chunks[0].files.size)
        assertEquals(1, chunks[1].files.size)
    }

    private fun sampleMr(changes: List<FileChange>): MergeRequest {
        return MergeRequest(
            id = 1,
            iid = 1,
            title = "title",
            description = null,
            author = Author(name = "a"),
            sourceBranch = "feature",
            targetBranch = "main",
            changes = changes,
            commits = emptyList(),
            discussions = emptyList()
        )
    }

    private fun change(path: String, lines: Int): FileChange {
        val diff = (1..lines).joinToString("\n") { "+line$it" }
        return FileChange(path, path, false, false, false, diff, lines, 0)
    }
}
