package com.mranalyser.infrastructure.gitlab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitLabUserDto(
    val id: Long? = null,
    val name: String,
    val username: String? = null
)

@Serializable
data class GitLabMergeRequestDto(
    val id: Long,
    val iid: Long,
    val title: String,
    val description: String? = null,
    val author: GitLabUserDto,
    @SerialName("source_branch") val sourceBranch: String,
    @SerialName("target_branch") val targetBranch: String,
    val labels: List<String> = emptyList(),
    val state: String? = null,
    val reviewers: List<GitLabUserDto> = emptyList(),
    @SerialName("web_url") val webUrl: String? = null
)

@Serializable
data class GitLabMergeRequestChangesDto(
    val changes: List<GitLabChangeDto>
)

/**
 * ATENÇÃO: os nomes em snake_case são obrigatórios. Sem `@SerialName`, e com
 * `ignoreUnknownKeys = true`, estes campos ficavam **sempre `false`** — o GitLab envia
 * `new_file`/`deleted_file`/`renamed_file`. O efeito era arquivos removidos nunca serem
 * identificados e o filtro `filter { !it.deleted }` nunca ter efeito.
 */
@Serializable
data class GitLabChangeDto(
    @SerialName("old_path") val oldPath: String,
    @SerialName("new_path") val newPath: String,
    val diff: String = "",
    @SerialName("new_file") val newFile: Boolean = false,
    @SerialName("deleted_file") val deletedFile: Boolean = false,
    @SerialName("renamed_file") val renamedFile: Boolean = false,
    @SerialName("generated_file") val generatedFile: Boolean = false
)

@Serializable
data class GitLabCommitDto(
    val id: String,
    val title: String,
    /** Corpo completo do commit. Costuma conter a intenção que o título omite. */
    val message: String? = null,
    @SerialName("author_name") val authorName: String
)

@Serializable
data class GitLabDiscussionDto(
    val id: String,
    val notes: List<GitLabNoteDto> = emptyList()
)

@Serializable
data class GitLabNoteDto(
    val id: Long,
    val body: String,
    val author: GitLabUserDto,
    /** Notas de sistema ("changed target branch") são ruído e são filtradas. */
    val system: Boolean = false,
    val resolvable: Boolean = false,
    val resolved: Boolean = false,
    val position: GitLabPositionDto? = null
)

@Serializable
data class GitLabPositionDto(
    @SerialName("old_path") val oldPath: String? = null,
    @SerialName("new_path") val newPath: String? = null,
    @SerialName("old_line") val oldLine: Int? = null,
    @SerialName("new_line") val newLine: Int? = null
)

@Serializable
data class GitLabApprovalsDto(
    @SerialName("approvals_required") val approvalsRequired: Int? = null
)
