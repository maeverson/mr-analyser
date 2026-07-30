package com.mranalyser.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.mranalyser.application.port.MergeRequestProvider
import com.mranalyser.application.usecase.AnalyseMergeRequestUseCase
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.infrastructure.config.AnalyzerFactory
import com.mranalyser.infrastructure.config.ConfigLoader
import com.mranalyser.infrastructure.gitlab.GitLabApiException
import com.mranalyser.infrastructure.gitlab.GitLabClient
import com.mranalyser.infrastructure.gitlab.GitLabMergeRequestProvider
import com.mranalyser.infrastructure.gitlab.GitLabUrlParser
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
    private val showLowConfidence by option(
        "--show-low-confidence",
        help = "Exibe findings abaixo do limite de confianca (nao participam da recomendacao)"
    ).flag(default = false)
    private val noContext by option(
        "--no-context",
        help = "Desativa a busca de contexto relacionado no repositorio local"
    ).flag(default = false)
    private val fastMode by option(
        "--fast",
        help = "Executa apenas o review local, sem entendimento, validacao, cross-file e parecer"
    ).flag(default = false)

    override fun run() = runBlocking {
        var gitLabClient: GitLabClient? = null

        try {
            require(url != null || mr != null) { "Informe --url ou --mr" }

            val loaded = ConfigLoader().load(
                verbose = verbose,
                showLowConfidence = showLowConfidence,
                providerOverride = provider,
                modelOverride = model
            )

            val config = loaded.copy(
                context = loaded.context.copy(enabled = loaded.context.enabled && !noContext),
                review = if (fastMode) {
                    loaded.review.copy(
                        understandingEnabled = false,
                        validationEnabled = false,
                        crossFileEnabled = false,
                        finalAssessmentEnabled = false
                    )
                } else {
                    loaded.review
                }
            )

            val (resolvedProject, resolvedMr, resolvedHost) = resolveTarget(project, mr, url)

            gitLabClient = GitLabClient(
                gitlabUrl = resolvedHost ?: config.gitlabUrl,
                token = config.gitlabToken
            )

            val llmProvider = AnalyzerFactory.createLlmProvider(config)
            if (llmProvider.name == "noop") {
                echo(
                    "Aviso: nenhum provedor de LLM configurado. A analise sera limitada a regras " +
                        "estaticas e sinais arquiteturais.",
                    err = true
                )
            }

            val analyzer = AnalyzerFactory.createAnalyzer(
                config = config,
                llmProvider = llmProvider,
                repositoryContextProvider = LocalRepositoryContextProvider()
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
            val reportPath = ReportFileWriter().writeReport(
                mrIdentifier = "mr-$resolvedMr",
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
        val compiled = patterns.map(::globToRegex)
        return changes.filterNot { change ->
            compiled.any { it.matches(change.newPath) || it.matches(change.oldPath) }
        }
    }

    /**
     * Conversão de glob para regex.
     *
     * A versão anterior usava `Regex.escape(glob)` e depois tentava substituir a estrela no
     * resultado. `Regex.escape` devolve `\Q...\E`, então as substituições nunca casavam e
     * **nenhum wildcard funcionava**: um `ignoredPaths` com `*.lock` ou `generated` seguido de
     * estrela dupla não filtrava nada. Aqui o escape é feito caractere a caractere, preservando
     * estrela simples, estrela dupla e interrogação.
     */
    private fun globToRegex(glob: String): Regex {
        val pattern = StringBuilder("^")
        var index = 0

        while (index < glob.length) {
            when (val char = glob[index]) {
                '*' -> if (index + 1 < glob.length && glob[index + 1] == '*') {
                    pattern.append(".*")
                    index++
                } else {
                    pattern.append("[^/]*")
                }

                '?' -> pattern.append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                    pattern.append('\\').append(char)

                else -> pattern.append(char)
            }
            index++
        }

        // "generated/**" deve casar também com o próprio diretório e com "generated/a/b.kt".
        return Regex(pattern.append('$').toString().replace("/.*$", "(/.*)?$"))
    }

    private fun friendlyGitLabError(exception: GitLabApiException): String {
        return when (exception.statusCode) {
            401 -> "Erro ao consultar GitLab: autenticação negada. Verifique GITLAB_TOKEN."
            403 -> "Erro ao consultar GitLab: acesso negado. Verifique permissões do token para este projeto."
            404 -> "Erro ao consultar GitLab: projeto ou MR não encontrado. Em grupos privados, isso também " +
                "pode indicar falta de SSO ou token sem acesso ao grupo/projeto. Verifique --url/--project, " +
                "permissões do token e se o projeto está visível para sua conta."
            else -> "Erro ao consultar GitLab: ${exception.message}"
        }
    }
}
