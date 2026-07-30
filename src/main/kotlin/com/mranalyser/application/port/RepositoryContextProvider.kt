package com.mranalyser.application.port

data class RepositoryCoordinates(
    val host: String,
    val projectPath: String
)

/**
 * Por que este arquivo foi trazido como contexto. Exposto ao LLM para que ele saiba o que está
 * lendo — "interface implementada pelo arquivo alterado" e "arquivo com nome parecido" têm
 * pesos muito diferentes na hora de concluir algo.
 */
enum class RelatedContextKind {
    INTERFACE_OR_PARENT,
    IMPLEMENTATION,
    CALLER,
    DEPENDENCY,
    TEST,
    CONFIGURATION,
    MIGRATION,
    CONTRACT,
    SIBLING;

    val label: String
        get() = when (this) {
            INTERFACE_OR_PARENT -> "interface/classe base do arquivo alterado"
            IMPLEMENTATION -> "implementação relacionada"
            CALLER -> "chama o arquivo alterado"
            DEPENDENCY -> "dependência direta do arquivo alterado"
            TEST -> "teste correspondente"
            CONFIGURATION -> "configuração relacionada"
            MIGRATION -> "migration relacionada"
            CONTRACT -> "contrato de API/evento relacionado"
            SIBLING -> "arquivo vizinho do mesmo domínio"
        }
}

data class RelatedFileContext(
    val referencePath: String,
    val relatedPath: String,
    val content: String,
    val kind: RelatedContextKind = RelatedContextKind.SIBLING,
    val reason: String = "",
    val score: Double = 0.0,
    /** `true` quando o conteúdo é um recorte, não o arquivo completo. */
    val excerpt: Boolean = true
)

/** Consulta por arquivo alterado, alimentada pelo `SymbolExtractor` a partir do diff. */
data class ChangedFileQuery(
    val path: String,
    val declaredSymbols: List<String> = emptyList(),
    val superTypes: List<String> = emptyList(),
    val imports: List<String> = emptyList(),
    val referencedTypes: List<String> = emptyList()
)

/** Limites de orçamento de contexto (item 34). */
data class ContextBudget(
    val maxFilesPerChange: Int = 4,
    val maxTotalFiles: Int = 24,
    val maxCharsPerFile: Int = 4_000
)

data class ContextRetrievalRequest(
    val queries: List<ChangedFileQuery>,
    val budget: ContextBudget = ContextBudget()
)

interface RepositoryContextProvider {
    fun detectRepositoryCoordinates(): RepositoryCoordinates?

    fun findRelatedContext(request: ContextRetrievalRequest): List<RelatedFileContext>

    /** Atalho para chamadas simples (sem símbolos extraídos). */
    fun findRelatedContext(changedFiles: List<String>): List<RelatedFileContext> =
        findRelatedContext(ContextRetrievalRequest(changedFiles.map { ChangedFileQuery(it) }))
}
