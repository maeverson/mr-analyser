package com.mranalyser.application.review

import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.ParsedDiff

/**
 * Classifica cada arquivo alterado em um grupo arquitetural (item 7).
 *
 * Usado para montar chunks coesos e para calibrar o foco do prompt. A ordem de avaliação
 * importa: testes e build vencem qualquer outra pista, porque um `PaymentServiceTest.kt` deve
 * ser revisado como teste, não como serviço.
 */
class ChangeClassifier {
    fun classify(change: FileChange, parsedDiff: ParsedDiff = ParsedDiff.EMPTY): ChangeGroup {
        val path = change.path
        val lower = path.lowercase()
        val fileName = path.substringAfterLast('/')
        val addedContent = parsedDiff.addedLines.joinToString("\n") { it.content }

        return when {
            isTest(lower, fileName) -> ChangeGroup.TEST
            isBuild(lower, fileName) -> ChangeGroup.BUILD
            isDocumentation(lower) -> ChangeGroup.DOCUMENTATION
            isMigration(lower) -> ChangeGroup.MIGRATION
            isContract(lower) -> ChangeGroup.CONTRACT
            isMessaging(lower, addedContent) -> ChangeGroup.MESSAGING
            isApi(lower, addedContent) -> ChangeGroup.API
            isPersistence(lower, addedContent) -> ChangeGroup.PERSISTENCE
            isIntegration(lower, addedContent) -> ChangeGroup.INTEGRATION
            isConfiguration(lower) -> ChangeGroup.CONFIGURATION
            isApplication(lower) -> ChangeGroup.APPLICATION
            isDomain(lower) -> ChangeGroup.DOMAIN
            else -> ChangeGroup.OTHER
        }
    }

    private fun isTest(lower: String, fileName: String): Boolean =
        lower.contains("/test/") ||
            lower.contains("/tests/") ||
            lower.startsWith("test/") ||
            lower.contains("/spec/") ||
            lower.contains("__tests__") ||
            TEST_FILE_SUFFIX.containsMatchIn(fileName) ||
            fileName.startsWith("Test", ignoreCase = false) && fileName.endsWith(".java")

    private fun isBuild(lower: String, fileName: String): Boolean =
        fileName in BUILD_FILES ||
            lower.endsWith(".gradle") ||
            lower.endsWith(".gradle.kts") ||
            lower.startsWith(".github/") ||
            lower.startsWith(".gitlab") ||
            lower.contains("/dockerfile") ||
            fileName.equals("Dockerfile", ignoreCase = true) ||
            lower.contains("/helm/") ||
            lower.contains("/k8s/") ||
            lower.contains("/kubernetes/")

    private fun isDocumentation(lower: String): Boolean =
        lower.endsWith(".md") || lower.endsWith(".adoc") || lower.endsWith(".rst") || lower.startsWith("docs/")

    private fun isMigration(lower: String): Boolean =
        lower.contains("/migration") ||
            lower.contains("/migrate/") ||
            lower.contains("db/changelog") ||
            lower.contains("flyway") ||
            lower.contains("liquibase") ||
            MIGRATION_FILE.containsMatchIn(lower.substringAfterLast('/'))

    private fun isContract(lower: String): Boolean =
        lower.endsWith(".proto") ||
            lower.endsWith(".avsc") ||
            lower.endsWith(".graphql") ||
            lower.endsWith(".graphqls") ||
            lower.contains("openapi") ||
            lower.contains("swagger") ||
            lower.endsWith(".wsdl") ||
            lower.contains("/schemas/") ||
            lower.contains("/contracts/")

    private fun isMessaging(lower: String, added: String): Boolean =
        NAME_MESSAGING.containsMatchIn(lower) || CONTENT_MESSAGING.containsMatchIn(added)

    private fun isApi(lower: String, added: String): Boolean =
        NAME_API.containsMatchIn(lower) || CONTENT_API.containsMatchIn(added)

    private fun isPersistence(lower: String, added: String): Boolean =
        NAME_PERSISTENCE.containsMatchIn(lower) || CONTENT_PERSISTENCE.containsMatchIn(added)

