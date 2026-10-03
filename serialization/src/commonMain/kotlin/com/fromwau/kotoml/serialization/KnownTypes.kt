package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.Date
import com.fromwau.kotoml.Datetime
import com.fromwau.kotoml.Offset
import com.fromwau.kotoml.Time
import com.fromwau.kotoml.TomlType
import com.fromwau.kotoml.Value
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// The serial names of the serializers kotlinx-serialization and kotlinx-datetime use by default.
private const val DURATION = "kotlin.time.Duration"
private const val INSTANT = "kotlin.time.Instant"
private const val LOCAL_DATE = "kotlinx.datetime.LocalDate"
private const val LOCAL_TIME = "kotlinx.datetime.LocalTime"
private const val LOCAL_DATE_TIME = "kotlinx.datetime.LocalDateTime"
private const val U_BYTE = "kotlin.UByte"
private const val U_SHORT = "kotlin.UShort"
private const val U_INT = "kotlin.UInt"
private const val U_LONG = "kotlin.ULong"

private val TOML_YEARS = 0..9999

/**
 * The TOML value for [value] when its serializer, named [serialName], is one TOML writes its own way, or null
 * to let the serializer write it. A map key is always a string, so [nativeDatetime] is false there.
 */
internal fun knownValueOrNull(
    serialName: String,
    value: Any?,
    path: TomlPath,
    nativeDatetime: Boolean,
): Value? = when {
    serialName == DURATION && value is Duration -> Value.String(value.toString())
    serialName == U_BYTE && value is UByte -> Value.Integer(value.toLong())
    serialName == U_SHORT && value is UShort -> Value.Integer(value.toLong())
    serialName == U_INT && value is UInt -> Value.Integer(value.toLong())
    serialName == U_LONG && value is ULong -> {
        if (value > Long.MAX_VALUE.toULong()) throw EncodeFailure(TomlEncodeError.OutOfRange("$path", "$value"))
        Value.Integer(value.toLong())
    }
    !nativeDatetime -> null
    serialName == LOCAL_DATE && value is LocalDate -> Value.Datetime(Datetime(date = value.toTomlDate(path)))
    serialName == LOCAL_TIME && value is LocalTime -> Value.Datetime(Datetime(time = value.toTomlTime()))
    serialName == LOCAL_DATE_TIME && value is LocalDateTime -> Value.Datetime(
        Datetime(
            date = value.date.toTomlDate(path),
            time = value.time.toTomlTime(),
        ),
    )
    serialName == INSTANT && value is Instant -> {
        val utc = value.toLocalDateTime(TimeZone.UTC)
        Value.Datetime(
            Datetime(
                date = utc.date.toTomlDate(path),
                time = utc.time.toTomlTime(),
                offset = Offset.Z,
            ),
        )
    }
    else -> null
}

/**
 * The value of the type named [serialName] read from [input], when it is a type TOML reads its own way, or
 * null to let its serializer read it.
 */
internal fun knownValueOrNull(
    serialName: String,
    input: TomlInput,
    path: TomlPath,
    nativeDatetime: Boolean,
): Any? = when {
    serialName == DURATION -> {
        val text = input.string(path)
        Duration.parseOrNull(text) ?: throw DecodeFailure(TomlDecodeError.Unparsable("$path", text, serialName))
    }
    serialName == U_BYTE -> input.integerIn(path, serialName, 0L..UByte.MAX_VALUE.toLong()).toUByte()
    serialName == U_SHORT -> input.integerIn(path, serialName, 0L..UShort.MAX_VALUE.toLong()).toUShort()
    serialName == U_INT -> input.integerIn(path, serialName, 0L..UInt.MAX_VALUE.toLong()).toUInt()
    serialName == U_LONG -> input.integerIn(path, serialName, 0L..Long.MAX_VALUE).toULong()
    !nativeDatetime -> null
    serialName == LOCAL_DATE -> input.datetime(path, serialName, DatetimeKind.LocalDate, LocalDate::parse) {
        it.date!!.toLocalDate()
    }
    serialName == LOCAL_TIME -> input.datetime(path, serialName, DatetimeKind.LocalTime, LocalTime::parse) {
        it.time!!.toLocalTime(path)
    }
    serialName == LOCAL_DATE_TIME -> {
        input.datetime(path, serialName, DatetimeKind.LocalDateTime, LocalDateTime::parse) {
            LocalDateTime(it.date!!.toLocalDate(), it.time!!.toLocalTime(path))
        }
    }
    serialName == INSTANT -> input.datetime(path, serialName, DatetimeKind.OffsetDateTime, Instant::parse) {
        val local = LocalDateTime(it.date!!.toLocalDate(), it.time!!.toLocalTime(path))
        local.toInstant(UtcOffset.ZERO) - it.offset!!.minutesEast().minutes
    }
    else -> null
}

