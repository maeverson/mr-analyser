package com.mranalyser.cli

/**
 * Interpreta a escolha do revisor: `1,3-5`, `all` ou `none`. Entrada vazia é `none`:
 * não publicar nada é sempre o padrão.
 */
object CommentSelection {
    private val all = setOf("all", "todos", "*")
    private val none = setOf("", "none", "nenhum", "-")
    private val range = Regex("""^(\d+)-(\d+)$""")

    fun parse(input: String, availableIds: Set<Int>): Set<Int> {
        val normalized = input.trim().lowercase()
        if (normalized in none) {
            return emptySet()
        }
        if (normalized in all) {
            return availableIds
        }

        val selected = normalized.split(',', ' ', ';')
            .filter { it.isNotBlank() }
            .flatMap { token ->
                val match = range.matchEntire(token)
                when {
                    match != null -> {
                        val (start, end) = match.destructured
                        require(start.toInt() <= end.toInt()) { "intervalo invertido: $token" }
                        (start.toInt()..end.toInt()).toList()
                    }
                    token.toIntOrNull() != null -> listOf(token.toInt())
                    else -> throw IllegalArgumentException("não entendi '$token'. Use números (1,3-5), all ou none")
                }
            }
            .toSet()

        val unknown = selected - availableIds
        require(unknown.isEmpty()) { "comentário(s) inexistente(s): ${unknown.sorted().joinToString(", ")}" }
        return selected
    }
}
