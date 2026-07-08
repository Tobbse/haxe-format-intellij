# Haxe Formatter IntelliJ Plugin — Design Spec

**Date:** 2026-07-08
**Status:** Draft for review (research-verified; two decisions assumed while author was away — see §2)
**Repo:** `haxe-format-intellij` (this repository — implementation lives here)

## 1. Goal

An IntelliJ IDEA plugin that makes the built-in **Reformat Code** action (⌘⌥L) format
Haxe (`.hx`) files by piping the **in-memory editor buffer** through the real formatter —
[haxe-formatter](https://github.com/HaxeCheckstyle/haxe-formatter) (HaxeCheckstyle,
configured via `hxformat.json`) — so IntelliJ output is byte-identical to VS Code and CI.

The plugin is **generic**: it must work for any Haxe repository (lix or stock haxelib),
not just the Forge of Empires monorepo. Platform scope: **macOS only** (matching current
tooling); nothing in the design is macOS-specific except the environment-capture layer,
so other platforms remain a possible later extension.

### Hard constraints (a solution violating these is wrong)

- IDE autosave stays **on**; formatting must **never** run during typing or on autosave —
  only on the deliberate Reformat Code action.
- Operates on the **in-memory Document**, never on the file on disk (the prior File
  Watcher approach raced the editor buffer under autosave and corrupted edits).
