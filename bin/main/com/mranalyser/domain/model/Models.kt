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
)

data class DiscussionNote(
    val id: String,
    val author: Author,
    val body: String
)

data class FileChange(
    val oldPath: String,
    val newPath: String,
    val added: Boolean,
    val deleted: Boolean,
    val renamed: Boolean,
    val diff: String,
    val linesAdded: Int = 0,
    val linesRemoved: Int = 0
)

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
    val discussions: List<Discussion>
)

enum class Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO
}

enum class ReviewCategory {
    BUG,
    SECURITY,
    ARCHITECTURE,
    DESIGN,
    PERFORMANCE,
    CONCURRENCY,
    RELIABILITY,
    OBSERVABILITY,
    TESTABILITY,
    MAINTAINABILITY,
    CODE_STYLE,
    COMPATIBILITY,
    DOCUMENTATION
}

data class ReviewFinding(
    val severity: Severity,
    val category: ReviewCategory,
    val file: String?,
    val line: Int?,
    val title: String,
    val description: String,
    val impact: String?,
    val recommendation: String?,
    val suggestedComment: String?,
    val confidence: Double
)

enum class MergeRecommendation {
    APPROVE,
    APPROVE_WITH_SUGGESTIONS,
    REQUEST_CHANGES
}

data class ReviewReport(
    val summary: String,
    val findings: List<ReviewFinding>,
    val questions: List<String>,
    val positivePoints: List<String>,
    val recommendation: MergeRecommendation
)

data class RepositoryFileContext(
    val referencePath: String,
    val relatedPath: String,
    val content: String
)

data class ReviewContext(
    val title: String,
    val description: String?,
    val sourceBranch: String,
    val targetBranch: String,
    val changedFiles: List<String>,
    val diff: String,
    val commits: List<Commit>,
    val existingDiscussions: List<String>,
    val repositoryContext: List<RepositoryFileContext> = emptyList()
)

data class LlmReviewResult(
    val summary: String,
    val findings: List<ReviewFinding>,
    val questions: List<String>,
    val positivePoints: List<String>,
    val suggestedRecommendation: MergeRecommendation? = null
)
