package com.mranalyser.application.review

import com.mranalyser.application.port.KnowledgeBaseProvider
import com.mranalyser.application.port.KnowledgeDocument
import com.mranalyser.application.port.KnowledgeQuery
import org.slf4j.LoggerFactory

/** Status declarado no documento. Decide se uma divergência pode virar finding ou só pergunta. */
enum class KnowledgeStatus(val label: String) {
    ACCEPTED("ACEITO"),
    PROPOSED("PROPOSTO"),
    UNKNOWN("SEM STATUS")
}

/** Trecho já filtrado e classificado, pronto para o prompt. */
data class KnowledgeExcerpt(
    val source: String,
    val docType: String?,
    val status: KnowledgeStatus,
    val content: String,
    /** `true` quando o documento é do próprio repositório do MR, não um processo transversal. */
    val sameRepository: Boolean
)

data class KnowledgeBudget(
    val maxDocuments: Int = 4,
    val maxChars: Int = 3_500
)

/**
 * Recupera da base de conhecimento a documentação do repositório do MR.
 *
 * Uma consulta por MR, não por chunk: o objetivo é intenção e decisões já tomadas (contrato do
 * endpoint, ADR do padrão de integração), e isso é do MR inteiro. Consultar por chunk multiplicaria
 * chamadas e tokens no `num_ctx` do Ollama para trazer os mesmos documentos.
 *
 * Só entram documentos do próprio repositório ou que o citem nominalmente. Documento de outro
 * serviço, por mais parecido que seja o assunto, leva o modelo a cobrar uma regra que não vale aqui.
 */
