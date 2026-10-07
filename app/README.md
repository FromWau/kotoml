# kotoml command line

Query TOML files like jq. What `kotoml` prints keeps the comments, spacing and spelling of the file it came from,
because it reads through [`:core`](../core/README.md), a format-preserving TOML parser.

```console
$ kotoml get '.servers[0].host' config.toml
"alpha"
$ kotoml get -r '.servers[0].host' config.toml
alpha
$ kotoml get '.servers[] | .host' config.toml
"alpha"
"beta"
$ kotoml get '.servers[]' config.toml
host = "alpha"  # primary
port = 8080

host = "beta"
port = 8081
$ kotoml get --json '.servers[0]' config.toml
{"host":"alpha","port":8080}
$ kotoml get --json '.servers[] | .port' config.toml
[8080,8081]
$ cat config.toml | kotoml get '.servers[1]'
host = "beta"
port = 8081
```

## Commands

- `get <filter> [file]` prints what a jq path selects: `.`, `.a.b`, `."quoted key"`, `.["quoted key"]`, `.[0]`,
  `.[-1]`, `.[]`, and `|` between them. A quoted key takes jq's escapes, such as `\t`, `\n` and `\u00e9`. What
  is printed keeps the file's comments, spacing and spelling:
  - a table prints as its key/value lines, without its own header, each line ending as it does in the file;
  - an array of tables prints as written, each element under its `[[name]]` header;
  - a header inside a selected table is written from that table, while a selected array of tables keeps its whole
    header path:
    ```console
    $ cat nested.toml
    [a]
    x = 1
    [[a.srv]]
    h = 1
    [a.c]
    d = 2
    $ kotoml get .a nested.toml
    x = 1
    [[srv]]
    h = 1
    [c]
    d = 2
    $ kotoml get .a.srv nested.toml
    [[a.srv]]
    h = 1
    ```
  - `.[]` over tables prints one after another, separated by a blank line;
  - a value prints as TOML on a line ending in `\n`, also from a file with CRLF line endings, and `-r` prints
    strings without quotes;
  - a key or index that is not there is an error, such as `no key "nope" at .nope`, and exits 3.
- `check [file...]` prints `OK` and exits 0 when the file is valid TOML, and otherwise prints why it is not,
  pointing at the line. Given several files, it prints `<file>: OK` for each valid one and an error for each
  other, and exits 1 when any is not TOML.
- `decode [file]` and `encode [file]` speak [toml-test](https://github.com/toml-lang/toml-test)'s protocol: TOML
  in and tagged JSON out, or the reverse. `encode` refuses JSON nested deeper than the TOML kotoml reads.

Without a file, or with `-`, input is read from standard input. `--toml 1.0` refuses what TOML 1.1 added;
the default is 1.1. `kotoml completion <shell>` and `kotoml docs man` print a completion script and a man page.
`kotoml` without a command prints its help on standard error and exits 2; `kotoml --help` prints it on standard
output.

An error starts with the file it is in, or `standard input`:

```console
$ kotoml check config.toml
error: config.toml: TOML parse error at line 2, column 1
  |
2 | a = 2
  | ^
duplicate key `a`
```

`--json` is for programs: every command prints data and no sentence. `get` prints the selected value, or an
array of them when the filter iterates with `.[]`. `check` prints `{"valid":true}` for one file, and for several
an array of `{"path":…,"valid":…}`, with the `error` of each file that is not TOML. A failure prints the error
itself on standard error, its kind under `type`, and an error in an input names it as `path`, `-` for standard
input:

```console
$ printf 'a = 1\na = 2\n' | kotoml check --json
{"error":{"type":"InvalidToml","path":"-","error":{"reason":{"type":"DuplicateKey","keyPath":["a"]},"span":{"start":6,"end":7},"position":{"line":2,"column":1},"context":{"start":0,"end":1}}},"code":1}
```

`get --json` turns TOML into JSON this way:

| TOML                   | JSON                                                                                    |
|------------------------|-----------------------------------------------------------------------------------------|
| string                 | string                                                                                  |
| integer                | number; a reader that holds numbers as doubles, as JavaScript does, loses digits past 2^53 |
| float                  | number; `nan`, `inf` and `-inf`, which JSON has no number for, as the strings `"nan"`, `"inf"` and `"-inf"` |
| boolean                | `true` or `false`                                                                       |
| date-time              | string in RFC 3339 form with seconds, such as `"1979-05-27T07:32:00Z"`; a local date-time, date or time likewise, `"07:32:00"`; the fraction of a second has at most 9 digits, as kotoml reads it, without trailing zeros |
| table, inline table    | object                                                                                  |
| array, array of tables | array                                                                                   |

In an error, a `span` counts UTF-16 units of the text from 0, as a JVM or JavaScript string indexes it, and a
`position` counts lines and characters from 1.

Exit codes: 0 on success; 1 when the input cannot be read or is not TOML or tagged JSON, when a filter step does
not apply to what it meets, such as `.a.b` on a string, or when the output cannot be written; 2 for a usage error,
such as a filter that does not parse, an unknown option or no command; 3 when the filter finds no key or index
where it looks; 141, with no message, when a reader such as `head` closes the pipe early.

## Build and install

```console
$ ./gradlew :app:linkReleaseExecutableLinuxX64
$ app/build/bin/linuxX64/releaseExecutable/kotoml.kexe --help
```

The same task exists for `MingwX64` and `MacosArm64`, and `./gradlew :app:jvmRun --args="get . f.toml"`
runs it on the JVM. On Arch, `makepkg -si` in [`packaging/arch`](packaging/arch/PKGBUILD) builds and installs
the binary with its completions and man page.

## Run the official toml-test suite

`./gradlew check` runs it, as `:app:tomlTest`: it builds toml-test's own runner from the `toml-test` submodule,
links the native binary for this machine, and runs every valid, invalid and encoder test for TOML 1.0 and 1.1.
Any failure fails the build. Building the runner needs [Go](https://go.dev/dl/) on the `PATH`.

Every test passes for both versions.
