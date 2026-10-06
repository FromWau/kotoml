package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult

/** Stores a string at [key]; see [Item.set]. */
public operator fun Item.set(
    key: String,
    value: String,
): EmptyResult<TomlEditError> = set(key, Value.String(value))

/** Stores an integer at [key]; see [Item.set]. */
public operator fun Item.set(
    key: String,
    value: Long,
): EmptyResult<TomlEditError> = set(key, Value.Integer(value))

/** Stores an integer at [key]; see [Item.set]. */
public operator fun Item.set(
    key: String,
    value: Int,
): EmptyResult<TomlEditError> = set(key, Value.Integer(value))

/** Stores a float at [key]; see [Item.set]. */
public operator fun Item.set(
    key: String,
    value: Double,
): EmptyResult<TomlEditError> = set(key, Value.Float(value))

/** Stores a boolean at [key]; see [Item.set]. */
public operator fun Item.set(
    key: String,
    value: Boolean,
): EmptyResult<TomlEditError> = set(key, Value.Boolean(value))

/** Stores a date-time at [key]; see [Item.set]. */
public operator fun Item.set(
    key: String,
    value: Datetime,
): EmptyResult<TomlEditError> = set(key, Value.Datetime(value))

/** Stores a string at [key]; see [StandardTable.set]. */
public operator fun StandardTable.set(
    key: String,
    value: String,
): EmptyResult<TomlEditError> = set(key, Value.String(value))

/** Stores an integer at [key]; see [StandardTable.set]. */
public operator fun StandardTable.set(
    key: String,
    value: Long,
): EmptyResult<TomlEditError> = set(key, Value.Integer(value))

/** Stores an integer at [key]; see [StandardTable.set]. */
public operator fun StandardTable.set(
    key: String,
    value: Int,
): EmptyResult<TomlEditError> = set(key, Value.Integer(value))

/** Stores a float at [key]; see [StandardTable.set]. */
public operator fun StandardTable.set(
    key: String,
    value: Double,
): EmptyResult<TomlEditError> = set(key, Value.Float(value))

/** Stores a boolean at [key]; see [StandardTable.set]. */
public operator fun StandardTable.set(
    key: String,
    value: Boolean,
): EmptyResult<TomlEditError> = set(key, Value.Boolean(value))

/** Stores a date-time at [key]; see [StandardTable.set]. */
public operator fun StandardTable.set(
    key: String,
    value: Datetime,
): EmptyResult<TomlEditError> = set(key, Value.Datetime(value))

/** Sets [key] to a string; see [InlineTable.set]. */
public operator fun InlineTable.set(
    key: String,
    value: String,
): EmptyResult<TomlEditError> = set(key, Value.String(value))

/** Sets [key] to an integer; see [InlineTable.set]. */
public operator fun InlineTable.set(
    key: String,
    value: Long,
): EmptyResult<TomlEditError> = set(key, Value.Integer(value))

/** Sets [key] to an integer; see [InlineTable.set]. */
public operator fun InlineTable.set(
    key: String,
    value: Int,
): EmptyResult<TomlEditError> = set(key, Value.Integer(value))

/** Sets [key] to a float; see [InlineTable.set]. */
public operator fun InlineTable.set(
    key: String,
    value: Double,
): EmptyResult<TomlEditError> = set(key, Value.Float(value))

/** Sets [key] to a boolean; see [InlineTable.set]. */
public operator fun InlineTable.set(
    key: String,
    value: Boolean,
): EmptyResult<TomlEditError> = set(key, Value.Boolean(value))

/** Sets [key] to a date-time; see [InlineTable.set]. */
public operator fun InlineTable.set(
    key: String,
    value: Datetime,
): EmptyResult<TomlEditError> = set(key, Value.Datetime(value))

/** Stores a string at [key] of the root table; see [StandardTable.set]. */
public operator fun TomlDocument.set(
    key: String,
    value: String,
): EmptyResult<TomlEditError> = set(key, Value.String(value))

/** Stores an integer at [key] of the root table; see [StandardTable.set]. */
public operator fun TomlDocument.set(
    key: String,
    value: Long,
): EmptyResult<TomlEditError> = set(key, Value.Integer(value))

/** Stores an integer at [key] of the root table; see [StandardTable.set]. */
public operator fun TomlDocument.set(
    key: String,
    value: Int,
): EmptyResult<TomlEditError> = set(key, Value.Integer(value))

/** Stores a float at [key] of the root table; see [StandardTable.set]. */
public operator fun TomlDocument.set(
    key: String,
    value: Double,
): EmptyResult<TomlEditError> = set(key, Value.Float(value))

/** Stores a boolean at [key] of the root table; see [StandardTable.set]. */
public operator fun TomlDocument.set(
    key: String,
    value: Boolean,
): EmptyResult<TomlEditError> = set(key, Value.Boolean(value))

/** Stores a date-time at [key] of the root table; see [StandardTable.set]. */
public operator fun TomlDocument.set(
    key: String,
    value: Datetime,
): EmptyResult<TomlEditError> = set(key, Value.Datetime(value))
