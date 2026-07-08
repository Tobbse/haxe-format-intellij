# Haxe Formatter (hxformat.json) — IntelliJ plugin

Routes IntelliJ's **Reformat Code** action (⌘⌥L) for Haxe (`.hx`) files through the real
[haxe-formatter](https://github.com/HaxeCheckstyle/haxe-formatter) CLI (HaxeCheckstyle,
configured via `hxformat.json`), so IDE formatting is **byte-identical to VS Code and CI**.
It pipes the in-memory editor buffer through `haxelib run formatter --stdin -s <file>` —
no disk I/O, no autosave races. Works with **any** Haxe repository (lix or stock haxelib)
and any git worktree: config, shims and formatter version are discovered by walking up
from the file being formatted. macOS only.

## Requirements

- IntelliJ IDEA 2025.3 – 2026.1 (build `253` – `261.*`)
- The team's [intellij-haxe fork](https://carostobbe.github.io/intellij-haxe/updatePlugins.xml)
  (plugin id `com.intellij.plugins.haxe`) installed — this plugin depends on it
- `haxelib` (and `node` for lix projects) reachable from a **login shell**
  (the IDE captures your login-shell PATH at startup; project-local
  `node_modules/.bin` lix shims are picked up automatically)

## Install

1. **Custom plugin repository (recommended):** Settings → Plugins → ⚙ → Manage Plugin
   Repositories → add `https://carostobbe.github.io/intellij-haxe/updatePlugins.xml`
   (most team members already have it for the Haxe fork). Once this plugin's entry is
   added there (see Publishing), it appears in Marketplace search and auto-updates.
2. **Fallback:** Settings → Plugins → ⚙ → Install Plugin from Disk… with the release
   zip from this repo's GitHub releases.

## Behaviour notes

- **Selection + ⌘⌥L formats the whole file** (the CLI has no range mode; whole-file
  output always matches CI).
- Files matched by `hxformat.json` `"excludes"` — and configs with
  `"disableFormatting": true` — are **silent no-ops**.
- A formatter failure (e.g. syntax error) shows a balloon notification and **never
  touches the buffer**.
- `// @formatter:off` / `// @formatter:on` regions are honoured (markers must have no
  trailing whitespace).
- Live type-and-indent stays with the IDE's built-in engine (snappy, local); the file
  converges to `hxformat.json` form on the next explicit ⌘⌥L (and in CI).
- Formatting **never runs on typing or autosave** — only on the deliberate Reformat
  Code action. Leave Settings → Tools → Actions on Save → "Reformat code" **off**.
- Project-view directory reformat spawns one formatter process per file, sequentially —
  acceptable for an explicit action, but expect it to take a while on big trees.
- A brand-new, never-saved file cannot be formatted (the formatter requires the `-s`
  path to exist on disk); you get a notification and no change.
- No `hxformat.json` above the file? The formatter falls back to its default config —
  same as VS Code.

## Publishing a release (maintainers)

1. Tag `vX.Y.Z` and push — CI (`.github/workflows/build.yml`) builds the plugin,
   runs the tests and attaches `haxe-format-intellij-X.Y.Z.zip` to the GitHub release.
2. **Manual step, outside this repo:** add/update this plugin's `<plugin>` entry in the
   `carostobbe.github.io/intellij-haxe` repository's `updatePlugins.xml` (one custom
   repository URL serves both plugins):

   ```xml
   <plugin id="com.innogames.haxeformatter"
           url="https://github.com/<org>/haxe-format-intellij/releases/download/vX.Y.Z/haxe-format-intellij-X.Y.Z.zip"
           version="X.Y.Z">
       <idea-version since-build="253" until-build="261.*"/>
   </plugin>
   ```

   Keep `since-build`/`until-build` in lockstep with the intellij-haxe fork's range
   whenever either is bumped.

## Migration (FOE monorepo — applies outside this repo)

This plugin replaces the old "haxe format on save" **File Watcher** (which wrote to
disk and raced the editor buffer under autosave):

- **Remove** the "haxe format on save" File Watcher entry: delete/prune
  `.idea/watcherTasks.xml` in the FOE project **and in every worktree**; also remove it
  from any shared/template `.idea` config or setup automation that seeds it.
- **Remove** any setup-flow/onboarding step that installs or configures that watcher.
- **Keep** `scripts/frontend/format-haxe-on-save.sh` only if pre-commit/CI/docs still
  call it; the plugin does not use it.
- **Add** to onboarding: the plugin arrives via the already-configured custom plugin
  repository (or manual zip install as fallback).
- Optionally disable the bundled File Watchers plugin if nothing else uses it.

## Development

```bash
./gradlew test              # unit + platform tests (fake executor; no Haxe toolchain needed)
./gradlew integrationTest   # opt-in: real formatter; macOS + node + lix — run
                            #   src/integrationTest/fixtures/setup.sh   once first
./gradlew buildPlugin       # zip in build/distributions/
./gradlew runIde            # sandbox IDE; drop the Haxe fork zip into
                            #   libs/intellij-haxe-fork.zip first so the dependency resolves
```

Architecture (see `docs/plans/2026-07-08-haxe-formatter-plugin-design.md` for the full
design): `HaxeExternalFormattingService` (an `AsyncDocumentFormattingService`) snapshots
the document text on the EDT, then on a background thread `FormatterContextResolver`
walks up to the nearest `hxformat.json` (cached per directory), `FormatterCommandBuilder`
builds `haxelib run formatter --stdin -s <path>` with `node_modules/.bin` prepended to
the login-shell PATH, `CliFormatterExecutor` runs it with deadlock-free pipes, and
`ResultPolicy` maps exit codes: apply on 0 (with the CLI's one trailing `Sys.println`
newline stripped), silent no-op on 1, error balloon (buffer untouched) otherwise.
