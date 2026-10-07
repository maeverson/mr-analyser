package com.mranalyser.infrastructure.knowledge

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Login interativo: abre o navegador no SSO da base e recebe o código num servidor HTTP local
 * em `127.0.0.1`, numa porta efêmera. O cliente é registrado a cada login com o redirect daquela
 * porta — registro dinâmico existe justamente para isso, e evita depender de porta fixa livre.
 */
class KbLoginFlow(
    private val serverUrl: String,
    private val oauth: KbOAuthClient = KbOAuthClient(serverUrl),
    private val store: KbCredentialStore = KbCredentialStore(),
    private val announce: (String) -> Unit = ::println,
    private val timeoutMillis: Long = 5 * 60_000
) {
    suspend fun login(): KbCredentials {
        val metadata = oauth.metadata()
        val received = CompletableDeferred<Map<String, String>>()

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext(CALLBACK_PATH) { exchange ->
            val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val page = if (query.containsKey("code")) SUCCESS_PAGE else FAILURE_PAGE
            val bytes = page.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            received.complete(query)
        }
        server.start()

        try {
            val redirectUri = "http://127.0.0.1:${server.address.port}$CALLBACK_PATH"
            val clientId = oauth.register(metadata, redirectUri)
            val verifier = randomToken(64)
            val state = randomToken(24)

            val authorizeUrl = metadata.authorizationEndpoint + "?" + listOf(
                "response_type" to "code",
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "scope" to KbOAuthClient.SCOPE,
                "state" to state,
                "code_challenge" to challenge(verifier),
                "code_challenge_method" to "S256",
                "resource" to serverUrl
            ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }

            announce("Abra no navegador para autenticar na base de conhecimento:\n\n$authorizeUrl\n")
            openBrowser(authorizeUrl)

            val query = withTimeout(timeoutMillis) { received.await() }
            query["error"]?.let { error("autorização negada: $it ${query["error_description"].orEmpty()}") }
            check(query["state"] == state) { "parâmetro state divergente; login descartado" }
            val code = query["code"] ?: error("callback sem código de autorização")

            return oauth.exchangeCode(metadata, clientId, code, verifier, redirectUri).also(store::save)
        } finally {
            server.stop(0)
        }
    }

    private fun openBrowser(url: String) {
        val command = when {
            System.getProperty("os.name").lowercase().contains("mac") -> listOf("open", url)
            System.getProperty("os.name").lowercase().contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> listOf("xdg-open", url)
        }
        runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.contains('=') }.associate { pair ->
            val (key, value) = pair.split('=', limit = 2)
            URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        const val CALLBACK_PATH = "/callback"

        val SUCCESS_PAGE = "<html><body><h3>mr-analyser autenticado.</h3><p>Pode fechar esta aba.</p></body></html>"
        val FAILURE_PAGE = "<html><body><h3>Falha na autenticação.</h3><p>Veja o terminal.</p></body></html>"

        private val random = SecureRandom()

        fun randomToken(bytes: Int): String {
            val buffer = ByteArray(bytes)
            random.nextBytes(buffer)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
        }

        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}
