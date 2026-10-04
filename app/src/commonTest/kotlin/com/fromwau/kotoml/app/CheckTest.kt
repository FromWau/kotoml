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
        is Result.Error -> AppError.InvalidToml(parsed.error, text)
    }

    @Test
    fun `an error in a CRLF file quotes its line without the carriage return`() {
        val described = invalid("a = 1\r\na = 2\r\n").describe()
        assertFalse('\r' in described, described)
        assertEquals(invalid("a = 1\na = 2\n").describe(), described)
    }

    @Test
    fun `a lone carriage return ending the file stays in the quoted line`() {
        val described = invalid("a = 1\r").describe()
        assertTrue("1 | a = 1\r\n" in described, described)
    }
}
