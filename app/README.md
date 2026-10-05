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
  is printed keeps the file's comments, spacing and line endings:
  - a table prints as its key/value lines, without its own header;
  - an array of tables prints as written, each element under its `[[name]]` header;
  - `.[]` over tables prints one after another, separated by a blank line;
  - a value prints as TOML, and `-r` prints strings without quotes;
  - a key or index that is not there is an error, such as `no key "nope" at .nope`.
- `check [file]` prints `OK` and exits 0 when the file is valid TOML, and otherwise prints why it is not,
  pointing at the line.
- `decode` and `encode` speak [toml-test](https://github.com/toml-lang/toml-test)'s protocol: TOML in and
  tagged JSON out, or the reverse, on standard input and output. `encode` refuses JSON nested deeper than the TOML
  kotoml reads.

Without a file, or with `-`, input is read from standard input. `--toml 1.0` refuses what TOML 1.1 added;
the default is 1.1. `kotoml completion <shell>` and `kotoml docs man` print a completion script and a man page.

`--json` is for programs: every command prints data and no sentence. `get` prints the selected value, or an
array of them when the filter iterates with `.[]`, and `check` prints `{"valid":true}`. A failure prints the
error itself on standard error, its kind under `type`:

```console
$ printf 'a = 1\na = 2\n' | kotoml check --json
{"error":{"type":"InvalidToml","error":{"reason":{"type":"DuplicateKey","key":"a"},"span":{"start":6,"end":7},"position":{"line":2,"column":1},"context":{"start":0,"end":1}}},"code":1}
```

Exit codes: 0 on success; 1 when the input cannot be read, is not TOML or tagged JSON, or has nothing where
the filter looks; 2 for a usage error, such as a filter that does not parse or an unknown option.

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
