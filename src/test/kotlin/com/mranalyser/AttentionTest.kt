package com.mranalyser

import com.mranalyser.application.service.AttentionClassifier
import com.mranalyser.domain.model.AttentionLevel
import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.model.Severity
import com.mranalyser.infrastructure.render.MarkdownReportRenderer
import com.mranalyser.support.MergeRequestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** O relatório é double-check para revisor experiente: separar cuidado de detalhe, sem esconder. */
class AttentionTest {

    private val groups = mapOf(
        "src/config/env.schema.ts" to ChangeGroup.CONFIGURATION,
        "package.json" to ChangeGroup.BUILD,
        "src/shared/http/http-client.ts" to ChangeGroup.INTEGRATION
    )

    private fun finding(file: String, severity: Severity, type: FindingType, blocking: Boolean = false) = ReviewFinding(
        severity = severity,
        category = ReviewCategory.RELIABILITY,
        file = file,
        line = 7,
        title = "Ponto em $file",
        description = "Descrição suficiente para o relatório.",
        impact = null,
        recommendation = "Confira se o valor atende o Product Core.",
        suggestedComment = null,
        confidence = 0.8,
        type = type,
        blocking = blocking
    )

    private fun level(finding: ReviewFinding) = AttentionClassifier().classify(finding, groups::get).attention

    @Test
    fun `pergunta sobre configuracao ou dependencia e item menor`() {
        assertEquals(AttentionLevel.MINOR, level(finding("src/config/env.schema.ts", Severity.MEDIUM, FindingType.QUESTION)))
        assertEquals(AttentionLevel.MINOR, level(finding("package.json", Severity.INFO, FindingType.QUESTION)))
    }

    @Test
    fun `risco em codigo de integracao merece cuidado`() {
        assertEquals(AttentionLevel.CAREFUL, level(finding("src/shared/http/http-client.ts", Severity.MEDIUM, FindingType.RISK)))
        assertEquals(AttentionLevel.CAREFUL, level(finding("src/shared/http/http-client.ts", Severity.MEDIUM, FindingType.QUESTION)))
    }

    @Test
    fun `severidade baixa e sugestao sao menores, bloqueante nunca`() {
        assertEquals(AttentionLevel.MINOR, level(finding("src/shared/http/http-client.ts", Severity.LOW, FindingType.RISK)))
        assertEquals(AttentionLevel.MINOR, level(finding("src/shared/http/http-client.ts", Severity.HIGH, FindingType.SUGGESTION)))
        assertEquals(
            AttentionLevel.CAREFUL,
            level(finding("src/config/env.schema.ts", Severity.LOW, FindingType.QUESTION, blocking = true))
        )
    }

    @Test
    fun `sem resumo do modelo as areas saem dos itens de cuidado`() {
        val careful = AttentionClassifier().classify(
            finding("src/shared/http/http-client.ts", Severity.MEDIUM, FindingType.RISK).copy(title = "Tratamento de erro do axios"),
            groups::get
        )
        val report = ReviewReport(
            summary = "",
            findings = listOf(careful),
            questions = emptyList(),
            positivePoints = emptyList(),
            recommendation = MergeRecommendation.NEEDS_DISCUSSION
        )

        val output = MarkdownReportRenderer().render(MergeRequestFixtures.transactionalOrderingMr(), report)

        assertTrue(output.contains("- http-client.ts — Tratamento de erro do axios"))
        assertTrue(output.contains("**O que conferir:** Confira se o valor atende o Product Core."))
    }
}
