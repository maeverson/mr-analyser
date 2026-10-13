package com.mranalyser

import com.mranalyser.application.llm.prompt.FinalAssessmentPrompt
import com.mranalyser.application.llm.prompt.LocalReviewPrompt
import com.mranalyser.application.llm.prompt.ReviewPromptPolicy
import com.mranalyser.application.review.ChunkReviewInput
import com.mranalyser.application.review.ClassifiedFile
import com.mranalyser.application.review.KnowledgeExcerpt
import com.mranalyser.application.review.KnowledgeStatus
import com.mranalyser.application.review.MergeRequestOverview
import com.mranalyser.application.service.EvidenceGroundingCheck
import com.mranalyser.application.service.FindingDeduplicator
import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.FindingOrigin
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import com.mranalyser.support.MergeRequestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Correções de precisão observadas no relatório do MR !4 (billing-backoffice-bff). */
class ReviewPrecisionTest {

    private val adapterPath = "src/domains/companies/adapters/product-core-catalog.adapter.ts"

    /** Trecho real do tipo de código onde o modelo inventou `repository.save()`. */
    private val corpus = """
+  async listPlans(priceSourceId: string): Promise<CatalogPlan[]> {
+    const body = await this.http.get('/api/v1/plans', { priceSourceId });
+    return parsePlans(body);
+  }
""".trimIndent()

    private fun finding(
        evidence: String?,
        failureScenario: String? = null,
        suggestedComment: String? = null,
        title: String = "Possível falha na persistência local após chamada ao Product Core",
        line: Int = 51,
        origin: FindingOrigin = FindingOrigin.LOCAL_REVIEW
    ) = ReviewFinding(
        severity = Severity.MEDIUM,
        category = ReviewCategory.RELIABILITY,
        file = adapterPath,
        line = line,
        title = title,
        description = "A chamada ao Product Core é feita antes de qualquer operação local.",
        impact = null,
        recommendation = null,
        suggestedComment = suggestedComment,
        confidence = 0.85,
        evidence = evidence,
        failureScenario = failureScenario,
        origin = origin
    )

    // --- Evidência ancorada no código --------------------------------------------------------

    @Test
    fun `evidencia citando metodo inexistente no diff deve descartar o finding`() {
        val invented = finding(
            evidence = "$adapterPath:64 chama httpClient.get() sem garantir que repository.save() seja chamado",
            failureScenario = "1. GET retorna sucesso 2. repository.save() falha"
        )

        val result = EvidenceGroundingCheck().apply(listOf(invented), corpus, listOf(adapterPath))

        assertTrue(result.findings.isEmpty())
        assertEquals(1, result.discarded.size)
    }

    @Test
    fun `evidencia com metodos reais deve ser mantida`() {
        val real = finding(evidence = "product-core-catalog.adapter.ts:2 chama this.http.get() e repassa para parsePlans()")

        val result = EvidenceGroundingCheck().apply(listOf(real), corpus, listOf(adapterPath))

        assertEquals(1, result.findings.size)
    }

    @Test
    fun `comentario copiado do exemplo deve ser removido sem descartar o finding`() {
        val parroted = finding(
            evidence = "$adapterPath:2 chama this.http.get()",
            suggestedComment = "Neste fluxo chamamos capture() antes de persistir a invoice."
        )

        val kept = EvidenceGroundingCheck().apply(listOf(parroted), corpus, listOf(adapterPath)).findings.single()

        assertNull(kept.suggestedComment)
    }

