package com.mranalyser

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.mranalyser.application.port.MergeRequestPublisher
import com.mranalyser.application.usecase.PublishReviewUseCase
import com.mranalyser.cli.CommentSelection
import com.mranalyser.cli.PublishFlow
import com.mranalyser.cli.PublishOptions
import com.mranalyser.domain.model.CommentPlan
import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.DiffRefs
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.LineAnchor
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewComment
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.ReviewReport
import com.mranalyser.domain.model.Severity
import com.mranalyser.infrastructure.gitlab.GitLabClient
import com.mranalyser.infrastructure.gitlab.GitLabMergeRequestPublisher
import com.mranalyser.infrastructure.render.CommentPlanBuilder
import com.mranalyser.infrastructure.render.CommentPlanFileStore
import com.mranalyser.support.MergeRequestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CommentSelectionTest {
    private val ids = setOf(1, 2, 3, 4, 5)

    @Test
    fun `empty input publishes nothing`() {
        assertEquals(emptySet<Int>(), CommentSelection.parse("", ids))
        assertEquals(emptySet<Int>(), CommentSelection.parse("none", ids))
    }

    @Test
    fun `accepts all, lists and ranges`() {
        assertEquals(ids, CommentSelection.parse("all", ids))
        assertEquals(setOf(1, 3, 4, 5), CommentSelection.parse("1, 3-5", ids))
    }

    @Test
    fun `rejects unknown ids and garbage`() {
        assertThrows<IllegalArgumentException> { CommentSelection.parse("6", ids) }
        assertThrows<IllegalArgumentException> { CommentSelection.parse("um", ids) }
    }
}

class CommentPlanBuilderTest {
    private val diff = """
        @@ -10,3 +10,4 @@
         val a = 1
        +val b = 2
         val c = 3
         val d = 4
    """.trimIndent()

    private val mergeRequest = MergeRequestFixtures.mergeRequest(
        changes = listOf(
            FileChange(
                oldPath = "src/Foo.kt", newPath = "src/Foo.kt", added = false, deleted = false,
                renamed = false, diff = diff
            )
        )
    ).copy(diffRefs = DiffRefs("base", "start", "head1234567890"))

    @Test
    fun `added line is anchored by new line only`() {
        val plan = build(finding(line = 11))
        assertEquals(LineAnchor("src/Foo.kt", "src/Foo.kt", null, 11), plan.comments.first().anchor)
    }

    @Test
    fun `context line is anchored by both lines`() {
        val plan = build(finding(line = 12))
        assertEquals(LineAnchor("src/Foo.kt", "src/Foo.kt", 11, 12), plan.comments.first().anchor)
    }

    @Test
    fun `line outside the diff goes to the general thread with its location`() {
        val comment = build(finding(line = 99)).comments.first()
        assertNull(comment.anchor)
        assertTrue(comment.body.contains("src/Foo.kt:99"))
    }

    @Test
    fun `summary is always offered last and ids start at one`() {
        val plan = build(finding(line = 11))
        assertEquals(listOf(1, 2), plan.comments.map { it.id })
        assertTrue(plan.comments.last().key.startsWith("summary-head12345678"))
    }

    private fun build(vararg findings: ReviewFinding): CommentPlan = CommentPlanBuilder().build(
        mergeRequest,
        ReviewReport(
            summary = "",
            findings = findings.toList(),
            questions = emptyList(),
            positivePoints = emptyList(),
            recommendation = MergeRecommendation.NEEDS_DISCUSSION
        ),
        gitlabUrl = "https://gitlab.example.com"
    )

    private fun finding(line: Int) = ReviewFinding(
        severity = Severity.MEDIUM,
        category = ReviewCategory.RELIABILITY,
        file = "src/Foo.kt",
        line = line,
        title = "Retry sem limite",
        description = "",
        impact = null,
        recommendation = null,
        suggestedComment = "Existe limite de tentativas aqui?",
        confidence = 0.9
    )
}

