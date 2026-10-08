@file:MustUseReturnValues

package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap

/**
 * Stores a copy of [item] at [key] of the item an earlier index found, as [Item.set] does.
 *
 * ```kotlin
 * doc["server"].set("port", 9090L)
 * doc["a"]["b"].set("c", Item(Value.Integer(1L)))
 * ```
 *
 * @return the index's own error, such as [TomlEditError.NotFound] for a missing parent, or the store's; either way
 *   nothing changes.
 */
public fun Result<Item, TomlEditError>.set(
    key: String,
    item: Item,
): EmptyResult<TomlEditError> = flatMap { it.set(key, item) }

/** Stores a copy of [value] at [key] of the item an earlier index found; see the [Item] form. */
public fun Result<Item, TomlEditError>.set(
    key: String,
    value: Value,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores a copy of [table] at [key] of the item an earlier index found; see the [Item] form. */
public fun Result<Item, TomlEditError>.set(
    key: String,
    table: StandardTable,
): EmptyResult<TomlEditError> = flatMap { it.set(key, table) }

/** Stores a copy of [arrayOfTables] at [key] of the item an earlier index found; see the [Item] form. */
public fun Result<Item, TomlEditError>.set(
    key: String,
    arrayOfTables: ArrayOfTables,
): EmptyResult<TomlEditError> = flatMap { it.set(key, arrayOfTables) }

/** Stores a string at [key] of the item an earlier index found; see the [Item] form. */
public fun Result<Item, TomlEditError>.set(
    key: String,
    value: String,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores an integer at [key] of the item an earlier index found; see the [Item] form. */
public fun Result<Item, TomlEditError>.set(
    key: String,
    value: Long,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores an integer at [key] of the item an earlier index found; see the [Item] form. */
public fun Result<Item, TomlEditError>.set(
    key: String,
    value: Int,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores a float at [key] of the item an earlier index found; see the [Item] form. */
public fun Result<Item, TomlEditError>.set(
    key: String,
    value: Double,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores a boolean at [key] of the item an earlier index found; see the [Item] form. */
public fun Result<Item, TomlEditError>.set(
    key: String,
    value: Boolean,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores a date-time at [key] of the item an earlier index found; see the [Item] form. */
public fun Result<Item, TomlEditError>.set(
    key: String,
    value: Datetime,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores a copy of [item] at [index] of the array the earlier index found, as [Item.set] does. */
public fun Result<Item, TomlEditError>.set(
    index: Int,
    item: Item,
): EmptyResult<TomlEditError> = flatMap { it.set(index, item) }
