package com.mranalyser.application.port

import com.mranalyser.domain.model.MergeRequest

interface MergeRequestProvider {
    suspend fun fetchMergeRequest(project: String, mrIid: Long): MergeRequest
}