class KnowledgeBaseRetriever(
    private val provider: KnowledgeBaseProvider?,
    private val budget: KnowledgeBudget = KnowledgeBudget()
) {
    private val logger = LoggerFactory.getLogger(KnowledgeBaseRetriever::class.java)

    suspend fun retrieve(
        projectPath: String,
        overview: MergeRequestOverview,
        diagnostics: AnalysisDiagnostics
    ): List<KnowledgeExcerpt> {
        // Desligada por configuração é escolha explícita, não degradação: não polui o relatório.
        if (provider == null) {
            return emptyList()
        }

        val repository = projectPath.trim('/').substringAfterLast('/').lowercase()
        if (repository.isBlank()) {
            diagnostics.skipStage(STAGE, "projeto do MR desconhecido")
            return emptyList()
        }
        val squad = projectPath.trim('/').split('/').getOrNull(1)?.lowercase()
        val text = buildQuery(repository, overview)

        // Com squad a busca concentra no time certo; se o nome do grupo do GitLab não for o
        // nome da squad na base, a busca filtrada volta vazia e repetimos sem o filtro.
        var result = provider.search(KnowledgeQuery(text, squad))
        if (result.failure == null && result.documents.none { it.mentions(repository) } && squad != null) {
            result = provider.search(KnowledgeQuery(text))
        }

        result.failure?.let { failure ->
            diagnostics.skipStage(STAGE, failure)
            return emptyList()
        }

        val excerpts = select(result.documents, repository)
        // Vários trechos do mesmo documento são úteis no prompt, mas a fonte se lista uma vez.
        diagnostics.knowledgeSources = excerpts.map { it.source }.distinct()
        if (excerpts.isEmpty()) {
            diagnostics.skipStage(STAGE, "nenhum documento do repositório '$repository' encontrado")
        } else {
            logger.info("Base de conhecimento: {} documento(s) de '{}'", excerpts.size, repository)
        }
        return excerpts
    }

    private fun select(documents: List<KnowledgeDocument>, repository: String): List<KnowledgeExcerpt> {
        val seen = mutableSetOf<String>()
        val candidates = documents
            .filter { it.mentions(repository) }
            .map { it.copy(source = canonicalSource(it.source), content = stripGeneratedPreamble(it.content)) }
            .filter { it.content.isNotBlank() }
            // A base indexa o mesmo documento sob mais de um caminho, e cada cópia ganha um
            // preâmbulo gerado diferente; sem o preâmbulo e com o caminho canônico, são iguais.
            .filter { seen.add(it.source + "|" + normalize(it.content).take(DEDUP_PREFIX)) }
            .map { document ->
                KnowledgeExcerpt(
                    source = document.source,
                    docType = document.docType,
                    status = statusOf(document.content),
                    content = document.content,
                    sameRepository = document.repo.equals(repository, ignoreCase = true)
                )
            }
            // Ordenação estável: dentro de cada grupo vale a relevância devolvida pela busca.
            // Ordenar por status cortava os contratos do endpoint (quase sempre "proposta") em
            // favor de ADRs aceitos genéricos — e o contrato é o que o review precisa conferir.
            // O status já governa o uso no prompt: proposto só gera pergunta.
            .sortedByDescending { it.sameRepository }

        var remaining = budget.maxChars
        val selected = mutableListOf<KnowledgeExcerpt>()
        for (excerpt in candidates) {
            if (selected.size >= budget.maxDocuments || remaining < MIN_USEFUL_CHARS) {
                break
            }
            val content = if (excerpt.content.length <= remaining) {
                excerpt.content
            } else {
                excerpt.content.take(remaining).substringBeforeLast('\n') + "\n[... trecho cortado]"
            }
            selected += excerpt.copy(content = content)
            remaining -= content.length
        }
        return selected
    }

    /**
     * Título e descrição carregam a intenção; o nome do repositório ancora a busca; os nomes de
     * arquivo trazem os termos de domínio (`companies`, `product-core-catalog`) que a descrição
     * costuma omitir.
     */
    private fun buildQuery(repository: String, overview: MergeRequestOverview): String {
        val fileTerms = overview.files
            .map { it.path.substringAfterLast('/').substringBefore('.') }
            .flatMap { it.split('-', '_') }
            .filter { it.length > 3 && it.lowercase() !in STOP_WORDS }
            .distinct()
            .take(MAX_FILE_TERMS)

        val raw = listOf(
            repository,
            overview.title,
            overview.description.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        ) + fileTerms

        return sanitize(raw.joinToString(" ")).take(MAX_QUERY_CHARS)
    }

    private fun KnowledgeDocument.mentions(repository: String): Boolean =
        repo.equals(repository, ignoreCase = true) || content.contains(repository, ignoreCase = true)

    private fun normalize(value: String): String = value.lowercase().replace(WHITESPACE, " ").trim()

    companion object {
        const val STAGE = "base de conhecimento"

        private const val MAX_QUERY_CHARS = 300
        private const val MAX_FILE_TERMS = 8
        private const val DEDUP_PREFIX = 400
        private const val MIN_USEFUL_CHARS = 300

        private val WHITESPACE = Regex("\\s+")

        /** Caracteres de sintaxe do Lucene: um `/` na consulta produziu erro 500 na base. */
        private val LUCENE_SPECIAL = Regex("""[+\-!(){}\[\]^"~*?:\\/&|<>=#`']""")

        private val STOP_WORDS = setOf(
            "index", "test", "spec", "module", "service", "controller", "adapter", "main",
            "readme", "package", "config", "types", "utils", "dto"
        )

        private val STATUS = Regex("""(?im)^\s*(?:#+\s*)?(?:\*\*)?status:?(?:\*\*)?:?[ \t]*(?:\n\s*)*([A-Za-zÀ-ú]+)""")

        /** `billing/billing-backoffice/-bff/x.md` e `billing/billing-backoffice-bff/x.md` são o mesmo. */
        fun canonicalSource(source: String): String = source.replace("/-", "-")

        /**
         * Remove o que a indexação acrescenta antes do documento: a frase "Este trecho ..." e a
         * seção "Cross-Repo Context", resumos gerados que gastam contexto e não são a fonte.
         */
        fun stripGeneratedPreamble(content: String): String {
            val lines = content.trim().lines().toMutableList()
            if (lines.firstOrNull()?.trimStart()?.startsWith("Este trecho") == true) {
                lines.removeAt(0)
            }
            val start = lines.indexOfFirst { it.trim().equals("## Cross-Repo Context", ignoreCase = true) }
            if (start >= 0) {
                val end = (start + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("#") } ?: lines.size
                repeat(end - start) { lines.removeAt(start) }
            }
            return lines.joinToString("\n").trim()
        }

        fun sanitize(text: String): String =
            text.replace(LUCENE_SPECIAL, " ").replace(WHITESPACE, " ").trim()

        fun statusOf(content: String): KnowledgeStatus {
            val value = STATUS.find(content)?.groupValues?.get(1)?.lowercase() ?: return KnowledgeStatus.UNKNOWN
            return when {
                value.startsWith("aceit") || value.startsWith("aprovad") || value.startsWith("accepted") ->
                    KnowledgeStatus.ACCEPTED
                value.startsWith("propost") || value.startsWith("proposed") || value.startsWith("rascunho") ->
                    KnowledgeStatus.PROPOSED
                else -> KnowledgeStatus.UNKNOWN
            }
        }
    }
}
