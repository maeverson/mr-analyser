package com.mranalyser.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.mranalyser.application.usecase.AnalyseMergeRequestUseCase
import com.mranalyser.application.service.FindingDeduplicator
import com.mranalyser.application.service.MergeRecommendationCalculator
import com.mranalyser.application.service.MergeRequestAnalyzer
import com.mranalyser.application.service.ReviewChunker
import com.mranalyser.application.port.MergeRequestProvider
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.rule.DebugCodeRule
import com.mranalyser.domain.rule.LargeChangeRule
import com.mranalyser.domain.rule.SecretsRule
import com.mranalyser.domain.rule.TestChangeRule
import com.mranalyser.domain.rule.TodoRule
import com.mranalyser.infrastructure.config.ConfigLoader
import com.mranalyser.infrastructure.gitlab.GitLabClient
import com.mranalyser.infrastructure.gitlab.GitLabApiException
import com.mranalyser.infrastructure.gitlab.GitLabMergeRequestProvider
import com.mranalyser.infrastructure.gitlab.GitLabUrlParser
import com.mranalyser.infrastructure.llm.AnthropicLlmProvider
import com.mranalyser.infrastructure.llm.GeminiLlmProvider
import com.mranalyser.infrastructure.llm.NoOpLlmProvider
import com.mranalyser.infrastructure.llm.OllamaLlmProvider
import com.mranalyser.infrastructure.llm.OpenAiLlmProvider
import com.mranalyser.infrastructure.render.ConsoleReportRenderer
import com.mranalyser.infrastructure.render.GitLabCommentRenderer
import com.mranalyser.infrastructure.render.JsonReportRenderer
import com.mranalyser.infrastructure.render.MarkdownReportRenderer
import com.mranalyser.infrastructure.render.ReportFileWriter
import com.mranalyser.infrastructure.render.ReportRenderer
import com.mranalyser.infrastructure.repository.LocalRepositoryContextProvider
import kotlinx.coroutines.runBlocking

class AnalyseCommand : CliktCommand(name = "analyse") {
    private val project by option("--project", help = "Projeto no formato group/project")
    private val mr by option("--mr", help = "IID do merge request")
    private val url by option("--url", help = "URL completa do merge request")
    private val provider by option("--provider", help = "Provedor de LLM")
    private val model by option("--model", help = "Modelo de LLM")
    private val output by option("--output", help = "Formato de saida: console|markdown|json|gitlab-comments")
    private val verbose by option("--verbose", help = "Ativa logs detalhados").flag(default = false)
    private val showLowConfidence by option("--show-low-confidence", help = "Exibe findings com baixa confianca").flag(default = false)

