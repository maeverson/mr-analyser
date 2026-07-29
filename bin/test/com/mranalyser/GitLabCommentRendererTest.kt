package com.mranalyser

import com.mranalyser.domain.model.Author
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.model.Severity
import com.mranalyser.infrastructure.render.GitLabCommentRenderer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitLabCommentRendererTest {
    @Test
    fun `should render suggested gitlab comments`() {
        val renderer = GitLabCommentRenderer()
        val mr = MergeRequest(
            id = 1,
            iid = 12,
            title = "MR",
            description = null,
            author = Author(name = "A"),
            sourceBranch = "feature",
            targetBranch = "main",
            changes = listOf(FileChange("A.kt", "A.kt", false, false, false, "+a", 1, 0)),
            commits = emptyList(),
            discussions = emptyList()
        )
        val report = ReviewReport(
            summary = "Resumo",
            findings = listOf(
                ReviewFinding(
                    severity = Severity.HIGH,
                    category = ReviewCategory.SECURITY,
                    file = "A.kt",
                    line = 42,
                    title = "Segredo",
                    description = "Possivel segredo",
                    impact = "vazamento",
                    recommendation = "remover",
                    suggestedComment = "Podemos remover este segredo?",
                    confidence = 0.95
                )
            ),
            questions = emptyList(),
            positivePoints = emptyList(),
            recommendation = MergeRecommendation.REQUEST_CHANGES
        )

        val output = renderer.render(mr, report)
        assertTrue(output.contains("SUGGESTED GITLAB COMMENTS"))
        assertTrue(output.contains("A.kt:42"))
    }
}
