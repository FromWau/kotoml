package com.fromwau.kotoml.app

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.Datetime
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.InlineTable
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.StandardTable
import com.fromwau.kotoml.TextPosition
import com.fromwau.kotoml.TomlArray
import com.fromwau.kotoml.Value
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// toml-test's tagged JSON: every scalar is {"type": ..., "value": "<text>"}, tables are objects, arrays arrays.

/** This document in toml-test's tagged JSON, which its decoder protocol prints. */
internal fun TomlDocument.toTaggedJson(): JsonElement = Item(root).tagged()

private fun Item.tagged(): JsonElement {
    valueOrNull?.let { return it.tagged() }
    standardTableOrNull?.let { table ->
        return JsonObject(table.associate { (key, item) -> key to item.tagged() })
    }
    return JsonArray(arrayOfTablesOrNull?.map { Item(it).tagged() }.orEmpty())
}

private fun Value.tagged(): JsonElement = when (this) {
    is Value.String -> scalar("string", value)
    is Value.Integer -> scalar("integer", value.toString())
    is Value.Float -> scalar("float", value.let { if (it.isFinite()) it.toString() else it.tomlName() })
    is Value.Boolean -> scalar("bool", value.toString())
    is Value.Datetime -> {
        // toml-test reads a time with its seconds, which TOML 1.1 lets a document leave out.
        val time = value.time?.let { it.copy(second = it.second ?: 0) }
        scalar(value.tag(), value.copy(time = time).toString())
    }
    is Value.Array -> JsonArray(array.map { it.tagged() })
    is Value.InlineTable -> JsonObject(table.associate { (key, value) -> key to value.tagged() })
}

private fun scalar(type: String, value: String): JsonElement =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

/** [text] read as a TOML value, keeping its spelling; null for text TOML does not read or that has spaces around it. */
private fun tomlNumber(text: String): Value? {
    if (text.isEmpty() || text.first().isWhitespace() || text.last().isWhitespace()) return null
    return (Value.parse(text) as? Result.Success)?.value
}

// kotlinx names where JSON broke only in its message: "Unexpected JSON token at offset 12: ...".
private val JSON_OFFSET = Regex("""at offset (\d+)""")

/** The line and column, from 1 as in TOML errors, of the offset kotlinx's [message] names; null without one. */
private fun String.positionOrNull(message: String?): TextPosition? {
    val offset = message?.let { JSON_OFFSET.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val before = substring(0, minOf(offset, length))
    val line = before.count { it == '\n' }
    return TextPosition(line + 1, before.length - before.lastIndexOf('\n'))
}

/** TOML's limit, plus the root object and the object that tags a scalar. */
private const val MAX_JSON_NESTING = TomlDocument.NESTING_LIMIT + 2

/** The deepest nesting of `[` and `{` in this JSON text, brackets inside strings not counted. */
private fun String.nesting(): Int {
    var depth = 0
    var deepest = 0
    var inString = false
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            inString && char == '\\' -> index++
            char == '"' -> inString = !inString
            inString -> {}
            char == '[' || char == '{' -> deepest = maxOf(deepest, ++depth)
            char == ']' || char == '}' -> depth--
        }
        index++
    }
    return deepest
}

/** The document toml-test's tagged JSON [text] describes, which its encoder protocol reads. */
internal fun parseTaggedJson(text: String): Result<TomlDocument, AppError> {
    // kotlinx's JSON reader recurses once per level, which on Kotlin/Native crashes rather than fails.
    if (text.nesting() > MAX_JSON_NESTING) return Err(AppError.JsonTooDeep(MAX_JSON_NESTING))
    val json = try {
        Json.parseToJsonElement(text)
    } catch (error: SerializationException) {
        return Err(AppError.InvalidJson(text.positionOrNull(error.message)))
    }
    if (json !is JsonObject) return notTagged("$", TaggedJsonProblem.RootNotObject)
    val table = StandardTable()
    for ((key, element) in json) {
        when (val value = element.toValue("$.$key")) {
            is Result.Success -> table[key] = value.value
            is Result.Error -> return value
        }
    }
    return Ok(TomlDocument(table))
}

private fun JsonElement.toValue(path: String): Result<Value, AppError> {
    when (this) {
        is JsonArray -> {
            val array = TomlArray()
            for ((index, element) in withIndex()) {
                when (val value = element.toValue("$path[$index]")) {
                    is Result.Success -> array.add(value.value)
                    is Result.Error -> return value
                }
            }
            return Ok(Value.Array(array))
        }
        is JsonObject -> {
            // A table's own `type` key holds a tagged object, never a string, so a string here tags a value.
            val type = get("type")
            if (type is JsonPrimitive && type.isString) return taggedValue(type.content, path)
            val table = InlineTable()
            for ((key, element) in this) {
                when (val value = element.toValue("$path.$key")) {
                    is Result.Success -> table[key] = value.value
                    is Result.Error -> return value
                }
            }
            return Ok(Value.InlineTable(table))
        }
        else -> return notTagged(path, TaggedJsonProblem.Untagged)
    }
}

private fun notTagged(
    path: String,
    reason: TaggedJsonProblem,
): Result<Nothing, AppError> = Err(AppError.NotTaggedJson(path, reason))

private val TOML_TYPES =
    setOf("string", "integer", "float", "bool", "datetime", "datetime-local", "date-local", "time-local")

/** The value this object, tagged [type], describes. */
private fun JsonObject.taggedValue(
    type: String,
    path: String,
): Result<Value, AppError> {
    if (type !in TOML_TYPES) return notTagged(path, TaggedJsonProblem.UnknownType(type))
    val value = get("value") ?: return notTagged(path, TaggedJsonProblem.MissingValue)
    if (value !is JsonPrimitive || !value.isString) return notTagged(path, TaggedJsonProblem.ValueNotString)
    keys.firstOrNull { it != "type" && it != "value" }?.let { return notTagged(path, TaggedJsonProblem.ExtraKey(it)) }
    return scalarValue(type, value.content)?.let { Ok(it) }
        ?: notTagged(path, TaggedJsonProblem.BadValue(type, value.content))
}

private fun scalarValue(type: String, text: String): Value? = when (type) {
    "string" -> Value.String(text)
    "integer" -> tomlNumber(text) as? Value.Integer
    "float" -> when (val number = tomlNumber(text)) {
        is Value.Float -> number
        is Value.Integer -> Value.Float(number.value.toDouble())
        else -> null
    }
    "bool" -> text.toBooleanStrictOrNull()?.let { Value.Boolean(it) }
    "datetime", "datetime-local", "date-local", "time-local" -> when (val parsed = Datetime.parse(text)) {
        is Result.Success -> parsed.value.takeIf { it.tag() == type }?.let { Value.Datetime(it) }
        is Result.Error -> null
    }
    else -> null
}

/** toml-test's tag for the kind of date-time this is. */
private fun Datetime.tag(): String = when {
    date != null && time != null && offset != null -> "datetime"
    date != null && time != null -> "datetime-local"
    date != null -> "date-local"
    else -> "time-local"
}
