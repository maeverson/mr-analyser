package com.mranalyser

import com.mranalyser.domain.model.Author
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.model.Severity
import com.mranalyser.infrastructure.render.ConsoleReportRenderer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsoleReportRendererTest {
    @Test
    fun `should render key sections`() {
        val renderer = ConsoleReportRenderer(showLowConfidence = false, minimumConfidence = 0.6)
        val mr = MergeRequest(
            id = 1,
            iid = 123,
            title = "Title",
            description = null,
            author = Author(name = "Gabriel"),
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
                    category = ReviewCategory.BUG,
                    file = "A.kt",
                    line = 1,
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

        assertTrue(output.contains("📋 MR REVIEW REPORT"))
        assertTrue(output.contains("🧾 Summary"))
        assertTrue(output.contains("❓ Questions for author"))
        assertTrue(output.contains("🚦 Merge recommendation"))
    }
}
