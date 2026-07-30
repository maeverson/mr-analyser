package com.mranalyser.application.review

import com.mranalyser.application.port.ChangedFileQuery
import com.mranalyser.domain.model.ChangeGroup

/**
 * Descobre relações estruturais **entre os arquivos alterados no próprio MR** (item 27).
 *
 * O grafo resultante é enviado à etapa cross-file para que o modelo saiba quais pares vale a
 * pena confrontar, em vez de comparar todos contra todos.
 */
class FileRelationDetector {
    fun detect(files: List<ClassifiedFile>, queries: Map<String, ChangedFileQuery>): List<FileRelation> {
        val relations = mutableListOf<FileRelation>()

        files.forEach { source ->
            val sourceQuery = queries[source.path] ?: return@forEach

            files.forEach inner@{ target ->
                if (source.path == target.path) {
                    return@inner
                }
                val targetQuery = queries[target.path] ?: return@inner

                reasonFor(source, sourceQuery, target, targetQuery)?.let { reason ->
                    relations += FileRelation(source.path, target.path, reason)
                }
            }
        }

        return relations.distinctBy { "${it.from}|${it.to}" }.take(MAX_RELATIONS)
    }

    private fun reasonFor(
        source: ClassifiedFile,
        sourceQuery: ChangedFileQuery,
        target: ClassifiedFile,
        targetQuery: ChangedFileQuery
    ): String? {
        val targetSymbols = targetQuery.declaredSymbols.filter { it.first().isUpperCase() }

        if (targetSymbols.any { it in sourceQuery.superTypes }) {
            return "implementa/estende tipo declarado no destino"
        }

        if (targetSymbols.any { symbol -> sourceQuery.imports.any { it.endsWith(".$symbol") || it.endsWith("/$symbol") } }) {
            return "importa tipo declarado no destino"
        }

        if (targetSymbols.any { it in sourceQuery.referencedTypes }) {
            return "referencia tipo declarado no destino"
        }

        if (target.group == ChangeGroup.TEST && isTestOf(target.path, source.path)) {
            return "teste correspondente"
        }

        layerFlow(source.group, target.group)?.let { return it }

        if (sameFeatureStem(source.path, target.path)) {
            return "mesmo domínio funcional (nome relacionado)"
        }

        return null
    }

    /** Fluxos de camada clássicos, na direção em que a inconsistência de contrato costuma aparecer. */
    private fun layerFlow(from: ChangeGroup, to: ChangeGroup): String? = when {
        from == ChangeGroup.API && to == ChangeGroup.APPLICATION -> "fluxo API -> aplicação"
        from == ChangeGroup.APPLICATION && to == ChangeGroup.PERSISTENCE -> "fluxo aplicação -> persistência"
        from == ChangeGroup.APPLICATION && to == ChangeGroup.INTEGRATION -> "fluxo aplicação -> integração"
        from == ChangeGroup.APPLICATION && to == ChangeGroup.DOMAIN -> "fluxo aplicação -> domínio"
        from == ChangeGroup.MESSAGING && to == ChangeGroup.APPLICATION -> "fluxo mensageria -> aplicação"
        from == ChangeGroup.MIGRATION && to == ChangeGroup.PERSISTENCE -> "migration -> entidade/repository"
        from == ChangeGroup.CONTRACT && to == ChangeGroup.API -> "contrato -> API"
        from == ChangeGroup.CONTRACT && to == ChangeGroup.MESSAGING -> "contrato -> evento"
        else -> null
    }

    private fun isTestOf(testPath: String, sourcePath: String): Boolean {
        val sourceStem = sourcePath.substringAfterLast('/').substringBeforeLast('.')
        val testStem = testPath.substringAfterLast('/').substringBeforeLast('.')
        return sourceStem.isNotBlank() &&
            testStem != sourceStem &&
            testStem.contains(sourceStem, ignoreCase = true)
    }

    private fun sameFeatureStem(a: String, b: String): Boolean {
        val stemA = featureStem(a)
        val stemB = featureStem(b)
        return stemA.isNotBlank() && stemA == stemB
    }

    private fun featureStem(path: String): String {
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        val withoutSuffix = LAYER_SUFFIXES.fold(name) { acc, suffix ->
            if (acc.length > suffix.length + 2 && acc.endsWith(suffix, ignoreCase = true)) {
                acc.dropLast(suffix.length)
            } else {
                acc
            }
        }
        return withoutSuffix.lowercase().takeIf { it.length >= 4 }.orEmpty()
    }

    private companion object {
        const val MAX_RELATIONS = 60

        val LAYER_SUFFIXES = listOf(
            "IntegrationTest", "ITCase", "Test", "Tests", "IT", "Spec",
            "ControllerImpl", "ServiceImpl", "RepositoryImpl",
            "Controller", "Resource", "Service", "UseCase", "Repository", "Dao",
            "Entity", "Dto", "Request", "Response", "Mapper", "Client", "Gateway",
            "Adapter", "Consumer", "Producer", "Listener", "Publisher", "Handler",
            "Factory", "Builder", "Config", "Configuration", "Impl"
        )
    }
}
