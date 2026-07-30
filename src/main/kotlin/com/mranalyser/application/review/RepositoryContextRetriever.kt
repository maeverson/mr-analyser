package com.mranalyser.application.review

import com.mranalyser.application.port.ChangedFileQuery
import com.mranalyser.application.port.ContextBudget
import com.mranalyser.application.port.ContextRetrievalRequest
import com.mranalyser.application.port.RelatedFileContext
import com.mranalyser.application.port.RepositoryContextProvider
import org.slf4j.LoggerFactory

/**
 * Orquestra o context retrieval e — o ponto mais importante — **valida a identidade do
 * repositório** antes de usar o resultado.
 *
 * A V1 varria o diretório de trabalho atual, que na prática é o checkout do próprio
 * `mr-analyser`, não o repositório do MR analisado. O resultado era injetar código
 * completamente alheio no prompt como "contexto relacionado": a maior fonte de falso positivo
 * da ferramenta. Aqui, se o `origin` local não corresponder ao projeto do MR, o retrieval é
 * desligado e o relatório informa a limitação — pior contexto é aceitável, contexto errado não.
 */
class RepositoryContextRetriever(
    private val provider: RepositoryContextProvider?,
    private val budget: ContextBudget = ContextBudget(),
    private val requireRepositoryMatch: Boolean = true
) {
    private val logger = LoggerFactory.getLogger(RepositoryContextRetriever::class.java)

    fun retrieve(
        projectPath: String?,
        queries: List<ChangedFileQuery>,
        diagnostics: AnalysisDiagnostics
    ): List<RelatedFileContext> {
        val activeProvider = provider
        if (activeProvider == null) {
            diagnostics.skipStage("contexto do repositório", "provider não configurado")
            return emptyList()
        }
        if (queries.isEmpty()) {
            return emptyList()
        }

        if (requireRepositoryMatch && !matchesLocalRepository(activeProvider, projectPath, diagnostics)) {
            return emptyList()
        }

        val contexts = runCatching {
            activeProvider.findRelatedContext(ContextRetrievalRequest(queries = queries, budget = budget))
        }.getOrElse { throwable ->
            logger.debug("Context retrieval falhou", throwable)
            diagnostics.warn("contexto do repositório indisponível: ${throwable.message}")
            emptyList()
        }

        diagnostics.relatedContextsLoaded = contexts.size
        if (contexts.isEmpty()) {
            diagnostics.warn(
                "nenhum arquivo relacionado encontrado localmente; conclusões sobre ausência de " +
                    "retry/timeout/transação/validação não podem ser afirmadas"
            )
        }
        return contexts
    }

    private fun matchesLocalRepository(
        activeProvider: RepositoryContextProvider,
        projectPath: String?,
        diagnostics: AnalysisDiagnostics
    ): Boolean {
        val local = activeProvider.detectRepositoryCoordinates()
        if (local == null) {
            diagnostics.skipStage(
                "contexto do repositório",
                "diretório atual não é um repositório Git com origin identificável"
            )
            return false
        }
        if (projectPath.isNullOrBlank()) {
            diagnostics.skipStage("contexto do repositório", "projeto do MR não informado")
            return false
        }

        val expected = normalize(projectPath)
        val actual = normalize(local.projectPath)
        if (expected != actual) {
            diagnostics.skipStage(
                "contexto do repositório",
                "diretório atual aponta para '$actual', mas o MR é de '$expected'"
            )
            return false
        }

        return true
    }

    private fun normalize(path: String): String =
        path.trim().trim('/').removeSuffix(".git").lowercase()
}
