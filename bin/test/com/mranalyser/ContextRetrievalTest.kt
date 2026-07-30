package com.mranalyser

import com.mranalyser.application.port.ChangedFileQuery
import com.mranalyser.application.port.ContextBudget
import com.mranalyser.application.port.ContextRetrievalRequest
import com.mranalyser.application.port.RelatedContextKind
import com.mranalyser.application.port.RelatedFileContext
import com.mranalyser.application.port.RepositoryContextProvider
import com.mranalyser.application.port.RepositoryCoordinates
import com.mranalyser.application.review.AnalysisDiagnostics
import com.mranalyser.application.review.RepositoryContextRetriever
import com.mranalyser.infrastructure.repository.LocalRepositoryContextProvider
import com.mranalyser.infrastructure.repository.SourceExcerptExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LocalRepositoryContextProviderTest {

    @Test
    fun `deve rankear teste correspondente acima de vizinho generico`() = withRepository { root ->
        val src = File(root, "src/main/kotlin").apply { mkdirs() }
        File(src, "PaymentService.kt").writeText("class PaymentService { fun pay() {} }")
        File(src, "PaymentServiceTest.kt").writeText("class PaymentServiceTest { fun deveP() {} }")
        File(src, "PaymentRepository.kt").writeText("interface PaymentRepository")
        File(src, "UnrelatedService.kt").writeText("class UnrelatedService")

        val result = LocalRepositoryContextProvider(root.absolutePath).findRelatedContext(
            listOf("src/main/kotlin/PaymentService.kt")
        )

        assertTrue(result.any { it.relatedPath.endsWith("PaymentServiceTest.kt") })
        assertEquals(
            RelatedContextKind.TEST,
            result.first { it.relatedPath.endsWith("PaymentServiceTest.kt") }.kind
        )
        assertFalse(
            result.any { it.relatedPath.endsWith("UnrelatedService.kt") },
            "a V1 trazia qualquer arquivo com 'Service' no nome, independentemente do arquivo alterado"
        )
    }

    @Test
    fun `deve priorizar interface implementada pelo arquivo alterado`() = withRepository { root ->
        val src = File(root, "src/main/kotlin").apply { mkdirs() }
        File(src, "InvoiceService.kt").writeText("class InvoiceService : InvoiceUseCase")
        File(src, "InvoiceUseCase.kt").writeText("interface InvoiceUseCase { fun cancel() }")

        val result = LocalRepositoryContextProvider(root.absolutePath).findRelatedContext(
            ContextRetrievalRequest(
                queries = listOf(
                    ChangedFileQuery(
                        path = "src/main/kotlin/InvoiceService.kt",
                        declaredSymbols = listOf("InvoiceService"),
                        superTypes = listOf("InvoiceUseCase")
                    )
                )
            )
        )

        val parent = result.first()
        assertTrue(parent.relatedPath.endsWith("InvoiceUseCase.kt"))
        assertEquals(RelatedContextKind.INTERFACE_OR_PARENT, parent.kind)
    }

    @Test
    fun `deve encontrar caller que referencia simbolo do arquivo alterado`() = withRepository { root ->
        val src = File(root, "src/main/kotlin").apply { mkdirs() }
        File(src, "InvoiceService.kt").writeText("class InvoiceService")
        File(src, "InvoiceController.kt").writeText("class InvoiceController(private val s: InvoiceService)")

        val result = LocalRepositoryContextProvider(root.absolutePath).findRelatedContext(
            ContextRetrievalRequest(
                queries = listOf(
                    ChangedFileQuery(
                        path = "src/main/kotlin/InvoiceService.kt",
                        declaredSymbols = listOf("InvoiceService")
                    )
                )
            )
        )

        assertTrue(result.any { it.relatedPath.endsWith("InvoiceController.kt") && it.kind == RelatedContextKind.CALLER })
    }

    @Test
    fun `deve ignorar diretorios de build e dependencias`() = withRepository { root ->
        val src = File(root, "src/main/kotlin").apply { mkdirs() }
        File(src, "PaymentService.kt").writeText("class PaymentService")
        File(root, "node_modules/pkg").apply { mkdirs() }
            .let { File(it, "PaymentServiceTest.kt").writeText("class PaymentServiceTest") }
        File(root, "build/generated").apply { mkdirs() }
            .let { File(it, "PaymentServiceTest.kt").writeText("class PaymentServiceTest") }

        val result = LocalRepositoryContextProvider(root.absolutePath).findRelatedContext(
            listOf("src/main/kotlin/PaymentService.kt")
        )

        assertTrue(result.none { it.relatedPath.contains("node_modules") })
        assertTrue(result.none { it.relatedPath.startsWith("build/") })
    }

    @Test
    fun `deve respeitar o orcamento de arquivos por alteracao`() = withRepository { root ->
        val src = File(root, "src/main/kotlin").apply { mkdirs() }
        File(src, "PaymentService.kt").writeText("class PaymentService")
        listOf("Test", "Tests", "IT", "Spec").forEach {
            File(src, "PaymentService$it.kt").writeText("class PaymentService$it")
        }

        val result = LocalRepositoryContextProvider(root.absolutePath).findRelatedContext(
            ContextRetrievalRequest(
                queries = listOf(ChangedFileQuery("src/main/kotlin/PaymentService.kt")),
                budget = ContextBudget(maxFilesPerChange = 2, maxTotalFiles = 10)
            )
        )

        assertEquals(2, result.size)
    }

    private fun withRepository(block: (File) -> Unit) {
        val root = createTempDirectory("mr-analyser-context-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

class SourceExcerptExtractorTest {

    @Test
    fun `deve devolver arquivo completo quando cabe no orcamento`() {
        val content = "class A { fun b() {} }"

        assertEquals(content, SourceExcerptExtractor().extract(content, listOf("A"), maxChars = 1_000))
    }

    @Test
    fun `deve incluir janela ao redor do simbolo alvo e marcar omissoes`() {
        val content = buildString {
            appendLine("package foo")
            (1..200).forEach { appendLine("val filler$it = $it") }
            appendLine("fun targetSymbol() = 1")
            (201..400).forEach { appendLine("val filler$it = $it") }
        }

        val excerpt = SourceExcerptExtractor().extract(content, listOf("targetSymbol"), maxChars = 2_000)

        assertTrue(excerpt.contains("targetSymbol"), "o símbolo-alvo é o motivo do recorte existir")
        assertTrue(excerpt.contains("package foo"), "cabeçalho deve ser preservado")
        assertTrue(excerpt.contains("omitida"), "omissões devem ser explícitas para o modelo")
    }
}

class RepositoryContextRetrieverTest {

    @Test
    fun `deve desligar retrieval quando o repositorio local nao corresponde ao MR`() {
        val diagnostics = AnalysisDiagnostics()
        val retriever = RepositoryContextRetriever(
            provider = StubProvider(
                coordinates = RepositoryCoordinates("https://gitlab.com", "outro/projeto"),
                contexts = listOf(context())
            ),
            requireRepositoryMatch = true
        )

        val result = retriever.retrieve("ctbz/billing/invoice-core", listOf(ChangedFileQuery("A.kt")), diagnostics)

        assertTrue(result.isEmpty(), "contexto de outro repositório é pior do que nenhum contexto")
        assertTrue(diagnostics.skippedStages.any { it.contains("outro/projeto") })
    }

    @Test
    fun `deve usar retrieval quando o repositorio corresponde`() {
        val diagnostics = AnalysisDiagnostics()
        val retriever = RepositoryContextRetriever(
            provider = StubProvider(
                coordinates = RepositoryCoordinates("https://gitlab.com", "grupo/projeto"),
                contexts = listOf(context())
            ),
            requireRepositoryMatch = true
        )

        val result = retriever.retrieve("grupo/projeto", listOf(ChangedFileQuery("A.kt")), diagnostics)

        assertEquals(1, result.size)
        assertEquals(1, diagnostics.relatedContextsLoaded)
    }

    @Test
    fun `deve avisar quando nao encontra contexto relacionado`() {
        val diagnostics = AnalysisDiagnostics()
        val retriever = RepositoryContextRetriever(
            provider = StubProvider(coordinates = null, contexts = emptyList()),
            requireRepositoryMatch = false
        )

        retriever.retrieve("grupo/projeto", listOf(ChangedFileQuery("A.kt")), diagnostics)

        assertTrue(
            diagnostics.warnings.any { it.contains("retry/timeout/transação/validação") },
            "o relatório precisa registrar que ausência não é prova"
        )
    }

    @Test
    fun `deve degradar sem provider configurado`() {
        val diagnostics = AnalysisDiagnostics()
        val result = RepositoryContextRetriever(provider = null)
            .retrieve("grupo/projeto", listOf(ChangedFileQuery("A.kt")), diagnostics)

        assertTrue(result.isEmpty())
        assertTrue(diagnostics.skippedStages.any { it.contains("provider não configurado") })
    }

    @Test
    fun `falha do provider nao derruba a analise`() {
        val diagnostics = AnalysisDiagnostics()
        val retriever = RepositoryContextRetriever(
            provider = object : RepositoryContextProvider {
                override fun detectRepositoryCoordinates() = RepositoryCoordinates("h", "grupo/projeto")
                override fun findRelatedContext(request: ContextRetrievalRequest): List<RelatedFileContext> =
                    throw IllegalStateException("disco indisponível")
            }
        )

        val result = retriever.retrieve("grupo/projeto", listOf(ChangedFileQuery("A.kt")), diagnostics)

        assertTrue(result.isEmpty())
        assertTrue(diagnostics.warnings.any { it.contains("disco indisponível") })
    }

    private fun context() = RelatedFileContext(
        referencePath = "A.kt",
        relatedPath = "ATest.kt",
        content = "class ATest"
    )

    private class StubProvider(
        private val coordinates: RepositoryCoordinates?,
        private val contexts: List<RelatedFileContext>
    ) : RepositoryContextProvider {
        override fun detectRepositoryCoordinates(): RepositoryCoordinates? = coordinates
        override fun findRelatedContext(request: ContextRetrievalRequest): List<RelatedFileContext> = contexts
    }
}
