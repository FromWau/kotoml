package com.fromwau.kotoml.app

import com.fromwau.kern.fs.FileType
import com.fromwau.kern.fs.expandTilde
import com.fromwau.kern.fs.fileType
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.defaultTerminal
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Builtin
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.CommandBuilder
import com.fromwau.klap.Help
import com.fromwau.klap.Version
import com.fromwau.klap.cli
import com.fromwau.klap.run
import com.fromwau.kotoml.TomlDocument
import com.fromwau.kotoml.TomlVersion
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private fun CommandBuilder.specOption() = option(long = "toml", help = "the TOML version to accept")
    .choice("1.0", "1.1")
    .default("1.1")

/**
 * This path with a leading `~` resolved to the home directory [home] finds, or null when there is none to find. A
 * shell does that before a run only where `~` starts an unquoted word, so `--file=~/x` and `-f '~/x'` reach kotoml
 * as typed, and so does every path a completion is handed. `~bob` names another user's home and is left as typed.
 */
private fun String.withHome(home: () -> Path?): String? {
    val atHome = this == "~" || startsWith("~/") || startsWith("~\\")
    return if (atHome) home()?.let { Path(this).expandTilde(it).toString() } else this
}

/** How every file argument and option reads its value, for its help. */
private const val READ_FROM = "standard input when absent or `-`, and a leading ~ is the home directory"

private fun tomlVersion(spec: String): TomlVersion = if (spec == "1.0") TomlVersion.V1_0 else TomlVersion.V1_1

private fun CommandBuilder.fileArgument(help: String = "the TOML file") =
    argument("file", "$help; $READ_FROM")
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

/** Every byte of the file at [path], a `~` it starts with resolved through [home], or of standard input. */
private fun readFile(
    path: String?,
    home: () -> Path?,
): Result<ByteArray, AppError.ReadFailed> {
    val resolved = path?.let { it.withHome(home) ?: return Err(AppError.ReadFailed(it, ReadFailure.HomeUnknown)) }
    // Named as typed, as the rest of a run's output names it.
    return readInput(resolved).mapError { failed -> path?.let { failed.copy(path = it) } ?: failed }
}

