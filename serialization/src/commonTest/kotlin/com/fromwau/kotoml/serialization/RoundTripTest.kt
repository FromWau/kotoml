package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TomlDocument
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RoundTripTest {

    private inline fun <reified T> assertRoundTrips(
        value: T,
        toml: Toml = Toml,
    ) {
        val text = toml.encodeToString(value).unwrap()
        assertEquals(value, toml.decodeFromString<T>(text).unwrap(), text)
        val document = TomlDocument.parse(text).unwrap()
        toml.encodeInto(document, value).unwrap()
        assertEquals(text, document.toString())
    }

    @Test
    fun `the owner's model round-trips`() {
        assertRoundTrips(Config("example.org", 8080, 30.seconds, Mode.UDP, listOf("alpha" to 1, "beta" to 2)))
        assertRoundTrips(Config("example.org", 8080))
    }

    @Test
    fun `nested classes and lists round-trip in both styles`() {
        val app = App(
            title = "t",
            owner = "o",
            database = Database("db", Pool(8, 5.minutes)),
            servers = listOf(Server("a"), Server("b", 1)),
            tags = listOf("x"),
        )
        assertRoundTrips(app)
        assertRoundTrips(app, Toml { tableListStyle = TableListStyle.InlineArray })
        assertRoundTrips(app, Toml { encodeDefaults = true })
    }

    @Test
    fun `sealed values round-trip`() {
        assertRoundTrips(Drawing(listOf(Circle(1.5), Square(2.0), Point)))
        assertRoundTrips(Holder(Point), Toml { classDiscriminator = "kind" })
    }

    @Test
    fun `scalars round-trip`() {
        assertRoundTrips(Numbers(1, 2, 3, 4, 5.5f, Double.NEGATIVE_INFINITY, 'c'))
        assertRoundTrips(Unsigned(255u, UInt.MAX_VALUE, 1u))
        assertRoundTrips(
            Times(
                date = LocalDate(2024, 2, 29),
                time = LocalTime(23, 59, 59, 1),
                local = LocalDateTime(2024, 2, 29, 0, 0),
                instant = Instant.parse("2024-02-29T12:00:00.123456789Z"),
            ),
        )
    }

    @Test
    fun `maps round-trip`() {
        assertRoundTrips(Ports(mapOf("http" to 80, "a b" to 1)))
        assertRoundTrips(ModePorts(mapOf(Mode.UDP to 53)))
        assertRoundTrips(mapOf("a" to listOf(1, 2), "b" to emptyList()))
    }
}
