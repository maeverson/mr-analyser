package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.AnalysisQuality
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport

/**
 * Mesma estrutura do relatório de console, em Markdown, para colar em wiki, issue ou descrição
 * de MR. Categorias vazias são omitidas.
 *
 * O relatório é um double-check para um revisor experiente, não um parecer de aprovação: abre
 * com onde ter cuidado em 30 segundos, detalha cada ponto como "o que conferir / por quê / como
 * conferir" e deixa a recomendação agregada como uma linha de metadado no fim.
 */
class MarkdownReportRenderer : ReportRenderer {
    override fun render(mergeRequest: MergeRequest, report: ReviewReport): String {
        val sb = StringBuilder()
        val added = mergeRequest.changes.sumOf { it.linesAdded }
        val removed = mergeRequest.changes.sumOf { it.linesRemoved }

        sb.appendLine("# 📋 Análise do MR !${mergeRequest.iid} — ${mergeRequest.title}")
        sb.appendLine()
        sb.appendLine("| | |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Autor | ${mergeRequest.author.name} |")
        sb.appendLine("| Branches | `${mergeRequest.sourceBranch}` → `${mergeRequest.targetBranch}` |")
        sb.appendLine("| Arquivos alterados | ${mergeRequest.changes.size} |")
        sb.appendLine("| Linhas | +$added / -$removed |")
        sb.appendLine()

        renderSummary(sb, report)
        renderUnderstanding(sb, report)
        renderSignals(sb, report)
        renderCareful(sb, report)
        renderMinor(sb, report)
        renderPositives(sb, report)
        renderQuestions(sb, report)
        renderPreExisting(sb, report)
        renderQuality(sb, report)

        return sb.toString().trimEnd()
    }

    private fun renderUnderstanding(sb: StringBuilder, report: ReviewReport) {
        sb.appendLine("## 🧠 Entendimento da alteração")
        sb.appendLine()

        val understanding = report.understanding
        if (understanding == null) {
            sb.appendLine(report.summary)
            sb.appendLine()
            return
        }

        sb.appendLine(understanding.narrative.ifBlank { understanding.intent })
        sb.appendLine()
        sb.appendLine("- **Alcance da mudança:** ${understanding.blastRadius.label}")
        understanding.blastRadiusRationale?.let { sb.appendLine("- **Motivo:** $it") }
        appendList(sb, "Comportamento alterado", understanding.behaviourChanges)
        appendList(sb, "Novos caminhos de execução", understanding.newExecutionPaths)
        appendList(sb, "Contratos alterados", understanding.contractChanges)
        appendList(sb, "Dependências afetadas", understanding.affectedDependencies)
        understanding.intentDiscrepancy?.let {
            sb.appendLine()
            sb.appendLine("> ⚠️ **Discrepância entre descrição e implementação:** $it")
        }
        sb.appendLine()
    }

    private fun renderSignals(sb: StringBuilder, report: ReviewReport) {
        if (report.architecturalSignals.isEmpty()) {
            return
        }
        sb.appendLine("## 🏗️ Mudanças estruturais detectadas")
        sb.appendLine()
        report.architecturalSignals.groupBy { it.kind }.forEach { (kind, group) ->
            sb.appendLine("- **${kind.label}**")
            group.forEach { signal ->
                sb.appendLine("  - ${signal.detail}${signal.file?.let { " (`$it`)" }.orEmpty()}")
            }
        }
        sb.appendLine()
    }

