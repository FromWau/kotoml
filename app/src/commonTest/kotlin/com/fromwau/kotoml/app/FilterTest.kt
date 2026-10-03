package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class FilterTest {

    private fun steps(filter: String): List<List<Step>> = when (val parsed = parseFilter(filter)) {
        is Result.Success -> parsed.value.stages
        is Result.Error -> fail("$filter: ${parsed.error}")
    }

    private fun refused(filter: String): AppError.FilterSyntax = when (val parsed = parseFilter(filter)) {
        is Result.Success -> fail("$filter parsed to ${parsed.value}")
        is Result.Error -> parsed.error
    }

    @Test
    fun `a lone dot is the identity filter`() {
        assertEquals(listOf(emptyList()), steps("."))
    }

    @Test
    fun `dotted keys chain into steps`() {
        assertEquals(listOf(listOf(Step.Key("a"), Step.Key("b-c"), Step.Key("d_1"))), steps(".a.b-c.d_1"))
    }

    @Test
    fun `quoted keys take any text in either form`() {
        assertEquals(listOf(listOf(Step.Key("a b"), Step.Key("q\"x"))), steps(".\"a b\".\"q\\\"x\""))
        assertEquals(listOf(listOf(Step.Key("x.y"))), steps(""".["x.y"]"""))
    }

    @Test
    fun `indexes and iteration follow a key`() {
        assertEquals(
            listOf(listOf(Step.Key("a"), Step.Index(0), Step.Index(-1), Step.Iterate)),
            steps(".a[0][-1][]"),
        )
        assertEquals(listOf(listOf(Step.Index(2))), steps(".[ 2 ]"))
    }

    @Test
    fun `pipes separate stages`() {
        assertEquals(
            listOf(listOf(Step.Key("servers"), Step.Iterate), listOf(Step.Key("host"))),
            steps(".servers[] | .host"),
        )
    }

    @Test
    fun `a stage starts with a dot`() {
        assertEquals(AppError.FilterSyntax("a", 0, FilterExpected.Dot), refused("a"))
        assertEquals(AppError.FilterSyntax(".a | b", 5, FilterExpected.Dot), refused(".a | b"))
    }

    @Test
    fun `a dot needs a step after it`() {
        assertEquals(AppError.FilterSyntax(".a..b", 3, FilterExpected.KeyOrBracket), refused(".a..b"))
        assertEquals(AppError.FilterSyntax(".a.", 3, FilterExpected.KeyOrBracket), refused(".a."))
    }

    @Test
    fun `brackets close and hold an index or a quoted key`() {
        assertEquals(AppError.FilterSyntax(".[x]", 2, FilterExpected.IndexOrKey), refused(".[x]"))
        assertEquals(AppError.FilterSyntax(".[1", 3, FilterExpected.CloseBracket), refused(".[1"))
        assertEquals(AppError.FilterSyntax(".\"a", 3, FilterExpected.Quote), refused(".\"a"))
    }

    @Test
    fun `only a pipe follows a stage`() {
        assertEquals(AppError.FilterSyntax(".a .b", 3, FilterExpected.PipeOrEnd), refused(".a .b"))
    }
}
