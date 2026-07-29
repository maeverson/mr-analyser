package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewReport

interface ReportRenderer {
    fun render(mergeRequest: MergeRequest, report: ReviewReport): String
}
