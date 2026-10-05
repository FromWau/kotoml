package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.CommandBuilder
import com.fromwau.klap.cli
import com.fromwau.klap.main
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.TomlVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private fun CommandBuilder.specOption() = option("--toml", help = "the TOML version to accept")
    .choice("1.0", "1.1")
    .default("1.1")

private fun tomlVersion(spec: String): TomlVersion = if (spec == "1.0") TomlVersion.V1_0 else TomlVersion.V1_1

private fun CommandBuilder.fileArgument() = argument("file", "the TOML file; standard input when absent or `-`")
    .file()
    .optional()

/** [AppError] as klap carries it: the sentence for a person, and the error itself for `--json`. */
private fun <T> Result<T, AppError>.orCliError(): Result<T, CliError> = mapError { error ->
    CliError.Domain(
        error = error,
        detail = error.describe(),
        exitCode = error.exitCode,
        json = Json.encodeToJsonElement(AppError.serializer(), error),
    )
}

private fun parseToml(path: String?, spec: String): Result<TomlDocument, AppError> {
    val bytes = when (val read = readInput(path)) {
        is Result.Success -> read.value
        is Result.Error -> return read
    }
    return TomlDocument.parse(bytes, tomlVersion(spec)).mapError { AppError.InvalidToml(it, bytes.decodeToString()) }
}

/** What `get` selects: [filter] run on the TOML in [path]. */
private fun get(
    filter: String,
    path: String?,
    spec: String,
): Result<Selection, AppError> {
    val parsedFilter = when (val parsed = parseFilter(filter)) {
        is Result.Success -> parsed.value
        is Result.Error -> return parsed
    }
    val document = when (val parsed = parseToml(path, spec)) {
        is Result.Success -> parsed.value
        is Result.Error -> return parsed
    }
    return parsedFilter.select(document)
}

/** What `check --json` prints for a valid file. */
@Serializable
internal data class Checked(val valid: Boolean)

/** What `encode` prints: the document toml-test's tagged JSON on standard input describes. */
private fun encode(): Result<String, AppError> = when (val read = readInput(null)) {
    is Result.Success -> parseTaggedJson(read.value.decodeToString()).flatMap { it.encodedText() }
    is Result.Error -> read
}

fun main(args: Array<String>) {
    kotomlCli().main(args)
}

/** The `kotoml` command line, built once per run. */
internal fun kotomlCli(): Cli = cli("kotoml") {
    description = "Query TOML like jq, keeping every comment and spelling of what it prints"
    version = VERSION

    command("get", "print what a jq path selects") {
        example("kotoml get '.servers[0].port' config.toml", "one value")
        example("kotoml get '.servers[] | .host' --json config.toml", "the host of every server, as JSON")
        val filter = argument("filter", "a jq path: ., .a.b, .\"key\", .[0], .[] and | between them")
        val file = fileArgument()
        val raw = flag("--raw", "-r", help = "print strings without quotes")
        val spec = specOption()
        action(human = { it.humanText(raw()) }) {
            get(filter(), file(), spec()).orCliError()
        }
    }

    command("check", "print OK when the file is valid TOML, or print why it is not") {
        val file = fileArgument()
        val spec = specOption()
        action(human = { "OK" }) { parseToml(file(), spec()).orCliError().map { Checked(valid = true) } }
    }

    command("decode", "toml-test decoder: TOML on standard input, tagged JSON on standard output") {
        val spec = specOption()
        action(human = { Json.encodeToString(JsonElement.serializer(), it) }) {
            parseToml(null, spec()).orCliError().map { it.toTaggedJson() }
        }
    }

    command("encode", "toml-test encoder: tagged JSON on standard input, TOML on standard output") {
        action { encode().orCliError() }
    }
}
