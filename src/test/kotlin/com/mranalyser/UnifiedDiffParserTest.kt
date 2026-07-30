package com.mranalyser

import com.mranalyser.domain.diff.AnnotatedDiffRenderer
import com.mranalyser.domain.diff.UnifiedDiffParser
import com.mranalyser.domain.model.LineOrigin
import com.mranalyser.support.MergeRequestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedDiffParserTest {

    @Test
    fun `deve calcular numeros de linha reais a partir do cabecalho do hunk`() {
        val parsed = UnifiedDiffParser.parse(
            """
            @@ -80,4 +80,6 @@ class InvoiceService {
                 fun cancel() {
            -        repository.save(invoice)
            +        provider.cancel(invoice)
            +        repository.save(invoice)
                 }
            """.trimIndent()
        )

        val context = parsed.lines.first { it.origin == LineOrigin.CONTEXT }
        assertEquals(80, context.newLine)
        assertEquals(80, context.oldLine)

        val removed = parsed.removedLines.single()
        assertEquals(81, removed.oldLine)
        assertNull(removed.newLine, "linha removida não existe no arquivo novo")

        val added = parsed.addedLines
        assertEquals(listOf(81, 82), added.map { it.newLine })
        assertTrue(added.all { it.oldLine == null })
    }

    @Test
    fun `deve tratar multiplos hunks de forma independente`() {
        val parsed = UnifiedDiffParser.parse(
            """
            @@ -1,2 +1,3 @@
             package a
            +import b
            @@ -50,2 +51,3 @@
             fun x() {
            +    val y = 1
            """.trimIndent()
        )

        assertEquals(2, parsed.hunks.size)
        assertEquals(listOf(2, 52), parsed.addedLines.map { it.newLine })
    }

    @Test
    fun `deve sintetizar hunk quando o diff nao possui cabecalho`() {
        val parsed = UnifiedDiffParser.parse("+val a = 1\n+val b = 2")

        assertEquals(2, parsed.addedLines.size)
        assertEquals(listOf(1, 2), parsed.addedLines.map { it.newLine })
    }

    @Test
    fun `deve ignorar cabecalhos de arquivo e diff binario`() {
        val parsed = UnifiedDiffParser.parse(
            """
            diff --git a/img.png b/img.png
            index 1234567..89abcde 100644
            Binary files a/img.png and b/img.png differ
            """.trimIndent()
        )

        assertTrue(parsed.isEmpty)
    }

    @Test
    fun `diff anotado deve marcar origem e alertar sobre truncamento`() {
        val change = MergeRequestFixtures.change(
            path = "A.kt",
            diff = buildString {
                appendLine("@@ -1,1 +1,60 @@")
                appendLine(" val header = 0")
                (1..59).forEach { appendLine("+val line$it = $it") }
            }
        )
        val parsed = UnifiedDiffParser.parse(change.diff)
        val rendered = AnnotatedDiffRenderer(maxLinesPerFile = 10).render(change, parsed)

        assertTrue(rendered.contains("=== FILE: A.kt ==="))
        assertTrue(rendered.contains("status: MODIFICADO"))
        assertTrue(rendered.contains("ADD "))
        assertTrue(rendered.contains("ctx "))
        assertTrue(rendered.contains("diff truncado"), "truncamento deve ser explícito para o modelo")
    }

    @Test
    fun `diff anotado deve identificar arquivo removido`() {
        val change = MergeRequestFixtures.change(
            path = "Old.kt",
            diff = "@@ -1,2 +0,0 @@\n-val a = 1\n-val b = 2",
            deleted = true
        )
        val rendered = AnnotatedDiffRenderer().render(change, UnifiedDiffParser.parse(change.diff))

        assertTrue(rendered.contains("status: REMOVIDO"))
        assertTrue(rendered.contains("DEL "))
    }
}
