package com.mranalyser.domain.rule

import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

class DebugCodeRule : ReviewRule {
    private val debugPattern = Regex("""(?i)(System\.out\.println|console\.log|println\(|printStackTrace\()""")

    override fun supports(change: FileChange): Boolean = !change.deleted

    override fun analyse(mergeRequest: MergeRequest, change: FileChange): List<ReviewFinding> {
        val findings = mutableListOf<ReviewFinding>()
        change.diff.lineSequence().forEachIndexed { index, line ->
            if (line.startsWith("+") && debugPattern.containsMatchIn(line)) {
                findings.add(
                    ReviewFinding(
                        severity = Severity.LOW,
                        category = ReviewCategory.MAINTAINABILITY,
                        file = change.newPath,
                        line = index + 1,
                        title = "Codigo de debug adicionado",
                        description = "Foi detectado uso de log/print de debug em linha adicionada.",
                        impact = "Pode gerar ruido em logs e dificultar operacao.",
                        recommendation = "Substituir por logging estruturado no nivel apropriado, se necessario.",
                        suggestedComment = "Podemos remover este debug ou trocar por log estruturado com nivel adequado?",
                        confidence = 0.85
                    )
                )
            }
        }
        return findings
    }
}
