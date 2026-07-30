package com.mranalyser.application.review

import com.mranalyser.application.port.ChangedFileQuery
import com.mranalyser.domain.model.LineOrigin
import com.mranalyser.domain.model.ParsedDiff

/**
 * Extrai do diff os símbolos que descrevem o arquivo alterado: o que ele declara, de quem herda,
 * o que importa e quais tipos referencia.
 *
 * É o insumo do context retrieval. A V1 buscava contexto apenas por semelhança de nome de
 * arquivo, o que trazia arquivos arbitrários; com símbolos é possível encontrar a interface
 * implementada, o caller e o teste correspondente (item 6).
 *
 * As expressões cobrem Kotlin/Java/Scala, TypeScript/JavaScript, Python e Go — suficiente para
 * um retrieval por heurística, sem introduzir um parser por linguagem.
 */
class SymbolExtractor {
    fun extract(path: String, parsedDiff: ParsedDiff): ChangedFileQuery {
        // Linhas de contexto entram porque a declaração da classe raramente está no trecho alterado.
        val relevant = parsedDiff.lines
            .filter { it.origin != LineOrigin.REMOVED }
            .joinToString("\n") { it.content }

        val declared = linkedSetOf<String>()
        val superTypes = linkedSetOf<String>()

        DECLARATION.findAll(relevant).forEach { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { declared += it }
        }

        SUPERTYPE.findAll(relevant).forEach { match ->
            match.groupValues[1]
                .split(',')
                .forEach { candidate -> typeName(candidate)?.let { superTypes += it } }
        }

        val imports = IMPORT.findAll(relevant)
            .mapNotNull { match -> match.groupValues.drop(1).firstOrNull { it.isNotBlank() } }
            .map { it.trim().trim(';', '"', '\'') }
            .distinct()
            .toList()

        val fileStem = path.substringAfterLast('/').substringBeforeLast('.')
        if (fileStem.isNotBlank() && fileStem.first().isUpperCase()) {
            declared += fileStem
        }

        val referenced = TYPE_REFERENCE.findAll(relevant)
            .map { it.value }
            .filter { it.length > 3 && it !in declared && it !in NOISE_TYPES }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(MAX_REFERENCED_TYPES)
            .map { it.key }

        return ChangedFileQuery(
            path = path,
            declaredSymbols = declared.toList(),
            superTypes = superTypes.toList(),
            imports = imports,
            referencedTypes = referenced
        )
    }

    private fun typeName(raw: String): String? {
        val cleaned = raw.trim()
            .substringBefore('<')
            .substringBefore('(')
            .substringAfterLast('.')
            .trim()
        return cleaned.takeIf { it.isNotBlank() && it.first().isUpperCase() && it !in NOISE_TYPES }
    }

    private companion object {
        const val MAX_REFERENCED_TYPES = 20

        val DECLARATION = Regex(
            """(?m)^\s*(?:@\w+\s+)*(?:public |private |internal |protected |open |abstract |final |sealed |data |export |default )*""" +
                """(?:class|interface|object|enum class|enum|record|trait|struct|type)\s+([A-Z][A-Za-z0-9_]*)""" +
                """|(?m)^\s*(?:public |private |internal |protected |suspend |static |async |override |fun|def|func)\s+""" +
                """(?:fun\s+|def\s+|func\s+)?([a-z][A-Za-z0-9_]*)\s*\("""
        )

        /** Um único grupo de captura: a lista de supertipos após `:`/`extends`/`implements`. */
        val SUPERTYPE = Regex(
            """(?:class|interface|object|record|struct|trait)\s+[A-Z][A-Za-z0-9_]*(?:<[^>]*>)?\s*""" +
                """(?:\([^)]*\))?\s*(?::|extends|implements)\s+([^{\n]+)"""
        )

        val IMPORT = Regex(
            """(?m)^\s*import\s+(?:static\s+)?([A-Za-z0-9_.*]+)""" +
                """|(?m)^\s*(?:import|export)\s+(?:type\s+)?\{?[^}]*}?\s*from\s+["']([^"']+)["']""" +
                """|(?m)^\s*from\s+([A-Za-z0-9_.]+)\s+import"""
        )

        val TYPE_REFERENCE = Regex("""\b[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+\b""")

        val NOISE_TYPES = setOf(
            "String", "Integer", "Boolean", "Double", "Float", "Long", "Object", "List", "Map",
            "Set", "ArrayList", "HashMap", "HashSet", "Optional", "Override", "Nullable",
            "NotNull", "Exception", "RuntimeException", "IllegalArgumentException",
            "IllegalStateException", "BigDecimal", "BigInteger", "LocalDate", "LocalDateTime",
            "Instant", "UUID", "Unit", "Any", "Nothing", "Deprecated", "Suppress", "JvmStatic",
            "Serializable", "SerialName", "Test", "BeforeEach", "AfterEach", "DisplayName"
        )
    }
}
