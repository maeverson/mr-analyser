package com.mranalyser.infrastructure.config

data class AppConfig(
    val gitlabUrl: String,
    val gitlabToken: String?,
    val llm: LlmConfig,
    val review: ReviewConfig,
    val limits: LimitsConfig,
    val maxConcurrency: Int,
    val verbose: Boolean = false
)

data class LlmConfig(
    val provider: String,
    val model: String,
    val apiKey: String?,
    val url: String?
)

data class ReviewConfig(
    val ignoredPaths: List<String>,
    val ignoredCategories: Set<String>,
    val minimumConfidence: Double,
    val showLowConfidence: Boolean
)

data class LimitsConfig(
    val maxDiffLines: Int,
    val maxFileLines: Int
)
