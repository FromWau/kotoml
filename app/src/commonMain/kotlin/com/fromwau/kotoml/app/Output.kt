package com.fromwau.kotoml.app

import com.fromwau.kern.result.getOrNull
import com.fromwau.kotoml.Decor
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.StandardTable
import com.fromwau.kotoml.Value
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
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
 * What `get` selected from [document]. A filter that iterates selects a stream, which `--json` prints as one
 * array; any other filter selects exactly one item, which `--json` prints as itself.
 */
@Serializable(with = SelectionSerializer::class)
internal data class Selection(
    val items: List<Selected>,
    val stream: Boolean,
    val document: TomlDocument,
)

/** [print] as `get` prints it for a person, ready for klap, which ends any text but an empty one with `\n`. */
internal fun Selection.humanText(raw: Boolean): String = print(raw).removeSuffix("\n")

/**
 * [selection] as TOML, each item on its own lines, a table set apart from its neighbours by a blank line; with
 * [raw], a string is printed bare instead of quoted. A value's line ends in `\n`, while a table's lines keep the
 * endings they have in the file.
 */
internal fun Selection.print(raw: Boolean): String {
    val printed = StringBuilder()
    var previous: Printed? = null
    for (selected in items) {
        val item = selected.item
        val current = when {
            // Only the root is a table no key leads to.
            selected.path.isEmpty() && item.standardTableOrNull != null -> document.asWritten()
            raw && item.stringOrNull != null -> Printed(item.stringOrNull.orEmpty(), "\n", isTable = false)
            else -> selected.toToml()
        }
        // A blank line sets a table apart, and ends like the table's own lines.
        if (previous != null && (current.isTable || previous.isTable)) {
            printed.append(if (previous.isTable) previous.lineEnding else current.lineEnding)
        }
        printed.append(current.text).append(current.lineEnding)
        previous = current
    }
    return printed.toString()
}

/** An item's lines as `get` prints them, without the [lineEnding] that ends the last one. */
internal data class Printed(
    val text: String,
    val lineEnding: String,
    val isTable: Boolean,
)

/** [text] without the space and line breaks after its last line, ended by the line break that followed it. */
private fun printedTable(text: String): Printed {
    val lines = text.trimEnd()
    val ending = if (text.startsWith("\r\n", lines.length)) "\r\n" else "\n"
    return Printed(lines, ending, isTable = true)
}

internal fun Selection.toJson(): JsonElement =
    if (stream) JsonArray(items.map { it.item.toJson() }) else items.singleOrNull()?.item?.toJson() ?: JsonNull

internal object SelectionSerializer : KSerializer<Selection> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Selection) {
        encoder.encodeSerializableValue(JsonElement.serializer(), value.toJson())
    }

    // klap only writes a command's result, so a selection is never read back.
    override fun deserialize(decoder: Decoder): Selection = throw SerializationException("a selection is only written")
}

/** The whole document as written, with the comments and blank lines after its last item. */
private fun TomlDocument.asWritten(): Printed {
    val text = toString()
    val ending = when {
        text.endsWith("\r\n") -> "\r\n"
        text.endsWith("\n") -> "\n"
        else -> ""
    }
    // Text without a final newline prints with `\n`, as any output line ends.
    return Printed(text.removeSuffix(ending), ending.ifEmpty { "\n" }, isTable = true)
}

/** The selected item as TOML: a table as its lines, an array of tables under its headers, a value as written. */
internal fun Selected.toToml(): Printed {
    item.standardTableOrNull?.let { table ->
        // The text around a table belongs to its header, which is not printed, and its lines print as its own.
        val printed = table.copy().also {
            it.decor = Decor()
            it.isDotted = false
        }
        return printedTable(TomlDocument(printed).toString())
    }
    item.arrayOfTablesOrNull?.let { tables ->
        // Printed under the header path it has in the file, inside parents that print nothing themselves. Only a
        // key reaches an array of tables, so the path is never empty. A copy stored nowhere keeps the comments above
        // its first header, which storing the file's own array would leave behind.
        val holder = tableHolding(path.last(), Item(tables.copy()))
        val root = path.dropLast(1).foldRight(holder) { key, child ->
            child.isImplicit = true
            tableHolding(key, Item(child))
        }
        return printedTable(TomlDocument(root).toString().trimStart())
    }
    val value = checkNotNull(item.valueOrNull) { "an item holds a value when it holds no table" }
    return Printed(value.toString(), "\n", isTable = false)
}

/** A table holding a copy of [item], read from a file under the same keys, which therefore nests as it did there. */
private fun tableHolding(
    key: String,
    item: Item,
): StandardTable {
    val table = StandardTable()
    checkNotNull(table.set(key, item).getOrNull()) { "content read from a file fits under the keys it stood at" }
    return table
}

internal fun Item.toJson(): JsonElement {
    valueOrNull?.let { return it.toJson() }
    tableOrNull?.let { table ->
        return JsonObject(table.entries.associate { (key, item) -> key to item.toJson() })
    }
    val tables = checkNotNull(arrayOfTablesOrNull) { "an item holds a value, a table or an array of tables" }
    return JsonArray(tables.map { Item(it).toJson() })
}

private fun Value.toJson(): JsonElement = when (this) {
    is Value.String -> JsonPrimitive(value)
    is Value.Integer -> JsonPrimitive(value)
    // JSON has no word for these, so they are spelled as TOML spells them.
    is Value.Float -> value.let { if (it.isFinite()) JsonPrimitive(it) else JsonPrimitive(it.tomlName()) }
    is Value.Boolean -> JsonPrimitive(value)
    is Value.Datetime -> JsonPrimitive(value.withSeconds().toString())
    is Value.Array -> JsonArray(array.map { it.toJson() })
    is Value.InlineTable -> JsonObject(table.associate { (key, value) -> key to value.toJson() })
}

internal fun Double.tomlName(): String = when {
    isNaN() -> "nan"
    this > 0 -> "inf"
    else -> "-inf"
}
