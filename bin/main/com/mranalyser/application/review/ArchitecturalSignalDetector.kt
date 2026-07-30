package com.mranalyser.application.review

import com.mranalyser.domain.model.ArchitecturalSignal
import com.mranalyser.domain.model.ArchitecturalSignalKind
import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.ParsedDiff

/**
 * Detecção determinística de mudanças estruturais (item 29).
 *
 * Deliberadamente **não** gera findings: gera sinais. Sinal é fato observável no diff
 * ("uma nova dependência foi adicionada"), não julgamento. Os sinais entram no resumo do MR e
 * alimentam os prompts, que decidem se há problema — evitando o falso positivo que uma regra
 * heurística com opinião produziria.
 */
class ArchitecturalSignalDetector {
    fun detect(files: List<ClassifiedFile>, parsedDiffs: Map<String, ParsedDiff>): List<ArchitecturalSignal> {
        val signals = mutableListOf<ArchitecturalSignal>()

        files.forEach { file ->
            val parsed = parsedDiffs[file.path] ?: ParsedDiff.EMPTY
            val added = parsed.addedLines.joinToString("\n") { it.content }
            signals += detectForFile(file, added)
        }

        signals += detectNewModules(files, parsedDiffs)

        return signals.distinctBy { "${it.kind}|${it.file}|${it.detail}" }
    }

    private fun detectForFile(file: ClassifiedFile, added: String): List<ArchitecturalSignal> {
        val signals = mutableListOf<ArchitecturalSignal>()
        val change = file.change
        val path = file.path

        fun add(kind: ArchitecturalSignalKind, detail: String) {
            signals += ArchitecturalSignal(kind, detail, path)
        }

        if (change.deleted) {
            add(ArchitecturalSignalKind.FILE_REMOVED, "arquivo removido do repositório")
        }

        if (file.group == ChangeGroup.BUILD) {
            dependencyDeclarations(added).forEach { add(ArchitecturalSignalKind.NEW_DEPENDENCY, "dependência adicionada: $it") }
        }

        if (file.group == ChangeGroup.MIGRATION) {
            val verb = if (change.added) "nova migration" else "migration alterada"
            add(ArchitecturalSignalKind.NEW_MIGRATION, verb)
        }

        if (file.group == ChangeGroup.CONTRACT) {
            add(ArchitecturalSignalKind.CONTRACT_CHANGE, "contrato de API/evento alterado")
        }

        if (SCHEMA_DDL.containsMatchIn(added)) {
            SCHEMA_DDL.findAll(added).take(4).forEach {
                add(ArchitecturalSignalKind.SCHEMA_CHANGE, "DDL: ${it.value.trim().take(120)}")
            }
        }

        if (change.added && ENDPOINT_MARKER.containsMatchIn(added)) {
            add(ArchitecturalSignalKind.NEW_ENDPOINT, "novo endpoint exposto")
        } else if (ENDPOINT_MARKER.containsMatchIn(added) && file.group == ChangeGroup.API) {
            add(ArchitecturalSignalKind.NEW_ENDPOINT, "rota/endpoint adicionado em controller existente")
        }

        if (CONSUMER_MARKER.containsMatchIn(added)) {
            add(ArchitecturalSignalKind.NEW_CONSUMER, "consumer de mensageria adicionado ou alterado")
        }

        if (PRODUCER_MARKER.containsMatchIn(added)) {
            add(ArchitecturalSignalKind.NEW_PRODUCER, "publicação de evento/mensagem adicionada ou alterada")
        }

        if (change.added && EXTERNAL_CLIENT_MARKER.containsMatchIn(added)) {
            add(ArchitecturalSignalKind.NEW_EXTERNAL_CLIENT, "novo client HTTP/externo")
        }

        TIMEOUT_MARKER.findAll(added).take(3).forEach {
            add(ArchitecturalSignalKind.TIMEOUT_CHANGE, "timeout definido/alterado: ${it.value.trim().take(120)}")
        }

        RETRY_MARKER.findAll(added).take(3).forEach {
            add(ArchitecturalSignalKind.RETRY_CHANGE, "retry/backoff definido/alterado: ${it.value.trim().take(120)}")
        }

        CONCURRENCY_MARKER.findAll(added).take(3).forEach {
            add(ArchitecturalSignalKind.CONCURRENCY_CHANGE, "concorrência/pool definido/alterado: ${it.value.trim().take(120)}")
        }

        FEATURE_FLAG_MARKER.findAll(added).take(2).forEach {
            add(ArchitecturalSignalKind.FEATURE_FLAG, "feature flag: ${it.value.trim().take(120)}")
        }

        if (file.group == ChangeGroup.CONFIGURATION && change.totalLines > 0) {
            add(ArchitecturalSignalKind.CONFIGURATION_CHANGE, "configuração alterada (+${change.linesAdded}/-${change.linesRemoved})")
        }

        return signals
    }

