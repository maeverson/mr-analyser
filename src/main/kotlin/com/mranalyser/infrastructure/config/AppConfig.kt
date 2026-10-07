package com.mranalyser.infrastructure.config

data class AppConfig(
    val gitlabUrl: String,
    val gitlabToken: String?,
    val llm: LlmConfig,
    val review: ReviewConfig,
    val limits: LimitsConfig,
    val context: ContextConfig,
    val maxConcurrency: Int,
    val verbose: Boolean = false,
    val knowledgeBase: KnowledgeBaseConfig = KnowledgeBaseConfig()
)

data class LlmConfig(
    val provider: String,
    val model: String,
    val apiKey: String?,
    val url: String?,
    val timeoutSeconds: Long = 180,
    val maxRetries: Int = 2,
    /**
     * Modo JSON nativo do fornecedor. Desligado por padrão porque vários gateways
     * "OpenAI-compatible" e proxies de Ollama respondem 400 ao campo, e 400 é falha permanente.
     */
    val jsonMode: Boolean = false,
    val maxOutputTokensReview: Int = 6_000,
    val maxOutputTokensAssessment: Int = 2_000,
    /**
     * Janela de contexto pedida ao provider self-hosted. Dimensioná-la é o que decide se o modelo
     * cabe inteiro na VRAM: `num_ctx` grande demais gasta no KV cache a memória que faltaria para
     * as camadas, e o servidor passa a rodar parte do modelo na CPU — uma diferença medida de
     * 5,9 para 31,6 tok/s em um 14B Q4 numa RTX 3060.
     */
    val numCtx: Int? = null,
    /** `none` (não envia), `off`, `stage` (só na validação) ou `on`. Ver [com.mranalyser.infrastructure.llm.ThinkingMode]. */
    val thinking: String = "none",
    val reasoningTokens: Int = 4_000
)

data class ReviewConfig(
    val ignoredPaths: List<String>,
    val ignoredCategories: Set<String>,
    val minimumConfidence: Double,
    val showLowConfidence: Boolean,
    val maxFindings: Int = 25,
    val understandingEnabled: Boolean = true,
    val validationEnabled: Boolean = true,
    val crossFileEnabled: Boolean = true,
    val finalAssessmentEnabled: Boolean = true,
    /**
     * Arquivo `.md` (ou diretório de `.md`) com as skills de revisão do time. Sem valor, usa
     * `.mranalyser/skills.md` do diretório atual, se existir.
     */
    val skillsPath: String? = null,
    /** Teto de caracteres de skills por prompt; sai do mesmo `num_ctx` que o diff. */
    val maxSkillChars: Int = 6_000
)

data class LimitsConfig(
    val maxDiffLines: Int,
    val maxFileLines: Int
)

data class ContextConfig(
    val enabled: Boolean = true,
    val maxFilesPerChange: Int = 4,
    val maxTotalFiles: Int = 24,
    val maxCharsPerFile: Int = 4_000,
    /**
     * Exige que o `origin` do diretório atual corresponda ao projeto do MR antes de usar o
     * contexto local. Desligar isto reintroduz o defeito da V1, em que o contexto vinha de um
     * repositório sem relação com o MR analisado.
     */
    val requireRepositoryMatch: Boolean = true
)

/**
 * Base de conhecimento de engenharia (MCP). Ligada por padrão: sem login, a etapa é registrada
 * como não executada com a instrução de `mr-analyser kb login`, e a análise segue.
 */
data class KnowledgeBaseConfig(
    val enabled: Boolean = true,
    val url: String = DEFAULT_URL,
    val maxDocuments: Int = 4,
    /** Sai do mesmo `num_ctx` do diff no Ollama, como as skills. */
    val maxChars: Int = 3_500,
    val timeoutSeconds: Long = 20
) {
    companion object {
        const val DEFAULT_URL = "https://processos.ctbz.com.br/mcp/base-conhecimento"
    }
}