    override fun run() = runBlocking {
        var gitLabClient: GitLabClient? = null

        try {
            require(url != null || mr != null) { "Informe --url ou --mr" }

            val config = ConfigLoader().load(
                verbose = verbose,
                showLowConfidence = showLowConfidence,
                providerOverride = provider,
                modelOverride = model
            )

            val (resolvedProject, resolvedMr, resolvedHost) = resolveTarget(project, mr, url)

            gitLabClient = GitLabClient(
                gitlabUrl = resolvedHost ?: config.gitlabUrl,
                token = config.gitlabToken
            )

            val llmProvider = when (config.llm.provider.lowercase()) {
                "openai" -> {
                    val key = config.llm.apiKey
                    if (key.isNullOrBlank()) {
                        NoOpLlmProvider()
                    } else {
                        OpenAiLlmProvider(
                            apiKey = key,
                            model = config.llm.model,
                            baseUrl = config.llm.url ?: "https://api.openai.com/v1"
                        )
                    }
                }
                "ollama" -> OllamaLlmProvider(
                    baseUrl = config.llm.url ?: "http://localhost:11434",
                    model = config.llm.model,
                    apiKey = config.llm.apiKey
                )
                "anthropic" -> {
                    val key = config.llm.apiKey
                    if (key.isNullOrBlank()) {
                        NoOpLlmProvider()
                    } else {
                        AnthropicLlmProvider(
                            apiKey = key,
                            model = config.llm.model,
                            baseUrl = config.llm.url ?: "https://api.anthropic.com"
                        )
                    }
                }
                "gemini" -> {
                    val key = config.llm.apiKey
                    if (key.isNullOrBlank()) {
                        NoOpLlmProvider()
                    } else {
                        GeminiLlmProvider(
                            apiKey = key,
                            model = config.llm.model,
                            baseUrl = config.llm.url ?: "https://generativelanguage.googleapis.com"
                        )
                    }
                }
                else -> NoOpLlmProvider()
            }

            val repositoryContextProvider = LocalRepositoryContextProvider()

            val analyzer = MergeRequestAnalyzer(
                rules = listOf(
                    SecretsRule(),
                    DebugCodeRule(),
                    TodoRule(),
                    LargeChangeRule(maxLinesPerFile = config.limits.maxFileLines),
                    TestChangeRule()
                ),
                llmProvider = llmProvider,
                reviewChunker = ReviewChunker(
                    maxDiffLines = config.limits.maxDiffLines,
                    maxFileLines = config.limits.maxFileLines
                ),
                deduplicator = FindingDeduplicator(),
                recommendationCalculator = MergeRecommendationCalculator(),
                minimumConfidence = config.review.minimumConfidence,
                ignoredCategories = config.review.ignoredCategories,
                showLowConfidence = config.review.showLowConfidence,
                repositoryContextProvider = repositoryContextProvider,
                maxConcurrency = config.maxConcurrency
            )

            val baseProvider = GitLabMergeRequestProvider(gitLabClient)
            val filteredProvider = object : MergeRequestProvider {
                override suspend fun fetchMergeRequest(project: String, mrIid: Long): MergeRequest {
                    val mergeRequest = baseProvider.fetchMergeRequest(project, mrIid)
                    return mergeRequest.copy(
                        changes = applyIgnoredPaths(mergeRequest.changes, config.review.ignoredPaths)
                    )
                }
            }

            val useCase = AnalyseMergeRequestUseCase(
                mergeRequestProvider = filteredProvider,
                analyzer = analyzer
            )

            val (mrData, report) = useCase.execute(resolvedProject, resolvedMr)

            val renderer: ReportRenderer = when (output?.lowercase()) {
                "markdown", "md" -> MarkdownReportRenderer()
                "json" -> JsonReportRenderer()
                "gitlab-comments", "gitlab-comment" -> GitLabCommentRenderer()
                else -> ConsoleReportRenderer(
                    showLowConfidence = config.review.showLowConfidence,
                    minimumConfidence = config.review.minimumConfidence
                )
            }

            val renderedReport = renderer.render(mrData, report)
            val reportWriter = ReportFileWriter()
            val reportPath = reportWriter.writeReport(
                mrIdentifier = "mr-${resolvedMr}",
                content = renderedReport,
                extension = when (output?.lowercase()) {
                    "json" -> ".json"
                    "markdown", "md" -> ".md"
                    else -> ".txt"
                }
            )

            echo(renderedReport)
            echo()
            echo("Relatório salvo em: $reportPath")
        } catch (exception: GitLabApiException) {
            System.err.println(friendlyGitLabError(exception))
        } catch (exception: IllegalStateException) {
            System.err.println("Erro ao consultar GitLab: ${exception.message}")
        } catch (exception: IllegalArgumentException) {
            System.err.println("Entrada invalida: ${exception.message}")
        } finally {
            gitLabClient?.close()
        }
    }

    private fun resolveTarget(project: String?, mr: String?, url: String?): Triple<String, Long, String?> {
        if (!url.isNullOrBlank()) {
            val parsed = GitLabUrlParser.parse(url)
                ?: throw IllegalArgumentException("Nao foi possivel interpretar --url")
            return Triple(parsed.projectPath, parsed.iid, parsed.host)
        }

        val resolvedMr = mr?.toLongOrNull() ?: throw IllegalArgumentException("--mr deve ser numerico")
        if (!project.isNullOrBlank()) {
            return Triple(project, resolvedMr, null)
        }

        val detected = LocalRepositoryContextProvider().detectRepositoryCoordinates()
            ?: throw IllegalArgumentException("Nao foi possivel detectar projeto automaticamente. Informe --project")
        return Triple(detected.projectPath, resolvedMr, detected.host)
    }

    private fun applyIgnoredPaths(changes: List<FileChange>, patterns: List<String>): List<FileChange> {
        if (patterns.isEmpty()) {
            return changes
        }
        return changes.filterNot { change ->
            patterns.any { glob -> globToRegex(glob).matches(change.newPath) }
        }
    }

    private fun globToRegex(glob: String): Regex {
        val escaped = Regex.escape(glob)
            .replace("\\*\\*", ".*")
            .replace("\\*", "[^/]*")
            .replace("\\?", ".")
        return Regex("^$escaped$")
    }

    private fun friendlyGitLabError(exception: GitLabApiException): String {
        return when (exception.statusCode) {
            401 -> "Erro ao consultar GitLab: autenticação negada. Verifique GITLAB_TOKEN."
            403 -> "Erro ao consultar GitLab: acesso negado. Verifique permissões do token para este projeto."
            404 -> "Erro ao consultar GitLab: projeto ou MR não encontrado. Em grupos privados como ctbz, isso também pode indicar falta de SSO/SSO ou token sem acesso ao grupo/projeto. Verifique --url/--project, permissões do token e se o projeto está visível para sua conta."
            else -> "Erro ao consultar GitLab: ${exception.message}"
        }
    }
}
