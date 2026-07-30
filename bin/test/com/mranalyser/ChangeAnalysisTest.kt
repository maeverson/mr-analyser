package com.mranalyser

import com.mranalyser.application.review.ArchitecturalSignalDetector
import com.mranalyser.application.review.ChangeClassifier
import com.mranalyser.application.review.ClassifiedFile
import com.mranalyser.application.review.FileRelationDetector
import com.mranalyser.application.review.SymbolExtractor
import com.mranalyser.application.service.ReviewChunker
import com.mranalyser.domain.diff.UnifiedDiffParser
import com.mranalyser.domain.model.ArchitecturalSignalKind
import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.support.MergeRequestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChangeClassifierTest {
    private val classifier = ChangeClassifier()

    @Test
    fun `deve classificar por caminho e conteudo`() {
        val cases = listOf(
            "src/test/kotlin/PaymentServiceTest.kt" to ChangeGroup.TEST,
            "build.gradle.kts" to ChangeGroup.BUILD,
            "README.md" to ChangeGroup.DOCUMENTATION,
            "src/main/resources/db/migration/V3__add_column.sql" to ChangeGroup.MIGRATION,
            "contracts/payment.proto" to ChangeGroup.CONTRACT,
            "src/main/kotlin/payments/messaging/PaymentConsumer.kt" to ChangeGroup.MESSAGING,
            "src/main/kotlin/api/PaymentController.kt" to ChangeGroup.API,
            "src/main/kotlin/persistence/PaymentRepository.kt" to ChangeGroup.PERSISTENCE,
            "src/main/kotlin/integration/BillingClient.kt" to ChangeGroup.INTEGRATION,
            "src/main/resources/application.yml" to ChangeGroup.CONFIGURATION,
            "src/main/kotlin/application/CancelInvoiceUseCase.kt" to ChangeGroup.APPLICATION,
            "src/main/kotlin/domain/Invoice.kt" to ChangeGroup.DOMAIN
        )

        cases.forEach { (path, expected) ->
            val change = MergeRequestFixtures.change(path, "@@ -1 +1 @@\n+val x = 1")
            assertEquals(expected, classifier.classify(change, UnifiedDiffParser.parse(change.diff)), path)
        }
    }

    @Test
    fun `teste tem precedencia sobre a camada indicada pelo nome`() {
        val change = MergeRequestFixtures.change(
            "src/test/kotlin/persistence/PaymentRepositoryTest.kt",
            "@@ -1 +1 @@\n+val x = 1"
        )

        assertEquals(ChangeGroup.TEST, classifier.classify(change, UnifiedDiffParser.parse(change.diff)))
    }

    @Test
    fun `deve usar conteudo quando o caminho nao indica a camada`() {
        val change = MergeRequestFixtures.change(
            "src/main/kotlin/Foo.kt",
            "@@ -1 +1 @@\n+    @KafkaListener(topics = [\"orders\"])"
        )

        assertEquals(ChangeGroup.MESSAGING, classifier.classify(change, UnifiedDiffParser.parse(change.diff)))
    }
}

class ArchitecturalSignalDetectorTest {
    private val detector = ArchitecturalSignalDetector()
    private val classifier = ChangeClassifier()

    @Test
    fun `deve detectar nova dependencia`() {
        val signals = detect(
            "build.gradle.kts",
            "@@ -10,3 +10,4 @@ dependencies {\n+    implementation(\"io.grpc:grpc-kotlin:1.4.0\")"
        )

        assertTrue(signals.any { it.kind == ArchitecturalSignalKind.NEW_DEPENDENCY && it.detail.contains("grpc") })
    }

    @Test
    fun `deve detectar migration e alteracao de schema`() {
        val signals = detect(
            "src/main/resources/db/migration/V5__add_status.sql",
            "@@ -0,0 +1,2 @@\n+ALTER TABLE invoices ADD COLUMN status VARCHAR(20) NOT NULL;",
            added = true
        )

        assertTrue(signals.any { it.kind == ArchitecturalSignalKind.NEW_MIGRATION })
        assertTrue(signals.any { it.kind == ArchitecturalSignalKind.SCHEMA_CHANGE })
    }

