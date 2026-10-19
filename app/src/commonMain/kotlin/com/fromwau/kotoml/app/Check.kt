package com.fromwau.kotoml.app

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** What `check --json` prints for a file it was given alone. */
@Serializable
internal data class Checked(val valid: Boolean)

/** A file `check` was given; [error] is why it is not TOML, when it is not. */
@Serializable
internal data class CheckedFile(
    val path: String,
    val valid: Boolean,
    val error: AppError? = null,
)

/**
 * What `check` found in each of its [files]. `--json` prints a file given alone as [Checked], which leaves out its
 * error, and several as an array of [CheckedFile].
 */
@Serializable(with = CheckReportSerializer::class)
internal data class CheckReport(val files: List<CheckedFile>) {
    val valid: Boolean get() = files.all { it.valid }
}

internal object CheckReportSerializer : KSerializer<CheckReport> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: CheckReport) {
        val single = value.files.singleOrNull()
        val json = if (single != null) {
            Json.encodeToJsonElement(Checked.serializer(), Checked(single.valid))
        } else {
            Json.encodeToJsonElement(ListSerializer(CheckedFile.serializer()), value.files)
        }
        encoder.encodeSerializableValue(JsonElement.serializer(), json)
    }

    // klap only writes a command's result, so a report is never read back.
    override fun deserialize(decoder: Decoder): CheckReport = throw SerializationException("a report is only written")
}
