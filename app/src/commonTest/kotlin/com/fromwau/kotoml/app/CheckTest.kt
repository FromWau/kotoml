package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import com.fromwau.kotoml.TomlDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CheckTest {

    private fun invalid(text: String): AppError.InvalidToml = when (val parsed = TomlDocument.parse(text)) {
        is Result.Success -> fail("$text parsed")
        is Result.Error -> AppError.InvalidToml("bad.toml", parsed.error, text)
    }

    @Test
    fun `an error in a CRLF file quotes its line without the carriage return`() {
        val described = invalid("a = 1\r\na = 2\r\n").describe()
        assertFalse('\r' in described, described)
        assertEquals(invalid("a = 1\na = 2\n").describe(), described)
    }

    @Test
    fun `a lone carriage return ending the file is quoted as an escape`() {
        val described = invalid("a = 1\r").describe()
        assertTrue("1 | a = 1\\x0D\n" in described, described)
    }

    @Test
    fun `an error starts with the file it is in`() {
        assertTrue(invalid("a = 1\na = 2\n").describe().startsWith("bad.toml: TOML parse error at line 2"))
        val fromStandardInput = invalid("a = 1\na = 2\n").copy(path = "-").describe()
        assertTrue(fromStandardInput.startsWith("standard input: TOML parse error"), fromStandardInput)
    }
}
