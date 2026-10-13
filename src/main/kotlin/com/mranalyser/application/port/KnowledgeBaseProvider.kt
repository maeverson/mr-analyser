package com.mranalyser.application.port

/** Consulta textual à base de conhecimento. `squad` restringe a busca quando conhecido. */
data class KnowledgeQuery(
    val text: String,
    val squad: String? = null,
    val limit: Int = 20
)

/** Trecho de documento devolvido pela base, sem interpretação. */
data class KnowledgeDocument(
    val content: String,
    val source: String,
    val repo: String? = null,
    val squad: String? = null,
    val docType: String? = null
)

data class KnowledgeSearchResult(
    val documents: List<KnowledgeDocument>,
    val failure: String? = null
) {
    companion object {
        fun failed(reason: String): KnowledgeSearchResult = KnowledgeSearchResult(emptyList(), reason)
    }
}

/**
 * Porta para a base de conhecimento de engenharia (ADRs, contratos, processos).
 *
 * Mesmo contrato do [LlmProvider]: **nunca lançar exceção**. Base indisponível ou sem login
 * vira [KnowledgeSearchResult.failed] e a análise segue sem esse contexto.
 */
interface KnowledgeBaseProvider {
    suspend fun search(query: KnowledgeQuery): KnowledgeSearchResult
}
