package com.mranalyser.application.service

import com.mranalyser.domain.model.Discussion
import com.mranalyser.domain.model.ReviewFinding

/**
 * Remove findings duplicados e findings que repetem uma discussão já existente no MR (item 31).
 *
 * Mudança de escopo em relação à V1: esta classe **não** decide mais se um finding é falso
 * positivo. Aquela heurística usava regex em português (`talvez|poderia|considerar`) e ausência
 * de campo `impact` para julgar validade — critério textual que descartava achados legítimos e
 * mantinha achados especulativos bem redigidos. Julgar validade agora é papel da etapa de
 * validação por LLM, que tem o código na mão; supressão objetiva de ruído é papel de
 * [com.mranalyser.domain.policy.NoisePolicy].
 *
 * A comparação é baseada em Jaccard de tokens. Levenshtein sobre descrições completas — usado na
 * V1 — é O(n·m) por par e quadrático no número de findings, sem ganho de precisão nesta tarefa.
 */
class FindingDeduplicator(
    private val duplicateThreshold: Double = 0.68,
    private val discussionCoverageThreshold: Double = 0.75,
    private val minimumTokensForCoverage: Int = 3
) {
    data class Result(
        val findings: List<ReviewFinding>,
        val removedAsDuplicate: Int,
        val removedAsAlreadyDiscussed: Int
    )

    fun deduplicate(findings: List<ReviewFinding>, discussions: List<Discussion>): List<ReviewFinding> =
        analyse(findings, discussions).findings

    fun analyse(findings: List<ReviewFinding>, discussions: List<Discussion>): Result {
        val openDiscussionTokens = discussions
            .flatMap { it.notes }
            .filterNot { it.system }
            // Discussão resolvida não impede um novo achado: o ponto pode ter voltado.
            .filterNot { it.resolved }
            .map { tokens(it.body) }
            .filter { it.isNotEmpty() }

        // Maior confiança primeiro: entre duplicatas, sobrevive a versão mais bem sustentada.
        val ordered = findings.sortedWith(
            compareByDescending<ReviewFinding> { it.confidence }
                .thenByDescending { it.severity.weight }
                .thenByDescending { if (it.hasEvidence) 1 else 0 }
        )

        val kept = mutableListOf<ReviewFinding>()
        val keptTokens = mutableListOf<Set<String>>()
        var duplicates = 0
        var alreadyDiscussed = 0

        ordered.forEach { finding ->
            val findingTokens = tokens("${finding.title} ${finding.description}")

            if (openDiscussionTokens.any { alreadyCovered(finding, it) }) {
                alreadyDiscussed++
                return@forEach
            }

            val duplicateIndex = kept.indices.firstOrNull { index ->
                isDuplicate(kept[index], keptTokens[index], finding, findingTokens)
            }

            if (duplicateIndex != null) {
                duplicates++
                kept[duplicateIndex] = merge(kept[duplicateIndex], finding)
                return@forEach
            }

            kept += finding
            keptTokens += findingTokens
        }

        return Result(kept, duplicates, alreadyDiscussed)
    }

    /**
     * "Este ponto já está contido em um comentário existente?" é uma relação de **contenção**,
     * não de similaridade: a nota do revisor costuma ser mais curta e usar outras palavras, então
     * Jaccard sobre o par inteiro subestima a sobreposição. Compara-se a descrição e o comentário
     * sugerido separadamente, cada um contra o texto da nota.
     */
    private fun alreadyCovered(finding: ReviewFinding, discussionTokens: Set<String>): Boolean =
        listOfNotNull(finding.description, finding.suggestedComment)
            .map { tokens(it) }
            .filter { it.size >= minimumTokensForCoverage }
            .any { containment(it, discussionTokens) >= discussionCoverageThreshold }

    private fun containment(part: Set<String>, whole: Set<String>): Double {
        if (part.isEmpty() || whole.isEmpty()) {
            return 0.0
        }
        return part.count { it in whole }.toDouble() / part.size
    }

    private fun isDuplicate(
        existing: ReviewFinding,
        existingTokens: Set<String>,
        candidate: ReviewFinding,
        candidateTokens: Set<String>
    ): Boolean {
        if (existing.file != candidate.file) {
            return false
        }
        if (existing.line != null && candidate.line != null && kotlin.math.abs(existing.line - candidate.line) > 5) {
            return false
        }
        return jaccard(existingTokens, candidateTokens) >= duplicateThreshold
    }

    /**
     * A duplicata pode trazer campos que o vencedor não tem (evidência, cenário de falha).
     * Descartá-la inteira perderia informação verificável.
     */
    private fun merge(winner: ReviewFinding, duplicate: ReviewFinding): ReviewFinding = winner.copy(
        evidence = winner.evidence ?: duplicate.evidence,
        failureScenario = winner.failureScenario ?: duplicate.failureScenario,
        impact = winner.impact ?: duplicate.impact,
        recommendation = winner.recommendation ?: duplicate.recommendation,
        suggestedComment = winner.suggestedComment ?: duplicate.suggestedComment,
        componentsAffected = (winner.componentsAffected + duplicate.componentsAffected).distinct(),
        relatedFiles = (winner.relatedFiles + duplicate.relatedFiles).distinct()
    )

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0
        }
        val intersection = a.count { it in b }.toDouble()
        val union = (a.size + b.size - intersection)
        return if (union == 0.0) 0.0 else intersection / union
    }

    private fun tokens(text: String): Set<String> = text
        .lowercase()
        .split(NON_WORD)
        .filter { it.length > 2 && it !in STOPWORDS }
        .toSet()

    private companion object {
        val NON_WORD = Regex("[^a-z0-9áàâãéêíóôõúç]+")

        val STOPWORDS = setOf(
            "que", "com", "para", "por", "uma", "dos", "das", "nao", "não", "esta", "este",
            "isso", "pode", "mais", "ser", "sem", "the", "and", "for", "with", "that", "this",
            "are", "was", "not", "can", "may", "from", "have", "has", "but", "will"
        )
    }
}
