package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A parsed document's keys know the line and column they were written at. */
class KeyPositionTest {

    private val document = TomlDocument
        .parse("\uFEFFa = 1\n[db]\n  \"é\".k = 2\nport = 3\n[[srv]]\nx.\"y\" = { z = 4 }\r\n")
        .assertSuccess()

    private val db = document["db"].assertSuccess().tableOrNull

    @Test
    fun `a key knows its line and column`() {
        assertEquals(TextPosition(1, 1), document.root.keyOrNull("a")?.position)
        assertEquals(TextPosition(4, 1), db?.keyOrNull("port")?.position)
    }

    @Test
    fun `a column counts characters`() {
        assertEquals(TextPosition(3, 7), db?.getItem("é")?.tableOrNull?.keyOrNull("k")?.position)
    }

    @Test
    fun `a header key and the parts of a dotted key know theirs`() {
        assertEquals(TextPosition(2, 2), document.root.keyOrNull("db")?.position)
        val srv = document["srv"].assertSuccess().arrayOfTablesOrNull?.getOrNull(0)
        assertEquals(TextPosition(6, 1), srv?.keyOrNull("x")?.position)
        val dotted = srv?.getItem("x")?.tableOrNull
        assertEquals(TextPosition(6, 3), dotted?.keyOrNull("y")?.position)
        assertEquals(TextPosition(6, 11), dotted?.getItem("y")?.inlineTableOrNull?.keyOrNull("z")?.position)
    }

    @Test
    fun `a key made in code or parsed on its own has no position`() {
        assertNull(Key("a").position)
        assertNull(Key.parse("a").assertSuccess().position)
        assertNull(document.root.keyOrNull("a")?.copy()?.position)
    }
}
