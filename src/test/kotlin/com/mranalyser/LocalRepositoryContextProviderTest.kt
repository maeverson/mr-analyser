package com.mranalyser

import com.mranalyser.infrastructure.repository.LocalRepositoryContextProvider
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LocalRepositoryContextProviderTest {
    @Test
    fun `should find related local files`() {
        val tempRoot = createTempDirectory("mr-analyser-context-").toFile()
        try {
            val src = File(tempRoot, "src/main/kotlin")
            src.mkdirs()
            File(src, "PaymentService.kt").writeText("class PaymentService")
            File(src, "PaymentServiceTest.kt").writeText("class PaymentServiceTest")
            File(src, "PaymentRepository.kt").writeText("class PaymentRepository")

            val provider = LocalRepositoryContextProvider(tempRoot.absolutePath)
            val result = provider.findRelatedContext(listOf("src/main/kotlin/PaymentService.kt"))

            assertTrue(result.any { it.relatedPath.endsWith("PaymentServiceTest.kt") })
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
