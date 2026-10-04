package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertIs

class DatetimeDisplayTest {

    private fun t(input: Datetime, expected: String) {
        val actual = input.toString()
        assertDebugSnapshot(expected, str(actual))
        assertIs<Result.Success<Datetime>>(Datetime.parse(actual))
    }

    @Test
    fun `a time without seconds prints as hours and minutes`() {
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
    fun `a time with nanoseconds but no seconds prints zero seconds before the fraction`() {
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
    fun `a time with zero seconds and zero nanoseconds prints a one digit fraction`() {
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
    fun `an offset date-time without seconds prints as hours and minutes`() {
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
