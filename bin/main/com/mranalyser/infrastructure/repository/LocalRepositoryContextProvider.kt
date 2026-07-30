package com.mranalyser.infrastructure.repository

import com.mranalyser.application.port.ChangedFileQuery
import com.mranalyser.application.port.ContextBudget
import com.mranalyser.application.port.ContextRetrievalRequest
import com.mranalyser.application.port.RelatedContextKind
import com.mranalyser.application.port.RelatedFileContext
import com.mranalyser.application.port.RepositoryContextProvider
import com.mranalyser.application.port.RepositoryCoordinates
import java.io.File

/**
 * Context retrieval local, com ranking por relação estrutural (item 34).
 *
 * Problemas da versão anterior que esta implementação corrige:
 * - varria o repositório inteiro para uma lista em memória, excluindo apenas `.git`, `build` e
 *   `.gradle` (binários, `node_modules`, `.venv` entravam);
 * - o filtro casava qualquer arquivo cujo nome contivesse `service/repository/controller/test`,
 *   **independentemente do arquivo alterado**, devolvendo 4 arquivos arbitrários por alteração;
 * - `take(4)` sem ranking, em ordem de caminhada;
 * - recorte = primeiras 120 linhas.
 *
 * Ordem de prioridade implementada: símbolo alterado → interface/supertipo → caller →
 * dependência direta → teste → configuração/migration.
 */
