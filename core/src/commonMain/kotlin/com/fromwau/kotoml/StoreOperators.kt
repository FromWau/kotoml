package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap

// The `[]=` form of each store. A failed store changes nothing; the `set` and `put` calls return why as a Result.

/** Stores a copy of [item] at [key], as [StandardTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun StandardTable.set(
    key: String,
    item: Item,
) {
    set(key, item).orThrow()
}

/** Stores a copy of [value] at [key], as [StandardTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun StandardTable.set(
    key: String,
    value: Value,
) {
    set(key, value).orThrow()
}

/** Stores a copy of [table] at [key], as [StandardTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun StandardTable.set(
    key: String,
    table: StandardTable,
) {
    set(key, table).orThrow()
}

/** Stores a copy of [arrayOfTables] at [key], as [StandardTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun StandardTable.set(
    key: String,
    arrayOfTables: ArrayOfTables,
) {
    set(key, arrayOfTables).orThrow()
}

/** Stores a string at [key], as [StandardTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun StandardTable.set(
    key: String,
    value: String,
) {
    set(key, value).orThrow()
}

/** Stores an integer at [key], as [StandardTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun StandardTable.set(
    key: String,
    value: Long,
) {
    set(key, value).orThrow()
}

/** Stores an integer at [key], as [StandardTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun StandardTable.set(
    key: String,
    value: Int,
) {
    set(key, value).orThrow()
}

/** Stores a float at [key], as [StandardTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun StandardTable.set(
    key: String,
    value: Double,
) {
    set(key, value).orThrow()
}

/** Stores a boolean at [key], as [StandardTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun StandardTable.set(
    key: String,
    value: Boolean,
) {
    set(key, value).orThrow()
}

/** Stores a date-time at [key], as [StandardTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun StandardTable.set(
    key: String,
    value: Datetime,
) {
    set(key, value).orThrow()
}

/** Stores a copy of [item] at [key], as [TomlDocument.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun TomlDocument.set(
    key: String,
    item: Item,
) {
    set(key, item).orThrow()
}

/** Stores a copy of [value] at [key], as [TomlDocument.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun TomlDocument.set(
    key: String,
    value: Value,
) {
    set(key, value).orThrow()
}

/** Stores a copy of [table] at [key], as [TomlDocument.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun TomlDocument.set(
    key: String,
    table: StandardTable,
) {
    set(key, table).orThrow()
}

/** Stores a copy of [arrayOfTables] at [key], as [TomlDocument.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun TomlDocument.set(
    key: String,
    arrayOfTables: ArrayOfTables,
) {
    set(key, arrayOfTables).orThrow()
}

/** Stores a string at [key], as [TomlDocument.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun TomlDocument.set(
    key: String,
    value: String,
) {
    set(key, value).orThrow()
}

/** Stores an integer at [key], as [TomlDocument.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun TomlDocument.set(
    key: String,
    value: Long,
) {
    set(key, value).orThrow()
}

/** Stores an integer at [key], as [TomlDocument.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun TomlDocument.set(
    key: String,
    value: Int,
) {
    set(key, value).orThrow()
}

/** Stores a float at [key], as [TomlDocument.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun TomlDocument.set(
    key: String,
    value: Double,
) {
    set(key, value).orThrow()
}

/** Stores a boolean at [key], as [TomlDocument.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun TomlDocument.set(
    key: String,
    value: Boolean,
) {
    set(key, value).orThrow()
}

/** Stores a date-time at [key], as [TomlDocument.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun TomlDocument.set(
    key: String,
    value: Datetime,
) {
    set(key, value).orThrow()
}

/** Stores a copy of [item] at [key], as [Item.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun Item.set(
    key: String,
    item: Item,
) {
    set(key, item).orThrow()
}

/** Stores a copy of [value] at [key], as [Item.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun Item.set(
    key: String,
    value: Value,
) {
    set(key, value).orThrow()
}

/** Stores a copy of [table] at [key], as [Item.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun Item.set(
    key: String,
    table: StandardTable,
) {
    set(key, table).orThrow()
}

/** Stores a copy of [arrayOfTables] at [key], as [Item.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun Item.set(
    key: String,
    arrayOfTables: ArrayOfTables,
) {
    set(key, arrayOfTables).orThrow()
}

/** Stores a string at [key], as [Item.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun Item.set(
    key: String,
    value: String,
) {
    set(key, value).orThrow()
}

/** Stores an integer at [key], as [Item.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun Item.set(
    key: String,
    value: Long,
) {
    set(key, value).orThrow()
}

/** Stores an integer at [key], as [Item.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun Item.set(
    key: String,
    value: Int,
) {
    set(key, value).orThrow()
}

/** Stores a float at [key], as [Item.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun Item.set(
    key: String,
    value: Double,
) {
    set(key, value).orThrow()
}

/** Stores a boolean at [key], as [Item.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun Item.set(
    key: String,
    value: Boolean,
) {
    set(key, value).orThrow()
}

/** Stores a date-time at [key], as [Item.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun Item.set(
    key: String,
    value: Datetime,
) {
    set(key, value).orThrow()
}

/** Stores a copy of [value] at [key], as [InlineTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun InlineTable.set(
    key: String,
    value: Value,
) {
    set(key, value).orThrow()
}

/** Stores a string at [key], as [InlineTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun InlineTable.set(
    key: String,
    value: String,
) {
    set(key, value).orThrow()
}

/** Stores an integer at [key], as [InlineTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun InlineTable.set(
    key: String,
    value: Long,
) {
    set(key, value).orThrow()
}

/** Stores an integer at [key], as [InlineTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun InlineTable.set(
    key: String,
    value: Int,
) {
    set(key, value).orThrow()
}

/** Stores a float at [key], as [InlineTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun InlineTable.set(
    key: String,
    value: Double,
) {
    set(key, value).orThrow()
}

/** Stores a boolean at [key], as [InlineTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun InlineTable.set(
    key: String,
    value: Boolean,
) {
    set(key, value).orThrow()
}

/** Stores a date-time at [key], as [InlineTable.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun InlineTable.set(
    key: String,
    value: Datetime,
) {
    set(key, value).orThrow()
}

/** Stores a copy of [item] at [index], as [Item.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun Item.set(
    index: Int,
    item: Item,
) {
    set(index, item).orThrow()
}

/** Stores a copy of [value] at [index], as [TomlArray.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun TomlArray.set(
    index: Int,
    value: Value,
) {
    set(index, value).orThrow()
}

/** Stores a copy of [table] at [index], as [ArrayOfTables.set] does, and throws [TomlEditException] with the error when the store fails. */
public operator fun ArrayOfTables.set(
    index: Int,
    table: StandardTable,
) {
    set(index, table).orThrow()
}

