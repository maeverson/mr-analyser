package com.mranalyser

import com.mranalyser.application.llm.prompt.CrossFileReviewPrompt
import com.mranalyser.application.llm.prompt.FinalAssessmentPrompt
import com.mranalyser.application.llm.prompt.FindingValidationPrompt
import com.mranalyser.application.llm.prompt.LocalReviewPrompt
import com.mranalyser.application.llm.prompt.ReviewPromptPolicy
import com.mranalyser.application.llm.prompt.UnderstandingPrompt
import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.review.ChunkReviewInput
import com.mranalyser.application.review.ClassifiedFile
import com.mranalyser.application.review.CrossFileReviewInput
import com.mranalyser.application.review.ExistingDiscussion
import com.mranalyser.application.review.FinalAssessmentInput
import com.mranalyser.application.review.MergeRequestOverview
import com.mranalyser.application.review.ValidationInput
import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import com.mranalyser.support.MergeRequestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptTest {

    @Test
    fun `system prompt deve conter as regras anti-alucinacao em todas as etapas`() {
        val prompts = listOf(
            UnderstandingPrompt().build(overview(), emptyList(), "digest", 1_000),
            LocalReviewPrompt().build(chunkInput(), 1_000),
            FindingValidationPrompt().build(validationInput(), 1_000),
            CrossFileReviewPrompt().build(crossFileInput(), 1_000),
            FinalAssessmentPrompt().build(finalAssessmentInput(), 1_000)
        )

        prompts.forEach { request ->
            assertTrue(
                request.system.contains("ANTI-HALLUCINATION RULES"),
                "etapa ${request.purpose} sem regras anti-alucinação"
            )
            assertTrue(
                request.system.contains("Do NOT assume absence of retry, timeout"),
                "etapa ${request.purpose} sem a proteção contra concluir ausência a partir do diff"
            )
            assertTrue(request.system.contains("untrusted DATA"), "etapa ${request.purpose} sem proteção de injeção")
            assertTrue(request.system.contains("Brazilian Portuguese"), "etapa ${request.purpose} sem idioma definido")
        }
    }

    @Test
    fun `cada etapa deve declarar seu proprio proposito`() {
        assertEquals(LlmPurpose.UNDERSTANDING, UnderstandingPrompt().build(overview(), emptyList(), "d", 100).purpose)
        assertEquals(LlmPurpose.LOCAL_REVIEW, LocalReviewPrompt().build(chunkInput(), 100).purpose)
        assertEquals(LlmPurpose.VALIDATION, FindingValidationPrompt().build(validationInput(), 100).purpose)
        assertEquals(LlmPurpose.CROSS_FILE_REVIEW, CrossFileReviewPrompt().build(crossFileInput(), 100).purpose)
        assertEquals(LlmPurpose.FINAL_ASSESSMENT, FinalAssessmentPrompt().build(finalAssessmentInput(), 100).purpose)
    }

    @Test
    fun `prompt de review local deve explicar a legenda do diff`() {
        val request = LocalReviewPrompt().build(chunkInput(), 1_000)

        assertTrue(request.user.contains("COMO LER O DIFF"))
        assertTrue(request.user.contains("linha REMOVIDA"))
        assertTrue(request.user.contains("NÃO EXISTE MAIS"))
    }

    @Test
    fun `prompt de review local deve adaptar o foco ao grupo do chunk`() {
        val prompt = LocalReviewPrompt()

        assertTrue(prompt.focusFor(ChangeGroup.INTEGRATION).contains("circuit breaker"))
        assertTrue(prompt.focusFor(ChangeGroup.MIGRATION).contains("NOT NULL sem default"))
        assertTrue(prompt.focusFor(ChangeGroup.MESSAGING).contains("idempotência do consumer"))
        assertTrue(prompt.focusFor(ChangeGroup.PERSISTENCE).contains("lock otimista"))
        assertTrue(prompt.focusFor(ChangeGroup.TEST).contains("Não gere finding sobre estilo de teste"))
    }

    @Test
    fun `prompt de validacao deve ser adversarial e instruir descarte`() {
        val request = FindingValidationPrompt().build(validationInput(), 1_000)

        assertTrue(request.system.contains("adversarial"))
        assertTrue(request.system.contains("SKEPTICAL"))
        assertTrue(request.user.contains("try to refute it"))
        assertTrue(request.user.contains("Discarding 12 of 18 candidates is a good outcome"))
        assertEquals(0.0, request.temperature, "validação deve ser determinística")
    }

    @Test
    fun `prompt de validacao deve incluir o codigo no local do finding`() {
        val request = FindingValidationPrompt().build(validationInput(), 1_000)

        assertTrue(request.user.contains("### F1"))
        assertTrue(request.user.contains("código no local indicado"))
        assertTrue(request.user.contains("provider.cancel"))
    }

    @Test
    fun `prompt cross-file deve proibir repetir achado de arquivo isolado`() {
        val request = CrossFileReviewPrompt().build(crossFileInput(), 1_000)

        assertTrue(request.system.contains("invisible when each file is read in isolation"))
        assertTrue(request.user.contains("FINDINGS JÁ CONFIRMADOS"))
        assertTrue(request.user.contains("Producer    -> Evento"))
    }

    @Test
    fun `prompt de parecer final nao deve receber diff`() {
        val request = FinalAssessmentPrompt().build(finalAssessmentInput(), 1_000)

        assertTrue(request.system.contains("do NOT re-open the diff"))
        assertFalse(request.user.contains("ADD "), "o parecer não deve reabrir detecção sobre o diff")
        assertTrue(request.user.contains("FINDINGS VALIDADOS"))
    }

    @Test
    fun `deve informar ao modelo quando o contexto relacionado esta indisponivel`() {
        val request = LocalReviewPrompt().build(chunkInput(contexts = emptyList()), 1_000)

        assertTrue(request.user.contains("CONTEXTO RELACIONADO DO REPOSITÓRIO"))
        assertTrue(request.user.contains("você NÃO pode concluir que retry, timeout"))
    }

    @Test
    fun `deve marcar discussao resolvida e instruir a nao repetir o ponto`() {
        val request = LocalReviewPrompt().build(
            chunkInput(
                discussions = listOf(
                    ExistingDiscussion("Revisor", "Falta timeout aqui", "A.kt", 10, resolved = true),
                    ExistingDiscussion("Revisor", "E o retry?", null, null, resolved = false)
                )
            ),
            1_000
        )

        assertTrue(request.user.contains("[RESOLVIDA] (A.kt:10)"))
        assertTrue(request.user.contains("[ABERTA] (geral)"))
        assertTrue(request.user.contains("NUNCA sugira novamente um ponto que já foi levantado"))
    }

    @Test
    fun `deve mascarar credencial literal antes de enviar ao modelo`() {
        val overview = MergeRequestOverview.from(
            MergeRequestFixtures.mergeRequest(
                description = "usar apiKey = \"sk-live-9f2b71ac44de8810bb3d\" no ambiente novo",
                changes = MergeRequestFixtures.leakedSecretMr().changes
            ),
            emptyList()
        )

        val request = UnderstandingPrompt().build(overview, emptyList(), "digest", 1_000)

        assertFalse(request.user.contains("9f2b71ac44de8810bb3d"))
        assertTrue(request.user.contains("<REDACTED>"))
    }

    @Test
    fun `politica de ruido deve estar no prompt de review e de validacao`() {
        assertTrue(ReviewPromptPolicy.NOISE_POLICY.contains("naming preference"))
        assertTrue(ReviewPromptPolicy.NOISE_POLICY.contains("Prefer 4 excellent findings over 20 shallow ones"))

        assertTrue(LocalReviewPrompt().build(chunkInput(), 100).system.contains("naming preference"))
        assertTrue(FindingValidationPrompt().build(validationInput(), 100).system.contains("naming preference"))
    }

    private fun overview(): MergeRequestOverview =
        MergeRequestOverview.from(MergeRequestFixtures.transactionalOrderingMr(), classifiedFiles())

    private fun classifiedFiles(): List<ClassifiedFile> =
        MergeRequestFixtures.transactionalOrderingMr().changes.map { change ->
            ClassifiedFile(
                change = change,
                group = ChangeGroup.APPLICATION,
                annotatedDiff = "=== FILE: ${change.path} ===\nADD     84 |         provider.cancel(invoice.externalId)"
            )
        }

    private fun chunkInput(
        contexts: List<com.mranalyser.application.port.RelatedFileContext> = listOf(
            com.mranalyser.application.port.RelatedFileContext(
                referencePath = "src/main/kotlin/billing/application/InvoiceCancellationService.kt",
                relatedPath = "src/main/kotlin/billing/port/InvoiceRepository.kt",
                content = "interface InvoiceRepository { fun save(invoice: Invoice) }"
            )
        ),
        discussions: List<ExistingDiscussion> = emptyList()
    ): ChunkReviewInput = ChunkReviewInput(
        overview = overview(),
        chunkIndex = 1,
        chunkCount = 2,
        group = ChangeGroup.APPLICATION,
        files = classifiedFiles(),
        relatedContext = contexts,
        discussions = discussions,
        understanding = null,
        architecturalSignals = emptyList()
    )

    private fun validationInput(): ValidationInput = ValidationInput(
        overview = overview(),
        understanding = null,
        candidates = listOf(candidate()),
        relatedContext = emptyList(),
        discussions = emptyList(),
        evidenceExcerpts = mapOf("F1" to "ADD     84 |         provider.cancel(invoice.externalId)")
    )

    private fun crossFileInput(): CrossFileReviewInput = CrossFileReviewInput(
        overview = overview(),
        understanding = null,
        architecturalSignals = emptyList(),
        confirmedFindings = listOf(candidate()),
        relationEdges = emptyList(),
        addedLinesByFile = mapOf("A.kt" to "   84 | provider.cancel(id)"),
        relatedContext = emptyList()
    )

    private fun finalAssessmentInput(): FinalAssessmentInput = FinalAssessmentInput(
        overview = overview(),
        understanding = null,
        architecturalSignals = emptyList(),
        findings = listOf(candidate()),
        positivePoints = emptyList(),
        openQuestions = emptyList(),
        degraded = false,
        degradationReasons = emptyList()
    )

    private fun candidate(): ReviewFinding = ReviewFinding(
        severity = Severity.HIGH,
        category = ReviewCategory.DATA_CONSISTENCY,
        type = FindingType.BUG,
        file = "src/main/kotlin/billing/application/InvoiceCancellationService.kt",
        line = 84,
        title = "Cancelamento externo antes da persistência",
        description = "O provider é chamado antes de persistir a invoice.",
        evidence = "InvoiceCancellationService.kt:84 chama provider.cancel() antes de repository.save().",
        failureScenario = "1. cancel sucede\n2. save falha",
        impact = "Inconsistência.",
        recommendation = "Tratar o cenário.",
        suggestedComment = "Como tratamos esse cenário?",
        confidence = 0.9
    )
}
