package com.mranalyser.application.usecase

import com.mranalyser.application.port.MergeRequestProvider
import com.mranalyser.application.service.MergeRequestAnalyzer
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewReport
import org.slf4j.LoggerFactory

class AnalyseMergeRequestUseCase(
    private val mergeRequestProvider: MergeRequestProvider,
    private val analyzer: MergeRequestAnalyzer
) {
    private val logger = LoggerFactory.getLogger(AnalyseMergeRequestUseCase::class.java)

    suspend fun execute(project: String, mrIid: Long): Pair<MergeRequest, ReviewReport> {
        logger.info("Fetching MR !{}...", mrIid)
        val mergeRequest = mergeRequestProvider.fetchMergeRequest(project, mrIid)
        logger.info("{} files changed.", mergeRequest.changes.size)

        logger.info("Running static analysis...")
        logger.info("Running AI review...")

        val report = analyzer.analyse(mergeRequest)
        logger.info("Generating report...")

        return mergeRequest to report
    }
}
