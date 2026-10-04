# kotoml serialization

Turns `@Serializable` Kotlin classes into TOML and back, the way `kotlinx-serialization-json` does for JSON. It
can also write a value into a TOML file you already have, changing only the values that differ, so the file
keeps its comments, blank lines, key spelling and key order. Failures come back as typed errors rather than
exceptions.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

## Add to your build

```kotlin
plugins {
    kotlin("plugin.serialization") version "$kotlinVersion"
}

repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kotoml:serialization:$kotomlVersion")
}
```

KMP consumers put it in `commonMain`. [`core`](../core/README.md) and `kotlinx-serialization-core` come along as
`api`, since `TomlDocument` and the serializer types appear in this module's signatures. To use kotlinx-datetime
types in your classes, add `kotlinx-datetime` yourself. The JVM and Android artifacts need a JDK 25 toolchain,
and Android needs `minSdk` 26. Published versions are listed at
[maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kotoml).

## Read TOML into a class

```kotlin
@Serializable
enum class Mode { TCP, @SerialName("udp") UDP }

@Serializable
data class Config(
    @SerialName("hostname") val host: String,
    val port: Int,
    val timeout: Duration = 15.seconds,
    val mode: Mode = Mode.TCP,
    val knownServers: List<Pair<String, Int>> = emptyList(),
)

val text = "hostname = \"example.org\"\nport = 8080\nmode = \"udp\"\n"
when (val decoded = Toml.decodeFromString<Config>(text)) {
    is Result.Success -> println(decoded.value)
    // Config(host=example.org, port=8080, timeout=15s, mode=UDP, knownServers=[])
    is Result.Error -> when (val error = decoded.error) {
        is TomlDecodeError.Parse -> println(error.error.render(text))
        is TomlDecodeError.MissingKey -> println("${error.path} is required")
        else -> println(error)
    }
}
```

