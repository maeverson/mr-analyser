package com.mranalyser.domain.rule

import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.FindingOrigin
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

/**
 * Marcadores de trabalho pendente adicionados no diff.
 *
 * Mantido como INFO agregado por arquivo e **sem comentário de GitLab**: pedir vínculo com issue
 * para cada TODO é o tipo de interrupção que o item 18 pede para evitar. Marcadores que já citam
 * uma issue são ignorados, porque nesse caso o rastreamento já existe.
 */
class TodoRule : ReviewRule {
    override val name: String = "pending-work-marker"

    override fun supports(context: RuleContext): Boolean =
        !context.change.deleted && !context.change.generated

    override fun analyse(context: RuleContext): List<ReviewFinding> {
        val markers = context.parsedDiff.addedLines
            .mapNotNull { line ->
                val match = MARKER.find(line.content) ?: return@mapNotNull null
                if (ISSUE_REFERENCE.containsMatchIn(line.content)) {
                    return@mapNotNull null
                }
                line.newLine to match.groupValues[1].uppercase()
            }

        if (markers.isEmpty()) {
            return emptyList()
        }

        val kinds = markers.map { it.second }.distinct()
        val hasUrgentMarker = kinds.any { it == "FIXME" || it == "HACK" || it == "XXX" }

        return listOf(
            ReviewFinding(
                severity = if (hasUrgentMarker) Severity.LOW else Severity.INFO,
                category = ReviewCategory.MAINTAINABILITY,
                type = FindingType.SUGGESTION,
                file = context.change.path,
                line = markers.first().first,
                title = "Marcador ${kinds.joinToString("/")} sem issue vinculada",
                description = "Foram adicionados ${markers.size} marcador(es) de trabalho pendente " +
                    "sem referência a issue.",
                evidence = "${context.change.path}: linha(s) ${markers.mapNotNull { it.first }.joinToString(", ")}.",
                failureScenario = null,
                impact = "Dívida técnica sem rastreamento formal tende a permanecer indefinidamente.",
                recommendation = "Vincular o marcador a uma issue ou resolvê-lo neste MR.",
                suggestedComment = null,
                commentType = CommentType.OBSERVATION,
                origin = FindingOrigin.STATIC_RULE,
                confidence = 0.90
            )
        )
    }

    private companion object {
        val MARKER = Regex("""(?i)\b(TODO|FIXME|HACK|XXX)\b""")
        val ISSUE_REFERENCE = Regex("""(?i)(#\d+|![0-9]+|[A-Z][A-Z0-9]+-\d+|issues?/\d+|https?://)""")
    }
}
