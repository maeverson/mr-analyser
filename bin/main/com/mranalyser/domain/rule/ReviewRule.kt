package com.mranalyser.domain.rule

import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewFinding

interface ReviewRule {
    fun supports(change: FileChange): Boolean

    fun analyse(
        mergeRequest: MergeRequest,
        change: FileChange
    ): List<ReviewFinding>
}
