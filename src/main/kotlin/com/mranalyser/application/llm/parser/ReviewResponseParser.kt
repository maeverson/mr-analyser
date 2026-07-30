package com.mranalyser.application.llm.parser

import com.mranalyser.domain.model.BlastRadius
import com.mranalyser.domain.model.ChangeUnderstanding
import com.mranalyser.domain.model.FindingOrigin
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.TechnicalOpinion
import kotlinx.serialization.json.JsonObject

/**
 * Parser único para todas as etapas. Tenta cada candidato de JSON extraído da resposta, do
 * maior para o menor, e só falha quando nenhum deles contém a chave esperada.
 *
 * Nunca lança: uma resposta inválida vira [StageResult.Failure], para que a análise siga
 * degradada e o relatório informe o motivo (itens 33 e 38).
 */
class ReviewResponseParser {

    fun parseLocalReview(raw: String, origin: FindingOrigin = FindingOrigin.LOCAL_REVIEW): StageResult<LlmReviewResult> =
        withObject(raw, requiredKeys = listOf("findings", "summary")) { root ->
            val rawFindings = root.objList("findings")
            val findings = rawFindings.mapNotNull { FindingReader.read(it, origin) }
            LlmReviewResult(
                summary = root.str("summary").orEmpty(),
                findings = findings,
                questions = root.strList("questions"),
                positivePoints = root.strList("positivePoints"),
                suggestedRecommendation = EnumCoercion.recommendation(root.str("suggestedRecommendation")),
                droppedFindings = rawFindings.size - findings.size
            )
        }

    fun parseUnderstanding(raw: String): StageResult<UnderstandingResult> =
        withObject(raw, requiredKeys = listOf("narrative", "intent")) { root ->
            UnderstandingResult(
                ChangeUnderstanding(
                    intent = root.str("intent") ?: root.str("summary").orEmpty(),
                    narrative = root.str("narrative") ?: root.str("summary").orEmpty(),
                    behaviourChanges = root.strList("behaviourChanges"),
                    newExecutionPaths = root.strList("newExecutionPaths"),
                    contractChanges = root.strList("contractChanges"),
                    affectedDependencies = root.strList("affectedDependencies"),
                    blastRadius = EnumCoercion.blastRadius(root.str("blastRadius"), BlastRadius.UNKNOWN),
                    blastRadiusRationale = root.str("blastRadiusRationale"),
                    intentDiscrepancy = root.str("intentDiscrepancy")
                )
            )
        }

    fun parseValidation(raw: String): StageResult<ValidationResult> =
        withObject(raw, requiredKeys = listOf("verdicts")) { root ->
            ValidationResult(
                verdicts = root.objList("verdicts").mapNotNull { verdict ->
                    val id = verdict.str("id") ?: verdict.str("candidateId") ?: return@mapNotNull null
                    FindingVerdict(
                        candidateId = id.trim(),
                        decision = decision(verdict.str("decision")),
                        rationale = verdict.str("reason") ?: verdict.str("rationale"),
                        severity = verdict.str("severity")?.let { EnumCoercion.severity(it) },
                        confidence = verdict.dbl("confidence")?.let { EnumCoercion.confidence(it) },
                        blocking = verdict.bool("blocking"),
                        evidence = verdict.str("evidence"),
                        failureScenario = verdict.str("failureScenario"),
                        impact = verdict.str("impact"),
                        recommendation = verdict.str("recommendation"),
                        suggestedComment = verdict.str("suggestedComment"),
                        commentTypeRaw = verdict.str("commentType"),
                        scopeRaw = verdict.str("scope")
                    )
                }
            )
        }

    fun parseCrossFileReview(raw: String): StageResult<CrossFileResult> =
        withObject(raw, requiredKeys = listOf("summary", "crossFileFindings", "findings")) { root ->
            val rawFindings = root.objList("crossFileFindings").ifEmpty { root.objList("findings") }
            CrossFileResult(
                summary = root.str("summary").orEmpty(),
                newFindings = rawFindings.mapNotNull {
                    FindingReader.read(it, FindingOrigin.CROSS_FILE_REVIEW)
                },
                invalidatedTitles = root.strList("invalidatedFindings"),
                questions = root.strList("questions"),
                positivePoints = root.strList("positivePoints")
            )
        }

