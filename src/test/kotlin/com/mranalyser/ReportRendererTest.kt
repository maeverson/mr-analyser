package com.mranalyser

import com.mranalyser.domain.model.AnalysisConfidence
import com.mranalyser.domain.model.AnalysisQuality
import com.mranalyser.domain.model.AttentionLevel
import com.mranalyser.domain.model.ArchitecturalSignal
import com.mranalyser.domain.model.ArchitecturalSignalKind
import com.mranalyser.domain.model.BlastRadius
import com.mranalyser.domain.model.ChangeUnderstanding
import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.FindingScope
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.model.Severity
import com.mranalyser.domain.model.TechnicalOpinion
import com.mranalyser.infrastructure.render.ConsoleReportRenderer
import com.mranalyser.infrastructure.render.GitLabCommentRenderer
import com.mranalyser.infrastructure.render.JsonReportRenderer
import com.mranalyser.infrastructure.render.MarkdownReportRenderer
import com.mranalyser.support.MergeRequestFixtures
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsoleReportRendererTest {
    private val renderer = ConsoleReportRenderer()

    @Test
    fun `deve renderizar as secoes centrais do parecer`() {
        val output = renderer.render(mergeRequest(), fullReport())

        listOf(
            "📋 MR REVIEW REPORT",
            "EM 30 SEGUNDOS",
            "InvoiceCancellationService — conferir a ordem entre cancel() e save()",
            "ENTENDIMENTO DA ALTERAÇÃO",
            "MUDANÇAS ESTRUTURAIS DETECTADAS",
            "🔍 ONDE TER CUIDADO",
            "📝 ITENS MENORES",
            "✅ JÁ COBERTO PELO MR",
            "PERGUNTAS AO AUTOR",
            "DÍVIDA PRÉ-EXISTENTE",
            "QUALIDADE DA ANÁLISE"
        ).forEach { assertTrue(output.contains(it), "faltou a seção: $it") }

        // O relatório é double-check, não veredito: a recomendação só aparece como metadado.
        listOf("PARECER TÉCNICO", "SOLICITARIA AJUSTE", "Bloqueadores:").forEach {
            assertFalse(output.contains(it), "linguagem de veredito ainda presente: $it")
        }
        assertTrue(output.contains("Sinal agregado: REQUEST_CHANGES"))
    }

    @Test
    fun `itens de cuidado viram cartao e itens menores viram uma linha`() {
        val output = renderer.render(mergeRequest(), fullReport())

        assertTrue(output.contains("1. src/main/kotlin/billing/application/InvoiceCancellationService.kt"))
        assertTrue(output.contains("2. "))
        val minorSection = output.substringAfter("📝 ITENS MENORES")
        assertTrue(minorSection.contains("— Cobertura do cancelamento idempotente"))
        assertFalse(
            output.substringBefore("📝 ITENS MENORES").contains("Cobertura do cancelamento idempotente"),
            "item menor não deve ocupar cartão em Onde ter cuidado"
        )
    }

    @Test
    fun `deve exibir evidencia cenario de falha e comentario sugerido`() {
        val output = renderer.render(mergeRequest(), fullReport())

        assertTrue(output.contains("O que conferir:"))
        assertTrue(output.contains("Como conferir:"))
        assertTrue(output.contains("Cenário a considerar:"))
        assertTrue(output.contains("Se for comentar no MR:"))
        assertTrue(output.contains("> Como garantimos consistência"))
        assertFalse(output.contains("bloqueia o merge"))
    }

    @Test
    fun `deve omitir secoes vazias`() {
        val output = renderer.render(
            mergeRequest(),
            ReviewReport(
                summary = "Alteração trivial de texto.",
                findings = emptyList(),
                questions = emptyList(),
                positivePoints = emptyList(),
                recommendation = MergeRecommendation.APPROVE
            )
        )

        assertFalse(output.contains("ONDE TER CUIDADO\n"))
        assertFalse(output.contains("ITENS MENORES"))
        assertFalse(output.contains("DÍVIDA PRÉ-EXISTENTE"))
        assertFalse(output.contains("MUDANÇAS ESTRUTURAIS"))
        assertTrue(output.contains("Nenhuma área exigiu atenção especial"))
        assertTrue(output.contains("Não identifiquei pontos que exigissem atenção"))
    }

    @Test
    fun `deve avisar quando a analise for parcial`() {
        val output = renderer.render(
            mergeRequest(),
            fullReport().copy(
                quality = AnalysisQuality(
                    filesChanged = 3,
                    filesAnalysed = 3,
                    chunksAnalysed = 1,
                    chunksFailed = 2,
                    warnings = listOf("review do chunk 2/3 falhou: timeout")
                )
            )
        )

        assertTrue(output.contains("ANÁLISE PARCIAL"))
        assertTrue(output.contains("timeout"))
        assertTrue(output.contains("(2 com falha)"))
    }
}

