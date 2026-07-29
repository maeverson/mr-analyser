package com.mranalyser.application.port

data class RepositoryCoordinates(
    val host: String,
    val projectPath: String
)

data class RelatedFileContext(
    val referencePath: String,
    val relatedPath: String,
    val content: String
)

interface RepositoryContextProvider {
    fun detectRepositoryCoordinates(): RepositoryCoordinates?

    fun findRelatedContext(changedFiles: List<String>): List<RelatedFileContext>
}
