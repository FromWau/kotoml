package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TextPosition
import com.fromwau.kotoml.TomlType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Numbers, characters, unsigned integers and date-times, read and written. */
class ScalarTest {

    @Test
    fun `an integer outside Int is out of range`() {
        assertEquals(
            TomlDecodeError.OutOfRange("int", "2147483648", "kotlin.Int", position = TextPosition(1, 1)),
            Toml.decodeFromString<Numbers>("int = 2147483648\n").errorOrFail(),
        )
    }

    @Test
    fun `an integer outside Short is out of range`() {
        assertEquals(
            TomlDecodeError.OutOfRange("short", "-32769", "kotlin.Short", position = TextPosition(1, 1)),
            Toml.decodeFromString<Numbers>("short = -32769\n").errorOrFail(),
        )
    }

    @Test
    fun `an integer outside Byte is out of range`() {
        assertEquals(
            TomlDecodeError.OutOfRange("byte", "128", "kotlin.Byte", position = TextPosition(1, 1)),
            Toml.decodeFromString<Numbers>("byte = 128\n").errorOrFail(),
        )
    }

    @Test
    fun `the edges of each integer type read`() {
        val text = "byte = -128\nshort = 32767\nint = -2147483648\nlong = 9223372036854775807\n"
        assertEquals(
            Numbers(byte = Byte.MIN_VALUE, short = Short.MAX_VALUE, int = Int.MIN_VALUE, long = Long.MAX_VALUE),
            Toml.decodeFromString<Numbers>(text).getOrFail(),
        )
    }

    @Test
    fun `an integer reads into a Double and a Float`() {
        assertEquals(
            Numbers(double = 3.0, float = 2f),
            Toml.decodeFromString<Numbers>("double = 3\nfloat = 2\n").getOrFail(),
        )
    }

    @Test
    fun `a float does not read into an integer`() {
        assertEquals(
            TomlDecodeError.TypeMismatch(
                "long",
                TomlType.Integer,
                TomlType.Float,
                value = "1.0",
                position = TextPosition(1, 1),
            ),
            Toml.decodeFromString<Numbers>("long = 1.0\n").errorOrFail(),
        )
    }

    @Test
    fun `a float too large for Float is out of range`() {
        assertEquals(
            TomlDecodeError.OutOfRange("float", "1.0E300", "kotlin.Float", position = TextPosition(1, 1)),
            Toml.decodeFromString<Numbers>("float = 1e300\n").errorOrFail(),
        )
    }

    @Test
    fun `a non-zero float too small for Float is out of range`() {
        assertEquals(
            TomlDecodeError.OutOfRange("float", "1.0E-50", "kotlin.Float", position = TextPosition(1, 1)),
            Toml.decodeFromString<Numbers>("float = 1e-50\n").errorOrFail(),
        )
        assertEquals(
            TomlDecodeError.OutOfRange("float", "-1.0E-50", "kotlin.Float", position = TextPosition(1, 1)),
            Toml.decodeFromString<Numbers>("float = -1e-50\n").errorOrFail(),
        )
    }

    @Test
    fun `zero and the smallest Float read as themselves`() {
        assertEquals(0.0f, Toml.decodeFromString<Numbers>("float = 0.0\n").getOrFail().float)
        assertEquals(-0.0f, Toml.decodeFromString<Numbers>("float = -0.0\n").getOrFail().float)
        assertEquals(Float.MIN_VALUE, Toml.decodeFromString<Numbers>("float = 1.4e-45\n").getOrFail().float)
    }

    @Test
    fun `a Float is written as its decimal text`() {
        assertEquals("float = 0.1\n", Toml { encodeDefaults = false }.encodeToString(Numbers(float = 0.1f)).getOrFail())
    }

    @Test
    fun `a float far from one is written in exponent form and reads back`() {
        val numbers = Numbers(float = 3e20f, double = Double.MIN_VALUE)
        val text = Toml { encodeDefaults = false }.encodeToString(numbers).getOrFail()
        assertEquals("float = 3e20\ndouble = 5e-324\n", text)
        assertEquals(numbers, Toml.decodeFromString<Numbers>(text).getOrFail())
    }

