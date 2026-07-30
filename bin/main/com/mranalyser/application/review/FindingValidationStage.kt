package com.mranalyser.application.review

import com.mranalyser.application.llm.parser.FindingVerdict
import com.mranalyser.application.llm.parser.ReviewResponseParser
import com.mranalyser.application.llm.parser.StageResult
import com.mranalyser.application.llm.parser.ValidationDecision
import com.mranalyser.application.llm.prompt.FindingValidationPrompt
import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.RelatedFileContext
import com.mranalyser.domain.model.ChangeUnderstanding
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ParsedDiff
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import com.mranalyser.domain.model.ValidationOutcome
import com.mranalyser.domain.model.ValidationVerdict
import com.mranalyser.application.llm.parser.EnumCoercion

/**
 * Etapa 3 do pipeline: validação adversarial de findings (item 8).
 *
 * É o componente com maior efeito sobre signal-to-noise. Cada candidato é confrontado com o
 * código real no local indicado e com o contexto relacionado; o modelo é instruído a refutar.
 *
 * Política de falha, deliberada:
 * - falha da etapa inteira → mantém os candidatos, marca a análise como parcial e força
 *   `blocking = false` (não se bloqueia um MR com achados não validados);
 * - candidato omitido pelo modelo → é mantido, mas rebaixado a questionamento, porque omissão
 *   não é confirmação. Descartar por omissão perderia achados válidos por descuido do modelo.
 */
