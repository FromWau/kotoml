package com.fromwau.kotoml

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.DatetimeExpected.DateOrTimeSeparator
import com.fromwau.kotoml.DatetimeExpected.DaySeparator
import com.fromwau.kotoml.DatetimeExpected.Digits
import com.fromwau.kotoml.DatetimeExpected.MinuteSeparator
import com.fromwau.kotoml.DatetimeExpected.OffsetStart
import com.fromwau.kotoml.DatetimeExpected.Range
import com.fromwau.kotoml.DatetimeExpected.TimeStart
import com.fromwau.kotoml.DatetimeExpected.Width
import com.fromwau.kotoml.DatetimeExpected.YearOrHour
import com.fromwau.kotoml.DatetimeField.Day
import com.fromwau.kotoml.DatetimeField.Hour
import com.fromwau.kotoml.DatetimeField.Minute
import com.fromwau.kotoml.DatetimeField.Month
import com.fromwau.kotoml.DatetimeField.Nanosecond
import com.fromwau.kotoml.DatetimeField.Second
import com.fromwau.kotoml.DatetimeField.Year

/**
 * Parses the RFC 3339 subset TOML allows, refusing at the same point, with the same part and expectation,
 * as `toml_datetime`.
 */
internal class DatetimeParser(private val input: String) {

    private enum class Kind { Digits, Dash, Colon, Dot, T, Space, Z, Plus, Unknown }

    private class Token(val kind: Kind, val raw: String)

    private var position = 0

    private fun next(): Token? {
        if (position >= input.length) return null
        val start = position
        val kind = when (input[position]) {
            in '0'..'9' -> {
                while (position < input.length && input[position] in '0'..'9') position++
                return Token(Kind.Digits, input.substring(start, position))
            }
            '-' -> Kind.Dash
            ':' -> Kind.Colon
            'T', 't' -> Kind.T
            ' ' -> Kind.Space
            'Z', 'z' -> Kind.Z
            '+' -> Kind.Plus
            '.' -> Kind.Dot
            else -> {
                position = input.length
                return Token(Kind.Unknown, input.substring(start))
            }
        }
        position++
        return Token(kind, input.substring(start, position))
    }

    private fun peekKind(): Kind? {
        val saved = position
        val kind = next()?.kind
        position = saved
        return kind
    }

    private fun error(
        part: DatetimePart? = null,
        expected: DatetimeExpected? = null,
    ): Err<DatetimeParseError> = Err(DatetimeParseError(part, expected))

    private fun dateError(expected: DatetimeExpected) = error(DatetimePart.Date, expected)

    private fun dateTimeError(expected: DatetimeExpected) = error(DatetimePart.DateTime, expected)

    private fun timeError(expected: DatetimeExpected) = error(DatetimePart.Time, expected)

    private fun offsetError(expected: DatetimeExpected) = error(DatetimePart.Offset, expected)

