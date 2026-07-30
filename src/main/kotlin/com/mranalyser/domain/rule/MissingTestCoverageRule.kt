package com.mranalyser.domain.rule

import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.FindingOrigin
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

/**
 * Substitui a `TestChangeRule` da V1, que emitia HIGH/TESTABILITY com o comentário genérico
 * "Temos testes cobrindo os cenários alterados neste MR?".
 *
 * Duas mudanças de julgamento:
 * 1. o achado é um **questionamento**, não uma acusação: a ausência de arquivo de teste no diff
 *    não prova ausência de cobertura (o teste pode já existir e continuar passando);
 * 2. só dispara para grupos onde comportamento novo realmente exige proteção — mudança apenas em
 *    configuração, documentação ou build não gera o achado.
 *
 * O item 5.11 pede cenário concreto em vez de "adicionar mais testes". Nomear o cenário exige
 * conhecer o comportamento novo, o que é papel da etapa LLM; esta regra apenas garante que o
 * tema não passe em branco quando o LLM está indisponível.
 */
class MissingTestCoverageRule(
    private val significantThreshold: Int = 80
) : ReviewRule {
    override val name: String = "missing-test-coverage"

    override fun supports(context: RuleContext): Boolean {
        if (context.change.deleted || context.change.generated) {
            return false
        }
        if (context.group !in BEHAVIOUR_GROUPS) {
            return false
        }
        if (context.change.totalLines < significantThreshold) {
            return false
        }
        // Emite um único achado por MR, ancorado no maior arquivo de comportamento alterado.
        return context.change.path == anchorPath(context)
    }

    override fun analyse(context: RuleContext): List<ReviewFinding> {
        val testFilesTouched = context.mergeRequest.changes.any { isTestPath(it.newPath) || isTestPath(it.oldPath) }
        if (testFilesTouched) {
            return emptyList()
        }

        val behaviourFiles = context.mergeRequest.changes
            .filter { !isTestPath(it.newPath) && it.totalLines >= significantThreshold }
            .map { it.path }

        return listOf(
            ReviewFinding(
                severity = Severity.MEDIUM,
                category = ReviewCategory.TESTABILITY,
                type = FindingType.QUESTION,
                file = context.change.path,
                line = null,
                title = "Alteração de comportamento sem teste no MR",
                description = "O MR altera comportamento em ${behaviourFiles.size} arquivo(s) de produção " +
                    "sem incluir nenhum arquivo de teste. Isso não prova ausência de cobertura — os testes " +
                    "existentes podem já cobrir o caminho — mas vale confirmar quais cenários novos estão protegidos.",
                evidence = "Arquivos de produção alterados sem teste correspondente no MR: " +
                    behaviourFiles.joinToString(", "),
                failureScenario = null,
                impact = "Comportamento novo sem verificação automatizada aumenta o risco de regressão silenciosa.",
                recommendation = "Confirmar quais cenários novos estão cobertos pelos testes existentes e, " +
                    "se algum caminho de falha ficou descoberto, adicionar teste para ele.",
                blocking = false,
                commentType = CommentType.QUESTION,
                suggestedComment = "Não vi alteração em testes neste MR. Os cenários novos já são cobertos " +
                    "por testes existentes, ou vale adicionar cobertura para o caminho de falha?",
                origin = FindingOrigin.STATIC_RULE,
                confidence = 0.70
            )
        )
    }

    private fun anchorPath(context: RuleContext): String? =
        context.mergeRequest.changes
            .filter { !isTestPath(it.newPath) && !it.generated && it.totalLines >= significantThreshold }
            .maxByOrNull { it.totalLines }
            ?.path

    private fun isTestPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("/test/") ||
            lower.contains("/tests/") ||
            lower.startsWith("test/") ||
            lower.contains("/spec/") ||
            lower.contains("__tests__") ||
            TEST_SUFFIX.containsMatchIn(lower)
    }

    private companion object {
        val BEHAVIOUR_GROUPS = setOf(
            ChangeGroup.DOMAIN,
            ChangeGroup.APPLICATION,
            ChangeGroup.PERSISTENCE,
            ChangeGroup.INTEGRATION,
            ChangeGroup.API,
            ChangeGroup.MESSAGING
        )

        val TEST_SUFFIX = Regex(
            """(test|tests|it|itcase|spec)\.(kt|java|scala|groovy)$|[._-](test|spec)\.(ts|tsx|js|jsx|py|go|rb)$"""
        )
    }
}
