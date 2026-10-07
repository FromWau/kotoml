package com.fromwau.kotoml.app

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.defaultTerminal
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.CommandBuilder
import com.fromwau.klap.Invocation
import com.fromwau.klap.USAGE_ERROR_EXIT
import com.fromwau.klap.cli
import com.fromwau.klap.parse
import com.fromwau.klap.run
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.TomlVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private fun CommandBuilder.specOption() = option("--toml", help = "the TOML version to accept")
    .choice("1.0", "1.1")
    .default("1.1")

private fun tomlVersion(spec: String): TomlVersion = if (spec == "1.0") TomlVersion.V1_0 else TomlVersion.V1_1

private fun CommandBuilder.fileArgument(help: String = "the TOML file") =
    argument("file", "$help; standard input when absent or `-`")
        .file()
        .optional()

/** [AppError] as klap carries it: the sentence for a person, and the error itself for `--json`. */
private fun AppError.toCliError(): CliError = CliError.Domain(
    error = this,
    detail = describe(),
    exitCode = exitCode,
    json = Json.encodeToJsonElement(AppError.serializer(), this),
)

private fun <T> Result<T, AppError>.orCliError(): Result<T, CliError> = mapError { it.toCliError() }

private fun parseToml(path: String?, spec: String): Result<TomlDocument, AppError> {
    val bytes = when (val read = readInput(path)) {
        is Result.Success -> read.value
        is Result.Error -> return read
    }
    return TomlDocument.parse(bytes, tomlVersion(spec))
        .mapError { AppError.InvalidToml(path ?: "-", it, bytes.decodeToString()) }
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

/** What `encode` prints: the document toml-test's tagged JSON in [path] describes. */
private fun encode(path: String?): Result<String, AppError> {
    val name = path ?: "-"
    return when (val read = readInput(path)) {
        // klap ends the text with its own newline.
        is Result.Success -> parseTaggedJson(read.value.decodeToString(), name)
            .flatMap { it.encodedText(name) }
            .map { it.removeSuffix("\n") }
        is Result.Error -> read
    }
}

/** Checks each of [paths] as TOML of [spec]; [errors], when given, gets a person's line for each that is not. */
private fun check(
    paths: List<String>,
    spec: String,
    errors: Terminal?,
): CheckReport {
    val results = paths.map { path ->
        val error = (parseToml(path, spec) as? Result.Error)?.error
        if (errors != null && error != null) errors.err("error: ${error.describe().escapingControls()}\n")
        CheckedFile(path, valid = error == null, error = error)
    }
    return CheckReport(results)
}

// What klap does to an error's detail: a control character from the input could rewrite the terminal.
private fun String.escapingControls(): String = buildString {
    for (char in this@escapingControls) {
        val code = char.code
        val control = code in 0x00..0x1F || code == 0x7F
        if (control && char != '\n' && char != '\t') {
            append("\\x")
            append(code.toString(16).padStart(2, '0').uppercase())
        } else {
            append(char)
        }
    }
}

fun main(args: Array<String>) {
    exitWith(runKotoml(args.toList(), defaultTerminal()))
}

/** Ends the process with [status]. */
internal expect fun exitWith(status: Int): Nothing

private val HELP_FLAGS = setOf("--help", "-h", "--help-all")

/**
 * Runs `kotoml` with [args], writing to [terminal], and returns the exit status. Without a command, the help goes
 * to standard error and the run is a usage error.
 */
internal fun runKotoml(
    args: List<String>,
    terminal: Terminal,
): Int {
    val cli = kotomlCli(terminal)
    val showsHelp = (cli.parse(args) as? Result.Success)?.value is Invocation.ShowHelp
    if (!showsHelp || args.any { it in HELP_FLAGS }) return cli.run(args, terminal)
    cli.run(listOf("--help"), StandardErrorOnly(terminal))
    return USAGE_ERROR_EXIT
}

/** [terminal] with standard output sent to its standard error. */
private class StandardErrorOnly(private val terminal: Terminal) : Terminal by terminal {
    override fun out(text: String) = terminal.err(text)
}

/** The `kotoml` command line, built once per run; `check` of several files writes its errors to [terminal]. */
internal fun kotomlCli(terminal: Terminal): Cli = cli("kotoml") {
    description = "Query TOML like jq, keeping every comment and spelling of what it prints"
    version = VERSION
    builtins { color = false }

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

    command("check", "print OK when each file is valid TOML, or print why it is not") {
        example("kotoml check a.toml b.toml", "each file named with OK or its error, exit 1 if any is not TOML")
        val files = argument("file", "the TOML files; standard input when absent or `-`")
            .file()
            .multiple()
        val spec = specOption()
        action(human = { it.humanText() }, exitCode = { if (it.valid) 0 else 1 }) {
            val paths = files().ifEmpty { listOf("-") }
            val single = paths.singleOrNull()
            if (single != null) {
                parseToml(single, spec())
                    .map { CheckReport(listOf(CheckedFile(single, valid = true))) }
                    .orCliError()
            } else {
                Ok(check(paths, spec(), errors = if (json) null else terminal))
            }
        }
    }

    command("decode", "toml-test decoder: TOML in, tagged JSON on standard output") {
        val file = fileArgument()
        val spec = specOption()
        action(human = { Json.encodeToString(JsonElement.serializer(), it) }) {
            parseToml(file(), spec()).orCliError().map { it.toTaggedJson() }
        }
    }

    command("encode", "toml-test encoder: tagged JSON in, TOML on standard output") {
        val file = fileArgument("toml-test's tagged JSON")
        action { encode(file()).orCliError() }
    }
}