    fun parse(): Result<Datetime, DatetimeParseError> {
        var date: Date? = null
        var time: Time? = null
        var offset: Offset? = null

        val digits = next() ?: return error(expected = YearOrHour)
        if (digits.kind != Kind.Digits) return error(expected = YearOrHour)
        val sep = next() ?: return error(expected = DateOrTimeSeparator)
        when (sep.kind) {
            Kind.Dash -> {
                val year = digits
                val month = next() ?: return dateError(Digits(Month))
                if (month.kind != Kind.Digits) return dateError(Digits(Month))
                val daySep = next() ?: return dateError(DaySeparator)
                if (daySep.kind != Kind.Dash) return dateError(DaySeparator)
                val day = next() ?: return dateError(Digits(Day))
                if (day.kind != Kind.Digits) return dateError(Digits(Day))

                if (year.raw.length != 4) return dateError(Width(Year, 4))
                if (month.raw.length != 2) return dateError(Width(Month, 2))
                if (day.raw.length != 2) return dateError(Width(Day, 2))
                date = when (val built = Date.of(digitsValue(year.raw), digitsValue(month.raw), digitsValue(day.raw))) {
                    is Result.Success -> built.value
                    is Result.Error -> return built
                }
            }
            Kind.Colon -> position = 0
            else -> return error(expected = DateOrTimeSeparator)
        }

        val partialTime = if (date != null) {
            val timeSep = next()
            when {
                timeSep == null -> false
                timeSep.kind == Kind.T || timeSep.kind == Kind.Space -> true
                else -> return dateTimeError(TimeStart)
            }
        } else {
            true
        }

        if (partialTime) {
            val hour = next() ?: return timeError(Digits(Hour))
            if (hour.kind != Kind.Digits) return timeError(Digits(Hour))
            val minuteSep = next() ?: return timeError(MinuteSeparator)
            if (minuteSep.kind != Kind.Colon) return timeError(MinuteSeparator)
            val minute = next() ?: return timeError(Digits(Minute))
            if (minute.kind != Kind.Digits) return timeError(Digits(Minute))
            val second = if (peekKind() == Kind.Colon) {
                next()
                val second = next() ?: return timeError(Digits(Second))
                if (second.kind != Kind.Digits) return timeError(Digits(Second))
                second
            } else {
                null
            }
            val nanosecond = if (second != null && peekKind() == Kind.Dot) {
                next()
                val nanosecond = next() ?: return timeError(Digits(Nanosecond))
                if (nanosecond.kind != Kind.Digits) return timeError(Digits(Nanosecond))
                nanosecond
            } else {
                null
            }

            if (hour.raw.length != 2) return timeError(Width(Hour, 2))
            if (minute.raw.length != 2) return timeError(Width(Minute, 2))
            if (second != null && second.raw.length != 2) return timeError(Width(Second, 2))

            val built = Time.of(
                hour = digitsValue(hour.raw),
                minute = digitsValue(minute.raw),
                second = second?.let { digitsValue(it.raw) },
                nanosecond = nanosecond?.let { secondsToNanoseconds(it.raw) },
            )
            time = when (built) {
                is Result.Success -> built.value
                is Result.Error -> return built
            }
        }

        if (date != null && time != null) {
            val token = next()
            when {
                token == null -> {}
                token.kind == Kind.Z -> offset = Offset.Z
                token.kind == Kind.Plus || token.kind == Kind.Dash -> {
                    val sign = if (token.kind == Kind.Plus) 1 else -1
                    val hours = next() ?: return offsetError(Digits(Hour))
                    if (hours.kind != Kind.Digits) return offsetError(Digits(Hour))
                    val minuteSep = next() ?: return offsetError(MinuteSeparator)
                    if (minuteSep.kind != Kind.Colon) return offsetError(MinuteSeparator)
                    val minutes = next() ?: return offsetError(Digits(Minute))
                    if (minutes.kind != Kind.Digits) return offsetError(Digits(Minute))

                    if (hours.raw.length != 2) return offsetError(Width(Hour, 2))
                    if (minutes.raw.length != 2) return offsetError(Width(Minute, 2))

                    val hourValue = digitsValue(hours.raw)
                    val minuteValue = digitsValue(minutes.raw)
                    if (hourValue > 23) return offsetError(Range(Hour, 0, 23))
                    if (minuteValue > 59) return offsetError(Range(Minute, 0, 59))

                    val minutesEast = sign * (hourValue * 60 + minuteValue)
                    offset = if (minutesEast == 0 && sign < 0) {
                        Offset.Unknown
                    } else {
                        when (val built = Offset.Custom.of(minutesEast)) {
                            is Result.Success -> built.value
                            is Result.Error -> return built
                        }
                    }
                }
                else -> return offsetError(OffsetStart)
            }
        }

        if (position < input.length) return error()

        return Ok(
            when {
                date == null -> Datetime.LocalTime(checkNotNull(time) { "a date-time without a date has a time" })
                time == null -> Datetime.LocalDate(date)
                offset == null -> Datetime.LocalDateTime(date, time)
                else -> Datetime.OffsetDateTime(date, time, offset)
            },
        )
    }

    // Only called on a run of ASCII digits no longer than a datetime field, so it cannot overflow.
    private fun digitsValue(digits: String): Int = digits.fold(0) { value, digit -> value * 10 + (digit - '0') }

    // Digits past the ninth are truncated, as the spec requires of precision an implementation cannot keep.
    private fun secondsToNanoseconds(digits: String): Int {
        var nanosecond = 0
        var scale = 100_000_000
        for (index in 0 until minOf(digits.length, 9)) {
            nanosecond += scale * (digits[index] - '0')
            scale /= 10
        }
        return nanosecond
    }
}
