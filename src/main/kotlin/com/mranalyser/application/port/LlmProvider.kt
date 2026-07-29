package com.mranalyser.application.port

import com.mranalyser.domain.model.LlmReviewResult
import com.mranalyser.domain.model.ReviewContext

interface LlmProvider {
    suspend fun analyse(context: ReviewContext): LlmReviewResult
}