    @Test
    fun `arquivo citado que nao existe no MR deve descartar o finding`() {
        val result = EvidenceGroundingCheck().apply(
            listOf(finding(evidence = "InvoiceService.kt:84 chama get antes de persistir")),
            corpus,
            listOf(adapterPath)
        )

        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `plural entre parenteses e regra estatica nao devem ser confundidos com codigo`() {
        val check = EvidenceGroundingCheck()

        assertTrue(check.missingReferences("2 arquivo(s) alterado(s) e a chamada(s) falha", emptySet(), emptySet()).isEmpty())
        assertTrue(check.missingReferences("falha (rede instável)", emptySet(), emptySet()).isEmpty())

        val staticRule = finding(evidence = "Sem teste correspondente: FooServiceTest.kt", origin = FindingOrigin.STATIC_RULE)
        assertEquals(1, check.apply(listOf(staticRule), corpus, listOf(adapterPath)).findings.size)
    }

    @Test
    fun `codigo do teste atribuido ao adapter deve descartar o finding`() {
        val specPath = "src/domains/companies/adapters/product-core-catalog.adapter.spec.ts"
        val misattributed = finding(
            evidence = "$adapterPath:87 productCore.respondWith({ status, body: { title: 'Erro' } });"
        )
        val files = mapOf(
            adapterPath to corpus,
            specPath to "+    productCore.respondWith({ status, body: { title: 'Erro' } });"
        )
        val all = files.values.joinToString("\n")

        val result = EvidenceGroundingCheck().apply(listOf(misattributed), all, files.keys, files)

        assertTrue(result.findings.isEmpty(), "respondWith só existe no spec, não no adapter citado")

        val correct = finding(evidence = "$specPath:12 productCore.respondWith({ status })")
        assertEquals(1, EvidenceGroundingCheck().apply(listOf(correct), all, files.keys, files).findings.size)
    }

    // --- Deduplicação ------------------------------------------------------------------------

    @Test
    fun `pergunta que so reformula um finding apresentado deve ser reconhecida`() {
        val presented = finding(evidence = "a", title = "Possível falha ao lidar com erros de rede").copy(
            description = "O código não trata explicitamente erros de rede além de 503, 502 e 504."
        )
        val deduplicator = FindingDeduplicator()

        assertTrue(deduplicator.restatesAny("Possível falha ao lidar com erros de rede. Como tratamos?", listOf(presented)))
        assertFalse(
            deduplicator.restatesAny("A validação de plans.name vazio cobre os outros consumidores?", listOf(presented))
        )
    }

    @Test
    fun `mesmo titulo no mesmo trecho com descricoes diferentes deve virar um finding`() {
        val first = finding(evidence = "a").copy(description = "A ordem de chamadas pode causar inconsistência.")
        val second = finding(evidence = "b", line = 58).copy(
            description = "Não há garantia de idempotência da operação seguinte ao GET.",
            confidence = 0.7
        )

        val result = FindingDeduplicator().analyse(listOf(first, second), emptyList())

        assertEquals(1, result.findings.size)
        assertEquals(1, result.removedAsDuplicate)
    }

    @Test
    fun `titulo identico em arquivos diferentes e o mesmo problema`() {
        val inMapper = finding(evidence = "a", title = "Possível inconsistência ao resolver o nome do plano")
            .copy(file = "src/domains/companies/mappers/company-list-item.mapper.ts", line = 145)
        val inAdapter = finding(evidence = "b", title = "Possível inconsistência ao resolver o nome do plano")
        val unrelated = finding(evidence = "c", title = "Timeout padrão curto para o Product Core")
            .copy(file = "src/config/env.schema.ts", line = 7)

        val result = FindingDeduplicator().analyse(listOf(inMapper, inAdapter, unrelated), emptyList())

        assertEquals(2, result.findings.size)
    }

    @Test
    fun `mesmo titulo em trechos distantes do arquivo nao e duplicata`() {
        val result = FindingDeduplicator().analyse(
            listOf(finding(evidence = "a", line = 10), finding(evidence = "b", line = 90)),
            emptyList()
        )

        assertEquals(2, result.findings.size)
    }

    @Test
    fun `pontos positivos em redacoes diferentes devem ser consolidados`() {
        val positives = listOf(
            "Implementação de retry e timeout no cliente HTTP",
            "Implementação de retries e timeouts no cliente HTTP",
            "Validação de respostas com Zod",
            "Validação de respostas com Zod para garantir integridade dos dados.",
            "Configuração de variáveis de ambiente com validação"
        )

        val distinct = FindingDeduplicator().distinctStatements(positives)

        assertEquals(
            listOf(
                "Implementação de retry e timeout no cliente HTTP",
                "Validação de respostas com Zod",
                "Configuração de variáveis de ambiente com validação"
            ),
            distinct
        )
        assertEquals(2, FindingDeduplicator().distinctStatements(positives, max = 2).size)
    }

    // --- Prompts -----------------------------------------------------------------------------

    @Test
    fun `prompts nao devem conter exemplos concretos que o modelo copia`() {
        val texts = listOf(
            ReviewPromptPolicy.EVIDENCE_REQUIREMENT,
            ReviewPromptPolicy.COMMENT_STYLE,
            ReviewPromptPolicy.DEEP_REVIEW_CHECKLIST,
            FinalAssessmentPrompt().build(
                com.mranalyser.application.review.FinalAssessmentInput(
                    overview(), null, emptyList(), emptyList(), emptyList(), emptyList(), false, emptyList()
                ),
                500
            ).user
        )

        texts.forEach { text ->
            listOf("capture()", "save()", "InvoiceService", "A implementação está coerente com o objetivo").forEach {
                assertFalse(text.contains(it), "exemplo copiável '$it' ainda presente")
            }
        }
        assertTrue(ReviewPromptPolicy.ANTI_HALLUCINATION.contains("Never reuse sentences from these instructions"))
        assertTrue(ReviewPromptPolicy.NOISE_POLICY.contains("this default may be too low/high"))
    }

    @Test
    fun `review local de codigo de producao deve conferir a documentacao`() {
        val knowledge = listOf(
            KnowledgeExcerpt("gs://kb/contracts/empresas-detalhe.md", "contracts", KnowledgeStatus.PROPOSED,
                "Se o filtro devolver mais de um plano, o BFF responde 503.", true)
        )

        val withDocs = LocalReviewPrompt().build(chunkInput(knowledge), 1_000).user
        assertTrue(withDocs.contains("CONFERÊNCIA CONTRA A DOCUMENTAÇÃO"))
        assertTrue(withDocs.contains("responde 503"))

        val withoutDocs = LocalReviewPrompt().build(chunkInput(emptyList()), 1_000).user
        assertFalse(withoutDocs.contains("CONFERÊNCIA CONTRA A DOCUMENTAÇÃO"))
    }

    private fun overview(): MergeRequestOverview {
        val mr = MergeRequestFixtures.transactionalOrderingMr()
        return MergeRequestOverview.from(mr, mr.changes.map { ClassifiedFile(it, ChangeGroup.INTEGRATION, "ADD 1 | x") })
    }

    private fun chunkInput(knowledge: List<KnowledgeExcerpt>) = ChunkReviewInput(
        overview = overview(),
        chunkIndex = 1,
        chunkCount = 1,
        group = ChangeGroup.INTEGRATION,
        files = overview().files,
        relatedContext = emptyList(),
        discussions = emptyList(),
        understanding = null,
        architecturalSignals = emptyList(),
        knowledge = knowledge
    )
}
