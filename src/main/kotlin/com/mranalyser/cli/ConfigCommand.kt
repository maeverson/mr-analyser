package com.mranalyser.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.mranalyser.infrastructure.config.ConfigLoader

class ConfigCommand : CliktCommand(name = "config") {
    override fun run() = Unit
}

class ConfigShowCommand : CliktCommand(name = "show") {
    override fun run() {
        val config = ConfigLoader().load(
            verbose = false,
            showLowConfidence = false,
            providerOverride = null,
            modelOverride = null
        )

        echo("GITLAB_URL=${config.gitlabUrl}")
        echo("GITLAB_TOKEN=${mask(config.gitlabToken)}")
        echo("MR_ANALYSER_LLM_PROVIDER=${config.llm.provider}")
        echo("MR_ANALYSER_LLM_MODEL=${config.llm.model}")
        echo("MR_ANALYSER_LLM_API_KEY=${mask(config.llm.apiKey)}")
        echo("MR_ANALYSER_LLM_URL=${config.llm.url ?: "<default>"}")
        echo("MR_ANALYSER_LLM_TIMEOUT_SECONDS=${config.llm.timeoutSeconds}")
        echo("MR_ANALYSER_LLM_MAX_RETRIES=${config.llm.maxRetries}")
        echo("MR_ANALYSER_LLM_JSON_MODE=${config.llm.jsonMode}")
        echo("MR_ANALYSER_LLM_MAX_TOKENS=${config.llm.maxOutputTokensReview}")
        echo("MR_ANALYSER_LLM_NUM_CTX=${config.llm.numCtx ?: "<default do provider>"}")
        echo("MR_ANALYSER_MAX_CONCURRENCY=${config.maxConcurrency}")
        echo()
        echo("review.minimumConfidence=${config.review.minimumConfidence}")
        echo("review.maxFindings=${config.review.maxFindings}")
        echo("review.ignoredCategories=${config.review.ignoredCategories.ifEmpty { "<none>" }}")
        echo("review.ignoredPaths=${config.review.ignoredPaths.ifEmpty { listOf("<none>") }}")
        echo()
        echo("etapas: entendimento=${config.review.understandingEnabled}" +
            " validacao=${config.review.validationEnabled}" +
            " crossFile=${config.review.crossFileEnabled}" +
            " parecer=${config.review.finalAssessmentEnabled}")
        echo()
        echo("context.enabled=${config.context.enabled}")
        echo("context.requireRepositoryMatch=${config.context.requireRepositoryMatch}")
        echo("context.maxFilesPerChange=${config.context.maxFilesPerChange}")
        echo("context.maxTotalFiles=${config.context.maxTotalFiles}")
        echo("context.maxCharsPerFile=${config.context.maxCharsPerFile}")
        echo()
        echo("limits.maxDiffLines=${config.limits.maxDiffLines}")
        echo("limits.maxFileLines=${config.limits.maxFileLines}")
    }

    private fun mask(value: String?): String {
        if (value.isNullOrBlank()) {
            return "<not-set>"
        }
        return "****"
    }
}

fun ConfigCommand.withSubcommands(): ConfigCommand = apply {
    subcommands(ConfigShowCommand())
}
