package com.mranalyser.domain.rule

import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

class TestChangeRule(
    private val significantThreshold: Int = 80
) : ReviewRule {
    override fun supports(change: FileChange): Boolean = !change.deleted

    override fun analyse(mergeRequest: MergeRequest, change: FileChange): List<ReviewFinding> {
        val hasAnyTestChanges = mergeRequest.changes.any { it.newPath.isTestFile() || it.oldPath.isTestFile() }
        val isProdCode = !change.newPath.isTestFile() && !change.oldPath.isTestFile()
        val size = change.linesAdded + change.linesRemoved

        if (hasAnyTestChanges || !isProdCode || size < significantThreshold) {
            return emptyList()
        }

        // Evita duplicar o mesmo alerta em todos os arquivos relevantes.
        val firstProd = mergeRequest.changes.firstOrNull {
            !it.newPath.isTestFile() && !it.oldPath.isTestFile() && (it.linesAdded + it.linesRemoved) >= significantThreshold
        }
        if (firstProd?.newPath != change.newPath) {
            return emptyList()
        }

        return listOf(
            ReviewFinding(
                severity = Severity.HIGH,
                category = ReviewCategory.TESTABILITY,
                file = change.newPath,
                line = null,
                title = "Mudanca relevante sem evidencias de testes",
                description = "Foram detectadas alteracoes relevantes em codigo de producao sem mudancas em testes no MR.",
                impact = "Aumenta risco de regressao e reduz confianca da mudanca.",
                recommendation = "Adicionar ou atualizar testes automatizados cobrindo cenarios principais e falhas.",
                suggestedComment = "Temos testes cobrindo os cenarios alterados neste MR?",
                confidence = 0.78
            )
        )
    }

    private fun String.isTestFile(): Boolean {
        val lower = lowercase()
        return lower.contains("/test/") ||
            lower.contains("test/") ||
            lower.endsWith("test.kt") ||
            lower.endsWith("spec.ts") ||
            lower.endsWith("spec.js")
    }
}
