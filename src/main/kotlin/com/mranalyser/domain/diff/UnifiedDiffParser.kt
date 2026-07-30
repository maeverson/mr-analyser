package com.mranalyser.domain.diff

import com.mranalyser.domain.model.DiffHunk
import com.mranalyser.domain.model.DiffLine
import com.mranalyser.domain.model.LineOrigin
import com.mranalyser.domain.model.ParsedDiff

/**
 * Parser de unified diff. Existe para resolver um defeito da V1: as regras estáticas usavam
 * o índice da linha dentro do texto do diff como número de linha do arquivo, o que produzia
 * localização incorreta em todo finding estático.
 *
 * Não é um parser completo de git: ignora cabeçalhos de arquivo e metadados, pois o GitLab
 * já entrega um diff por arquivo.
 */
object UnifiedDiffParser {
    private val hunkHeader = Regex("""^@@+\s*-(\d+)(?:,(\d+))?\s\+(\d+)(?:,(\d+))?\s*@@+(.*)$""")

    fun parse(diff: String): ParsedDiff {
        if (diff.isBlank()) {
            return ParsedDiff.EMPTY
        }

        val normalized = withSyntheticHunkHeaderIfMissing(diff)
        val hunks = mutableListOf<DiffHunk>()
        val unparsed = mutableListOf<String>()

        var header: String? = null
        var oldStart = 0
        var newStart = 0
        var oldCursor = 0
        var newCursor = 0
        var lines = mutableListOf<DiffLine>()

        fun flush() {
            val currentHeader = header ?: return
            hunks += DiffHunk(
                header = currentHeader,
                oldStart = oldStart,
                newStart = newStart,
                lines = lines.toList()
            )
            header = null
            lines = mutableListOf()
        }

        normalized.lineSequence().forEach { raw ->
            val match = hunkHeader.matchEntire(raw)
            if (match != null) {
                flush()
                oldStart = match.groupValues[1].toIntOrNull() ?: 1
                newStart = match.groupValues[3].toIntOrNull() ?: 1
                oldCursor = oldStart
                newCursor = newStart
                header = raw.trim()
                return@forEach
            }

            if (header == null) {
                // Cabeçalhos (diff --git, index, ---, +++) e diffs binários ficam fora dos hunks.
                if (raw.isNotBlank() && !isFileHeader(raw)) {
                    unparsed += raw
                }
                return@forEach
            }

            when {
                raw.startsWith("+") -> {
                    lines += DiffLine(LineOrigin.ADDED, raw.substring(1), null, newCursor)
                    newCursor++
                }

                raw.startsWith("-") -> {
                    lines += DiffLine(LineOrigin.REMOVED, raw.substring(1), oldCursor, null)
                    oldCursor++
                }

                raw.startsWith("\\") -> Unit // "\ No newline at end of file"

                else -> {
                    val content = if (raw.startsWith(" ")) raw.substring(1) else raw
                    lines += DiffLine(LineOrigin.CONTEXT, content, oldCursor, newCursor)
                    oldCursor++
                    newCursor++
                }
            }
        }

        flush()
        return ParsedDiff(hunks = hunks, unparsed = unparsed)
    }

    /**
     * Alguns diffs (fixtures, patches truncados, respostas de API sem hunk header) chegam
     * apenas com linhas `+`/`-`. Sem um `@@` não há como saber o número real da linha; assumir
     * início em 1 é melhor do que descartar o conteúdo, e o `origin` continua correto.
     */
    private fun withSyntheticHunkHeaderIfMissing(diff: String): String {
        val hasHeader = diff.lineSequence().any { hunkHeader.matches(it) }
        if (hasHeader) {
            return diff
        }
        val hasChangedLines = diff.lineSequence().any {
            (it.startsWith("+") || it.startsWith("-")) && !isFileHeader(it)
        }
        return if (hasChangedLines) "@@ -1 +1 @@\n$diff" else diff
    }

    private fun isFileHeader(line: String): Boolean {
        return line.startsWith("diff --git") ||
            line.startsWith("index ") ||
            line.startsWith("--- ") ||
            line.startsWith("+++ ") ||
            line.startsWith("new file mode") ||
            line.startsWith("deleted file mode") ||
            line.startsWith("similarity index") ||
            line.startsWith("rename from") ||
            line.startsWith("rename to") ||
            line.startsWith("Binary files")
    }
}