- Output must exactly match `hxformat.json`. IntelliJ's built-in Code Style → Haxe
  (the Haxe Toolkit plugin's `HaxeFormattingModelBuilder`) is a different rule engine
  and is **not** the source of truth.
- A formatter failure (syntax error, missing tooling) must leave the buffer untouched.

## 2. Decisions

| # | Decision | Status |
|---|----------|--------|
| D1 | Extension point: `AsyncDocumentFormattingService` via `com.intellij.formattingService` | Confirmed by research |
| D2 | Invocation: `--stdin` piped mode; buffer via STDIN, real file path via `-s` | Confirmed by research |
| D3 | Platform scope: macOS only | User-confirmed |
| D4 | Formatter discovery: plugin-native, generic per-project (no dependency on the FOE wrapper script) | User-confirmed ("must work for any Haxe repo") |
| D5 | Distribution: custom plugin repository (`updatePlugins.xml` on GitHub Pages), same model as the team's intellij-haxe fork | User-confirmed |
| D6 | Selection ⌘⌥L: **format the whole file anyway** (ignore the selection) | **ASSUMED** — recommended; alternatives in §5.5 |
| D7 | Supported IDE range: **match the intellij-haxe fork's** since/until build range | **ASSUMED** — read from the fork's `updatePlugins.xml`/`plugin.xml` at implementation time |

## 3. Recommended approach & rationale

Register an **`AsyncDocumentFormattingService`** (EP `com.intellij.formattingService`,
available since 2021.2 / build 212) that:

- `canFormat(PsiFile)` → true only for Haxe files (matched by language name / `.hx`
  extension string, **not** by compiling against Haxe plugin classes — see §5.2);
- `createFormattingTask(AsyncFormattingRequest)` → returns a task that, on a background
  thread, pipes `request.getDocumentText()` into
  `haxelib run formatter --stdin -s <absolute-file-path>` and returns the result via
  `request.onTextReady(...)` / `request.onError(...)`.

Why this is right (all verified against IntelliJ platform source at 2024.3):

- **Precedence is automatic.** `CodeStyleManagerImpl` dispatches every reformat through
  `FormattingServiceUtil.findService`, which picks the first registered service whose
  `canFormat` returns true. The built-in engine's wrapper (`CoreFormattingService`,
  which fronts the Haxe plugin's `HaxeFormattingModelBuilder`) is registered
  `order="last"`, so our service wins for `.hx` files with zero extra configuration.
- **No `LanguageFormattingRestriction`** — and using one would be a bug: the Reformat
  Code action's enablement checks `LanguageFormatting.forContext(file) != null`, which a
  restriction nulls out, greying the menu item (the known pitfall — confirmed in
  `ReformatCodeAction.isActionAvailable`). Because the Haxe plugin still provides a
  `FormattingModelBuilder`, the action stays enabled; execution simply routes to us.
- **Threading/cancellation handled by the platform.** `createFormattingTask` runs on the
  EDT (must be fast — no I/O); `FormattingTask.run()` runs on a pooled background thread
  with a 30 s default timeout. A second reformat of the same document cancels the first
  via `FormattingTask.cancel()` (we destroy the child process there). `onError` shows a
  notification balloon and **never touches the document**.
- **In-memory application.** The platform applies `onTextReady` text on the EDT in a
  write action (undo-transparent command); if the document changed while formatting ran,
  a `DocumentMerger` diff-applies minimally instead of `setText`. No disk I/O anywhere
  in the path — the autosave race is structurally impossible.
- **Never runs on typing/autosave.** The service is invoked only through the reformat
  code path. We do **not** declare `AD_HOC_FORMATTING`, so implicit/auto formatting
  (typing indentation, refactoring cleanup) stays with the built-in engine — which is
  desired: live type-and-indent remains snappy and local (§5.2).

**Rejected: `externalFormatProcessor`.** `ExternalFormatProcessor` is
`@ApiStatus.Experimental`, synchronous on the calling thread (no timeout, no
cancellation), and internally bridged onto the new API anyway. JetBrains' docs
explicitly point CLI integrations at `AsyncDocumentFormattingService`; their own
Prettier plugin uses only the latter.

**Rejected: fixing the File Watcher approach.** Anything that writes the file on disk
re-creates the autosave race by construction.

## 4. Architecture sketch

### Components

```
haxe-format-intellij (this repo)
├── plugin.xml
│     <depends>com.intellij.plugins.haxe</depends>          ← Haxe Toolkit (team's fork keeps this id — verify, §7)
│     <formattingService implementation="...HaxeExternalFormattingService"/>
│     <notificationGroup id="Haxe Formatter"/>
├── HaxeExternalFormattingService  (AsyncDocumentFormattingService)
│     canFormat: language name == "Haxe" (string match; no compile-time Haxe classes)
│     getFeatures: { FORMAT_FRAGMENTS }   ← receive selection requests; format whole file (D6)
│     createFormattingTask: snapshot doc text + file path on EDT, return task
├── FormatterContextResolver
│     From the file's path, walk up parent directories to the nearest hxformat.json
│     → configRoot. Also detect <root>/node_modules/.bin (lix shims) and .haxerc.
│     Cached per root; invalidated on invocation failure.
├── FormatterExecutor  (interface — mockable for tests)
│     └── CliFormatterExecutor
│           GeneralCommandLine("haxelib", "run", "formatter", "--stdin", "-s", filePath)
│           workDirectory = configRoot
│           env = EnvironmentUtil.getEnvironmentMap() (login-shell capture)
│                 + PATH prepended with <root>/node_modules/.bin when present
│           charset = UTF-8; stdin ← document text; capture stdout, stderr, exit code
│           cancel → OSProcessHandler.destroyProcess()
└── ResultPolicy
      exit-code mapping (§5.3) + trailing-newline normalisation + no-change detection
```

### Data flow

```
⌘⌥L on foo.hx
  → CodeStyleManager.reformatText
  → FormattingServiceUtil.findService          (ours wins; CoreFormattingService is order="last")
  → HaxeExternalFormattingService.createFormattingTask   [EDT, fast]
       snapshot: documentText, absolute file path
  → FormattingTask.run()                        [background thread]
       resolve configRoot (cached) → build command → spawn process
       write documentText → stdin, close stdin → read stdout/stderr → exit code
       exit 0 & changed   → onTextReady(normalised stdout)
       exit 0 & unchanged → onTextReady(null)
       exit 1 (excluded/disabled) → onTextReady(null)          (silent no-op)
       exit 2/3/−1        → onError("Haxe Formatter", stderr)  (buffer untouched)
  → platform applies text                       [EDT, write action, DocumentMerger if doc changed]
```

## 5. Resolved answers to the spec questions

### 5.1 Extension point (Q1)

Covered in §3. Additional verified facts:

- `AsyncFormattingRequest` provides `getDocumentText()` (in-memory text — this is what
  we pipe, so unsaved-but-autosave-pending edits are always included),
  `getFormattingRanges()`, `getIOFile()` (a **temp** file — we deliberately do **not**
  use it, because config discovery and exclusion regexes key off the real path, §5.3),
  `onTextReady(String|null)`, `onError(title, message)`.
- Timeout: default 30 s (`getTimeout()` overridable). Ample for an explicit action.
- `getFeatures()`: we declare `FORMAT_FRAGMENTS` only (per D6). Not `AD_HOC_FORMATTING`
  — implicit formatting stays built-in, which also guarantees "never on typing".
- **No `LanguageFormattingRestriction`** — needed by nothing, and it would grey out
  Reformat Code (pitfall confirmed; mechanism in §3).

### 5.2 Coexistence with the Haxe Toolkit plugin (Q2)

- `plugin.xml` declares `<depends>com.intellij.plugins.haxe</depends>` (marketplace
  plugin 6873). This is the correct model: it expresses the real requirement (Haxe
  language, file type, PSI, and a `FormattingModelBuilder` that keeps the Reformat
  action enabled) and guarantees load order.
- The team runs a **fork** of intellij-haxe distributed via
  `https://carostobbe.github.io/intellij-haxe/updatePlugins.xml`. A fork served as a
  drop-in update must keep the plugin id `com.intellij.plugins.haxe` — verify once
  against the fork's descriptor (§7).
- `canFormat` matches by **language name string** (`"Haxe"`) or `.hx` extension rather
  than by referencing Haxe plugin classes, so we need no compile-time dependency on the
  fork's jars and are immune to its internal API churn.
- Live **type-and-indent stays with the built-in engine** — accepted. Indent-on-Enter
  and refactoring cleanups are local micro-operations; the file converges to
  hxformat.json form on the next explicit ⌘⌥L (and in CI). This is the same division of
  labour the Prettier plugin uses.

### 5.3 haxe-formatter `--stdin` behaviour (Q3) — verified against formatter source (v1.18.0)

- **Flags:** `--stdin` reads all of STDIN to EOF and writes the result to STDOUT.
  It requires **exactly one** `-s <path>`; that path is the mechanism for passing the
  logical file path and serves double duty:
  1. `Formatter.loadConfig(path)` walks **up** the directory tree to the nearest
     `hxformat.json`;
  2. the path is matched against the config's `excludes` regexes and is the
     `SourceFile` name for per-file behaviour.
  The file **content is never read from that path** — only STDIN is formatted.
- **Constraint:** the `-s` path must **exist on disk** (`FileSystem.exists` check) or
  the formatter echoes the input and exits 3. With autosave on this is virtually always
  true; a brand-new never-saved file simply produces a notification and no change.
- **Exclusions / opt-outs:** `hxformat.json` `"excludes"` (regex list) and
  `"disableFormatting": true` → the CLI echoes the input unchanged and exits **1**
  (silently, no stderr). `// @formatter:off` / `// @formatter:on` regions are handled
  inside the engine (line-based, markers must have no trailing whitespace) and work
  identically in stdin mode.
- **Error safety (critical):** on any failure — syntax error (`Failure`, exit **2**),
  bad `-s` (exit **3**), no input/crash (exit **−1**) — the formatter writes the
  **original input unchanged** to STDOUT and the diagnostic to STDERR. It never emits
  partial output. Plugin policy regardless: **only apply on exit 0.**
- **Exit codes (stdin mode):** 0 success · 1 disabled/excluded · 2 format failure ·
  3 usage/path error · −1 no input/exception. No distinct changed/unchanged code —
  the plugin diffs output against input itself.
- ⚠️ **Trailing newline:** output is emitted via `Sys.println`, which appends `\n`.
  Without normalisation every reformat grows the file by one blank line. The
  `ResultPolicy` must normalise (empirically pin down the exact behaviour, Neko vs Node,
  in the integration tests; the idempotency test in §5.7 guards it).

### 5.4 Invocation robustness (Q4)

The IntelliJ JVM on macOS launches with a minimal PATH (GUI apps don't source shell rc
files) — a bare `haxelib`/`node` call fails. Generic, per-project design:

1. **Environment:** base env = `EnvironmentUtil.getEnvironmentMap()`. On macOS the IDE
   captures the user's **login-shell environment** at startup (it spawns `$SHELL` as a
   login shell and snapshots `printenv`), so the real PATH — including Homebrew
   node/neko and global haxelib — is available without any wrapper script. Equivalent:
   `GeneralCommandLine` with `ParentEnvironmentType.CONSOLE`.