class PublishReviewUseCaseTest {
    @Test
    fun `publishes only the selected comments`() = runBlocking {
        val publisher = FakePublisher()
        val result = PublishReviewUseCase(publisher).execute(plan(), selectedIds = setOf(2), approve = false)

        assertEquals(listOf(2), result.posted.map { it.id })
        assertEquals(1, publisher.general.size)
        assertTrue(publisher.lines.isEmpty())
        assertFalse(publisher.approvedWith.isNotEmpty())
    }

    @Test
    fun `skips comments already published`() = runBlocking {
        val publisher = FakePublisher(existing = listOf("texto antigo\n\n<!-- mr-analyser:k1 -->"))
        val result = PublishReviewUseCase(publisher).execute(plan(), selectedIds = setOf(1, 2), approve = false)

        assertEquals(listOf(1), result.skippedAsDuplicate.map { it.id })
        assertEquals(listOf(2), result.posted.map { it.id })
    }

    @Test
    fun `falls back to the general thread when gitlab refuses the line`() = runBlocking {
        val publisher = FakePublisher(acceptLines = false)
        val result = PublishReviewUseCase(publisher).execute(plan(), selectedIds = setOf(1), approve = false)

        assertEquals(listOf(1), result.fellBackToGeneral.map { it.id })
        assertTrue(publisher.general.single().contains("src/Foo.kt:11"))
    }

    @Test
    fun `approves pinned to the analysed commit only when asked`() = runBlocking {
        val publisher = FakePublisher()
        val result = PublishReviewUseCase(publisher).execute(plan(), selectedIds = emptySet(), approve = true)

        assertTrue(result.approved)
        assertEquals(listOf<String?>("head"), publisher.approvedWith)
    }
}

class PublishFlowTest {
    @Test
    fun `non interactive mode refuses to approve a partial analysis`() = runBlocking {
        val publisher = FakePublisher()
        val output = mutableListOf<String>()
        val flow = PublishFlow(PublishReviewUseCase(publisher), echo = { output += it }, readInput = { null })

        flow.run(plan(partial = true), options("--select", "none", "--approve", "--yes"))

        assertTrue(publisher.approvedWith.isEmpty())
        assertTrue(output.any { it.contains("análise foi parcial") })
    }

    @Test
    fun `non interactive mode publishes and approves a clean analysis`() = runBlocking {
        val publisher = FakePublisher()
        val flow = PublishFlow(PublishReviewUseCase(publisher), echo = {}, readInput = { null })

        flow.run(plan(), options("--select", "all", "--approve", "--yes"))

        assertEquals(1, publisher.lines.size)
        assertEquals(1, publisher.general.size)
        assertEquals(listOf<String?>("head"), publisher.approvedWith)
    }

    @Test
    fun `interactive mode with empty answer publishes nothing`() = runBlocking {
        val publisher = FakePublisher()
        val flow = PublishFlow(PublishReviewUseCase(publisher), echo = {}, readInput = { "" })

        flow.run(plan(), options())

        assertTrue(publisher.lines.isEmpty() && publisher.general.isEmpty())
    }

    private fun options(vararg args: String): PublishOptions {
        val command = object : CliktCommand(name = "test") {
            val publishOptions by PublishOptions()
            override fun run() = Unit
        }
        command.parse(args.toList())
        return command.publishOptions
    }
}

