package com.mranalyser.domain.rule

import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

class SecretsRule : ReviewRule {
    private val pattern = Regex(
        """(?i)(password\s*=|token\s*=|api[_-]?key\s*=|secret\s*=|BEGIN\s+PRIVATE\s+KEY)"""
    )

    override fun supports(change: FileChange): Boolean = !change.deleted

    override fun analyse(mergeRequest: MergeRequest, change: FileChange): List<ReviewFinding> {
        val findings = mutableListOf<ReviewFinding>()
        change.diff.lineSequence().forEachIndexed { index, line ->
            if (line.startsWith("+") && pattern.containsMatchIn(line)) {
                findings.add(
                    ReviewFinding(
                        severity = Severity.CRITICAL,
                        category = ReviewCategory.SECURITY,
                        file = change.newPath,
                        line = index + 1,
                        title = "Possivel segredo exposto no diff",
                        description = "Foi identificado padrao tipico de segredo em linha adicionada.",
                        impact = "Credenciais podem vazar e comprometer ambiente e dados.",
                        recommendation = "Remover segredo do codigo e usar mecanismo seguro de configuracao.",
                        suggestedComment = "Este trecho parece conter credencial. Podemos remover do codigo e usar variavel segura?",
                        confidence = 0.95
                    )
                )
            }
        }
        return findings
    }
}
