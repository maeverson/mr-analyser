package com.mranalyser

import com.mranalyser.infrastructure.render.ReportFileWriter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ReportFileWriterTest {
    @Test
    fun `should write report to disk`() {
        val outputDirectory = Files.createTempDirectory("reports-test")
        val writer = ReportFileWriter(outputDirectory = outputDirectory)

        val reportPath = writer.writeReport("mr-123", "conteudo do relatorio", ".txt")

        assertTrue(Files.exists(reportPath))
        assertTrue(Files.isRegularFile(reportPath))
        assertTrue(Files.readString(reportPath).contains("conteudo do relatorio"))
    }
}