    private fun renderSummary(sb: StringBuilder, report: ReviewReport) {
        sb.appendLine("## ⏱️ Em 30 segundos")
        sb.appendLine()
        report.opinion?.opinion?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine(it)
            sb.appendLine()
        }
        val areas = ReviewNarrative.attentionAreas(report)
        if (areas.isEmpty()) {
            sb.appendLine("Nenhuma área exigiu atenção especial nesta análise.")
        } else {
            sb.appendLine("**Onde ter cuidado:**")
            sb.appendLine()
            areas.forEach { sb.appendLine("- $it") }
        }
        sb.appendLine()
    }

    private fun renderCareful(sb: StringBuilder, report: ReviewReport) {
        val careful = report.carefulFindings
        if (careful.isEmpty()) {
            return
        }
        sb.appendLine("## 🔍 Onde ter cuidado")
        sb.appendLine()

        careful.forEachIndexed { index, finding ->
            sb.appendLine("#### ${index + 1}. `${finding.location}` — ${finding.title}")
            sb.appendLine()
            sb.appendLine("**O que conferir:** ${ReviewNarrative.whatToCheck(finding)}")
            ReviewNarrative.why(finding)?.let {
                sb.appendLine()
                sb.appendLine("**Por quê:** $it")
            }
            finding.evidence?.let {
                sb.appendLine()
                sb.appendLine("**Como conferir:** $it")
            }
            finding.failureScenario?.let { scenario ->
                sb.appendLine()
                sb.appendLine("**Cenário a considerar:**")
                sb.appendLine()
                ReviewNarrative.failureScenarioLines(scenario).forEach { sb.appendLine("$it  ") }
            }
            finding.suggestedComment?.let {
                sb.appendLine()
                sb.appendLine("**Se for comentar no MR:**")
                sb.appendLine()
                it.lines().forEach { line -> sb.appendLine("> $line") }
            }
            sb.appendLine()
            sb.appendLine(
                "_${finding.severity.name} · confiança ${ReviewNarrative.formatConfidence(finding.confidence)} · " +
                    "${finding.category.label}_"
            )
            sb.appendLine()
        }
    }

    private fun renderMinor(sb: StringBuilder, report: ReviewReport) {
        val minor = report.minorFindings
        if (minor.isEmpty()) {
            return
        }
        sb.appendLine("## 📝 Itens menores")
        sb.appendLine()
        minor.forEach { sb.appendLine("- `${it.location}` — ${it.title}") }
        sb.appendLine()
    }

    private fun renderPositives(sb: StringBuilder, report: ReviewReport) {
        if (report.positivePoints.isEmpty()) {
            if (report.findings.isEmpty()) {
                sb.appendLine("Não identifiquei pontos que exigissem atenção neste MR.")
                sb.appendLine()
            }
            return
        }
        sb.appendLine("## ✅ Já coberto pelo MR")
        sb.appendLine()
        report.positivePoints.forEach { sb.appendLine("- $it") }
        sb.appendLine()
    }

    private fun renderPreExisting(sb: StringBuilder, report: ReviewReport) {
        val preExisting = report.preExistingFindings
        if (preExisting.isEmpty()) {
            return
        }
        sb.appendLine("## 🧹 Dívida pré-existente — não introduzida por este MR")
        sb.appendLine()
        preExisting.forEach { finding ->
            sb.appendLine("- **${ReviewNarrative.findingHeader(finding)} ${finding.title}** — ${finding.description}")
        }
        sb.appendLine()
    }

    private fun renderQuestions(sb: StringBuilder, report: ReviewReport) {
        if (report.questions.isEmpty()) {
            return
        }
        sb.appendLine("## ❓ Perguntas ao autor")
        sb.appendLine()
        report.questions.forEachIndexed { index, question -> sb.appendLine("${index + 1}. $question") }
        sb.appendLine()
    }

    private fun renderQuality(sb: StringBuilder, report: ReviewReport) {
        val quality = report.quality ?: return
        sb.appendLine("## 📊 Qualidade da análise")
        sb.appendLine()
        // Metadado, não veredito: o relatório não aprova nem bloqueia.
        sb.appendLine(
            "_Sinal agregado: ${ReviewNarrative.recommendationLabel(report.recommendation)} · " +
                "confiança da análise: ${ReviewNarrative.confidenceLabel(report.opinion?.analysisConfidence)}_"
        )
        sb.appendLine()
        sb.appendLine("| Métrica | Valor |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Arquivos analisados | ${quality.filesAnalysed}/${quality.filesChanged} |")
        sb.appendLine("| Chunks analisados | ${quality.chunksAnalysed} |")
        sb.appendLine("| Chunks com falha | ${quality.chunksFailed} |")
        sb.appendLine("| Contextos relacionados carregados | ${quality.relatedContextsLoaded} |")
        sb.appendLine("| Findings candidatos | ${quality.candidateFindings} |")
        sb.appendLine("| Descartados por duplicidade | ${quality.discardedByDeduplication} |")
        sb.appendLine("| Descartados na validação | ${quality.discardedByValidation} |")
        sb.appendLine("| Descartados por confiança | ${quality.discardedByConfidence} |")
        sb.appendLine("| Descartados como ruído | ${quality.discardedAsNoise} |")
        sb.appendLine("| Descartados por citar código inexistente | ${quality.discardedAsUngrounded} |")
        sb.appendLine("| Findings apresentados | ${quality.presentedFindings} |")
        sb.appendLine()

        if (quality.knowledgeSources.isNotEmpty()) {
            sb.appendLine("**Documentos da base de conhecimento consultados:**")
            sb.appendLine()
            quality.knowledgeSources.forEach { sb.appendLine("- `$it`") }
            sb.appendLine()
        }

        if (quality.skippedStages.isNotEmpty()) {
            sb.appendLine("**Etapas não executadas:**")
            sb.appendLine()
            quality.skippedStages.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        if (quality.warnings.isNotEmpty()) {
            sb.appendLine("**Avisos:**")
            sb.appendLine()
            quality.warnings.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        if (quality.partial) {
            sb.appendLine("> ⚠️ **Análise parcial.** Os pontos acima não representam cobertura completa do MR.")
            sb.appendLine()
        }
    }

    private fun appendList(sb: StringBuilder, label: String, values: List<String>) {
        if (values.isEmpty()) {
            return
        }
        sb.appendLine("- **$label:**")
        values.forEach { sb.appendLine("  - $it") }
    }
}
