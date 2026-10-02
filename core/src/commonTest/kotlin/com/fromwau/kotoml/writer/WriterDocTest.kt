package com.fromwau.kotoml.writer

import kotlin.test.Test
import kotlin.test.assertEquals

/** Upstream's documentation examples for `toml_writer`, which `cargo test` runs as doctests. */
class WriterDocTest {

    @Test
    fun crateExample() {
        val output = StringWriter()
        output.newline()
        output.openTableHeader()
        output.key(WriteTomlKey.of("table"))
        output.closeTableHeader()
        output.newline()

        output.key(WriteTomlKey.of("key"))
        output.space()
        output.keyvalSep()
        output.space()
        output.value(WriteTomlValue.of("value"))
        output.newline()

        assertEquals("\n[table]\nkey = \"value\"\n", output.toString())
    }

    @Test
    fun tomlStringBuilderExample() {
        val string = TomlStringBuilder("Hello\nworld!\n").asDefault()
        assertEquals("\"\"\"\nHello\nworld!\n\"\"\"", string.toTomlValue())
    }

    @Test
    fun tomlIntegerFormatExample() {
        val format = TomlIntegerFormat().asHexLower()
        val number = format.format(10L) ?: TomlInteger(10L)
        assertEquals("0xa", number.toTomlValue())
    }
}
