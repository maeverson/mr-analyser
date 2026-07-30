package com.mranalyser.application.service

import com.mranalyser.application.port.RelatedFileContext
import com.mranalyser.application.review.AnalysisDiagnostics
import com.mranalyser.application.review.ArchitecturalSignalDetector
import com.mranalyser.application.review.ChangeClassifier
import com.mranalyser.application.review.ChangeUnderstandingStage
import com.mranalyser.application.review.ChunkReviewInput
import com.mranalyser.application.review.ClassifiedFile
import com.mranalyser.application.review.CrossFileReviewStage
import com.mranalyser.application.review.ExistingDiscussion
import com.mranalyser.application.review.FileRelationDetector
import com.mranalyser.application.review.FinalAssessmentStage
import com.mranalyser.application.review.FindingValidationStage
import com.mranalyser.application.review.LocalReviewStage
import com.mranalyser.application.review.MergeRequestOverview
import com.mranalyser.application.review.RepositoryContextRetriever
import com.mranalyser.application.review.SymbolExtractor
import com.mranalyser.domain.diff.AnnotatedDiffRenderer
import com.mranalyser.domain.diff.UnifiedDiffParser
import com.mranalyser.domain.model.AnalysisQuality
import com.mranalyser.domain.model.ChangeUnderstanding
import com.mranalyser.domain.model.FindingScope
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ParsedDiff
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.policy.BlockingPolicy
import com.mranalyser.domain.policy.EvidencePolicy
import com.mranalyser.domain.policy.NoisePolicy
import com.mranalyser.domain.rule.ReviewRule
import com.mranalyser.domain.rule.RuleContext
import org.slf4j.LoggerFactory

data class AnalyzerSettings(
    val minimumConfidence: Double = 0.60,
    val showLowConfidence: Boolean = false,
    val maxFindings: Int = 25,
    val understandingEnabled: Boolean = true,
    val validationEnabled: Boolean = true,
    val crossFileEnabled: Boolean = true,
    val finalAssessmentEnabled: Boolean = true
)

/**
 * Orquestrador do pipeline de análise. Deliberadamente **sem heurística própria**: sequencia as
 * etapas, agrega diagnósticos e delega toda decisão a um componente com responsabilidade única
 * (item 25). A versão anterior concentrava chunking, contexto, filtro, ordenação e decisão em um
 * único método.
 *
 * Nenhuma etapa LLM é obrigatória: se qualquer uma falhar, a análise segue degradada e o motivo
 * aparece em `AnalysisQuality.warnings` (item 38).
 */
