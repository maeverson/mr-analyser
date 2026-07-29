package com.mranalyser.domain.rule

import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

class LargeChangeRule(
    private val maxLinesPerFile: Int = 1500
) : ReviewRule {
    override fun supports(change: FileChange): Boolean = true

    override fun analyse(mergeRequest: MergeRequest, change: FileChange): List<ReviewFinding> {
        val totalLines = change.linesAdded + change.linesRemoved
        if (totalLines <= maxLinesPerFile) {
            return emptyList()
        }
        return listOf(
            ReviewFinding(
                severity = Severity.MEDIUM,
                category = ReviewCategory.MAINTAINABILITY,
                file = change.newPath,
                line = null,
                title = "Arquivo com alteracao muito grande",
                description = "Este arquivo possui $totalLines linhas alteradas.",
                impact = "Mudancas extensas dificultam revisao e elevam risco de regressao.",
                recommendation = "Avaliar particionamento em MRs menores ou extracao de componentes.",
                suggestedComment = "Esta mudanca ficou bem grande para um unico arquivo. Faz sentido quebrar para reduzir risco?",
                confidence = 0.80
            )
        )
    }
}