    /**
     * Novo módulo: diretório de topo que só aparece em arquivos novos, ou `include` adicionado
     * no settings do Gradle.
     */
    private fun detectNewModules(
        files: List<ClassifiedFile>,
        parsedDiffs: Map<String, ParsedDiff>
    ): List<ArchitecturalSignal> {
        return files
            .filter { it.path.substringAfterLast('/').startsWith("settings.gradle") }
            .flatMap { file ->
                val added = (parsedDiffs[file.path] ?: ParsedDiff.EMPTY).addedLines.joinToString("\n") { it.content }
                GRADLE_INCLUDE.findAll(added).map { it.groupValues[1] }.toList()
            }
            .distinct()
            .map { ArchitecturalSignal(ArchitecturalSignalKind.NEW_MODULE, "módulo declarado no settings: $it") }
    }

    private fun dependencyDeclarations(added: String): List<String> =
        DEPENDENCY_LINE.findAll(added)
            .map { it.groupValues.drop(1).firstOrNull { group -> group.isNotBlank() }.orEmpty().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
            .toList()

    private companion object {
        val DEPENDENCY_LINE = Regex(
            """(?i)(?:implementation|api|compileOnly|runtimeOnly|testImplementation|kapt|ksp)\s*\(?\s*["']([^"']+)["']""" +
                """|<artifactId>([^<]+)</artifactId>"""
        )

        val GRADLE_INCLUDE = Regex("""include\s*\(?\s*["']:?([^"']+)["']""")

        val SCHEMA_DDL = Regex(
            """(?i)\b(create\s+table|alter\s+table[^\n;]{0,80}|drop\s+table|add\s+column[^\n;]{0,60}|drop\s+column[^\n;]{0,60}|create\s+(unique\s+)?index[^\n;]{0,60}|not\s+null)\b"""
        )

        val ENDPOINT_MARKER = Regex(
            """(?i)(@(Get|Post|Put|Delete|Patch)Mapping|@RequestMapping|@Path\(|@(GET|POST|PUT|DELETE|PATCH)\b|""" +
                """\b(get|post|put|delete|patch)\s*\(\s*["'`]/|router\.(get|post|put|delete|patch)\()"""
        )

        val CONSUMER_MARKER = Regex(
            """(?i)(@KafkaListener|@RabbitListener|@SqsListener|@JmsListener|@StreamListener|@EventListener|""" +
                """ConsumerRecord|\bsubscribe\(|createConsumer|\.poll\()"""
        )

        val PRODUCER_MARKER = Regex(
            """(?i)(KafkaTemplate|kafkaProducer|rabbitTemplate\.convertAndSend|snsClient\.publish|sqsClient\.sendMessage|""" +
                """\bpublishEvent\(|\bpublish\(|ProducerRecord)"""
        )

        val EXTERNAL_CLIENT_MARKER = Regex(
            """(?i)(HttpClient\(|RestTemplate\(|WebClient\.|OkHttpClient|@FeignClient|HttpURLConnection|""" +
                """\bnewBuilder\(\)\s*\.\s*baseUrl)"""
        )

        val TIMEOUT_MARKER = Regex(
            """(?i)^[^\n]{0,80}\b(\w*timeout\w*|connectTimeout|readTimeout|socketTimeout|requestTimeout)\b\s*[:=][^\n]{0,60}""",
            RegexOption.MULTILINE
        )

        val RETRY_MARKER = Regex(
            """(?i)^[^\n]{0,80}\b(retry|retries|maxAttempts|maxRetries|backoff|@Retryable|circuitBreaker)\b[^\n]{0,60}""",
            RegexOption.MULTILINE
        )

        val CONCURRENCY_MARKER = Regex(
            """(?i)^[^\n]{0,80}\b(corePoolSize|maxPoolSize|threadPool|Executors\.new\w+|newFixedThreadPool|""" +
                """Dispatchers\.\w+|Semaphore\(|concurrency|parallelism|maxConcurren\w*)\b[^\n]{0,60}""",
            RegexOption.MULTILINE
        )

        val FEATURE_FLAG_MARKER = Regex(
            """(?i)^[^\n]{0,80}\b(featureFlag|feature_flag|featureToggle|unleash|launchDarkly|isEnabled\()\b[^\n]{0,60}""",
            RegexOption.MULTILINE
        )
    }
}
