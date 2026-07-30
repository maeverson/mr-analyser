package com.mranalyser.domain.model

enum class MergeRecommendation {
    APPROVE,
    APPROVE_WITH_SUGGESTIONS,

    /**
     * Não há evidência suficiente para solicitar mudança, mas existem decisões relevantes
     * que precisam de esclarecimento do autor (item 12).
     */
    NEEDS_DISCUSSION,
    REQUEST_CHANGES;

    val label: String
        get() = when (this) {
            APPROVE -> "APPROVE"
            APPROVE_WITH_SUGGESTIONS -> "APPROVE_WITH_SUGGESTIONS"
            NEEDS_DISCUSSION -> "NEEDS_DISCUSSION"
            REQUEST_CHANGES -> "REQUEST_CHANGES"
        }
}

enum class BlastRadius {
    /** Restrito ao trecho alterado. */
    LOCAL,

    /** Afeta o módulo/pacote. */
    MODULE,

    /** Afeta o comportamento observável do serviço. */
    SERVICE,

    /** Afeta contratos consumidos por outros serviços. */
    CROSS_SERVICE,
    UNKNOWN;

    val label: String
        get() = when (this) {
            LOCAL -> "local"
            MODULE -> "módulo"
            SERVICE -> "serviço"
            CROSS_SERVICE -> "entre serviços"
            UNKNOWN -> "não determinado"
        }
}

/** Resultado da etapa "Entendimento da alteração" (item 4). */
data class ChangeUnderstanding(
    val intent: String,
    val narrative: String,
    val behaviourChanges: List<String> = emptyList(),
    val newExecutionPaths: List<String> = emptyList(),
    val contractChanges: List<String> = emptyList(),
    val affectedDependencies: List<String> = emptyList(),
    val blastRadius: BlastRadius = BlastRadius.UNKNOWN,
    val blastRadiusRationale: String? = null,
    /** Discrepância entre o que a descrição/commits afirmam e o que o diff faz (item 32). */
    val intentDiscrepancy: String? = null
)

enum class ArchitecturalSignalKind {
    NEW_DEPENDENCY,
    NEW_MODULE,
    NEW_EXTERNAL_CLIENT,
    NEW_MIGRATION,
    SCHEMA_CHANGE,
    NEW_CONSUMER,
    NEW_PRODUCER,
    NEW_ENDPOINT,
    CONTRACT_CHANGE,
    CONFIGURATION_CHANGE,
    TIMEOUT_CHANGE,
    RETRY_CHANGE,
    CONCURRENCY_CHANGE,
    FEATURE_FLAG,
    FILE_REMOVED;

    val label: String
        get() = when (this) {
            NEW_DEPENDENCY -> "nova dependência"
            NEW_MODULE -> "novo módulo"
            NEW_EXTERNAL_CLIENT -> "novo client externo"
            NEW_MIGRATION -> "nova migration"
            SCHEMA_CHANGE -> "alteração de schema"
            NEW_CONSUMER -> "novo consumer"
            NEW_PRODUCER -> "novo producer"
            NEW_ENDPOINT -> "novo endpoint"
            CONTRACT_CHANGE -> "alteração de contrato"
            CONFIGURATION_CHANGE -> "alteração de configuração"
            TIMEOUT_CHANGE -> "alteração de timeout"
            RETRY_CHANGE -> "alteração de retry"
            CONCURRENCY_CHANGE -> "alteração de concorrência/thread pool"
            FEATURE_FLAG -> "feature flag"
            FILE_REMOVED -> "arquivo removido"
        }
}

data class ArchitecturalSignal(
    val kind: ArchitecturalSignalKind,
    val detail: String,
    val file: String? = null
)

enum class AnalysisConfidence {
    HIGH,
    MEDIUM,
    LOW
}

/** Parecer executivo (item 21). */
data class TechnicalOpinion(
    val opinion: String,
    val mainRisk: String? = null,
    val analysisConfidence: AnalysisConfidence = AnalysisConfidence.MEDIUM
)

/** Quality gate do próprio analisador (item 33). */
data class AnalysisQuality(
    val filesChanged: Int = 0,
    val filesAnalysed: Int = 0,
    val chunksAnalysed: Int = 0,
    val chunksFailed: Int = 0,
    val relatedContextsLoaded: Int = 0,
    val candidateFindings: Int = 0,
    val discardedByDeduplication: Int = 0,
    val discardedByValidation: Int = 0,
    val discardedByConfidence: Int = 0,
    val discardedAsNoise: Int = 0,
    val presentedFindings: Int = 0,
    val skippedStages: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val partial: Boolean
        get() = chunksFailed > 0 || warnings.isNotEmpty() || filesAnalysed < filesChanged
}

data class ReviewReport(
    val summary: String,
    val findings: List<ReviewFinding>,
    val questions: List<String>,
    val positivePoints: List<String>,
    val recommendation: MergeRecommendation,
    val understanding: ChangeUnderstanding? = null,
    val architecturalSignals: List<ArchitecturalSignal> = emptyList(),
    val opinion: TechnicalOpinion? = null,
    val quality: AnalysisQuality? = null
) {
    /** 🔴 Solicitaria ajuste. */
    val blockingFindings: List<ReviewFinding>
        get() = findings.filter { it.bucket() == ReviewBucket.BLOCKING }

    /** 🟡 Questionaria. */
    val questionFindings: List<ReviewFinding>
        get() = findings.filter { it.bucket() == ReviewBucket.QUESTION }

    /** 🔵 Sugestões. */
    val suggestionFindings: List<ReviewFinding>
        get() = findings.filter { it.bucket() == ReviewBucket.SUGGESTION }

    /** Dívida técnica não introduzida por este MR (item 19). */
    val preExistingFindings: List<ReviewFinding>
        get() = findings.filter { it.bucket() == ReviewBucket.PRE_EXISTING }
}

/** Seções de "Pontos que eu revisaria no MR" (item 22). Cada finding cai em exatamente uma. */
enum class ReviewBucket {
    BLOCKING,
    QUESTION,
    SUGGESTION,
    PRE_EXISTING
}

fun ReviewFinding.bucket(): ReviewBucket = when {
    scope == FindingScope.PRE_EXISTING -> ReviewBucket.PRE_EXISTING
    blocking -> ReviewBucket.BLOCKING
    type == FindingType.QUESTION || commentType == CommentType.QUESTION -> ReviewBucket.QUESTION
    else -> ReviewBucket.SUGGESTION
}
