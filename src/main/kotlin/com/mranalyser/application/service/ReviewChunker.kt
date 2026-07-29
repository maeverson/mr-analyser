package com.mranalyser.application.service

import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest

data class ReviewChunk(
    val index: Int,
    val files: List<FileChange>
)

class ReviewChunker(
    private val maxDiffLines: Int,
    private val maxFileLines: Int
) {
    fun chunk(mergeRequest: MergeRequest): List<ReviewChunk> {
        val filtered = mergeRequest.changes.filter { !it.deleted }
        val chunks = mutableListOf<ReviewChunk>()
        var current = mutableListOf<FileChange>()
        var currentLines = 0

        filtered.forEach { change ->
            val lines = change.diff.lineSequence().count()
            if (lines > maxFileLines) {
                if (current.isNotEmpty()) {
                    chunks.add(ReviewChunk(chunks.size + 1, current.toList()))
                    current = mutableListOf()
                    currentLines = 0
                }
                chunks.add(ReviewChunk(chunks.size + 1, listOf(trimChange(change, maxFileLines))))
                return@forEach
            }

            if (currentLines + lines > maxDiffLines && current.isNotEmpty()) {
                chunks.add(ReviewChunk(chunks.size + 1, current.toList()))
                current = mutableListOf()
                currentLines = 0
            }

            current += change
            currentLines += lines
        }

        if (current.isNotEmpty()) {
            chunks.add(ReviewChunk(chunks.size + 1, current.toList()))
        }

        return chunks
    }

    private fun trimChange(change: FileChange, maxLines: Int): FileChange {
        val limitedDiff = change.diff.lineSequence().take(maxLines).joinToString("\n")
        return change.copy(diff = limitedDiff)
    }
}
