package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.AnalysisQuality
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport

/**
 * Mesma estrutura do relatório de console, em Markdown, para colar em wiki, issue ou descrição
 * de MR. As seções seguem a ordem do item 36 e categorias vazias são omitidas.
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
        sb.appendLine("| Parecer | **${ReviewNarrative.recommendationLabel(report.recommendation)}** |")
        sb.appendLine()

        renderUnderstanding(sb, report)
        renderSignals(sb, report)
        renderReviewPoints(sb, report)
        renderPreExisting(sb, report)
        renderQuestions(sb, report)
        renderOpinion(sb, report)
        renderVerdict(sb, report)
        renderQuality(sb, report.quality)

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

    private fun renderReviewPoints(sb: StringBuilder, report: ReviewReport) {
        sb.appendLine("## 🎯 Pontos que eu revisaria no MR")
        sb.appendLine()

        var counter = 0
        counter = renderBucket(sb, "### 🔴 Solicitaria ajuste", report.blockingFindings, counter)
        counter = renderBucket(sb, "### 🟡 Questionaria", report.questionFindings, counter)
        counter = renderBucket(sb, "### 🔵 Sugestões", report.suggestionFindings, counter)

        if (report.positivePoints.isNotEmpty()) {
            sb.appendLine("### ✅ Pontos tecnicamente adequados")
            sb.appendLine()
            report.positivePoints.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        if (counter == 0 && report.positivePoints.isEmpty()) {
            sb.appendLine("Não identifiquei pontos que exigissem comentário neste MR.")
            sb.appendLine()
        }
    }

    private fun renderBucket(
        sb: StringBuilder,
        heading: String,
        findings: List<ReviewFinding>,
        startingCounter: Int
    ): Int {
        if (findings.isEmpty()) {
            return startingCounter
        }

        sb.appendLine(heading)
        sb.appendLine()

        var counter = startingCounter
        findings.forEach { finding ->
            counter++
            sb.appendLine("#### $counter. ${ReviewNarrative.findingHeader(finding)} ${finding.title}")
            sb.appendLine()
            sb.appendLine(finding.description)

            finding.evidence?.let {
                sb.appendLine()
                sb.appendLine("**Evidência:** $it")
            }
            finding.failureScenario?.let { scenario ->
                sb.appendLine()
                sb.appendLine("**Cenário de falha:**")
                sb.appendLine()
                ReviewNarrative.failureScenarioLines(scenario).forEach { sb.appendLine("$it  ") }
            }
            finding.impact?.let {
                sb.appendLine()
                sb.appendLine("**Impacto:** $it")
            }
            finding.recommendation?.let {
                sb.appendLine()
                sb.appendLine("**O que avaliar:** $it")
            }
            finding.suggestedComment?.let {
                sb.appendLine()
                sb.appendLine("**Comentário sugerido:**")
                sb.appendLine()
                it.lines().forEach { line -> sb.appendLine("> $line") }
            }

            sb.appendLine()
            sb.appendLine(
                "_confiança ${ReviewNarrative.formatConfidence(finding.confidence)} · " +
                    "categoria ${finding.category.label} · " +
                    "${if (finding.blocking) "bloqueia o merge" else "não bloqueia"}_"
            )
            sb.appendLine()
        }
        return counter
    }

    private fun renderPreExisting(sb: StringBuilder, report: ReviewReport) {
        val preExisting = report.preExistingFindings
        if (preExisting.isEmpty()) {
            return
        }
        sb.appendLine("## 🧹 Dívida técnica identificada — não introduzida por este MR")
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

    private fun renderOpinion(sb: StringBuilder, report: ReviewReport) {
        val opinion = report.opinion ?: return
        sb.appendLine("## 🧭 Parecer técnico")
        sb.appendLine()
        sb.appendLine(opinion.opinion)
        sb.appendLine()
    }

    private fun renderVerdict(sb: StringBuilder, report: ReviewReport) {
        sb.appendLine("## 🚦 Parecer final")
        sb.appendLine()
        sb.appendLine("**${ReviewNarrative.recommendationLabel(report.recommendation)}** — " +
            ReviewNarrative.recommendationExplanation(report.recommendation))
        sb.appendLine()
        sb.appendLine("- Bloqueadores: ${report.blockingFindings.size}")
        sb.appendLine("- Questionamentos: ${report.questionFindings.size}")
        sb.appendLine("- Sugestões: ${report.suggestionFindings.size}")
        ReviewNarrative.mainRisk(report)?.let {
            sb.appendLine()
            sb.appendLine("**Principal risco:** $it")
        }
        sb.appendLine()
        sb.appendLine("**Confiança da análise:** ${ReviewNarrative.confidenceLabel(report.opinion?.analysisConfidence)}")
        sb.appendLine()
    }

    private fun renderQuality(sb: StringBuilder, quality: AnalysisQuality?) {
        if (quality == null) {
            return
        }
        sb.appendLine("## 📊 Qualidade da análise")
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
        sb.appendLine("| Findings apresentados | ${quality.presentedFindings} |")
        sb.appendLine()

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
