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
   Repositories → add `https://OWNER.github.io/haxe-format-intellij/updatePlugins.xml`
   (published by this repo's CI on every release). The plugin then appears in
   Marketplace search and auto-updates. Team setup automation can seed this URL into
   `IntelliJIdea*/options/updates.xml` (`pluginHosts`) and prompt the install per
   project via `.idea/externalDependencies.xml` (Required Plugins).
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

1. Tag `vX.Y.Z` and push — CI (`.github/workflows/build.yml`) builds the plugin, runs
   the tests, attaches `haxe-format-intellij-X.Y.Z.zip` to the GitHub release, **and
   redeploys `updatePlugins.xml` to this repo's GitHub Pages** pointing at that release.
   Nothing else to do — IDEs with the repository URL configured pick up the update.
   (`workflow_dispatch` on the Build workflow re-publishes the XML without a new tag.)

   One-time repo setting: Settings → Pages → Source: **GitHub Actions**.

2. *(Optional alternative)* serve both plugins from the intellij-haxe fork's single
   repository URL instead — see
   [docs/publishing-updatePlugins.md](docs/publishing-updatePlugins.md).

## Migrating from a format-on-save File Watcher

If your project previously ran the formatter through an IDE **File Watcher** (which
writes to disk and races the editor buffer under autosave — the problem this plugin
exists to fix):

- **Remove** the File Watcher entry: delete/prune `.idea/watcherTasks.xml` in the
  project **and in every worktree**; also remove it from any shared/template `.idea`
  config or setup automation that seeds it.
- **Remove** any setup-flow/onboarding step that installs or configures that watcher.
- **Keep** any formatter wrapper script only if pre-commit/CI/docs still call it; the
  plugin does not use one.
- **Add** to onboarding: the plugin arrives via the custom plugin repository (or
  manual zip install as fallback).
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
