package com.mranalyser.domain.policy

import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.FindingScope
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

/**
 * Decide se um finding bloqueia o merge (item 13).
 *
 * A regra deliberadamente **não** deriva bloqueio apenas da severidade:
 *
 * - `HIGH` + hipótese sem cenário de falha → não bloqueia (vira questionamento);
 * - `MEDIUM` + corrupção garantida em cenário específico → bloqueia.
 *
 * A sugestão do modelo é aceita apenas como *downgrade*: o modelo pode dizer "não bloqueia",
 * mas não pode promover um finding a bloqueante sem satisfazer os critérios objetivos.
 */
class BlockingPolicy(
    private val criticalMinimumConfidence: Double = 0.70,
    private val highMinimumConfidence: Double = 0.75,
    private val mediumMinimumConfidence: Double = 0.85
) {
    fun apply(finding: ReviewFinding): ReviewFinding {
        val blocking = evaluate(finding)
        return finding.copy(
            blocking = blocking,
            commentType = finding.commentType ?: defaultCommentType(finding, blocking)
        )
    }

    fun evaluate(finding: ReviewFinding): Boolean {
        if (finding.scope == FindingScope.PRE_EXISTING) {
            return false
        }
        if (finding.type.neverBlocks) {
            return false
        }
        // O modelo pode rebaixar, nunca promover.
        if (!finding.blocking && finding.commentType != null && finding.commentType != CommentType.BLOCKER) {
            return false
        }

        return when (finding.severity) {
            Severity.CRITICAL -> finding.confidence >= criticalMinimumConfidence && finding.hasEvidence

            Severity.HIGH -> finding.confidence >= highMinimumConfidence &&
                finding.hasEvidence &&
                (!finding.failureScenario.isNullOrBlank() || finding.type == FindingType.BUG)

            Severity.MEDIUM -> finding.confidence >= mediumMinimumConfidence &&
                finding.type == FindingType.BUG &&
                !finding.failureScenario.isNullOrBlank()

            Severity.LOW, Severity.INFO -> false
        }
    }

    /**
     * Revoga o bloqueio de um finding que não passou pela etapa de validação.
     *
     * Deve ser aplicada **depois** de [apply]: um achado que ninguém confrontou com o código não
     * tem autoridade para segurar um merge, mesmo satisfazendo os critérios objetivos. Ele
     * permanece no relatório como questionamento, para que o revisor decida.
     */
    fun revokeBlocking(finding: ReviewFinding): ReviewFinding {
        if (!finding.blocking) {
            return finding
        }
        return finding.copy(
            blocking = false,
            type = if (finding.type.neverBlocks) finding.type else FindingType.QUESTION,
            commentType = CommentType.QUESTION
        )
    }

    private fun defaultCommentType(finding: ReviewFinding, blocking: Boolean): CommentType = when {
        blocking -> CommentType.BLOCKER
        finding.type == FindingType.QUESTION -> CommentType.QUESTION
        finding.type == FindingType.SUGGESTION -> CommentType.SUGGESTION
        finding.severity.atLeast(Severity.MEDIUM) -> CommentType.QUESTION
        else -> CommentType.OBSERVATION
    }
}
