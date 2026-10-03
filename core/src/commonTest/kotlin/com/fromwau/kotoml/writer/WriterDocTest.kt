package com.fromwau.kotoml.writer

import kotlin.test.Test
import kotlin.test.assertEquals

/** Upstream's documentation examples for `toml_writer`, which `cargo test` runs as doctests. */
class WriterDocTest {

    @Test
    fun `a table header and a key value pair write through the writer calls`() {
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
    fun `a string with newlines writes as a multi-line basic string by default`() {
        val string = TomlStringBuilder("Hello\nworld!\n").asDefault()
        assertEquals("\"\"\"\nHello\nworld!\n\"\"\"", string.toTomlValue())
    }

    @Test
    fun `an integer format writes ten as lower-case hex`() {
        val format = TomlIntegerFormat().asHexLower()
        val number = format.format(10L) ?: TomlInteger(10L)
        assertEquals("0xa", number.toTomlValue())
    }
}
