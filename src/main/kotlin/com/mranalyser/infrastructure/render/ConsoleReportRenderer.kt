package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.AnalysisQuality
import com.mranalyser.domain.model.ChangeUnderstanding
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport

/**
 * Relatório de console.
 *
 * A ferramenta é um double-check para um revisor experiente, não um gate de merge. O relatório
 * abre com onde ter cuidado em 30 segundos; a seção central é "Onde ter cuidado", com o que
 * conferir, por quê e como conferir; o que é menor ocupa uma linha. A recomendação agregada é um
 * metadado no fim, não um veredito. Categorias vazias não são impressas.
 */
class ConsoleReportRenderer(
    private val showLowConfidence: Boolean = false,
    private val minimumConfidence: Double = 0.60
) : ReportRenderer {
    override fun render(mergeRequest: MergeRequest, report: ReviewReport): String {
        val sb = StringBuilder()

        renderHeader(sb, mergeRequest)
        renderSummary(sb, report)
        renderUnderstanding(sb, report.understanding, report.summary)
        renderArchitecturalSignals(sb, report)
        renderCareful(sb, report)
        renderMinor(sb, report)
        renderPositives(sb, report)
        renderOpenQuestions(sb, report)
        renderPreExisting(sb, report)
        renderQuality(sb, report)

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

    private fun renderSummary(sb: StringBuilder, report: ReviewReport) {
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("EM 30 SEGUNDOS")
        sb.appendLine()
        report.opinion?.opinion?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine(indent(it))
            sb.appendLine()
        }
        val areas = ReviewNarrative.attentionAreas(report)
        if (areas.isEmpty()) {
            sb.appendLine("   Nenhuma área exigiu atenção especial nesta análise.")
        } else {
            sb.appendLine("   Onde ter cuidado:")
            areas.forEach { sb.appendLine("   - $it") }
        }
        sb.appendLine()
    }

    private fun renderCareful(sb: StringBuilder, report: ReviewReport) {
        val careful = report.carefulFindings
        if (careful.isEmpty()) {
            return
        }
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("🔍 ONDE TER CUIDADO")
        sb.appendLine()
        careful.forEachIndexed { index, finding -> renderFinding(sb, index + 1, finding) }
    }

    private fun renderFinding(sb: StringBuilder, number: Int, finding: ReviewFinding) {
        sb.appendLine("$number. ${finding.location} — ${finding.title}")
        sb.appendLine()
        sb.appendLine("   O que conferir: ${ReviewNarrative.whatToCheck(finding)}")

        ReviewNarrative.why(finding)?.let {
            sb.appendLine()
            sb.appendLine("   Por quê: $it")
        }

        finding.evidence?.let {
            sb.appendLine()
            sb.appendLine("   Como conferir:")
            sb.appendLine("   $it")
        }

        finding.failureScenario?.let { scenario ->
            sb.appendLine()
            sb.appendLine("   Cenário a considerar:")
            ReviewNarrative.failureScenarioLines(scenario).forEach { sb.appendLine("   $it") }
        }

        finding.suggestedComment?.let {
            sb.appendLine()
            sb.appendLine("   Se for comentar no MR:")
            it.lines().forEach { line -> sb.appendLine("   > $line") }
        }

        sb.appendLine()
        sb.appendLine(
            "   [${finding.severity.name} | confiança ${ReviewNarrative.formatConfidence(finding.confidence)}" +
                " | ${finding.category.label}]"
        )
        sb.appendLine()
    }

    private fun renderMinor(sb: StringBuilder, report: ReviewReport) {
        val minor = report.minorFindings
        if (minor.isEmpty()) {
            return
        }
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("📝 ITENS MENORES")
        sb.appendLine()
        minor.forEach { sb.appendLine("   - ${ReviewNarrative.minorLine(it)}") }
        sb.appendLine()
    }

    private fun renderPositives(sb: StringBuilder, report: ReviewReport) {
        if (report.positivePoints.isEmpty()) {
            if (report.findings.isEmpty()) {
                sb.appendLine("   Não identifiquei pontos que exigissem atenção neste MR.")
                sb.appendLine()
            }
            return
        }
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("✅ JÁ COBERTO PELO MR")
        sb.appendLine()
        report.positivePoints.forEach { sb.appendLine("   - $it") }
        sb.appendLine()
    }

    private fun renderPreExisting(sb: StringBuilder, report: ReviewReport) {
        val preExisting = report.preExistingFindings
        if (preExisting.isEmpty()) {
            return
        }
        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("DÍVIDA PRÉ-EXISTENTE — NÃO INTRODUZIDA POR ESTE MR")
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

    private fun renderQuality(sb: StringBuilder, report: ReviewReport) {
        val quality = report.quality ?: return

        sb.appendLine(DIVIDER)
        sb.appendLine()
        sb.appendLine("QUALIDADE DA ANÁLISE")
        sb.appendLine()
        // Metadado, não veredito: o relatório não aprova nem bloqueia.
        sb.appendLine(
            "   Sinal agregado: ${ReviewNarrative.recommendationLabel(report.recommendation)}" +
                " | confiança da análise: ${ReviewNarrative.confidenceLabel(report.opinion?.analysisConfidence)}"
        )
        sb.appendLine("   Arquivos analisados: ${quality.filesAnalysed}/${quality.filesChanged}")
        sb.appendLine("   Chunks analisados: ${quality.chunksAnalysed}" + failedSuffix(quality))
        sb.appendLine("   Contextos relacionados carregados: ${quality.relatedContextsLoaded}")
        sb.appendLine("   Findings candidatos: ${quality.candidateFindings}")
        sb.appendLine("   Descartados por duplicidade: ${quality.discardedByDeduplication}")
        sb.appendLine("   Descartados na validação: ${quality.discardedByValidation}")
        sb.appendLine("   Descartados por confiança: ${quality.discardedByConfidence}")
        sb.appendLine("   Descartados como ruído: ${quality.discardedAsNoise}")
        sb.appendLine("   Descartados por citar código inexistente: ${quality.discardedAsUngrounded}")
        sb.appendLine("   Findings apresentados: ${quality.presentedFindings}")

        if (quality.knowledgeSources.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("   Documentos da base de conhecimento consultados:")
            quality.knowledgeSources.forEach { sb.appendLine("      - $it") }
        }

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
                    "eles não participam do sinal agregado."
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
