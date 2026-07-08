# Integration-test fixtures

A minimal Haxe project used by the opt-in integration tests
(`./gradlew integrationTest`, package `com.innogames.haxeformatter.it`) that run the
**real** haxe-formatter CLI. The unit/platform test suite (`./gradlew test`) never
touches these.

## Requirements

- macOS
- node >= 18 and npm on PATH
- network access (first run downloads lix, Haxe and the formatter)

## Setup (once per machine)

```bash
src/integrationTest/fixtures/setup.sh
```

This installs, **inside `project/` and gitignored**:

- `node_modules/` with [lix](https://github.com/lix-pm/lix.client) — its
  `node_modules/.bin/haxelib` is the haxeshim the plugin discovers via
  `node_modules/.bin` PATH-prepending;
- `.haxerc` + `haxe_libraries/` pinning Haxe 4.3.6 and `formatter` 1.18.0.

Alternative environment: stock haxelib works too — `brew install haxe && haxelib
install formatter 1.18.0`. The tests only need `haxelib run formatter` resolvable
through the same environment the plugin would build (they prepend
`project/node_modules/.bin` to PATH when it exists).

## Contents

| Path | Purpose |
|---|---|
| `project/hxformat.json` | Non-default config (4-space indent, `leftCurly: both`) + `excludes: ["src/excluded/.*"]` |
| `project/src/Unformatted.hx` | Deliberately mis-formatted → golden-compare |
| `project/src/excluded/ExcludedFile.hx` | Matches `excludes` → CLI exit 1 (silent no-op path) |
| `project/src/FormatterOff.hx` | `// @formatter:off` region must survive byte-identically |
| `project/src/SyntaxError.hx` | Must make the formatter exit 2 |
| `golden/*.golden` | Raw CLI stdout (INCLUDING the trailing `Sys.println` newline) — tests normalise before comparing where relevant |

## Regenerating goldens

```bash
cd src/integrationTest/fixtures/project
cat src/Unformatted.hx  | ./node_modules/.bin/haxelib run formatter --stdin -s "$(pwd)/src/Unformatted.hx"  > ../golden/Unformatted.hx.golden
cat src/FormatterOff.hx | ./node_modules/.bin/haxelib run formatter --stdin -s "$(pwd)/src/FormatterOff.hx" > ../golden/FormatterOff.hx.golden
```

Eyeball the results (4-space indent, spaces after commas; matrix region untouched)
and cross-check against the VS Code/CI toolchain output on the same input.

## Neko vs Node (informational)

`haxelib run formatter` launches Neko `run.n`, which re-executes as `node run.js`
when node is on PATH. To exercise the pure-Neko path, temporarily remove node from
PATH and re-run the suite manually — optional; the trailing-newline rule is pinned by
`TrailingNewlineIT` under whichever runtime is active.
