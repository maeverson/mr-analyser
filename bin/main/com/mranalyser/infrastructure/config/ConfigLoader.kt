package com.mranalyser.infrastructure.config

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Properties

class ConfigLoader {
    fun load(
        verbose: Boolean,
        showLowConfidence: Boolean,
        providerOverride: String?,
        modelOverride: String?
    ): AppConfig {
        val fileConfig = loadFromFile(File(".mranalyser.yml"))
        val properties = loadProperties(File(".mranalyser.properties"))

        val source = ConfigSource(properties, fileConfig)

        val gitlabUrl = (source.text("GITLAB_URL", "gitlabUrl") ?: "https://gitlab.com").trimEnd('/')

        return AppConfig(
            gitlabUrl = gitlabUrl,
            gitlabToken = source.text("GITLAB_TOKEN"),
            llm = LlmConfig(
                provider = providerOverride
                    ?: source.text("MR_ANALYSER_LLM_PROVIDER", "llm", "provider")
                    ?: "openai",
                model = modelOverride
                    ?: source.text("MR_ANALYSER_LLM_MODEL", "llm", "model")
                    ?: "gpt-4o-mini",
                apiKey = source.text("MR_ANALYSER_LLM_API_KEY"),
                url = source.text("MR_ANALYSER_LLM_URL", "llm", "url"),
                timeoutSeconds = source.number("MR_ANALYSER_LLM_TIMEOUT_SECONDS", "llm", "timeoutSeconds")
                    ?.toLong() ?: 180L,
                maxRetries = source.number("MR_ANALYSER_LLM_MAX_RETRIES", "llm", "maxRetries")
                    ?.toInt()?.coerceIn(0, 5) ?: 2,
                jsonMode = source.flag("MR_ANALYSER_LLM_JSON_MODE", "llm", "jsonMode") ?: false,
                maxOutputTokensReview = source.number("MR_ANALYSER_LLM_MAX_TOKENS", "llm", "maxOutputTokens")
                    ?.toInt() ?: 6_000,
                maxOutputTokensAssessment = source.number("MR_ANALYSER_LLM_MAX_TOKENS_ASSESSMENT", "llm", "maxOutputTokensAssessment")
                    ?.toInt() ?: 2_000
            ),
            review = ReviewConfig(
                ignoredPaths = source.list("review", "ignoredPaths"),
                ignoredCategories = source.list("review", "ignoredCategories").toSet(),
                minimumConfidence = source.number("MR_ANALYSER_MIN_CONFIDENCE", "review", "minimumConfidence")
                    ?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.60,
                showLowConfidence = showLowConfidence,
                maxFindings = source.number("MR_ANALYSER_MAX_FINDINGS", "review", "maxFindings")
                    ?.toInt()?.coerceAtLeast(1) ?: 25,
                understandingEnabled = source.flag("MR_ANALYSER_STAGE_UNDERSTANDING", "review", "understandingEnabled") ?: true,
                validationEnabled = source.flag("MR_ANALYSER_STAGE_VALIDATION", "review", "validationEnabled") ?: true,
                crossFileEnabled = source.flag("MR_ANALYSER_STAGE_CROSS_FILE", "review", "crossFileEnabled") ?: true,
                finalAssessmentEnabled = source.flag("MR_ANALYSER_STAGE_ASSESSMENT", "review", "finalAssessmentEnabled") ?: true
            ),
            limits = LimitsConfig(
                maxDiffLines = source.number("MR_ANALYSER_MAX_DIFF_LINES", "limits", "maxDiffLines")?.toInt() ?: 2_500,
                maxFileLines = source.number("MR_ANALYSER_MAX_FILE_LINES", "limits", "maxFileLines")?.toInt() ?: 900
            ),
            context = ContextConfig(
                enabled = source.flag("MR_ANALYSER_CONTEXT_ENABLED", "context", "enabled") ?: true,
                maxFilesPerChange = source.number("MR_ANALYSER_CONTEXT_MAX_FILES_PER_CHANGE", "context", "maxFilesPerChange")
                    ?.toInt() ?: 4,
                maxTotalFiles = source.number("MR_ANALYSER_CONTEXT_MAX_TOTAL_FILES", "context", "maxTotalFiles")
                    ?.toInt() ?: 24,
                maxCharsPerFile = source.number("MR_ANALYSER_CONTEXT_MAX_CHARS", "context", "maxCharsPerFile")
                    ?.toInt() ?: 4_000,
                requireRepositoryMatch = source.flag("MR_ANALYSER_CONTEXT_REQUIRE_REPO_MATCH", "context", "requireRepositoryMatch")
                    ?: true
            ),
            maxConcurrency = (source.number("MR_ANALYSER_MAX_CONCURRENCY", "maxConcurrency")?.toInt() ?: 4)
                .coerceIn(1, 16),
            verbose = verbose
        )
    }

    /**
     * Precedência: `.mranalyser.properties` → variável de ambiente → `.mranalyser.yml` → default.
     * Extraída para uma classe própria porque a versão anterior repetia essa cadeia em cada campo.
     */
    private class ConfigSource(
        private val properties: Properties,
        private val yaml: Map<String, Any>
    ) {
        fun text(propertyKey: String, vararg yamlPath: String): String? =
            properties.getProperty(propertyKey)?.trim()?.takeIf { it.isNotBlank() }
                ?: System.getenv(propertyKey)?.trim()?.takeIf { it.isNotBlank() }
                ?: nested(*yamlPath)?.toString()?.trim()?.takeIf { it.isNotBlank() }

        fun number(propertyKey: String, vararg yamlPath: String): Number? {
            text(propertyKey)?.let { raw ->
                raw.toDoubleOrNull()?.let { return it }
            }
            return nested(*yamlPath) as? Number
                ?: nested(*yamlPath)?.toString()?.toDoubleOrNull()
        }

        fun flag(propertyKey: String, vararg yamlPath: String): Boolean? {
            text(propertyKey)?.let { raw ->
                return when (raw.lowercase()) {
                    "true", "1", "yes", "on" -> true
                    "false", "0", "no", "off" -> false
                    else -> null
                }
            }
            return nested(*yamlPath) as? Boolean
        }

        fun list(vararg yamlPath: String): List<String> =
            (nested(*yamlPath) as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        private fun nested(vararg keys: String): Any? {
            if (keys.isEmpty()) {
                return null
            }
            var node: Any? = yaml
            for (key in keys) {
                node = (node as? Map<String, Any>)?.get(key) ?: return null
            }
            return node
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromFile(file: File): Map<String, Any> {
        if (!file.exists()) {
            return emptyMap()
        }
        return runCatching { Yaml().load<Any>(file.readText()) as? Map<String, Any> }
            .getOrNull()
            ?: emptyMap()
    }

    private fun loadProperties(file: File): Properties {
        val properties = Properties()
        if (!file.exists()) {
            return properties
        }
        runCatching { file.inputStream().use { properties.load(it) } }
        return properties
    }
}
