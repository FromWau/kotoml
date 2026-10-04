package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** The name each TOML type has in error text. */
class TypeNameTest {

    @Test
    fun `nothing found is named missing`() {
        assertEquals("missing", TomlType.Missing.describe())
    }
}
