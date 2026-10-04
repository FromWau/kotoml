package com.fromwau.kotoml

import kotlin.test.Test

class DatetimeParseTest {

    private fun assertParsesTo(input: String, expected: String) {
        val actual = Datetime.parse(input)
        assertDebugSnapshot(expected, actual.debug({ it.dbg() }, { it.dbg() }))
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
        what: None,
        expected: None,
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
