package com.mranalyser

import com.mranalyser.application.service.FindingDeduplicator
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import com.mranalyser.support.MergeRequestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FindingDeduplicatorTest {
    private val deduplicator = FindingDeduplicator()

    @Test
    fun `deve manter a versao mais bem sustentada entre duplicatas`() {
        val weaker = finding(
            title = "Chamada Redis sem timeout explicito",
            description = "A chamada pode bloquear a thread por falta de timeout explicito.",
            line = 87,
            confidence = 0.82
        )
        val stronger = weaker.copy(
            title = "Redis sem timeout explicito na chamada",
            description = "Nao existe timeout explicito e a chamada pode bloquear a thread.",
            line = 88,
            confidence = 0.91,
            evidence = "RedisRepository.kt:88 usa get() sem timeout"
        )

        val result = deduplicator.analyse(listOf(weaker, stronger), emptyList())

        assertEquals(1, result.findings.size)
        assertEquals(0.91, result.findings.single().confidence)
        assertEquals(1, result.removedAsDuplicate)
    }

    @Test
    fun `deve preservar campos que so a duplicata possuia`() {
        val winner = finding(title = "Timeout ausente", description = "Sem timeout na chamada externa", confidence = 0.9)
        val duplicate = winner.copy(
            confidence = 0.85,
            failureScenario = "1. provider fica lento\n2. thread bloqueia",
            componentsAffected = listOf("RedisClient")
        )

        val merged = deduplicator.deduplicate(listOf(winner, duplicate), emptyList()).single()

        assertNotNull(merged.failureScenario, "cenário de falha da duplicata não deve ser perdido")
        assertEquals(listOf("RedisClient"), merged.componentsAffected)
    }

    @Test
    fun `deve descartar finding que repete discussao aberta`() {
        val result = deduplicator.analyse(
            listOf(finding(title = "Timeout ausente na chamada", description = "Falta timeout explicito no client")),
            listOf(MergeRequestFixtures.discussion("Falta timeout explicito no client, podemos definir?"))
        )

        assertTrue(result.findings.isEmpty())
        assertEquals(1, result.removedAsAlreadyDiscussed)
    }

    @Test
    fun `deve manter finding quando a discussao correspondente esta resolvida`() {
        val result = deduplicator.analyse(
            listOf(finding(title = "Timeout ausente na chamada", description = "Falta timeout explicito no client")),
            listOf(
                MergeRequestFixtures.discussion(
                    "Falta timeout explicito no client, podemos definir?",
                    resolved = true
                )
            )
        )

        assertEquals(1, result.findings.size, "discussão resolvida não deve silenciar o ponto se ele reaparece")
    }

    @Test
    fun `deve ignorar notas de sistema ao comparar com discussoes`() {
        val result = deduplicator.analyse(
            listOf(finding(title = "Timeout ausente na chamada", description = "Falta timeout explicito no client")),
            listOf(
                MergeRequestFixtures.discussion(
                    "Falta timeout explicito no client, podemos definir?",
                    system = true
                )
            )
        )

        assertEquals(1, result.findings.size)
    }

    @Test
    fun `nao deve deduplicar findings de arquivos diferentes`() {
        val a = finding(title = "Timeout ausente", description = "Falta timeout explicito").copy(file = "A.kt")
        val b = finding(title = "Timeout ausente", description = "Falta timeout explicito").copy(file = "B.kt")

        assertEquals(2, deduplicator.deduplicate(listOf(a, b), emptyList()).size)
    }

    @Test
    fun `nao deve deduplicar findings distantes no mesmo arquivo`() {
        val a = finding(title = "Timeout ausente", description = "Falta timeout explicito", line = 10)
        val b = finding(title = "Timeout ausente", description = "Falta timeout explicito", line = 400)

        assertEquals(2, deduplicator.deduplicate(listOf(a, b), emptyList()).size)
    }

    /**
     * Diferente da V1, avaliar validade não é mais responsabilidade desta classe: um finding
     * vago mas legítimo passa daqui e é julgado pela etapa de validação, que tem o código.
     */
    @Test
    fun `nao deve julgar validade de finding por heuristica textual`() {
        val vague = finding(
            title = "Talvez valha considerar melhorar o nome",
            description = "Poderia considerar renomear a variavel para algo mais claro",
            confidence = 0.9
        ).copy(category = ReviewCategory.MAINTAINABILITY)

        assertEquals(1, deduplicator.deduplicate(listOf(vague), emptyList()).size)
    }

    private fun finding(
        title: String,
        description: String,
        line: Int? = 10,
        confidence: Double = 0.85
    ): ReviewFinding = ReviewFinding(
        severity = Severity.MEDIUM,
        category = ReviewCategory.PERFORMANCE,
        file = "RedisRepository.kt",
        line = line,
        title = title,
        description = description,
        impact = "latência",
        recommendation = "configurar timeout",
        suggestedComment = null,
        confidence = confidence
    )
}
