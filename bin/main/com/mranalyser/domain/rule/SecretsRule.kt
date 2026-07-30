package com.mranalyser.domain.rule

import com.mranalyser.domain.security.SecretRedactor
import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.FindingOrigin
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

/**
 * Detecta credencial literal adicionada no diff.
 *
 * A V1 disparava CRITICAL para qualquer linha contendo `password =`, o que incluía
 * `val password = user.password` e `apiKey = config.apiKey` — falso positivo CRITICAL, o pior
 * tipo, porque desgasta a confiança em todo o relatório. Aqui só dispara quando o valor
 * atribuído é um **literal opaco**, e a verificação de literal é a mesma usada pelo
 * [SecretRedactor], garantindo comportamento consistente.
 */
class SecretsRule(
    private val redactor: SecretRedactor = SecretRedactor()
) : ReviewRule {
    override val name: String = "secrets"

    override fun supports(context: RuleContext): Boolean =
        !context.change.deleted &&
            !context.change.generated &&
            context.group != ChangeGroup.DOCUMENTATION &&
            !isExample(context.change.path)

    override fun analyse(context: RuleContext): List<ReviewFinding> {
        return context.parsedDiff.addedLines.mapNotNull { line ->
            val match = ASSIGNMENT.find(line.content) ?: return@mapNotNull null
            val identifier = match.groupValues[1]
            val value = match.groupValues[3]

            if (!redactor.looksLikeLiteralSecret(value)) {
                return@mapNotNull null
            }

            ReviewFinding(
                severity = if (context.group == ChangeGroup.TEST) Severity.MEDIUM else Severity.CRITICAL,
                category = ReviewCategory.SECURITY,
                type = FindingType.BUG,
                file = context.change.path,
                line = line.newLine,
                title = "Credencial literal adicionada em `$identifier`",
                description = "A linha adicionada atribui um valor literal a `$identifier`, " +
                    "padrão típico de credencial versionada no repositório.",
                evidence = "${context.change.path}:${line.newLine} atribui literal a `$identifier` " +
                    "(valor omitido deste relatório).",
                failureScenario = "1. o valor é versionado no repositório\n" +
                    "2. qualquer pessoa com acesso ao histórico do Git obtém a credencial\n" +
                    "3. remover a linha depois não invalida o segredo, que permanece no histórico\n" +
                    "4. acesso indevido ao recurso protegido por essa credencial",
                impact = "Vazamento de credencial com acesso ao ambiente e aos dados protegidos por ela.",
                recommendation = "Remover o valor do código, mover para variável de ambiente ou cofre de " +
                    "segredos e **rotacionar a credencial**, já que ela está no histórico do Git.",
                blocking = context.group != ChangeGroup.TEST,
                commentType = CommentType.BLOCKER,
                suggestedComment = "Esse valor de `$identifier` parece ser uma credencial literal. " +
                    "Podemos movê-lo para variável de ambiente ou cofre? Como já entrou no histórico do Git, " +
                    "vale rotacionar a credencial mesmo depois de remover a linha.",
                origin = FindingOrigin.STATIC_RULE,
                confidence = 0.90
            )
        }
    }

    private fun isExample(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains(".example") ||
            lower.contains(".sample") ||
            lower.contains("/fixtures/") ||
            lower.endsWith(".md")
    }

    private companion object {
        // Prefixo opcional: `apiKey = "..."` deve casar, e não só `myApiKey = "..."`.
        val ASSIGNMENT = Regex(
            """(?i)\b([A-Za-z0-9_.\-]*(?:password|passwd|secret|token|api[_-]?key|apikey|""" +
                """access[_-]?key|private[_-]?key|credential)[A-Za-z0-9_.\-]*)\s*(=|:)\s*(.+)"""
        )
    }
}
