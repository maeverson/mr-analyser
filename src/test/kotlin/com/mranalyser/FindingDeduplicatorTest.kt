package com.mranalyser

import com.mranalyser.application.service.FindingDeduplicator
import com.mranalyser.domain.model.Author
import com.mranalyser.domain.model.Discussion
import com.mranalyser.domain.model.DiscussionNote
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FindingDeduplicatorTest {
    @Test
    fun `should remove duplicates and comments already discussed`() {
        val finding = ReviewFinding(
            severity = Severity.HIGH,
            category = ReviewCategory.BUG,
            file = "A.kt",
            line = 10,
            title = "Timeout ausente",
            description = "Sem timeout",
            impact = "Latencia",
            recommendation = "Adicionar timeout",
            suggestedComment = "Podemos definir timeout explicitamente?",
            confidence = 0.8
        )

        val deduplicator = FindingDeduplicator()
        val result = deduplicator.deduplicate(
            listOf(finding, finding.copy(confidence = 0.9)),
            listOf(
                Discussion(
                    id = "1",
                    notes = listOf(
                        DiscussionNote("n1", Author(name = "r"), "Podemos definir timeout explicitamente?")
                    )
                )
            )
        )

        assertEquals(0, result.size)
    }

    @Test
    fun `should merge semantically similar findings`() {
        val deduplicator = FindingDeduplicator()
        val result = deduplicator.deduplicate(
            listOf(
                ReviewFinding(
                    severity = Severity.HIGH,
                    category = ReviewCategory.PERFORMANCE,
                    file = "RedisPaymentRepository.kt",
                    line = 87,
                    title = "Redis sem timeout explicito",
                    description = "A chamada pode bloquear thread por falta de timeout.",
                    impact = "latencia",
                    recommendation = "configurar timeout",
                    suggestedComment = null,
                    confidence = 0.82
                ),
                ReviewFinding(
                    severity = Severity.HIGH,
                    category = ReviewCategory.PERFORMANCE,
                    file = "RedisPaymentRepository.kt",
                    line = 88,
                    title = "Chamada Redis pode bloquear sem timeout",
                    description = "Nao existe timeout explicito e isso pode aumentar latencia.",
                    impact = "latencia",
                    recommendation = "configurar timeout",
                    suggestedComment = null,
                    confidence = 0.91
                )
            ),
            emptyList()
        )

        assertEquals(1, result.size)
        assertEquals(0.91, result.first().confidence)
    }

    @Test
    fun `should filter code style with no impact as likely false positive`() {
        val deduplicator = FindingDeduplicator()
        val result = deduplicator.deduplicate(
            listOf(
                ReviewFinding(
                    severity = Severity.LOW,
                    category = ReviewCategory.CODE_STYLE,
                    file = "A.kt",
                    line = 10,
                    title = "Nome poderia ser melhor",
                    description = "Talvez seja melhor trocar o nome da variavel.",
                    impact = null,
                    recommendation = "renomear",
                    suggestedComment = null,
                    confidence = 0.9
                )
            ),
            emptyList()
        )

        assertEquals(0, result.size)
    }
}
