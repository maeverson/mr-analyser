package com.mranalyser.domain.rule

import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ParsedDiff
import com.mranalyser.domain.model.ReviewFinding

/**
 * Contexto de execução de uma regra estática.
 *
 * Recebe o diff **já interpretado**: na V1 as regras usavam o índice da linha dentro do texto do
 * diff como número de linha do arquivo, o que tornava incorreta a localização de todo finding
 * estático.
 */
data class RuleContext(
    val mergeRequest: MergeRequest,
    val change: FileChange,
    val parsedDiff: ParsedDiff,
    val group: ChangeGroup
)

/**
 * Regras estáticas cobrem apenas o que é **deterministicamente verificável** no diff.
 *
 * Julgamento sobre transação, concorrência ou consistência fica com as etapas LLM, que têm
 * contexto; aproximá-lo por regex produziria exatamente o falso positivo que a ferramenta
 * existe para evitar.
 */
interface ReviewRule {
    val name: String

    fun supports(context: RuleContext): Boolean

    fun analyse(context: RuleContext): List<ReviewFinding>
}