class MarkdownReportRendererTest {

    @Test
    fun `deve renderizar as secoes em markdown`() {
        val output = MarkdownReportRenderer().render(mergeRequest(), fullReport())

        listOf(
            "# 📋 Análise do MR !101",
            "## 🧠 Entendimento da alteração",
            "## 🏗️ Mudanças estruturais detectadas",
            "## ⏱️ Em 30 segundos",
            "## 🔍 Onde ter cuidado",
            "## 📝 Itens menores",
            "## ✅ Já coberto pelo MR",
            "## 🧹 Dívida pré-existente",
            "## 📊 Qualidade da análise"
        ).forEach { assertTrue(output.contains(it), "faltou a seção: $it") }

        assertTrue(output.contains("**O que conferir:**"))
        assertTrue(output.contains("**Como conferir:**"))
        assertFalse(output.contains("Parecer final"))
        assertFalse(output.contains("| Parecer |"))
    }
}

class GitLabCommentRendererTest {
    private val renderer = GitLabCommentRenderer()

    @Test
    fun `deve classificar comentarios por tipo e apontar arquivo e linha`() {
        val output = renderer.render(mergeRequest(), fullReport())

        assertTrue(output.contains("[BLOCKER]"))
        assertTrue(output.contains("[QUESTION]"))
        assertTrue(output.contains("InvoiceCancellationService.kt:87"))
        assertTrue(output.contains("Como garantimos consistência"))
        assertTrue(output.contains("[COMENTÁRIO GERAL DO MR]"))
        assertTrue(output.contains("[PRAISE]"))
    }

    @Test
    fun `bloqueador deve vir antes de questionamento`() {
        val output = renderer.render(mergeRequest(), fullReport())

        assertTrue(output.indexOf("[BLOCKER]") < output.indexOf("[QUESTION]"))
    }

    @Test
    fun `nao deve gerar comentario para estilo nem para finding pre-existente`() {
        val report = fullReport().copy(
            findings = listOf(
                finding(
                    severity = Severity.LOW,
                    type = FindingType.SUGGESTION,
                    category = ReviewCategory.CODE_STYLE,
                    title = "Nomenclatura poderia ser melhor",
                    comment = "Podemos renomear essa variável?"
                ),
                finding(
                    severity = Severity.HIGH,
                    type = FindingType.DESIGN,
                    title = "Acoplamento pré-existente",
                    comment = "Isso já era assim antes."
                ).copy(scope = FindingScope.PRE_EXISTING)
            )
        )

        val output = renderer.render(mergeRequest(), report)

        assertFalse(output.contains("Podemos renomear essa variável?"))
        assertFalse(output.contains("Isso já era assim antes."))
        assertTrue(output.contains("Dívida técnica não introduzida por este MR"))
    }

    @Test
    fun `deve informar quando nao ha comentario de linha recomendado`() {
        val report = ReviewReport(
            summary = "ok",
            findings = emptyList(),
            questions = emptyList(),
            positivePoints = emptyList(),
            recommendation = MergeRecommendation.APPROVE
        )

        assertTrue(renderer.render(mergeRequest(), report).contains("Nenhum comentário de linha recomendado"))
    }
}

class JsonReportRendererTest {

    @Test
    fun `deve expor campos de rastreabilidade`() {
        val output = JsonReportRenderer().render(mergeRequest(), fullReport())

        listOf(
            "\"mergeRequestIid\"",
            "\"recommendation\"",
            "\"recommendationRationale\"",
            "\"understanding\"",
            "\"blastRadius\"",
            "\"architecturalSignals\"",
            "\"evidence\"",
            "\"failureScenario\"",
            "\"blocking\"",
            "\"bucket\"",
            "\"scope\"",
            "\"origin\"",
            "\"counts\"",
            "\"quality\"",
            "\"partial\""
        ).forEach { assertTrue(output.contains(it), "faltou o campo: $it") }
    }