2. **Project-local shims:** if `<configRoot>/node_modules/.bin` exists (lix projects),
   prepend it to PATH. Under lix, `haxelib` there is the haxeshim — scoped resolution
   via `.haxerc` picks the right formatter version **per worktree** automatically.
3. **Working directory = configRoot** (the directory containing `hxformat.json`), found
   by walking up from the file. This makes discovery **worktree-correct by
   construction**: every worktree has its own `hxformat.json`, `.haxerc`,
   `node_modules`, so no global state or IDE project root is consulted.
4. **Command:** `haxelib run formatter --stdin -s <absoluteFilePath>`.

**Neko-vs-Node nuance (verified in source):** `haxelib run formatter` — under both
stock haxelib and the lix shim — launches the Neko `run.n` (formatter's `haxelib.json`
has no `main`). `run.n` then probes `node -v` and, when node is on PATH, re-executes
itself as `node run.js` (faster). Stdio is inherited, so the pipe works transparently
either way; the cost is one extra process hop per invocation. **Optimisation (optional,
not v1):** resolve the formatter lib dir once per configRoot (`haxelib libpath
formatter` with the same env/cwd) and invoke `node <libdir>/run.js --stdin -s <path>`
directly — skips Neko entirely. Ship v1 with the plain `haxelib run` form; add the
direct-node fast path only if measured latency annoys (it's an explicit action; a few
hundred ms is acceptable).

