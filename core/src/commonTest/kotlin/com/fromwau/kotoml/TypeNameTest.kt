package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals

/** The name each TOML type has in error text. */
class TypeNameTest {

    @Test
    fun `an array of tables is named in words`() {
        assertEquals("array of tables", TomlType.ArrayOfTables.describe())
    }
}
