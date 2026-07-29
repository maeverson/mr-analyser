package com.mranalyser.infrastructure.gitlab

data class ParsedMergeRequestUrl(
    val host: String,
    val projectPath: String,
    val iid: Long
)

object GitLabUrlParser {
    fun parse(url: String): ParsedMergeRequestUrl? {
        val regex = Regex("https?://([^/]+)/(.+)/-/merge_requests/(\\d+)")
        val match = regex.matchEntire(url.trim()) ?: return null
        val host = "https://${match.groupValues[1]}"
        val project = match.groupValues[2]
        val iid = match.groupValues[3].toLongOrNull() ?: return null
        return ParsedMergeRequestUrl(host = host, projectPath = project, iid = iid)
    }
}
