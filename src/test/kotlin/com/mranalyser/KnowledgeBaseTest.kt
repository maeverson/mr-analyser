package com.mranalyser

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.mranalyser.application.llm.prompt.FindingValidationPrompt
import com.mranalyser.application.llm.prompt.UnderstandingPrompt
import com.mranalyser.application.port.KnowledgeBaseProvider
import com.mranalyser.application.port.KnowledgeDocument
import com.mranalyser.application.port.KnowledgeQuery
import com.mranalyser.application.port.KnowledgeSearchResult
import com.mranalyser.application.review.AnalysisDiagnostics
import com.mranalyser.application.review.ClassifiedFile
import com.mranalyser.application.review.KnowledgeBaseRetriever
import com.mranalyser.application.review.KnowledgeBudget
import com.mranalyser.application.review.KnowledgeExcerpt
import com.mranalyser.application.review.KnowledgeStatus
import com.mranalyser.application.review.MergeRequestOverview
import com.mranalyser.application.review.ValidationInput
import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.infrastructure.knowledge.KbCredentialStore
import com.mranalyser.infrastructure.knowledge.KbCredentials
import com.mranalyser.infrastructure.knowledge.KbTokenSource
import com.mranalyser.infrastructure.knowledge.McpKnowledgeBaseProvider
import com.mranalyser.support.MergeRequestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

class KnowledgeBaseTest {

    // --- Retriever -------------------------------------------------------------------------

    private class FakeKnowledgeBase(private val answer: (KnowledgeQuery) -> KnowledgeSearchResult) : KnowledgeBaseProvider {
        val queries = mutableListOf<KnowledgeQuery>()
        override suspend fun search(query: KnowledgeQuery): KnowledgeSearchResult {
            queries += query
            return answer(query)
        }
    }

    private val contract = KnowledgeDocument(
        content = "# Detalhe da empresa\n\n**Status:** proposta.\nSe o filtro devolver mais de um plano, o BFF responde `503`.",
        source = "gs://kb/billing/billing-backoffice-bff/contracts/empresas-detalhe.md",
        repo = "billing-backoffice-bff",
        docType = "contracts"
    )
    private val adr = KnowledgeDocument(
        content = "# 3. Integração HTTP\n\n## Status\n\nAceito\n\nTimeout configurado no bean do client.",
        source = "gs://kb/billing/billing-backoffice-bff/adr/003.md",
        repo = "billing-backoffice-bff",
        docType = "adr"
    )

    @Test
    fun `deve manter so documentos do repositorio, sem duplicata, na ordem de relevancia da busca`() = runBlocking {
        val kb = FakeKnowledgeBase {
            KnowledgeSearchResult(
                listOf(
                    contract,
                    contract.copy(source = "gs://kb/billing/billing-backoffice/-bff/contracts/empresas-detalhe.md"),
                    KnowledgeDocument("ADR do product-core sem relação", "gs://kb/product-core/adr/1.md", repo = "product-core"),
                    KnowledgeDocument(
                        "Processo transversal: o billing-backoffice-bff agrega três fontes.",
                        "gs://kb/cross-repo/processo.md"
                    ),
                    adr
                )
            )
        }
        val diagnostics = AnalysisDiagnostics()

        val excerpts = KnowledgeBaseRetriever(kb).retrieve(PROJECT, overview(), diagnostics)

        // O contrato (proposto) vem antes do ADR (aceito) porque a busca o considerou mais
        // relevante; ordenar por status cortava justamente o contrato do endpoint.
        assertEquals(listOf(contract.source, adr.source, "gs://kb/cross-repo/processo.md"), excerpts.map { it.source })
        assertEquals(KnowledgeStatus.PROPOSED, excerpts[0].status)
        assertEquals(KnowledgeStatus.ACCEPTED, excerpts[1].status)
        assertFalse(excerpts[2].sameRepository)
        assertEquals(excerpts.map { it.source }, diagnostics.knowledgeSources)
        assertEquals("billing", kb.queries.single().squad)
        assertTrue(diagnostics.skippedStages.isEmpty())
    }

