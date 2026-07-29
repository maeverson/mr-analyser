package com.mranalyser.infrastructure.llm

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.domain.model.LlmReviewResult
import com.mranalyser.domain.model.ReviewContext

class NoOpLlmProvider : LlmProvider {
    override suspend fun analyse(context: ReviewContext): LlmReviewResult {
        return LlmReviewResult(
            summary = "Analise de IA desabilitada ou nao configurada.",
            findings = emptyList(),
            questions = emptyList(),
            positivePoints = emptyList()
        )
    }
}
