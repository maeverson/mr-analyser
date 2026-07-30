package com.mranalyser.application.llm.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Leitura tolerante de respostas do LLM.
 *
 * Optou-se por ler `JsonObject` manualmente em vez de usar DTOs `@Serializable`: os modelos
 * violam o tipo declarado com frequência (`"line": "84"`, `"questions": "texto"`,
 * `"confidence": 85`, chave em snake_case), e com DTOs estritos qualquer uma dessas variações
 * derruba a desserialização inteira e perde todos os findings do chunk.
 */
internal object LenientJson {
    // Vírgula sobrando já é removida por JsonExtractor, evitando depender de API experimental.
    val instance = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseObject(text: String): JsonObject? =
        runCatching { instance.parseToJsonElement(text) }.getOrNull() as? JsonObject
}

/** Busca a chave tolerando camelCase/snake_case e diferença de caixa. */
internal fun JsonObject.field(key: String): JsonElement? {
    this[key]?.let { return it.takeUnless { element -> element is JsonNull } }

    val normalizedTarget = key.normalizedKey()
    return entries
        .firstOrNull { it.key.normalizedKey() == normalizedTarget }
        ?.value
        ?.takeUnless { it is JsonNull }
}

private fun String.normalizedKey(): String = lowercase().replace("_", "").replace("-", "")

internal fun JsonObject.str(key: String): String? {
    val element = field(key) ?: return null
    val value = when (element) {
        is JsonPrimitive -> element.content
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString("\n")
        is JsonObject -> element.entries.joinToString("\n") { "${it.key}: ${(it.value as? JsonPrimitive)?.content}" }
        else -> null
    } ?: return null

    val trimmed = value.trim()
    return trimmed.takeIf { it.isNotBlank() && !PLACEHOLDERS.contains(it.lowercase()) }
}

internal fun JsonObject.int(key: String): Int? {
    val primitive = field(key) as? JsonPrimitive ?: return null
    primitive.intOrNull?.let { return it }
    // Aceita "84", "L84", "linha 84" e "84-90".
    return Regex("""\d+""").find(primitive.content)?.value?.toIntOrNull()
}

internal fun JsonObject.dbl(key: String): Double? {
    val primitive = field(key) as? JsonPrimitive ?: return null
    primitive.doubleOrNull?.let { return it }
    return primitive.content.replace(',', '.').toDoubleOrNull()
}

internal fun JsonObject.bool(key: String): Boolean? {
    val primitive = field(key) as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.content.trim().lowercase()) {
        "true", "yes", "sim", "1" -> true
        "false", "no", "nao", "não", "0" -> false
        else -> null
    }
}

/** Aceita array de strings, array de objetos, string única ou texto com linhas/bullets. */
internal fun JsonObject.strList(key: String): List<String> {
    val element = field(key) ?: return emptyList()
    val raw = when (element) {
        is JsonArray -> element.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.content
                is JsonObject -> item.str("text") ?: item.str("description") ?: item.str("title")
                    ?: item.str("question") ?: item.str("point")
                else -> null
            }
        }

        is JsonPrimitive -> element.content.split('\n')
        else -> emptyList()
    }

    return raw
        .map { it.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim() }
        .filter { it.isNotBlank() && !PLACEHOLDERS.contains(it.lowercase()) }
        .distinct()
}

internal fun JsonObject.objList(key: String): List<JsonObject> {
    val element = field(key) ?: return emptyList()
    return when (element) {
        is JsonArray -> element.filterIsInstance<JsonObject>()
        is JsonObject -> listOf(element)
        else -> emptyList()
    }
}

private val PLACEHOLDERS = setOf(
    "null", "none", "n/a", "na", "-", "--", "nenhum", "nenhuma", "não aplicável",
    "nao aplicavel", "not applicable", "unknown", "desconhecido", "todo", "..."
)
