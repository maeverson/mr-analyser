package com.mranalyser.application.llm.skill

/**
 * Conversão de glob para regex, compartilhada por `ignoredPaths` e pela seleção de skills.
 *
 * A versão anterior usava `Regex.escape(glob)` e depois tentava substituir a estrela no
 * resultado. `Regex.escape` devolve `\Q...\E`, então as substituições nunca casavam e
 * **nenhum wildcard funcionava**: um `ignoredPaths` com `*.lock` ou `generated` seguido de
 * estrela dupla não filtrava nada. Aqui o escape é feito caractere a caractere, preservando
 * estrela simples, estrela dupla e interrogação.
 */
object PathGlob {
    fun toRegex(glob: String): Regex {
        val pattern = StringBuilder("^")
        var index = 0

        while (index < glob.length) {
            when (val char = glob[index]) {
                '*' -> if (index + 1 < glob.length && glob[index + 1] == '*') {
                    // "**/" também casa com zero diretórios: "**/*.kt" deve aceitar "Main.kt".
                    if (index + 2 < glob.length && glob[index + 2] == '/') {
                        pattern.append("(.*/)?")
                        index += 2
                    } else {
                        pattern.append(".*")
                        index++
                    }
                } else {
                    pattern.append("[^/]*")
                }

                '?' -> pattern.append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                    pattern.append('\\').append(char)

                else -> pattern.append(char)
            }
            index++
        }

        // "generated/**" deve casar também com o próprio diretório e com "generated/a/b.kt".
        return Regex(pattern.append('$').toString().replace("/.*$", "(/.*)?$"))
    }
}
