package com.mranalyser

import com.mranalyser.application.review.ChangeClassifier
import com.mranalyser.domain.diff.UnifiedDiffParser
import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import com.mranalyser.domain.rule.DebugCodeRule
import com.mranalyser.domain.rule.LargeChangeRule
import com.mranalyser.domain.rule.MissingTestCoverageRule
import com.mranalyser.domain.rule.ReviewRule
import com.mranalyser.domain.rule.RuleContext
import com.mranalyser.domain.rule.SecretsRule
import com.mranalyser.domain.rule.TodoRule
import com.mranalyser.domain.security.SecretRedactor
import com.mranalyser.support.MergeRequestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretsRuleTest {

    @Test
    fun `deve apontar apenas a credencial literal e ignorar referencias`() {
        val findings = run(SecretsRule(), MergeRequestFixtures.leakedSecretMr())

        assertEquals(1, findings.size, "referências a símbolo e getenv não são segredo: $findings")
        val finding = findings.single()
        assertEquals(Severity.CRITICAL, finding.severity)
        assertTrue(finding.title.contains("apiKey"))
        assertTrue(finding.blocking)
        assertTrue(finding.recommendation!!.contains("rotacionar"), "rotação é obrigatória após vazamento")
    }

    @Test
    fun `deve usar o numero de linha real do arquivo`() {
        val change = MergeRequestFixtures.change(
            "src/main/kotlin/Config.kt",
            "@@ -40,2 +40,3 @@ object Config {\n val a = 1\n+    const val apiToken = \"tok_live_82ha91kd0192\"\n"
        )
        val mergeRequest = MergeRequestFixtures.mergeRequest(changes = listOf(change))

        val finding = run(SecretsRule(), mergeRequest).single()

        assertEquals(41, finding.line, "linha deve vir do cabeçalho do hunk, não do índice no texto do diff")
    }

    @Test
    fun `nao deve disparar em arquivo de exemplo`() {
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(
                MergeRequestFixtures.change(
                    ".mranalyser.properties.example",
                    "@@ -1 +1,2 @@\n+MR_ANALYSER_LLM_API_KEY=sk-live-9f2b71ac44de8810"
                )
            )
        )

        assertTrue(run(SecretsRule(), mergeRequest).isEmpty())
    }

    @Test
    fun `deve rebaixar severidade em codigo de teste`() {
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(
                MergeRequestFixtures.change(
                    "src/test/kotlin/ClientTest.kt",
                    "@@ -1 +1,2 @@\n+    private val apiKey = \"test-key-8817263shd\""
                )
            )
        )

        val finding = run(SecretsRule(), mergeRequest).single()

        assertEquals(Severity.MEDIUM, finding.severity)
        assertFalse(finding.blocking)
    }
}

class SecretRedactorTest {
    private val redactor = SecretRedactor()

    @Test
    fun `deve preservar referencias a simbolo`() {
        val input = "val apiKey = config.apiKey\nval token = System.getenv(\"TOKEN\")"

        assertEquals(input, redactor.redact(input), "referência a símbolo é contexto legítimo de review")
    }

    @Test
    fun `deve mascarar literal opaco`() {
        val redacted = redactor.redact("const val apiKey = \"sk-live-9f2b71ac44de8810bb3d\"")

        assertTrue(redacted.contains("<REDACTED>"))
        assertFalse(redacted.contains("9f2b71ac44de8810bb3d"))
    }

    @Test
    fun `deve mascarar chave privada e bearer token`() {
        val key = redactor.redact("-----BEGIN RSA PRIVATE KEY-----\nMIIEabc\n-----END RSA PRIVATE KEY-----")
        assertTrue(key.contains("<REDACTED>"))
        assertFalse(key.contains("MIIEabc"))

        val bearer = redactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
        assertTrue(bearer.contains("Bearer <REDACTED>"))
    }

    @Test
    fun `deve preservar placeholders de documentacao`() {
        listOf("password = \"<seu-password-aqui>\"", "apiKey = \"CHANGEME-please-set\"").forEach { input ->
            assertFalse(redactor.redact(input).contains("<REDACTED>"), input)
        }
    }
}

class DebugCodeRuleTest {

    @Test
    fun `deve agrupar ocorrencias em um unico finding sem comentario de gitlab`() {
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(
                MergeRequestFixtures.change(
                    "src/main/kotlin/domain/Order.kt",
                    """
                    @@ -1,2 +1,5 @@
                     class Order {
                    +    fun a() { System.out.println("a") }
                    +    fun b() { System.out.println("b") }
                     }
                    """.trimIndent()
                )
            )
        )

        val finding = run(DebugCodeRule(), mergeRequest).single()

