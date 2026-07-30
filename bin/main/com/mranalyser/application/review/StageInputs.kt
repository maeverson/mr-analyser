package com.mranalyser.application.review

import com.mranalyser.application.port.RelatedFileContext
import com.mranalyser.domain.model.ArchitecturalSignal
import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.ChangeUnderstanding
import com.mranalyser.domain.model.Commit
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest
import com.mranalyser.domain.model.ReviewFinding

/** Metadados do MR compartilhados por todas as etapas. */
data class MergeRequestOverview(
    val iid: Long,
    val title: String,
    val description: String?,
    val author: String,
    val sourceBranch: String,
    val targetBranch: String,
    val labels: List<String>,
    val commits: List<Commit>,
    val files: List<ClassifiedFile>
) {
    companion object {
        fun from(mergeRequest: MergeRequest, files: List<ClassifiedFile>): MergeRequestOverview =
            MergeRequestOverview(
                iid = mergeRequest.iid,
                title = mergeRequest.title,
                description = mergeRequest.description,
                author = mergeRequest.author.name,
                sourceBranch = mergeRequest.sourceBranch,
                targetBranch = mergeRequest.targetBranch,
                labels = mergeRequest.labels,
                commits = mergeRequest.commits,
                files = files
            )
    }
}

/** Arquivo alterado com seu grupo arquitetural e diff já interpretado. */
data class ClassifiedFile(
    val change: FileChange,
    val group: ChangeGroup,
    val annotatedDiff: String
) {
    val path: String get() = change.path
}

/** Discussão existente, achatada com posição e status de resolução (item 31). */
data class ExistingDiscussion(
    val author: String,
    val body: String,
    val file: String?,
    val line: Int?,
    val resolved: Boolean
)

/** Entrada da etapa de review local (por chunk). */
data class ChunkReviewInput(
    val overview: MergeRequestOverview,
    val chunkIndex: Int,
    val chunkCount: Int,
    val group: ChangeGroup,
    val files: List<ClassifiedFile>,
    val relatedContext: List<RelatedFileContext>,
    val discussions: List<ExistingDiscussion>,
    val understanding: ChangeUnderstanding?,
    val architecturalSignals: List<ArchitecturalSignal>
)

/** Entrada da etapa de validação de findings (item 8). */
data class ValidationInput(
    val overview: MergeRequestOverview,
    val understanding: ChangeUnderstanding?,
    val candidates: List<ReviewFinding>,
    val relatedContext: List<RelatedFileContext>,
    val discussions: List<ExistingDiscussion>,
    /** Recorte do diff em volta de cada finding, indexado pelo identificador do candidato. */
    val evidenceExcerpts: Map<String, String>
)

/** Entrada da etapa de review cross-file (item 27). */
data class CrossFileReviewInput(
    val overview: MergeRequestOverview,
    val understanding: ChangeUnderstanding?,
    val architecturalSignals: List<ArchitecturalSignal>,
    val confirmedFindings: List<ReviewFinding>,
    val relationEdges: List<FileRelation>,
    val addedLinesByFile: Map<String, String>,
    val relatedContext: List<RelatedFileContext>
)

/** Relação estrutural entre dois arquivos do MR, usada para raciocínio cross-file. */
data class FileRelation(
    val from: String,
    val to: String,
    val reason: String
)

/** Entrada da etapa de parecer final (itens 21 e 23). */
data class FinalAssessmentInput(
    val overview: MergeRequestOverview,
    val understanding: ChangeUnderstanding?,
    val architecturalSignals: List<ArchitecturalSignal>,
    val findings: List<ReviewFinding>,
    val positivePoints: List<String>,
    val openQuestions: List<String>,
    val degraded: Boolean,
    val degradationReasons: List<String>
)