class LocalRepositoryContextProvider(
    private val rootPath: String = ".",
    private val excerptExtractor: SourceExcerptExtractor = SourceExcerptExtractor(),
    private val maxIndexedFiles: Int = 20_000,
    private val maxFileSizeBytes: Long = 512 * 1024,
    private val maxFilesToScanContent: Int = 600
) : RepositoryContextProvider {

    private val root: File by lazy { File(rootPath).canonicalFile }

    private data class IndexedFile(
        val relativePath: String,
        val file: File,
        val stem: String,
        val directory: String
    )

    private val index: List<IndexedFile> by lazy { buildIndex() }

    override fun detectRepositoryCoordinates(): RepositoryCoordinates? {
        val origin = runGit("config", "--get", "remote.origin.url") ?: return null
        val cleaned = origin.trim().removeSuffix(".git")

        Regex("https?://(?:[^@/]+@)?([^/]+)/(.+)").matchEntire(cleaned)?.let { match ->
            return RepositoryCoordinates("https://${match.groupValues[1]}", match.groupValues[2])
        }
        Regex("(?:ssh://)?git@([^:/]+)[:/](.+)").matchEntire(cleaned)?.let { match ->
            return RepositoryCoordinates("https://${match.groupValues[1]}", match.groupValues[2])
        }

        return null
    }

    override fun findRelatedContext(request: ContextRetrievalRequest): List<RelatedFileContext> {
        if (request.queries.isEmpty() || index.isEmpty()) {
            return emptyList()
        }

        val changedPaths = request.queries.map { it.path }.toSet()
        val results = mutableListOf<RelatedFileContext>()

        for (query in request.queries) {
            if (results.size >= request.budget.maxTotalFiles) {
                break
            }
            results += rankCandidates(query, changedPaths, request.budget)
        }

        return results
            .distinctBy { "${it.referencePath}|${it.relatedPath}" }
            .take(request.budget.maxTotalFiles)
    }

    private fun rankCandidates(
        query: ChangedFileQuery,
        changedPaths: Set<String>,
        budget: ContextBudget
    ): List<RelatedFileContext> {
        val queryStem = query.path.substringAfterLast('/').substringBeforeLast('.')
        val queryDirectory = query.path.substringBeforeLast('/', "")
        val importStems = query.imports.map { it.substringAfterLast('.').substringAfterLast('/') }
            .filter { it.isNotBlank() && it != "*" }
            .toSet()

        // Filtro barato primeiro: só arquivos com alguma chance real de relação.
        val shortlist = index
            .asSequence()
            .filter { it.relativePath !in changedPaths && it.relativePath != query.path }
            .filter { candidate -> isPlausible(candidate, queryStem, queryDirectory, importStems, query) }
            .take(maxFilesToScanContent)
            .toList()

        val scored = shortlist.mapNotNull { candidate ->
            val content = readOrNull(candidate.file) ?: return@mapNotNull null
            score(candidate, content, query, queryStem, queryDirectory, importStems)
                ?.let { (kind, points, reason) -> Ranked(candidate, content, kind, points, reason) }
        }

        return scored
            .sortedByDescending { it.points }
            .take(budget.maxFilesPerChange)
            .map { ranked ->
                RelatedFileContext(
                    referencePath = query.path,
                    relatedPath = ranked.candidate.relativePath,
                    content = excerptExtractor.extract(
                        content = ranked.content,
                        targetSymbols = query.declaredSymbols + query.superTypes,
                        maxChars = budget.maxCharsPerFile
                    ),
                    kind = ranked.kind,
                    reason = ranked.reason,
                    score = ranked.points,
                    excerpt = ranked.content.length > budget.maxCharsPerFile
                )
            }
    }

    private data class Ranked(
        val candidate: IndexedFile,
        val content: String,
        val kind: RelatedContextKind,
        val points: Double,
        val reason: String
    )

    private fun isPlausible(
        candidate: IndexedFile,
        queryStem: String,
        queryDirectory: String,
        importStems: Set<String>,
        query: ChangedFileQuery
    ): Boolean {
        if (candidate.stem.contains(queryStem, ignoreCase = true) && queryStem.length >= 4) return true
        if (queryStem.contains(candidate.stem, ignoreCase = true) && candidate.stem.length >= 4) return true
        if (candidate.stem in importStems) return true
        if (query.superTypes.any { it.equals(candidate.stem, ignoreCase = true) }) return true
        if (query.referencedTypes.any { it.equals(candidate.stem, ignoreCase = true) }) return true
        if (candidate.directory == queryDirectory && queryDirectory.isNotBlank()) return true
        if (isMigrationOrConfig(candidate.relativePath) && sharesFeature(candidate.stem, queryStem)) return true
        return false
    }

    private fun score(
        candidate: IndexedFile,
        content: String,
        query: ChangedFileQuery,
        queryStem: String,
        queryDirectory: String,
        importStems: Set<String>
    ): Triple<RelatedContextKind, Double, String>? {
        // 1. Interface/supertipo declarado no candidato — maior valor para evitar falso positivo
        //    sobre "contrato não respeitado".
        query.superTypes.firstOrNull { superType ->
            candidate.stem.equals(superType, ignoreCase = true) ||
                declaresSymbol(content, superType)
        }?.let {
            return Triple(
                RelatedContextKind.INTERFACE_OR_PARENT,
                6.0,
                "declara '$it', que ${queryStem} implementa/estende"
            )
        }

        // 2. Teste correspondente.
        if (isTestOf(candidate.stem, queryStem)) {
            return Triple(RelatedContextKind.TEST, 5.0, "teste correspondente a $queryStem")
        }

        // 3. Caller: menciona um símbolo declarado no arquivo alterado.
        query.declaredSymbols.firstOrNull { symbol ->
            symbol.length >= 4 && symbol.first().isUpperCase() && content.contains(symbol)
        }?.let {
            return Triple(RelatedContextKind.CALLER, 4.5, "referencia '$it', declarado no arquivo alterado")
        }

        // 4. Dependência direta: importada pelo arquivo alterado.
        if (candidate.stem in importStems) {
            return Triple(
                RelatedContextKind.DEPENDENCY,
                4.0,
                "importado por ${query.path.substringAfterLast('/')}"
            )
        }

        // 5. Outra implementação do mesmo supertipo.
        if (query.superTypes.any { superType -> extendsSymbol(content, superType) }) {
            return Triple(RelatedContextKind.IMPLEMENTATION, 3.5, "outra implementação do mesmo tipo base")
        }

        // 6. Migration / configuração / contrato do mesmo domínio funcional.
        if (sharesFeature(candidate.stem, queryStem)) {
            kindForPath(candidate.relativePath)?.let { kind ->
                return Triple(kind, 3.0, "${kind.label} do mesmo domínio funcional")
            }
        }

        // 7. Vizinho do mesmo diretório com raiz de nome compartilhada.
        if (candidate.directory == queryDirectory && sharesFeature(candidate.stem, queryStem)) {
            return Triple(RelatedContextKind.SIBLING, 2.0, "arquivo vizinho do mesmo domínio")
        }

        return null
    }

    private fun kindForPath(path: String): RelatedContextKind? {
        val lower = path.lowercase()
        return when {
            lower.contains("migration") || lower.contains("changelog") || lower.contains("flyway") ->
                RelatedContextKind.MIGRATION

            lower.endsWith(".proto") || lower.endsWith(".avsc") || lower.contains("openapi") ||
                lower.contains("swagger") -> RelatedContextKind.CONTRACT

            CONFIG_EXTENSION.containsMatchIn(lower) -> RelatedContextKind.CONFIGURATION
            else -> null
        }
    }

    private fun isMigrationOrConfig(path: String): Boolean = kindForPath(path) != null

    private fun declaresSymbol(content: String, symbol: String): Boolean =
        Regex("""\b(?:class|interface|object|trait|record|struct|enum)\s+${Regex.escape(symbol)}\b""")
            .containsMatchIn(content)

    private fun extendsSymbol(content: String, symbol: String): Boolean =
        Regex("""(?::|extends|implements)[^{\n]*\b${Regex.escape(symbol)}\b""").containsMatchIn(content)

    private fun isTestOf(candidateStem: String, queryStem: String): Boolean {
        if (queryStem.length < 4) return false
        return TEST_SUFFIXES.any { suffix -> candidateStem.equals("$queryStem$suffix", ignoreCase = true) } ||
            candidateStem.equals("Test$queryStem", ignoreCase = true)
    }

    private fun sharesFeature(candidateStem: String, queryStem: String): Boolean {
        val a = featureStem(candidateStem)
        val b = featureStem(queryStem)
        return a.isNotBlank() && a == b
    }

    private fun featureStem(name: String): String {
        val withoutSuffix = LAYER_SUFFIXES.fold(name) { acc, suffix ->
            if (acc.length > suffix.length + 2 && acc.endsWith(suffix, ignoreCase = true)) {
                acc.dropLast(suffix.length)
            } else {
                acc
            }
        }
        return withoutSuffix.trimStart('V').trimStart('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '_')
            .lowercase()
            .takeIf { it.length >= 4 }
            .orEmpty()
    }

    private fun readOrNull(file: File): String? = runCatching {
        if (file.length() > maxFileSizeBytes) null else file.readText()
    }.getOrNull()

    /**
     * Índice com poda de diretório e filtro por extensão/tamanho, construído uma única vez.
     */
    private fun buildIndex(): List<IndexedFile> = runCatching {
        val collected = mutableListOf<IndexedFile>()

        root.walkTopDown()
            .onEnter { directory -> directory.name !in EXCLUDED_DIRECTORIES }
            .filter { it.isFile }
            .filter { it.extension.lowercase() in SOURCE_EXTENSIONS }
            .filter { it.length() in 1..maxFileSizeBytes }
            .take(maxIndexedFiles)
            .forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                collected += IndexedFile(
                    relativePath = relative,
                    file = file,
                    stem = file.nameWithoutExtension,
                    directory = relative.substringBeforeLast('/', "")
                )
            }

        collected
    }.getOrElse { emptyList() }

    private fun runGit(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(root)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && output.isNotBlank()) output else null
    }.getOrNull()

    private companion object {
        val EXCLUDED_DIRECTORIES = setOf(
            ".git", ".gradle", ".idea", ".vscode", ".kotlin", ".bootstrap",
            "build", "out", "target", "dist", "bin", "obj",
            "node_modules", "vendor", ".venv", "venv", "__pycache__", ".mypy_cache",
            ".pytest_cache", ".tox", "coverage", ".next", ".nuxt", ".terraform",
            "generated", "gen"
        )

        val SOURCE_EXTENSIONS = setOf(
            "kt", "kts", "java", "scala", "groovy",
            "ts", "tsx", "js", "jsx", "mjs", "cjs",
            "py", "go", "rb", "rs", "cs", "php", "swift",
            "sql", "proto", "avsc", "graphql", "graphqls",
            "yml", "yaml", "properties", "toml", "json", "xml", "conf"
        )

        val CONFIG_EXTENSION = Regex("""\.(ya?ml|properties|toml|ini|conf|cfg)$""")

        val TEST_SUFFIXES = listOf("Test", "Tests", "IT", "ITCase", "IntegrationTest", "Spec", "Specs")

        val LAYER_SUFFIXES = listOf(
            "IntegrationTest", "ITCase", "Tests", "Test", "Spec",
            "ControllerImpl", "ServiceImpl", "RepositoryImpl",
            "Controller", "Resource", "Service", "UseCase", "Repository", "Dao",
            "Entity", "Dto", "Request", "Response", "Mapper", "Client", "Gateway",
            "Adapter", "Consumer", "Producer", "Listener", "Publisher", "Handler",
            "Factory", "Builder", "Configuration", "Config", "Impl"
        )
    }
}