A missing key takes its field's default. Every other problem is a `TomlDecodeError`: text that is not TOML, a
required key that is missing, a key no field names, a value of the wrong type or out of range, an unknown enum name
or subclass, a [nullable type](#nullable-types) in the class. Each one that concerns a place in the document names
it as a `path` such as `servers[1].port`. `Toml.decodeFromDocument` reads a `TomlDocument` you
already parsed, without changing it.

## Write a value as TOML

```kotlin
val text = Toml.encodeToString(Config("example.org", 8080, knownServers = listOf("alpha" to 1))).getOrNull()
// hostname = "example.org"
// port = 8080
//
// [[knownServers]]
// first = "alpha"
// second = 1
```

`Toml.encodeToDocument` gives a `TomlDocument` instead, to edit further before printing it. A value TOML cannot
hold, such as a `ULong` above `Long.MAX_VALUE`, is a `TomlEncodeError`, and so is a
[nullable type](#nullable-types) in the class.

## Update a file in place

```kotlin
val document = TomlDocument.parse("# where to listen\nhostname = \"example.org\"\nport = 8080 # the default\n")
    .getOrElse { error(it) }
val config = Toml.decodeFromDocument<Config>(document).getOrElse { error(it) }
Toml.encodeInto(document, config.copy(port = 9090, mode = Mode.UDP))
document.toString()
// # where to listen
// hostname = "example.org"
// port = 9090 # the default
// mode = "udp"
```

`encodeInto` changes only what differs:

- A key whose value changes keeps its place, its spelling (quoted, dotted) and the comment after it. A value
  that reads the same is left as written, so `0x1F90` stays `0x1F90` and `"PT30S"` stays `"PT30S"`.
- A key the document lacks is appended to its table.
- A key no field names stays, whatever `ignoreUnknownKeys` says.
- A nested class updates its table, recursively. A map accounts for every key of its table, so a key the map
  no longer has is removed.
- A list is merged into the document's list element by element, in the form the document wrote it: an array of
  tables stays one, and an inline array keeps its layout and the comment after it. An element equal to one the
  document holds keeps that element's text and comments, even when it moved. A changed element updates the old
  element sharing the most values with it, as a nested class updates its table; in a list of scalars, it takes
  the next old element left over. A new element is inserted at its place. Where an old element was removed
  there, it takes over the lines above that element; elsewhere a table gets default formatting and an element
  of an inline array a line like its neighbours'. Any other old element no new one takes is removed, while the
  comment lines above it stay.
- A field at its default, with `encodeDefaults` off, updates a key the document has and adds none.
- The serializer runs once. With `encodeDefaults` off, it runs a second time when a field at its default has a
  key in the document, to get the value that key is updated to.
- When a polymorphic value changes subclass, the keys of the old subclass are removed.

A list of servers, one of them given a port and a new one added in front:

```kotlin
@Serializable
data class Server(val host: String, val port: Int = 8080)

@Serializable
data class Fleet(val name: String, val servers: List<Server> = emptyList())

val document = TomlDocument.parse(
    "name = \"f\"\n\n[[servers]]\nhost = \"a\" # primary\n# fallback box\n[[servers]]\nhost = \"b\"\n",
).getOrElse { error(it) }
val fleet = Toml.decodeFromDocument<Fleet>(document).getOrElse { error(it) }
Toml.encodeInto(document, fleet.copy(servers = listOf(Server("z"), Server("a", 80), Server("b"))))
document.toString()
// name = "f"
//
// [[servers]]
// host = "z"
//
// [[servers]]
// host = "a" # primary
// port = 80
// # fallback box
// [[servers]]
// host = "b"
```

When `encodeInto` returns an error, the document is unchanged. To read and write the file itself, use core's
`TomlDocument.read` and `writeTo` (see [core's README](../core/README.md#read-and-write-files)).

## Configure the format

```kotlin
val toml = Toml {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "kind"
    tableListStyle = TableListStyle.InlineArray
}
```

| option               | default         | what it does                                                                   |
|----------------------|-----------------|--------------------------------------------------------------------------------|
| `ignoreUnknownKeys`  | `false`         | skip a key no field names, rather than return `TomlDecodeError.UnknownKey`     |
| `encodeDefaults`     | `false`         | write a field that holds its default value                                     |
| `classDiscriminator` | `"type"`        | the key holding a polymorphic value's subclass name                            |
| `tableListStyle`     | `ArrayOfTables` | write a list of classes as `[[key]]` tables, or as `InlineArray` `[{ … }, …]`  |
| `serializersModule`  | empty           | serializers for polymorphic and contextual types                               |

`Toml(from = other) { … }` starts from another instance's settings. `Toml.Default`, the companion object, holds
the defaults, so `Toml.decodeFromString<T>(text)` uses them.

## How Kotlin maps to TOML

| Kotlin                                     | TOML                                                                                                     |
|--------------------------------------------|----------------------------------------------------------------------------------------------------------|
| class, object                              | table; the value at the top of a document must be a class or a map                                       |
| `Map<String, T>`, `Map<Enum, T>`           | table, an enum key written as its name; any other key type is `NonStringMapKey`                          |
| nullable type, `T?`                        | refused with `NullableType`, writing and reading; see [Nullable types](#nullable-types)                  |
| missing key                                | the field's default, and `MissingKey` for a field without one                                            |
| list of scalars, list of lists             | inline array; `encodeInto` merges into the document's list element by element                            |
| list of classes or maps                    | array of tables, or inline array of inline tables with `InlineArray`; always inline inside an inline value; reading takes both; `encodeInto` keeps the form the document has |
| enum                                       | string holding its serial name (`@SerialName` honoured), read case-sensitively                           |
| sealed or polymorphic class                | table with the subclass's serial name under `classDiscriminator`                                         |
| `Pair`, `Triple`, other classes            | as their serializers say: `first`, `second`, …                                                           |
| `Byte`, `Short`, `Int`, `Long`             | integer; reading checks the range (`OutOfRange`)                                                         |
| `UByte`, `UShort`, `UInt`, `ULong`         | integer holding the unsigned value; a `ULong` above `Long.MAX_VALUE` is `OutOfRange` when writing        |
| `Float`, `Double`                          | float, `nan` and `inf` included; reading also takes an integer                                           |
| `Char`                                     | one-character string                                                                                     |
| `String`                                   | string; reading a native date-time gives its TOML text                                                   |
| `kotlin.time.Duration`                     | string in Kotlin's form, `"1m 30s"`; reading also takes ISO-8601, `"PT1M30S"`                            |
| `kotlinx.datetime.LocalDate`               | local date, `1979-05-27`                                                                                 |
| `kotlinx.datetime.LocalTime`               | local time, `07:32:00`; reading `07:32` gives seconds 0                                                  |
| `kotlinx.datetime.LocalDateTime`           | local date-time, `1979-05-27T07:32:00`                                                                   |
| `kotlin.time.Instant`                      | offset date-time in UTC, `1979-05-27T07:32:00Z`; reading takes any offset                                |
| `kotlinx.datetime.UtcOffset` and others    | string, as their serializers write it                                                                    |

The date-time types read a quoted string too, such as `"1979-05-27"`. A date-time of the wrong kind is
`DatetimeKindMismatch`, and a leap second (`23:59:60`), which kotlinx-datetime cannot hold, is `LeapSecond`. A
date-time, `Duration` or unsigned type is recognised by its default serializer; a field with another serializer,
such as `LocalDateIso8601Serializer`, is written as that serializer says. A map key is always a string, so a
`LocalDate` key is written as its text.

## Nullable types

TOML has no null. A key is there with a value, or it is missing, and a missing key means the field's default. So
kotoml refuses every nullable type in your classes, wherever it sits: a field such as `String?` (with a default or
without one), a list element such as `List<Int?>`, a map value such as `Map<String, Int?>`, or a nested class such
as `Database?`. It looks at the types before writing or reading anything, whatever `encodeDefaults` says, and
returns `TomlEncodeError.NullableType` or `TomlDecodeError.NullableType` with the path of the first nullable type.
`[]` in that path stands for the elements of a list or the values of a map: `owner`, `database.pool.idle`,
`ports[]`, `limits[]`, `listeners[].name`. `encodeInto` leaves the document as it was.

```kotlin
@Serializable
data class Account(val name: String, val owner: String? = null)

Toml.encodeToString(Account("n"))                 // Result.Error(TomlEncodeError.NullableType("owner"))
Toml.decodeFromString<Account>("name = \"n\"\n") // Result.Error(TomlDecodeError.NullableType("owner"))
```

Model a value that may be left out with a non-null type instead:

- a default value, such as `val port: Int = 8080`, which a file may leave out;
- an empty collection or string, such as `val tags: List<String> = emptyList()` or `val owner: String = ""`;
- an explicit flag, or a sealed type with a case for "not set", when "not set" must differ from every value.

A section a file may leave out, as a class with a default and a flag that says whether it is in use:

```kotlin
@Serializable
data class Proxy(val enabled: Boolean = false, val host: String = "", val port: Int = 3128)

@Serializable
data class Settings(val name: String, val proxy: Proxy = Proxy())

Toml.decodeFromString<Settings>("name = \"app\"\n").getOrNull()?.proxy?.enabled // false
Toml.encodeToString(Settings("app")).getOrNull()
// name = "app"
Toml.encodeToString(Settings("app", Proxy(enabled = true, host = "proxy.local"))).getOrNull()
// name = "app"
//
// [proxy]
// enabled = true
// host = "proxy.local"
```

kotoml sees every type your classes name, the subclasses of a sealed type, and the subclasses and contextual
serializers registered in `serializersModule`. Some types are known only from a value: a subclass that only a
default polymorphic serializer picks, or a contextual serializer made from type arguments. A nullable type there is
refused, with the same `NullableType`, when a value of it is written or read, and nothing is written then either. A
serializer of your own that writes a null, or asks for one, gets `NullValue`.

## Handle errors

Every error type is `@Serializable`, so a program can pass it on as JSON:

```kotlin
Json.encodeToString(TomlDecodeError.serializer(), Toml.decodeFromString<Config>("port = 1\n").errorOrNull()!!)
// {"type":"MissingKey","path":"hostname"}
```

A `path` is the dotted key path with array indexes, keys spelled as TOML quotes them: `servers[1].port`,
`ports."a.b"`. The top of the document is the empty path. A custom serializer that throws becomes
`TomlDecodeError.Serializer` or `TomlEncodeError.Serializer`, carrying its message as `detail`; no call throws.
