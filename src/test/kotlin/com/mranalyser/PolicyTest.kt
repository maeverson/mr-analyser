package com.mranalyser

import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.FindingScope
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import com.mranalyser.domain.policy.BlockingPolicy
import com.mranalyser.domain.policy.EvidencePolicy
import com.mranalyser.domain.policy.NoisePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlockingPolicyTest {
    private val policy = BlockingPolicy()

    @Test
    fun `HIGH sem cenario de falha nao bloqueia`() {
        val finding = finding(
            severity = Severity.HIGH,
            type = FindingType.RISK,
            evidence = "código chama o provider antes de persistir",
            failureScenario = null,
            confidence = 0.9
        )

        assertFalse(policy.evaluate(finding))
    }

    @Test
    fun `HIGH com evidencia e cenario de falha bloqueia`() {
        val finding = finding(
            severity = Severity.HIGH,
            type = FindingType.BUG,
            evidence = "InvoiceService.kt:84 chama capture() antes de save()",
            failureScenario = "1. capture ok\n2. save falha\n3. pagamento sem invoice",
            confidence = 0.9
        )

        assertTrue(policy.evaluate(finding))
    }

    @Test
    fun `MEDIUM com corrupcao garantida em cenario especifico bloqueia`() {
        val finding = finding(
            severity = Severity.MEDIUM,
            type = FindingType.BUG,
            evidence = "Migration adiciona coluna NOT NULL sem default",
            failureScenario = "1. migration roda em tabela populada\n2. falha\n3. deploy interrompido",
            confidence = 0.9
        )

        assertTrue(policy.evaluate(finding))
    }

    @Test
    fun `CRITICAL sem evidencia nao bloqueia`() {
        val finding = finding(
            severity = Severity.CRITICAL,
            type = FindingType.BUG,
            evidence = null,
            failureScenario = null,
            confidence = 0.95
        )

        assertFalse(policy.evaluate(finding), "severidade sozinha não determina bloqueio")
    }

    @Test
    fun `questionamento e sugestao nunca bloqueiam`() {
        listOf(FindingType.QUESTION, FindingType.SUGGESTION).forEach { type ->
            val finding = finding(
                severity = Severity.CRITICAL,
                type = type,
                evidence = "evidência",
                failureScenario = "1. algo\n2. falha",
                confidence = 0.99
            )
            assertFalse(policy.evaluate(finding), "$type não pode bloquear")
        }
    }

    @Test
    fun `finding pre-existente nunca bloqueia`() {
        val finding = finding(
            severity = Severity.CRITICAL,
            type = FindingType.BUG,
            evidence = "evidência",
            failureScenario = "1. algo\n2. falha",
            confidence = 0.99
        ).copy(scope = FindingScope.PRE_EXISTING)

        assertFalse(policy.evaluate(finding))
    }

    @Test
    fun `modelo pode rebaixar mas nao promover para bloqueante`() {
        val base = finding(
            severity = Severity.HIGH,
            type = FindingType.BUG,
            evidence = "evidência",
            failureScenario = "1. algo\n2. falha",
            confidence = 0.9
        )

        assertFalse(
            policy.evaluate(base.copy(blocking = false, commentType = CommentType.SUGGESTION)),
            "modelo pode rebaixar via commentType"
        )
        assertTrue(policy.evaluate(base.copy(blocking = false, commentType = CommentType.BLOCKER)))
    }

    @Test
    fun `revogacao deve transformar bloqueador nao validado em questionamento`() {
        val blocker = policy.apply(
            finding(
                severity = Severity.CRITICAL,
                type = FindingType.BUG,
                evidence = "evidência",
                failureScenario = "1. algo\n2. falha",
                confidence = 0.95
            )
        )
        assertTrue(blocker.blocking)

        val revoked = policy.revokeBlocking(blocker)

        assertFalse(revoked.blocking)
        assertEquals(FindingType.QUESTION, revoked.type)
        assertEquals(CommentType.QUESTION, revoked.commentType)
    }

    @Test
    fun `revogacao nao altera finding que ja nao bloqueava`() {
        val suggestion = policy.apply(
            finding(severity = Severity.LOW, type = FindingType.SUGGESTION, confidence = 0.8)
        )

        assertEquals(suggestion, policy.revokeBlocking(suggestion))
    }

    @Test
    fun `deve preencher commentType coerente com a decisao`() {
        val blocker = policy.apply(
            finding(
                severity = Severity.HIGH,
                type = FindingType.BUG,
                evidence = "evidência",
                failureScenario = "1. algo\n2. falha",
                confidence = 0.9
            )
        )
        assertEquals(CommentType.BLOCKER, blocker.commentType)

        val question = policy.apply(
            finding(severity = Severity.MEDIUM, type = FindingType.QUESTION, confidence = 0.7)
        )
        assertEquals(CommentType.QUESTION, question.commentType)

        val observation = policy.apply(
            finding(severity = Severity.INFO, type = FindingType.DESIGN, confidence = 0.7)
        )
        assertEquals(CommentType.OBSERVATION, observation.commentType)
    }
}