    @Test
    fun `deve detectar timeout, retry e concorrencia`() {
        val signals = detect(
            "src/main/resources/application.yml",
            """
            @@ -1,3 +1,6 @@
            +  connectTimeout: 500
            +  maxRetries: 5
            +  corePoolSize: 32
            """.trimIndent()
        )

        assertTrue(signals.any { it.kind == ArchitecturalSignalKind.TIMEOUT_CHANGE })
        assertTrue(signals.any { it.kind == ArchitecturalSignalKind.RETRY_CHANGE })
        assertTrue(signals.any { it.kind == ArchitecturalSignalKind.CONCURRENCY_CHANGE })
        assertTrue(signals.any { it.kind == ArchitecturalSignalKind.CONFIGURATION_CHANGE })
    }

    @Test
    fun `deve detectar consumer e producer`() {
        assertTrue(
            detect(
                "src/main/kotlin/OrderConsumer.kt",
                "@@ -1 +1,3 @@\n+    @KafkaListener(topics = [\"orders\"])"
            ).any { it.kind == ArchitecturalSignalKind.NEW_CONSUMER }
        )

        assertTrue(
            detect(
                "src/main/kotlin/OrderProducer.kt",
                "@@ -1 +1,3 @@\n+        kafkaTemplate.send(\"orders\", event)"
            ).any { it.kind == ArchitecturalSignalKind.NEW_PRODUCER }
        )
    }

    @Test
    fun `deve detectar arquivo removido`() {
        val signals = detect(
            "src/main/kotlin/LegacyService.kt",
            "@@ -1,2 +0,0 @@\n-class LegacyService",
            deleted = true
        )

        assertTrue(signals.any { it.kind == ArchitecturalSignalKind.FILE_REMOVED })
    }

    @Test
    fun `nao deve produzir sinal para alteracao trivial`() {
        val signals = detect("src/main/kotlin/Messages.kt", "@@ -1 +1 @@\n+const val A = \"x\"")

        assertTrue(signals.isEmpty())
    }

    private fun detect(path: String, diff: String, added: Boolean = false, deleted: Boolean = false) =
        MergeRequestFixtures.change(path, diff, added = added, deleted = deleted).let { change ->
            val parsed = UnifiedDiffParser.parse(change.diff)
            detector.detect(
                listOf(ClassifiedFile(change, classifier.classify(change, parsed), "")),
                mapOf(change.path to parsed)
            )
        }
}

class SymbolExtractorTest {
    private val extractor = SymbolExtractor()

    @Test
    fun `deve extrair declaracao supertipo e imports`() {
        val change = MergeRequestFixtures.change(
            "src/main/kotlin/billing/InvoiceService.kt",
            """
            @@ -1,10 +1,14 @@
             package billing
             import billing.port.InvoiceRepository
            +import billing.integration.BillingProviderClient
             class InvoiceService : InvoiceUseCase {
            +    fun cancel(id: InvoiceId) {
            +        provider.cancel(id)
            +    }
             }
            """.trimIndent()
        )

        val query = extractor.extract(change.path, UnifiedDiffParser.parse(change.diff))

        assertTrue(query.declaredSymbols.contains("InvoiceService"))
        assertTrue(query.superTypes.contains("InvoiceUseCase"), "supertipo: ${query.superTypes}")
        assertTrue(query.imports.any { it.endsWith("BillingProviderClient") })
        assertTrue(query.imports.any { it.endsWith("InvoiceRepository") })
    }

    @Test
    fun `nao deve considerar linhas removidas como declaracoes atuais`() {
        val change = MergeRequestFixtures.change(
            "src/main/kotlin/A.kt",
            "@@ -1,3 +1,2 @@\n-class RemovedType\n+class KeptType"
        )

        val query = extractor.extract(change.path, UnifiedDiffParser.parse(change.diff))

        assertTrue(query.declaredSymbols.contains("KeptType"))
        assertTrue(!query.declaredSymbols.contains("RemovedType"))
    }

    @Test
    fun `deve descartar tipos de biblioteca padrao das referencias`() {
        val change = MergeRequestFixtures.change(
            "src/main/kotlin/A.kt",
            "@@ -1 +1,3 @@\n+    val map: HashMap<String, InvoiceStatus> = HashMap()"
        )

        val query = extractor.extract(change.path, UnifiedDiffParser.parse(change.diff))

        assertTrue(query.referencedTypes.contains("InvoiceStatus"))
        assertTrue(!query.referencedTypes.contains("HashMap"))
    }
}

class FileRelationDetectorTest {

