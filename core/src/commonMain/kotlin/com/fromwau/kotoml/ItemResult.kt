package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap

/**
 * Indexes on into the item an earlier index found, or passes its error on, so `doc["a"]["b"]` stops at the first
 * key it misses.
 */
public operator fun Result<Item, TomlEditError>.get(key: String): Result<Item, TomlEditError> = flatMap { it[key] }

/** Indexes on by position into the item an earlier index found, or passes its error on; see [get]. */
public operator fun Result<Item, TomlEditError>.get(index: Int): Result<Item, TomlEditError> =
    flatMap { it[index] }

/** Stores a copy of [item] at [key] of the item an earlier index found, or returns that index's error. */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    item: Item,
): EmptyResult<TomlEditError> = flatMap { it.set(key, item) }

/** Stores a copy of [value] at [key]; see [set]. */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Value,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores a copy of [table] at [key]; see [set]. */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    table: StandardTable,
): EmptyResult<TomlEditError> = flatMap { it.set(key, table) }

/** Stores a copy of [arrayOfTables] at [key]; see [set]. */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    arrayOfTables: ArrayOfTables,
): EmptyResult<TomlEditError> = flatMap { it.set(key, arrayOfTables) }

/** Stores a string at [key]; see [set]. */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: String,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores an integer at [key]; see [set]. */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Long,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores an integer at [key]; see [set]. */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Int,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores a float at [key]; see [set]. */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Double,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores a boolean at [key]; see [set]. */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Boolean,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores a date-time at [key]; see [set]. */
public operator fun Result<Item, TomlEditError>.set(
    key: String,
    value: Datetime,
): EmptyResult<TomlEditError> = flatMap { it.set(key, value) }

/** Stores a copy of [item] at [index] of the item an earlier index found, or returns that index's error. */
public operator fun Result<Item, TomlEditError>.set(
    index: Int,
    item: Item,
): EmptyResult<TomlEditError> = flatMap { it.set(index, item) }
