package com.mranalyser.application.llm.parser

/**
 * Extrai o objeto JSON de uma resposta de LLM (item 38).
 *
 * Modelos frequentemente devolvem: cerca markdown, texto explicativo antes/depois, dois objetos
 * seguidos, vírgula sobrando, ou raciocínio em `<think>`. A V1 fazia
 * `indexOf('{')`..`lastIndexOf('}')`, o que quebra quando há qualquer chave depois do objeto
 * ou quando há mais de um objeto na resposta.
 *
 * Estratégia: remover envelopes conhecidos, varrer objetos balanceados respeitando strings e
 * escapes, e devolver os candidatos do maior para o menor, para que o chamador tente
 * desserializar em ordem.
 */
object JsonExtractor {
    private val thinkBlock = Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)
    private val codeFence = Regex("""```[a-zA-Z]*\s*""")
    private val trailingComma = Regex(""",(\s*[}\]])""")

    fun candidates(raw: String): List<String> {
        if (raw.isBlank()) {
            return emptyList()
        }

        val cleaned = raw
            .replace(thinkBlock, " ")
            .replace(codeFence, " ")
            // Modelos as vezes emitem espaco nao-quebravel, que o parser JSON rejeita.
            .replace('\u00A0', ' ')

        return balancedObjects(cleaned)
            .map { normalize(it) }
            .distinct()
            .sortedByDescending { it.length }
    }

    private fun normalize(candidate: String): String = trailingComma.replace(candidate, "$1").trim()

    /**
     * Varre todos os objetos JSON balanceados de nível superior a partir de cada `{`,
     * ignorando chaves dentro de strings.
     */
    private fun balancedObjects(text: String): List<String> {
        val results = mutableListOf<String>()
        var index = 0

        while (index < text.length && results.size < MAX_CANDIDATES) {
            if (text[index] != '{') {
                index++
                continue
            }

            val end = findMatchingBrace(text, index)
            if (end == null) {
                index++
                continue
            }

            results += text.substring(index, end + 1)
            // Avança para depois do objeto encontrado: objetos aninhados não são candidatos úteis.
            index = end + 1
        }

        return results
    }

    private fun findMatchingBrace(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val char = text[i]

            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return i
                    }
                }
            }
        }

        return null
    }

    private const val MAX_CANDIDATES = 12
}
