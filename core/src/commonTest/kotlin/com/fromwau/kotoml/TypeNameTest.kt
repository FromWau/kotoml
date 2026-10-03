package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The name each TOML type has in error text. */
class TypeNameTest {

    @Test
    fun `a slot holding nothing is named missing`() {
        assertEquals("missing", TomlType.Missing.describe())
    }

    @Test
    fun `an item indexed at a key that is not there is missing`() {
        val doc = TomlDocument.parse("a = 1\n").unwrap()
        assertEquals(TomlType.Missing, doc["b"].type)
        assertTrue(doc["b"].isMissing)
        assertFalse(doc["a"].isMissing)
    }
}
