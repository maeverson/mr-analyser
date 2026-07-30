package com.mranalyser.support

import com.mranalyser.application.port.ContextRetrievalRequest
import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.RelatedFileContext
import com.mranalyser.application.port.RepositoryContextProvider
import com.mranalyser.application.port.RepositoryCoordinates
import com.mranalyser.application.review.ArchitecturalSignalDetector
import com.mranalyser.application.review.ChangeClassifier
import com.mranalyser.application.review.ChangeUnderstandingStage
import com.mranalyser.application.review.CrossFileReviewStage
import com.mranalyser.application.review.FileRelationDetector
import com.mranalyser.application.review.FinalAssessmentStage
import com.mranalyser.application.review.FindingValidationStage
import com.mranalyser.application.review.LocalReviewStage
import com.mranalyser.application.review.RepositoryContextRetriever
import com.mranalyser.application.review.SymbolExtractor
import com.mranalyser.application.service.AnalyzerSettings
import com.mranalyser.application.service.FindingDeduplicator
import com.mranalyser.application.service.MergeRecommendationCalculator
import com.mranalyser.application.service.MergeRequestAnalyzer
import com.mranalyser.application.service.ReviewChunker
import com.mranalyser.domain.diff.AnnotatedDiffRenderer
import com.mranalyser.domain.policy.BlockingPolicy
import com.mranalyser.domain.policy.EvidencePolicy
import com.mranalyser.domain.policy.NoisePolicy
import com.mranalyser.domain.rule.ReviewRule

/** Monta o pipeline com providers falsos, mantendo todas as políticas reais. */
object TestAnalyzer {

    fun build(
        llmProvider: LlmProvider,
        rules: List<ReviewRule> = emptyList(),
        repositoryContextProvider: RepositoryContextProvider? = null,
        settings: AnalyzerSettings = AnalyzerSettings(),
        contexts: List<RelatedFileContext> = emptyList()
    ): MergeRequestAnalyzer = MergeRequestAnalyzer(
        rules = rules,
        changeClassifier = ChangeClassifier(),
        signalDetector = ArchitecturalSignalDetector(),
        symbolExtractor = SymbolExtractor(),
        relationDetector = FileRelationDetector(),
        contextRetriever = RepositoryContextRetriever(
            provider = repositoryContextProvider ?: contexts.takeIf { it.isNotEmpty() }
                ?.let { StubContextProvider(it) },
            requireRepositoryMatch = false
        ),
        diffRenderer = AnnotatedDiffRenderer(),
        reviewChunker = ReviewChunker(maxDiffLines = 2_000, maxFileLines = 900),
        understandingStage = ChangeUnderstandingStage(llmProvider),
        localReviewStage = LocalReviewStage(llmProvider, maxConcurrency = 2),
        validationStage = FindingValidationStage(llmProvider),
        crossFileStage = CrossFileReviewStage(llmProvider),
        finalAssessmentStage = FinalAssessmentStage(llmProvider),
        deduplicator = FindingDeduplicator(),
        evidencePolicy = EvidencePolicy(),
        blockingPolicy = BlockingPolicy(),
        noisePolicy = NoisePolicy(),
        recommendationCalculator = MergeRecommendationCalculator(),
        settings = settings
    )

    class StubContextProvider(
        private val contexts: List<RelatedFileContext>,
        private val coordinates: RepositoryCoordinates? = RepositoryCoordinates(
            host = "https://gitlab.example.com",
            projectPath = "grupo/projeto"
        )
    ) : RepositoryContextProvider {
        override fun detectRepositoryCoordinates(): RepositoryCoordinates? = coordinates

        override fun findRelatedContext(request: ContextRetrievalRequest): List<RelatedFileContext> = contexts
    }
}