class FindingValidationStage(
    private val llmProvider: LlmProvider,
    private val prompt: FindingValidationPrompt = FindingValidationPrompt(),
    private val parser: ReviewResponseParser = ReviewResponseParser(),
    private val batchSize: Int = 8,
    private val maxOutputTokens: Int = 6_000,
    private val excerptContextLines: Int = 12
) {
    data class Outcome(
        val findings: List<ReviewFinding>,
        val discarded: Int,
        val validated: Boolean
    )

    suspend fun run(
        overview: MergeRequestOverview,
        understanding: ChangeUnderstanding?,
        candidates: List<ReviewFinding>,
        relatedContext: List<RelatedFileContext>,
        discussions: List<ExistingDiscussion>,
        parsedDiffs: Map<String, ParsedDiff>,
        diagnostics: AnalysisDiagnostics
    ): Outcome {
        if (candidates.isEmpty()) {
            return Outcome(emptyList(), discarded = 0, validated = true)
        }

        val kept = mutableListOf<ReviewFinding>()
        var discarded = 0
        var anyBatchFailed = false

        candidates.chunked(batchSize).forEach { batch ->
            val identified = batch.mapIndexed { index, finding -> "F${index + 1}" to finding }.toMap()

            val input = ValidationInput(
                overview = overview,
                understanding = understanding,
                candidates = batch,
                relatedContext = relatedContext.filter { context ->
                    batch.any { it.file != null && it.file == context.referencePath }
                }.ifEmpty { relatedContext.take(MAX_FALLBACK_CONTEXTS) },
                discussions = discussions,
                evidenceExcerpts = identified.mapNotNull { (id, finding) ->
                    excerptFor(finding, parsedDiffs)?.let { id to it }
                }.toMap()
            )

            val response = llmProvider.complete(prompt.build(input, maxOutputTokens))
            if (!response.successful) {
                anyBatchFailed = true
                diagnostics.warn("validação de findings indisponível: ${response.failure}")
                kept += batch.map { unvalidated(it) }
                return@forEach
            }

            val parsed = parser.parseValidation(response.text)
            if (parsed is StageResult.Failure) {
                anyBatchFailed = true
                diagnostics.warn("resposta da validação inválida: ${parsed.reason}")
                kept += batch.map { unvalidated(it) }
                return@forEach
            }

            val verdicts = (parsed as StageResult.Success).value.verdicts.associateBy { it.candidateId }

            identified.forEach { (id, finding) ->
                val verdict = verdicts[id]
                when {
                    verdict == null -> {
                        diagnostics.warn("validação não retornou veredito para \"${finding.title}\"; rebaixado a questionamento")
                        kept += downgrade(finding, "veredito ausente na validação")
                    }

                    verdict.decision == ValidationDecision.DISCARD -> discarded++

                    verdict.decision == ValidationDecision.DOWNGRADE_TO_QUESTION ->
                        kept += downgrade(apply(finding, verdict), verdict.rationale)

                    else -> kept += apply(finding, verdict).copy(
                        validation = ValidationOutcome(
                            verdict = if (verdict.severity != null && verdict.severity != finding.severity) {
                                ValidationVerdict.SEVERITY_ADJUSTED
                            } else {
                                ValidationVerdict.CONFIRMED
                            },
                            rationale = verdict.rationale
                        )
                    )
                }
            }
        }

        return Outcome(
            findings = kept,
            discarded = discarded,
            validated = !anyBatchFailed
        )
    }

    /** Aplica as correções que o validador fez sobre o candidato. */
    private fun apply(finding: ReviewFinding, verdict: FindingVerdict): ReviewFinding = finding.copy(
        severity = verdict.severity ?: finding.severity,
        confidence = verdict.confidence ?: finding.confidence,
        blocking = verdict.blocking ?: finding.blocking,
        evidence = verdict.evidence ?: finding.evidence,
        failureScenario = verdict.failureScenario ?: finding.failureScenario,
        impact = verdict.impact ?: finding.impact,
        recommendation = verdict.recommendation ?: finding.recommendation,
        suggestedComment = verdict.suggestedComment ?: finding.suggestedComment,
        commentType = EnumCoercion.commentType(verdict.commentTypeRaw) ?: finding.commentType,
        scope = verdict.scopeRaw?.let { EnumCoercion.scope(it, finding.scope) } ?: finding.scope
    )

    private fun downgrade(finding: ReviewFinding, rationale: String?): ReviewFinding = finding.copy(
        type = FindingType.QUESTION,
        severity = finding.severity.cappedAt(Severity.MEDIUM),
        blocking = false,
        validation = ValidationOutcome(ValidationVerdict.DOWNGRADED_TO_QUESTION, rationale)
    )

    /** Candidato que não pôde ser validado: preservado, porém nunca bloqueante. */
    private fun unvalidated(finding: ReviewFinding): ReviewFinding = finding.copy(
        blocking = false,
        confidence = finding.confidence * UNVALIDATED_CONFIDENCE_PENALTY,
        validation = ValidationOutcome(ValidationVerdict.NOT_VALIDATED, "etapa de validação indisponível")
    )

    /**
     * Recorte do diff em volta da linha citada pelo finding, para que o validador confronte a
     * afirmação com o código real em vez de confiar na prosa do candidato.
     */
    private fun excerptFor(finding: ReviewFinding, parsedDiffs: Map<String, ParsedDiff>): String? {
        val file = finding.file ?: return null
        val parsed = parsedDiffs[file] ?: parsedDiffs.entries.firstOrNull { it.key.endsWith(file) }?.value ?: return null

        val lines = parsed.lines
        if (lines.isEmpty()) {
            return null
        }

        val anchor = finding.line?.let { target ->
            lines.indexOfFirst { it.newLine == target || it.oldLine == target }.takeIf { it >= 0 }
        }

        val range = if (anchor == null) {
            0 until minOf(lines.size, excerptContextLines * 3)
        } else {
            val from = (anchor - excerptContextLines).coerceAtLeast(0)
            val to = (anchor + excerptContextLines).coerceAtMost(lines.size - 1)
            from..to
        }

        return range.joinToString("\n") { index ->
            val line = lines[index]
            val reference = (line.newLine ?: line.oldLine)?.toString().orEmpty()
            "${line.origin.tag} ${reference.padStart(6)} | ${line.content}"
        }
    }

    private companion object {
        const val MAX_FALLBACK_CONTEXTS = 6
        const val UNVALIDATED_CONFIDENCE_PENALTY = 0.9
    }
}