    @Test
    fun `deve repetir a busca sem squad quando o filtro nao traz o repositorio`() = runBlocking {
        val kb = FakeKnowledgeBase { query ->
            if (query.squad != null) KnowledgeSearchResult(emptyList()) else KnowledgeSearchResult(listOf(adr))
        }

        val excerpts = KnowledgeBaseRetriever(kb).retrieve(PROJECT, overview(), AnalysisDiagnostics())

        assertEquals(listOf("billing", null), kb.queries.map { it.squad })
        assertEquals(1, excerpts.size)
    }

    @Test
    fun `falha da base deve virar etapa nao executada sem interromper`() = runBlocking {
        val kb = FakeKnowledgeBase { KnowledgeSearchResult.failed("sem login na base — execute: mr-analyser kb login") }
        val diagnostics = AnalysisDiagnostics()

        val excerpts = KnowledgeBaseRetriever(kb).retrieve(PROJECT, overview(), diagnostics)

        assertTrue(excerpts.isEmpty())
        assertTrue(diagnostics.skippedStages.single().contains("kb login"))
    }

    @Test
    fun `projeto desconhecido nao deve aceitar documento qualquer`() = runBlocking {
        val kb = FakeKnowledgeBase { KnowledgeSearchResult(listOf(adr)) }
        val diagnostics = AnalysisDiagnostics()

        val excerpts = KnowledgeBaseRetriever(kb).retrieve("", overview(), diagnostics)

        assertTrue(excerpts.isEmpty())
        assertTrue(kb.queries.isEmpty())
        assertTrue(diagnostics.skippedStages.single().contains("projeto do MR desconhecido"))
    }

    @Test
    fun `orcamento deve limitar quantidade e tamanho dos documentos`() = runBlocking {
        val big = (1..6).map { adr.copy(content = "billing-backoffice-bff doc $it\n" + "x".repeat(900), source = "s$it") }
        val kb = FakeKnowledgeBase { KnowledgeSearchResult(big) }

        val excerpts = KnowledgeBaseRetriever(kb, KnowledgeBudget(maxDocuments = 4, maxChars = 2_000))
            .retrieve(PROJECT, overview(), AnalysisDiagnostics())

        assertTrue(excerpts.size <= 4)
        assertTrue(excerpts.sumOf { it.content.length } <= 2_000 + 40)
    }

    @Test
    fun `copias do mesmo documento com preambulos gerados diferentes devem virar uma so`() = runBlocking {
        val body = "# Detalhe da empresa\n\n**Status:** proposta.\nO BFF agrega três fontes."
        val kb = FakeKnowledgeBase {
            KnowledgeSearchResult(
                listOf(
                    contract.copy(
                        content = "Este trecho é a introdução do\n\n## Cross-Repo Context\nResumo A.\n\n$body",
                        source = "gs://kb/billing/billing-backoffice-bff/contracts/empresas-detalhe.md"
                    ),
                    contract.copy(
                        content = "Este trecho corresponde à introdução da\n\n## Cross-Repo Context\nResumo B, diferente.\n\n$body",
                        source = "gs://kb/billing/billing-backoffice/-bff/contracts/empresas-detalhe.md"
                    )
                )
            )
        }

        val excerpts = KnowledgeBaseRetriever(kb).retrieve(PROJECT, overview(), AnalysisDiagnostics())

        assertEquals(1, excerpts.size)
        assertEquals(body, excerpts.single().content)
        assertEquals("gs://kb/billing/billing-backoffice-bff/contracts/empresas-detalhe.md", excerpts.single().source)
    }

    @Test
    fun `consulta nao deve levar sintaxe do Lucene`() {
        assertEquals("GET companies uidc a b", KnowledgeBaseRetriever.sanitize("GET /companies/:uidc (a) [b]"))
    }

    @Test
    fun `status deve ser reconhecido nos formatos usados pela base`() {
        assertEquals(KnowledgeStatus.ACCEPTED, KnowledgeBaseRetriever.statusOf("## Status\n\nAceito\n"))
        assertEquals(KnowledgeStatus.ACCEPTED, KnowledgeBaseRetriever.statusOf("**Status:** Aprovado  \n"))
        assertEquals(KnowledgeStatus.PROPOSED, KnowledgeBaseRetriever.statusOf("**Status:** proposta. Origem: PRD"))
        assertEquals(KnowledgeStatus.UNKNOWN, KnowledgeBaseRetriever.statusOf("documento sem cabeçalho"))
    }

