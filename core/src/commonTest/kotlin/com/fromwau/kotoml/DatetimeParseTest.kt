package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DatetimeParseTest {

    private fun assertParsesTo(input: String, expected: String) {
        val actual = Datetime.parse(input)
        assertDebugSnapshot(expected, actual.debug({ it.dbg() }, { it.dbg() }))
    }

    @Test
    fun `the Datetime KDoc example parses an offset date-time and builds a local date`() {
        val odt = Datetime.parse("1979-05-27T07:32:00Z").assertSuccess()
        val date = Datetime.LocalDate(Date.of(year = 1979, month = 5, day = 27).assertSuccess())
        assertIs<Datetime.OffsetDateTime>(odt)
        assertEquals("1979-05-27", date.toString())
    }

    @Test
    fun `each of the four forms parses to its own kind`() {
        assertIs<Datetime.OffsetDateTime>(Datetime.parse("1979-05-27T07:32:00Z").assertSuccess())
        assertIs<Datetime.LocalDateTime>(Datetime.parse("1979-05-27T07:32:00").assertSuccess())
        assertIs<Datetime.LocalDate>(Datetime.parse("1979-05-27").assertSuccess())
        assertIs<Datetime.LocalTime>(Datetime.parse("07:32:00").assertSuccess())
    }

    @Test
    fun `a lone T is refused as missing a year or hour`() {
        assertParsesTo(
            "T",
            """
Err(
    DatetimeParseError {
        what: None,
        expected: Some(
            "year or hour",
        ),
    },
)

""",
        )
    }

    @Test
    fun `a lone TZ is refused as missing a year or hour`() {
        assertParsesTo(
            "TZ",
            """
Err(
    DatetimeParseError {
        what: None,
        expected: Some(
            "year or hour",
        ),
    },
)

""",
        )
    }

    @Test
    fun `a lone TZ followed by a dot is refused as missing a year or hour`() {
        assertParsesTo(
            "TZ.",
            """
Err(
    DatetimeParseError {
        what: None,
        expected: Some(
            "year or hour",
        ),
    },
)

""",
        )
    }

    @Test
    fun `a time without seconds parses with no seconds`() {
        assertParsesTo(
            "13:37",
            """
Ok(
    Datetime {
        date: None,
        time: Some(
            Time {
                hour: 13,
                minute: 37,
                second: None,
                nanosecond: None,
            },
        ),
        offset: None,
    },
)

""",
        )
    }

    @Test
    fun `a fraction after minutes without seconds is refused`() {
        assertParsesTo(
            "13:37.0",
            """
Err(
    DatetimeParseError {
        what: Some(
            "time",
        ),
        expected: Some(
            "a second before the fraction",
        ),
    },
)

""",
        )
    }

    @Test
    fun `an offset date-time without seconds parses with no seconds`() {
        assertParsesTo(
            "1979-05-27 07:32Z",
            """
Ok(
    Datetime {
        date: Some(
            Date {
                year: 1979,
                month: 5,
                day: 27,
            },
        ),
        time: Some(
            Time {
                hour: 7,
                minute: 32,
                second: None,
                nanosecond: None,
            },
        ),
        offset: Some(
            Z,
        ),
    },
)

""",
        )
    }
}
