package com.fromwau.kotoml.app

import com.fromwau.kotoml.Decor
import com.fromwau.kotoml.DocumentMut
import com.fromwau.kotoml.InlineTable
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.TomlArray
import com.fromwau.kotoml.Value
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What `get` selected. A filter that iterates selects a stream, which `--json` prints as one array; any other
 * filter selects exactly one item, which `--json` prints as itself.
 */
@Serializable(with = SelectionSerializer::class)
internal data class Selection(val items: List<Item>, val stream: Boolean)

/** [selection] as TOML, one item per line; with [raw], a string is printed bare instead of quoted. */
internal fun Selection.print(raw: Boolean): String = items.joinToString("\n") { item ->
    if (raw) item.stringOrNull ?: item.toToml() else item.toToml()
}

internal fun Selection.toJson(): JsonElement =
    if (stream) JsonArray(items.map { it.toJson() }) else items.singleOrNull()?.toJson() ?: JsonNull

internal object SelectionSerializer : KSerializer<Selection> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Selection) {
        encoder.encodeSerializableValue(JsonElement.serializer(), value.toJson())
    }

    override fun deserialize(decoder: Decoder): Selection {
        val json = decoder.decodeSerializableValue(JsonElement.serializer())
        return Selection(listOf(Item.from(json.toValue() ?: return Selection(listOf(Item.none()), false))), false)
    }
}

/** Plain JSON as the TOML value closest to it; null for JSON `null`, which TOML cannot hold. */
private fun JsonElement.toValue(): Value? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> when {
        isString -> Value.String(content)
        content == "true" || content == "false" -> Value.Boolean(content == "true")
        else -> content.toLongOrNull()?.let { Value.Integer(it) } ?: content.toDoubleOrNull()?.let { Value.Float(it) }
    }
    is JsonArray -> Value.Array(TomlArray.fromIter(mapNotNull { it.toValue() }))
    is JsonObject -> Value.InlineTable(
        InlineTable.fromIter(entries.mapNotNull { (key, element) -> element.toValue()?.let { key to it } }),
    )
}

/** An empty item prints as `null`, which TOML has no word for, as jq prints a missing key. */
internal fun Item.toToml(): String {
    asTable()?.let { table ->
        // The text around a table belongs to its header, which is not printed.
        val printed = table.clone().also { it.decor = Decor() }
        return DocumentMut(printed).toString().trimEnd()
    }
    asArrayOfTables()?.let { tables ->
        return Value.Array(tables.clone().intoArray())
            .toString()
            .trim()
    }
    val value = asValue()?.copy() ?: return "null"
    value.decor = Decor()
    return value.toString()
}

internal fun Item.toJson(): JsonElement {
    asValue()?.let { return it.toJson() }
    asTableLike()?.let { table ->
        return JsonObject(table.iterItems().asSequence().associate { (key, item) -> key to item.toJson() })
    }
    asArrayOfTables()?.let { tables ->
        return JsonArray(tables.iter().asSequence().map { Item.from(it).toJson() }.toList())
    }
    return JsonNull
}

private fun Value.toJson(): JsonElement = when (this) {
    is Value.String -> JsonPrimitive(value)
    is Value.Integer -> JsonPrimitive(value)
    // JSON has no word for these, so they are spelled as TOML spells them.
    is Value.Float -> value.let { if (it.isFinite()) JsonPrimitive(it) else JsonPrimitive(it.tomlName()) }
    is Value.Boolean -> JsonPrimitive(value)
    is Value.Datetime -> JsonPrimitive(value.toString())
    is Value.Array -> JsonArray(array.iter().asSequence().map { it.toJson() }.toList())
    is Value.InlineTable -> JsonObject(table.iter().asSequence().associate { (key, value) -> key to value.toJson() })
}

internal fun Double.tomlName(): String = when {
    isNaN() -> "nan"
    this > 0 -> "inf"
    else -> "-inf"
}
