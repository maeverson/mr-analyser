package com.mranalyser.domain.model

enum class Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO;

    val weight: Int
        get() = when (this) {
            CRITICAL -> 5
            HIGH -> 4
            MEDIUM -> 3
            LOW -> 2
            INFO -> 1
        }

    fun atLeast(other: Severity): Boolean = weight >= other.weight

    fun downgrade(): Severity = when (this) {
        CRITICAL -> HIGH
        HIGH -> MEDIUM
        MEDIUM -> LOW
        LOW -> INFO
        INFO -> INFO
    }

    fun cappedAt(maximum: Severity): Severity = if (weight > maximum.weight) maximum else this
}

enum class ReviewCategory {
    BUG,
    BUSINESS_RULE,
    SECURITY,
    ARCHITECTURE,
    DESIGN,
    PERFORMANCE,
    CONCURRENCY,
    TRANSACTION,
    DATA_CONSISTENCY,
    RELIABILITY,
    API_CONTRACT,
    OBSERVABILITY,
    OPERATIONS,
    TESTABILITY,
    MAINTAINABILITY,
    CODE_STYLE,
    COMPATIBILITY,
    DOCUMENTATION;

    /**
     * Categorias em que um cenário de falha concreto é esperado quando o finding é material
     * (item 10 da especificação).
     */
    val requiresFailureScenario: Boolean
        get() = this in setOf(
            BUG, RELIABILITY, CONCURRENCY, TRANSACTION, DATA_CONSISTENCY, PERFORMANCE, SECURITY
        )

    val label: String
        get() = when (this) {
            BUG -> "Correção"
            BUSINESS_RULE -> "Regra de negócio"
            SECURITY -> "Segurança"
            ARCHITECTURE -> "Arquitetura"
            DESIGN -> "Design"
            PERFORMANCE -> "Performance"
            CONCURRENCY -> "Concorrência"
            TRANSACTION -> "Transação"
            DATA_CONSISTENCY -> "Consistência de dados"
            RELIABILITY -> "Confiabilidade"
            API_CONTRACT -> "Contrato de API"
            OBSERVABILITY -> "Observabilidade"
            OPERATIONS -> "Operação"
            TESTABILITY -> "Testes"
            MAINTAINABILITY -> "Manutenibilidade"
            CODE_STYLE -> "Estilo"
            COMPATIBILITY -> "Compatibilidade"
            DOCUMENTATION -> "Documentação"
        }
}

/**
 * Natureza epistemológica do finding. Separa "existe evidência de comportamento incorreto"
 * de "há uma decisão que merece esclarecimento" (item 2 da especificação).
 */
enum class FindingType {
    /** Evidência concreta de comportamento incorreto. */
    BUG,

    /** Pode funcionar, mas há condição relevante capaz de provocar falha. */
    RISK,

    /** Funciona, porém introduz dívida técnica ou desenho inadequado. */
    DESIGN,

    /** Impacto estrutural, acoplamento ou responsabilidade incorreta. */
    ARCHITECTURE,

    /** Falta informação no contexto para afirmar problema; requer esclarecimento do autor. */
    QUESTION,

    /** Melhoria possível, não deve impedir aprovação. */
    SUGGESTION;

    /** Tipos que nunca devem bloquear um MR por si mesmos. */
    val neverBlocks: Boolean
        get() = this == QUESTION || this == SUGGESTION
}

/** Tom do comentário sugerido para o GitLab (item 17). */
enum class CommentType {
    BLOCKER,
    QUESTION,
    SUGGESTION,
    OBSERVATION,
    PRAISE
}

/** Se o problema foi introduzido por este MR ou é pré-existente (item 19). */
enum class FindingScope {
    INTRODUCED,
    PRE_EXISTING
}

/** Etapa que produziu o finding. Usado para rastreabilidade no relatório JSON. */
enum class FindingOrigin {
    STATIC_RULE,
    LOCAL_REVIEW,
    CROSS_FILE_REVIEW
}

enum class ValidationVerdict {
    /** Não passou pela etapa de validação (etapa desabilitada ou indisponível). */
    NOT_VALIDATED,
    CONFIRMED,
    SEVERITY_ADJUSTED,
    DOWNGRADED_TO_QUESTION,
    DISCARDED
}

data class ValidationOutcome(
    val verdict: ValidationVerdict,
    val rationale: String? = null
)

data class ReviewFinding(
    val severity: Severity,
    val category: ReviewCategory,
    val file: String?,
    val line: Int?,
    val title: String,
    val description: String,
    val impact: String?,
    val recommendation: String?,
    val suggestedComment: String?,
    val confidence: Double,
    val type: FindingType = FindingType.RISK,
    /** Fato verificável no código/contexto que sustenta o finding (item 9). */
    val evidence: String? = null,
    /** Raciocínio que liga a evidência ao impacto. Opcional. */
    val reasoning: String? = null,
    /** Sequência concreta de eventos que leva à falha (item 10). */
    val failureScenario: String? = null,
    /** Decidido por [com.mranalyser.domain.policy.BlockingPolicy], não inferido da severidade. */
    val blocking: Boolean = false,
    val commentType: CommentType? = null,
    val scope: FindingScope = FindingScope.INTRODUCED,
    val origin: FindingOrigin = FindingOrigin.LOCAL_REVIEW,
    val componentsAffected: List<String> = emptyList(),
    val relatedFiles: List<String> = emptyList(),
    val validation: ValidationOutcome? = null
) {
    val hasEvidence: Boolean
        get() = !evidence.isNullOrBlank() || !failureScenario.isNullOrBlank()

    val location: String
        get() = when {
            file == null -> "(MR)"
            line == null -> file
            else -> "$file:$line"
        }

    /** Identificador estável usado para correlacionar findings entre etapas do pipeline. */
    val correlationKey: String
        get() = "${category.name}|${file ?: "-"}|${line ?: "-"}|${title.lowercase().trim()}"
}
