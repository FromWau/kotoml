package com.fromwau.kotoml

import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A TOML date-time value, keeping exactly what the TOML text spelled: one of TOML's four kinds.
 *
 * ```kotlin
 * val odt = Datetime.parse("1979-05-27T07:32:00Z")
 * val date = Datetime.LocalDate(Date(year = 1979, month = 5, day = 27))
 * ```
 *
 * Datetimes order by their [date], then [time], then [offset], each missing part sorting before a present one.
 * That is the order of the fields, not the order of instants: `1979-05-27T07:00:00+02:00` sorts after
 * `1979-05-27T06:00:00Z`, though it is the earlier instant.
 */
public sealed interface Datetime : Comparable<Datetime> {
    /** The date, null only for a [LocalTime]. */
    public val date: Date?

    /** The time, null only for a [LocalDate]. */
    public val time: Time?

    /** The offset from UTC, set only for an [OffsetDateTime]. */
    public val offset: Offset?

    /** A date and time at an [offset] from UTC, such as `1979-05-27T07:32:00Z`. */
    public data class OffsetDateTime(
        override val date: Date,
        override val time: Time,
        override val offset: Offset,
    ) : Datetime {
        override fun toString(): String = "${date}T$time$offset"
    }

    /** A date and time with no offset, such as `1979-05-27T07:32:00`. */
    public data class LocalDateTime(
        override val date: Date,
        override val time: Time,
    ) : Datetime {
        override val offset: Offset? get() = null

        override fun toString(): String = "${date}T$time"
    }

    /** A date alone, such as `1979-05-27`. */
    public data class LocalDate(override val date: Date) : Datetime {
        override val time: Time? get() = null

        override val offset: Offset? get() = null

        override fun toString(): String = date.toString()
    }

    /** A time of day alone, such as `07:32:00`. */
    public data class LocalTime(override val time: Time) : Datetime {
        override val date: Date? get() = null

        override val offset: Offset? get() = null

        override fun toString(): String = time.toString()
    }

    override fun compareTo(other: Datetime): Int {
        compareNullable(date, other.date).let { if (it != 0) return it }
        compareNullable(time, other.time).let { if (it != 0) return it }
        return compareNullable(offset, other.offset)
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
 * @throws IllegalArgumentException when constructed with a date that does not exist.
 */
public data class Date(
    /** The year, 0 to 9999, written with four digits. */
    val year: Int,
    /** The month, 1 to 12. */
    val month: Int,
    /** The day of the month, 1 to 28, 29, 30 or 31 depending on [month] and [year]. */
    val day: Int,
) : Comparable<Date> {

    init {
        require(year in 0..9999) { "year $year is not between 0 and 9999" }
        require(month in 1..12) { "month $month is not between 1 and 12" }
        val days = daysInMonth(year, month)
        require(day in 1..days) { "day $day is not between 1 and $days" }
    }

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
 * A time without [second] is a TOML 1.1 spelling (`07:32`) and prints back without seconds; it cannot have a
 * [nanosecond].
 *
 * @throws IllegalArgumentException when constructed with a field out of range, or a [nanosecond] without a
 * [second].
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

    init {
        require(hour in 0..23) { "hour $hour is not between 0 and 23" }
        require(minute in 0..59) { "minute $minute is not between 0 and 59" }
        require(second == null || second in 0..60) { "second $second is not between 0 and 60" }
        require(nanosecond == null || nanosecond in 0..999_999_999) {
            "nanosecond $nanosecond is not between 0 and 999999999"
        }
        require(nanosecond == null || second != null) { "a nanosecond needs a second" }
    }

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
        if (second != null) {
            append(':')
            append(second.pad(2))
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

    /**
     * A numeric offset such as `-07:00`, in whole [minutes] east of UTC.
     *
     * @throws IllegalArgumentException when [minutes] is a day or more either way.
     */
    public data class Custom(
        /** Minutes east of UTC, -1439 to 1439: `-23:59` to `+23:59`. */
        val minutes: Int,
    ) : Offset {

        init {
            require(minutes in -MAX_MINUTES..MAX_MINUTES) { "offset of $minutes minutes is a day or more" }
        }

        /** Writes the offset as `+HH:MM` or `-HH:MM`. */
        override fun toString(): String {
            val sign = if (minutes < 0) '-' else '+'
            val magnitude = if (minutes < 0) -minutes else minutes
            return "$sign${(magnitude / 60).pad(2)}:${(magnitude % 60).pad(2)}"
        }
    }

    /** Orders [Z] first, then [Unknown], then each [Custom] by its minutes. */
    override fun compareTo(other: Offset): Int = when {
        this is Custom && other is Custom -> minutes.compareTo(other.minutes)
        else -> rank.compareTo(other.rank)
    }

    private val rank: Int
        get() = when (this) {
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

// RFC 3339's `time-numoffset` allows `23:59` at most.
private const val MAX_MINUTES = 23 * 60 + 59

/** The number of days in [month] of [year], in the proleptic Gregorian calendar RFC 3339 uses. */
internal fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}
