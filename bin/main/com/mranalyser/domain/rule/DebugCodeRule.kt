package com.mranalyser.domain.rule

import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.FindingOrigin
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

/**
 * Detecta print/stack trace de depuração deixado em código de produção.
 *
 * Ajustes em relação à V1: não dispara em testes, CLI ou scripts (onde `println` é legítimo),
 * emite um único finding por arquivo em vez de um por linha, e não sugere comentário de GitLab
 * — é observação de manutenibilidade, e o item 18 pede para não interromper o autor com isso.
 *
 * `printStackTrace` recebe tratamento distinto: além de ruído em log, indica exceção engolida
 * sem observabilidade, o que é um problema real de diagnóstico.
 */
class DebugCodeRule : ReviewRule {
    override val name: String = "debug-code"

    override fun supports(context: RuleContext): Boolean =
        !context.change.deleted &&
            !context.change.generated &&
            context.group.isProductionCode &&
            context.group != ChangeGroup.CONFIGURATION &&
            !isCliOrScript(context.change.path)

    override fun analyse(context: RuleContext): List<ReviewFinding> {
        val occurrences = context.parsedDiff.addedLines
            .filter { !isComment(it.content) }
            .mapNotNull { line ->
                DEBUG_PATTERN.find(line.content)?.let { match -> line.newLine to match.value.trim() }
            }

        if (occurrences.isEmpty()) {
            return emptyList()
        }

        val swallowedException = occurrences.any { it.second.contains("printStackTrace") }
        val locations = occurrences.mapNotNull { it.first }.joinToString(", ")

        return listOf(
            ReviewFinding(
                severity = if (swallowedException) Severity.MEDIUM else Severity.LOW,
                category = ReviewCategory.OBSERVABILITY,
                type = if (swallowedException) FindingType.RISK else FindingType.SUGGESTION,
                file = context.change.path,
                line = occurrences.first().first,
                title = if (swallowedException) {
                    "Exceção tratada com `printStackTrace` em código de produção"
                } else {
                    "Saída de depuração adicionada em código de produção"
                },
                description = if (swallowedException) {
                    "As linhas adicionadas registram exceção via stack trace em stdout/stderr, fora do " +
                        "logger da aplicação. Falhas nesse caminho não aparecem no agregador de logs."
                } else {
                    "As linhas adicionadas escrevem diretamente em stdout/stderr em vez de usar o logger."
                },
                evidence = "${context.change.path}: ${occurrences.size} ocorrência(s) de " +
                    "${occurrences.map { it.second }.distinct().joinToString(", ")} na(s) linha(s) $locations.",
                failureScenario = if (swallowedException) {
                    "1. a exceção ocorre em produção\n" +
                        "2. o stack trace vai para stdout, sem nível, sem correlação e sem contexto\n" +
                        "3. o alerta não dispara e a falha passa despercebida\n" +
                        "4. o diagnóstico depende de acesso manual ao console do processo"
                } else {
                    null
                },
                impact = if (swallowedException) {
                    "Perda de capacidade de diagnóstico e de alerta nesse caminho de erro."
                } else {
                    "Ruído em log e ausência de nível/correlação nessas mensagens."
                },
                recommendation = "Substituir por logging estruturado no nível adequado, incluindo " +
                    "identificador de correlação do contexto.",
                // Item 18: não gera comentário de GitLab; entra no relatório como observação.
                suggestedComment = null,
                commentType = CommentType.OBSERVATION,
                origin = FindingOrigin.STATIC_RULE,
                confidence = 0.88
            )
        )
    }

    private fun isCliOrScript(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("/cli/") ||
            lower.contains("/scripts/") ||
            lower.contains("/tools/") ||
            lower.endsWith("main.kt") ||
            lower.endsWith(".sh")
    }

    private fun isComment(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("//") ||
            trimmed.startsWith("*") ||
            trimmed.startsWith("/*") ||
            trimmed.startsWith("#")
    }

    private companion object {
        val DEBUG_PATTERN = Regex(
            """(System\.(out|err)\.print(ln)?|console\.(log|debug|trace)|\bprintStackTrace\s*\(|""" +
                """^\s*print(ln)?\s*\(|\bdebugger\b|\bdump\s*\()"""
        )
    }
}
