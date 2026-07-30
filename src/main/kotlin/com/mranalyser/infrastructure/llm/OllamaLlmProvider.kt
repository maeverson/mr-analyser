package com.mranalyser.infrastructure.llm

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.port.LlmResponse
import com.mranalyser.application.port.LlmUsage
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Provider para Ollama self-hosted.
 *
 * Duas diferenças em relação aos providers de nuvem, ambas consequência de haver **um único
 * runner** atendendo o endpoint:
 *
 * 1. A resposta é consumida em streaming (`stream = true`). Sem isso, o tempo total de geração
 *    precisa caber em um `requestTimeout`, e não cabe: em hardware com offload parcial para CPU a
 *    geração fica na casa de 10 tokens/s, então alguns milhares de tokens de saída estouram
 *    qualquer teto razoável. Em streaming, o critério de falha é o servidor *parar de responder*.
 *
 * 2. As chamadas são serializadas por instância ([serialAccess]). Requisições concorrentes não
 *    ganham throughput num runner único: o Ollama divide o contexto entre os slots paralelos
 *    (truncando prompt) ou enfileira a requisição — e a requisição enfileirada não recebe byte
 *    algum, o que a faria morrer no timeout de ociosidade. Por isso `maxConcurrency` não se
 *    aplica aqui; o paralelismo do pipeline apenas prepara os prompts adiante.
 *
 * 3. A janela de contexto (`num_ctx`) é enviada pelo cliente, que é quem conhece o tamanho do
 *    prompt que produziu. Deixá-la ao default do Modelfile foi o que degradou a análise: 32k por
 *    slot, multiplicados por `OLLAMA_NUM_PARALLEL`, consumiram em KV cache a VRAM que faltou para
 *    as camadas do modelo, e 15 das 49 caíram na CPU — 5,9 tok/s em vez de 31,6.
 */
class OllamaLlmProvider(
    private val model: String,
    private val baseUrl: String = "http://localhost:11434",
    private val apiKey: String? = null,
    // Inferência self-hosted em diffs grandes é significativamente mais lenta. Aqui o valor é o
    // limite de ociosidade entre tokens, não a duração total permitida.
    private val settings: LlmTransportSettings = LlmTransportSettings(timeoutSeconds = 600)
) : LlmProvider {
    override val name: String = "ollama:$model"

    private val logger = LoggerFactory.getLogger(OllamaLlmProvider::class.java)
    private val client = buildStreamingLlmHttpClient(settings)
    private val serialAccess = Mutex()

    override suspend fun complete(request: LlmRequest): LlmResponse = safeCompletion(name) {
        serialAccess.withLock { generate(request) }
    }

    private suspend fun generate(request: LlmRequest): LlmResponse {
        val statement = client.preparePost(buildGenerateEndpoint(baseUrl)) {
            header("Content-Type", "application/json")
            if (!apiKey.isNullOrBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
            setBody(
                OllamaRequest(
                    model = model,
                    system = request.system,
                    prompt = request.user,
                    stream = true,
                    format = if (settings.jsonMode) "json" else null,
                    options = OllamaOptions(
                        temperature = request.temperature,
                        numPredict = request.maxOutputTokens,
                        numCtx = settings.numCtx
                    )
                )
            )
        }

        return statement.execute { response ->
            response.failureOrNull(name) { raw ->
                runCatching { llmJson.decodeFromString<OllamaChunk>(raw) }.getOrNull()?.error
            }?.let { return@execute LlmResponse.failed(it) }

            collect(response.bodyAsChannel(), request.maxOutputTokens)
        }
    }

    /**
     * Consome o NDJSON do Ollama até `done` ou fim do canal. Linha inválida é ignorada em vez de
     * derrubar a resposta: um fragmento malformado no meio do stream não invalida o que já veio.
     */
    private suspend fun collect(channel: ByteReadChannel, maxOutputTokens: Int): LlmResponse {
        val text = StringBuilder()
        var last: OllamaChunk? = null

        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.isBlank()) {
                continue
            }
            val chunk = runCatching { llmJson.decodeFromString<OllamaChunk>(line) }.getOrNull() ?: continue

            chunk.error?.let { error ->
                return LlmResponse.failed("$name retornou erro: $error")
            }
            text.append(chunk.response)
            last = chunk
            if (chunk.done) {
                break
            }
        }

        if (text.isBlank()) {
            return LlmResponse.failed("Ollama retornou conteúdo vazio")
        }

        val usage = last?.let {
            LlmUsage(
                promptTokens = it.promptEvalCount,
                outputTokens = it.evalCount,
                contextWindow = settings.numCtx
            )
        }

        // Ollama corta o começo do prompt para caber na janela e não avisa. Sem este log, a
        // revisão simplesmente fica pior sem que ninguém saiba por quê.
        if (usage != null && usage.exceedsContextWindow(maxOutputTokens)) {
            logger.warn(
                "Prompt de {} tokens + {} de saída não cabem em num_ctx={}: o Ollama truncou o " +
                    "prompt. Reduza MR_ANALYSER_MAX_DIFF_LINES ou aumente MR_ANALYSER_LLM_NUM_CTX.",
                usage.promptTokens,
                maxOutputTokens,
                settings.numCtx
            )
        }

        return LlmResponse(text.toString(), usage = usage)
    }

    private fun buildGenerateEndpoint(base: String): String {
        val normalized = base.trimEnd('/')
        return if (normalized.endsWith("/api")) "$normalized/generate" else "$normalized/api/generate"
    }
}

@Serializable
private data class OllamaRequest(
    val model: String,
    val prompt: String,
    val system: String? = null,
    val stream: Boolean,
    val format: String? = null,
    val options: OllamaOptions? = null
)

@Serializable
private data class OllamaOptions(
    val temperature: Double,
    @SerialName("num_predict") val numPredict: Int,
    @SerialName("num_ctx") val numCtx: Int? = null
)

@Serializable
private data class OllamaChunk(
    val response: String = "",
    val done: Boolean = false,
    val error: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
    @SerialName("eval_count") val evalCount: Int = 0
)