    @Test
    fun `nan and infinity round-trip`() {
        val numbers = Numbers(float = Float.NEGATIVE_INFINITY, double = Double.NaN)
        val text = Toml.encodeToString(numbers).getOrFail()
        assertEquals("float = -inf\ndouble = nan\n", text)
        assertEquals(numbers, Toml.decodeFromString<Numbers>(text).getOrFail())
        assertEquals(
            Double.POSITIVE_INFINITY,
            Toml.decodeFromString<Numbers>("double = +inf\n").getOrFail().double,
        )
    }

    @Test
    fun `a Char is a one-character string`() {
        assertEquals("char = \"z\"\n", Toml.encodeToString(Numbers(char = 'z')).getOrFail())
        assertEquals('z', Toml.decodeFromString<Numbers>("char = \"z\"\n").getOrFail().char)
        assertEquals(
            TomlDecodeError.Unparsable("char", "zz", "kotlin.Char", position = TextPosition(1, 1)),
            Toml.decodeFromString<Numbers>("char = \"zz\"\n").errorOrFail(),
        )
    }

    @Test
    fun `unsigned integers are written as their value`() {
        val unsigned = Unsigned(byte = 255u, int = UInt.MAX_VALUE, long = Long.MAX_VALUE.toULong())
        val text = Toml.encodeToString(unsigned).getOrFail()
        assertEquals("byte = 255\nint = 4294967295\nlong = 9223372036854775807\n", text)
        assertEquals(unsigned, Toml.decodeFromString<Unsigned>(text).getOrFail())
    }

    @Test
    fun `a negative integer is out of range for an unsigned field`() {
        assertEquals(
            TomlDecodeError.OutOfRange("int", "-1", "kotlin.UInt", position = TextPosition(1, 1)),
            Toml.decodeFromString<Unsigned>("int = -1\n").errorOrFail(),
        )
    }

    @Test
    fun `a ULong past Long is out of range when written`() {
        assertEquals(
            TomlEncodeError.OutOfRange("long", "18446744073709551615"),
            Toml.encodeToString(Unsigned(long = ULong.MAX_VALUE)).errorOrFail(),
        )
    }

    @Test
    fun `a LocalDate is a native local date`() {
        val times = Times(date = LocalDate(1979, 5, 27))
        assertEquals("date = 1979-05-27\n", Toml.encodeToString(times).getOrFail())
        assertEquals(times, Toml.decodeFromString<Times>("date = 1979-05-27\n").getOrFail())
    }

    @Test
    fun `a LocalTime is a native local time`() {
        val times = Times(time = LocalTime(7, 32, 0, 999_000_000))
        assertEquals("time = 07:32:00.999\n", Toml.encodeToString(times).getOrFail())
        assertEquals(times, Toml.decodeFromString<Times>("time = 07:32:00.999\n").getOrFail())
    }

    @Test
    fun `a local time without seconds reads as the full minute`() {
        assertEquals(
            Times(time = LocalTime(7, 32)),
            Toml.decodeFromString<Times>("time = 07:32\n").getOrFail(),
        )
    }

    @Test
    fun `a LocalDateTime is a native local date-time`() {
        val times = Times(local = LocalDateTime(1979, 5, 27, 7, 32, 0))
        assertEquals("local = 1979-05-27T07:32:00\n", Toml.encodeToString(times).getOrFail())
        assertEquals(times, Toml.decodeFromString<Times>("local = 1979-05-27 07:32:00\n").getOrFail())
    }

    @Test
    fun `an Instant is written in UTC`() {
        val times = Times(instant = Instant.parse("1979-05-27T07:32:00.5+02:00"))
        assertEquals("instant = 1979-05-27T05:32:00.5Z\n", Toml.encodeToString(times).getOrFail())
    }

