package com.mranalyser.domain.rule

import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

class TodoRule : ReviewRule {
    private val todoPattern = Regex("""(?i)\b(TODO|FIXME|HACK)\b""")

    override fun supports(change: FileChange): Boolean = !change.deleted

    override fun analyse(mergeRequest: MergeRequest, change: FileChange): List<ReviewFinding> {
        val findings = mutableListOf<ReviewFinding>()
        change.diff.lineSequence().forEachIndexed { index, line ->
            if (line.startsWith("+") && todoPattern.containsMatchIn(line)) {
                findings.add(
                    ReviewFinding(
                        severity = Severity.INFO,
                        category = ReviewCategory.MAINTAINABILITY,
                        file = change.newPath,
                        line = index + 1,
                        title = "Marcador TODO/FIXME/HACK encontrado",
                        description = "Linha adicionada contem marcador de trabalho pendente.",
                        impact = "Pode indicar debito tecnico sem rastreamento formal.",
                        recommendation = "Referenciar tarefa/issue e definir plano para resolucao.",
                        suggestedComment = "Conseguimos vincular este TODO/FIXME a uma issue para acompanhamento?",
                        confidence = 0.75
                    )
                )
            }
        }
        return findings
    }
}
