package com.mranalyser.application.service

import com.mranalyser.application.review.ClassifiedFile
import com.mranalyser.domain.model.ChangeGroup

data class ReviewChunk(
    val index: Int,
    val files: List<ClassifiedFile>,
    val group: ChangeGroup = ChangeGroup.OTHER
)

/**
 * Agrupa os arquivos alterados em chunks **coesos** (item 7).
 *
 * A V1 quebrava apenas por tamanho, misturando domínio, persistência e integração no mesmo
 * prompt. Aqui o agrupamento primário é o grupo arquitetural, e o corte por tamanho acontece
 * dentro de cada grupo. Isso permite ao prompt focar nos aspectos que importam para aquela
 * camada e melhora a coerência dos findings.
 *
 * A ordem dos grupos coloca código de comportamento primeiro: quando o orçamento de contexto
 * acaba, o que se perde é build/documentação, não domínio.
 */
class ReviewChunker(
    private val maxDiffLines: Int,
    private val maxFileLines: Int
) {
    fun chunk(files: List<ClassifiedFile>): List<ReviewChunk> {
        if (files.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<ReviewChunk>()

        files.groupBy { it.group }
            .toList()
            .sortedBy { (group, _) -> GROUP_PRIORITY.indexOf(group).takeIf { it >= 0 } ?: GROUP_PRIORITY.size }
            .forEach { (group, groupFiles) ->
                splitBySize(groupFiles).forEach { batch ->
                    chunks += ReviewChunk(index = chunks.size + 1, files = batch, group = group)
                }
            }

        return chunks
    }

    private fun splitBySize(files: List<ClassifiedFile>): List<List<ClassifiedFile>> {
        val batches = mutableListOf<List<ClassifiedFile>>()
        var current = mutableListOf<ClassifiedFile>()
        var currentLines = 0

        files.forEach { file ->
            val lines = file.annotatedDiff.lineSequence().count()

            // Arquivo maior que o limite por arquivo recebe um chunk próprio: dividir seu diff
            // entre chunks separaria trechos que só fazem sentido juntos.
            if (lines > maxFileLines) {
                if (current.isNotEmpty()) {
                    batches += current.toList()
                    current = mutableListOf()
                    currentLines = 0
                }
                batches += listOf(file)
                return@forEach
            }

            if (currentLines + lines > maxDiffLines && current.isNotEmpty()) {
                batches += current.toList()
                current = mutableListOf()
                currentLines = 0
            }

            current += file
            currentLines += lines
        }

        if (current.isNotEmpty()) {
            batches += current.toList()
        }

        return batches
    }

    private companion object {
        val GROUP_PRIORITY = listOf(
            ChangeGroup.DOMAIN,
            ChangeGroup.APPLICATION,
            ChangeGroup.PERSISTENCE,
            ChangeGroup.INTEGRATION,
            ChangeGroup.API,
            ChangeGroup.MESSAGING,
            ChangeGroup.MIGRATION,
            ChangeGroup.CONTRACT,
            ChangeGroup.CONFIGURATION,
            ChangeGroup.OTHER,
            ChangeGroup.TEST,
            ChangeGroup.BUILD,
            ChangeGroup.DOCUMENTATION
        )
    }
}