The FOE monorepo's `scripts/frontend/format-haxe-on-save.sh` is **prior art, not a
dependency** — the plugin re-implements its PATH insight generically (D4). The script
can remain for CLI/CI use.

### 5.5 Range/selection formatting; whitespace-only; keep-line-breaks (Q5)

- The formatter **CLI has no range support** (range formatting exists only in its
  library API, unexposed by `Cli.hx`). Given D6: declare `FORMAT_FRAGMENTS`, ignore
  `getFormattingRanges()`, and always format the **whole file** — output always matches
  CI. Alternatives considered: (a) not declaring the feature → selection reformat falls
  to the built-in engine → output diverges from hxformat.json (violates the goal);
  (b) declining with a notification → friction on a muscle-memory action. **Revisit D6
  if whole-file-on-selection surprises the team in practice.**
- `canChangeWhitespaceOnly()` / keep-line-breaks: the CLI has **no such flags**; these
  are hxformat.json config concerns (`"keep"` wrapping policies etc.). The service
  ignores the request's whitespace-only hint — irrelevant for explicit whole-file
  reformat, which is the only path we serve.

### 5.6 Build & distribution (Q6)

- **Toolchain:** IntelliJ Platform Gradle Plugin 2.x (`org.jetbrains.intellij.platform`),
  Kotlin, JDK 17+. Verify the exact Gradle floor against the pinned plugin version
  (docs currently say Gradle 9 for the latest 2.x; older 2.x accepted 8.5).
- **Gradle dependency on the Haxe plugin:**
  `dependencies { intellijPlatform { plugin("com.intellij.plugins.haxe", "<version>") } }`
  (compile-time presence only; no fork classes referenced).
- **IDE range (D7):** mirror the intellij-haxe fork's `since-build`/`until-build` so the
  pair installs together cleanly.
- **Distribution:** `./gradlew buildPlugin` → zip in `build/distributions/`. Publish via
  a **custom plugin repository** `updatePlugins.xml`, per D5. Two options:
  - **Recommended:** add this plugin as a second `<plugin>` entry in the **existing**
    `carostobbe.github.io/intellij-haxe/updatePlugins.xml` — one custom repository URL
    can serve multiple plugins, and every dev already has that URL configured →
    zero-setup rollout and auto-updates.
  - Alternative: this repo gets its own GitHub Pages `updatePlugins.xml` (independent
    release cadence, but each dev must add a second repository URL once).
  Either way: a GitHub Actions workflow builds the zip on tag, attaches it as a release
  asset, and regenerates the XML. Manual "Install Plugin from Disk" remains the
  fallback.
- **Worktrees:** nothing to do — discovery is per-file walk-up (§5.4), so any worktree,
  any checkout location, any repo works identically.
- **macOS-only (D3):** the only platform-coupled piece is `EnvironmentUtil`'s env
  capture semantics, which the platform itself abstracts; Linux would likely work
  unmodified, Windows would need invocation changes. Out of scope; not tested.

### 5.7 Testing strategy (Q7)

1. **Pure unit tests (plain JUnit, no IDE):** configRoot walk-up discovery, command
   construction, PATH prepending, exit-code → action mapping, trailing-newline
   normalisation, no-change detection.
2. **Platform tests (`BasePlatformTestCase`):** register the service with a **fake
   `FormatterExecutor`** (the interface exists for exactly this) returning canned
   (stdout, stderr, exitCode) triples; invoke the reformat action on fixture `.hx` files
   and assert: document replaced on exit 0; untouched on 1/2/3/−1; notification raised
   on 2/3/−1; selection reformat formats the whole file; cancellation destroys the
   process.
3. **Integration tests (macOS runner with node + lix, real formatter):** golden-file
   suite over a fixture repo containing `hxformat.json`, an excluded file, a
   `@formatter:off` region, and a syntax-error file. Byte-compare output with the same
   files formatted by the VS Code/CI toolchain. **Idempotency test:** format twice →
   byte-identical (catches the trailing-newline bug).
4. **Manual smoke checklist:** autosave on + typing during a slow format (DocumentMerger
   path), undo behaviour after reformat, Project-view directory reformat, second
   worktree.

