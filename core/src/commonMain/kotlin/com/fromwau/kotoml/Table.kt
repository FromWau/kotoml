package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
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
     * Sets [key] to a copy of [item].
     *
     * @return the item [key] held before, or null when it held none; [TomlEditError.NotAValue] when this is an
     *   inline table and [item] holds no value; or [TomlEditError.TooDeep], storing nothing, when [item] would nest
     *   deeper than the parser reads.
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

    /**
     * Renames the key [old] to [new], as [StandardTable.rename] and [InlineTable.rename] do.
     *
     * @return [TomlEditError.NotFound] when there is no [old], or [TomlEditError.KeyTaken] when [new] is another key
     *   already there; either changes nothing.
     */
    public fun rename(
        old: String,
        new: String,
    ): EmptyResult<TomlEditError>

    /** The number of keys. */
    public val size: Int

    /** The keys, in order, as a copy that later changes to the table leave as it is. */
    public val keys: Set<String>

    /** Whether the table has no keys. */
    public fun isEmpty(): Boolean

    /** Whether the table has a key. */
    public fun isNotEmpty(): Boolean

    /** Removes every entry, keeping the comment and blank lines above each. */
    public fun clear()

    /** Whether the table has [key]. */
    public fun containsKey(key: String): Boolean

    /** Whether the table has [key], for `key in table`. */
    public operator fun contains(key: String): Boolean

    /** The values visually under this table, with the key path that reaches each. */
    public fun valuesWithPaths(): List<Pair<List<Key>, Value>>

    /** Drops the formatting of the keys and values in this table. */
    public fun resetFormatting()

    /**
     * How the table prints: [TableLayout.Header] or [TableLayout.Dotted] for a [StandardTable], and
     * [TableLayout.Inline], [TableLayout.MultilineInline] or [TableLayout.Dotted] for an [InlineTable]. A document's
     * root and an element of an array of tables read [TableLayout.Header]. [Item.setLayout] changes it through the
     * slot holding the table.
     */
    public val layout: TableLayout

    /** The key [name] as stored, whose formatting can be changed in place; null when there is none. */
    public fun keyOrNull(name: String): Key?
}