/**
 * Stores a copy of [item] at [key] of the item an earlier index found, and throws [TomlEditException] with that index's error or
 * the store's; see [Item.set].
 */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    item: Item,
) {
    flatMap { it.set(key, item) }.orThrow()
}

/**
 * Stores a copy of [value] at [key] of the item an earlier index found, and throws [TomlEditException] with that index's error or
 * the store's; see [Item.set].
 */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Value,
) {
    flatMap { it.set(key, value) }.orThrow()
}

/**
 * Stores a copy of [table] at [key] of the item an earlier index found, and throws [TomlEditException] with that index's error or
 * the store's; see [Item.set].
 */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    table: StandardTable,
) {
    flatMap { it.set(key, table) }.orThrow()
}

/**
 * Stores a copy of [arrayOfTables] at [key] of the item an earlier index found, and throws [TomlEditException] with that index's error or
 * the store's; see [Item.set].
 */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    arrayOfTables: ArrayOfTables,
) {
    flatMap { it.set(key, arrayOfTables) }.orThrow()
}

/**
 * Stores a string at [key] of the item an earlier index found, and throws [TomlEditException] with that index's error or
 * the store's; see [Item.set].
 */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: String,
) {
    flatMap { it.set(key, value) }.orThrow()
}

/**
 * Stores an integer at [key] of the item an earlier index found, and throws [TomlEditException] with that index's error or
 * the store's; see [Item.set].
 */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Long,
) {
    flatMap { it.set(key, value) }.orThrow()
}

/**
 * Stores an integer at [key] of the item an earlier index found, and throws [TomlEditException] with that index's error or
 * the store's; see [Item.set].
 */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Int,
) {
    flatMap { it.set(key, value) }.orThrow()
}

/**
 * Stores a float at [key] of the item an earlier index found, and throws [TomlEditException] with that index's error or
 * the store's; see [Item.set].
 */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Double,
) {
    flatMap { it.set(key, value) }.orThrow()
}

/**
 * Stores a boolean at [key] of the item an earlier index found, and throws [TomlEditException] with that index's error or
 * the store's; see [Item.set].
 */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Boolean,
) {
    flatMap { it.set(key, value) }.orThrow()
}

/**
 * Stores a date-time at [key] of the item an earlier index found, and throws [TomlEditException] with that index's error or
 * the store's; see [Item.set].
 */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Datetime,
) {
    flatMap { it.set(key, value) }.orThrow()
}

/** Stores a copy of [item] at [index] of the item an earlier index found; see [Item.set] and the key form. */
public operator fun Result<Item, TomlEditError>.set(
    index: Int,
    item: Item,
) {
    flatMap { it.set(index, item) }.orThrow()
}

private fun Result<*, TomlEditError>.orThrow() {
    if (this is Result.Error) throw TomlEditException(error)
}
