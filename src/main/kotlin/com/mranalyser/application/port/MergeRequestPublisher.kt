package com.mranalyser.application.port

import com.mranalyser.domain.model.DiffRefs
import com.mranalyser.domain.model.LineAnchor

interface MergeRequestPublisher {
    /** SHA do último commit do MR, para detectar alterações posteriores à análise. */
    suspend fun currentHeadSha(project: String, mrIid: Long): String?

    suspend fun existingNoteBodies(project: String, mrIid: Long): List<String>

    /** @return false quando o GitLab recusa a posição (linha fora do diff atual). */
    suspend fun postLineComment(project: String, mrIid: Long, body: String, anchor: LineAnchor, diffRefs: DiffRefs): Boolean

    suspend fun postGeneralComment(project: String, mrIid: Long, body: String)

    /** [headSha] faz o GitLab recusar a aprovação se o MR recebeu commits depois da análise. */
    suspend fun approve(project: String, mrIid: Long, headSha: String?)
}
