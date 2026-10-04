package com.fromwau.kotoml.writer

import com.fromwau.kotoml.assertDebugSnapshot
import com.fromwau.kotoml.lit
import com.fromwau.kotoml.option
import com.fromwau.kotoml.str
import com.fromwau.kotoml.struct
import kotlin.test.Test

class WriterIntegerTest {

    private fun assertWritesInEveryFormat(value: Long, expected: String) {
        val results = struct(
            "IntegerResults",
            "value" to lit(value),
            "decimal" to option(TomlIntegerFormat().asDecimal().format(value)?.toTomlValue()) { str(it) },
            "hex_upper" to option(TomlIntegerFormat().asHexUpper().format(value)?.toTomlValue()) { str(it) },
            "hex_lower" to option(TomlIntegerFormat().asHexLower().format(value)?.toTomlValue()) { str(it) },
            "octal" to option(TomlIntegerFormat().asOctal().format(value)?.toTomlValue()) { str(it) },
            "binary" to option(TomlIntegerFormat().asBinary().format(value)?.toTomlValue()) { str(it) },
        )
        assertDebugSnapshot(expected, results)
    }

    @Test
    fun `a positive integer writes in every radix`() {
        assertWritesInEveryFormat(
            42,
            """
IntegerResults {
    value: 42,
    decimal: Some(
        "42",
    ),
    hex_upper: Some(
        "0x2A",
    ),
    hex_lower: Some(
        "0x2a",
    ),
    octal: Some(
        "0o52",
    ),
    binary: Some(
        "0b101010",
    ),
}

""",
        )
    }

    @Test
    fun `a negative integer writes only in decimal`() {
        assertWritesInEveryFormat(
            -42,
            """
IntegerResults {
    value: -42,
    decimal: Some(
        "-42",
    ),
    hex_upper: None,
    hex_lower: None,
    octal: None,
    binary: None,
}

""",
        )
    }
}
