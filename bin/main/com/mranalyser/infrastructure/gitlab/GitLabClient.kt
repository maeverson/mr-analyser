package com.mranalyser.infrastructure.gitlab

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class GitLabClient(
    private val gitlabUrl: String,
    private val token: String?
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
            )
        }

        defaultRequest {
            headers.append("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                headers.append("PRIVATE-TOKEN", token)
            }
        }
    }

    suspend fun getMergeRequest(project: String, iid: Long): GitLabMergeRequestDto {
        return getJson(apiUrl("/projects/${encodeProject(project)}/merge_requests/$iid"))
    }

    suspend fun getMergeRequestChanges(project: String, iid: Long): GitLabMergeRequestChangesDto {
        return getJson(apiUrl("/projects/${encodeProject(project)}/merge_requests/$iid/changes"))
    }

    suspend fun getMergeRequestCommits(project: String, iid: Long): List<GitLabCommitDto> {
        return getJson(apiUrl("/projects/${encodeProject(project)}/merge_requests/$iid/commits"))
    }

    suspend fun getMergeRequestDiscussions(project: String, iid: Long): List<GitLabDiscussionDto> {
        return getJson(apiUrl("/projects/${encodeProject(project)}/merge_requests/$iid/discussions"))
    }

    suspend fun getMergeRequestApprovals(project: String, iid: Long): GitLabApprovalsDto? {
        return try {
            getJson(apiUrl("/projects/${encodeProject(project)}/merge_requests/$iid/approvals"))
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        client.close()
    }

    private fun apiUrl(path: String): String {
        val base = gitlabUrl.trimEnd('/')
        return URLBuilder().takeFrom("$base/api/v4$path").buildString()
    }

    private fun encodeProject(project: String): String {
        return project.replace("/", "%2F")
    }

    private suspend inline fun <reified T> getJson(url: String): T {
        val response: HttpResponse = client.get(url)

        if (!response.status.isSuccess()) {
            throw GitLabApiException(
                statusCode = response.status.value,
                responseBody = response.bodyAsText()
            )
        }

        return response.body<T>()
    }
}