class EvidencePolicyTest {
    private val policy = EvidencePolicy()

    @Test
    fun `HIGH sem evidencia vira questionamento com severidade limitada`() {
        val result = policy.apply(
            finding(severity = Severity.HIGH, type = FindingType.RISK, confidence = 0.8)
        )

        assertEquals(FindingType.QUESTION, result.type)
        assertEquals(Severity.MEDIUM, result.severity)
        assertFalse(result.blocking)
        assertEquals(CommentType.QUESTION, result.commentType)
    }

    @Test
    fun `HIGH com cenario de falha e preservado`() {
        val result = policy.apply(
            finding(
                severity = Severity.HIGH,
                type = FindingType.BUG,
                failureScenario = "1. algo\n2. falha",
                confidence = 0.8
            )
        )

        assertEquals(FindingType.BUG, result.type)
        assertEquals(Severity.HIGH, result.severity)
    }

    @Test
    fun `LOW sem evidencia permanece inalterado`() {
        val original = finding(severity = Severity.LOW, type = FindingType.SUGGESTION, confidence = 0.7)

        assertEquals(original, policy.apply(original))
    }
}

class NoisePolicyTest {

    @Test
    fun `deve suprimir estilo sem impacto declarado`() {
        val decision = NoisePolicy().evaluate(
            finding(
                severity = Severity.LOW,
                type = FindingType.SUGGESTION,
                category = ReviewCategory.CODE_STYLE,
                confidence = 0.9
            )
        )

        assertTrue(decision.suppressed)
    }

    @Test
    fun `deve suprimir categoria ignorada por configuracao`() {
        val decision = NoisePolicy(ignoredCategories = setOf("MAINTAINABILITY")).evaluate(
            finding(severity = Severity.MEDIUM, category = ReviewCategory.MAINTAINABILITY, confidence = 0.9)
        )

        assertTrue(decision.suppressed)
        assertTrue(decision.reason!!.contains("MAINTAINABILITY"))
    }

    @Test
    fun `deve suprimir descricao curta demais para ser acionavel`() {
        val decision = NoisePolicy().evaluate(
            finding(severity = Severity.MEDIUM, confidence = 0.9).copy(title = "Bug", description = "ruim")
        )

        assertTrue(decision.suppressed)
    }

    @Test
    fun `deve manter bug com descricao adequada`() {
        val decision = NoisePolicy().evaluate(
            finding(severity = Severity.HIGH, category = ReviewCategory.BUG, confidence = 0.9)
        )

        assertFalse(decision.suppressed)
    }

    @Test
    fun `nao deve recomendar comentario de gitlab para estilo nem para INFO`() {
        val policy = NoisePolicy()

        assertFalse(
            policy.deservesGitLabComment(
                finding(severity = Severity.LOW, category = ReviewCategory.CODE_STYLE, confidence = 0.9)
                    .copy(suggestedComment = "renomear a variável")
            )
        )
        assertFalse(
            policy.deservesGitLabComment(
                finding(severity = Severity.INFO, confidence = 0.9).copy(suggestedComment = "observação")
            )
        )
        assertFalse(
            policy.deservesGitLabComment(finding(severity = Severity.HIGH, confidence = 0.9)),
            "sem comentário sugerido não há o que publicar"
        )
        assertTrue(
            policy.deservesGitLabComment(
                finding(severity = Severity.HIGH, confidence = 0.9).copy(suggestedComment = "podemos tratar isso?")
            )
        )
    }
}

private fun finding(
    severity: Severity,
    type: FindingType = FindingType.RISK,
    category: ReviewCategory = ReviewCategory.RELIABILITY,
    evidence: String? = null,
    failureScenario: String? = null,
    confidence: Double
): ReviewFinding = ReviewFinding(
    severity = severity,
    category = category,
    type = type,
    file = "src/main/kotlin/A.kt",
    line = 10,
    title = "Título representativo do finding",
    description = "Descrição com tamanho suficiente para passar pelo filtro de ruído.",
    evidence = evidence,
    failureScenario = failureScenario,
    impact = null,
    recommendation = null,
    suggestedComment = null,
    confidence = confidence
)