        assertEquals(Severity.LOW, finding.severity)
        assertEquals(CommentType.OBSERVATION, finding.commentType)
        assertNull(finding.suggestedComment, "item 18: não interromper o autor com isso")
        assertTrue(finding.evidence!!.contains("2 ocorrência"))
    }

    @Test
    fun `printStackTrace eleva severidade e ganha cenario de falha`() {
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(
                MergeRequestFixtures.change(
                    "src/main/kotlin/domain/Order.kt",
                    "@@ -1,2 +1,4 @@\n class Order {\n+    catch (e: Exception) { e.printStackTrace() }\n }"
                )
            )
        )

        val finding = run(DebugCodeRule(), mergeRequest).single()

        assertEquals(Severity.MEDIUM, finding.severity)
        assertEquals(FindingType.RISK, finding.type)
        assertTrue(finding.failureScenario != null)
    }

    @Test
    fun `nao deve disparar em teste nem em cli`() {
        listOf("src/test/kotlin/OrderTest.kt", "src/main/kotlin/cli/AnalyseCommand.kt").forEach { path ->
            val mergeRequest = MergeRequestFixtures.mergeRequest(
                changes = listOf(MergeRequestFixtures.change(path, "@@ -1 +1,2 @@\n+    println(\"debug\")"))
            )
            assertTrue(run(DebugCodeRule(), mergeRequest).isEmpty(), path)
        }
    }

    @Test
    fun `nao deve disparar em linha comentada`() {
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(
                MergeRequestFixtures.change(
                    "src/main/kotlin/domain/Order.kt",
                    "@@ -1 +1,2 @@\n+    // System.out.println(\"antigo debug\")"
                )
            )
        )

        assertTrue(run(DebugCodeRule(), mergeRequest).isEmpty())
    }
}

class TodoRuleTest {

    @Test
    fun `deve agrupar marcadores e nao sugerir comentario`() {
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(
                MergeRequestFixtures.change(
                    "src/main/kotlin/A.kt",
                    "@@ -1 +1,3 @@\n+    // TODO tratar erro\n+    // FIXME calculo errado"
                )
            )
        )

        val finding = run(TodoRule(), mergeRequest).single()

        assertEquals(Severity.LOW, finding.severity, "FIXME/HACK elevam para LOW")
        assertNull(finding.suggestedComment)
        assertTrue(finding.title.contains("TODO") && finding.title.contains("FIXME"))
    }

    @Test
    fun `deve ignorar marcador que ja referencia issue`() {
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(
                MergeRequestFixtures.change(
                    "src/main/kotlin/A.kt",
                    "@@ -1 +1,2 @@\n+    // TODO(PROJ-1234): tratar erro"
                )
            )
        )

        assertTrue(run(TodoRule(), mergeRequest).isEmpty(), "rastreamento já existe")
    }
}

class LargeChangeRuleTest {

    @Test
    fun `deve sinalizar como observacao sem bloquear`() {
        val diff = "@@ -1 +1,30 @@\n" + (1..30).joinToString("\n") { "+linha$it" }
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(MergeRequestFixtures.change("src/main/kotlin/domain/Big.kt", diff))
        )

        val finding = run(LargeChangeRule(maxLinesPerFile = 10), mergeRequest).single()

        assertEquals(Severity.INFO, finding.severity)
        assertFalse(finding.blocking)
        assertNull(finding.suggestedComment)
        assertTrue(finding.description.contains("análise automatizada deste arquivo é parcial"))
    }

    @Test
    fun `nao deve sinalizar arquivo gerado`() {
        val diff = "@@ -1 +1,30 @@\n" + (1..30).joinToString("\n") { "+linha$it" }
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(MergeRequestFixtures.change("package-lock.json", diff, generated = true))
        )

        assertTrue(run(LargeChangeRule(maxLinesPerFile = 10), mergeRequest).isEmpty())
    }
}

class MissingTestCoverageRuleTest {

    @Test
    fun `deve gerar questionamento nao bloqueante quando nao ha teste no MR`() {
        val finding = run(MissingTestCoverageRule(), MergeRequestFixtures.missingTestsMr()).single()

        assertEquals(FindingType.QUESTION, finding.type)
        assertEquals(CommentType.QUESTION, finding.commentType)
        assertFalse(finding.blocking)
        assertTrue(
            finding.description.contains("não prova ausência de cobertura"),
            "ausência de arquivo de teste no diff não prova ausência de cobertura"
        )
    }

    @Test
    fun `nao deve gerar finding quando o MR altera testes`() {
        val mergeRequest = MergeRequestFixtures.missingTestsMr().let { base ->
            base.copy(
                changes = base.changes + MergeRequestFixtures.change(
                    "src/test/kotlin/tax/TaxCalculatorTest.kt",
                    "@@ -1 +1,2 @@\n+    @Test fun deveCalcular() {}"
                )
            )
        }

        assertTrue(run(MissingTestCoverageRule(), mergeRequest).isEmpty())
    }

    @Test
    fun `nao deve gerar finding para mudanca apenas de configuracao`() {
        val diff = "@@ -1 +1,100 @@\n" + (1..100).joinToString("\n") { "+chave$it: valor" }
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(MergeRequestFixtures.change("src/main/resources/application.yml", diff))
        )

        assertTrue(run(MissingTestCoverageRule(), mergeRequest).isEmpty())
    }

    @Test
    fun `deve emitir um unico finding por MR`() {
        val diff = "@@ -1 +1,100 @@\n" + (1..100).joinToString("\n") { "+val x$it = $it" }
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(
                MergeRequestFixtures.change("src/main/kotlin/domain/A.kt", diff),
                MergeRequestFixtures.change("src/main/kotlin/domain/B.kt", diff)
            )
        )

        assertEquals(1, run(MissingTestCoverageRule(), mergeRequest).size)
    }
}

private fun run(rule: ReviewRule, mergeRequest: MergeRequest): List<ReviewFinding> {
    val classifier = ChangeClassifier()
    return mergeRequest.changes.flatMap { change ->
        val parsed = UnifiedDiffParser.parse(change.diff)
        val context = RuleContext(
            mergeRequest = mergeRequest,
            change = change,
            parsedDiff = parsed,
            group = classifier.classify(change, parsed)
        )
        if (rule.supports(context)) rule.analyse(context) else emptyList()
    }
}
