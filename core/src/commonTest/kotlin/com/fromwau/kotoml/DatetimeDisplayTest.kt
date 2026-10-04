package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertIs

class DatetimeDisplayTest {

    private fun assertPrintsAndParsesBack(input: Datetime, expected: String) {
        val actual = input.toString()
        assertDebugSnapshot(expected, str(actual))
        assertIs<Result.Success<Datetime>>(Datetime.parse(actual))
    }

    @Test
    fun `a time without seconds prints as hours and minutes`() {
        assertPrintsAndParsesBack(
            Datetime.LocalTime(
                Time.of(hour = 13, minute = 37, second = null, nanosecond = null).getOrFail(),
            ),
            """
"13:37"

""",
        )
    }

    @Test
    fun `a time with zero seconds and zero nanoseconds prints a one digit fraction`() {
        assertPrintsAndParsesBack(
            Datetime.LocalTime(
                Time.of(hour = 13, minute = 37, second = 0, nanosecond = 0).getOrFail(),
            ),
            """
"13:37:00.0"

""",
        )
    }

    @Test
    fun `an offset date-time without seconds prints as hours and minutes`() {
        assertPrintsAndParsesBack(
            Datetime.OffsetDateTime(
                date = Date.of(year = 1979, month = 5, day = 27).getOrFail(),
                time = Time.of(hour = 7, minute = 32, second = null, nanosecond = null).getOrFail(),
                offset = Offset.Z,
            ),
            """
"1979-05-27T07:32Z"

""",
        )
    }
}
