package com.mranalyser

import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.port.RelatedContextKind
import com.mranalyser.application.port.RelatedFileContext
import com.mranalyser.domain.rule.MissingTestCoverageRule
import com.mranalyser.infrastructure.render.ConsoleReportRenderer
import com.mranalyser.infrastructure.render.GitLabCommentRenderer
import com.mranalyser.support.FakeLlmProvider
import com.mranalyser.support.FakeLlmResponses
import com.mranalyser.support.MergeRequestFixtures
import com.mranalyser.support.TestAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Teste de ponta a ponta do produto principal: dado um MR com problema transacional real, o
 * relatório precisa dizer com clareza o que solicitar, o que questionar e o que comentar.
 *
 * Vale como verificação do critério do item 41: depois de ler a saída, o revisor sabe onde olhar.
 */
class EndToEndReportTest {

    @Test
    fun `relatorio deve responder o que revisar onde e por que`() = runBlocking {
        val analyzer = TestAnalyzer.build(
            llmProvider = FakeLlmProvider.replying(
                LlmPurpose.UNDERSTANDING to FakeLlmResponses.understanding(),
                LlmPurpose.LOCAL_REVIEW to FakeLlmResponses.localReview(),
                LlmPurpose.VALIDATION to FakeLlmResponses.validation(
                    FakeLlmResponses.verdict("F1", "KEEP", severity = "HIGH", confidence = 0.9, blocking = true),
                    FakeLlmResponses.verdict("F2", "DOWNGRADE_TO_QUESTION")
                ),
                LlmPurpose.CROSS_FILE_REVIEW to FakeLlmResponses.crossFileReview(),
                LlmPurpose.FINAL_ASSESSMENT to FakeLlmResponses.finalAssessment()
            ),
            rules = listOf(MissingTestCoverageRule()),
            contexts = listOf(
                RelatedFileContext(
                    referencePath = "src/main/kotlin/billing/application/InvoiceCancellationService.kt",
                    relatedPath = "src/main/kotlin/billing/port/InvoiceRepository.kt",
                    content = "interface InvoiceRepository { fun save(invoice: Invoice) }",
                    kind = RelatedContextKind.DEPENDENCY
                )
            )
        )

        val mergeRequest = MergeRequestFixtures.transactionalOrderingMr()
        val report = analyzer.analyse(mergeRequest)
        val console = ConsoleReportRenderer().render(mergeRequest, report)

        // O que revisar, e por que.
        assertTrue(console.contains("PONTOS QUE EU REVISARIA NO MR"))
        assertTrue(console.contains("🔴 SOLICITARIA AJUSTE"))
        assertTrue(console.contains("Cancelamento externo confirmado antes da persistência local"))

        // Onde — arquivo e linha reais, não índice dentro do diff.
        assertTrue(console.contains("InvoiceCancellationService.kt:84"))

        // Em qual cenário, e qual impacto.
        assertTrue(console.contains("Evidência:"))
        assertTrue(console.contains("Cenário de falha:"))
        assertTrue(console.contains("Impacto:"))

        // O que comentar no GitLab.
        assertTrue(console.contains("Comentário sugerido:"))

        // Conclusão acionável.
        assertTrue(console.contains("PARECER"))
        assertTrue(console.contains("REQUEST_CHANGES"))
        assertTrue(console.contains("Bloqueadores: 1"))
        assertTrue(console.contains("Principal risco:"))
        assertTrue(console.contains("Confiança da análise: HIGH"))

        // Transparência sobre a própria análise.
        assertTrue(console.contains("QUALIDADE DA ANÁLISE"))
        assertTrue(console.contains("Findings candidatos:"))

        val comments = GitLabCommentRenderer().render(mergeRequest, report)
        assertTrue(comments.contains("[BLOCKER]"))
        assertTrue(
            comments.contains("Como tratamos o cenário em que o provider confirma e o save() falha depois?"),
            "comentário deve soar como revisor, não como relatório de auditoria"
        )
        assertTrue(comments.contains("[COMENTÁRIO GERAL DO MR]"))
    }
}