class GitLabMergeRequestPublisherTest {
    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build()
    }

    private val discussions = "/api/v4/projects/group%2Fproject/merge_requests/7/discussions"

    @Test
    fun `posts a line comment with the gitlab position`() = runBlocking {
        wireMock.stubFor(post(urlEqualTo(discussions)).willReturn(aResponse().withStatus(201).withBody("{}")))

        val posted = publisher().postLineComment(
            "group/project", 7, "corpo",
            LineAnchor("a.kt", "a.kt", null, 11), DiffRefs("b", "s", "h")
        )

        assertTrue(posted)
        wireMock.verify(
            postRequestedFor(urlEqualTo(discussions)).withRequestBody(
                equalToJson(
                    """{"body":"corpo","position":{"position_type":"text","base_sha":"b","start_sha":"s",
                    "head_sha":"h","old_path":"a.kt","new_path":"a.kt","new_line":11}}""",
                    true, false
                )
            )
        )
    }

    @Test
    fun `reports a refused position instead of failing`() = runBlocking {
        wireMock.stubFor(post(urlEqualTo(discussions)).willReturn(aResponse().withStatus(400).withBody("{}")))

        val posted = publisher().postLineComment(
            "group/project", 7, "corpo",
            LineAnchor("a.kt", "a.kt", null, 999), DiffRefs("b", "s", "h")
        )

        assertFalse(posted)
    }

    @Test
    fun `approval sends the analysed sha`() = runBlocking {
        val approve = "/api/v4/projects/group%2Fproject/merge_requests/7/approve"
        wireMock.stubFor(post(urlEqualTo(approve)).willReturn(aResponse().withStatus(201).withBody("{}")))

        publisher().approve("group/project", 7, "h")

        wireMock.verify(postRequestedFor(urlEqualTo(approve)).withRequestBody(equalToJson("""{"sha":"h"}""")))
    }

    private fun publisher() = GitLabMergeRequestPublisher(GitLabClient("http://localhost:${wireMock.port}", "token"))
}

class CommentPlanFileStoreTest {
    @Test
    fun `round trips the plan and finds the latest for the mr`(@TempDir dir: Path) {
        val store = CommentPlanFileStore(dir)
        val older = dir.resolve("mr-7-20260101-000000.md").also { Files.writeString(it, "") }
        val newer = dir.resolve("mr-7-20260202-000000.md").also { Files.writeString(it, "") }
        store.writeNextTo(older, plan())
        val written = store.writeNextTo(newer, plan(partial = true))

        assertEquals(written, store.findLatest(7))
        assertEquals(plan(partial = true), store.read(written))
        assertNull(store.findLatest(7, projectPath = "outro/projeto"))
        assertNotNull(store.findLatest(7, projectPath = "group/project"))
    }
}

private fun plan(partial: Boolean = false) = CommentPlan(
    gitlabUrl = "https://gitlab.example.com",
    projectPath = "group/project",
    mrIid = 7,
    webUrl = null,
    diffRefs = DiffRefs("base", "start", "head"),
    recommendation = MergeRecommendation.APPROVE_WITH_SUGGESTIONS,
    blockingCount = 0,
    partialAnalysis = partial,
    comments = listOf(
        ReviewComment(
            id = 1, key = "k1", type = CommentType.QUESTION, title = "Retry", location = "src/Foo.kt:11",
            body = "Existe limite?", anchor = LineAnchor("src/Foo.kt", "src/Foo.kt", null, 11)
        ),
        ReviewComment(
            id = 2, key = "summary-head", type = CommentType.OBSERVATION, title = "Resumo",
            location = "(MR)", body = "Resumo"
        )
    )
)

private class FakePublisher(
    private val existing: List<String> = emptyList(),
    private val acceptLines: Boolean = true
) : MergeRequestPublisher {
    val lines = mutableListOf<String>()
    val general = mutableListOf<String>()
    val approvedWith = mutableListOf<String?>()

    override suspend fun currentHeadSha(project: String, mrIid: Long): String = "head"

    override suspend fun existingNoteBodies(project: String, mrIid: Long): List<String> = existing

    override suspend fun postLineComment(
        project: String, mrIid: Long, body: String, anchor: LineAnchor, diffRefs: DiffRefs
    ): Boolean {
        if (acceptLines) lines += body
        return acceptLines
    }

    override suspend fun postGeneralComment(project: String, mrIid: Long, body: String) {
        general += body
    }

    override suspend fun approve(project: String, mrIid: Long, headSha: String?) {
        approvedWith += headSha
    }
}
