package com.mranalyser.infrastructure.skill

import com.mranalyser.application.llm.skill.ReviewSkill
import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.domain.model.ChangeGroup
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Lê skills de um arquivo Markdown (ou de todos os `.md` de um diretório).
 *
 * Cada skill começa com um frontmatter YAML entre linhas `---` contendo ao menos `name`;
 * o corpo vai até o próximo frontmatter. Texto antes do primeiro frontmatter é documentação
 * do arquivo e não é enviado ao modelo. Um `---` cujo bloco seguinte não é um frontmatter
 * válido é tratado como conteúdo.
 *
 * Erros de formato nunca derrubam a análise: a skill inválida é ignorada com `WARN`.
 */
class ReviewSkillFileLoader {
    private val logger = LoggerFactory.getLogger(ReviewSkillFileLoader::class.java)

    fun load(path: File): List<ReviewSkill> {
        val files = when {
            path.isDirectory -> path.listFiles { file -> file.isFile && file.extension == "md" }
                ?.sortedBy { it.name }
                .orEmpty()

            path.isFile -> listOf(path)
            else -> {
                logger.warn("Arquivo de skills não encontrado: {}", path.path)
                return emptyList()
            }
        }

        return files.flatMap { file ->
            runCatching { parse(file.readText(), file.name) }
                .onFailure { logger.warn("Falha ao ler skills de {}: {}", file.path, it.message) }
                .getOrDefault(emptyList())
        }
    }

    fun parse(text: String, source: String = "<inline>"): List<ReviewSkill> {
        val lines = text.lines()
        val skills = mutableListOf<ReviewSkill>()
        var header: Map<String, Any?>? = null
        val body = StringBuilder()
        var index = 0

        fun flush() {
            header?.let { toSkill(it, body.toString(), source) }?.let(skills::add)
            body.clear()
        }

        while (index < lines.size) {
            val line = lines[index]
            val parsed = if (line.trim() == DELIMITER) frontmatterAt(lines, index) else null

            if (parsed != null) {
                flush()
                header = parsed.first
                index = parsed.second + 1
                continue
            }
            if (header != null) {
                body.appendLine(line)
            }
            index++
        }
        flush()

        return skills
    }

    /** Frontmatter válido iniciando em [start], e o índice da linha que o fecha. */
    @Suppress("UNCHECKED_CAST")
    private fun frontmatterAt(lines: List<String>, start: Int): Pair<Map<String, Any?>, Int>? {
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim() == DELIMITER } ?: return null
        val yaml = lines.subList(start + 1, end).joinToString("\n")
        val map = runCatching { Yaml().load<Any>(yaml) as? Map<String, Any?> }.getOrNull() ?: return null
        return if (map["name"]?.toString().isNullOrBlank()) null else map to end
    }

    private fun toSkill(header: Map<String, Any?>, body: String, source: String): ReviewSkill? {
        val name = header["name"].toString().trim()
        val content = body.trim()
        if (content.isEmpty()) {
            logger.warn("Skill '{}' em {} sem conteúdo; ignorada.", name, source)
            return null
        }

        return ReviewSkill(
            name = name,
            description = header["description"]?.toString()?.trim().orEmpty(),
            content = content,
            groups = enumList(header["groups"], name, source) { ChangeGroup.valueOf(it) }.toSet(),
            paths = stringList(header["paths"]),
            triggers = stringList(header["triggers"]),
            stages = enumList(header["stages"], name, source) { stage(it) }.toSet()
                .ifEmpty { ReviewSkill.DEFAULT_STAGES },
            priority = (header["priority"] as? Number)?.toInt() ?: 0
        )
    }

    /** Aceita tanto o nome do enum (`LOCAL_REVIEW`) quanto o rótulo (`local-review`). */
    private fun stage(raw: String): LlmPurpose =
        LlmPurpose.entries.firstOrNull { it.label.equals(raw, ignoreCase = true) }
            ?: LlmPurpose.valueOf(raw.replace('-', '_'))

    private fun stringList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        else -> listOf(value.toString().trim()).filter(String::isNotEmpty)
    }

    private fun <T> enumList(value: Any?, skill: String, source: String, convert: (String) -> T): List<T> =
        stringList(value).mapNotNull { raw ->
            runCatching { convert(raw.uppercase()) }
                .onFailure { logger.warn("Valor '{}' desconhecido na skill '{}' ({}); ignorado.", raw, skill, source) }
                .getOrNull()
        }

    private companion object {
        const val DELIMITER = "---"
    }
}