    @Test
    fun `nao deve emitir nulos explicitos`() {
        val output = JsonReportRenderer().render(
            mergeRequest(),
            ReviewReport(
                summary = "ok",
                findings = emptyList(),
                questions = emptyList(),
                positivePoints = emptyList(),
                recommendation = MergeRecommendation.APPROVE
            )
        )

        assertFalse(output.contains(": null"))
    }
}

private fun mergeRequest(): MergeRequest = MergeRequestFixtures.transactionalOrderingMr()

private fun fullReport(): ReviewReport = ReviewReport(
    summary = "O MR adiciona cancelamento de invoice.",
    findings = listOf(
        finding(
            severity = Severity.HIGH,
            type = FindingType.BUG,
            category = ReviewCategory.DATA_CONSISTENCY,
            title = "Cancelamento externo antes da persistência local",
            comment = "Como garantimos consistência se o save() falhar depois do cancel()?"
        ).copy(
            line = 87,
            evidence = "InvoiceCancellationService.kt:87 chama provider.cancel() antes de repository.save().",
            failureScenario = "1. provider.cancel() sucede\n2. repository.save() falha\n3. estados divergentes",
            impact = "Inconsistência entre sistemas.",
            recommendation = "Tratar o cenário com compensação ou idempotência.",
            blocking = true,
            commentType = CommentType.BLOCKER
        ),
        finding(
            severity = Severity.MEDIUM,
            type = FindingType.QUESTION,
            category = ReviewCategory.RELIABILITY,
            title = "Retry no BillingProviderClient",
            comment = "Esse client já tem política de retry configurada em outra camada?"
        ).copy(commentType = CommentType.QUESTION),
        finding(
            severity = Severity.LOW,
            type = FindingType.SUGGESTION,
            category = ReviewCategory.TESTABILITY,
            title = "Cobertura do cancelamento idempotente",
            comment = "Vale um teste para cancelamento repetido?"
        ).copy(attention = AttentionLevel.MINOR),
        finding(
            severity = Severity.MEDIUM,
            type = FindingType.DESIGN,
            category = ReviewCategory.ARCHITECTURE,
            title = "Repository exposto no controller",
            comment = null
        ).copy(scope = FindingScope.PRE_EXISTING)
    ),
    questions = listOf("Qual o comportamento esperado em cancelamento duplicado?"),
    positivePoints = listOf("Exceções do provider são convertidas em erro de aplicação"),
    recommendation = MergeRecommendation.REQUEST_CHANGES,
    understanding = ChangeUnderstanding(
        intent = "Adicionar cancelamento de invoice.",
        narrative = "O MR introduz cancelamento e integra com o provider externo de billing.",
        behaviourChanges = listOf("invoice passa a poder ser cancelada"),
        newExecutionPaths = listOf("cancel -> provider -> persistência"),
        contractChanges = emptyList(),
        affectedDependencies = listOf("Billing Provider"),
        blastRadius = BlastRadius.SERVICE,
        blastRadiusRationale = "altera comportamento observável do serviço"
    ),
    architecturalSignals = listOf(
        ArchitecturalSignal(
            ArchitecturalSignalKind.NEW_EXTERNAL_CLIENT,
            "novo client HTTP/externo",
            "src/main/kotlin/billing/integration/BillingProviderClient.kt"
        )
    ),
    opinion = TechnicalOpinion(
        opinion = "Existe risco concreto de inconsistência entre o provider e o estado local.",
        mainRisk = "Inconsistência entre cancelamento externo e persistência da invoice.",
        analysisConfidence = AnalysisConfidence.HIGH,
        attentionAreas = listOf("InvoiceCancellationService — conferir a ordem entre cancel() e save()")
    ),
    quality = AnalysisQuality(
        filesChanged = 2,
        filesAnalysed = 2,
        chunksAnalysed = 2,
        relatedContextsLoaded = 3,
        candidateFindings = 9,
        discardedByValidation = 5,
        presentedFindings = 4
    )
)

private fun finding(
    severity: Severity,
    type: FindingType,
    category: ReviewCategory = ReviewCategory.BUG,
    title: String,
    comment: String?
): ReviewFinding = ReviewFinding(
    severity = severity,
    category = category,
    type = type,
    file = "src/main/kotlin/billing/application/InvoiceCancellationService.kt",
    line = 87,
    title = title,
    description = "Descrição do finding com tamanho suficiente para ser acionável.",
    impact = null,
    recommendation = null,
    suggestedComment = comment,
    confidence = 0.9
)