    fun parseFinalAssessment(raw: String): StageResult<FinalAssessmentResult> =
        withObject(raw, requiredKeys = listOf("opinion", "parecer")) { root ->
            FinalAssessmentResult(
                opinion = TechnicalOpinion(
                    opinion = root.str("opinion") ?: root.str("parecer").orEmpty(),
                    mainRisk = root.str("mainRisk"),
                    analysisConfidence = EnumCoercion.analysisConfidence(root.str("analysisConfidence"))
                ),
                suggestedRecommendation = EnumCoercion.recommendation(root.str("recommendation")),
                questions = root.strList("questions"),
                positivePoints = root.strList("positivePoints")
            )
        }

    private fun decision(raw: String?): ValidationDecision {
        val key = raw?.trim()?.uppercase()?.replace(' ', '_')?.replace('-', '_')
        return when (key) {
            "KEEP", "CONFIRM", "CONFIRMED", "VALID", "MANTER" -> ValidationDecision.KEEP
            "DOWNGRADE_TO_QUESTION", "DOWNGRADE", "QUESTION", "ASK", "QUESTIONAR" ->
                ValidationDecision.DOWNGRADE_TO_QUESTION
            "DISCARD", "DROP", "REJECT", "INVALID", "FALSE_POSITIVE", "DESCARTAR" ->
                ValidationDecision.DISCARD
            // Decisão ilegível é tratada como incerteza: rebaixa em vez de acusar ou descartar.
            else -> ValidationDecision.DOWNGRADE_TO_QUESTION
        }
    }

    private fun <T> withObject(
        raw: String,
        requiredKeys: List<String>,
        transform: (JsonObject) -> T
    ): StageResult<T> {
        if (raw.isBlank()) {
            return StageResult.Failure("resposta vazia do modelo")
        }

        val candidates = JsonExtractor.candidates(raw)
        if (candidates.isEmpty()) {
            return StageResult.Failure("nenhum objeto JSON encontrado na resposta do modelo")
        }

        val parsedObjects = candidates.mapNotNull { LenientJson.parseObject(it) }
        if (parsedObjects.isEmpty()) {
            return StageResult.Failure("JSON da resposta é inválido e não pôde ser recuperado")
        }

        val chosen = parsedObjects.firstOrNull { obj -> requiredKeys.any { obj.field(it) != null } }
            ?: return StageResult.Failure(
                "resposta JSON não contém nenhuma das chaves esperadas: ${requiredKeys.joinToString(", ")}"
            )

        return runCatching { StageResult.Success(transform(chosen)) }
            .getOrElse { StageResult.Failure("falha ao interpretar a resposta: ${it.message}") }
    }
}

/** Leitura de um finding individual, compartilhada entre a etapa local e a cross-file. */
internal object FindingReader {
    fun read(node: JsonObject, origin: FindingOrigin): ReviewFinding? {
        val title = node.str("title") ?: node.str("issue") ?: return null
        val description = node.str("description") ?: node.str("details") ?: title

        return ReviewFinding(
            severity = EnumCoercion.severity(node.str("severity")),
            category = EnumCoercion.category(node.str("category")),
            type = EnumCoercion.findingType(node.str("type")),
            file = node.str("file") ?: node.str("path"),
            line = node.int("line") ?: node.int("startLine"),
            title = title,
            description = description,
            evidence = node.str("evidence"),
            reasoning = node.str("reasoning"),
            failureScenario = node.str("failureScenario") ?: node.str("scenario"),
            impact = node.str("impact"),
            recommendation = node.str("recommendation"),
            blocking = node.bool("blocking") ?: false,
            suggestedComment = node.str("suggestedComment") ?: node.str("comment"),
            commentType = EnumCoercion.commentType(node.str("commentType")),
            scope = EnumCoercion.scope(node.str("scope")),
            origin = origin,
            componentsAffected = node.strList("componentsAffected"),
            relatedFiles = node.strList("relatedFiles"),
            confidence = EnumCoercion.confidence(node.dbl("confidence"))
        )
    }
}
