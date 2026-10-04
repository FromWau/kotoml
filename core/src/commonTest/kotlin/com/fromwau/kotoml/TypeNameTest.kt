package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The name each TOML type has in error text. */
class TypeNameTest {

    @Test
    fun `nothing found is named missing`() {
        assertEquals("missing", TomlType.Missing.describe())
    }

}
