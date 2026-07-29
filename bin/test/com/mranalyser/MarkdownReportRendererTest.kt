package com.mranalyser

import com.mranalyser.domain.model.Author
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.model.Severity
import com.mranalyser.infrastructure.render.MarkdownReportRenderer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownReportRendererTest {
    @Test
    fun `should render markdown sections`() {
        val renderer = MarkdownReportRenderer()
        val mr = MergeRequest(
            id = 1,
            iid = 77,
            title = "MR title",
            description = null,
            author = Author(name = "Gabriel"),
            sourceBranch = "feature",
            targetBranch = "main",
            changes = listOf(FileChange("a.kt", "a.kt", false, false, false, "+x", 1, 0)),
            commits = emptyList(),
            discussions = emptyList()
        )
        val report = ReviewReport(
            summary = "Resumo",
            findings = listOf(
                ReviewFinding(
                    severity = Severity.HIGH,
                    category = ReviewCategory.BUG,
                    file = "a.kt",
                    line = 10,
                    title = "Erro",
                    description = "Descricao",
                    impact = "Impacto",
                    recommendation = "Recomendacao",
                    suggestedComment = "Comentario",
                    confidence = 0.9
                )
            ),
            questions = listOf("Pergunta?"),
            positivePoints = listOf("Ponto positivo"),
            recommendation = MergeRecommendation.REQUEST_CHANGES
        )

        val output = renderer.render(mr, report)
        assertTrue(output.contains("# 📋 MR Analysis"))
        assertTrue(output.contains("## 🧾 Summary"))
        assertTrue(output.contains("## 🔎 Findings"))
        assertTrue(output.contains("## 🚦 Merge Recommendation"))
    }
}
