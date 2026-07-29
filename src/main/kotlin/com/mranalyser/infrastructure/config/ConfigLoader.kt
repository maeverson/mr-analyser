package com.mranalyser.infrastructure.config

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Properties

class ConfigLoader {
    fun load(verbose: Boolean, showLowConfidence: Boolean, providerOverride: String?, modelOverride: String?): AppConfig {
        val fileConfig = loadFromFile(File(".mranalyser.yml"))
        val properties = loadProperties(File(".mranalyser.properties"))

        fun prop(key: String): String? = properties.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }

        val gitlabUrl = (prop("GITLAB_URL")
            ?: System.getenv("GITLAB_URL")
            ?: fileConfig["gitlabUrl"] as? String
            ?: "https://gitlab.com")
            .trimEnd('/')

        val llmProvider = providerOverride
            ?: prop("MR_ANALYSER_LLM_PROVIDER")
            ?: System.getenv("MR_ANALYSER_LLM_PROVIDER")
            ?: nested(fileConfig, "llm", "provider") as? String
            ?: "openai"

        val llmModel = modelOverride
            ?: prop("MR_ANALYSER_LLM_MODEL")
            ?: System.getenv("MR_ANALYSER_LLM_MODEL")
            ?: nested(fileConfig, "llm", "model") as? String
            ?: "gpt-4o-mini"

        val ignoredPaths = (nested(fileConfig, "review", "ignoredPaths") as? List<*>)
            ?.mapNotNull { it?.toString() }
            ?: emptyList()

        val ignoredCategories = (nested(fileConfig, "review", "ignoredCategories") as? List<*>)
            ?.mapNotNull { it?.toString() }
            ?.toSet()
            ?: emptySet()

        val minimumConfidence = ((nested(fileConfig, "review", "minimumConfidence") as? Number)?.toDouble())
            ?: 0.60

        val maxDiffLines = ((nested(fileConfig, "limits", "maxDiffLines") as? Number)?.toInt())
            ?: 5000

        val maxFileLines = ((nested(fileConfig, "limits", "maxFileLines") as? Number)?.toInt())
            ?: 1500

        val maxConcurrency = (prop("MR_ANALYSER_MAX_CONCURRENCY")?.toIntOrNull()
            ?: System.getenv("MR_ANALYSER_MAX_CONCURRENCY")?.toIntOrNull()
            ?: 4).coerceAtLeast(1)

        return AppConfig(
            gitlabUrl = gitlabUrl,
            gitlabToken = prop("GITLAB_TOKEN") ?: System.getenv("GITLAB_TOKEN"),
            llm = LlmConfig(
                provider = llmProvider,
                model = llmModel,
                apiKey = prop("MR_ANALYSER_LLM_API_KEY") ?: System.getenv("MR_ANALYSER_LLM_API_KEY"),
                url = prop("MR_ANALYSER_LLM_URL") ?: System.getenv("MR_ANALYSER_LLM_URL")
            ),
            review = ReviewConfig(
                ignoredPaths = ignoredPaths,
                ignoredCategories = ignoredCategories,
                minimumConfidence = minimumConfidence,
                showLowConfidence = showLowConfidence
            ),
            limits = LimitsConfig(
                maxDiffLines = maxDiffLines,
                maxFileLines = maxFileLines
            ),
            maxConcurrency = maxConcurrency,
            verbose = verbose
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun nested(root: Map<String, Any>, vararg keys: String): Any? {
        var node: Any? = root
        for (key in keys) {
            node = (node as? Map<String, Any>)?.get(key)
        }
        return node
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromFile(file: File): Map<String, Any> {
        if (!file.exists()) {
            return emptyMap()
        }
        val yaml = Yaml()
        val parsed = yaml.load<Any>(file.readText())
        return parsed as? Map<String, Any> ?: emptyMap()
    }

    private fun loadProperties(file: File): Properties {
        val properties = Properties()
        if (!file.exists()) {
            return properties
        }

        file.inputStream().use { input ->
            properties.load(input)
        }
        return properties
    }
}
