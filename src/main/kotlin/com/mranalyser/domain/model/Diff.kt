package com.mranalyser.domain.model

/**
 * Origem de uma linha do diff. Enviada explicitamente ao LLM para que ele não comente
 * código removido como se ainda estivesse presente (item 30).
 */
enum class LineOrigin {
    ADDED,
    REMOVED,
    CONTEXT;

    val tag: String
        get() = when (this) {
            ADDED -> "ADD"
            REMOVED -> "DEL"
            CONTEXT -> "ctx"
        }
}

data class DiffLine(
    val origin: LineOrigin,
    val content: String,
    /** Número da linha no arquivo antigo. Nulo para linhas adicionadas. */
    val oldLine: Int?,
    /** Número da linha no arquivo novo. Nulo para linhas removidas. */
    val newLine: Int?
)

data class DiffHunk(
    val header: String,
    val oldStart: Int,
    val newStart: Int,
    val lines: List<DiffLine>
)

data class ParsedDiff(
    val hunks: List<DiffHunk>,
    /** Trechos do diff que não puderam ser interpretados (ex.: diff binário). */
    val unparsed: List<String> = emptyList()
) {
    val lines: List<DiffLine> get() = hunks.flatMap { it.lines }

    val addedLines: List<DiffLine> get() = lines.filter { it.origin == LineOrigin.ADDED }

    val removedLines: List<DiffLine> get() = lines.filter { it.origin == LineOrigin.REMOVED }

    val isEmpty: Boolean get() = hunks.isEmpty()

    companion object {
        val EMPTY = ParsedDiff(emptyList())
    }
}
