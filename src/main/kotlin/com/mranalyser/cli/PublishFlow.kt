package com.mranalyser.cli

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.mranalyser.application.usecase.PublishReviewUseCase
import com.mranalyser.domain.model.CommentPlan
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewComment
import com.mranalyser.infrastructure.render.CommentPlanBuilder

/** Opções comuns a `publish` e a `analyse --publish`. */
class PublishOptions : OptionGroup(name = "Publicação no MR") {
    val select by option(
        "--select",
        help = "Comentários a publicar: 1,3-5 | all | none. Sem a opção, pergunta interativamente"
    )
    val approve by option("--approve", help = "Aprova o MR depois de publicar").flag(default = false)
    val assumeYes by option(
        "--yes", "-y",
        help = "Não pergunta nada (exige --select). Recusa aprovar análise parcial ou com bloqueadores"
    ).flag(default = false)
    val dryRun by option("--dry-run", help = "Mostra o que seria publicado, sem publicar").flag(default = false)
}

/**
 * Conduz a escolha do revisor e a publicação. Nada é publicado sem seleção explícita, e a
 * aprovação exige `--approve`; no modo não interativo, só é aceita quando a análise está
 * completa e sem bloqueadores.
 */
class PublishFlow(
    private val useCase: PublishReviewUseCase,
    private val echo: (String) -> Unit,
    private val readInput: (String) -> String?
) {
    suspend fun run(plan: CommentPlan, options: PublishOptions) {
        echo("")
        echo("COMENTÁRIOS DISPONÍVEIS PARA O MR !${plan.mrIid} (${plan.projectPath})")
        plan.webUrl?.let { echo(it) }
        echo("")
        plan.comments.forEach { echo(listingLine(it)) }

        val selectedIds = selectComments(plan, options) ?: return
        val approve = options.approve && approvalAllowed(plan, options.assumeYes)

        if (selectedIds.isEmpty() && !approve) {
            echo("Nada publicado.")
            return
        }

        if (useCase.headChangedSinceAnalysis(plan)) {
            echo("⚠️  O MR recebeu commits depois da análise: linhas e parecer podem não valer mais.")
            if (options.assumeYes || !confirm("Publicar mesmo assim?")) {
                echo("Nada publicado. Rode a análise de novo.")
                return
            }
        }

        val selected = plan.comments.filter { it.id in selectedIds }
        val action = buildList {
            if (selected.isNotEmpty()) add("publicar ${selected.size} comentário(s) [${selectedIds.sorted().joinToString(",")}]")
            if (approve) add("aprovar o MR")
        }.joinToString(" e ")

        if (options.dryRun) {
            echo("")
            echo("--dry-run: iria $action.")
            selected.forEach {
                echo("")
                echo("── #${it.id} ${if (it.anchor != null) "na linha ${it.location}" else "thread geral"}")
                echo(it.body)
            }
            return
        }

        if (!options.assumeYes && !confirm("Confirma $action no MR !${plan.mrIid}?")) {
            echo("Nada publicado.")
            return
        }

        val result = useCase.execute(plan, selectedIds, approve)

        echo("")
        if (result.posted.isNotEmpty()) {
            echo("✅ ${result.posted.size} comentário(s) publicado(s).")
        }
        result.fellBackToGeneral.forEach {
            echo("   #${it.id}: linha ${it.location} fora do diff atual; publicado na thread geral.")
        }
        if (result.skippedAsDuplicate.isNotEmpty()) {
            echo("↩️  Já publicado(s) antes, ignorado(s): ${result.skippedAsDuplicate.joinToString(", ") { "#${it.id}" }}")
        }
        result.failed.forEach { (comment, reason) -> echo("❌ #${comment.id} não publicado: $reason") }
        if (approve) {
            echo(if (result.approved) "✅ MR aprovado." else "❌ Não aprovado: ${result.approvalError}")
        }
    }

    /** @return null quando o revisor desiste. */
    private fun selectComments(plan: CommentPlan, options: PublishOptions): Set<Int>? {
        val available = plan.comments.map { it.id }.toSet()
        options.select?.let { return CommentSelection.parse(it, available) }
        if (options.assumeYes) {
            throw IllegalArgumentException("--yes exige --select (ex.: --select all ou --select none)")
        }
        if (available.isEmpty()) {
            return emptySet()
        }

        echo("")
        echo("Escolha: números (1,3-5), all, none (padrão) · 'ver N' mostra o texto completo")
        while (true) {
            val answer = readInput("Publicar quais? ") ?: return null
            val view = Regex("""^\s*(?:ver|v)\s+(\d+)\s*$""", RegexOption.IGNORE_CASE).matchEntire(answer)
            if (view != null) {
                val comment = plan.comments.firstOrNull { it.id == view.groupValues[1].toInt() }
                echo(comment?.let { "\n${it.body}\n" } ?: "comentário inexistente")
                continue
            }
            try {
                return CommentSelection.parse(answer, available)
            } catch (exception: IllegalArgumentException) {
                echo(exception.message.orEmpty())
            }
        }
    }

    private fun approvalAllowed(plan: CommentPlan, assumeYes: Boolean): Boolean {
        val concerns = buildList {
            if (plan.partialAnalysis) add("a análise foi parcial (etapas do modelo falharam)")
            if (plan.blockingCount > 0) add("há ${plan.blockingCount} bloqueador(es)")
            if (plan.recommendation == MergeRecommendation.REQUEST_CHANGES) add("o parecer é solicitar mudanças")
        }
        if (concerns.isEmpty()) {
            return true
        }
        echo("")
        echo("⚠️  Aprovação não recomendada: ${concerns.joinToString("; ")}.")
        if (assumeYes) {
            echo("Aprovação não feita no modo --yes. Rode sem --yes para decidir manualmente.")
            return false
        }
        return readInput("Digite 'aprovar' para aprovar mesmo assim: ")?.trim()?.lowercase() == "aprovar"
    }

    private fun confirm(question: String): Boolean =
        readInput("$question [s/N] ")?.trim()?.lowercase() in setOf("s", "sim", "y", "yes")

    private fun listingLine(comment: ReviewComment): String {
        val where = if (comment.anchor != null) comment.location else "${comment.location} · thread geral"
        return "%3d. %-16s %s — %s".format(comment.id, CommentPlanBuilder.labelOf(comment.type), where, comment.title)
    }
}
