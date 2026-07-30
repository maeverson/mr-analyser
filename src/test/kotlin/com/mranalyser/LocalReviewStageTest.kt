package com.mranalyser

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.port.LlmResponse
import com.mranalyser.application.review.AnalysisDiagnostics
import com.mranalyser.application.review.ChunkReviewInput
import com.mranalyser.application.review.ClassifiedFile
import com.mranalyser.application.review.LocalReviewStage
import com.mranalyser.application.review.MergeRequestOverview
import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.FileChange
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Collections

class LocalReviewStageTest {

    /**
     * Com provider serializado (`maxConcurrency = 1`) os chunks precisam sair **em ordem**. O
     * `Semaphore` garante exclusão mútua, não ordem de entrada: sob fan-out o progresso aparecia
     * como "chunk 2", "chunk 3", ..., "chunk 1", o que parece defeito para quem acompanha o log.
     */
    @Test
    fun `deve revisar os chunks em ordem quando a concorrencia e 1`() = runBlocking {
        val provider = RecordingProvider()
        val stage = LocalReviewStage(provider, maxConcurrency = 1)

        val results = stage.run(inputs(count = 6), AnalysisDiagnostics())

        assertEquals(6, results.size)
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6).map { "chunk $it/6 (DOMAIN)" },
            provider.labels
        )
    }

    @Test
    fun `deve revisar todos os chunks quando ha concorrencia`() = runBlocking {
        val provider = RecordingProvider()
        val stage = LocalReviewStage(provider, maxConcurrency = 4)

        val results = stage.run(inputs(count = 6), AnalysisDiagnostics())

        assertEquals(6, results.size)
        assertEquals((1..6).map { "chunk $it/6 (DOMAIN)" }.toSet(), provider.labels.toSet())
    }

    private fun inputs(count: Int): List<ChunkReviewInput> = (1..count).map { index ->
        val file = ClassifiedFile(
            change = FileChange(
                oldPath = "src/File$index.kt",
                newPath = "src/File$index.kt",
                added = false,
                deleted = false,
                renamed = false,
                diff = "@@ -1 +1 @@\n+val a = $index",
                linesAdded = 1
            ),
            group = ChangeGroup.DOMAIN,
            annotatedDiff = "+ 1 | val a = $index"
        )
        ChunkReviewInput(
            overview = MergeRequestOverview(
                iid = 1,
                title = "MR",
                description = null,
                author = "autor",
                sourceBranch = "feature",
                targetBranch = "main",
                labels = emptyList(),
                commits = emptyList(),
                files = listOf(file)
            ),
            chunkIndex = index,
            chunkCount = count,
            group = ChangeGroup.DOMAIN,
            files = listOf(file),
            relatedContext = emptyList(),
            discussions = emptyList(),
            understanding = null,
            architecturalSignals = emptyList()
        )
    }

    private class RecordingProvider : LlmProvider {
        override val name: String = "recording"
        private val received = Collections.synchronizedList(mutableListOf<String>())

        val labels: List<String> get() = synchronized(received) { received.toList() }

        override suspend fun complete(request: LlmRequest): LlmResponse {
            received += request.label
            return LlmResponse("""{"summary": "ok", "findings": []}""")
        }
    }
}
