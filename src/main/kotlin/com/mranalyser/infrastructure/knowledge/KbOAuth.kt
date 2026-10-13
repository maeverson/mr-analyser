package com.mranalyser.infrastructure.knowledge

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

internal val kbJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/** Credenciais OAuth da base de conhecimento, obtidas por `mr-analyser kb login`. */
@Serializable
data class KbCredentials(
    val serverUrl: String,
    val clientId: String,
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch em segundos. `null` quando o servidor não informa validade. */
    val expiresAt: Long? = null
) {
    fun expired(nowSeconds: Long, marginSeconds: Long = 60): Boolean =
        expiresAt != null && nowSeconds + marginSeconds >= expiresAt
}

/**
 * Persistência do token fora do repositório, legível só pelo usuário (600). Fica em
 * `~/.config/mr-analyser/` porque a ferramenta roda de dentro do checkout do MR, e um arquivo no
 * diretório atual acabaria versionado em algum repositório.
 */
class KbCredentialStore(
    private val path: Path = Paths.get(System.getProperty("user.home"), ".config", "mr-analyser", "kb-oauth.json")
) {
    fun load(): KbCredentials? = runCatching {
        if (!Files.exists(path)) null else kbJson.decodeFromString<KbCredentials>(Files.readString(path))
    }.getOrNull()

    fun save(credentials: KbCredentials) {
        Files.createDirectories(path.parent)
        Files.writeString(path, kbJson.encodeToString(credentials))
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    fun delete(): Boolean = Files.deleteIfExists(path)

    val location: String get() = path.toString()
}

@Serializable
data class AuthorizationServerMetadata(
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null
)

@Serializable
private data class ProtectedResourceMetadata(
    @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList()
)

@Serializable
private data class RegistrationResponse(
    @SerialName("client_id") val clientId: String
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null
)

/**
 * Cliente OAuth 2.1 conforme a especificação de autorização do MCP: descoberta por
 * `.well-known`, registro dinâmico de cliente público (sem segredo), authorization code com PKCE
 * S256 e refresh token. O servidor da base não aceita client credentials, então o primeiro acesso
 * exige o login interativo de uma pessoa.
 */
class KbOAuthClient(
    private val serverUrl: String,
    private val http: HttpClient = defaultHttpClient()
) {
    suspend fun metadata(): AuthorizationServerMetadata {
        val resource = URI(serverUrl)
        val resourceMetadata = http.get(wellKnown(resource, "oauth-protected-resource")).bodyAsText()
        val issuer = runCatching {
            kbJson.decodeFromString<ProtectedResourceMetadata>(resourceMetadata).authorizationServers.firstOrNull()
        }.getOrNull() ?: serverUrl

        val body = http.get(wellKnown(URI(issuer), "oauth-authorization-server")).bodyAsText()
        return kbJson.decodeFromString(body)
    }

    suspend fun register(metadata: AuthorizationServerMetadata, redirectUri: String): String {
        val endpoint = metadata.registrationEndpoint
            ?: error("servidor não oferece registro dinâmico de cliente")
        val response = http.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("client_name", "mr-analyser")
                    put("redirect_uris", buildJsonArray { add(JsonPrimitive(redirectUri)) })
                    put("grant_types", buildJsonArray {
                        add(JsonPrimitive("authorization_code"))
                        add(JsonPrimitive("refresh_token"))
                    })
                    put("response_types", buildJsonArray { add(JsonPrimitive("code")) })
                    put("token_endpoint_auth_method", "none")
                    put("scope", SCOPE)
                }.toString()
            )
        }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "registro de cliente recusado (${response.status.value}): ${body.take(300)}" }
        return kbJson.decodeFromString<RegistrationResponse>(body).clientId
    }

    suspend fun exchangeCode(
        metadata: AuthorizationServerMetadata,
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String
    ): KbCredentials = token(
        metadata,
        clientId,
        parameters {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", redirectUri)
            append("client_id", clientId)
            append("code_verifier", verifier)
            append("resource", serverUrl)
        },
        previousRefreshToken = null
    )

    suspend fun refresh(credentials: KbCredentials): KbCredentials {
        val refreshToken = credentials.refreshToken ?: error("sem refresh token")
        return token(
            metadata(),
            credentials.clientId,
            parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", credentials.clientId)
                append("resource", serverUrl)
            },
            previousRefreshToken = refreshToken
        )
    }

    private suspend fun token(
        metadata: AuthorizationServerMetadata,
        clientId: String,
        form: io.ktor.http.Parameters,
        previousRefreshToken: String?
    ): KbCredentials {
        val response = http.submitForm(metadata.tokenEndpoint, form)
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "token recusado (${response.status.value}): ${body.take(300)}" }
        val token = kbJson.decodeFromString<TokenResponse>(body)
        return KbCredentials(
            serverUrl = serverUrl,
            clientId = clientId,
            accessToken = token.accessToken,
            // Servidor sem rotação de refresh token devolve só o access token.
            refreshToken = token.refreshToken ?: previousRefreshToken,
            expiresAt = token.expiresIn?.let { System.currentTimeMillis() / 1_000 + it }
        )
    }

    /** RFC 8414/9728: o caminho do recurso vai depois do `.well-known/<tipo>`. */
    private fun wellKnown(uri: URI, kind: String): String {
        val path = uri.path.trimEnd('/')
        return "${uri.scheme}://${uri.authority}/.well-known/$kind$path"
    }

    companion object {
        const val SCOPE = "openid email profile"

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
            }
        }
    }
}

/**
 * Entrega um access token válido, renovando pelo refresh token quando expirado. Serializado
 * porque duas renovações concorrentes com rotação de refresh token invalidariam uma à outra.
 */
class KbTokenSource(
    private val serverUrl: String,
    private val store: KbCredentialStore = KbCredentialStore(),
    private val oauth: KbOAuthClient = KbOAuthClient(serverUrl),
    private val clock: () -> Long = { System.currentTimeMillis() / 1_000 }
) {
    private val logger = LoggerFactory.getLogger(KbTokenSource::class.java)
    private val lock = Mutex()

    /** `null` quando não há login para este servidor ou a renovação falhou. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = lock.withLock {
        val credentials = store.load()?.takeIf { it.serverUrl == serverUrl } ?: return@withLock null
        if (!forceRefresh && !credentials.expired(clock())) {
            return@withLock credentials.accessToken
        }
        if (credentials.refreshToken == null) {
            return@withLock null
        }

        try {
            oauth.refresh(credentials).also(store::save).accessToken
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            logger.warn("Renovação do token da base de conhecimento falhou: {}", exception.message)
            null
        }
    }
}