    @Test
    fun `deve relacionar producer e consumer do mesmo evento`() {
        val mergeRequest = MergeRequestFixtures.crossFileMr()
        val classifier = ChangeClassifier()
        val extractor = SymbolExtractor()

        val files = mergeRequest.changes.map { change ->
            val parsed = UnifiedDiffParser.parse(change.diff)
            ClassifiedFile(change, classifier.classify(change, parsed), "")
        }
        val queries = mergeRequest.changes.associate { change ->
            change.path to extractor.extract(change.path, UnifiedDiffParser.parse(change.diff))
        }

        val relations = FileRelationDetector().detect(files, queries)

        assertTrue(relations.isNotEmpty(), "producer e consumer do mesmo evento devem estar relacionados")
    }

    @Test
    fun `deve relacionar codigo de producao ao seu teste`() {
        val service = MergeRequestFixtures.change(
            "src/main/kotlin/PaymentService.kt",
            "@@ -1 +1,3 @@\n+class PaymentService { fun pay() {} }"
        )
        val test = MergeRequestFixtures.change(
            "src/test/kotlin/PaymentServiceTest.kt",
            "@@ -1 +1,3 @@\n+class PaymentServiceTest { fun deveTestar() {} }"
        )

        val classifier = ChangeClassifier()
        val extractor = SymbolExtractor()
        val files = listOf(service, test).map { change ->
            val parsed = UnifiedDiffParser.parse(change.diff)
            ClassifiedFile(change, classifier.classify(change, parsed), "")
        }
        val queries = listOf(service, test).associate { change ->
            change.path to extractor.extract(change.path, UnifiedDiffParser.parse(change.diff))
        }

        val relations = FileRelationDetector().detect(files, queries)

        assertTrue(
            relations.any { it.from == service.path && it.to == test.path } ||
                relations.any { it.from == test.path && it.to == service.path }
        )
    }
}

class ReviewChunkerTest {
    private val classifier = ChangeClassifier()

    @Test
    fun `deve agrupar por camada arquitetural antes de quebrar por tamanho`() {
        val files = listOf(
            classified("src/main/kotlin/domain/Invoice.kt", 4),
            classified("src/main/kotlin/persistence/InvoiceRepository.kt", 4),
            classified("src/test/kotlin/InvoiceTest.kt", 4)
        )

        val chunks = ReviewChunker(maxDiffLines = 100, maxFileLines = 100).chunk(files)

        assertEquals(3, chunks.size, "camadas diferentes não devem compartilhar o mesmo prompt")
        assertEquals(
            listOf(ChangeGroup.DOMAIN, ChangeGroup.PERSISTENCE, ChangeGroup.TEST),
            chunks.map { it.group },
            "código de comportamento deve vir antes de testes"
        )
    }

    @Test
    fun `deve quebrar por tamanho dentro do mesmo grupo`() {
        val files = (1..3).map { classified("src/main/kotlin/domain/File$it.kt", 4) }

        val chunks = ReviewChunker(maxDiffLines = 8, maxFileLines = 100).chunk(files)

        assertEquals(2, chunks.size)
        assertEquals(2, chunks[0].files.size)
        assertEquals(1, chunks[1].files.size)
    }

    @Test
    fun `arquivo acima do limite recebe chunk proprio`() {
        val files = listOf(
            classified("src/main/kotlin/domain/Small.kt", 2),
            classified("src/main/kotlin/domain/Huge.kt", 50)
        )

        val chunks = ReviewChunker(maxDiffLines = 100, maxFileLines = 10).chunk(files)

        assertEquals(2, chunks.size)
        assertEquals(listOf("src/main/kotlin/domain/Huge.kt"), chunks.last().files.map { it.path })
    }

    @Test
    fun `deve devolver lista vazia sem arquivos`() {
        assertTrue(ReviewChunker(100, 100).chunk(emptyList()).isEmpty())
    }

    private fun classified(path: String, lines: Int): ClassifiedFile {
        val diff = "@@ -1 +1,$lines @@\n" + (1..lines).joinToString("\n") { "+linha$it" }
        val change = MergeRequestFixtures.change(path, diff)
        val parsed = UnifiedDiffParser.parse(diff)
        return ClassifiedFile(
            change = change,
            group = classifier.classify(change, parsed),
            annotatedDiff = (1..lines).joinToString("\n") { "ADD $it | linha$it" }
        )
    }
}
