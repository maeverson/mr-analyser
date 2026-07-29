package com.mranalyser

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.RepositoryContextProvider
import com.mranalyser.application.service.FindingDeduplicator
import com.mranalyser.application.service.MergeRecommendationCalculator
import com.mranalyser.application.service.MergeRequestAnalyzer
import com.mranalyser.application.service.ReviewChunker
import com.mranalyser.domain.model.Author
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.LlmReviewResult
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewContext
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import com.mranalyser.domain.rule.SecretsRule
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MergeRequestAnalyzerTest {
    @Test
    fun `should combine static and llm findings`() = runBlocking {
        val llmProvider = object : LlmProvider {
            override suspend fun analyse(context: ReviewContext): LlmReviewResult {
                return LlmReviewResult(
                    summary = "Resumo IA",
                    findings = listOf(
                        ReviewFinding(
                            severity = Severity.MEDIUM,
                            category = ReviewCategory.PERFORMANCE,
                            file = "A.kt",
                            line = 2,
                            title = "Sem timeout",
                            description = "Sem timeout explicito",
                            impact = "latencia",
                            recommendation = "definir timeout",
                            suggestedComment = "Podemos definir timeout?",
                            confidence = 0.9
                        )
                    ),
                    questions = listOf("Qual timeout esperado?"),
                    positivePoints = listOf("Fallback implementado")
                )
            }
        }

        val analyzer = MergeRequestAnalyzer(
            rules = listOf(SecretsRule()),
            llmProvider = llmProvider,
            reviewChunker = ReviewChunker(1000, 1000),
            deduplicator = FindingDeduplicator(),
            recommendationCalculator = MergeRecommendationCalculator(),
            minimumConfidence = 0.6,
            ignoredCategories = emptySet(),
            showLowConfidence = false,
            repositoryContextProvider = object : RepositoryContextProvider {
                override fun detectRepositoryCoordinates() = null
                override fun findRelatedContext(changedFiles: List<String>) = emptyList<com.mranalyser.application.port.RelatedFileContext>()
            },
            maxConcurrency = 2
        )

        val report = analyzer.analyse(sampleMr())

        assertEquals(2, report.findings.size)
        assertEquals(1, report.questions.size)
        assertEquals("Resumo IA", report.summary)
    }

    private fun sampleMr(): MergeRequest {
        return MergeRequest(
            id = 1,
            iid = 123,
            title = "Title",
            description = "desc",
            author = Author(name = "Gabriel"),
            sourceBranch = "feature",
            targetBranch = "main",
            changes = listOf(
                FileChange("A.kt", "A.kt", false, false, false, "+token=abc\n+code", 2, 0)
            ),
            commits = emptyList(),
            discussions = emptyList()
        )
    }
}