### 5.8 Migration (Q8)

Retire the format-on-save File Watcher (in the FOE monorepo, not this repo):

- **Remove** the "haxe format on save" File Watcher entry → delete/prune
  `.idea/watcherTasks.xml` (per project **and per worktree**; it's IDE-generated, also
  remove from any shared/template `.idea` config or setup automation that seeds it).
- **Remove** any setup-flow step that installs/configures that watcher.
- **Keep** `scripts/frontend/format-haxe-on-save.sh` only if something else (pre-commit,
  CI, docs) still calls it; the plugin does not. Rename/retire at the monorepo owners'
  discretion.
- **Add** to onboarding: install the plugin from the custom repository (or nothing, if
  it's added to the already-configured intellij-haxe repository XML — recommended).
- Optionally disable the File Watchers bundled plugin if nothing else uses it.

## 6. Risks & open questions

| Risk / question | Mitigation |
|---|---|
| Fork's plugin id differs from `com.intellij.plugins.haxe` | One-line check of the fork's `plugin.xml` before pinning `<depends>`. |
| Trailing-newline behaviour differs Neko vs Node | Pin down empirically; idempotency test guards it permanently. |
| Reformat applied "undo-transparently" (platform wraps the write in `runUndoTransparentAction`) — ⌘Z semantics need validating | Manual smoke test; same machinery Prettier uses, so expected fine. |
| Latency of Neko→Node double-hop on every invoke | Measure; if annoying, add cached direct `node run.js` fast path (§5.4). |
| No `hxformat.json` found above the file | Formatter falls back to default config (same as VS Code behaviour) — accepted for genericity; could optionally no-op instead. Decide at implementation. |
| Dev enables "Reformat code on save" (Actions on Save) | Would route saves through the CLI. Default is off; document "leave it off". (Autosave itself never triggers it.) |
| Project-view bulk reformat over a large directory spawns one process per file, sequentially | Acceptable for an explicit action; note in README. |
| `-s` path must exist on disk | Only affects never-saved files; graceful notification, buffer untouched. |

## 7. Pre-implementation verification checklist

- [ ] Fork's plugin id == `com.intellij.plugins.haxe`; read its since/until build range (D7).
- [ ] Empirically byte-diff `--stdin` output vs input for the no-change case (trailing newline, Neko vs Node).
- [ ] Confirm `haxelib libpath formatter` works under the lix haxeshim (only needed for the optional fast path).
- [ ] Confirm exact Gradle/JDK floor for the pinned IntelliJ Platform Gradle Plugin version.

## 8. References

- IntelliJ Platform source (tag `idea/243.22562.145`):
  `AsyncDocumentFormattingService`, `AsyncFormattingRequest`, `FormattingService`,
  `FormattingServiceUtil`, `CodeStyleManagerImpl`, `CoreFormattingService`
  (registered `order="last"` in `CodeStyle.xml`), `LanguageFormattingRestriction`,
  `LanguageFormatting`, `ReformatCodeAction`, `ExternalFormatProcessor`,
  `EnvironmentUtil` — github.com/JetBrains/intellij-community
- SDK docs: Code Formatting / `formattingService`
  (plugins.jetbrains.com/docs/intellij/code-formatting.html), Plugin Dependencies,
  Tools: IntelliJ Platform Gradle Plugin 2.x, Testing (tests-and-fixtures,
  light-and-heavy-tests), Publishing
- Reference implementation: Prettier plugin — `PrettierFormattingService.kt`,
  `plugin.xml` (github.com/JetBrains/intellij-plugins/tree/master/prettierJS)
- JetBrains support: "Shell Environment Loading" (macOS GUI-app PATH capture)
- haxe-formatter v1.18.0 source: `src/formatter/Cli.hx` (flag set, `runPipe`, exit
  codes, Neko→Node delegation), `src/formatter/Formatter.hx` (`loadConfig`,
  `determineConfig`), `src/formatter/config/Config.hx` (`excludes`,
  `disableFormatting`), `src/formatter/codedata/CodeLines.hx` (`@formatter:off`),
  `haxelib.json`, `README.md` — github.com/HaxeCheckstyle/haxe-formatter
- lix/haxeshim: `src/haxeshim/cli/HaxelibCli.hx` (`runDir` — how `haxelib run` resolves
  under lix) — github.com/lix-pm/haxeshim
- Haxe Toolkit Support plugin: marketplace id 6873, plugin id
  `com.intellij.plugins.haxe` — github.com/HaxeFoundation/intellij-haxe; team fork
  repository XML: carostobbe.github.io/intellij-haxe/updatePlugins.xml
