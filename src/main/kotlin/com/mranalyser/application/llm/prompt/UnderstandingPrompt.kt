package com.mranalyser.application.llm.prompt

import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.review.MergeRequestOverview
import com.mranalyser.domain.model.ArchitecturalSignal

/**
 * Etapa 1: análise orientada a impacto (item 4). Roda **antes** do review para que cada chunk
 * seja avaliado sabendo o que o MR se propõe a fazer e qual é o blast radius.
 *
 * Recebe apenas metadados e o resumo das linhas adicionadas — não o diff completo — porque o
 * objetivo aqui é intenção e escopo, não detecção de defeito.
 */
class UnderstandingPrompt(
    private val sections: PromptSections = PromptSections()
) {
    fun build(
        overview: MergeRequestOverview,
        signals: List<ArchitecturalSignal>,
        diffDigest: String,
        maxOutputTokens: Int
    ): LlmRequest = LlmRequest(
        purpose = LlmPurpose.UNDERSTANDING,
        system = ReviewPromptPolicy.systemPrompt(SYSTEM_EXTRA),
        user = buildString {
            appendLine(TASK)
            appendLine()
            appendLine(sections.mergeRequestHeader(overview))
            appendLine()
            appendLine(sections.changedFilesTable(overview))
            appendLine()
            appendLine(sections.commits(overview))
            sections.architecturalSignals(signals).takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine("## RESUMO DAS ALTERAÇÕES (linhas adicionadas por arquivo)")
            appendLine(diffDigest)
            appendLine()
            appendLine(SCHEMA)
        },
        maxOutputTokens = maxOutputTokens,
        temperature = 0.1,
        label = "understanding"
    )

    private companion object {
        val SYSTEM_EXTRA = """
In this step you are NOT looking for defects. You are establishing what the change does and how
far its effects reach, so that the later review steps have context.
${ReviewPromptPolicy.LANGUAGE}
""".trim()

        val TASK = """
# TAREFA: ENTENDIMENTO DA ALTERAÇÃO

Answer, using only the provided material:
- What is this MR trying to do?
- Which components were changed?
- Which previous behaviour is being modified?
- Which new execution paths appeared?
- Which contracts changed (API, events, database schema, public signatures)?
- Which external dependencies are affected?
- What is the blast radius of the change?

Use the title, description and commits to understand INTENT — but intent does not replace
evidence. If the description claims one thing and the diff does something else, report that in
"intentDiscrepancy". If there is no discrepancy, set it to null.

"narrative" must be 2 to 4 sentences of flowing prose in Brazilian Portuguese, in the style of a
senior engineer explaining the change to a colleague. Describe what changed and where the effect
lands. Do not list files. Do not judge quality here.
""".trim()

        val SCHEMA = """
## FORMATO DE RESPOSTA (JSON único)
{
  "intent": "uma frase objetiva sobre o objetivo do MR",
  "narrative": "2 a 4 frases descrevendo a alteração e onde o impacto recai",
  "behaviourChanges": ["comportamento anterior X passa a ser Y"],
  "newExecutionPaths": ["novo caminho: ..."],
  "contractChanges": ["contrato alterado: ..."],
  "affectedDependencies": ["dependência externa afetada: ..."],
  "blastRadius": "LOCAL|MODULE|SERVICE|CROSS_SERVICE|UNKNOWN",
  "blastRadiusRationale": "por que esse alcance",
  "intentDiscrepancy": null
}
Arrays vazios são válidos. Use null quando não houver informação suficiente.
""".trim()
    }
}
