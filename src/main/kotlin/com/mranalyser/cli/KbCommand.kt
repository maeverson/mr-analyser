package com.mranalyser.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.mranalyser.application.port.KnowledgeQuery
import com.mranalyser.infrastructure.config.ConfigLoader
import com.mranalyser.infrastructure.knowledge.KbCredentialStore
import com.mranalyser.infrastructure.knowledge.KbLoginFlow
import com.mranalyser.infrastructure.knowledge.KbTokenSource
import com.mranalyser.infrastructure.knowledge.McpKnowledgeBaseProvider
import kotlinx.coroutines.runBlocking

class KbCommand : CliktCommand(name = "kb", help = "Acesso à base de conhecimento de engenharia") {
    override fun run() = Unit
}

class KbLoginCommand : CliktCommand(name = "login", help = "Autentica na base pelo SSO, no navegador") {
    override fun run() = runBlocking {
        val url = kbUrl()
        val store = KbCredentialStore()
        runCatching { KbLoginFlow(url, store = store, announce = { echo(it) }).login() }
            .onSuccess { echo("Login concluído. Credenciais em ${store.location}") }
            .onFailure { echo("Login falhou: ${it.message}", err = true) }
        Unit
    }
}

class KbLogoutCommand : CliktCommand(name = "logout", help = "Remove as credenciais locais da base") {
    override fun run() {
        val store = KbCredentialStore()
        echo(if (store.delete()) "Credenciais removidas de ${store.location}" else "Nenhuma credencial salva.")
    }
}

class KbSearchCommand : CliktCommand(name = "search", help = "Busca na base, como a análise faz (diagnóstico)") {
    private val text by argument(help = "termos da busca")
    private val squad by option("--squad", help = "filtra por squad")

    override fun run() = runBlocking {
        val url = kbUrl()
        val result = McpKnowledgeBaseProvider(url, KbTokenSource(url)).search(KnowledgeQuery(text, squad, limit = 10))
        result.failure?.let {
            echo("Falha: $it", err = true)
            return@runBlocking
        }
        if (result.documents.isEmpty()) {
            echo("Nenhum documento encontrado.")
        }
        result.documents.forEach { document ->
            echo("- [${document.repo ?: "transversal"}/${document.docType ?: "?"}] ${document.source}")
        }
    }
}

private fun kbUrl(): String =
    ConfigLoader().load(verbose = false, showLowConfidence = false, providerOverride = null, modelOverride = null)
        .knowledgeBase.url

fun KbCommand.withKbSubcommands(): KbCommand = apply {
    subcommands(KbLoginCommand(), KbLogoutCommand(), KbSearchCommand())
}
