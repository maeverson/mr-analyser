package com.mranalyser.application.llm.prompt

import com.mranalyser.domain.security.SecretRedactor
import com.mranalyser.application.port.RelatedFileContext
import com.mranalyser.application.review.ExistingDiscussion
import com.mranalyser.application.review.MergeRequestOverview
import com.mranalyser.domain.model.ArchitecturalSignal
import com.mranalyser.domain.model.ChangeUnderstanding

/**
 * Renderização dos blocos de contexto reutilizados pelos prompts. Centralizado para que a
 * sanitização de segredos e os limites de tamanho sejam aplicados em um único lugar.
 */
class PromptSections(
    private val redactor: SecretRedactor = SecretRedactor(),
    private val maxDescriptionChars: Int = 4_000,
    private val maxCommits: Int = 30,
    private val maxDiscussions: Int = 40
) {
    fun mergeRequestHeader(overview: MergeRequestOverview): String = buildString {
        appendLine("## MERGE REQUEST")
        appendLine("iid: !${overview.iid}")
        appendLine("title: ${clean(overview.title)}")
        appendLine("author: ${overview.author}")
        appendLine("branches: ${overview.sourceBranch} -> ${overview.targetBranch}")
        if (overview.labels.isNotEmpty()) {
            appendLine("labels: ${overview.labels.joinToString(", ")}")
        }
        appendLine("description:")
        appendLine(clean(overview.description.orEmpty()).take(maxDescriptionChars).ifBlank { "(vazia)" })
    }.trimEnd()

    fun changedFilesTable(overview: MergeRequestOverview): String = buildString {
        appendLine("## ARQUIVOS ALTERADOS NESTE MR (${overview.files.size})")
        overview.files.forEach { file ->
            val status = when {
                file.change.added -> "novo"
                file.change.deleted -> "removido"
                file.change.renamed -> "renomeado"
                else -> "modificado"
            }
            appendLine(
                "- ${file.path} [grupo=${file.group.name}, $status, +${file.change.linesAdded}/-${file.change.linesRemoved}]"
            )
        }
    }.trimEnd()

    fun commits(overview: MergeRequestOverview): String {
        if (overview.commits.isEmpty()) {
            return "## COMMITS\n(nenhum commit retornado)"
        }
        return buildString {
            appendLine("## COMMITS")
            overview.commits.take(maxCommits).forEach {
                appendLine("- ${it.sha.take(10)}: ${clean(it.message).lineSequence().first()}")
            }
            if (overview.commits.size > maxCommits) {
                appendLine("- ... (${overview.commits.size - maxCommits} commits omitidos)")
            }
        }.trimEnd()
    }

    /** Item 31: informa também se a discussão já está resolvida e a que linha se refere. */
    fun discussions(discussions: List<ExistingDiscussion>): String {
        if (discussions.isEmpty()) {
            return """
## DISCUSSÕES EXISTENTES
(nenhuma)
""".trim()
        }

        return buildString {
            appendLine("## DISCUSSÕES EXISTENTES")
            appendLine(
                "Regra: NUNCA sugira novamente um ponto que já foi levantado aqui. " +
                    "Se a discussão está RESOLVIDA, considere o ponto tratado, a menos que o diff mostre o contrário."
            )
            discussions.take(maxDiscussions).forEach { discussion ->
                val status = if (discussion.resolved) "RESOLVIDA" else "ABERTA"
                val location = listOfNotNull(discussion.file, discussion.line?.toString())
                    .joinToString(":")
                    .ifBlank { "geral" }
                appendLine("- [$status] ($location) ${discussion.author}: ${clean(discussion.body).take(500)}")
            }
        }.trimEnd()
    }

    fun relatedContext(contexts: List<RelatedFileContext>): String {
        if (contexts.isEmpty()) {
            return """
## CONTEXTO RELACIONADO DO REPOSITÓRIO
(indisponível)
ATENÇÃO: sem este contexto você NÃO pode concluir que retry, timeout, transação, validação,
autorização ou idempotência estão ausentes. Nesses casos, pergunte ao autor.
""".trim()
        }

        return buildString {
            appendLine("## CONTEXTO RELACIONADO DO REPOSITÓRIO")
            appendLine(
                "Arquivos não alterados por este MR, trazidos para reduzir falso positivo. " +
                    "São RECORTES: a ausência de um trecho aqui não prova que ele não existe no arquivo."
            )
            contexts.groupBy { it.referencePath }.forEach { (reference, group) ->
                appendLine()
                appendLine("### Relacionados a $reference")
                group.forEach { context ->
                    appendLine()
                    appendLine("#### ${context.relatedPath} (${context.kind.label})")
                    if (context.reason.isNotBlank()) {
                        appendLine("motivo: ${context.reason}")
                    }
                    appendLine("```")
                    appendLine(clean(context.content))
                    appendLine("```")
                }
            }
        }.trimEnd()
    }

    fun understanding(understanding: ChangeUnderstanding?): String {
        if (understanding == null) {
            return ""
        }
        return buildString {
            appendLine("## ENTENDIMENTO DA ALTERAÇÃO (produzido na etapa anterior)")
            appendLine("intenção: ${understanding.intent}")
            appendLine(understanding.narrative)
            appendList("mudanças de comportamento", understanding.behaviourChanges)
            appendList("novos caminhos de execução", understanding.newExecutionPaths)
            appendList("contratos alterados", understanding.contractChanges)
            appendList("dependências afetadas", understanding.affectedDependencies)
            appendLine("blast radius: ${understanding.blastRadius.name}")
            understanding.intentDiscrepancy?.let { appendLine("discrepância intenção/implementação: $it") }
        }.trimEnd()
    }

    fun architecturalSignals(signals: List<ArchitecturalSignal>): String {
        if (signals.isEmpty()) {
            return ""
        }
        return buildString {
            appendLine("## MUDANÇAS ESTRUTURAIS DETECTADAS ESTATICAMENTE")
            signals.forEach { signal ->
                appendLine("- [${signal.kind.name}] ${signal.detail}${signal.file?.let { " ($it)" }.orEmpty()}")
            }
        }.trimEnd()
    }

    fun clean(input: String): String = redactor.redact(input)

    private fun StringBuilder.appendList(label: String, values: List<String>) {
        if (values.isEmpty()) {
            return
        }
        appendLine("$label:")
        values.forEach { appendLine("- $it") }
    }
}
