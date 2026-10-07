package com.mranalyser.application.llm.prompt

import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.review.FinalAssessmentInput
import com.mranalyser.domain.model.AttentionLevel
import com.mranalyser.domain.model.FindingScope

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
                val section = when {
                    finding.scope == FindingScope.PRE_EXISTING -> "PRÉ-EXISTENTE"
                    finding.attention == AttentionLevel.MINOR -> "MENOR"
                    else -> "ONDE TER CUIDADO"
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
In this step you do NOT look for new problems and you do NOT re-open the diff. You summarise,
for the human reviewer, where the validated findings say they should be careful. Do not mention
any problem that is not in that list.
""".trim()

        val TASK = """
# TAREFA: RESUMO PARA O REVISOR

The reader is an experienced engineer who will review this MR anyway. This report is a safety
double-check: it never approves or blocks anything. Your job is to tell them, in 30 seconds,
where in this MR they should be careful.

"attentionAreas": 2 to 4 items in Brazilian Portuguese, most important first. Each item is ONE
line in the form "<area of the MR> — <what to verify>", naming the real component, file or rule
of this MR. Build them from the "ONDE TER CUIDADO" findings and from the understanding; group
related findings into one area. Example of the FORM only (not of content):
"<adapter X> — <conferir se a falha Y é tratada>". If nothing deserves care, return [].

"opinion": 1 to 2 sentences in Brazilian Portuguese: what the change does and how risky it is to
review (e.g. "mudança contida, risco concentrado na integração nova"). No approval language, no
"antes do merge", no counting of findings.

"mainRisk": the single area that deserves the most care, in one sentence, or null.

"analysisConfidence": how much confidence YOU have in this analysis, given the evidence available
(HIGH only with direct evidence and no stated limitation).

"recommendation": kept for automation only; it is not shown as a verdict.

Never reuse sentences from these instructions. Do not manufacture concerns to fill the summary.
""".trim()

        val SCHEMA = """
## FORMATO DE RESPOSTA (JSON único)
{
  "attentionAreas": ["<área> — <o que conferir>"],
  "opinion": "1 a 2 frases em pt-BR sobre o que a mudança faz e onde está o risco",
  "mainRisk": "a área que mais merece cuidado, em uma frase, ou null",
  "analysisConfidence": "HIGH|MEDIUM|LOW",
  "recommendation": "APPROVE|APPROVE_WITH_SUGGESTIONS|NEEDS_DISCUSSION|REQUEST_CHANGES",
  "questions": ["perguntas que valem levar ao autor"],
  "positivePoints": ["o que o MR já cobre bem e o revisor não precisa reconferir"]
}
""".trim()
    }
}
