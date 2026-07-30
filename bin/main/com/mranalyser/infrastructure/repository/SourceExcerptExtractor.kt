package com.mranalyser.infrastructure.repository

/**
 * Produz um recorte útil de um arquivo relacionado.
 *
 * A V1 enviava as primeiras 120 linhas, o que normalmente truncava no meio da classe e
 * raramente incluía o símbolo relevante. Aqui o recorte é: cabeçalho (package/import), linhas
 * de declaração (assinaturas, anotações) e janelas ao redor das ocorrências dos símbolos-alvo.
 *
 * Trechos omitidos são marcados, para que o modelo não conclua ausência de código a partir do corte.
 */
class SourceExcerptExtractor(
    private val headerLines: Int = 25,
    private val windowRadius: Int = 6,
    private val maxWindows: Int = 6
) {
    fun extract(content: String, targetSymbols: Collection<String>, maxChars: Int): String {
        val lines = content.lines()
        if (content.length <= maxChars) {
            return content
        }

        // A ordem aqui é a ordem de prioridade no orçamento, e importa: quando o arquivo é grande,
        // as janelas em volta do símbolo-alvo — o motivo pelo qual o arquivo foi trazido — precisam
        // sobreviver ao corte antes de declarações genéricas espalhadas pelo arquivo.
        val byPriority = LinkedHashSet<Int>()
        byPriority += targetWindows(lines, targetSymbols)
        byPriority += lines.indices.take(headerLines)
        byPriority += lines.indices.filter { DECLARATION.containsMatchIn(lines[it]) }

        val selected = sortedSetOf<Int>()
        var budget = 0
        for (index in byPriority) {
            val cost = lines[index].length + LINE_OVERHEAD
            if (budget + cost > maxChars) {
                break
            }
            selected += index
            budget += cost
        }

        return render(lines, selected, maxChars)
    }

    /** Janelas ao redor de cada ocorrência dos símbolos-alvo. */
    private fun targetWindows(lines: List<String>, targetSymbols: Collection<String>): List<Int> {
        val symbols = targetSymbols.filter { it.isNotBlank() }
        if (symbols.isEmpty()) {
            return emptyList()
        }

        val indices = mutableListOf<Int>()
        var windows = 0

        for ((index, line) in lines.withIndex()) {
            if (windows >= maxWindows) {
                break
            }
            if (symbols.none { line.contains(it) }) {
                continue
            }
            ((index - windowRadius)..(index + windowRadius))
                .filter { it in lines.indices }
                .forEach { indices += it }
            windows++
        }

        return indices.distinct()
    }

    private fun render(lines: List<String>, selected: Set<Int>, maxChars: Int): String {
        val builder = StringBuilder()
        var previous = -1

        for (index in selected) {
            if (builder.length >= maxChars) {
                builder.appendLine("... [recorte interrompido pelo limite de contexto]")
                break
            }
            if (previous >= 0 && index > previous + 1) {
                builder.appendLine("... [${index - previous - 1} linha(s) omitida(s)]")
            }
            builder.appendLine("${(index + 1).toString().padStart(5)} | ${lines[index]}")
            previous = index
        }

        if (previous in lines.indices && previous < lines.size - 1) {
            builder.appendLine("... [${lines.size - 1 - previous} linha(s) omitida(s) no fim do arquivo]")
        }

        return builder.toString().trimEnd().take(maxChars)
    }

    private companion object {
        /** Prefixo de numeração e separador que cada linha renderizada acrescenta. */
        const val LINE_OVERHEAD = 9

        val DECLARATION = Regex(
            """(?:^|\s)(?:package|import|@\w+|class|interface|object|enum|record|trait|struct|""" +
                """fun|def|func|val|var|const|public|private|protected|internal|open|abstract|override|suspend|""" +
                """CREATE|ALTER|DROP)\b"""
        )
    }
}
