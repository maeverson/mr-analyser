package com.mranalyser.application.service

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.RepositoryContextProvider
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.LlmReviewResult
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.RepositoryFileContext
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewContext
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.model.Severity
import com.mranalyser.domain.rule.ReviewRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MergeRequestAnalyzer(
    private val rules: List<ReviewRule>,
    private val llmProvider: LlmProvider,
    private val reviewChunker: ReviewChunker,
    private val deduplicator: FindingDeduplicator,
    private val recommendationCalculator: MergeRecommendationCalculator,
    private val minimumConfidence: Double,
    private val ignoredCategories: Set<String>,
    private val showLowConfidence: Boolean,
    private val repositoryContextProvider: RepositoryContextProvider?,
    private val maxConcurrency: Int
) {
    suspend fun analyse(mergeRequest: MergeRequest): ReviewReport = coroutineScope {
        val staticFindings = runStaticRules(mergeRequest)
        val chunks = reviewChunker.chunk(mergeRequest)
        val repositoryContext = repositoryContextProvider
            ?.findRelatedContext(mergeRequest.changes.map { it.newPath })
            ?.map {
                RepositoryFileContext(
                    referencePath = it.referencePath,
                    relatedPath = it.relatedPath,
                    content = it.content
                )
            }
            ?: emptyList()

        val semaphore = Semaphore(maxConcurrency)
        val llmResults = chunks.map { chunk ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    llmProvider.analyse(
                        ReviewContext(
                            title = mergeRequest.title,
                            description = mergeRequest.description,
                            sourceBranch = mergeRequest.sourceBranch,
                            targetBranch = mergeRequest.targetBranch,
                            changedFiles = chunk.files.map { it.newPath },
                            diff = chunk.files.joinToString("\n\n") { c ->
                                "FILE: ${c.newPath}\n${c.diff}"
                            },
                            commits = mergeRequest.commits,
                            existingDiscussions = mergeRequest.discussions.flatMap { d -> d.notes.map { it.body } },
                            repositoryContext = repositoryContext.filter { ctx ->
                                chunk.files.any { file -> file.newPath == ctx.referencePath }
                            }
                        )
                    )
                }
            }
        }.awaitAll()

        val llmFindings = llmResults.flatMap { it.findings }
        val deduplicatedFindings = deduplicator
            .deduplicate(staticFindings + llmFindings, mergeRequest.discussions)
            .filterNot { ignoredCategories.contains(it.category.name) }

        val findingsForDecision = deduplicatedFindings.filter { it.confidence >= minimumConfidence }
        val findingsToShow = if (showLowConfidence) deduplicatedFindings else findingsForDecision
        val allFindings = findingsToShow.sortedWith(
            compareByDescending<ReviewFinding> { severityWeight(it.severity) }
                .thenByDescending { it.confidence }
        )

        val mergedSummary = llmResults.mapNotNull { it.summary.takeIf(String::isNotBlank) }.joinToString("\n")
            .ifBlank { buildFallbackSummary(mergeRequest, allFindings) }

        val questions = llmResults.flatMap { it.questions }.distinct()
        val positives = llmResults.flatMap { it.positivePoints }.distinct()
        val recommendation = recommendationCalculator.calculate(findingsForDecision)

        ReviewReport(
            summary = mergedSummary,
            findings = allFindings,
            questions = questions,
            positivePoints = positives,
            recommendation = recommendation
        )
    }

    private fun runStaticRules(mergeRequest: MergeRequest): List<ReviewFinding> {
        val findings = mutableListOf<ReviewFinding>()
        mergeRequest.changes.forEach { change ->
            rules.filter { it.supports(change) }
                .forEach { rule -> findings += rule.analyse(mergeRequest, change) }
        }
        return findings
    }

    private fun buildFallbackSummary(
        mergeRequest: MergeRequest,
        findings: List<ReviewFinding>
    ): String {
        return "MR ${mergeRequest.title} analisado com ${mergeRequest.changes.size} arquivos alterados e ${findings.size} findings relevantes."
    }

    private fun severityWeight(severity: Severity): Int {
        return when (severity) {
            Severity.CRITICAL -> 5
            Severity.HIGH -> 4
            Severity.MEDIUM -> 3
            Severity.LOW -> 2
            Severity.INFO -> 1
        }
    }
}
