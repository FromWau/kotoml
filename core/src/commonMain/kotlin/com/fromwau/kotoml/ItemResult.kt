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

/** Renames the key [old] to [new] in the item an earlier index found, or returns that index's error. */
public fun Result<Item, TomlEditError>.rename(
    old: String,
    new: String,
): EmptyResult<TomlEditError> = flatMap { it.rename(old, new) }
