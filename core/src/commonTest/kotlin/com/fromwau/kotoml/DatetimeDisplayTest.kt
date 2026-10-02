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
                time = Time(hour = 13u, minute = 37u, second = null, nanosecond = null),
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
                time = Time(hour = 13u, minute = 37u, second = null, nanosecond = 5u),
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
                time = Time(hour = 13u, minute = 37u, second = 0u, nanosecond = 0u),
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
                date = Date(year = 1979u, month = 5u, day = 27u),
                time = Time(hour = 7u, minute = 32u, second = null, nanosecond = null),
                offset = Offset.Z,
            ),
            """
"1979-05-27T07:32Z"

""",
        )
    }
}
