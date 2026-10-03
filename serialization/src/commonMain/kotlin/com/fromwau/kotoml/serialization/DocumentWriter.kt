package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.ArrayOfTables
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.Table
import com.fromwau.kotoml.Value
import com.fromwau.kotoml.toArrayOfTables
import com.fromwau.kotoml.toInlineTable
import com.fromwau.kotoml.toTomlArray

/** Turns encoded nodes into new document items. */
internal class DocumentWriter(private val tableListStyle: TableListStyle) {

    fun table(struct: TomlNode.Struct): Table {
        val table = Table()
        for ((key, node) in struct.entries) table[key] = item(node)
        return table
    }

    /** The item for [node] in a standard table, where a class is a table and a list of classes may be `[[...]]`. */
    fun item(node: TomlNode): Item = when (node) {
        is TomlNode.Struct -> Item(table(node))
        is TomlNode.Sequence -> if (tableListStyle == TableListStyle.ArrayOfTables && node.isTableList()) {
            Item(arrayOfTables(node))
        } else {
            Item(value(node))
        }
        is TomlNode.Scalar -> Item(value(node))
    }

    /** The tables of a list for which [isTableList] holds. */
    fun arrayOfTables(node: TomlNode.Sequence): ArrayOfTables = node.elements
        .map { table(it as TomlNode.Struct) }
        .toArrayOfTables()

    /** The value for [node] inside an array or an inline table, where everything is inline. */
    fun value(node: TomlNode): Value = when (node) {
        is TomlNode.Scalar -> node.value.copy()
        is TomlNode.Sequence -> Value.Array(node.elements.map { value(it) }.toTomlArray())
        is TomlNode.Struct -> Value.InlineTable(
            node.entries
                .map { (key, child) -> key to value(child) }
                .toInlineTable(),
        )
    }
}

/** A non-empty list whose elements are all classes or maps, which TOML can write as an array of tables. */
internal fun TomlNode.Sequence.isTableList(): Boolean =
    elements.isNotEmpty() && elements.all { it is TomlNode.Struct }
