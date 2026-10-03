package com.fromwau.kotoml

internal fun Date.dbg(): Dbg = struct(
    "Date",
    "year" to lit(year),
    "month" to lit(month),
    "day" to lit(day),
)

internal fun Time.dbg(): Dbg = struct(
    "Time",
    "hour" to lit(hour),
    "minute" to lit(minute),
    "second" to option(second) { lit(it) },
    "nanosecond" to option(nanosecond) { lit(it) },
)

internal fun Offset.dbg(): Dbg = when (this) {
    Offset.Z -> lit("Z")
    // Upstream has no unknown offset; it reads `-00:00` as zero minutes.
    Offset.Unknown -> struct("Custom", "minutes" to lit(0))
    is Offset.Custom -> struct("Custom", "minutes" to lit(minutes))
}

internal fun Datetime.dbg(): Dbg = struct(
    "Datetime",
    "date" to option(date) { it.dbg() },
    "time" to option(time) { it.dbg() },
    "offset" to option(offset) { it.dbg() },
)

internal fun DatetimeParseError.dbg(): Dbg = struct(
    "DatetimeParseError",
    "what" to option(part) { str(it.describe()) },
    "expected" to option(expected) { str(it.describe(part)) },
)
