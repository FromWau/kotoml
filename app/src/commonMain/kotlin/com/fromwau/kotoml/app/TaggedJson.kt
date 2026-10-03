package com.fromwau.kotoml.app

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.Datetime
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.InlineTable
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.Table
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
    tableOrNull?.let { table ->
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

/** The document toml-test's tagged JSON [text] describes, which its encoder protocol reads. */
internal fun parseTaggedJson(text: String): Result<TomlDocument, AppError> {
    val json = try {
        Json.parseToJsonElement(text)
    } catch (error: SerializationException) {
        return Err(AppError.InvalidJson(error.message))
    }
    if (json !is JsonObject) return Err(AppError.NotTaggedJson("$"))
    val table = Table()
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
            scalarOrNull()?.let { (type, text) -> return scalarValue(type, text)?.let { Ok(it) } ?: notTagged(path) }
            val table = InlineTable()
            for ((key, element) in this) {
                when (val value = element.toValue("$path.$key")) {
                    is Result.Success -> table[key] = value.value
                    is Result.Error -> return value
                }
            }
            return Ok(Value.InlineTable(table))
        }
        else -> return notTagged(path)
    }
}

private fun notTagged(path: String): Result<Nothing, AppError> = Err(AppError.NotTaggedJson(path))

/** The type and text of a tagged scalar, or null when this object is a table. */
private fun JsonObject.scalarOrNull(): Pair<String, String>? {
    if (keys != setOf("type", "value")) return null
    val type = (get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val value = (get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return type to value
}

private fun scalarValue(type: String, text: String): Value? = when (type) {
    "string" -> Value.String(text)
    "integer" -> text.toLongOrNull()?.let { Value.Integer(it) }
    "float" -> when (text) {
        "nan", "+nan", "-nan" -> Value.Float(Double.NaN)
        "inf", "+inf" -> Value.Float(Double.POSITIVE_INFINITY)
        "-inf" -> Value.Float(Double.NEGATIVE_INFINITY)
        else -> text.toDoubleOrNull()?.let { Value.Float(it) }
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
