package com.mranalyser

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.mranalyser.infrastructure.gitlab.GitLabClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File

class GitLabClientTest {
    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build()
    }

    @Test
    fun `should fetch merge request from gitlab api`() = runBlocking {
        wireMock.stubFor(
            get(urlEqualTo("/api/v4/projects/group%2Fproject/merge_requests/123"))
                .withHeader("PRIVATE-TOKEN", equalTo("secret-token"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(resource("gitlab/mr.json"))
                )
        )

        val client = GitLabClient("http://localhost:${wireMock.port}", "secret-token")
        val mr = client.getMergeRequest("group/project", 123)

        assertEquals(123, mr.iid)
        assertEquals("Implement Redis fallback", mr.title)
    }

    private fun resource(path: String): String {
        return File("src/test/resources/$path").readText()
    }
}
