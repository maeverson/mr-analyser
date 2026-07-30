package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.AnalysisQuality
import com.mranalyser.domain.model.ChangeUnderstanding
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport

/**
 * Relatório de console no formato do item 36.
 *
 * A seção central é "Pontos que eu revisaria no MR" (item 22): é o produto principal da
 * ferramenta. A listagem por severidade da V1 foi substituída por agrupamento pela **ação que o
 * revisor tomaria** — solicitar ajuste, questionar, sugerir — porque é isso que o revisor precisa
 * decidir. Categorias vazias não são impressas.
 */
class ConsoleReportRenderer(
    private val showLowConfidence: Boolean = false,
    private val minimumConfidence: Double = 0.60
) : ReportRenderer {
    override fun render(mergeRequest: MergeRequest, report: ReviewReport): String {
        val sb = StringBuilder()

        renderHeader(sb, mergeRequest)
        renderUnderstanding(sb, report.understanding, report.summary)
        renderArchitecturalSignals(sb, report)
        renderReviewPoints(sb, report)
        renderPreExisting(sb, report)
        renderOpenQuestions(sb, report)
        renderOpinion(sb, report)
        renderVerdict(sb, report)
        renderQuality(sb, report.quality)

        return sb.toString().trimEnd()
    }

    private fun renderHeader(sb: StringBuilder, mergeRequest: MergeRequest) {
        val added = mergeRequest.changes.sumOf { it.linesAdded }
        val removed = mergeRequest.changes.sumOf { it.linesRemoved }

        sb.appendLine("📋 MR REVIEW REPORT")
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("MR !${mergeRequest.iid} — ${mergeRequest.title}")
        sb.appendLine("Autor: ${mergeRequest.author.name}")
        sb.appendLine("Branch: ${mergeRequest.sourceBranch} → ${mergeRequest.targetBranch}")
        sb.appendLine("Arquivos alterados: ${mergeRequest.changes.size}")
        sb.appendLine("+$added / -$removed")
        sb.appendLine()
    }

    private fun renderUnderstanding(sb: StringBuilder, understanding: ChangeUnderstanding?, summary: String) {
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("ENTENDIMENTO DA ALTERAÇÃO")
        sb.appendLine()

        if (understanding == null) {
            sb.appendLine(indent(summary))
            sb.appendLine()
            return
        }

        sb.appendLine(indent(understanding.narrative.ifBlank { understanding.intent }))
        sb.appendLine()
        sb.appendLine("   Alcance da mudança: ${understanding.blastRadius.label}")
        understanding.blastRadiusRationale?.let { sb.appendLine("   Motivo: $it") }

        appendBullets(sb, "Comportamento alterado", understanding.behaviourChanges)
        appendBullets(sb, "Novos caminhos de execução", understanding.newExecutionPaths)
        appendBullets(sb, "Contratos alterados", understanding.contractChanges)
        appendBullets(sb, "Dependências afetadas", understanding.affectedDependencies)

        understanding.intentDiscrepancy?.let {
            sb.appendLine()
            sb.appendLine("   ⚠️  Discrepância entre descrição e implementação:")
            sb.appendLine("      $it")
        }
        sb.appendLine()
    }

    private fun renderArchitecturalSignals(sb: StringBuilder, report: ReviewReport) {
        if (report.architecturalSignals.isEmpty()) {
            return
        }
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("MUDANÇAS ESTRUTURAIS DETECTADAS")
        sb.appendLine()
        report.architecturalSignals
            .groupBy { it.kind }
            .forEach { (kind, group) ->
                sb.appendLine("   • ${kind.label}")
                group.take(MAX_SIGNALS_PER_KIND).forEach { signal ->
                    sb.appendLine("     - ${signal.detail}${signal.file?.let { " ($it)" }.orEmpty()}")
                }
                if (group.size > MAX_SIGNALS_PER_KIND) {
                    sb.appendLine("     - ... (${group.size - MAX_SIGNALS_PER_KIND} ocorrência(s) adicional(is))")
                }
            }
        sb.appendLine()
    }

    private fun renderReviewPoints(sb: StringBuilder, report: ReviewReport) {
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("PONTOS QUE EU REVISARIA NO MR")
        sb.appendLine()

        var counter = 0

        counter = renderBucket(sb, "🔴 SOLICITARIA AJUSTE", report.blockingFindings, counter)
        counter = renderBucket(sb, "🟡 QUESTIONARIA", report.questionFindings, counter)
        counter = renderBucket(sb, "🔵 SUGESTÕES", report.suggestionFindings, counter)

        if (report.positivePoints.isNotEmpty()) {
            sb.appendLine("✅ PONTOS TECNICAMENTE ADEQUADOS")
            sb.appendLine()
            report.positivePoints.forEach { sb.appendLine("   - $it") }
            sb.appendLine()
        }

        if (counter == 0 && report.positivePoints.isEmpty()) {
            sb.appendLine("   Não identifiquei pontos que exigissem comentário neste MR.")
            sb.appendLine()
        }
    }

    private fun renderBucket(
        sb: StringBuilder,
        title: String,
        findings: List<ReviewFinding>,
        startingCounter: Int
    ): Int {
        if (findings.isEmpty()) {
            return startingCounter
        }

        sb.appendLine(title)
        sb.appendLine()

        var counter = startingCounter
        findings.forEach { finding ->
            counter++
            renderFinding(sb, counter, finding)
        }
        return counter
    }

    private fun renderFinding(sb: StringBuilder, number: Int, finding: ReviewFinding) {
        sb.appendLine("$number. ${ReviewNarrative.findingHeader(finding)} ${finding.title}")
        sb.appendLine()
        sb.appendLine("   ${finding.description}")

        finding.evidence?.let {
            sb.appendLine()
            sb.appendLine("   Evidência:")
            sb.appendLine("   $it")
        }

        finding.failureScenario?.let { scenario ->
            sb.appendLine()
            sb.appendLine("   Cenário de falha:")
            ReviewNarrative.failureScenarioLines(scenario).forEach { sb.appendLine("   $it") }
        }

        finding.impact?.let {
            sb.appendLine()
            sb.appendLine("   Impacto: $it")
        }

        finding.recommendation?.let {
            sb.appendLine()
            sb.appendLine("   O que avaliar: $it")
        }

        finding.suggestedComment?.let {
            sb.appendLine()
            sb.appendLine("   Comentário sugerido:")
            it.lines().forEach { line -> sb.appendLine("   > $line") }
        }

        sb.appendLine()
        sb.appendLine(
            "   [confiança ${ReviewNarrative.formatConfidence(finding.confidence)}" +
                " | categoria ${finding.category.label}" +
                " | ${if (finding.blocking) "bloqueia o merge" else "não bloqueia"}]"
        )
        sb.appendLine()
    }

    private fun renderPreExisting(sb: StringBuilder, report: ReviewReport) {
        val preExisting = report.preExistingFindings
        if (preExisting.isEmpty()) {
            return
        }
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("DÍVIDA TÉCNICA IDENTIFICADA — NÃO INTRODUZIDA POR ESTE MR")
        sb.appendLine()
        preExisting.forEach { finding ->
            sb.appendLine("   - ${ReviewNarrative.findingHeader(finding)} ${finding.title}")
            sb.appendLine("     ${finding.description}")
        }
        sb.appendLine()
    }

    private fun renderOpenQuestions(sb: StringBuilder, report: ReviewReport) {
        if (report.questions.isEmpty()) {
            return
        }
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("PERGUNTAS AO AUTOR")
        sb.appendLine()
        report.questions.forEachIndexed { index, question ->
            sb.appendLine("   ${index + 1}. $question")
        }
        sb.appendLine()
    }

    private fun renderOpinion(sb: StringBuilder, report: ReviewReport) {
        val opinion = report.opinion ?: return
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("PARECER TÉCNICO")
        sb.appendLine()
        sb.appendLine(indent(opinion.opinion))
        sb.appendLine()
    }

    private fun renderVerdict(sb: StringBuilder, report: ReviewReport) {
        sb.appendLine(DIVIDER)
        sb.appendLine("PARECER")
        sb.appendLine()
        sb.appendLine("   ${ReviewNarrative.recommendationLabel(report.recommendation)}")
        sb.appendLine("   ${ReviewNarrative.recommendationExplanation(report.recommendation)}")
        sb.appendLine()
        sb.appendLine("   Bloqueadores: ${report.blockingFindings.size}")
        sb.appendLine("   Questionamentos: ${report.questionFindings.size}")
        sb.appendLine("   Sugestões: ${report.suggestionFindings.size}")

        ReviewNarrative.mainRisk(report)?.let {
            sb.appendLine()
            sb.appendLine("   Principal risco:")
            sb.appendLine("   $it")
        }

        sb.appendLine()
        sb.appendLine("   Confiança da análise: ${ReviewNarrative.confidenceLabel(report.opinion?.analysisConfidence)}")
        sb.appendLine(DIVIDER)
        sb.appendLine()
    }

    private fun renderQuality(sb: StringBuilder, quality: AnalysisQuality?) {
        if (quality == null) {
            return
        }

        sb.appendLine("QUALIDADE DA ANÁLISE")
        sb.appendLine()
        sb.appendLine("   Arquivos analisados: ${quality.filesAnalysed}/${quality.filesChanged}")
        sb.appendLine("   Chunks analisados: ${quality.chunksAnalysed}" + failedSuffix(quality))
        sb.appendLine("   Contextos relacionados carregados: ${quality.relatedContextsLoaded}")
        sb.appendLine("   Findings candidatos: ${quality.candidateFindings}")
        sb.appendLine("   Descartados por duplicidade: ${quality.discardedByDeduplication}")
        sb.appendLine("   Descartados na validação: ${quality.discardedByValidation}")
        sb.appendLine("   Descartados por confiança: ${quality.discardedByConfidence}")
        sb.appendLine("   Descartados como ruído: ${quality.discardedAsNoise}")
        sb.appendLine("   Findings apresentados: ${quality.presentedFindings}")

        if (quality.skippedStages.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("   ⚠️  Etapas não executadas:")
            quality.skippedStages.forEach { sb.appendLine("      - $it") }
        }

        if (quality.warnings.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("   ⚠️  Avisos:")
            quality.warnings.forEach { sb.appendLine("      - $it") }
        }

        if (quality.partial) {
            sb.appendLine()
            sb.appendLine("   ⚠️  ANÁLISE PARCIAL: os pontos acima não representam cobertura completa do MR.")
        }

        if (showLowConfidence) {
            sb.appendLine()
            sb.appendLine(
                "   Exibindo também findings abaixo do limite de confiança " +
                    "(${ReviewNarrative.formatConfidence(minimumConfidence)}); " +
                    "eles não participam da recomendação de merge."
            )
        }
    }

    private fun failedSuffix(quality: AnalysisQuality): String =
        if (quality.chunksFailed > 0) " (${quality.chunksFailed} com falha)" else ""

    private fun appendBullets(sb: StringBuilder, label: String, values: List<String>) {
        if (values.isEmpty()) {
            return
        }
        sb.appendLine()
        sb.appendLine("   $label:")
        values.forEach { sb.appendLine("   - $it") }
    }

    private fun indent(text: String): String =
        text.lines().joinToString("\n") { "   ${it.trim()}" }

    private companion object {
        const val DIVIDER = "────────────────────────────────────────────────────────"
        const val MAX_SIGNALS_PER_KIND = 4
    }
}
