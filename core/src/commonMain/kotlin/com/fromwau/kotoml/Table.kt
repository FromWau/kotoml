package com.fromwau.kotoml

import com.fromwau.kern.result.Result

/**
 * A TOML table of either kind: a [StandardTable] (`[section]`) or an [InlineTable] (`{ a = 1 }`), seen as key/item
 * pairs.
 *
 * ```kotlin
 * fun names(table: Table): List<String> = table.entries.map { it.first }
 * ```
 */
public sealed interface Table {
    /** The key/item pairs, in order. */
    public val entries: List<Pair<String, Item>>

    /** The item at [key], or null when there is none. */
    public fun getItem(key: String): Item?

    /**
     * Sets [key] to a copy of [item]. An inline table holds only values, so it stores a table as an inline table
     * and an array of tables as an array of inline tables.
     *
     * @return the item [key] held before, or null when it held none; or [TomlEditError.NotAValue], with nothing
     *   written, when this is an inline table and [item] is empty.
     */
    public fun putItem(
        key: String,
        item: Item,
    ): Result<Item?, TomlEditError>

    /**
     * Removes [key] and returns its item, or null when it held none. Only the key's own text goes; the comment and
     * blank lines above it stay where they print, as [StandardTable.remove] and [InlineTable.remove] keep them.
     */
    public fun removeItem(key: String): Item?

    /** The number of keys holding an item. */
    public val size: Int

    /** The keys holding an item, in order, as a copy that later changes to the table leave as it is. */
    public val keys: Set<String>

    /** Whether no key holds an item. */
    public fun isEmpty(): Boolean

    /** Whether some key holds an item. */
    public fun isNotEmpty(): Boolean

    /** Removes every entry, keeping the comment and blank lines above each. */
    public fun clear()

    /** Whether [key] holds an item. */
    public fun containsKey(key: String): Boolean

    /** Whether [key] holds an item, for `key in table`. */
    public operator fun contains(key: String): Boolean

    /** The values visually under this table, with the key path that reaches each. */
    public fun valuesWithPaths(): List<Pair<List<Key>, Value>>

    /** Drops the formatting of the keys and values in this table. */
    public fun resetFormatting()

    /** Whether this table stands for the parts of a dotted key. */
    public var isDotted: Boolean

    /** The key [name] as stored, whose formatting can be changed in place; null when there is none. */
    public fun keyOrNull(name: String): Key?
}
