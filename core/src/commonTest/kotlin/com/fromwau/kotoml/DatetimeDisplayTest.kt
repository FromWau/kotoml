package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertIs

class DatetimeDisplayTest {

    private fun t(input: Datetime, expected: String) {
        val actual = input.toString()
        assertDebug(expected, str(actual))
        assertIs<Result.Success<Datetime>>(Datetime.parse(actual))
    }

    @Test
    fun timeWithoutSeconds() {
        t(
            Datetime(
                date = null,
                time = Time(hour = 13, minute = 37, second = null, nanosecond = null),
                offset = null,
            ),
            """
"13:37"

""",
        )
    }

    @Test
    fun timeWithoutSecondsWithNanoseconds() {
        t(
            Datetime(
                date = null,
                time = Time(hour = 13, minute = 37, second = null, nanosecond = 5),
                offset = null,
            ),
            """
"13:37:00.000000005"

""",
        )
    }

    @Test
    fun timeWithZeroSecondsAndNanoseconds() {
        t(
            Datetime(
                date = null,
                time = Time(hour = 13, minute = 37, second = 0, nanosecond = 0),
                offset = null,
            ),
            """
"13:37:00.0"

""",
        )
    }

    @Test
    fun datetimeWithoutSeconds() {
        t(
            Datetime(
                date = Date(year = 1979, month = 5, day = 27),
                time = Time(hour = 7, minute = 32, second = null, nanosecond = null),
                offset = Offset.Z,
            ),
            """
"1979-05-27T07:32Z"

""",
        )
    }
}
