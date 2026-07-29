package com.mranalyser

import com.mranalyser.domain.model.Author
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.infrastructure.render.JsonReportRenderer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonReportRendererTest {
    @Test
    fun `should render json payload`() {
        val renderer = JsonReportRenderer()
        val mr = MergeRequest(
            id = 1,
            iid = 88,
            title = "MR",
            description = null,
            author = Author(name = "Author"),
            sourceBranch = "feature",
            targetBranch = "main",
            changes = listOf(FileChange("a.kt", "a.kt", false, false, false, "+a", 1, 0)),
            commits = emptyList(),
            discussions = emptyList()
        )
        val report = ReviewReport(
            summary = "Resumo",
            findings = emptyList(),
            questions = emptyList(),
            positivePoints = emptyList(),
            recommendation = MergeRecommendation.APPROVE
        )

        val output = renderer.render(mr, report)
        assertTrue(output.contains("\"mergeRequestIid\""))
        assertTrue(output.contains("\"recommendation\""))
    }
}
