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
    private val minimumTokensForCoverage: Int = 3,
    private val titleThreshold: Double = 0.6,
    private val nearbyLines: Int = 5,
    private val sameAreaLines: Int = 15,
    private val statementThreshold: Double = 0.5,
    private val restatementThreshold: Double = 0.6,
    private val sameTitleAnywhere: Double = 0.9
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
        val sameTitle = jaccard(tokens(existing.title), tokens(candidate.title)) >= sameTitleAnywhere
        if (existing.file != candidate.file) {
            // O mesmo problema relatado a partir de dois arquivos (o mapper e o adapter que o
            // alimenta) chega com título idêntico; o prompt pede um finding por problema.
            return sameTitle
        }
        val distance = if (existing.line != null && candidate.line != null) {
            kotlin.math.abs(existing.line - candidate.line)
        } else {
            0
        }
        if (distance > sameAreaLines) {
            return false
        }
        // Mesmo ponto reescrito por chunks diferentes costuma manter o título e variar toda a
        // descrição — o Jaccard do conjunto ficava abaixo do limiar e o relatório repetia o achado.
        if (jaccard(tokens(existing.title), tokens(candidate.title)) >= titleThreshold) {
            return true
        }
        return distance <= nearbyLines && jaccard(existingTokens, candidateTokens) >= duplicateThreshold
    }

    /**
     * Pontos positivos e perguntas chegam de cada chunk e da etapa cross-file com a mesma ideia
     * em redações diferentes; `distinct()` só remove cópias exatas. Mantém a primeira de cada
     * grupo de frases semelhantes, até [max].
     */
    fun distinctStatements(values: List<String>, max: Int = Int.MAX_VALUE): List<String> {
        val kept = mutableListOf<Pair<String, Set<String>>>()
        values.map { it.trim() }.filter { it.isNotEmpty() }.forEach { value ->
            val valueTokens = tokens(value).map(::singular).toSet()
            if (kept.none { (_, existing) -> jaccard(existing, valueTokens) >= statementThreshold }) {
                kept += value to valueTokens
            }
        }
        return kept.take(max).map { it.first }
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
        // Duplicata de outro arquivo: o local continua visível como arquivo relacionado.
        relatedFiles = (winner.relatedFiles + listOfNotNull(duplicate.file.takeIf { it != winner.file }) +
            duplicate.relatedFiles).distinct()
    )

    /** A frase está contida no título ou na descrição de algum dos findings? */
    fun restatesAny(statement: String, findings: List<ReviewFinding>): Boolean {
        val statementTokens = tokens(statement).map(::singular).toSet()
        if (statementTokens.size < minimumTokensForCoverage) {
            return false
        }
        return findings.any { finding ->
            val findingTokens = tokens("${finding.title} ${finding.description}").map(::singular).toSet()
            containment(statementTokens, findingTokens) >= restatementThreshold
        }
    }

    private fun singular(token: String): String =
        if (token.length > 4 && token.endsWith('s')) token.dropLast(1) else token

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
