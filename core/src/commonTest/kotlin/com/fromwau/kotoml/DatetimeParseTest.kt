package com.fromwau.kotoml

import kotlin.test.Test

class DatetimeParseTest {

    private fun t(input: String, expected: String) {
        val actual = Datetime.parse(input)
        assertDebug(expected, actual.debug({ it.dbg() }, { it.dbg() }))
    }

    @Test
    fun onlyT() {
        t(
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
    fun onlyTz() {
        t(
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
    fun onlyTzDot() {
        t(
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
    fun timeWithoutSeconds() {
        t(
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
    fun timeWithoutSecondsWithNanoseconds() {
        t(
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
    fun datetimeWithoutSeconds() {
        t(
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
