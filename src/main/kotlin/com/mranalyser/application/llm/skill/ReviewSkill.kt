package com.mranalyser.application.llm.skill

import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.domain.model.ChangeGroup
import org.slf4j.LoggerFactory

/**
 * Conhecimento de revisão específico do time/stack, mantido fora do código.
 *
 * Existe para modelos self-hosted menores: um 14B não sabe que, neste stack, `@Transactional`
 * em método privado não abre transação ou que todo evento sai pelo outbox. Sem isso ele gera
 * falso positivo onde o padrão já trata o caso e deixa passar o desvio do padrão. A skill
 * diz *o que procurar* e *o que não reportar*; nunca substitui as regras do system prompt.
 *
 * Aplicabilidade: todas as condições declaradas precisam casar (grupo E caminho E gatilho).
 * Condição não declarada não restringe.
 */
data class ReviewSkill(
    val name: String,
    val description: String = "",
    val content: String,
    val groups: Set<ChangeGroup> = emptySet(),
    val paths: List<String> = emptyList(),
    /** Trechos literais (case-insensitive) procurados no código/finding em análise. */
    val triggers: List<String> = emptyList(),
    val stages: Set<LlmPurpose> = DEFAULT_STAGES,
    /** Maior primeiro. Decide o que entra quando o orçamento de caracteres não comporta todas. */
    val priority: Int = 0
) {
    private val pathPatterns: List<Regex> = paths.map(PathGlob::toRegex)

    fun appliesTo(purpose: LlmPurpose, target: SkillTarget): Boolean {
        if (purpose !in stages) {
            return false
        }
        if (groups.isNotEmpty() && target.group !in groups) {
            return false
        }
        if (pathPatterns.isNotEmpty() && pathPatterns.none { it.matches(target.path) }) {
            return false
        }
        return triggers.isEmpty() || triggers.any { target.text.contains(it, ignoreCase = true) }
    }

    companion object {
        val DEFAULT_STAGES: Set<LlmPurpose> = setOf(
            LlmPurpose.LOCAL_REVIEW,
            LlmPurpose.VALIDATION,
            LlmPurpose.CROSS_FILE_REVIEW
        )
    }
}

/** Arquivo (ou finding sobre um arquivo) contra o qual a aplicabilidade das skills é avaliada. */
data class SkillTarget(
    val path: String,
    val group: ChangeGroup?,
    val text: String
)

/**
 * Seleciona e renderiza as skills de cada chamada.
 *
 * O orçamento em caracteres existe por causa do Ollama: `num_ctx` é dimensionado para caber
 * na VRAM, e o que as skills ocupam sai do espaço do diff. Skills que estouram o orçamento
 * são omitidas (com log), nunca cortadas no meio — instrução pela metade engana o modelo.
 */
class ReviewSkillCatalog(
    val skills: List<ReviewSkill> = emptyList(),
    private val maxChars: Int = DEFAULT_MAX_CHARS
) {
    private val logger = LoggerFactory.getLogger(ReviewSkillCatalog::class.java)

    fun select(purpose: LlmPurpose, targets: List<SkillTarget>): List<ReviewSkill> {
        if (skills.isEmpty() || targets.isEmpty()) {
            return emptyList()
        }

        val applicable = skills
            .filter { skill -> targets.any { skill.appliesTo(purpose, it) } }
            .sortedByDescending { it.priority }

        var remaining = maxChars
        return applicable.filter { skill ->
            val cost = skill.content.length + skill.description.length + HEADER_OVERHEAD
            if (cost > remaining) {
                logger.warn(
                    "Skill '{}' omitida em {}: orçamento de {} caracteres esgotado. " +
                        "Aumente MR_ANALYSER_SKILLS_MAX_CHARS ou ajuste a priority.",
                    skill.name,
                    purpose.label,
                    maxChars
                )
                false
            } else {
                remaining -= cost
                true
            }
        }
    }

    /** Bloco pronto para o prompt, ou string vazia quando nenhuma skill se aplica. */
    fun render(purpose: LlmPurpose, targets: List<SkillTarget>): String {
        val selected = select(purpose, targets)
        if (selected.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("## SKILLS DO TIME (diretrizes de revisão para este stack)")
            appendLine(PREAMBLE)
            selected.forEach { skill ->
                appendLine()
                append("### ${skill.name}")
                if (skill.description.isNotBlank()) {
                    append(" — ${skill.description}")
                }
                appendLine()
                appendLine(skill.content.trim())
            }
        }.trimEnd()
    }

    companion object {
        val EMPTY = ReviewSkillCatalog()

        /** ~1,5 mil tokens: cabe no `num_ctx` padrão do Ollama sem sacrificar o maior chunk. */
        const val DEFAULT_MAX_CHARS = 6_000

        private const val HEADER_OVERHEAD = 16

        private val PREAMBLE = """
Guidance maintained by the reviewers of this codebase, selected for the files in this request.
Use it to know what matters in this stack and which patterns are already safe. It refines WHAT
to look for; it NEVER relaxes the system rules (evidence, anti-hallucination, output format).
Apply an item only when the code shown actually matches it. A skill is not evidence: a finding
still needs file, line and what the code does.
""".trim()
    }
}
