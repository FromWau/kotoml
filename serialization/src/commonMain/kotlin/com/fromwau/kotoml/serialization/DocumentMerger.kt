package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.getOrNull
import com.fromwau.kotoml.Datetime
import com.fromwau.kotoml.InlineTable
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.Table
import com.fromwau.kotoml.TableLike
import com.fromwau.kotoml.Value

/**
 * Which fields of an encoded value hold their default, told by the same value encoded without defaults. A
 * field at its default updates a key the document has but adds none, and what is added is written without
 * defaults, as `encodeToString` would write it.
 */
internal sealed interface Defaults {
    /** The format writes defaults, so no field counts as one. */
    data object Written : Defaults

    /** The whole value is its field's default, so every field in it holds its default too. */
    data object All : Defaults

    /** [node] is the value encoded without defaults: a field it lacks holds its default. */
    class Sparse(val node: TomlNode) : Defaults

    fun isDefault(key: String): Boolean = when (this) {
        Written -> false
        All -> true
        is Sparse -> node is TomlNode.Struct && key !in node.entries
    }

    fun child(key: String): Defaults = when (this) {
        Written, All -> this
        is Sparse -> (node as? TomlNode.Struct)?.entries?.get(key)?.let(::Sparse) ?: All
    }

    fun element(index: Int): Defaults = when (this) {
        Written, All -> this
        is Sparse -> (node as? TomlNode.Sequence)?.elements?.getOrNull(index)?.let(::Sparse) ?: Written
    }

    /** What to write for [full]: the value without its defaults when the format leaves them out. */
    fun written(full: TomlNode): TomlNode = (this as? Sparse)?.node ?: full
}

/**
 * Writes an encoded value into an existing document, changing only what differs. A key that stays keeps its
 * spelling, its place and the text around its value; a value that reads the same as the one there is left
 * untouched.
 */
internal class DocumentMerger(private val writer: DocumentWriter) {

    fun mergeTable(
        table: TableLike,
        full: TomlNode.Struct,
        defaults: Defaults,
    ) {
        val subtypeChanged = full.discriminator?.let { key ->
            table.item(key)?.stringOrNull != (full.entries[key] as? TomlNode.Scalar)?.value?.stringOrNull
        } ?: false
        if (full.ownsAllKeys || subtypeChanged) {
            for ((key, _) in table.items()) if (key !in full.entries) table.removeItem(key)
        }
        for (key in full.omitted) table.removeItem(key)
        for ((key, child) in full.entries) {
            val existing = table.item(key)
            val childDefaults = defaults.child(key)
            when {
                existing != null -> mergeItem(table, key, existing, child, childDefaults)
                !defaults.isDefault(key) -> put(table, key, newItem(table, childDefaults.written(child)))
            }
        }
    }

    private fun mergeItem(
        parent: TableLike,
        key: String,
        existing: Item,
        full: TomlNode,
        defaults: Defaults,
    ) {
        val existingTable = existing.tableOrNull ?: existing.inlineTableOrNull
        when {
            full is TomlNode.Struct && existingTable != null -> mergeTable(existingTable, full, defaults)
            existing.toInput()?.sameAs(full, defaults) == true -> {}
            else -> put(parent, key, replacement(parent, existing, defaults.written(full)))
        }
    }

    private fun newItem(
        parent: TableLike,
        node: TomlNode,
    ): Item = if (parent is InlineTable) Item(writer.value(node)) else writer.item(node)

    /**
     * The item replacing [existing], in the form the document wrote it: a list stays an array of tables, its
     * tables taking over the old ones' decor by position, or an inline array keeping the old array's decor.
     */
    private fun replacement(
        parent: TableLike,
        existing: Item,
        node: TomlNode,
    ): Item {
        val oldTables = existing.arrayOfTablesOrNull
        if (parent is Table && oldTables != null && node is TomlNode.Sequence && node.isTableList()) {
            val tables = writer.arrayOfTables(node)
            for ((old, new) in oldTables.zip(tables)) new.decor = old.decor
            return Item(tables)
        }
        val old = existing.valueOrNull ?: return newItem(parent, node)
        val value = writer.value(node)
        value.decor = old.decor
        return Item(value)
    }

    private fun put(
        table: TableLike,
        key: String,
        item: Item,
    ) {
        when (table) {
            is Table -> table[key] = item
            is InlineTable -> item.valueOrNull?.let { table[key] = it }
        }
    }
}

/** Whether the document already holds what [full] would write, a missing key counting when it holds a default. */
private fun TomlInput.sameAs(
    full: TomlNode,
    defaults: Defaults,
): Boolean = when (full) {
    is TomlNode.Scalar -> this is TomlInput.Scalar && full.readsBack?.invoke(value) ?: value.sameAs(full.value)
    is TomlNode.Sequence -> this is TomlInput.Sequence &&
        elements.size == full.elements.size &&
        elements.indices.all { elements[it].sameAs(full.elements[it], defaults.element(it)) }
    is TomlNode.Struct -> this is TomlInput.Table &&
        full.entries.keys.containsAll(entries.keys) &&
        full.entries.all { (key, child) ->
            val item = entries[key]
            if (item == null) defaults.isDefault(key) else item.toInput()?.sameAs(child, defaults.child(key)) == true
        }
}

private fun Value.sameAs(other: Value): Boolean = when (this) {
    is Value.String -> value == other.stringOrNull
    is Value.Integer -> value == other.longOrNull
    is Value.Float -> other.doubleOrNull?.let { value.compareTo(it) == 0 } ?: false
    is Value.Boolean -> value == other.booleanOrNull
    // A date-time read into a string field writes back as its text, which still means this date-time.
    is Value.Datetime -> value == (other.datetimeOrNull ?: other.stringOrNull?.let { Datetime.parse(it).getOrNull() })
    else -> false
}