    // --- Prompts ---------------------------------------------------------------------------

    @Test
    fun `entendimento e validacao devem receber os documentos com as regras de uso`() {
        val knowledge = listOf(
            KnowledgeExcerpt(contract.source, "contracts", KnowledgeStatus.PROPOSED, "responde 503 com mais de um plano", true)
        )

        val understanding = UnderstandingPrompt().build(overview(), emptyList(), "digest", 1_000, knowledge).user
        assertTrue(understanding.contains("BASE DE CONHECIMENTO"))
        assertTrue(understanding.contains("### D1 [PROPOSTO] contracts — empresas-detalhe.md"))
        assertTrue(understanding.contains("A document is CONTEXT, not evidence"))
        assertTrue(understanding.contains("intentDiscrepancy"))

        val validation = FindingValidationPrompt().build(validationInput(knowledge), 1_000).user
        assertTrue(validation.contains("responde 503 com mais de um plano"))
        assertTrue(validation.contains("documented decision in the knowledge-base"))
    }

    @Test
    fun `sem documentos o prompt nao deve ganhar secao vazia`() {
        val understanding = UnderstandingPrompt().build(overview(), emptyList(), "digest", 1_000).user

        assertFalse(understanding.contains("BASE DE CONHECIMENTO"))
    }

    // --- Cliente MCP -----------------------------------------------------------------------

    private lateinit var server: WireMockServer

    @BeforeEach
    fun startServer() {
        server = WireMockServer(options().dynamicPort())
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop()
    }

    private fun mcpUrl() = "http://localhost:${server.port()}/mcp"

    private fun store(credentials: KbCredentials?): KbCredentialStore {
        val store = KbCredentialStore(Files.createTempDirectory("kb").resolve("kb-oauth.json"))
        credentials?.let(store::save)
        return store
    }

    private fun credentials(token: String = "token-1", expiresAt: Long? = null) =
        KbCredentials(mcpUrl(), "client-1", token, refreshToken = "refresh-1", expiresAt = expiresAt)