    private fun isIntegration(lower: String, added: String): Boolean =
        NAME_INTEGRATION.containsMatchIn(lower) || CONTENT_INTEGRATION.containsMatchIn(added)

    private fun isConfiguration(lower: String): Boolean =
        CONFIG_EXTENSION.containsMatchIn(lower) ||
            lower.contains("/config/") ||
            lower.contains("/resources/") ||
            lower.endsWith(".env")

    private fun isApplication(lower: String): Boolean =
        NAME_APPLICATION.containsMatchIn(lower)

    private fun isDomain(lower: String): Boolean =
        NAME_DOMAIN.containsMatchIn(lower)

    private companion object {
        val TEST_FILE_SUFFIX = Regex(
            """(?i)(Test|Tests|IT|ITCase|Spec|Specs)\.(kt|java|scala|groovy)$|""" +
                """[._-](test|spec)\.(ts|tsx|js|jsx|py|go|rb)$|^test_.*\.py$"""
        )

        val BUILD_FILES = setOf(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "pom.xml", "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            "go.mod", "go.sum", "Cargo.toml", "Cargo.lock", "requirements.txt",
            "pyproject.toml", "poetry.lock", "gradle.properties", "Makefile", "docker-compose.yml",
            "docker-compose.yaml"
        )

        val MIGRATION_FILE = Regex("""(?i)^(v\d+|\d{8,14})[_.\-].*\.(sql|xml|ya?ml|kt|java|py|rb)$""")

        val NAME_MESSAGING = Regex(
            """(?i)(consumer|producer|listener|publisher|subscriber|kafka|rabbit|sqs|sns|pubsub|eventbus|/messaging/|/events?/)"""
        )
        val CONTENT_MESSAGING = Regex(
            """(?i)(@KafkaListener|@RabbitListener|@SqsListener|@JmsListener|@StreamListener|KafkaTemplate|kafkaProducer|rabbitTemplate|sqsClient|snsClient|\.publish\(|\.send\(.*Event|ConsumerRecord|ProducerRecord)"""
        )

        // `\bresource\b` e não `resource`: sem a fronteira, `src/main/resources/application.yml`
        // era classificado como API.
        val NAME_API = Regex(
            """(?i)(controller|\bresource\b|endpoint|/rest/|/api/|/routes?/|handler\.(kt|java|ts|go)$)"""
        )
        val CONTENT_API = Regex(
            """(?i)(@RestController|@Controller|@RequestMapping|@GetMapping|@PostMapping|@PutMapping|@DeleteMapping|@PatchMapping|@Path\(|@GET|@POST|routing\s*\{|\brouter\.(get|post|put|delete)\b|app\.(get|post|put|delete)\()"""
        )

        val NAME_PERSISTENCE = Regex(
            """(?i)(repository|repositories|/dao/|entity|entities|/persistence/|/jpa/|/jdbc/|/database/|/db/)"""
        )
        val CONTENT_PERSISTENCE = Regex(
            """(?i)(@Entity|@Table|@Column|@Repository|JpaRepository|CrudRepository|EntityManager|jdbcTemplate|@Query\(|createQuery|\bexposed\b|\.transaction\s*\{|@Transactional)"""
        )

        val NAME_INTEGRATION = Regex(
            """(?i)(client|gateway|adapter|/integration/|/external/|/http/|/infrastructure/(?!config)|feign|webclient|provider)"""
        )
        val CONTENT_INTEGRATION = Regex(
            """(?i)(HttpClient|RestTemplate|WebClient|OkHttpClient|@FeignClient|HttpURLConnection|okhttp|ktor.*client|requests\.(get|post)|axios\.)"""
        )

        val CONFIG_EXTENSION = Regex("""(?i)\.(ya?ml|properties|toml|ini|conf|cfg|hcl|tfvars)$""")

        val NAME_APPLICATION = Regex("""(?i)(usecase|use_case|/application/|service|orchestrat|/facade/|interactor|command|query)""")

        val NAME_DOMAIN = Regex("""(?i)(/domain/|/model/|/core/|aggregate|valueobject|/entity/|/rule/|/policy/)""")
    }
}
