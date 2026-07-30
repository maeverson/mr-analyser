package com.mranalyser.domain.model

data class Author(
    val id: Long? = null,
    val name: String,
    val username: String? = null
)

data class Commit(
    val sha: String,
    val message: String,
    val author: Author
)

data class Discussion(
    val id: String,
    val notes: List<DiscussionNote>
) {
    /** Uma discussão é considerada resolvida quando todas as notas resolvíveis estão resolvidas. */
    val resolved: Boolean
        get() {
            val resolvable = notes.filter { it.resolvable }
            return resolvable.isNotEmpty() && resolvable.all { it.resolved }
        }
}

data class DiscussionNote(
    val id: String,
    val author: Author,
    val body: String,
    val system: Boolean = false,
    val resolvable: Boolean = false,
    val resolved: Boolean = false,
    val file: String? = null,
    val line: Int? = null
)

data class FileChange(
    val oldPath: String,
    val newPath: String,
    val added: Boolean,
    val deleted: Boolean,
    val renamed: Boolean,
    val diff: String,
    val linesAdded: Int = 0,
    val linesRemoved: Int = 0,
    val generated: Boolean = false
) {
    val path: String get() = if (deleted) oldPath else newPath
    val totalLines: Int get() = linesAdded + linesRemoved
}

data class MergeRequest(
    val id: Long,
    val iid: Long,
    val title: String,
    val description: String?,
    val author: Author,
    val sourceBranch: String,
    val targetBranch: String,
    val labels: List<String> = emptyList(),
    val status: String? = null,
    val reviewers: List<Author> = emptyList(),
    val approvalsRequired: Int? = null,
    val changes: List<FileChange>,
    val commits: List<Commit>,
    val discussions: List<Discussion>,
    val projectPath: String? = null,
    val webUrl: String? = null
)

/**
 * Agrupamento arquitetural de um arquivo alterado. Usado para montar chunks coesos
 * (evita misturar domínio, persistência e integração no mesmo prompt) e para calibrar
 * quais aspectos o reviewer deve investigar em cada grupo.
 */
enum class ChangeGroup {
    DOMAIN,
    APPLICATION,
    PERSISTENCE,
    INTEGRATION,
    API,
    MESSAGING,
    MIGRATION,
    CONFIGURATION,
    CONTRACT,
    TEST,
    BUILD,
    DOCUMENTATION,
    OTHER;

    val isProductionCode: Boolean
        get() = this !in setOf(TEST, BUILD, DOCUMENTATION)

    val label: String
        get() = when (this) {
            DOMAIN -> "domínio"
            APPLICATION -> "aplicação"
            PERSISTENCE -> "persistência"
            INTEGRATION -> "integração"
            API -> "API"
            MESSAGING -> "mensageria"
            MIGRATION -> "migration"
            CONFIGURATION -> "configuração"
            CONTRACT -> "contrato"
            TEST -> "testes"
            BUILD -> "build"
            DOCUMENTATION -> "documentação"
            OTHER -> "outros"
        }
}
