package com.mranalyser.infrastructure.gitlab

class GitLabApiException(
    val statusCode: Int,
    val responseBody: String
) : IllegalStateException("GitLab API request failed: $statusCode $responseBody")
