package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test

class DatetimeDisplayTest {

    private fun assertPrintsAndParsesBack(input: Datetime, expected: String) {
        val actual = input.toString()
        assertDebugSnapshot(expected, str(actual))
        Datetime.parse(actual).assertSuccess()
    }

    @Test
    fun `a time without seconds prints as hours and minutes`() {
        assertPrintsAndParsesBack(
            Datetime.LocalTime(
                Time.of(hour = 13, minute = 37, second = null, nanosecond = null).assertSuccess(),
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
                Time.of(hour = 13, minute = 37, second = 0, nanosecond = 0).assertSuccess(),
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
                date = Date.of(year = 1979, month = 5, day = 27).assertSuccess(),
                time = Time.of(hour = 7, minute = 32, second = null, nanosecond = null).assertSuccess(),
                offset = Offset.Z,
            ),
            """
"1979-05-27T07:32Z"

""",
        )
    }
}
