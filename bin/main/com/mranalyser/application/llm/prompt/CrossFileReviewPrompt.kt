package com.mranalyser.application.llm.prompt

import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.review.CrossFileReviewInput

/**
 * Etapa 4: análise global (itens 7 e 27).
 *
 * Existe porque a V1 enviava chunks independentes e concatenava resultados, perdendo qualquer
 * problema que só aparece quando dois arquivos são avaliados juntos — Controller→Service→
 * Repository, Producer→Evento→Consumer, Migration→Entidade→Repository.
 */
class CrossFileReviewPrompt(
    private val sections: PromptSections = PromptSections()
) {
    fun build(input: CrossFileReviewInput, maxOutputTokens: Int): LlmRequest = LlmRequest(
        purpose = LlmPurpose.CROSS_FILE_REVIEW,
        system = ReviewPromptPolicy.systemPrompt(
            SYSTEM_EXTRA,
            ReviewPromptPolicy.FINDING_TYPE_TAXONOMY,
            ReviewPromptPolicy.SEVERITY_RUBRIC,
            ReviewPromptPolicy.CONFIDENCE_RUBRIC,
            ReviewPromptPolicy.EVIDENCE_REQUIREMENT,
            ReviewPromptPolicy.BLOCKING_RUBRIC,
            ReviewPromptPolicy.SCOPE_POLICY,
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
            sections.architecturalSignals(input.architecturalSignals).takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine(sections.changedFilesTable(input.overview))
            appendLine()
            appendLine(relations(input))
            appendLine()
            appendLine(existingFindings(input))
            appendLine()
            appendLine(sections.relatedContext(input.relatedContext))
            appendLine()
            appendLine("## LINHAS ADICIONADAS POR ARQUIVO")
            input.addedLinesByFile.forEach { (path, content) ->
                appendLine()
                appendLine("### $path")
                appendLine("```")
                appendLine(sections.clean(content))
                appendLine("```")
            }
            appendLine()
            appendLine(SCHEMA)
        },
        maxOutputTokens = maxOutputTokens,
        temperature = 0.1,
        label = "cross-file (${input.overview.files.size} arquivos)"
    )

    private fun relations(input: CrossFileReviewInput): String {
        if (input.relationEdges.isEmpty()) {
            return "## RELAÇÕES ENTRE OS ARQUIVOS ALTERADOS\n(nenhuma relação estrutural detectada estaticamente)"
        }
        return buildString {
            appendLine("## RELAÇÕES ENTRE OS ARQUIVOS ALTERADOS")
            input.relationEdges.forEach { appendLine("- ${it.from} -> ${it.to} (${it.reason})") }
        }.trimEnd()
    }

    private fun existingFindings(input: CrossFileReviewInput): String {
        if (input.confirmedFindings.isEmpty()) {
            return "## FINDINGS JÁ CONFIRMADOS\n(nenhum)"
        }
        return buildString {
            appendLine("## FINDINGS JÁ CONFIRMADOS (não repita nenhum deles)")
            input.confirmedFindings.forEach {
                appendLine("- [${it.severity.name}/${it.type.name}] ${it.location} — ${it.title}")
            }
        }.trimEnd()
    }

    private companion object {
        val SYSTEM_EXTRA = """
In this step you look ONLY for problems that are invisible when each file is read in isolation.
Anything you could have found by reading a single file has already been analysed and must not be
repeated here.
""".trim()

        val TASK = """
# TAREFA: ANÁLISE CROSS-FILE

Correlate the changed files and look for inconsistencies that only appear when two or more of
them are read together. Typical chains:

  Controller  -> Service   -> Repository
  Producer    -> Evento    -> Consumer
  Migration   -> Entidade  -> Repository
  Interface   -> Implementação(ões)
  DTO/Contrato-> Mapeamento -> Persistência

Look specifically for:
- contract mismatch between the two sides (field name, type, nullability, obligatoriness, enum value);
- validation applied on one side and assumed on the other;
- transactional boundary that does not cover all the writes involved;
- external call and local persistence in an order that can diverge on partial failure;
- one implementation of an interface updated and another left behind;
- migration that does not match the entity or the query;
- event published with data the consumer does not handle, or vice versa;
- duplicated business rule now diverging between two places;
- responsibility that moved layer and left the previous layer inconsistent.

Also do two global jobs:
1. "invalidatedFindings": list the exact titles of already-confirmed findings that the broader
   view proves wrong (e.g. the "missing validation" exists in the caller shown here).
2. "summary": 2 to 4 sentences describing the technical state of the change as a whole, in pt-BR.

If there is no genuine cross-file problem, return "crossFileFindings": []. That is the expected
answer for most MRs — do not invent one to justify this step.
""".trim()

        val SCHEMA = """
## FORMATO DE RESPOSTA (JSON único)
{
  "summary": "2 a 4 frases sobre o estado técnico da alteração como um todo",
  "crossFileFindings": [
    {
      "type": "BUG|RISK|DESIGN|ARCHITECTURE|QUESTION|SUGGESTION",
      "severity": "CRITICAL|HIGH|MEDIUM|LOW|INFO",
      "category": "BUG|BUSINESS_RULE|SECURITY|ARCHITECTURE|DESIGN|PERFORMANCE|CONCURRENCY|TRANSACTION|DATA_CONSISTENCY|RELIABILITY|API_CONTRACT|OBSERVABILITY|OPERATIONS|TESTABILITY|MAINTAINABILITY|COMPATIBILITY",
      "scope": "INTRODUCED|PRE_EXISTING",
      "file": "arquivo principal do problema",
      "line": 84,
      "relatedFiles": ["outro/arquivo/envolvido.kt"],
      "componentsAffected": ["ComponenteA", "ComponenteB"],
      "title": "título curto e específico",
      "description": "o que a visão conjunta revela",
      "evidence": "cite os DOIS lados: arquivo:linha de cada um e o que cada um faz",
      "failureScenario": "1. ...\\n2. ...\\n3. estado resultante",
      "impact": "consequência concreta",
      "recommendation": "o que avaliar ou corrigir",
      "blocking": false,
      "commentType": "BLOCKER|QUESTION|SUGGESTION|OBSERVATION",
      "suggestedComment": "comentário em pt-BR no estilo de um revisor sênior",
      "confidence": 0.85
    }
  ],
  "invalidatedFindings": ["título exato de finding que a visão global invalida"],
  "questions": ["pergunta ao autor sobre decisão que atravessa arquivos"],
  "positivePoints": ["pontos tecnicamente relevantes visíveis só na visão conjunta"]
}
""".trim()
    }
}
