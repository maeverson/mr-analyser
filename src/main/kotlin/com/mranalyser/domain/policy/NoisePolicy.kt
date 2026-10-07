package com.mranalyser.domain.policy

import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

/**
 * Suprime findings que não devem gerar comentário no GitLab (item 18).
 *
 * Diferente da V1, isto **não** tenta adivinhar falso positivo por regex em português —
 * essa responsabilidade foi movida para a etapa de validação por LLM, que tem contexto.
 * Aqui ficam apenas critérios objetivos e verificáveis.
 */
class NoisePolicy(
    private val ignoredCategories: Set<String> = emptySet(),
    private val minimumDescriptionLength: Int = 24
) {
    data class Decision(
        val suppressed: Boolean,
        val reason: String? = null
    )

    fun evaluate(finding: ReviewFinding): Decision {
        if (finding.title.isBlank()) {
            return Decision(true, "finding sem título")
        }

        if (ignoredCategories.contains(finding.category.name)) {
            return Decision(true, "categoria ${finding.category.name} ignorada por configuração")
        }

        val text = "${finding.title} ${finding.description}".trim()
        if (text.length < minimumDescriptionLength) {
            return Decision(true, "descrição insuficiente para ser acionável")
        }

        // Estilo/formatação só entra no relatório se houver impacto declarado e severidade material.
        if (finding.category == ReviewCategory.CODE_STYLE && finding.impact.isNullOrBlank()) {
            return Decision(true, "estilo sem impacto declarado")
        }

        if (finding.category == ReviewCategory.DOCUMENTATION &&
            finding.impact.isNullOrBlank() &&
            !finding.severity.atLeast(Severity.MEDIUM)
        ) {
            return Decision(true, "documentação sem impacto declarado")
        }

        return Decision(false)
    }

    /**
     * Um comentário sugerido para o GitLab exige mais do que o relatório: preferência de
     * nomenclatura, formatação e micro-otimização não devem interromper o desenvolvedor.
     */
    fun deservesGitLabComment(finding: ReviewFinding): Boolean {
        if (finding.suggestedComment.isNullOrBlank()) {
            return false
        }
        if (finding.category == ReviewCategory.CODE_STYLE) {
            return false
        }
        if (finding.severity == Severity.INFO && !finding.blocking) {
            return false
        }
        return true
    }
}
