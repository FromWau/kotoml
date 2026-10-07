package com.fromwau.kotoml.app

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class FilterTest {

    private fun steps(filter: String): List<List<Step>> = parseFilter(filter).assertSuccess().stages

    private fun refused(filter: String): AppError.FilterSyntax = parseFilter(filter).assertError()

    @Test
    fun `a unicode escape takes exactly four hex digits`() {
        assertEquals(listOf(listOf(Step.Key("é"))), steps(".\"\\u00e9\""))
        assertEquals(FilterExpected.Escape, refused(".\"\\u-041\"").expected)
        assertEquals(FilterExpected.Escape, refused(".\"\\u+041\"").expected)
        assertEquals(FilterExpected.Escape, refused(".\"\\u12\"").expected)
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
    fun `an index beyond 32 bits is refused as out of range`() {
        assertEquals(
            AppError.FilterSyntax(".[2147483648]", 2, FilterExpected.IndexInRange),
            refused(".[2147483648]"),
        )
        assertEquals(
            AppError.FilterSyntax(".a[-2147483649]", 3, FilterExpected.IndexInRange),
            refused(".a[-2147483649]"),
        )
        assertEquals(
            listOf(listOf(Step.Index(Int.MAX_VALUE), Step.Index(Int.MIN_VALUE))),
            steps(".[2147483647][-2147483648]"),
        )
    }

    @Test
    fun `an index out of range is a usage error that names the range`() {
        val error = AppError.FilterSyntax(".[2147483648]", 2, FilterExpected.IndexInRange)
        assertEquals(2, error.exitCode)
        assertEquals(
            "invalid filter at character 3, expected an index from -2147483648 to 2147483647\n  .[2147483648]\n    ^",
            error.describe(),
        )
    }

    @Test
    fun `a sign without digits is refused as no index`() {
        assertEquals(AppError.FilterSyntax(".[-]", 2, FilterExpected.IndexOrKey), refused(".[-]"))
    }

    @Test
    fun `only a pipe follows a stage`() {
        assertEquals(AppError.FilterSyntax(".a .b", 3, FilterExpected.PipeOrEnd), refused(".a .b"))
    }
}
