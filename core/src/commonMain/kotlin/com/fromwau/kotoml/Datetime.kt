package com.fromwau.kotoml

import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A TOML date-time value, keeping exactly what the TOML text spelled.
 *
 * Which of the three parts are present decides which of the four TOML date-time kinds this is:
 *
 * | [date] | [time] | [offset] | TOML kind        |
 * | ------ | ------ | -------- | ---------------- |
 * | set    | set    | set      | Offset Date-Time |
 * | set    | set    | null     | Local Date-Time  |
 * | set    | null   | null     | Local Date       |
 * | null   | set    | null     | Local Time       |
 *
 * ```kotlin
 * val odt = Datetime.parse("1979-05-27T07:32:00Z")
 * val date = Datetime(date = Date(year = 1979, month = 5, day = 27))
 * ```
 *
 * Datetimes order by their date, then time, then offset, each missing part sorting before a present one.
 * That is the order of the fields, not the order of instants: `00:00+01:00` sorts after `23:00Z`.
 */
public data class Datetime(
    /** The date, required for an offset date-time, a local date-time and a local date. */
    val date: Date? = null,
    /** The time, required for an offset date-time, a local date-time and a local time. */
    val time: Time? = null,
    /** The offset from UTC, required for an offset date-time only. */
    val offset: Offset? = null,
) : Comparable<Datetime> {

    override fun compareTo(other: Datetime): Int {
        compareNullable(date, other.date).let { if (it != 0) return it }
        compareNullable(time, other.time).let { if (it != 0) return it }
        return compareNullable(offset, other.offset)
    }

    /** Writes this date-time as TOML spells it, such as `1979-05-27T07:32:00Z`. */
    override fun toString(): String = buildString {
        date?.let { append(it) }
        time?.let {
            if (date != null) append('T')
            append(it)
        }
        offset?.let { append(it) }
    }

    public companion object {
        /**
         * Parses one of the four RFC 3339 forms TOML allows: an offset date-time, a local date-time, a
         * local date or a local time.
         *
         * The separator between date and time may be `T`, `t` or a space, the offset `Z`, `z` or
         * `+HH:MM` / `-HH:MM`. Fractional seconds beyond nanoseconds are truncated, not rounded.
         */
        public fun parse(input: String): Result<Datetime, DatetimeParseError> = DatetimeParser(input).parse()
    }
}

/**
 * A calendar date: on its own, a TOML Local Date such as `1979-05-27`.
 *
 * [day] is checked against the month and leap year only when parsing; constructing one directly does not
 * validate.
 */
public data class Date(
    /** The year, written with four digits. */
    val year: Int,
    /** The month, 1 to 12. */
    val month: Int,
    /** The day of the month, 1 to 28, 29, 30 or 31 depending on [month] and [year]. */
    val day: Int,
) : Comparable<Date> {

    override fun compareTo(other: Date): Int {
        year.compareTo(other.year).let { if (it != 0) return it }
        month.compareTo(other.month).let { if (it != 0) return it }
        return day.compareTo(other.day)
    }

    /** Writes this date as `YYYY-MM-DD`. */
    override fun toString(): String = "${year.pad(4)}-${month.pad(2)}-${day.pad(2)}"
}

/**
 * A time of day: on its own, a TOML Local Time such as `07:32:00`.
 *
 * A time without [second] is a TOML 1.1 spelling (`07:32`); it prints back without seconds unless it has
 * a fraction.
 */
public data class Time(
    /** The hour, 0 to 23. */
    val hour: Int,
    /** The minute, 0 to 59. */
    val minute: Int,
    /** The second, 0 to 60 to allow a leap second, or null when the text had none. */
    val second: Int?,
    /** The fraction of the second in nanoseconds, 0 to 999 999 999, or null when the text had none. */
    val nanosecond: Int?,
) : Comparable<Time> {

    override fun compareTo(other: Time): Int {
        hour.compareTo(other.hour).let { if (it != 0) return it }
        minute.compareTo(other.minute).let { if (it != 0) return it }
        compareNullable(second, other.second).let { if (it != 0) return it }
        return compareNullable(nanosecond, other.nanosecond)
    }

    /** Writes this time as `HH:MM[:SS[.fraction]]`, the fraction without trailing zeros. */
    override fun toString(): String = buildString {
        append(hour.pad(2))
        append(':')
        append(minute.pad(2))
        val shownSecond = second ?: if (nanosecond != null) 0 else null
        if (shownSecond != null) {
            append(':')
            append(shownSecond.pad(2))
        }
        if (nanosecond != null) {
            val fraction = nanosecond.toString().padStart(9, '0').trimEnd('0')
            append('.')
            append(fraction.ifEmpty { "0" })
        }
    }
}

