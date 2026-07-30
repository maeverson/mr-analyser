package com.mranalyser.infrastructure.config

import com.mranalyser.application.llm.parser.ReviewResponseParser
import com.mranalyser.application.llm.prompt.CrossFileReviewPrompt
import com.mranalyser.application.llm.prompt.FinalAssessmentPrompt
import com.mranalyser.application.llm.prompt.FindingValidationPrompt
import com.mranalyser.application.llm.prompt.LocalReviewPrompt
import com.mranalyser.application.llm.prompt.PromptSections
import com.mranalyser.application.llm.prompt.UnderstandingPrompt
import com.mranalyser.application.port.ContextBudget
import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.RepositoryContextProvider
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
import com.mranalyser.domain.rule.DebugCodeRule
import com.mranalyser.domain.rule.LargeChangeRule
import com.mranalyser.domain.rule.MissingTestCoverageRule
import com.mranalyser.domain.rule.ReviewRule
import com.mranalyser.domain.rule.SecretsRule
import com.mranalyser.domain.rule.TodoRule
import com.mranalyser.infrastructure.llm.AnthropicLlmProvider
import com.mranalyser.infrastructure.llm.GeminiLlmProvider
import com.mranalyser.infrastructure.llm.LlmTransportSettings
import com.mranalyser.infrastructure.llm.NoOpLlmProvider
import com.mranalyser.infrastructure.llm.OllamaLlmProvider
import com.mranalyser.infrastructure.llm.OpenAiLlmProvider
import com.mranalyser.infrastructure.llm.ResilientLlmProvider
import com.mranalyser.infrastructure.repository.LocalRepositoryContextProvider

/**
 * Montagem do pipeline a partir da configuração.
 *
 * Extraído da `AnalyseCommand` para que a CLI trate de argumentos e saída, e a composição de
 * dependências fique em um lugar só — o pipeline passou de 10 para 20 colaboradores e inline
 * na CLI ficaria ilegível.
 */
object AnalyzerFactory {

    fun createLlmProvider(config: AppConfig): LlmProvider {
        val transport = LlmTransportSettings(
            timeoutSeconds = config.llm.timeoutSeconds,
            jsonMode = config.llm.jsonMode
        )
        val key = config.llm.apiKey

        val base = when (config.llm.provider.lowercase()) {
            "openai" -> if (key.isNullOrBlank()) {
                NoOpLlmProvider()
            } else {
                OpenAiLlmProvider(
                    apiKey = key,
                    model = config.llm.model,
                    baseUrl = config.llm.url ?: "https://api.openai.com/v1",
                    settings = transport
                )
            }

            "anthropic" -> if (key.isNullOrBlank()) {
                NoOpLlmProvider()
            } else {
                AnthropicLlmProvider(
                    apiKey = key,
                    model = config.llm.model,
                    baseUrl = config.llm.url ?: "https://api.anthropic.com",
                    settings = transport
                )
            }

            "gemini" -> if (key.isNullOrBlank()) {
                NoOpLlmProvider()
            } else {
                GeminiLlmProvider(
                    apiKey = key,
                    model = config.llm.model,
                    baseUrl = config.llm.url ?: "https://generativelanguage.googleapis.com",
                    settings = transport
                )
            }

            "ollama" -> OllamaLlmProvider(
                model = config.llm.model,
                baseUrl = config.llm.url ?: "http://localhost:11434",
                apiKey = key,
                settings = transport.copy(
                    timeoutSeconds = maxOf(config.llm.timeoutSeconds, OLLAMA_MINIMUM_TIMEOUT_SECONDS)
                )
            )

            else -> NoOpLlmProvider()
        }

        return if (base is NoOpLlmProvider || config.llm.maxRetries <= 0) {
            base
        } else {
            ResilientLlmProvider(base, maxRetries = config.llm.maxRetries)
        }
    }

    fun createAnalyzer(
        config: AppConfig,
        llmProvider: LlmProvider,
        repositoryContextProvider: RepositoryContextProvider? = LocalRepositoryContextProvider()
    ): MergeRequestAnalyzer {
        val sections = PromptSections()
        val parser = ReviewResponseParser()

        return MergeRequestAnalyzer(
            rules = defaultRules(config),
            changeClassifier = ChangeClassifier(),
            signalDetector = ArchitecturalSignalDetector(),
            symbolExtractor = SymbolExtractor(),
            relationDetector = FileRelationDetector(),
            contextRetriever = RepositoryContextRetriever(
                provider = repositoryContextProvider.takeIf { config.context.enabled },
                budget = ContextBudget(
                    maxFilesPerChange = config.context.maxFilesPerChange,
                    maxTotalFiles = config.context.maxTotalFiles,
                    maxCharsPerFile = config.context.maxCharsPerFile
                ),
                requireRepositoryMatch = config.context.requireRepositoryMatch
            ),
            diffRenderer = AnnotatedDiffRenderer(maxLinesPerFile = config.limits.maxFileLines),
            reviewChunker = ReviewChunker(
                maxDiffLines = config.limits.maxDiffLines,
                maxFileLines = config.limits.maxFileLines
            ),
            understandingStage = ChangeUnderstandingStage(
                llmProvider = llmProvider,
                prompt = UnderstandingPrompt(sections),
                parser = parser,
                maxOutputTokens = config.llm.maxOutputTokensAssessment
            ),
            localReviewStage = LocalReviewStage(
                llmProvider = llmProvider,
                prompt = LocalReviewPrompt(sections),
                parser = parser,
                maxConcurrency = config.maxConcurrency,
                maxOutputTokens = config.llm.maxOutputTokensReview
            ),
            validationStage = FindingValidationStage(
                llmProvider = llmProvider,
                prompt = FindingValidationPrompt(sections),
                parser = parser,
                maxOutputTokens = config.llm.maxOutputTokensReview
            ),
            crossFileStage = CrossFileReviewStage(
                llmProvider = llmProvider,
                prompt = CrossFileReviewPrompt(sections),
                parser = parser,
                maxOutputTokens = config.llm.maxOutputTokensReview
            ),
            finalAssessmentStage = FinalAssessmentStage(
                llmProvider = llmProvider,
                prompt = FinalAssessmentPrompt(sections),
                parser = parser,
                maxOutputTokens = config.llm.maxOutputTokensAssessment
            ),
            deduplicator = FindingDeduplicator(),
            evidencePolicy = EvidencePolicy(),
            blockingPolicy = BlockingPolicy(),
            noisePolicy = NoisePolicy(ignoredCategories = config.review.ignoredCategories),
            recommendationCalculator = MergeRecommendationCalculator(),
            settings = AnalyzerSettings(
                minimumConfidence = config.review.minimumConfidence,
                showLowConfidence = config.review.showLowConfidence,
                maxFindings = config.review.maxFindings,
                understandingEnabled = config.review.understandingEnabled,
                validationEnabled = config.review.validationEnabled,
                crossFileEnabled = config.review.crossFileEnabled,
                finalAssessmentEnabled = config.review.finalAssessmentEnabled
            )
        )
    }

    private fun defaultRules(config: AppConfig): List<ReviewRule> = listOf(
        SecretsRule(),
        DebugCodeRule(),
        TodoRule(),
        LargeChangeRule(maxLinesPerFile = config.limits.maxFileLines),
        MissingTestCoverageRule()
    )

    private const val OLLAMA_MINIMUM_TIMEOUT_SECONDS = 600L
}
