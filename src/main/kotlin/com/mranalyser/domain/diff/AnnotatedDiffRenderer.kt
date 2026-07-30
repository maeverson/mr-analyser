package com.mranalyser.domain.diff

import com.mranalyser.domain.model.DiffLine
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.LineOrigin
import com.mranalyser.domain.model.ParsedDiff

/**
 * Renderiza o diff em formato explícito para o LLM: cada linha traz sua origem
 * (`ADD`/`DEL`/`ctx`) e o número de linha real do arquivo.
 *
 * Resolve dois problemas: (a) o modelo comentava código removido como se existisse,
 * (b) o modelo tinha que adivinhar o número de linha, produzindo `file:line` inútil.
 */
class AnnotatedDiffRenderer(
    private val maxLinesPerFile: Int = 900
) {
    fun render(change: FileChange, parsed: ParsedDiff): String {
        val builder = StringBuilder()
        builder.appendLine("=== FILE: ${change.path} ===")
        builder.appendLine("status: ${statusOf(change)} | +${change.linesAdded} -${change.linesRemoved}")
        if (change.renamed && change.oldPath != change.newPath) {
            builder.appendLine("renamed from: ${change.oldPath}")
        }

        if (parsed.isEmpty) {
            builder.appendLine("(sem hunks interpretáveis — possivelmente arquivo binário ou diff vazio)")
            return builder.toString().trimEnd()
        }

        var emitted = 0
        var truncated = false

        for (hunk in parsed.hunks) {
            if (emitted >= maxLinesPerFile) {
                truncated = true
                break
            }
            builder.appendLine(hunk.header)
            for (line in hunk.lines) {
                if (emitted >= maxLinesPerFile) {
                    truncated = true
                    break
                }
                builder.appendLine(format(line))
                emitted++
            }
        }

        if (truncated) {
            builder.appendLine("... [diff truncado após $maxLinesPerFile linhas — não conclua ausência de código a partir deste corte]")
        }

        return builder.toString().trimEnd()
    }

    private fun format(line: DiffLine): String {
        val reference = when (line.origin) {
            LineOrigin.ADDED -> line.newLine?.toString().orEmpty()
            LineOrigin.REMOVED -> line.oldLine?.let { "($it)" }.orEmpty()
            LineOrigin.CONTEXT -> line.newLine?.toString().orEmpty()
        }
        return "${line.origin.tag} ${reference.padStart(6)} | ${line.content}"
    }

    private fun statusOf(change: FileChange): String = when {
        change.added -> "ADICIONADO"
        change.deleted -> "REMOVIDO"
        change.renamed -> "RENOMEADO"
        else -> "MODIFICADO"
    }
}
