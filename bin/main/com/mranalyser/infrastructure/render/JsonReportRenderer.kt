package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.model.bucket
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Saída estruturada completa, incluindo os campos de rastreabilidade (evidência, cenário de
 * falha, veredito da validação, origem do finding). É a saída pensada para automação e para
 * auditar por que um finding apareceu.
 */
class JsonReportRenderer : ReportRenderer {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun render(mergeRequest: MergeRequest, report: ReviewReport): String {
        val payload = JsonReportPayload(
            mergeRequestIid = mergeRequest.iid,
            mergeRequestTitle = mergeRequest.title,
            projectPath = mergeRequest.projectPath,
            webUrl = mergeRequest.webUrl,
            author = mergeRequest.author.name,
            sourceBranch = mergeRequest.sourceBranch,
            targetBranch = mergeRequest.targetBranch,
            filesChanged = mergeRequest.changes.size,
            linesAdded = mergeRequest.changes.sumOf { it.linesAdded },
            linesRemoved = mergeRequest.changes.sumOf { it.linesRemoved },
            summary = report.summary,
            recommendation = report.recommendation.name,
            recommendationRationale = ReviewNarrative.recommendationExplanation(report.recommendation),
            understanding = report.understanding?.let {
                JsonUnderstanding(
                    intent = it.intent,
                    narrative = it.narrative,
                    behaviourChanges = it.behaviourChanges,
                    newExecutionPaths = it.newExecutionPaths,
                    contractChanges = it.contractChanges,
                    affectedDependencies = it.affectedDependencies,
                    blastRadius = it.blastRadius.name,
                    blastRadiusRationale = it.blastRadiusRationale,
                    intentDiscrepancy = it.intentDiscrepancy
                )
            },
            architecturalSignals = report.architecturalSignals.map {
                JsonArchitecturalSignal(kind = it.kind.name, detail = it.detail, file = it.file)
            },
            opinion = report.opinion?.let {
                JsonOpinion(
                    opinion = it.opinion,
                    mainRisk = it.mainRisk,
                    analysisConfidence = it.analysisConfidence.name
                )
            },
            findings = report.findings.map { finding ->
                JsonFinding(
                    bucket = finding.bucket().name,
                    severity = finding.severity.name,
                    category = finding.category.name,
                    type = finding.type.name,
                    scope = finding.scope.name,
                    origin = finding.origin.name,
                    file = finding.file,
                    line = finding.line,
                    title = finding.title,
                    description = finding.description,
                    evidence = finding.evidence,
                    reasoning = finding.reasoning,
                    failureScenario = finding.failureScenario,
                    impact = finding.impact,
                    recommendation = finding.recommendation,
                    blocking = finding.blocking,
                    suggestedComment = finding.suggestedComment,
                    commentType = finding.commentType?.name,
                    componentsAffected = finding.componentsAffected,
                    relatedFiles = finding.relatedFiles,
                    validationVerdict = finding.validation?.verdict?.name,
                    validationRationale = finding.validation?.rationale,
                    confidence = finding.confidence
                )
            },
            questions = report.questions,
            positivePoints = report.positivePoints,
            counts = JsonCounts(
                blocking = report.blockingFindings.size,
                questions = report.questionFindings.size,
                suggestions = report.suggestionFindings.size,
                preExisting = report.preExistingFindings.size
            ),
            quality = report.quality?.let {
                JsonQuality(
                    filesChanged = it.filesChanged,
                    filesAnalysed = it.filesAnalysed,
                    chunksAnalysed = it.chunksAnalysed,
                    chunksFailed = it.chunksFailed,
                    relatedContextsLoaded = it.relatedContextsLoaded,
                    candidateFindings = it.candidateFindings,
                    discardedByDeduplication = it.discardedByDeduplication,
                    discardedByValidation = it.discardedByValidation,
                    discardedByConfidence = it.discardedByConfidence,
                    discardedAsNoise = it.discardedAsNoise,
                    presentedFindings = it.presentedFindings,
                    skippedStages = it.skippedStages,
                    warnings = it.warnings,
                    partial = it.partial
                )
            }
        )
        return json.encodeToString(payload)
    }
}

@Serializable
data class JsonReportPayload(
    val mergeRequestIid: Long,
    val mergeRequestTitle: String,
    val projectPath: String? = null,
    val webUrl: String? = null,
    val author: String,
    val sourceBranch: String,
    val targetBranch: String,
    val filesChanged: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
    val summary: String,
    val recommendation: String,
    val recommendationRationale: String,
    val understanding: JsonUnderstanding? = null,
    val architecturalSignals: List<JsonArchitecturalSignal> = emptyList(),
    val opinion: JsonOpinion? = null,
    val findings: List<JsonFinding> = emptyList(),
    val questions: List<String> = emptyList(),
    val positivePoints: List<String> = emptyList(),
    val counts: JsonCounts,
    val quality: JsonQuality? = null
)

@Serializable
data class JsonUnderstanding(
    val intent: String,
    val narrative: String,
    val behaviourChanges: List<String> = emptyList(),
    val newExecutionPaths: List<String> = emptyList(),
    val contractChanges: List<String> = emptyList(),
    val affectedDependencies: List<String> = emptyList(),
    val blastRadius: String,
    val blastRadiusRationale: String? = null,
    val intentDiscrepancy: String? = null
)

@Serializable
data class JsonArchitecturalSignal(
    val kind: String,
    val detail: String,
    val file: String? = null
)

@Serializable
data class JsonOpinion(
    val opinion: String,
    val mainRisk: String? = null,
    val analysisConfidence: String
)

@Serializable
data class JsonFinding(
    val bucket: String,
    val severity: String,
    val category: String,
    val type: String,
    val scope: String,
    val origin: String,
    val file: String? = null,
    val line: Int? = null,
    val title: String,
    val description: String,
    val evidence: String? = null,
    val reasoning: String? = null,
    val failureScenario: String? = null,
    val impact: String? = null,
    val recommendation: String? = null,
    val blocking: Boolean,
    val suggestedComment: String? = null,
    val commentType: String? = null,
    val componentsAffected: List<String> = emptyList(),
    val relatedFiles: List<String> = emptyList(),
    val validationVerdict: String? = null,
    val validationRationale: String? = null,
    val confidence: Double
)

@Serializable
data class JsonCounts(
    val blocking: Int,
    val questions: Int,
    val suggestions: Int,
    val preExisting: Int
)

@Serializable
data class JsonQuality(
    val filesChanged: Int,
    val filesAnalysed: Int,
    val chunksAnalysed: Int,
    val chunksFailed: Int,
    val relatedContextsLoaded: Int,
    val candidateFindings: Int,
    val discardedByDeduplication: Int,
    val discardedByValidation: Int,
    val discardedByConfidence: Int,
    val discardedAsNoise: Int,
    val presentedFindings: Int,
    val skippedStages: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val partial: Boolean
)
