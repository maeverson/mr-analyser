package com.mranalyser.application.llm.prompt

import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.review.FinalAssessmentInput
import com.mranalyser.domain.model.bucket
import com.mranalyser.domain.model.ReviewBucket

/**
 * Etapa 5: parecer executivo (itens 21 e 23).
 *
 * Recebe apenas os findings já validados — nunca o diff. A intenção é forçar um parecer sobre o
 * conjunto de evidências consolidado, e não uma nova rodada de detecção que produziria findings
 * sem passar pela validação.
 */
class FinalAssessmentPrompt(
    private val sections: PromptSections = PromptSections()
) {
    fun build(input: FinalAssessmentInput, maxOutputTokens: Int): LlmRequest = LlmRequest(
        purpose = LlmPurpose.FINAL_ASSESSMENT,
        system = ReviewPromptPolicy.systemPrompt(SYSTEM_EXTRA),
        user = buildString {
            appendLine(TASK)
            appendLine()
            appendLine(sections.mergeRequestHeader(input.overview))
            sections.understanding(input.understanding).takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(it)
            }
            sections.architecturalSignals(input.architecturalSignals).takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine(findingsBlock(input))
            if (input.positivePoints.isNotEmpty()) {
                appendLine()
                appendLine("## PONTOS POSITIVOS JÁ IDENTIFICADOS")
                input.positivePoints.forEach { appendLine("- $it") }
            }
            if (input.openQuestions.isNotEmpty()) {
                appendLine()
                appendLine("## PERGUNTAS ABERTAS")
                input.openQuestions.forEach { appendLine("- $it") }
            }
            if (input.degraded) {
                appendLine()
                appendLine("## LIMITAÇÕES DESTA ANÁLISE")
                input.degradationReasons.forEach { appendLine("- $it") }
                appendLine(
                    "Considere essas limitações ao definir \"analysisConfidence\". " +
                        "Análise parcial não pode resultar em confiança HIGH."
                )
            }
            appendLine()
            appendLine(SCHEMA)
        },
        maxOutputTokens = maxOutputTokens,
        temperature = 0.1,
        label = "parecer final"
    )

    private fun findingsBlock(input: FinalAssessmentInput): String {
        if (input.findings.isEmpty()) {
            return "## FINDINGS VALIDADOS\n(nenhum finding material sobreviveu à validação)"
        }

        return buildString {
            appendLine("## FINDINGS VALIDADOS")
            input.findings.forEach { finding ->
                val section = when (finding.bucket()) {
                    ReviewBucket.BLOCKING -> "SOLICITARIA AJUSTE"
                    ReviewBucket.QUESTION -> "QUESTIONARIA"
                    ReviewBucket.SUGGESTION -> "SUGESTÃO"
                    ReviewBucket.PRE_EXISTING -> "PRÉ-EXISTENTE"
                }
                appendLine()
                appendLine("- [$section][${finding.severity.name}/${finding.type.name}/${finding.category.name}] ${finding.location}")
                appendLine("  título: ${finding.title}")
                finding.evidence?.let { appendLine("  evidência: $it") }
                finding.failureScenario?.let { appendLine("  cenário: ${it.replace("\n", " | ")}") }
                finding.impact?.let { appendLine("  impacto: $it") }
            }
        }.trimEnd()
    }

    private companion object {
        val SYSTEM_EXTRA = """
In this step you do NOT look for new problems and you do NOT re-open the diff. You write the
executive opinion a reviewer would leave on the MR, based strictly on the validated findings
listed in the user message. Do not mention any problem that is not in that list.
""".trim()

        val TASK = """
# TAREFA: PARECER TÉCNICO

Write the final opinion. It must be short, technical and conclusive — 3 to 5 sentences in
Brazilian Portuguese, in first person, as a reviewer summarising their position. Structure it as:

1. whether the implementation is coherent with the MR's objective;
2. the point (if any) that deserves adjustment before merge, and why;
3. what you would leave as non-blocking questions or suggestions.

Example of the expected tone:
"A implementação está coerente com o objetivo do MR e não identifiquei problemas estruturais no
fluxo principal. Há, entretanto, um ponto que merece ajuste antes do merge relacionado à
consistência entre persistência e chamada ao provider externo. Também deixaria dois
questionamentos não bloqueantes sobre retry e cobertura de testes."

If the list of validated findings is empty, say so plainly and state that you would approve.
Do not manufacture concerns to fill the opinion.

"mainRisk": the single most relevant risk of this MR in one sentence, or null if there is none.

"analysisConfidence": how much confidence YOU have in this analysis, given the evidence available
(HIGH only with direct evidence and no stated limitation).

"recommendation": your suggestion. Note that the final decision is computed deterministically from
the findings; your suggestion can raise the outcome to NEEDS_DISCUSSION but only a finding with
evidence and a failure scenario produces REQUEST_CHANGES.
""".trim()

        val SCHEMA = """
## FORMATO DE RESPOSTA (JSON único)
{
  "opinion": "3 a 5 frases em pt-BR, primeira pessoa, conclusivas",
  "mainRisk": "principal risco em uma frase, ou null",
  "analysisConfidence": "HIGH|MEDIUM|LOW",
  "recommendation": "APPROVE|APPROVE_WITH_SUGGESTIONS|NEEDS_DISCUSSION|REQUEST_CHANGES",
  "questions": ["questionamentos consolidados que valem levar ao autor"],
  "positivePoints": ["pontos tecnicamente adequados que valem registrar"]
}
""".trim()
    }
}
