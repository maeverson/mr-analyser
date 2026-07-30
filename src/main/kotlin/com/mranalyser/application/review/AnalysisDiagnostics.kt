package com.mranalyser.application.review

import java.util.Collections

/**
 * Acumula avisos e contadores do quality gate (item 33).
 *
 * Thread-safe porque a etapa de review local roda os chunks concorrentemente.
 */
class AnalysisDiagnostics {
    private val warningList = Collections.synchronizedList(mutableListOf<String>())
    private val skippedList = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    var chunksAnalysed: Int = 0
        private set

    @Volatile
    var chunksFailed: Int = 0
        private set

    @Volatile
    var candidateFindings: Int = 0

    @Volatile
    var discardedByDeduplication: Int = 0

    @Volatile
    var discardedByValidation: Int = 0

    @Volatile
    var discardedByConfidence: Int = 0

    @Volatile
    var discardedAsNoise: Int = 0

    @Volatile
    var relatedContextsLoaded: Int = 0

    @Synchronized
    fun chunkSucceeded() {
        chunksAnalysed++
    }

    @Synchronized
    fun chunkFailed(label: String, reason: String) {
        chunksFailed++
        warn("review do $label falhou: $reason")
    }

    fun warn(message: String) {
        if (warningList.none { it == message }) {
            warningList += message
        }
    }

    fun skipStage(stage: String, reason: String) {
        val entry = "$stage ($reason)"
        if (skippedList.none { it == entry }) {
            skippedList += entry
        }
    }

    val warnings: List<String> get() = synchronized(warningList) { warningList.toList() }

    val skippedStages: List<String> get() = synchronized(skippedList) { skippedList.toList() }

    val degraded: Boolean get() = chunksFailed > 0 || warnings.isNotEmpty() || skippedStages.isNotEmpty()

    /** Motivos de degradação, enviados à etapa de parecer final para calibrar a confiança. */
    val degradationReasons: List<String>
        get() = buildList {
            addAll(warnings)
            skippedStages.forEach { add("etapa não executada: $it") }
        }
}
