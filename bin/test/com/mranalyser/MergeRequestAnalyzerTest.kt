package com.mranalyser

import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.port.RelatedContextKind
import com.mranalyser.application.port.RelatedFileContext
import com.mranalyser.application.service.AnalyzerSettings
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.Severity
import com.mranalyser.domain.rule.SecretsRule
import com.mranalyser.support.FakeLlmProvider
import com.mranalyser.support.FakeLlmResponses
import com.mranalyser.support.MergeRequestFixtures
import com.mranalyser.support.TestAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MergeRequestAnalyzerTest {

    @Test
    fun `deve produzir bloqueador com evidencia e cenario de falha`() = runBlocking {
        val analyzer = TestAnalyzer.build(
            FakeLlmProvider.replying(
                LlmPurpose.UNDERSTANDING to FakeLlmResponses.understanding(),
                LlmPurpose.LOCAL_REVIEW to FakeLlmResponses.localReview(),
                LlmPurpose.VALIDATION to FakeLlmResponses.validation(
                    FakeLlmResponses.verdict("F1", "KEEP", severity = "HIGH", confidence = 0.9, blocking = true)
                ),
                LlmPurpose.CROSS_FILE_REVIEW to FakeLlmResponses.crossFileReview(),
                LlmPurpose.FINAL_ASSESSMENT to FakeLlmResponses.finalAssessment()
            )
        )

        val report = analyzer.analyse(MergeRequestFixtures.transactionalOrderingMr())

        assertEquals(1, report.blockingFindings.size)
        assertEquals(MergeRecommendation.REQUEST_CHANGES, report.recommendation)

        val blocker = report.blockingFindings.single()
        assertNotNull(blocker.evidence)
        assertNotNull(blocker.failureScenario)
        assertNotNull(blocker.suggestedComment)
        assertEquals(FindingType.BUG, blocker.type)

        assertNotNull(report.understanding)
        assertNotNull(report.opinion)
        assertEquals("Inconsistência entre cancelamento externo e persistência da invoice.", report.opinion?.mainRisk)
    }

    @Test
    fun `deve descartar finding recusado pela validacao`() = runBlocking {
        val analyzer = TestAnalyzer.build(
            FakeLlmProvider.replying(
                LlmPurpose.UNDERSTANDING to FakeLlmResponses.understanding(),
                LlmPurpose.LOCAL_REVIEW to FakeLlmResponses.localReview(),
                LlmPurpose.VALIDATION to FakeLlmResponses.validation(
                    FakeLlmResponses.verdict("F1", "DISCARD")
                ),
                LlmPurpose.CROSS_FILE_REVIEW to FakeLlmResponses.crossFileReview(),
                LlmPurpose.FINAL_ASSESSMENT to FakeLlmResponses.finalAssessment(
                    opinion = "Sem problemas materiais.",
                    mainRisk = null,
                    recommendation = "APPROVE"
                )
            )
        )

        val report = analyzer.analyse(MergeRequestFixtures.falsePositiveMr())

        assertTrue(report.findings.isEmpty(), "finding descartado não deveria aparecer")
        assertEquals(1, report.quality?.discardedByValidation)
        // Sem finding, mas com uma pergunta aberta ao autor: aprovação com sugestões, não APPROVE puro.
        assertEquals(MergeRecommendation.APPROVE_WITH_SUGGESTIONS, report.recommendation)
        assertEquals(1, report.questions.size)
    }

    @Test
    fun `deve aprovar sem restricoes quando nao sobra finding nem pergunta`() = runBlocking {
        val analyzer = TestAnalyzer.build(
            FakeLlmProvider.replying(
                LlmPurpose.UNDERSTANDING to FakeLlmResponses.understanding(),
                LlmPurpose.LOCAL_REVIEW to FakeLlmResponses.localReview(
                    findingsJson = FakeLlmResponses.transactionalFinding(),
                    questions = "[]",
                    positives = "[]"
                ),
                LlmPurpose.VALIDATION to FakeLlmResponses.validation(FakeLlmResponses.verdict("F1", "DISCARD")),
                LlmPurpose.CROSS_FILE_REVIEW to FakeLlmResponses.crossFileReview(),
                LlmPurpose.FINAL_ASSESSMENT to FakeLlmResponses.finalAssessment(
                    opinion = "Sem problemas materiais.",
                    mainRisk = null,
                    recommendation = "APPROVE"
                )
            )
        )

        val report = analyzer.analyse(MergeRequestFixtures.falsePositiveMr())

        assertEquals(MergeRecommendation.APPROVE, report.recommendation)
    }

    @Test
    fun `deve rebaixar finding sem evidencia para questionamento e nao bloquear`() = runBlocking {
        val analyzer = TestAnalyzer.build(
            FakeLlmProvider.replying(
                LlmPurpose.UNDERSTANDING to FakeLlmResponses.understanding(),
                LlmPurpose.LOCAL_REVIEW to FakeLlmResponses.localReview(
                    findingsJson = FakeLlmResponses.unsupportedFinding()
                ),
                // O validador mantém o finding, mas ele continua sem evidência.
                LlmPurpose.VALIDATION to FakeLlmResponses.validation(
                    FakeLlmResponses.verdict("F1", "KEEP", blocking = true)
                ),
                LlmPurpose.CROSS_FILE_REVIEW to FakeLlmResponses.crossFileReview(),
                LlmPurpose.FINAL_ASSESSMENT to FakeLlmResponses.finalAssessment(recommendation = "NEEDS_DISCUSSION")
            )
        )

        val report = analyzer.analyse(MergeRequestFixtures.uncertainRiskMr())

        val finding = report.findings.single()
        assertEquals(FindingType.QUESTION, finding.type, "sem evidência o finding deve virar questionamento")
        assertFalse(finding.blocking, "questionamento nunca bloqueia")
        assertEquals(Severity.MEDIUM, finding.severity, "severidade deve ser limitada sem evidência")
        assertEquals(MergeRecommendation.NEEDS_DISCUSSION, report.recommendation)
        assertEquals(1, report.questionFindings.size)
    }

    @Test
    fun `deve encontrar problema cross-file e bloquear`() = runBlocking {
        val analyzer = TestAnalyzer.build(
            FakeLlmProvider.replying(
                LlmPurpose.UNDERSTANDING to FakeLlmResponses.understanding(blastRadius = "CROSS_SERVICE"),
                LlmPurpose.LOCAL_REVIEW to FakeLlmResponses.localReview(
                    summary = "Producer e consumer alterados.",
                    findingsJson = "",
                    questions = "[]",
                    positives = "[]"
                ),
                LlmPurpose.CROSS_FILE_REVIEW to FakeLlmResponses.crossFileReview(
                    findingsJson = FakeLlmResponses.contractMismatchFinding()
                ),
                LlmPurpose.FINAL_ASSESSMENT to FakeLlmResponses.finalAssessment(recommendation = "REQUEST_CHANGES")
            )
        )

        val report = analyzer.analyse(MergeRequestFixtures.crossFileMr())

        assertEquals(1, report.blockingFindings.size)
        assertEquals(MergeRecommendation.REQUEST_CHANGES, report.recommendation)
        assertTrue(report.blockingFindings.single().componentsAffected.size >= 2)
    }

    @Test
    fun `deve invalidar finding que a analise cross-file refuta`() = runBlocking {
        val analyzer = TestAnalyzer.build(
            FakeLlmProvider.replying(
                LlmPurpose.UNDERSTANDING to FakeLlmResponses.understanding(),
                LlmPurpose.LOCAL_REVIEW to FakeLlmResponses.localReview(),
                LlmPurpose.VALIDATION to FakeLlmResponses.validation(
                    FakeLlmResponses.verdict("F1", "KEEP", blocking = true)
                ),
                LlmPurpose.CROSS_FILE_REVIEW to FakeLlmResponses.crossFileReview(
                    invalidated = """["Cancelamento externo confirmado antes da persistência local"]"""
                ),
                LlmPurpose.FINAL_ASSESSMENT to FakeLlmResponses.finalAssessment(recommendation = "APPROVE")
            )
        )

        val report = analyzer.analyse(MergeRequestFixtures.transactionalOrderingMr())

        assertTrue(report.findings.isEmpty())
        assertTrue(report.quality!!.warnings.any { it.contains("invalidado pela análise cross-file") })
    }

    @Test
    fun `deve aprovar MR sem findings`() = runBlocking {
        val analyzer = TestAnalyzer.build(
            FakeLlmProvider.replying(
                LlmPurpose.UNDERSTANDING to FakeLlmResponses.understanding(
                    intent = "Corrige acentuação de mensagem.",
                    blastRadius = "LOCAL"
                ),
                LlmPurpose.LOCAL_REVIEW to FakeLlmResponses.localReview(
                    summary = "Correção de texto.",
                    findingsJson = "",
                    questions = "[]",
                    positives = "[]"
                ),
                LlmPurpose.FINAL_ASSESSMENT to FakeLlmResponses.finalAssessment(
                    opinion = "Alteração trivial e correta. Aprovaria.",
                    mainRisk = null,
                    recommendation = "APPROVE"
                )
            )
        )

        val report = analyzer.analyse(MergeRequestFixtures.cleanMr())

        assertTrue(report.findings.isEmpty())
        assertEquals(MergeRecommendation.APPROVE, report.recommendation)
    }

    @Test
    fun `deve concluir analise parcial quando o provider falha em todas as etapas`() = runBlocking {
        val analyzer = TestAnalyzer.build(
            llmProvider = FakeLlmProvider.alwaysFailing("timeout ao contatar o modelo"),
            rules = listOf(SecretsRule())
        )

        val report = analyzer.analyse(MergeRequestFixtures.leakedSecretMr())

        // A regra estática sobrevive à indisponibilidade do LLM.
        assertEquals(1, report.findings.size)
        assertEquals(Severity.CRITICAL, report.findings.single().severity)
        assertTrue(report.blockingFindings.isEmpty(), "sem validação nenhum finding pode bloquear")
        assertEquals(MergeRecommendation.NEEDS_DISCUSSION, report.recommendation)

        val quality = report.quality!!
        assertTrue(quality.partial)
        assertTrue(quality.chunksFailed > 0)
        assertTrue(quality.warnings.any { it.contains("timeout ao contatar o modelo") })
    }

    @Test
    fun `deve respeitar desativacao de etapas e registrar no quality gate`() = runBlocking {
        val provider = FakeLlmProvider.replying(
            LlmPurpose.LOCAL_REVIEW to FakeLlmResponses.localReview()
        )
        val analyzer = TestAnalyzer.build(
            llmProvider = provider,
            settings = AnalyzerSettings(
                understandingEnabled = false,
                validationEnabled = false,
                crossFileEnabled = false,
                finalAssessmentEnabled = false
            )
        )

        val report = analyzer.analyse(MergeRequestFixtures.transactionalOrderingMr())

        assertTrue(provider.requestsFor(LlmPurpose.UNDERSTANDING).isEmpty())
        assertTrue(provider.requestsFor(LlmPurpose.VALIDATION).isEmpty())
        assertTrue(provider.requestsFor(LlmPurpose.CROSS_FILE_REVIEW).isEmpty())
        assertTrue(provider.requestsFor(LlmPurpose.FINAL_ASSESSMENT).isEmpty())

        val skipped = report.quality!!.skippedStages
        listOf("entendimento da alteração", "validação de findings", "análise cross-file", "parecer técnico")
            .forEach { stage ->
                assertTrue(
                    skipped.any { it.startsWith(stage) && it.contains("desabilitad") },
                    "etapa não registrada como desabilitada: $stage (registradas: $skipped)"
                )
            }
        assertTrue(report.blockingFindings.isEmpty(), "sem validação nenhum finding pode bloquear")
    }

    @Test
    fun `deve enviar contexto relacionado apenas para o chunk correspondente`() = runBlocking {
        val servicePath = "src/main/kotlin/billing/application/InvoiceCancellationService.kt"
        val provider = FakeLlmProvider.replying(
            LlmPurpose.LOCAL_REVIEW to FakeLlmResponses.localReview(
                findingsJson = "",
                questions = "[]",
                positives = "[]"
            )
        )

        val analyzer = TestAnalyzer.build(
            llmProvider = provider,
            contexts = listOf(
                RelatedFileContext(
                    referencePath = servicePath,
                    relatedPath = "src/test/kotlin/billing/InvoiceCancellationServiceTest.kt",
                    content = "class InvoiceCancellationServiceTest",
                    kind = RelatedContextKind.TEST,
                    reason = "teste correspondente"
                )
            )
        )

        val report = analyzer.analyse(MergeRequestFixtures.transactionalOrderingMr())

        assertEquals(1, report.quality?.relatedContextsLoaded)

        val promptsWithContext = provider.requestsFor(LlmPurpose.LOCAL_REVIEW)
            .filter { it.user.contains("InvoiceCancellationServiceTest.kt") }
        assertEquals(1, promptsWithContext.size, "contexto deve aparecer só no chunk do arquivo referenciado")
    }

    @Test
    fun `deve ignorar arquivos gerados`() = runBlocking {
        val mergeRequest = MergeRequestFixtures.mergeRequest(
            changes = listOf(
                MergeRequestFixtures.change("src/main/kotlin/A.kt", "@@ -1 +1 @@\n+val a = 1"),
                MergeRequestFixtures.change("build/generated/B.kt", "@@ -1 +1 @@\n+val b = 2", generated = true)
            )
        )
        val analyzer = TestAnalyzer.build(FakeLlmProvider.alwaysFailing())

        val report = analyzer.analyse(mergeRequest)

        assertEquals(2, report.quality?.filesChanged)
        assertEquals(1, report.quality?.filesAnalysed)
        assertTrue(report.quality!!.warnings.any { it.contains("gerado") })
    }
}