/** The offset of an Offset Date-Time from UTC. */
public sealed interface Offset : Comparable<Offset> {

    /** UTC, spelled `Z`. */
    public data object Z : Offset {
        override fun toString(): String = "Z"
    }

    /** `-00:00`: a time in UTC whose local offset is unknown, as RFC 3339 spells it. */
    public data object Unknown : Offset {
        override fun toString(): String = "-00:00"
    }

    /** A numeric offset such as `-07:00`, in whole [minutes] east of UTC. */
    public data class Custom(
        /** Minutes east of UTC, within a day either way. */
        val minutes: Short,
    ) : Offset {
        /** Writes the offset as `+HH:MM` or `-HH:MM`. */
        override fun toString(): String {
            val total = minutes.toInt()
            val sign = if (total < 0) '-' else '+'
            val magnitude = if (total < 0) -total else total
            return "$sign${(magnitude / 60).pad(2)}:${(magnitude % 60).pad(2)}"
        }
    }

    /** Orders [Z] first, then [Unknown], then each [Custom] by its minutes. */
    override fun compareTo(other: Offset): Int = when {
        this is Custom && other is Custom -> minutes.compareTo(other.minutes)
        else -> rank().compareTo(other.rank())
    }

    private fun rank(): Int = when (this) {
        Z -> 0
        Unknown -> 1
        is Custom -> 2
    }
}

/**
 * Why [Datetime.parse] refused its input: the [part] being read, if any, and what it [expected] there, if
 * known.
 */
@Serializable
public data class DatetimeParseError(
    val part: DatetimePart?,
    val expected: DatetimeExpected?,
) : IError

/** The part of a date-time being read when it was refused. */
public enum class DatetimePart {
    Date,

    /** The separator between the date and the time. */
    DateTime,
    Time,
    Offset,
}

/** A field of a date-time. */
public enum class DatetimeField {
    Year,
    Month,
    Day,
    Hour,
    Minute,
    Second,
    Nanosecond,
}

/** What a date-time needed where it was refused. */
@Serializable
public sealed interface DatetimeExpected {
    /** A year or an hour, to start the value. */
    @Serializable
    @SerialName("YearOrHour")
    public data object YearOrHour : DatetimeExpected

    /** `-` after a year, or `:` after an hour. */
    @Serializable
    @SerialName("DateOrTimeSeparator")
    public data object DateOrTimeSeparator : DatetimeExpected

    /** `-` between month and day. */
    @Serializable
    @SerialName("DaySeparator")
    public data object DaySeparator : DatetimeExpected

    /** `T` or a space between the date and the time. */
    @Serializable
    @SerialName("TimeStart")
    public data object TimeStart : DatetimeExpected

    /** `:` between hours and minutes. */
    @Serializable
    @SerialName("MinuteSeparator")
    public data object MinuteSeparator : DatetimeExpected

    /** `Z`, `+` or `-` to start an offset. */
    @Serializable
    @SerialName("OffsetStart")
    public data object OffsetStart : DatetimeExpected

    /** The digits of [field]. */
    @Serializable
    @SerialName("Digits")
    public data class Digits(val field: DatetimeField) : DatetimeExpected

    /** [field] written with exactly [width] digits. */
    @Serializable
    @SerialName("Width")
    public data class Width(val field: DatetimeField, val width: Int) : DatetimeExpected

    /** [field] between [min] and [max]. */
    @Serializable
    @SerialName("Range")
    public data class Range(val field: DatetimeField, val min: Int, val max: Int) : DatetimeExpected
}

private fun <T : Comparable<T>> compareNullable(left: T?, right: T?): Int = when {
    left == null && right == null -> 0
    left == null -> -1
    right == null -> 1
    else -> left.compareTo(right)
}

private fun Int.pad(width: Int): String = toString().padStart(width, '0')
