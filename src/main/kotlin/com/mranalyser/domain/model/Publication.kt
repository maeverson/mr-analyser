package com.mranalyser.domain.model

/** SHAs que o GitLab exige para ancorar um comentário numa linha do diff. */
data class DiffRefs(
    val baseSha: String,
    val startSha: String,
    val headSha: String
)

/**
 * Linha do diff onde um comentário pode ser ancorado. Linha de contexto exige os dois
 * números; linha adicionada, só o novo.
 */
data class LineAnchor(
    val oldPath: String,
    val newPath: String,
    val oldLine: Int?,
    val newLine: Int?
)

/** Comentário candidato a publicação. O revisor escolhe quais publicar pelo [id]. */
data class ReviewComment(
    /** Número exibido ao revisor, a partir de 1. */
    val id: Int,
    /** Identificador estável entre execuções: evita publicar o mesmo comentário duas vezes. */
    val key: String,
    val type: CommentType,
    val title: String,
    val location: String,
    val body: String,
    /** Nulo quando o comentário vai para a thread geral do MR. */
    val anchor: LineAnchor? = null
)

/**
 * O que a análise propõe publicar no MR. Gravado ao lado do relatório para que a decisão de
 * publicar aconteça depois da leitura, sem refazer a análise.
 */
data class CommentPlan(
    val gitlabUrl: String,
    val projectPath: String,
    val mrIid: Long,
    val webUrl: String?,
    val diffRefs: DiffRefs?,
    val recommendation: MergeRecommendation,
    val blockingCount: Int,
    /** Análise com etapas que falharam não deve sustentar uma aprovação automática. */
    val partialAnalysis: Boolean,
    val comments: List<ReviewComment>
)

data class PublicationResult(
    val posted: List<ReviewComment> = emptyList(),
    /**
     * Subconjunto de [posted]: o GitLab recusou a posição da linha e o comentário foi para a
     * thread geral, com a localização no texto.
     */
    val fellBackToGeneral: List<ReviewComment> = emptyList(),
    val skippedAsDuplicate: List<ReviewComment> = emptyList(),
    val failed: List<Pair<ReviewComment, String>> = emptyList(),
    val approved: Boolean = false,
    val approvalError: String? = null
)
