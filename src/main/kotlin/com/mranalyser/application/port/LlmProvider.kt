package com.mranalyser.application.port

/**
 * Etapa do pipeline que originou a chamada. Serve para log/telemetria e para permitir que
 * o provider ajuste limites por etapa, sem que o provider conheça a semântica da análise.
 */
enum class LlmPurpose(val label: String) {
    UNDERSTANDING("understanding"),
    LOCAL_REVIEW("local-review"),
    VALIDATION("validation"),
    CROSS_FILE_REVIEW("cross-file-review"),
    FINAL_ASSESSMENT("final-assessment")
}

data class LlmRequest(
    val purpose: LlmPurpose,
    val system: String,
    val user: String,
    val maxOutputTokens: Int = 4096,
    val temperature: Double = 0.1,
    /** Identificação legível do alvo (ex.: "chunk 2/5") usada apenas em log e diagnóstico. */
    val label: String = ""
)

data class LlmResponse(
    val text: String,
    val failure: String? = null
) {
    val successful: Boolean get() = failure == null && text.isNotBlank()

    companion object {
        fun failed(reason: String): LlmResponse = LlmResponse(text = "", failure = reason)
    }
}

/**
 * Porta de comunicação com o modelo. Responsabilidade **exclusivamente** de transporte:
 * autenticação, serialização do protocolo do fornecedor, timeout e mapeamento de erro.
 *
 * Prompt, schema de resposta e parsing vivem em `application/llm` e são compartilhados por
 * todos os providers (item 35). Essa inversão é o que permite o pipeline multi-etapa: sem ela
 * cada provider carregaria seu próprio prompt e só existiria uma etapa possível.
 *
 * Contrato: **nunca lançar exceção**. Falha de rede, HTTP não-2xx ou timeout devem retornar
 * [LlmResponse.failed], para que uma etapa com problema não invalide a análise inteira (item 38).
 */
interface LlmProvider {
    suspend fun complete(request: LlmRequest): LlmResponse

    /** Nome do provider, exibido no diagnóstico da análise. */
    val name: String
}
