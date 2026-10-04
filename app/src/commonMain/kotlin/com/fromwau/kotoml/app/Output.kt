package com.fromwau.kotoml.app

import com.fromwau.kotoml.Decor
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.StandardTable
import com.fromwau.kotoml.Value
import com.fromwau.kotoml.toInlineTable
import com.fromwau.kotoml.toTomlArray
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
internal data class Selection(
    val items: List<Selected>,
    val stream: Boolean,
    val lineEnding: String = "\n",
)

/**
 * [selection] as TOML, each item on its own lines, a table set apart from its neighbours by a blank line; with
 * [raw], a string is printed bare instead of quoted.
 */
internal fun Selection.print(raw: Boolean): String {
    val printed = StringBuilder()
    var previousIsTable = false
    for ((index, selected) in items.withIndex()) {
        val isTable = selected.item.standardTableOrNull != null || selected.item.arrayOfTablesOrNull != null
        if (index > 0) printed.append(if (isTable || previousIsTable) lineEnding + lineEnding else lineEnding)
        val item = selected.item
        printed.append(if (raw) item.stringOrNull ?: selected.toToml() else selected.toToml())
        previousIsTable = isTable
    }
    return printed.toString()
}

internal fun Selection.toJson(): JsonElement =
    if (stream) JsonArray(items.map { it.item.toJson() }) else items.singleOrNull()?.item?.toJson() ?: JsonNull

internal object SelectionSerializer : KSerializer<Selection> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Selection) {
        encoder.encodeSerializableValue(JsonElement.serializer(), value.toJson())
    }

    override fun deserialize(decoder: Decoder): Selection {
        val json = decoder.decodeSerializableValue(JsonElement.serializer())
        val item = json.toValue()?.let { Item(it) } ?: Item()
        return Selection(listOf(Selected(item, emptyList())), false)
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
    is JsonArray -> Value.Array(mapNotNull { it.toValue() }.toTomlArray())
    is JsonObject -> Value.InlineTable(
        entries
            .mapNotNull { (key, element) -> element.toValue()?.let { key to it } }
            .toInlineTable(),
    )
}

/** An empty item prints as `null`, which TOML has no word for, as jq prints a missing key. */
internal fun Selected.toToml(): String {
    item.standardTableOrNull?.let { table ->
        // The text around a table belongs to its header, which is not printed, and its lines print as its own.
        val printed = table.copy().also {
            it.decor = Decor()
            it.isDotted = false
        }
        return TomlDocument(printed).toString().trimEnd()
    }
    item.arrayOfTablesOrNull?.let { tables ->
        // Printed under the header path it has in the file, inside parents that print nothing themselves. Only a
        // key reaches an array of tables, so the path is never empty.
        val holder = StandardTable().also { it[path.last()] = tables }
        val root = path.dropLast(1).foldRight(holder) { key, child ->
            child.isImplicit = true
            StandardTable().also { it[key] = child }
        }
        return TomlDocument(root).toString().trim()
    }
    val value = item.valueOrNull?.copy() ?: return "null"
    value.decor = Decor()
    return value.toString()
}

internal fun Item.toJson(): JsonElement {
    valueOrNull?.let { return it.toJson() }
    tableOrNull?.let { table ->
        return JsonObject(table.entries.associate { (key, item) -> key to item.toJson() })
    }
    arrayOfTablesOrNull?.let { tables ->
        return JsonArray(tables.map { Item(it).toJson() })
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
    is Value.Array -> JsonArray(array.map { it.toJson() })
    is Value.InlineTable -> JsonObject(table.associate { (key, value) -> key to value.toJson() })
}

internal fun Double.tomlName(): String = when {
    isNaN() -> "nan"
    this > 0 -> "inf"
    else -> "-inf"
}
