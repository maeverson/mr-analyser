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
    val reviewers: List<GitLabUserDto> = emptyList()
)

@Serializable
data class GitLabMergeRequestChangesDto(
    val changes: List<GitLabChangeDto>
)

@Serializable
data class GitLabChangeDto(
    @SerialName("old_path") val oldPath: String,
    @SerialName("new_path") val newPath: String,
    val diff: String,
    val newFile: Boolean = false,
    val deletedFile: Boolean = false,
    val renamedFile: Boolean = false
)

@Serializable
data class GitLabCommitDto(
    val id: String,
    val title: String,
    @SerialName("author_name") val authorName: String
)

@Serializable
data class GitLabDiscussionDto(
    val id: String,
    val notes: List<GitLabNoteDto>
)

@Serializable
data class GitLabNoteDto(
    val id: Long,
    val body: String,
    val author: GitLabUserDto
)

@Serializable
data class GitLabApprovalsDto(
    @SerialName("approvals_required") val approvalsRequired: Int? = null
)