/** Whether [existing], read as the type named [serialName], is [value]. */
internal fun readsBackAs(
    serialName: String,
    existing: Value,
    value: Any?,
): Boolean = try {
    knownValueOrNull(serialName, existing.toInput(), TomlPath.Root, nativeDatetime = true) == value
} catch (_: DecodeFailure) {
    false
}

private fun TomlInput.string(path: TomlPath): String = (this as? TomlInput.Scalar)?.value?.stringOrNull
    ?: throw DecodeFailure(TomlDecodeError.TypeMismatch("$path", TomlType.String, type))

/** The integer at [path], refused unless it is in [range] of the type named [serialName]. */
internal fun TomlInput?.integerIn(
    path: TomlPath,
    serialName: String,
    range: LongRange,
): Long {
    val integer = (this as? TomlInput.Scalar)?.value?.longOrNull
        ?: throw DecodeFailure(TomlDecodeError.TypeMismatch("$path", TomlType.Integer, this?.type ?: TomlType.Missing))
    if (integer !in range) throw DecodeFailure(TomlDecodeError.OutOfRange("$path", "$integer", serialName))
    return integer
}

/**
 * Reads a native date-time of [kind] with [convert], which may count on the parts that kind has, or a string
 * with [parse]; any other kind of date-time or value is refused.
 */
private fun <T> TomlInput.datetime(
    path: TomlPath,
    serialName: String,
    kind: DatetimeKind,
    parse: (String) -> T,
    convert: (Datetime) -> T,
): T {
    val value = (this as? TomlInput.Scalar)?.value
    val datetime = value?.datetimeOrNull
    val text = value?.stringOrNull
    return when {
        datetime != null -> {
            val found = datetime.kind()
            if (found != kind) throw DecodeFailure(TomlDecodeError.DatetimeKindMismatch("$path", kind, found))
            convert(datetime)
        }
        text != null -> try {
            parse(text)
        } catch (_: IllegalArgumentException) {
            throw DecodeFailure(TomlDecodeError.Unparsable("$path", text, serialName))
        }
        else -> throw DecodeFailure(TomlDecodeError.TypeMismatch("$path", TomlType.Datetime, type))
    }
}

private fun Datetime.kind(): DatetimeKind = when {
    offset != null -> DatetimeKind.OffsetDateTime
    date != null && time != null -> DatetimeKind.LocalDateTime
    date != null -> DatetimeKind.LocalDate
    else -> DatetimeKind.LocalTime
}

private fun Offset.minutesEast(): Int = when (this) {
    Offset.Z -> 0
    is Offset.Custom -> minutes.toInt()
}

private fun Date.toLocalDate(): LocalDate = LocalDate(year, month, day)

private fun Time.toLocalTime(path: TomlPath): LocalTime {
    if (second == 60) throw DecodeFailure(TomlDecodeError.LeapSecond("$path"))
    return LocalTime(hour, minute, second ?: 0, nanosecond ?: 0)
}

private fun LocalDate.toTomlDate(path: TomlPath): Date {
    if (year !in TOML_YEARS) throw EncodeFailure(TomlEncodeError.OutOfRange("$path", "$this"))
    return Date(year, month.number, day)
}

private fun LocalTime.toTomlTime(): Time = Time(hour, minute, second, nanosecond.takeIf { it != 0 })
