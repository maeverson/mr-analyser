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
        echo("MR_ANALYSER_MAX_CONCURRENCY=${config.maxConcurrency}")
        echo("minimumConfidence=${config.review.minimumConfidence}")
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
