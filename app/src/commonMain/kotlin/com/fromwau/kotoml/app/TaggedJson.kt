package com.fromwau.kotoml.app

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.mapError
import com.fromwau.kotoml.Datetime
import com.fromwau.kotoml.InlineTable
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.StandardTable
import com.fromwau.kotoml.TextPosition
import com.fromwau.kotoml.Time
import com.fromwau.kotoml.TomlArray
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.TomlEditError
import com.fromwau.kotoml.TomlIoError
import com.fromwau.kotoml.TomlWriteError
import com.fromwau.kotoml.Value
import kotlinx.io.Buffer
import kotlinx.io.readString
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
    is Value.Datetime -> scalar(value.tag(), value.withSeconds().toString())
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

/** TOML's limit on nested arrays and inline tables, plus the root object; a tagged value's object is no level. */
private const val MAX_JSON_NESTING = TomlDocument.NESTING_LIMIT + 1

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

/**
 * This document's text, or the tagged JSON path of the first string or key that UTF-8 cannot write; [path] names
 * the JSON it was read from.
 */
internal fun TomlDocument.encodedText(path: String = "-"): Result<String, AppError> {
    val buffer = Buffer()
    return when (val written = writeTo(buffer)) {
        is Result.Success -> Ok(buffer.readString())
        is Result.Error -> when (val error = written.error) {
            is TomlWriteError.NotUnicode -> {
                val at = error.keyPath?.joinToString("") { ".$it" }.orEmpty()
                Err(AppError.NotTaggedJson(path, "$$at", TaggedJsonProblem.NotUnicode))
            }
            is TomlWriteError.TooDeep -> Err(AppError.JsonTooDeep(path, MAX_JSON_NESTING))
            is TomlWriteError.NotInToml10 -> error("a document built from JSON writes TOML 1.1")
            is TomlIoError -> error("a Buffer accepts every write")
        }
    }
}

/** The document toml-test's tagged JSON [text], read from [path], describes, which its encoder protocol reads. */
internal fun parseTaggedJson(
    text: String,
    path: String = "-",
): Result<TomlDocument, AppError> = text.toDocument().mapError { it.from(path) }

/** Why JSON text does not describe a document, before it is known which input it came from. */
private sealed interface JsonProblem : IError {
    data class Invalid(val position: TextPosition?) : JsonProblem

    data class NotTagged(
        val at: String,
        val reason: TaggedJsonProblem,
    ) : JsonProblem

    data object TooDeep : JsonProblem
}

private fun JsonProblem.from(path: String): AppError = when (this) {
    is JsonProblem.Invalid -> AppError.InvalidJson(path, position)
    is JsonProblem.NotTagged -> AppError.NotTaggedJson(path, at, reason)
    JsonProblem.TooDeep -> AppError.JsonTooDeep(path, MAX_JSON_NESTING)
}

private fun String.toDocument(): Result<TomlDocument, JsonProblem> {
    val text = this
    // kotlinx's JSON reader recurses once per level, which on Kotlin/Native crashes rather than fails. Brackets
    // cannot tell a tagged value's object from a table, so this lets one level more through for it.
    if (text.nesting() > MAX_JSON_NESTING + 1) return Err(JsonProblem.TooDeep)
    val json = try {
        Json.parseToJsonElement(text)
    } catch (error: SerializationException) {
        return Err(JsonProblem.Invalid(text.positionOrNull(error.message)))
    }
    if (json !is JsonObject) return notTagged("$", TaggedJsonProblem.RootNotObject)
    val table = StandardTable()
    for ((key, element) in json) {
        when (val value = element.toValue("$.$key")) {
            is Result.Success -> table.set(key, value.value).orJsonTooDeep()?.let { return it }
            is Result.Error -> return value
        }
    }
    return Ok(TomlDocument(table))
}

/** This element as a TOML value. */
private fun JsonElement.toValue(path: String): Result<Value, JsonProblem> {
    when (this) {
        is JsonArray -> {
            val array = TomlArray()
            for ((index, element) in withIndex()) {
                when (val value = element.toValue("$path[$index]")) {
                    is Result.Success -> array.add(value.value).orJsonTooDeep()?.let { return it }
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
                    is Result.Success -> table.set(key, value.value).orJsonTooDeep()?.let { return it }
                    is Result.Error -> return value
                }
            }
            return Ok(Value.InlineTable(table))
        }
        else -> return notTagged(path, TaggedJsonProblem.Untagged)
    }
}

/** The error to return for a store that nested too deep, or null when it stored. */
private fun EmptyResult<TomlEditError>.orJsonTooDeep(): Result<Nothing, JsonProblem>? =
    (this as? Result.Error)?.let { Err(JsonProblem.TooDeep) }

private fun notTagged(
    at: String,
    reason: TaggedJsonProblem,
): Result<Nothing, JsonProblem> = Err(JsonProblem.NotTagged(at, reason))

private val TOML_TYPES =
    setOf("string", "integer", "float", "bool", "datetime", "datetime-local", "date-local", "time-local")

/** The value this object, tagged [type], describes. */
private fun JsonObject.taggedValue(
    type: String,
    path: String,
): Result<Value, JsonProblem> {
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
        // A Long has no negative zero, so `-0` keeps its sign only through its spelling.
        is Value.Integer -> {
            val negativeZero = number.value == 0L && text.startsWith('-')
            Value.Float(if (negativeZero) -0.0 else number.value.toDouble())
        }
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
private fun Datetime.tag(): String = when (this) {
    is Datetime.OffsetDateTime -> "datetime"
    is Datetime.LocalDateTime -> "datetime-local"
    is Datetime.LocalDate -> "date-local"
    is Datetime.LocalTime -> "time-local"
}

// toml-test reads a time with its seconds, which TOML 1.1 lets a document leave out, and so does JSON's RFC 3339.
internal fun Datetime.withSeconds(): Datetime = when (this) {
    is Datetime.OffsetDateTime -> copy(time = time.withSeconds())
    is Datetime.LocalDateTime -> copy(time = time.withSeconds())
    is Datetime.LocalDate -> this
    is Datetime.LocalTime -> copy(time = time.withSeconds())
}

private fun Time.withSeconds(): Time = if (second != null) this else Time.of(hour, minute, 0).getOrElse { this }
