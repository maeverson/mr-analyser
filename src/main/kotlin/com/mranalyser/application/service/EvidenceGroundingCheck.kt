package com.mranalyser.application.service

import com.mranalyser.domain.model.FindingOrigin
import com.mranalyser.domain.model.ReviewFinding

/**
 * Descarta findings cuja evidência cita código que não existe no material enviado ao modelo.
 *
 * Existe porque modelos locais completam lacunas com o exemplo do prompt: num adapter TypeScript
 * que só faz GET, três findings citavam `repository.save()` — o `save()` do exemplo, não do MR.
 * A validação por LLM não pegou, porque o mesmo modelo acha a própria invenção plausível. Aqui a
 * checagem é textual e determinística: método chamado ou arquivo citado precisa aparecer no diff
 * ou no contexto relacionado.
 *
 * Em evidência, cenário de falha e descrição, citação inexistente invalida o finding inteiro.
 * Em comentário sugerido e recomendação, que são redação, só o campo é removido.
 */
class EvidenceGroundingCheck {

    data class Result(
        val findings: List<ReviewFinding>,
        val discarded: List<ReviewFinding>
    )

    /**
     * @param corpus todo o material visto pelo modelo (diffs e contexto relacionado).
     * @param fileCorpora conteúdo por arquivo, indexado pelo caminho. Quando a evidência cita
     *   arquivos, os métodos citados precisam estar **nesses** arquivos: um trecho do teste
     *   atribuído ao adapter passava na checagem global e virava evidência falsa.
     */
    fun apply(
        findings: List<ReviewFinding>,
        corpus: String,
        knownPaths: Collection<String>,
        fileCorpora: Map<String, String> = emptyMap()
    ): Result {
        val identifiers = IDENTIFIER.findAll(corpus).map { it.value }.toHashSet()
        val fileNames = knownPaths.map { it.substringAfterLast('/') }.toHashSet()
        val identifiersByFile = fileCorpora.entries.associate { (path, content) ->
            path.substringAfterLast('/') to IDENTIFIER.findAll(content).map { it.value }.toHashSet()
        }

        fun grounded(text: String?): Boolean = text == null || missingReferences(text, identifiers, fileNames).isEmpty()

        /** Métodos citados na evidência existem em algum dos arquivos que ela cita. */
        fun attributed(evidence: String?): Boolean {
            if (evidence == null || identifiersByFile.isEmpty()) {
                return true
            }
            val cited = FILE_REFERENCE.findAll(evidence)
                .mapNotNull { identifiersByFile[it.groupValues[1].substringAfterLast('/')] }
                .toList()
            if (cited.isEmpty()) {
                return true
            }
            val scope = cited.flatten().toHashSet()
            return CALL.findAll(evidence)
                .map { it.groupValues[1].substringAfterLast('.') }
                .filter { it.length >= MIN_NAME_LENGTH }
                .all { it in scope }
        }

        val kept = mutableListOf<ReviewFinding>()
        val discarded = mutableListOf<ReviewFinding>()

        findings.forEach { finding ->
            if (finding.origin == FindingOrigin.STATIC_RULE) {
                kept += finding
                return@forEach
            }
            if (!grounded(finding.evidence) || !grounded(finding.failureScenario) ||
                !grounded(finding.description) || !attributed(finding.evidence)
            ) {
                discarded += finding
                return@forEach
            }
            kept += finding.copy(
                suggestedComment = finding.suggestedComment?.takeIf(::grounded),
                recommendation = finding.recommendation?.takeIf(::grounded)
            )
        }

        return Result(kept, discarded)
    }

    /** Referências de código no texto que não existem no material. Exposto para teste. */
    fun missingReferences(text: String, identifiers: Set<String>, fileNames: Set<String>): List<String> {
        val calls = CALL.findAll(text)
            .map { it.groupValues[1].substringAfterLast('.') }
            .filter { it.length >= MIN_NAME_LENGTH && it !in identifiers }

        val files = FILE_REFERENCE.findAll(text)
            .map { it.groupValues[1].substringAfterLast('/') }
            .filter { it !in fileNames }

        return (calls + files).distinct().toList()
    }

    private companion object {
        const val MIN_NAME_LENGTH = 3

        val IDENTIFIER = Regex("""[A-Za-z_$][\w$]*""")

        /**
         * `nome(` ou `obj.metodo(` colado ao parêntese — prosa "falha (rede)" tem espaço, e o
         * plural "arquivo(s)" é excluído pelo lookahead.
         */
        val CALL = Regex("""(?<![\w.$])([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\((?![a-z]{1,2}\))""")

        val FILE_REFERENCE = Regex(
            """([\w./-]+\.(?:kt|kts|java|scala|groovy|ts|tsx|js|jsx|mjs|py|go|rb|cs|sql|ya?ml|json|xml|properties|gradle|proto))\b"""
        )
    }
}
