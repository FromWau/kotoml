package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/** Keeps an inner block of the TOML builders from reaching an outer block's members by accident. */
@DslMarker
public annotation class TomlDsl

/**
 * Builds a document from scratch.
 *
 * ```kotlin
 * val doc = buildTomlDocument {
 *     "title" to "kotoml"
 *     table("server") {
 *         "port" to 8080
 *         array("tags") { add("fast") }
 *     }
 *     arrayOfTables("bin") {
 *         table { "name" to "kotoml" }
 *     }
 * }.getOrElse { error(it) }
 * ```
 *
 * @return the document, or the [TomlEditError.TypeMismatch] of a block that opened a key holding another kind.
 */
public fun buildTomlDocument(block: TableScope.() -> Unit): Result<TomlDocument, TomlEditError> {
    val document = TomlDocument()
    return runScope { TableScope(document.root).block() }?.let { Err(it) } ?: Ok(document)
}

/**
 * Edits this document through the builder, all or nothing: when the block fails, the document is left exactly as it
 * was. Keys the block writes keep their place, spelling and comments, and `table(name)` creates a table that is not
 * there.
 *
 * ```kotlin
 * doc.edit {
 *     table("server") { "port" to 9090 }
 *     table("database") { "url" to "postgres://" }
 *     remove("debug")
 * }
 * ```
 *
 * An edit replaces the document's content with the edited one, so an [Item] taken from the document before a
 * successful edit no longer belongs to it; index the document again afterwards.
 *
 * @return [TomlEditError.TypeMismatch] when the block opened a key holding another kind, with nothing changed.
 */
public fun TomlDocument.edit(block: TableScope.() -> Unit): EmptyResult<TomlEditError> {
    val edited = copy()
    runScope { TableScope(edited.root).block() }?.let { return Err(it) }
    root.takeStateOf(edited.root)
    rawTrailing = edited.rawTrailing
    return Ok(Unit)
}

/** Runs [block], returning the error a scope stopped it with, or null. */
private inline fun runScope(block: () -> Unit): TomlEditError? = try {
    block()
    null
} catch (aborted: EditAborted) {
    aborted.error
}

// Stops a builder block at its first error; only the entry points catch it, so it never reaches a caller.
private class EditAborted(val error: TomlEditError) : Exception()

private fun mismatch(
    key: String,
    expected: TomlType,
    found: Item,
): Nothing = throw EditAborted(TomlEditError.TypeMismatch(key, expected, found.type))

/**
 * A block that writes keys: a table, the document root or an inline table.
 *
 * A key is written `"key" to value`. Inside a block that `to` is the builder's, so build a [Pair] there with
 * `Pair("a", 1)` instead of `"a" to 1`.
 */
@TomlDsl
public sealed class KeyedScope {
    internal abstract fun put(key: String, value: Value)

    internal abstract fun storedOrPut(key: String, value: () -> Value): Item

    /** Writes a string at this key. */
    public infix fun String.to(value: String): Unit = put(this, Value.String(value))

    /** Writes an integer at this key. */
    public infix fun String.to(value: Int): Unit = put(this, Value.Integer(value.toLong()))

    /** Writes an integer at this key. */
    public infix fun String.to(value: Long): Unit = put(this, Value.Integer(value))

    /** Writes a float at this key. */
    public infix fun String.to(value: Double): Unit = put(this, Value.Float(value))

    /** Writes a boolean at this key. */
    public infix fun String.to(value: Boolean): Unit = put(this, Value.Boolean(value))

    /** Writes a date-time at this key. */
    public infix fun String.to(value: Datetime): Unit = put(this, Value.Datetime(value))

    /** Writes a copy of [value] at this key. */
    public infix fun String.to(value: Value): Unit = put(this, value)

    /** Turns `"key" to` anything else into a compile error rather than a [Pair] nobody reads. */
    @Deprecated(
        "A key takes a String, a number, a Boolean, a Datetime or a Value; use table, inlineTable or array to nest",
        level = DeprecationLevel.ERROR,
    )
    public infix fun String.to(value: Any?): Unit = Unit