private fun parseToml(
    path: String?,
    spec: String,
    home: () -> Path?,
): Result<TomlDocument, AppError> {
    val bytes = when (val read = readFile(path, home)) {
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
    home: () -> Path?,
): Result<Selection, AppError> {
    val parsedFilter = when (val parsed = parseFilter(filter)) {
        is Result.Success -> parsed.value
        is Result.Error -> return parsed
    }
    val document = when (val parsed = parseToml(path, spec, home)) {
        is Result.Success -> parsed.value
        is Result.Error -> return parsed
    }
    return parsedFilter.select(document)
}

/** What `encode` prints: the document toml-test's tagged JSON in [path] describes. */
private fun encode(
    path: String?,
    home: () -> Path?,
): Result<String, AppError> {
    val name = path ?: "-"
    return when (val read = readFile(path, home)) {
        // klap ends the text with its own newline.
        is Result.Success -> parseTaggedJson(read.value.decodeToString(), name)
            .flatMap { it.encodedText(name) }
            .map { it.removeSuffix("\n") }
        is Result.Error -> read
    }
}

/**
 * Checks each of [paths] as TOML of [spec]. [terminal], when given, gets a person's line for each file as it is
 * checked, so the lines keep the order the files were given in although OK and errors go to different streams.
 */
private fun check(
    paths: List<String>,
    spec: String,
    terminal: Terminal?,
    home: () -> Path?,
): CheckReport {
    val results = paths.map { path ->
        val error = (parseToml(path, spec, home) as? Result.Error)?.error
        when {
            terminal == null -> Unit
            error == null -> terminal.out("${inputName(path)}: OK\n")
            else -> terminal.err("error: ${error.describe().escapingControls()}\n")
        }
        CheckedFile(path, valid = error == null, error = error)
    }
    return CheckReport(results)
}

fun main(args: Array<String>) {
    exitWith(runKotoml(args.toList(), defaultTerminal()))
}

/** Ends the process with [status]. */
internal expect fun exitWith(status: Int): Nothing

/**
 * Runs `kotoml` with [args], writing to [terminal], and returns the exit status; [home] finds where `~` points, asked
 * only for a path that starts with one.
 */
internal fun runKotoml(
    args: List<String>,
    terminal: Terminal,
    home: () -> Path? = ::homeDirectory,
): Int {
    val homeOnce by lazy(home)
    return kotomlCli(terminal) { homeOnce }.run(args, terminal)
}

/**
 * The `kotoml` command line, built once per run; `check` writes each file's line to [terminal], and a `~` that
 * starts a file path resolves through [home].
 */
internal fun kotomlCli(
    terminal: Terminal,
    home: () -> Path?,
): Cli = cli("kotoml") {
    help = "Query TOML like jq, keeping every comment and spelling of what it prints"
    version = Version.Of(VERSION)
    abbreviation = Abbreviation.None
    builtins {
        help = Help.ByTree(short = 'h')
        color = Builtin.Off
    }

    command("get") {
        help = "print what a jq path selects"
        example("kotoml get -f config.toml '.servers[0].port'", "one value")
        example("kotoml get -f config.toml '.servers[] | .host' --json", "the host of every server, as JSON")
        // An option the user types before the filter, not an operand after it: fish gives a completion the line
        // only up to the cursor, so the filter's keys complete only from a file named first.
        val file = option(short = 'f', long = "file", help = "the TOML file; $READ_FROM")
            .placeholder("file")
            .file()
        val spec = specOption()
        val filter = argument("filter", "a jq path: ., .a.b, .\"key\", .[0], .[] and | between them")
            .completeWith {
                // Standard input is the data a later run will read, not one Tab press may consume.
                val path = file()?.takeUnless { it == "-" }?.withHome(home) ?: return@completeWith
                // Only a file: a pipe or a device would block the Tab press, or use up what a run should read.
                if (Path(path).fileType() != Ok(FileType.Regular)) return@completeWith
                val document = (parseToml(path, spec(), home) as? Result.Success)?.value ?: return@completeWith
                filterCompletions(document, current).forEach { candidate(it.filter, it.type) }
            }
        val raw = flag(short = 'r', long = "raw", help = "print strings without quotes, and TOML without comments")
        action(human = { it.humanText(raw()) }) {
            get(filter(), file(), spec(), home).orCliError()
        }
    }

    command("check") {
        help = "print each file with OK when it is valid TOML, or print why it is not"
        example("kotoml check a.toml b.toml", "each file named with OK or its error, exit 1 if any is not TOML")
        val files = argument("file", "the TOML files; $READ_FROM")
            .file()
            .multiple()
        val spec = specOption()
        // Each file's line is printed as it is checked, so nothing is left to print at the end.
        action(human = { "" }, exitCode = { if (it.valid) 0 else 1 }) {
            val paths = files().ifEmpty { listOf("-") }
            val single = paths.singleOrNull()
            if (json && single != null) {
                parseToml(single, spec(), home)
                    .map { CheckReport(listOf(CheckedFile(single, valid = true))) }
                    .orCliError()
            } else {
                Ok(check(paths, spec(), terminal = if (json) null else terminal, home = home))
            }
        }
    }

    command("decode") {
        help = "toml-test decoder: TOML in, tagged JSON on standard output"
        val file = fileArgument()
        val spec = specOption()
        action(human = { Json.encodeToString(JsonElement.serializer(), it) }) {
            parseToml(file(), spec(), home).orCliError().map { it.toTaggedJson() }
        }
    }

    command("encode") {
        help = "toml-test encoder: tagged JSON in, TOML on standard output"
        val file = fileArgument("toml-test's tagged JSON")
        action { encode(file(), home).orCliError() }
    }
}
