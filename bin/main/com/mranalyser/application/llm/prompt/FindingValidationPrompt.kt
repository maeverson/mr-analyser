package com.mranalyser.application.llm.prompt

import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.review.ValidationInput

/**
 * Etapa 3: validação de findings (item 8). É o mecanismo com maior efeito sobre
 * signal-to-noise em todo o sistema.
 *
 * O prompt é deliberadamente **adversarial**: o modelo é instruído a tentar derrubar cada
 * finding, e o benefício da dúvida vai para o autor do MR, não para o analisador. Pedir "valide"
 * produz confirmação complacente; pedir "refute" produz descarte de falso positivo.
 */
class FindingValidationPrompt(
    private val sections: PromptSections = PromptSections()
) {
    fun build(input: ValidationInput, maxOutputTokens: Int): LlmRequest = LlmRequest(
        purpose = LlmPurpose.VALIDATION,
        system = ReviewPromptPolicy.systemPrompt(
            SYSTEM_EXTRA,
            ReviewPromptPolicy.FINDING_TYPE_TAXONOMY,
            ReviewPromptPolicy.SEVERITY_RUBRIC,
            ReviewPromptPolicy.CONFIDENCE_RUBRIC,
            ReviewPromptPolicy.EVIDENCE_REQUIREMENT,
            ReviewPromptPolicy.BLOCKING_RUBRIC,
            ReviewPromptPolicy.COMMENT_STYLE,
            ReviewPromptPolicy.NOISE_POLICY
        ),
        user = buildString {
            appendLine(TASK)
            appendLine()
            appendLine(sections.mergeRequestHeader(input.overview))
            sections.understanding(input.understanding).takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine(sections.discussions(input.discussions))
            appendLine()
            appendLine(sections.relatedContext(input.relatedContext))
            appendLine()
            appendLine("## FINDINGS CANDIDATOS")
            input.candidates.forEachIndexed { index, finding ->
                val id = "F${index + 1}"
                appendLine()
                appendLine("### $id")
                appendLine("type: ${finding.type.name} | severity: ${finding.severity.name} | category: ${finding.category.name}")
                appendLine("scope: ${finding.scope.name} | confidence declarada: ${"%.2f".format(finding.confidence)} | origem: ${finding.origin.name}")
                appendLine("local: ${finding.location}")
                appendLine("title: ${finding.title}")
                appendLine("description: ${finding.description}")
                finding.evidence?.let { appendLine("evidence: $it") }
                finding.failureScenario?.let { appendLine("failureScenario: $it") }
                finding.impact?.let { appendLine("impact: $it") }
                finding.recommendation?.let { appendLine("recommendation: $it") }
                finding.suggestedComment?.let { appendLine("suggestedComment atual: $it") }
                input.evidenceExcerpts[id]?.let { excerpt ->
                    appendLine("código no local indicado:")
                    appendLine("```")
                    appendLine(sections.clean(excerpt))
                    appendLine("```")
                }
            }
            appendLine()
            appendLine(SCHEMA)
        },
        maxOutputTokens = maxOutputTokens,
        temperature = 0.0,
        label = "validação de ${input.candidates.size} candidatos"
    )

    private companion object {
        val SYSTEM_EXTRA = """
In this step you are an adversarial reviewer of the analyser itself, not of the MR.
Your default position is SKEPTICAL: assume each candidate finding is wrong until the provided
code proves otherwise. The benefit of the doubt goes to the MR author.
A candidate produced by an earlier step carries NO authority. Its declared severity, confidence
and evidence may all be wrong or invented.
""".trim()

        val TASK = """
# TAREFA: VALIDAÇÃO ADVERSARIAL DOS FINDINGS

For EACH candidate below, try to refute it. Answer these six questions internally:

1. Is there concrete evidence in the code shown? (not in the finding's own prose — in the code)
2. Is the described impact plausible given what the code actually does?
3. Is there any context here that invalidates the finding — including the related-context section
   showing that the "missing" retry/timeout/transaction/validation already exists elsewhere,
   or an existing discussion that already covers it?
4. Is it a real problem, or only a preference?
5. Is it worth interrupting the developer with this comment?
6. Does the comment help improve the MR?

Then decide:
- DISCARD                : any of (1) (2) (4) (5) (6) fails, or (3) invalidates it, or the
                           finding refers to removed code, or it is a duplicate of another
                           candidate, or it was already discussed in an existing discussion.
- DOWNGRADE_TO_QUESTION  : the concern is legitimate but the evidence is insufficient to assert
                           a problem. This is the correct answer for most uncertain candidates.
- KEEP                   : evidence is verifiable in the shown code AND the impact is plausible.

When you KEEP or DOWNGRADE, you may and should improve the finding:
- rewrite "evidence" so it cites the file, the line and what the code does — never a paraphrase
  of the conclusion;
- rewrite "failureScenario" as numbered steps ending in the resulting bad state;
- rewrite "suggestedComment" in the reviewer style described in the system prompt;
- correct "severity" and "confidence" to what the evidence actually supports (usually downwards);
- set "blocking" true only with evidence AND a described failure scenario.

Be aggressive about discarding. Discarding 12 of 18 candidates is a good outcome.
Every candidate must appear exactly once in "verdicts", using the same id (F1, F2, ...).
""".trim()

        val SCHEMA = """
## FORMATO DE RESPOSTA (JSON único)
{
  "verdicts": [
    {
      "id": "F1",
      "decision": "KEEP|DOWNGRADE_TO_QUESTION|DISCARD",
      "reason": "por que, em uma frase objetiva",
      "severity": "CRITICAL|HIGH|MEDIUM|LOW|INFO",
      "confidence": 0.85,
      "blocking": false,
      "scope": "INTRODUCED|PRE_EXISTING",
      "evidence": "fato verificável reescrito, ou null",
      "failureScenario": "1. ...\\n2. ...\\n3. estado resultante, ou null",
      "impact": "consequência concreta, ou null",
      "recommendation": "o que avaliar/corrigir, ou null",
      "commentType": "BLOCKER|QUESTION|SUGGESTION|OBSERVATION|PRAISE",
      "suggestedComment": "comentário reescrito em pt-BR, ou null"
    }
  ]
}
Para decision = DISCARD, apenas "id", "decision" e "reason" são necessários.
""".trim()
    }
}
