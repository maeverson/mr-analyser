package com.mranalyser.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.mranalyser.application.usecase.PublishReviewUseCase
import com.mranalyser.infrastructure.config.ConfigLoader
import com.mranalyser.infrastructure.gitlab.GitLabApiException
import com.mranalyser.infrastructure.gitlab.GitLabClient
import com.mranalyser.infrastructure.gitlab.GitLabMergeRequestPublisher
import com.mranalyser.infrastructure.gitlab.GitLabUrlParser
import com.mranalyser.infrastructure.render.CommentPlanFileStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Publica no MR os comentários escolhidos a partir do plano gravado pela última análise.
 * Separado de `analyse` para que a decisão aconteça depois da leitura do relatório.
 */
class PublishCommand : CliktCommand(
    name = "publish",
    help = "Publica no MR os comentários escolhidos da última análise e, com --approve, aprova"
) {
    private val target by argument(
        name = "URL|IID",
        help = "MR analisado; usa o plano mais recente dele em reports/"
    ).optional()
    private val planPath by option("--plan", help = "Arquivo .comments.json específico")
    private val publishOptions by PublishOptions()

    override fun run() = runBlocking {
        var gitLabClient: GitLabClient? = null
        try {
            val store = CommentPlanFileStore()
            val path = planPath?.let { Paths.get(it) } ?: run {
                val value = target ?: throw IllegalArgumentException("Informe a URL/IID do MR ou --plan")
                val parsed = if (value.all(Char::isDigit)) null else GitLabUrlParser.parse(value)
                val iid = value.toLongOrNull() ?: parsed?.iid
                    ?: throw IllegalArgumentException("Nao foi possivel interpretar '$value'")
                store.findLatest(iid, parsed?.projectPath)
                    ?: throw IllegalArgumentException("Nenhuma análise salva para o MR !$iid. Rode 'analyse' antes.")
            }
            require(Files.exists(path)) { "plano não encontrado: $path" }

            val plan = store.read(path)
            echo("Plano: $path")

            val config = ConfigLoader().load(
                verbose = false,
                showLowConfidence = false,
                providerOverride = null,
                modelOverride = null
            )
            gitLabClient = GitLabClient(gitlabUrl = plan.gitlabUrl, token = config.gitlabToken)

            PublishFlow(
                useCase = PublishReviewUseCase(GitLabMergeRequestPublisher(gitLabClient)),
                echo = { echo(it) },
                readInput = { prompt -> print(prompt); System.out.flush(); readlnOrNull() }
            ).run(plan, publishOptions)
        } catch (exception: GitLabApiException) {
            System.err.println("Erro ao publicar no GitLab: ${exception.statusCode} ${exception.responseBody.take(300)}")
        } catch (exception: IllegalStateException) {
            System.err.println("Erro ao publicar no GitLab: ${exception.message}")
        } catch (exception: IllegalArgumentException) {
            System.err.println("Entrada invalida: ${exception.message}")
        } finally {
            gitLabClient?.close()
        }
    }
}