    /** Opens the array at [key], creating an empty one when the key is missing; [block] appends to it. */
    public fun array(
        key: String,
        block: ArrayScope.() -> Unit,
    ) {
        val item = storedOrPut(key) { Value.Array(TomlArray()) }
        val array = item.arrayOrNull ?: mismatch(key, TomlType.Array, item)
        ArrayScope(array).block()
    }

    /** Opens the inline table at [key], creating an empty one when the key is missing. */
    public fun inlineTable(
        key: String,
        block: InlineTableScope.() -> Unit,
    ) {
        val item = storedOrPut(key) { Value.InlineTable(InlineTable()) }
        val table = item.inlineTableOrNull ?: mismatch(key, TomlType.InlineTable, item)
        InlineTableScope(table).block()
    }

    /** Removes [key], keeping the comment and blank lines above it. */
    public abstract fun remove(key: String)
}

/** A block writing into a standard table, or the document root. */
public class TableScope internal constructor(private val table: StandardTable) : KeyedScope() {
    override fun put(key: String, value: Value) {
        table[key] = value
    }

    override fun storedOrPut(key: String, value: () -> Value): Item = table.getOrPut(key) { Item(value()) }

    override fun remove(key: String) {
        table.remove(key)
    }

    /**
     * Opens the table at [key], creating it when the key is missing. A table created to hold only other tables prints
     * no header of its own, as `[tool]` does not above `[tool.poetry]`.
     */
    public fun table(
        key: String,
        block: TableScope.() -> Unit,
    ) {
        val existing = table.getOrNull(key)
        val item = existing ?: table.getOrPut(key) { Item(StandardTable()) }
        val child = item.standardTableOrNull ?: mismatch(key, TomlType.Table, item)
        TableScope(child).block()
        if (existing == null && child.isNotEmpty() && child.visualValues().isEmpty()) child.isImplicit = true
    }

    /** Opens the array of tables at [key], creating it when the key is missing; [block] appends tables to it. */
    public fun arrayOfTables(
        key: String,
        block: ArrayOfTablesScope.() -> Unit,
    ) {
        val item = table.getOrPut(key) { Item(ArrayOfTables()) }
        val tables = item.arrayOfTablesOrNull ?: mismatch(key, TomlType.ArrayOfTables, item)
        ArrayOfTablesScope(tables).block()
    }
}

/** A block writing into an inline table, which holds only values. */
public class InlineTableScope internal constructor(private val table: InlineTable) : KeyedScope() {
    override fun put(key: String, value: Value) {
        table[key] = value
    }

    override fun storedOrPut(key: String, value: () -> Value): Item {
        table.getOrPut(key, value)
        return checkNotNull(table.getItem(key))
    }

    override fun remove(key: String) {
        table.remove(key)
    }
}

/** A block appending to an array. */
@TomlDsl
public class ArrayScope internal constructor(private val array: TomlArray) {
    /** Appends a string. */
    public fun add(value: String): Unit = array.add(value)

    /** Appends an integer. */
    public fun add(value: Int): Unit = array.add(value.toLong())

    /** Appends an integer. */
    public fun add(value: Long): Unit = array.add(value)

    /** Appends a float. */
    public fun add(value: Double): Unit = array.add(value)

    /** Appends a boolean. */
    public fun add(value: Boolean): Unit = array.add(value)

    /** Appends a date-time. */
    public fun add(value: Datetime): Unit = array.add(value)

    /** Appends a copy of [value]. */
    public fun add(value: Value): Unit = array.add(value.copy())

    /** Appends an array that [block] fills. */
    public fun array(block: ArrayScope.() -> Unit) {
        val inner = TomlArray()
        array.add(Value.Array(inner))
        ArrayScope(inner).block()
    }

    /** Appends an inline table that [block] fills. */
    public fun inlineTable(block: InlineTableScope.() -> Unit) {
        val inner = InlineTable()
        array.add(Value.InlineTable(inner))
        InlineTableScope(inner).block()
    }
}

/** A block appending tables to an array of tables. */
@TomlDsl
public class ArrayOfTablesScope internal constructor(private val tables: ArrayOfTables) {
    /** Appends a table that [block] fills. */
    public fun table(block: TableScope.() -> Unit) {
        val table = StandardTable()
        tables.add(table)
        TableScope(table).block()
    }
}
