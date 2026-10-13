package com.mranalyser.infrastructure.knowledge

import com.mranalyser.application.port.KnowledgeBaseProvider
import com.mranalyser.application.port.KnowledgeDocument
import com.mranalyser.application.port.KnowledgeQuery
import com.mranalyser.application.port.KnowledgeSearchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cliente mínimo de MCP sobre HTTP (Streamable HTTP) para a ferramenta `search_documents`.
 *
 * Mínimo de propósito: só `initialize`, `notifications/initialized` e `tools/call`. Usa
 * `search_documents` e não `ask_knowledge_base` — a segunda é lenta e devolve a síntese de outro
 * modelo, que viraria mais uma fonte de alucinação dentro do prompt.
 */
class McpKnowledgeBaseProvider(
    private val serverUrl: String,
    private val tokens: KbTokenSource,
    timeoutSeconds: Long = 20,
    private val http: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutSeconds * 1_000
            connectTimeoutMillis = minOf(timeoutSeconds, 10) * 1_000
        }
    }
) : KnowledgeBaseProvider {
    private val ids = AtomicInteger()
    private val sessionLock = Mutex()
    private var session: Session? = null

    private data class Session(val token: String, val id: String?)

    override suspend fun search(query: KnowledgeQuery): KnowledgeSearchResult = try {
        val token = tokens.accessToken()
            ?: return KnowledgeSearchResult.failed("sem login na base — execute: mr-analyser kb login")
        callSearch(query, token, allowRetry = true)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        KnowledgeSearchResult.failed("base indisponível: ${exception::class.simpleName}: ${exception.message}")
    }

    private suspend fun callSearch(query: KnowledgeQuery, token: String, allowRetry: Boolean): KnowledgeSearchResult {
        val current = session(token)
        val response = rpc(
            current,
            "tools/call",
            buildJsonObject {
                put("name", "search_documents")
                put("arguments", buildJsonObject {
                    put("query", query.text)
                    put("limit", query.limit.coerceIn(1, 20))
                    query.squad?.let { put("squad", it) }
                })
            }
        )

        // Token expirado no servidor antes do previsto, ou sessão MCP descartada: uma nova
        // tentativa com token renovado e sessão nova resolve os dois casos.
        if ((response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.NotFound) && allowRetry) {
            sessionLock.withLock { session = null }
            val renewed = if (response.status == HttpStatusCode.Unauthorized) {
                tokens.accessToken(forceRefresh = true)
                    ?: return KnowledgeSearchResult.failed("login na base expirado — execute: mr-analyser kb login")
            } else {
                token
            }
            return callSearch(query, renewed, allowRetry = false)
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            return KnowledgeSearchResult.failed("login na base recusado — execute: mr-analyser kb login")
        }
        if (!response.status.isSuccess()) {
            return KnowledgeSearchResult.failed("base respondeu HTTP ${response.status.value}")
        }

        return parseToolResult(message(response))
    }

    private suspend fun session(token: String): Session = sessionLock.withLock {
        session?.takeIf { it.token == token }?.let { return@withLock it }

        val initialize = rpc(
            Session(token, null),
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", "mr-analyser")
                    put("version", "1")
                })
            }
        )
        check(initialize.status.isSuccess()) { "initialize recusado (HTTP ${initialize.status.value})" }
        message(initialize)

        val created = Session(token, initialize.headers[SESSION_HEADER])
        post(created, buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        })
        created.also { session = it }
    }

    private suspend fun rpc(session: Session, method: String, params: JsonObject): HttpResponse =
        post(session, buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.incrementAndGet())
            put("method", method)
            put("params", params)
        })

    private suspend fun post(session: Session, body: JsonObject): HttpResponse = http.post(serverUrl) {
        contentType(ContentType.Application.Json)
        header("Accept", "application/json, text/event-stream")
        header("Authorization", "Bearer ${session.token}")
        header("MCP-Protocol-Version", PROTOCOL_VERSION)
        session.id?.let { header(SESSION_HEADER, it) }
        setBody(body.toString())
    }

    /** A resposta pode vir como JSON puro ou como SSE com a mensagem JSON-RPC em `data:`. */
    private suspend fun message(response: HttpResponse): JsonObject {
        val body = response.bodyAsText()
        val payload = if (response.headers["Content-Type"].orEmpty().contains("text/event-stream")) {
            body.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .lastOrNull { it.contains("\"result\"") || it.contains("\"error\"") }
                ?: error("stream SSE sem resposta JSON-RPC")
        } else {
            body
        }

        val message = kbJson.parseToJsonElement(payload).jsonObject
        message["error"]?.let { error("erro JSON-RPC: ${it.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: it}") }
        return message
    }

    private fun parseToolResult(message: JsonObject): KnowledgeSearchResult {
        val result = message["result"]?.jsonObject ?: return KnowledgeSearchResult.failed("resposta sem result")
        val text = result["content"]?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("\n")
            .orEmpty()

        if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) {
            return KnowledgeSearchResult.failed("busca falhou: ${text.take(200)}")
        }

        val structured: JsonElement = result["structuredContent"]
            ?: runCatching { kbJson.parseToJsonElement(text) }.getOrNull()
            ?: return KnowledgeSearchResult.failed("resposta da busca não é JSON: ${text.take(200)}")

        val documents = runCatching {
            kbJson.decodeFromJsonElement(SearchResponse.serializer(), structured).results
        }.getOrElse { return KnowledgeSearchResult.failed("formato de busca inesperado: ${it.message}") }

        return KnowledgeSearchResult(
            documents.map {
                KnowledgeDocument(
                    content = it.content,
                    source = it.gcsPath ?: "(sem origem)",
                    repo = it.repo,
                    squad = it.squad,
                    docType = it.docType
                )
            }
        )
    }

    @Serializable
    private data class SearchResponse(val results: List<SearchHit> = emptyList())

    @Serializable
    private data class SearchHit(
        val content: String,
        val gcsPath: String? = null,
        val squad: String? = null,
        val repo: String? = null,
        val docType: String? = null
    )

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val SESSION_HEADER = "Mcp-Session-Id"
    }
}
