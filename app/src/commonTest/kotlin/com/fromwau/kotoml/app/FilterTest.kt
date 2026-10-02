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
    fun identity() {
        assertEquals(listOf(emptyList()), steps("."))
    }

    @Test
    fun keysChain() {
        assertEquals(listOf(listOf(Step.Key("a"), Step.Key("b-c"), Step.Key("d_1"))), steps(".a.b-c.d_1"))
    }

    @Test
    fun quotedKeys() {
        assertEquals(listOf(listOf(Step.Key("a b"), Step.Key("q\"x"))), steps(".\"a b\".\"q\\\"x\""))
        assertEquals(listOf(listOf(Step.Key("x.y"))), steps(""".["x.y"]"""))
    }

    @Test
    fun indexesAndIteration() {
        assertEquals(
            listOf(listOf(Step.Key("a"), Step.Index(0), Step.Index(-1), Step.Iterate)),
            steps(".a[0][-1][]"),
        )
        assertEquals(listOf(listOf(Step.Index(2))), steps(".[ 2 ]"))
    }

    @Test
    fun pipesSeparateStages() {
        assertEquals(
            listOf(listOf(Step.Key("servers"), Step.Iterate), listOf(Step.Key("host"))),
            steps(".servers[] | .host"),
        )
    }

    @Test
    fun aStageStartsWithADot() {
        assertEquals(AppError.FilterSyntax("a", 0, FilterExpected.Dot), refused("a"))
        assertEquals(AppError.FilterSyntax(".a | b", 5, FilterExpected.Dot), refused(".a | b"))
    }

    @Test
    fun aDotNeedsAStepAfterIt() {
        assertEquals(AppError.FilterSyntax(".a..b", 3, FilterExpected.KeyOrBracket), refused(".a..b"))
        assertEquals(AppError.FilterSyntax(".a.", 3, FilterExpected.KeyOrBracket), refused(".a."))
    }

    @Test
    fun bracketsCloseAndHoldIndexes() {
        assertEquals(AppError.FilterSyntax(".[x]", 2, FilterExpected.IndexOrKey), refused(".[x]"))
        assertEquals(AppError.FilterSyntax(".[1", 3, FilterExpected.CloseBracket), refused(".[1"))
        assertEquals(AppError.FilterSyntax(".\"a", 3, FilterExpected.Quote), refused(".\"a"))
    }

    @Test
    fun onlyAPipeFollowsAStage() {
        assertEquals(AppError.FilterSyntax(".a .b", 3, FilterExpected.PipeOrEnd), refused(".a .b"))
    }
}
