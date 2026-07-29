package com.mranalyser.infrastructure.render

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ReportFileWriter(
    private val outputDirectory: Path = Paths.get("reports")
) {
    fun writeReport(mrIdentifier: String, content: String, extension: String = ".md"): Path {
        Files.createDirectories(outputDirectory)

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val fileName = "${sanitize(mrIdentifier)}-$timestamp$extension"
        val reportPath = outputDirectory.resolve(fileName)
        Files.writeString(reportPath, content)
        return reportPath
    }

    private fun sanitize(value: String): String {
        return value.lowercase()
            .replace(" ", "-")
            .replace("/", "-")
            .replace("\\", "-")
            .replace("?", "")
            .replace("#", "")
            .replace("%", "")
            .replace("&", "")
            .replace("*", "")
            .replace(":", "")
            .replace("|", "")
            .replace("<", "")
            .replace(">", "")
            .replace("\"", "")
            .replace("'", "")
            .replace(".", "-")
            .trim('-')
    }
}