    @Test
    fun `an Instant reads any offset`() {
        val expected = Times(instant = Instant.parse("1979-05-27T05:32:00Z"))
        for (text in listOf("1979-05-27T07:32:00+02:00", "1979-05-27T00:02:00-05:30", "1979-05-27t05:32:00z")) {
            assertEquals(expected, Toml.decodeFromString<Times>("instant = $text\n").getOrFail(), text)
        }
    }

    @Test
    fun `a leap second is a typed error`() {
        assertEquals(
            TomlDecodeError.LeapSecond("time", position = TextPosition(1, 1)),
            Toml.decodeFromString<Times>("time = 23:59:60\n").errorOrFail(),
        )
        assertEquals(
            TomlDecodeError.LeapSecond("instant", position = TextPosition(1, 1)),
            Toml.decodeFromString<Times>("instant = 1990-12-31T23:59:60Z\n").errorOrFail(),
        )
    }

    @Test
    fun `a date-time of the wrong kind names both kinds`() {
        assertEquals(
            TomlDecodeError.DatetimeKindMismatch(
                "instant",
                DatetimeKind.OffsetDateTime,
                DatetimeKind.LocalDateTime,
                position = TextPosition(1, 1),
            ),
            Toml.decodeFromString<Times>("instant = 1979-05-27T07:32:00\n").errorOrFail(),
        )
        assertEquals(
            TomlDecodeError.DatetimeKindMismatch(
                "date",
                DatetimeKind.LocalDate,
                DatetimeKind.LocalTime,
                TextPosition(1, 1),
            ),
            Toml.decodeFromString<Times>("date = 07:32:00\n").errorOrFail(),
        )
    }

    @Test
    fun `a quoted date-time reads into a date-time field`() {
        val text = "date = \"1979-05-27\"\ntime = \"07:32\"\nlocal = \"1979-05-27T07:32\"\n" +
            "instant = \"1979-05-27T07:32:00Z\"\n"
        assertEquals(
            Times(
                date = LocalDate(1979, 5, 27),
                time = LocalTime(7, 32),
                local = LocalDateTime(1979, 5, 27, 7, 32),
                instant = Instant.parse("1979-05-27T07:32:00Z"),
            ),
            Toml.decodeFromString<Times>(text).getOrFail(),
        )
    }

    @Test
    fun `a native date-time read into a string gives its text`() {
        assertEquals(
            Text("1979-05-27T07:32:00Z"),
            Toml.decodeFromString<Text>("text = 1979-05-27T07:32:00Z\n").getOrFail(),
        )
    }

    @Test
    fun `a number in a date-time field is a type mismatch`() {
        assertEquals(
            TomlDecodeError.TypeMismatch(
                "date",
                TomlType.Datetime,
                TomlType.Integer,
                value = "19790527",
                position = TextPosition(1, 1),
            ),
            Toml.decodeFromString<Times>("date = 19790527\n").errorOrFail(),
        )
    }

    @Test
    fun `a year TOML cannot write is out of range`() {
        assertEquals(
            TomlEncodeError.OutOfRange("date", "+10000-01-01"),
            Toml.encodeToString(Times(date = LocalDate(10000, 1, 1))).errorOrFail(),
        )
    }

    @Test
    fun `a date with another serializer is written as that serializer says`() {
        val date = IsoDate(LocalDate(1979, 5, 27))
        assertEquals("date = \"1979-05-27\"\n", Toml.encodeToString(date).getOrFail())
        assertEquals(date, Toml.decodeFromString<IsoDate>("date = 1979-05-27\n").getOrFail())
    }

    @Test
    fun `a date-time map key is a string`() {
        val text = Toml.encodeToString(mapOf(LocalDate(1979, 5, 27) to 1)).getOrFail()
        assertEquals("1979-05-27 = 1\n", text)
        assertTrue(Toml.decodeFromString<Map<LocalDate, Int>>(text).getOrFail().containsKey(LocalDate(1979, 5, 27)))
    }
}