class MergeRequestAnalyzer(
    private val rules: List<ReviewRule>,
    private val changeClassifier: ChangeClassifier,
    private val signalDetector: ArchitecturalSignalDetector,
    private val symbolExtractor: SymbolExtractor,
    private val relationDetector: FileRelationDetector,
    private val contextRetriever: RepositoryContextRetriever,
    private val diffRenderer: AnnotatedDiffRenderer,
    private val reviewChunker: ReviewChunker,
    private val understandingStage: ChangeUnderstandingStage,
    private val localReviewStage: LocalReviewStage,
    private val validationStage: FindingValidationStage,
    private val crossFileStage: CrossFileReviewStage,
    private val finalAssessmentStage: FinalAssessmentStage,
    private val deduplicator: FindingDeduplicator,
    private val evidencePolicy: EvidencePolicy,
    private val blockingPolicy: BlockingPolicy,
    private val noisePolicy: NoisePolicy,
    private val recommendationCalculator: MergeRecommendationCalculator,
    private val settings: AnalyzerSettings = AnalyzerSettings()
) {
    private val logger = LoggerFactory.getLogger(MergeRequestAnalyzer::class.java)

    suspend fun analyse(mergeRequest: MergeRequest): ReviewReport {
        val diagnostics = AnalysisDiagnostics()

        // --- Preparação determinística ---------------------------------------------------
        val analysable = mergeRequest.changes.filterNot { it.generated }
        if (analysable.size < mergeRequest.changes.size) {
            diagnostics.warn("${mergeRequest.changes.size - analysable.size} arquivo(s) gerado(s) ignorado(s)")
        }

        val parsedDiffs = analysable.associate { it.path to UnifiedDiffParser.parse(it.diff) }
        val files = analysable.map { change ->
            val parsed = parsedDiffs.getValue(change.path)
            ClassifiedFile(
                change = change,
                group = changeClassifier.classify(change, parsed),
                annotatedDiff = diffRenderer.render(change, parsed)
            )
        }

        val overview = MergeRequestOverview.from(mergeRequest, files)
        val signals = signalDetector.detect(files, parsedDiffs)
        val discussions = flattenDiscussions(mergeRequest)

        val queries = files.associate { it.path to symbolExtractor.extract(it.path, parsedDiffs.getValue(it.path)) }
        val relatedContext = contextRetriever.retrieve(mergeRequest.projectPath, queries.values.toList(), diagnostics)
        val relations = relationDetector.detect(files, queries)

        // --- Etapa 1: entendimento --------------------------------------------------------
        val understanding = if (settings.understandingEnabled) {
            understandingStage.run(overview, signals, parsedDiffs, diagnostics)
        } else {
            diagnostics.skipStage("entendimento da alteração", "desabilitado por configuração")
            null
        }

        // --- Etapa 2: regras estáticas + review local por chunk ---------------------------
        val staticFindings = runStaticRules(mergeRequest, files, parsedDiffs)
        val chunks = reviewChunker.chunk(files)
        val localResults = localReviewStage.run(
            chunks.map { chunk ->
                ChunkReviewInput(
                    overview = overview,
                    chunkIndex = chunk.index,
                    chunkCount = chunks.size,
                    group = chunk.group,
                    files = chunk.files,
                    relatedContext = relatedContext.filter { context ->
                        chunk.files.any { it.path == context.referencePath }
                    },
                    discussions = discussions,
                    understanding = understanding,
                    architecturalSignals = signals
                )
            },
            diagnostics
        )

        val candidates = staticFindings + localResults.flatMap { it.findings }
        diagnostics.candidateFindings = candidates.size

        // --- Etapa 3: deduplicação --------------------------------------------------------
        val deduplication = deduplicator.analyse(candidates, mergeRequest.discussions)
        diagnostics.discardedByDeduplication =
            deduplication.removedAsDuplicate + deduplication.removedAsAlreadyDiscussed

        // --- Etapa 4: validação adversarial ----------------------------------------------
        val validated = if (settings.validationEnabled) {
            validationStage.run(
                overview = overview,
                understanding = understanding,
                candidates = deduplication.findings,
                relatedContext = relatedContext,
                discussions = discussions,
                parsedDiffs = parsedDiffs,
                diagnostics = diagnostics
            ).also { diagnostics.discardedByValidation = it.discarded }
        } else {
            diagnostics.skipStage("validação de findings", "desabilitada por configuração")
            FindingValidationStage.Outcome(deduplication.findings, discarded = 0, validated = false)
        }

        // --- Etapa 5: análise cross-file --------------------------------------------------
        val crossFile = if (settings.crossFileEnabled) {
            crossFileStage.run(
                overview = overview,
                understanding = understanding,
                signals = signals,
                confirmedFindings = validated.findings,
                relations = relations,
                parsedDiffs = parsedDiffs,
                relatedContext = relatedContext,
                diagnostics = diagnostics
            )
        } else {
            diagnostics.skipStage("análise cross-file", "desabilitada por configuração")
            CrossFileReviewStage.Outcome.EMPTY
        }

        // --- Etapa 6: políticas determinísticas ------------------------------------------
        val afterCrossFile = applyInvalidations(validated.findings, crossFile.invalidatedTitles, diagnostics) +
            crossFile.newFindings

        // A ordem importa: evidência limita a severidade, a política de bloqueio decide, e o
        // gate de validação revoga por último — um achado não confrontado com o código não
        // segura um merge, ainda que satisfaça os critérios objetivos de bloqueio.
        val refined = afterCrossFile
            .map(evidencePolicy::apply)
            .map(blockingPolicy::apply)
            .map { finding -> if (validated.validated) finding else blockingPolicy.revokeBlocking(finding) }

        val (visible, filtered) = applyVisibilityFilters(refined, diagnostics)

        val questions = (localResults.flatMap { it.questions } + crossFile.questions).distinct()
        val positives = (localResults.flatMap { it.positivePoints } + crossFile.positivePoints).distinct()

        // --- Etapa 7: parecer final -------------------------------------------------------
        val assessment = if (settings.finalAssessmentEnabled) {
            finalAssessmentStage.run(overview, understanding, signals, visible, positives, questions, diagnostics)
        } else {
            diagnostics.skipStage("parecer técnico", "desabilitado por configuração")
            null
        }

        val recommendation = recommendationCalculator.calculate(
            findings = filtered,
            openQuestions = questions,
            llmSuggestion = assessment?.suggestedRecommendation
                ?: localResults.mapNotNull { it.suggestedRecommendation }.maxByOrNull { it.ordinal }
        )

        logger.info(
            "Análise concluída: {} candidatos -> {} apresentados, recomendação {}",
            diagnostics.candidateFindings,
            visible.size,
            recommendation
        )

        return ReviewReport(
            summary = buildSummary(crossFile.summary, localResults.map { it.summary }, understanding, visible.size),
            findings = visible,
            questions = (questions + assessment?.questions.orEmpty()).distinct(),
            positivePoints = (positives + assessment?.positivePoints.orEmpty()).distinct(),
            recommendation = recommendation,
            understanding = understanding,
            architecturalSignals = signals,
            opinion = assessment?.opinion,
            quality = AnalysisQuality(
                filesChanged = mergeRequest.changes.size,
                filesAnalysed = files.size,
                chunksAnalysed = diagnostics.chunksAnalysed,
                chunksFailed = diagnostics.chunksFailed,
                relatedContextsLoaded = diagnostics.relatedContextsLoaded,
                candidateFindings = diagnostics.candidateFindings,
                discardedByDeduplication = diagnostics.discardedByDeduplication,
                discardedByValidation = diagnostics.discardedByValidation,
                discardedByConfidence = diagnostics.discardedByConfidence,
                discardedAsNoise = diagnostics.discardedAsNoise,
                presentedFindings = visible.size,
                skippedStages = diagnostics.skippedStages,
                warnings = diagnostics.warnings
            )
        )
    }

    private fun runStaticRules(
        mergeRequest: MergeRequest,
        files: List<ClassifiedFile>,
        parsedDiffs: Map<String, ParsedDiff>
    ): List<ReviewFinding> = files.flatMap { file ->
        val context = RuleContext(
            mergeRequest = mergeRequest,
            change = file.change,
            parsedDiff = parsedDiffs.getValue(file.path),
            group = file.group
        )
        rules.filter { it.supports(context) }.flatMap { rule ->
            runCatching { rule.analyse(context) }.getOrElse { throwable ->
                logger.warn("Regra {} falhou em {}: {}", rule.name, file.path, throwable.message)
                emptyList()
            }
        }
    }

    /** Remove findings que a visão cross-file provou incorretos (item 7). */
    private fun applyInvalidations(
        findings: List<ReviewFinding>,
        invalidatedTitles: List<String>,
        diagnostics: AnalysisDiagnostics
    ): List<ReviewFinding> {
        if (invalidatedTitles.isEmpty()) {
            return findings
        }
        val normalized = invalidatedTitles.map { it.lowercase().trim() }.toSet()
        val (removed, kept) = findings.partition { it.title.lowercase().trim() in normalized }
        removed.forEach { diagnostics.warn("finding \"${it.title}\" invalidado pela análise cross-file") }
        diagnostics.discardedByValidation += removed.size
        return kept
    }

    /**
     * Retorna (findings exibidos, findings usados na decisão). Os conjuntos divergem quando
     * `showLowConfidence` está ativo: baixa confiança pode aparecer no relatório, mas nunca
     * participa da recomendação de merge.
     */
    private fun applyVisibilityFilters(
        findings: List<ReviewFinding>,
        diagnostics: AnalysisDiagnostics
    ): Pair<List<ReviewFinding>, List<ReviewFinding>> {
        val afterNoise = findings.filter { finding ->
            val decision = noisePolicy.evaluate(finding)
            if (decision.suppressed) {
                diagnostics.discardedAsNoise++
                logger.debug("Finding suprimido ({}): {}", decision.reason, finding.title)
            }
            !decision.suppressed
        }

        val confident = afterNoise.filter { it.confidence >= settings.minimumConfidence }
        diagnostics.discardedByConfidence = afterNoise.size - confident.size

        val displayed = (if (settings.showLowConfidence) afterNoise else confident)
            .sortedWith(
                compareByDescending<ReviewFinding> { if (it.blocking) 1 else 0 }
                    .thenBy { if (it.scope == FindingScope.PRE_EXISTING) 1 else 0 }
                    .thenByDescending { it.severity.weight }
                    .thenByDescending { it.confidence }
            )
            .take(settings.maxFindings)

        if (displayed.size < afterNoise.size && settings.showLowConfidence) {
            diagnostics.warn("relatório limitado aos ${settings.maxFindings} findings mais relevantes")
        }

        return displayed to confident
    }

    /** Itens 31 e 32: descarta notas de sistema, que são ruído puro no prompt. */
    private fun flattenDiscussions(mergeRequest: MergeRequest): List<ExistingDiscussion> =
        mergeRequest.discussions.flatMap { discussion ->
            discussion.notes
                .filterNot { it.system }
                .filter { it.body.isNotBlank() }
                .map { note ->
                    ExistingDiscussion(
                        author = note.author.name,
                        body = note.body,
                        file = note.file,
                        line = note.line,
                        resolved = note.resolved || discussion.resolved
                    )
                }
        }

    private fun buildSummary(
        crossFileSummary: String,
        localSummaries: List<String>,
        understanding: ChangeUnderstanding?,
        presentedFindings: Int
    ): String = listOf(
        crossFileSummary,
        understanding?.narrative.orEmpty(),
        localSummaries.firstOrNull { it.isNotBlank() }.orEmpty()
    ).firstOrNull { it.isNotBlank() }
        ?: "Análise concluída com $presentedFindings ponto(s) relevante(s) para revisão."
}
