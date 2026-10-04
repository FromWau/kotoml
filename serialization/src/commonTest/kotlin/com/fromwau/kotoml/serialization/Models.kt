package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Result
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.serializers.LocalDateIso8601Serializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Serializable
enum class Mode {
    TCP,

    @SerialName("udp")
    UDP,
}

@Serializable
data class Config(
    @SerialName("hostname") val host: String,
    val port: Int,
    val timeout: Duration = 15.seconds,
    val mode: Mode = Mode.TCP,
    val knownServers: List<Pair<String, Int>> = emptyList(),
)

/** The owner's model without its list, for what `encodeDefaults` writes. */
@Serializable
data class Endpoint(
    @SerialName("hostname") val host: String,
    val port: Int,
    val timeout: Duration = 15.seconds,
    val mode: Mode = Mode.TCP,
)

@Serializable
data class Server(
    val host: String,
    val port: Int = 8080,
)

@Serializable
data class Fleet(
    val name: String,
    val servers: List<Server> = emptyList(),
)

@Serializable
data class Database(
    val url: String,
    val pool: Pool = Pool(),
)

@Serializable
data class Pool(
    val size: Int = 4,
    val idle: Duration = Duration.ZERO,
)

@Serializable
data class App(
    val title: String,
    val owner: String = "",
    val database: Database,
    val servers: List<Server> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
sealed interface Shape

@Serializable
@SerialName("circle")
data class Circle(val radius: Double) : Shape

@Serializable
@SerialName("square")
data class Square(val side: Double) : Shape

@Serializable
@SerialName("point")
data object Point : Shape

@Serializable
data class Drawing(val shapes: List<Shape>)

@Serializable
data class Holder(val shape: Shape)

@Serializable
data class Numbers(
    val byte: Byte = 0,
    val short: Short = 0,
    val int: Int = 0,
    val long: Long = 0,
    val float: Float = 0f,
    val double: Double = 0.0,
    val char: Char = 'a',
)

@Serializable
data class Unsigned(
    val byte: UByte = 0u,
    val int: UInt = 0u,
    val long: ULong = 0u,
)

@Serializable
data class Times(
    val date: LocalDate = LocalDate(1970, 1, 1),
    val time: LocalTime = LocalTime(0, 0),
    val local: LocalDateTime = LocalDateTime(1970, 1, 1, 0, 0),
    val instant: Instant = Instant.fromEpochSeconds(0),
)

@Serializable
data class IsoDate(
    @Serializable(with = LocalDateIso8601Serializer::class) val date: LocalDate,
)

@Serializable
data class Text(val text: String)

@Serializable
data class Timeout(val timeout: Duration)

@Serializable
data class Grid(val rows: List<List<Int>>)

@Serializable
data class Groups(val groups: List<Fleet>)

@Serializable
data class Matrix(val cells: List<List<Server>>)

@Serializable
data class Ports(val ports: Map<String, Int>)

@Serializable
data class ModePorts(val ports: Map<Mode, Int>)

@Serializable
data class NumberedPorts(val ports: Map<Int, String>)

/** A serializer that fails the way a hand-written one might, with an exception of its own. */
object RefusingSerializer : KSerializer<Server> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Refusing")

    override fun serialize(
        encoder: Encoder,
        value: Server,
    ): Unit = throw IllegalStateException("refused to write")

    override fun deserialize(decoder: Decoder): Server = throw IllegalStateException("refused to read")
}

/** The success value, failing the test with the error otherwise. */
internal fun <S, E : IError> Result<S, E>.getOrFail(): S = when (this) {
    is Result.Success -> value
    is Result.Error -> fail("expected success, got error:\n$error")
}

/** The error, failing the test on success. */
internal fun <S, E : IError> Result<S, E>.errorOrFail(): E = when (this) {
    is Result.Success -> fail("expected an error, got success: $value")
    is Result.Error -> error
}
