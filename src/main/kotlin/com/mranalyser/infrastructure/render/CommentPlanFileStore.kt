package com.mranalyser.infrastructure.render

import com.mranalyser.domain.model.CommentPlan
import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.DiffRefs
import com.mranalyser.domain.model.LineAnchor
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewComment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name

/**
 * Grava o plano de comentários ao lado do relatório (`mr-132-<timestamp>.comments.json`),
 * para que `publish` rode depois da leitura do relatório sem refazer a análise.
 */
class CommentPlanFileStore(
    private val outputDirectory: Path = Paths.get("reports")
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun writeNextTo(reportPath: Path, plan: CommentPlan): Path {
        val baseName = reportPath.name.substringBeforeLast('.')
        val planPath = reportPath.resolveSibling("$baseName$SUFFIX")
        Files.writeString(planPath, json.encodeToString(PlanFile.serializer(), PlanFile.from(plan)))
        return planPath
    }

    fun read(path: Path): CommentPlan =
        json.decodeFromString(PlanFile.serializer(), Files.readString(path)).toDomain()

    /** O timestamp no nome ordena lexicograficamente: o último é o mais recente. */
    fun findLatest(mrIid: Long, projectPath: String? = null): Path? {
        if (!Files.isDirectory(outputDirectory)) {
            return null
        }
        val candidates = Files.list(outputDirectory).use { stream ->
            stream.filter { it.name.startsWith("mr-$mrIid-") && it.name.endsWith(SUFFIX) }.toList()
        }
        return candidates
            .sortedByDescending { it.name }
            .firstOrNull { projectPath == null || runCatching { read(it).projectPath == projectPath }.getOrDefault(false) }
    }

    @Serializable
    private data class PlanFile(
        val gitlabUrl: String,
        val projectPath: String,
        val mrIid: Long,
        val webUrl: String? = null,
        val diffRefs: DiffRefsFile? = null,
        val recommendation: String,
        val blockingCount: Int,
        val partialAnalysis: Boolean,
        val comments: List<CommentFile>
    ) {
        fun toDomain() = CommentPlan(
            gitlabUrl = gitlabUrl,
            projectPath = projectPath,
            mrIid = mrIid,
            webUrl = webUrl,
            diffRefs = diffRefs?.let { DiffRefs(it.baseSha, it.startSha, it.headSha) },
            recommendation = MergeRecommendation.valueOf(recommendation),
            blockingCount = blockingCount,
            partialAnalysis = partialAnalysis,
            comments = comments.map { it.toDomain() }
        )

        companion object {
            fun from(plan: CommentPlan) = PlanFile(
                gitlabUrl = plan.gitlabUrl,
                projectPath = plan.projectPath,
                mrIid = plan.mrIid,
                webUrl = plan.webUrl,
                diffRefs = plan.diffRefs?.let { DiffRefsFile(it.baseSha, it.startSha, it.headSha) },
                recommendation = plan.recommendation.name,
                blockingCount = plan.blockingCount,
                partialAnalysis = plan.partialAnalysis,
                comments = plan.comments.map { CommentFile.from(it) }
            )
        }
    }

    @Serializable
    private data class DiffRefsFile(val baseSha: String, val startSha: String, val headSha: String)

    @Serializable
    private data class AnchorFile(val oldPath: String, val newPath: String, val oldLine: Int? = null, val newLine: Int? = null)

    @Serializable
    private data class CommentFile(
        val id: Int,
        val key: String,
        val type: String,
        val title: String,
        val location: String,
        val body: String,
        val anchor: AnchorFile? = null
    ) {
        fun toDomain() = ReviewComment(
            id = id,
            key = key,
            type = CommentType.valueOf(type),
            title = title,
            location = location,
            body = body,
            anchor = anchor?.let { LineAnchor(it.oldPath, it.newPath, it.oldLine, it.newLine) }
        )

        companion object {
            fun from(comment: ReviewComment) = CommentFile(
                id = comment.id,
                key = comment.key,
                type = comment.type.name,
                title = comment.title,
                location = comment.location,
                body = comment.body,
                anchor = comment.anchor?.let { AnchorFile(it.oldPath, it.newPath, it.oldLine, it.newLine) }
            )
        }
    }

    companion object {
        const val SUFFIX = ".comments.json"
    }
}
