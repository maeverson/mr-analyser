package com.mranalyser.domain.security

/**
 * Mascara valores que parecem credenciais antes de enviar conteúdo ao LLM.
 *
 * A versão anterior redigia qualquer atribuição cujo identificador contivesse
 * `token|secret|password|key`, o que transformava `val apiKey = config.apiKey` em
 * `val apiKey =<REDACTED>` e destruía contexto legítimo de review — justamente nos trechos
 * onde a análise de segurança é mais necessária.
 *
 * Aqui só é redigido o **valor literal** e apenas quando ele realmente parece um segredo:
 * literal de string/número com tamanho mínimo, sem interpolação e sem referência a símbolo.
 */
class SecretRedactor {
    // O prefixo é opcional: sem isso, `apiKey = "..."` não casava, porque o identificador é
    // exatamente a palavra-chave e o padrão exigia ao menos um caractere antes dela.
    private val assignment = Regex(
        """(?i)\b([A-Za-z0-9_.\-]*(?:password|passwd|secret|token|api[_-]?key|apikey|access[_-]?key|private[_-]?key|credential|authorization)[A-Za-z0-9_.\-]*)\s*(=|:|=>)\s*(.+)"""
    )

    private val privateKeyBlock = Regex(
        """-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""
    )

    private val bearerToken = Regex("""(?i)\b(Bearer|Basic)\s+([A-Za-z0-9._\-+/=]{16,})""")

    fun redact(input: String): String {
        if (input.isBlank()) {
            return input
        }

        var output = privateKeyBlock.replace(input, "-----BEGIN PRIVATE KEY-----<REDACTED>-----END PRIVATE KEY-----")
        output = bearerToken.replace(output) { match -> "${match.groupValues[1]} <REDACTED>" }
        output = assignment.replace(output) { match ->
            val name = match.groupValues[1]
            val operator = match.groupValues[2]
            val rawValue = match.groupValues[3]
            if (looksLikeLiteralSecret(rawValue)) {
                "$name $operator <REDACTED>"
            } else {
                match.value
            }
        }
        return output
    }

    /**
     * `true` apenas para literais opacos. Referências a símbolos, chamadas de função,
     * placeholders e valores curtos são preservados porque são exatamente o que o reviewer
     * precisa enxergar.
     */
    fun looksLikeLiteralSecret(rawValue: String): Boolean {
        val value = rawValue.trim().trimEnd(',', ';', ')')
        val unquoted = value.removeSurrounding("\"").removeSurrounding("'")

        if (unquoted.length < 12) return false
        if (unquoted.equals("null", ignoreCase = true)) return false

        // Interpolação, referência a variável, chamada de função ou placeholder de template.
        val referencePatterns = listOf(
            Regex("""\$\{?"""),
            Regex("""\w+\s*\("""),
            Regex("""\w+\.\w+"""),
            Regex("""^\{\{.*}}$"""),
            Regex("""^<.*>$"""),
            Regex("""(?i)^(env|process|system|config|secrets?|vault)\b""")
        )
        if (referencePatterns.any { it.containsMatchIn(unquoted) }) return false

        // Placeholders explícitos usados em exemplos e documentação.
        if (Regex("""(?i)^(x{3,}|\*{3,}|changeme|your[_-]?\w+|todo|example|placeholder|redacted)""")
                .containsMatchIn(unquoted)
        ) {
            return false
        }

        val hasLetter = unquoted.any { it.isLetter() }
        val hasDigitOrSymbol = unquoted.any { it.isDigit() || it in "+/=_-." }
        return hasLetter && hasDigitOrSymbol
    }
}
