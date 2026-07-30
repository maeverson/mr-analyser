package com.mranalyser.domain.policy

import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

/**
 * Aplica a exigência do item 9: todo finding `MEDIUM` ou superior deve possuir evidência.
 *
 * A ação não é descartar — é **rebaixar a questionamento**. Um risco plausível sem evidência
 * continua sendo informação útil para o revisor, mas apresentá-lo como acusação categórica é
 * exatamente o ruído que a especificação pede para eliminar (item 2).
 */
class EvidencePolicy(
    private val severityCapWithoutEvidence: Severity = Severity.MEDIUM
) {
    fun apply(finding: ReviewFinding): ReviewFinding {
        if (!finding.severity.atLeast(Severity.MEDIUM)) {
            return finding
        }
        if (finding.hasEvidence) {
            return finding
        }

        return finding.copy(
            severity = finding.severity.cappedAt(severityCapWithoutEvidence),
            type = if (finding.type == FindingType.SUGGESTION) finding.type else FindingType.QUESTION,
            blocking = false,
            commentType = finding.commentType.takeIf { it == CommentType.SUGGESTION } ?: CommentType.QUESTION
        )
    }
}
