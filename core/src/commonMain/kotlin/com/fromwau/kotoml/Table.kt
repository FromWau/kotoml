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
@MustUseReturnValues
public sealed interface Table {
    /** The key/item pairs, in order. */
    public val entries: List<Pair<String, Item>>

    /** The item at [key], or null when there is none. */
    public fun getItem(key: String): Item?

    /**
     * Sets [key] to a copy of [item], as [StandardTable.put] and [InlineTable.put] do.
     *
     * @param force stores [item] even when this table cannot hold its comments, which then go; a [StandardTable] holds
     *   every comment.
     * @return the item [key] held before, or null when it held none; or, storing nothing, [TomlEditError.NotAValue]
     *   when this is an inline table and [item] holds no value, [TomlEditError.TooDeep] when [item] would nest deeper
     *   than the parser reads, or [TomlEditError.CommentsWouldBeLost] when this is an inline table that cannot hold
     *   the comments [item] brings and [force] is false.
     */
    public fun putItem(
        key: String,
        item: Item,
        force: Boolean = false,
    ): Result<Item?, TomlEditError>

    /**
     * Removes [key] and returns its item, with the comments that belong to it, as [StandardTable.remove] and
     * [InlineTable.remove] do.
     *
     * @param force removes the key even when comments would go with it.
     * @return the removed item; [TomlEditError.NotFound] when there is no [key]; or
     *   [TomlEditError.CommentsWouldBeLost], changing nothing, when [force] is false and comments would go with it.
     */
    public fun removeItem(
        key: String,
        force: Boolean = false,
    ): Result<Item, TomlEditError>

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

    /**
     * Removes every entry, each with the comments that belong to it, as [StandardTable.clear] and [InlineTable.clear]
     * do.
     *
     * @param force removes them even when comments would go with them.
     * @return [TomlEditError.CommentsWouldBeLost], removing none, when [force] is false and comments would go.
     */
    public fun clear(force: Boolean = false): EmptyResult<TomlEditError>

    /** Whether the table has [key]. */
    public fun containsKey(key: String): Boolean

    /** Whether the table has [key], for `key in table`. */
    public operator fun contains(key: String): Boolean

    /** The values visually under this table, with the key path that reaches each. */
    public fun valuesWithPaths(): List<Pair<List<Key>, Value>>

    /**
     * Formats this table and everything under it, as [StandardTable.format] and [InlineTable.format] say; the rest of
     * the document stays as it is.
     */
    public fun format()

    /**
     * Writes every value under this table in its default spelling, as [TomlDocument.respell] does; the rest of the
     * document stays as it is.
     */
    public fun respell()

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
