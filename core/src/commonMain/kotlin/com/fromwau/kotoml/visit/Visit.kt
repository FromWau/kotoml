package com.fromwau.kotoml.visit

import com.fromwau.kotoml.ArrayOfTables
import com.fromwau.kotoml.Datetime
import com.fromwau.kotoml.DocumentMut
import com.fromwau.kotoml.Formatted
import com.fromwau.kotoml.InlineTable
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.Table
import com.fromwau.kotoml.TableLike
import com.fromwau.kotoml.TomlArray
import com.fromwau.kotoml.Value

/**
 * Walks a document without changing it. Each function is a hook for one kind of node; by default it
 * visits the node's children through the matching top-level function, so an override calls that function to
 * keep descending.
 *
 * ```kotlin
 * class StringCollector : Visit {
 *     val strings = mutableListOf<String>()
 *     override fun visitString(node: Formatted<String>) {
 *         strings += node.value()
 *     }
 * }
 *
 * val document = DocumentMut.parse("laputa = \"sky-castle\"\nthe-force = { value = \"surrounds-you\" }\n")
 *     .getOrNull()!!
 * val collector = StringCollector()
 * collector.visitDocument(document)
 * collector.strings // [sky-castle, surrounds-you]
 * ```
 */
public interface Visit {
    /** Visits the document's root table. */
    public fun visitDocument(node: DocumentMut): Unit = visitDocument(this, node)

    /** Visits whatever the item holds. */
    public fun visitItem(node: Item): Unit = visitItem(this, node)

    /** Visits a table's entries. */
    public fun visitTable(node: Table): Unit = visitTable(this, node)

    /** Visits an inline table's entries. */
    public fun visitInlineTable(node: InlineTable): Unit = visitInlineTable(this, node)

    /** Visits the entries of a table or inline table. */
    public fun visitTableLike(node: TableLike): Unit = visitTableLike(this, node)

    /** Visits one entry of a table or inline table. */
    public fun visitTableLikeKv(key: String, node: Item): Unit = visitTableLikeKv(this, key, node)

    /** Visits an array's values. */
    public fun visitArray(node: TomlArray): Unit = visitArray(this, node)

    /** Visits the tables of an array of tables. */
    public fun visitArrayOfTables(node: ArrayOfTables): Unit = visitArrayOfTables(this, node)

    /** Visits whatever the value holds. */
    public fun visitValue(node: Value): Unit = visitValue(this, node)

    /** Visits a boolean; does nothing by default. */
    public fun visitBoolean(node: Formatted<Boolean>) {}

    /** Visits a date-time; does nothing by default. */
    public fun visitDatetime(node: Formatted<Datetime>) {}

    /** Visits a float; does nothing by default. */
    public fun visitFloat(node: Formatted<Double>) {}

    /** Visits an integer; does nothing by default. */
    public fun visitInteger(node: Formatted<Long>) {}

    /** Visits a string; does nothing by default. */
    public fun visitString(node: Formatted<String>) {}
}

/** Visits [node]'s root table. */
public fun visitDocument(visitor: Visit, node: DocumentMut) {
    visitor.visitTable(node.asTable())
}

/** Visits what [node] holds: its value, table or array of tables. */
public fun visitItem(visitor: Visit, node: Item) {
    node.asValue()?.let { visitor.visitValue(it) }
    node.asTable()?.let { visitor.visitTable(it) }
    node.asArrayOfTables()?.let { visitor.visitArrayOfTables(it) }
}

/** Visits [node] as a table-like. */
public fun visitTable(visitor: Visit, node: Table) {
    visitor.visitTableLike(node)
}

/** Visits [node] as a table-like. */
public fun visitInlineTable(visitor: Visit, node: InlineTable) {
    visitor.visitTableLike(node)
}

/** Visits every entry of [node]. */
public fun visitTableLike(visitor: Visit, node: TableLike) {
    for ((key, item) in node.iterItems()) visitor.visitTableLikeKv(key, item)
}

/** Visits the item of one entry. */
public fun visitTableLikeKv(visitor: Visit, key: String, node: Item) {
    visitor.visitItem(node)
}

/** Visits every value of [node]. */
public fun visitArray(visitor: Visit, node: TomlArray) {
    for (value in node.iter()) visitor.visitValue(value)
}

/** Visits every table of [node]. */
public fun visitArrayOfTables(visitor: Visit, node: ArrayOfTables) {
    for (table in node.iter()) visitor.visitTable(table)
}

/** Visits what [node] holds. */
public fun visitValue(visitor: Visit, node: Value) {
    when (node) {
        is Value.String -> visitor.visitString(node.formatted)
        is Value.Integer -> visitor.visitInteger(node.formatted)
        is Value.Float -> visitor.visitFloat(node.formatted)
        is Value.Boolean -> visitor.visitBoolean(node.formatted)
        is Value.Datetime -> visitor.visitDatetime(node.formatted)
        is Value.Array -> visitor.visitArray(node.array)
        is Value.InlineTable -> visitor.visitInlineTable(node.table)
    }
}
