package com.mranalyser.infrastructure.repository

import com.mranalyser.application.port.RepositoryContextProvider
import com.mranalyser.application.port.RepositoryCoordinates
import com.mranalyser.application.port.RelatedFileContext
import java.io.File

class LocalRepositoryContextProvider(
    private val rootPath: String = "."
) : RepositoryContextProvider {
    override fun detectRepositoryCoordinates(): RepositoryCoordinates? {
        val origin = runCommand("git config --get remote.origin.url") ?: return null
        val cleaned = origin.trim().removeSuffix(".git")

        val httpsRegex = Regex("https?://([^/]+)/(.+)")
        val sshRegex = Regex("git@([^:]+):(.+)")

        val https = httpsRegex.matchEntire(cleaned)
        if (https != null) {
            return RepositoryCoordinates(
                host = "https://${https.groupValues[1]}",
                projectPath = https.groupValues[2]
            )
        }

        val ssh = sshRegex.matchEntire(cleaned)
        if (ssh != null) {
            return RepositoryCoordinates(
                host = "https://${ssh.groupValues[1]}",
                projectPath = ssh.groupValues[2]
            )
        }

        return null
    }

    override fun findRelatedContext(changedFiles: List<String>): List<RelatedFileContext> {
        if (changedFiles.isEmpty()) {
            return emptyList()
        }

        val root = File(rootPath).canonicalFile
        val projectFiles = root.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.path.contains("/.git/") || it.path.contains("/build/") || it.path.contains("/.gradle/") }
            .toList()

        val contexts = mutableListOf<RelatedFileContext>()

        changedFiles.forEach { changedPath ->
            val baseName = changedPath.substringAfterLast('/').substringBeforeLast('.')
            val stems = buildNameStems(baseName)

            val candidates = projectFiles
                .asSequence()
                .map { it.relativeTo(root).invariantSeparatorsPath to it }
                .filter { (relativePath, _) -> relativePath != changedPath }
                .filter { (relativePath, file) ->
                    val candidateBaseName = file.nameWithoutExtension
                    val containsStem = stems.any { stem ->
                        candidateBaseName.contains(stem, ignoreCase = true) ||
                            relativePath.contains(stem, ignoreCase = true)
                    }
                    val domainRelated = listOf("test", "repository", "service", "controller", "handler", "usecase")
                        .any { hint -> candidateBaseName.contains(hint, ignoreCase = true) }
                    containsStem || domainRelated
                }
                .take(4)
                .toList()

            candidates.forEach { (relativePath, file) ->
                val preview = file.useLines { lines -> lines.take(120).joinToString("\n") }
                if (preview.isNotBlank()) {
                    contexts += RelatedFileContext(
                        referencePath = changedPath,
                        relatedPath = relativePath,
                        content = preview
                    )
                }
            }
        }

        return contexts.distinctBy { "${it.referencePath}|${it.relatedPath}" }.take(20)
    }

    private fun buildNameStems(baseName: String): Set<String> {
        if (baseName.isBlank()) {
            return emptySet()
        }

        val sanitized = baseName.replace("[^A-Za-z0-9]".toRegex(), "")
        val suffixes = listOf("Service", "Repository", "Controller", "Handler", "UseCase", "Impl", "Test")
        val withoutSuffix = suffixes.fold(sanitized) { acc, suffix ->
            if (acc.endsWith(suffix, ignoreCase = true) && acc.length > suffix.length + 2) {
                acc.dropLast(suffix.length)
            } else {
                acc
            }
        }

        val parts = withoutSuffix.split("(?=[A-Z])".toRegex()).map { it.lowercase() }.filter { it.length > 2 }

        return buildSet {
            add(baseName.lowercase())
            add(sanitized.lowercase())
            add(withoutSuffix.lowercase())
            addAll(parts)
        }.filter { it.isNotBlank() }.toSet()
    }

    private fun runCommand(command: String): String? {
        return runCatching {
            val parts = command.split(" ")
            val process = ProcessBuilder(parts)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) output else null
        }.getOrNull()
    }
}