    private fun stubInitialize() {
        server.stubFor(
            post(urlPathEqualTo("/mcp")).withRequestBody(containing("\"initialize\"")).willReturn(
                aResponse().withHeader("Content-Type", "application/json").withHeader("Mcp-Session-Id", "sessao-1")
                    .withBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""")
            )
        )
        server.stubFor(
            post(urlPathEqualTo("/mcp")).withRequestBody(containing("notifications/initialized"))
                .willReturn(aResponse().withStatus(202))
        )
    }

    private val searchText =
        """{\"results\":[{\"content\":\"contrato\",\"gcsPath\":\"gs://kb/a.md\",\"squad\":\"billing\",\"repo\":\"billing-backoffice-bff\",\"docType\":\"contracts\"}]}"""

    @Test
    fun `deve inicializar sessao e ler resultado em SSE`() = runBlocking {
        stubInitialize()
        server.stubFor(
            post(urlPathEqualTo("/mcp")).withRequestBody(containing("tools/call")).willReturn(
                aResponse().withHeader("Content-Type", "text/event-stream").withBody(
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"$searchText\"}]}}\n\n"
                )
            )
        )
        val provider = McpKnowledgeBaseProvider(mcpUrl(), KbTokenSource(mcpUrl(), store(credentials())))

        val result = provider.search(KnowledgeQuery("empresa detalhe", squad = "billing"))

        assertNull(result.failure)
        assertEquals("gs://kb/a.md", result.documents.single().source)
        assertEquals("billing-backoffice-bff", result.documents.single().repo)
        server.verify(
            postRequestedFor(urlPathEqualTo("/mcp"))
                .withRequestBody(containing("tools/call"))
                .withHeader("Authorization", equalTo("Bearer token-1"))
                .withHeader("Mcp-Session-Id", equalTo("sessao-1"))
                .withRequestBody(containing("\"squad\":\"billing\""))
        )
    }

    @Test
    fun `token recusado deve ser renovado uma vez e a busca repetida`() = runBlocking {
        stubInitialize()
        server.stubFor(
            post(urlPathEqualTo("/mcp")).withRequestBody(containing("tools/call"))
                .inScenario("auth").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("renovado")
        )
        server.stubFor(
            post(urlPathEqualTo("/mcp")).withRequestBody(containing("tools/call"))
                .inScenario("auth").whenScenarioStateIs("renovado")
                .willReturn(
                    aResponse().withHeader("Content-Type", "application/json")
                        .withBody("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"$searchText"}]}}""")
                )
        )
        server.stubFor(
            get(urlPathEqualTo("/.well-known/oauth-protected-resource/mcp")).willReturn(
                aResponse().withBody("""{"authorization_servers":["${mcpUrl()}"]}""")
            )
        )
        server.stubFor(
            get(urlPathEqualTo("/.well-known/oauth-authorization-server/mcp")).willReturn(
                aResponse().withBody(
                    """{"authorization_endpoint":"${mcpUrl()}/authorize","token_endpoint":"${mcpUrl()}/token"}"""
                )
            )
        )
        server.stubFor(
            post(urlPathEqualTo("/mcp/token")).withRequestBody(containing("grant_type=refresh_token"))
                .willReturn(aResponse().withBody("""{"access_token":"token-2","expires_in":3600}"""))
        )
        val store = store(credentials())

        val result = McpKnowledgeBaseProvider(mcpUrl(), KbTokenSource(mcpUrl(), store)).search(KnowledgeQuery("x"))

        assertNull(result.failure)
        assertEquals(1, result.documents.size)
        assertEquals("token-2", store.load()?.accessToken)
        assertEquals("refresh-1", store.load()?.refreshToken, "sem rotação, o refresh token anterior continua valendo")
        server.verify(
            postRequestedFor(urlPathEqualTo("/mcp")).withRequestBody(containing("tools/call"))
                .withHeader("Authorization", equalTo("Bearer token-2"))
        )
    }

    @Test
    fun `erro da ferramenta deve virar falha e nao lista vazia`() = runBlocking {
        stubInitialize()
        server.stubFor(
            post(urlPathEqualTo("/mcp")).withRequestBody(containing("tools/call")).willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(
                    """{"jsonrpc":"2.0","id":2,"result":{"isError":true,"content":[{"type":"text","text":"base-conhecimento respondeu 500"}]}}"""
                )
            )
        )

        val result = McpKnowledgeBaseProvider(mcpUrl(), KbTokenSource(mcpUrl(), store(credentials()))).search(KnowledgeQuery("x"))

        assertTrue(result.failure!!.contains("respondeu 500"))
    }

    @Test
    fun `sem login deve orientar o comando de login sem chamar o servidor`() = runBlocking {
        val result = McpKnowledgeBaseProvider(mcpUrl(), KbTokenSource(mcpUrl(), store(null))).search(KnowledgeQuery("x"))

        assertTrue(result.failure!!.contains("mr-analyser kb login"))
        assertEquals(0, server.allServeEvents.size)
    }

    @Test
    fun `credencial salva deve ficar legivel so pelo usuario`() {
        val path = Files.createTempDirectory("kb").resolve("kb-oauth.json")
        KbCredentialStore(path).save(credentials())

        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
    }

    // --- Fixtures --------------------------------------------------------------------------

    private fun overview(): MergeRequestOverview {
        val mr = MergeRequestFixtures.transactionalOrderingMr()
        return MergeRequestOverview.from(
            mr,
            mr.changes.map { ClassifiedFile(it, ChangeGroup.APPLICATION, "ADD 1 | x") }
        )
    }

    private fun validationInput(knowledge: List<KnowledgeExcerpt>) = ValidationInput(
        overview = overview(),
        understanding = null,
        candidates = emptyList(),
        relatedContext = emptyList(),
        discussions = emptyList(),
        evidenceExcerpts = emptyMap(),
        knowledge = knowledge
    )

    private companion object {
        const val PROJECT = "ctbz/billing/backoffice/billing-backoffice-bff"
    }
}
