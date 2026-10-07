package com.fromwau.kotoml

import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which comments a removal takes: a comment above the removed thing stays, while the one on its own line and every
 * comment inside its value or body go with it.
 */
class RemovalCommentScopeTest {

    private fun removing(
        text: String,
        edit: (TomlDocument) -> Unit,
    ): String {
        val document = TomlDocument.parse(text).assertSuccess()
        edit(document)
        return document.toString()
    }

    @Test
    fun `a comment above a removed key stays`() {
        val text = "[[server]]\n#host = \"127.0.0.1\"\nhost = \"localhost\"\nport = 8080\n"
        assertEquals(
            "[[server]]\n#host = \"127.0.0.1\"\nport = 8080\n",
            removing(text) { it["server"].assertSuccess().arrayOfTablesOrNull!![0].assertSuccess().remove("host") },
        )
    }

    @Test
    fun `a comment on the removed key's line goes with it`() {
        val text = "port = 8080 # default\nhost = \"0.0.0.0\"\n"
        assertEquals("host = \"0.0.0.0\"\n", removing(text) { it.remove("port") })
    }

    @Test
    fun `a comment above a removed table's header stays`() {
        val text = "name = \"app\"\n\n# database settings\n[database]\nurl = \"postgres://\"\n"
        assertEquals("name = \"app\"\n\n# database settings\n", removing(text) { it.remove("database") })
    }

    @Test
    fun `a comment inside a removed table's body goes with it`() {
        val text = "[database]\n# use the replica\nurl = \"postgres://\"\n"
        assertEquals("", removing(text) { it.remove("database") })
    }

    @Test
    fun `a comment above a nested header inside a removed table goes with it`() {
        val text = "[database]\nurl = \"postgres://\"\n# pool tuning\n[database.pool]\nsize = 10\n"
        assertEquals("", removing(text) { it.remove("database") })
    }

    @Test
    fun `removing an array of tables keeps only the comment above its first header`() {
        val text = "# all servers\n[[server]]\nhost = \"a\"\n# backup\n[[server]]\nhost = \"b\"\n"
        assertEquals("# all servers\n", removing(text) { it.remove("server") })
    }

    @Test
    fun `a comment above a removed array of tables element stays`() {
        val text = "[[server]]\nhost = \"a\"\n# backup\n[[server]]\nhost = \"b\"\n"
        assertEquals(
            "[[server]]\nhost = \"a\"\n# backup\n",
            removing(text) { it["server"].assertSuccess().arrayOfTablesOrNull!!.removeAt(1).assertSuccess() },
        )
    }

    @Test
    fun `a comment inside a removed multi-line array goes with it`() {
        val text = "ports = [\n  8080, # http\n  # 8443, # https later\n]\nhost = \"0.0.0.0\"\n"
        assertEquals("host = \"0.0.0.0\"\n", removing(text) { it.remove("ports") })
    }

    @Test
    fun `a comment inside a removed multi-line inline table goes with it`() {
        val text = "owner = {\n  # contact for outages\n  name = \"Ada\",\n}\nhost = \"0.0.0.0\"\n"
        assertEquals("host = \"0.0.0.0\"\n", removing(text) { it.remove("owner") })
    }

    @Test
    fun `a removed array element keeps the comment above it and takes the one on its line`() {
        val text = "ports = [\n  8080, # http\n  # https\n  8443, # tls\n]\n"
        assertEquals(
            "ports = [\n  8080, # http\n  # https\n]\n",
            removing(text) { it["ports"].assertSuccess().arrayOrNull!!.removeAt(1).assertSuccess() },
        )
    }

    @Test
    fun `a comment above the removed last key of a table stays before the next header`() {
        val text = "[database]\n# primary\nurl = \"postgres://\"\n[cache]\n"
        assertEquals(
            "[database]\n# primary\n[cache]\n",
            removing(text) { it["database"].assertSuccess().standardTableOrNull!!.remove("url") },
        )
    }

    @Test
    fun `removing a table whose sub-table prints first keeps only the comment above the first header`() {
        val text = "# comment 1\n[a.b.c]\nx = 1\n# comment 3\n[a]\ny = 2\n"
        assertEquals("# comment 1\n", removing(text) { it.remove("a") })
    }

    @Test
    fun `clearing an array of tables keeps the comment above its first element above its key as an empty array`() {
        val text = "# all servers\n[[server]]\nhost = \"a\"\n# backup\n[[server]]\nhost = \"b\"\n"
        assertEquals(
            "# all servers\nserver = []\n",
            removing(text) { it["server"].assertSuccess().arrayOfTablesOrNull!!.clear() },
        )
    }
}
