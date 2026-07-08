# Haxe Formatter IntelliJ Plugin — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the IntelliJ plugin described in `docs/plans/2026-07-08-haxe-formatter-plugin-design.md` — an `AsyncDocumentFormattingService` that routes Reformat Code (⌘⌥L) on `.hx` files through `haxelib run formatter --stdin -s <path>` so IDE output is byte-identical to VS Code/CI.

**Architecture:** Four small components (per design §4): `HaxeExternalFormattingService` (the EP implementation), `FormatterContextResolver` (walk-up discovery of `hxformat.json` root + `node_modules/.bin`, cached per root), `FormatterExecutor` interface with `CliFormatterExecutor` (process spawn, stdin/stdout/stderr/exit-code, cancellation), and `ResultPolicy` (exit-code mapping, trailing-newline normalisation, no-change detection). The service is constructor-injectable so platform tests use a fake executor. No compile-time dependency on any Haxe plugin class.

**Tech Stack:** Kotlin, IntelliJ Platform Gradle Plugin 2.x (`org.jetbrains.intellij.platform`, pin 2.17.0), Gradle ≥ 9.0.0 (wrapper committed), JDK 21 toolchain (platform build 253 requires Java 21 bytecode; satisfies the design's "JDK 17+"), JUnit 4 (both pure unit tests and `BasePlatformTestCase`).

**Design doc is source of truth:** `docs/plans/2026-07-08-haxe-formatter-plugin-design.md`. Decisions D1–D7 are settled — do not re-litigate. In particular D6 (selection ⌘⌥L formats the whole file) and D7 (IDE range mirrors the team's intellij-haxe fork) are accepted.

**Skills to use while executing:**
- superpowers:test-driven-development for every code task (Tasks 4–9). Test first, watch it fail, minimal implementation, watch it pass, commit.
- superpowers:verification-before-completion before claiming any task done.

**Verified facts (fetched 2026-07-08 — Task 1/2 re-verify at execution time):**
- Fork descriptor at `https://carostobbe.github.io/intellij-haxe/updatePlugins.xml`: single entry, `id="com.intellij.plugins.haxe"`, version `1.8.1-fork.2-dev.5`, since-build `253`, until-build `261.*`.
- IntelliJ Platform Gradle Plugin 2.x (latest 2.17.0) minimums: IntelliJ Platform 2023.3, **Gradle 9.0.0**, Java runtime 17 (for Gradle itself). Targeting build 253 additionally requires compiling to Java 21 bytecode.

**Conventions used below:**
- All paths are relative to the repo root `/Users/tobias.jansing/coding_projects/haxe-format-intellij`.
- Kotlin package: `com.innogames.haxeformatter`. Plugin id: `com.innogames.haxeformatter`.
- Run all Gradle commands from the repo root with `./gradlew` (wrapper, committed in Task 3).
- Exit-code nuance: the formatter's documented "−1" exit surfaces to the JVM as **255** on macOS (POSIX `& 0xFF`). `ResultPolicy` therefore treats *any* exit code other than 0 and 1 as an error.

---

## Phase 0 — Pre-implementation verification (design §7)

### Task 1: Verify the intellij-haxe fork's plugin id and build range (D7)

**Files:** none (verification only; values are already pinned in this plan's header — this task re-confirms them).

**Step 1: Fetch the fork's repository descriptor**

Run:
```bash
curl -s https://carostobbe.github.io/intellij-haxe/updatePlugins.xml
```

Expected output contains:
```xml
<plugin id="com.intellij.plugins.haxe" url="https://github.com/CaroStobbe/intellij-haxe/releases/download/v1.8.1-fork.2-dev.5/intellij-haxe-2026.1.zip" version="1.8.1-fork.2-dev.5">
```
with `<idea-version since-build="253" until-build="261.*"/>` (attributes may live on the `idea-version` child element — read whichever is present).

**Step 2: Record and cross-check**

- Confirm plugin id is exactly `com.intellij.plugins.haxe` → this is what `<depends>` in `plugin.xml` (Task 3) uses. If it differs, STOP and update Task 3's plugin.xml and this plan before proceeding.
- Confirm since/until (expected `253` / `261.*`) → these are the `sinceBuild`/`untilBuild` values in `build.gradle.kts` (Task 3) and determine `platformVersion` (2025.3, i.e. the lowest supported build line). If the fork has moved since this plan was written, use the fork's *current* values everywhere this plan says `253`/`261.*`/`2025.3`.
- Note the release zip URL — Task 9 optionally downloads it for `runIde` smoke testing.

**Success criteria:** id and build range confirmed (or plan values updated to match reality). No commit — nothing changed.

---

### Task 2: Confirm toolchain floor and prepare local environment

**Files:** none (verification only).

**Step 1: Confirm the Gradle/JDK floor for the pinned plugin version**

Run:
```bash
curl -s https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html | grep -o 'Gradle[^<]*9[^<]*' | head -5
```
(or WebFetch the page). Expected: IntelliJ Platform Gradle Plugin 2.x requires **Gradle 9.0.0** and **Java runtime 17** minimum. Pin plugin version **2.17.0** (or the latest 2.x listed — if newer, use it and re-check its floor).

**Step 2: Verify local JDK 21 and Gradle availability**

Run:
```bash
java -version
gradle --version || echo "no system gradle"
```
Expected: a JDK 21 (`21.x`) available. If not: `brew install --cask temurin@21`. A system Gradle is only needed once, to generate the wrapper in Task 3 (`brew install gradle` if absent); after that the committed wrapper is used.

**Step 3: Record the deferred §7 checklist items**

Two design-§7 items **cannot** be verified now — they need a real Haxe project with node + lix and the real formatter. They are scheduled as integration-test work, marked as requiring that environment:

- §7 item "byte-diff `--stdin` output vs input for the no-change case (trailing newline, Neko vs Node)" → **Task 11, integration test `TrailingNewlineIT`**.
- §7 item "confirm `haxelib libpath formatter` works under the lix haxeshim" (only needed for the optional post-v1 fast path) → **Task 11, integration test `LibpathProbeIT`** (informational/optional).

**Success criteria:** Gradle ≥ 9.0.0 + JDK 21 confirmed available; plugin version pinned; deferred items acknowledged. No commit.

---

## Phase 1 — Project scaffolding

### Task 3: Gradle project skeleton, wrapper, minimal plugin.xml

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `src/main/resources/META-INF/plugin.xml`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/*` (generated, committed)
- Modify: `.gitignore` (currently empty)

**Step 1: Write `.gitignore`**

```gitignore
.gradle/
build/
.intellijPlatform/
libs/
.idea/
src/integrationTest/fixtures/project/node_modules/
src/integrationTest/fixtures/project/haxe_libraries/
src/integrationTest/fixtures/project/.haxerc
local.properties
```

**Step 2: Write `gradle.properties`**

```properties
pluginGroup = com.innogames.haxeformatter
pluginVersion = 0.1.0
# Lowest supported build line per D7 (fork since-build 253 = 2025.3) — value re-verified in Task 1
platformVersion = 2025.3

# IntelliJ Platform bundles the Kotlin stdlib — do not ship our own
kotlin.stdlib.default.dependency = false
org.gradle.caching = true
org.gradle.jvmargs = -Xmx2g
```

**Step 3: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "haxe-format-intellij"
```

**Step 4: Write `build.gradle.kts`**

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "2.2.0" // any KGP compatible with Gradle 9
    id("org.jetbrains.intellij.platform") version "2.17.0" // floor: Gradle 9.0.0 (Task 2)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        testFramework(TestFrameworkType.Platform)

        // Optional: the team's intellij-haxe fork for runIde smoke testing (Task 9).
        // Not required for compilation or tests — we reference no Haxe classes (design §5.2).
        val haxeFork = layout.projectDirectory.file("libs/intellij-haxe-fork.zip")
        if (haxeFork.asFile.exists()) {
            localPlugin(haxeFork)
        }
    }
    testImplementation("junit:junit:4.13.2")
    // If platform tests fail with NoClassDefFoundError: org/opentest4j/..., add:
    // testRuntimeOnly("org.opentest4j:opentest4j:1.3.0")
}

kotlin { jvmToolchain(21) } // build 253 (2025.3) requires Java 21 bytecode

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            // Mirrors the intellij-haxe fork's range (D7; verified in Task 1)
            sinceBuild = "253"
            untilBuild = "261.*"
        }
    }
}

// Integration tests (Task 11) live in package ...haxeformatter.it and are opt-in:
tasks.test {
    filter { excludeTestsMatching("com.innogames.haxeformatter.it.*") }
}

// Plain-JUnit process tests against the real formatter; requires macOS + node + lix
// (or stock haxelib) — see src/integrationTest/fixtures/README.md (Task 11).
val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs real-formatter integration tests (requires macOS + node + lix/haxelib)"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("com.innogames.haxeformatter.it.*") }
    environment("HAXE_FORMATTER_IT", "1")
    outputs.upToDateWhen { false }
}
```

**Step 5: Write minimal `src/main/resources/META-INF/plugin.xml`**

(No `formattingService` yet — that is registered in Task 9 once the class exists.)

```xml
<idea-plugin>
    <id>com.innogames.haxeformatter</id>
    <name>Haxe Formatter (hxformat.json)</name>
    <vendor>InnoGames</vendor>
    <description><![CDATA[
        Routes IntelliJ's Reformat Code action for Haxe (.hx) files through the real
        haxe-formatter CLI (HaxeCheckstyle, hxformat.json), so IDE formatting is
        byte-identical to VS Code and CI. macOS only.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <!-- Haxe Toolkit Support — the team's fork keeps this id (verified, Task 1) -->
    <depends>com.intellij.plugins.haxe</depends>

    <extensions defaultExtensionNs="com.intellij">
        <notificationGroup id="Haxe Formatter" displayType="BALLOON"/>
    </extensions>
</idea-plugin>
```

**Step 6: Generate and commit the Gradle wrapper**

Run (uses system Gradle once; pick the newest 9.x from https://gradle.org/releases/ — 9.0.0 is the verified minimum):
```bash
gradle wrapper --gradle-version 9.0.0
```
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` created.

**Step 7: Verify the empty project builds**

Run:
```bash
./gradlew build
```
Expected: `BUILD SUCCESSFUL`. First run downloads IntelliJ IDEA Community 2025.3 (large, needs network). If `2025.3` is not yet resolvable under that exact version string, list candidates with `./gradlew printProductsReleases` and use the closest 253-line release.

**Step 8: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle/ src/main/resources/META-INF/plugin.xml
git commit -m "chore: scaffold IntelliJ Platform Gradle 2.x project (build 253–261.*)"
```

**Success criteria:** `./gradlew build` green; wrapper committed; plugin.xml declares `<depends>com.intellij.plugins.haxe</depends>` and the notification group.

---

## Phase 2 — Core components (TDD, pure JUnit)

> Each task in this phase follows superpowers:test-driven-development: write the failing test, run it, watch it fail for the right reason, implement minimally, watch it pass, commit.

### Task 4: ResultPolicy — exit-code mapping, trailing-newline normalisation, no-change detection

**Files:**
- Create: `src/main/kotlin/com/innogames/haxeformatter/FormatterExecutor.kt` (data types only, needed by the test)
- Create: `src/main/kotlin/com/innogames/haxeformatter/ResultPolicy.kt`
- Test: `src/test/kotlin/com/innogames/haxeformatter/ResultPolicyTest.kt`

**Step 1: Write the data types the policy consumes**

`src/main/kotlin/com/innogames/haxeformatter/FormatterExecutor.kt`:
```kotlin
package com.innogames.haxeformatter

import java.nio.file.Path

data class FormatterCommand(
    val argv: List<String>,
    val workDir: Path,
    val environment: Map<String, String>,
)

data class FormatterResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

/** Started, running formatter process. */
interface FormatterProcess {
    /** Blocks until the process exits. */
    fun waitFor(): FormatterResult
    /** Kills the process; waitFor() must then return promptly. */
    fun destroy()
}

/** Mockable seam between the service and the OS (design §4). */
interface FormatterExecutor {
    fun start(command: FormatterCommand, stdin: String): FormatterProcess
}
```

**Step 2: Write the failing test**

`src/test/kotlin/com/innogames/haxeformatter/ResultPolicyTest.kt`:
```kotlin
package com.innogames.haxeformatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultPolicyTest {

    private fun result(stdout: String = "", stderr: String = "", exit: Int) =
        FormatterResult(stdout, stderr, exit)

    // --- exit 0 -------------------------------------------------------------

    @Test
    fun `exit 0 with changed output applies text with one trailing newline stripped`() {
        // formatter prints formattedText via Sys.println -> exactly one extra \n (design §5.3)
        val outcome = ResultPolicy.decide("class Foo{}", result(stdout = "class Foo {}\n\n", exit = 0))
        assertEquals(FormatOutcome.ApplyText("class Foo {}\n"), outcome)
    }

    @Test
    fun `exit 0 with output equal to input after normalisation is a silent no-change`() {
        val input = "class Foo {}\n"
        val outcome = ResultPolicy.decide(input, result(stdout = input + "\n", exit = 0))
        assertEquals(FormatOutcome.NoChange, outcome)
    }

    // --- exit 1: excluded / disableFormatting -> silent no-op (design §5.3) --

    @Test
    fun `exit 1 is a silent no-change even though stdout echoes the input`() {
        val input = "class Foo{}"
        val outcome = ResultPolicy.decide(input, result(stdout = input + "\n", exit = 1))
        assertEquals(FormatOutcome.NoChange, outcome)
    }

    // --- errors: 2, 3, -1(=255) ----------------------------------------------

    @Test
    fun `exit 2 is an error carrying stderr`() {
        val outcome = ResultPolicy.decide("x", result(stderr = "Parse error line 3", exit = 2))
        assertEquals(FormatOutcome.Error("Haxe Formatter", "Parse error line 3"), outcome)
    }

    @Test
    fun `exit 3 is an error`() {
        assertTrue(ResultPolicy.decide("x", result(stderr = "bad -s path", exit = 3)) is FormatOutcome.Error)
    }

    @Test
    fun `exit 255 (formatter's -1) is an error`() {
        assertTrue(ResultPolicy.decide("x", result(exit = 255)) is FormatOutcome.Error)
    }

    @Test
    fun `error with blank stderr falls back to a message naming the exit code`() {
        val outcome = ResultPolicy.decide("x", result(exit = 2)) as FormatOutcome.Error
        assertTrue(outcome.message.contains("2"))
    }

    // --- normalisation rule in isolation --------------------------------------
    // Rule (v1, pinned empirically by TrailingNewlineIT in Task 11): the CLI emits the
    // formatted text via Sys.println, i.e. exactly one '\n' beyond the true result.

    @Test
    fun `normalise strips exactly one trailing newline`() {
        assertEquals("a\n", ResultPolicy.normaliseTrailingNewline("a\n\n"))
        assertEquals("a", ResultPolicy.normaliseTrailingNewline("a\n"))
        assertEquals("a", ResultPolicy.normaliseTrailingNewline("a"))
        assertEquals("", ResultPolicy.normaliseTrailingNewline("\n"))
    }
}
```

**Step 3: Run the test — expect compile failure (ResultPolicy missing)**

Run: `./gradlew test --tests "com.innogames.haxeformatter.ResultPolicyTest"`
Expected: FAILED — unresolved reference `ResultPolicy` / `FormatOutcome`.

**Step 4: Minimal implementation**

`src/main/kotlin/com/innogames/haxeformatter/ResultPolicy.kt`:
```kotlin
package com.innogames.haxeformatter

sealed interface FormatOutcome {
    data class ApplyText(val text: String) : FormatOutcome
    object NoChange : FormatOutcome
    data class Error(val title: String, val message: String) : FormatOutcome
}

/**
 * Maps a formatter CLI result onto a document action (design §5.3).
 * Apply ONLY on exit 0; exit 1 (excluded/disableFormatting) is a silent no-op;
 * anything else (2 format failure, 3 usage/path, 255 = Neko/Node -1) is an error
 * that must leave the buffer untouched.
 */
object ResultPolicy {
    const val NOTIFICATION_TITLE = "Haxe Formatter"

    fun decide(input: String, result: FormatterResult): FormatOutcome = when (result.exitCode) {
        0 -> {
            val normalised = normaliseTrailingNewline(result.stdout)
            if (normalised == input) FormatOutcome.NoChange else FormatOutcome.ApplyText(normalised)
        }
        1 -> FormatOutcome.NoChange
        else -> FormatOutcome.Error(
            NOTIFICATION_TITLE,
            result.stderr.ifBlank { "haxe-formatter exited with code ${result.exitCode}" },
        )
    }

    /**
     * The CLI emits its result with Sys.println, appending exactly one '\n' beyond the
     * formatted text (design §5.3 ⚠). Strip exactly one. The empirical rule is pinned
     * permanently by the idempotency + trailing-newline integration tests (Task 11).
     */
    fun normaliseTrailingNewline(stdout: String): String = stdout.removeSuffix("\n")
}
```

**Step 5: Run the test — expect pass**

Run: `./gradlew test --tests "com.innogames.haxeformatter.ResultPolicyTest"`
Expected: PASS (8 tests).

**Step 6: Commit**

```bash
git add src/main/kotlin/com/innogames/haxeformatter/FormatterExecutor.kt \
        src/main/kotlin/com/innogames/haxeformatter/ResultPolicy.kt \
        src/test/kotlin/com/innogames/haxeformatter/ResultPolicyTest.kt
git commit -m "feat: ResultPolicy exit-code mapping + trailing-newline normalisation (TDD)"
```

**Success criteria:** all ResultPolicyTest cases green; `ResultPolicy` has no IntelliJ imports (pure JVM).

---

### Task 5: FormatterContextResolver — hxformat.json walk-up, node_modules/.bin, per-root cache

**Files:**
- Create: `src/main/kotlin/com/innogames/haxeformatter/FormatterContextResolver.kt`
- Test: `src/test/kotlin/com/innogames/haxeformatter/FormatterContextResolverTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.innogames.haxeformatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FormatterContextResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val resolver = FormatterContextResolver()

    private fun mkdirs(vararg segments: String): Path {
        val p = tmp.root.toPath().resolve(segments.joinToString("/"))
        Files.createDirectories(p)
        return p
    }

    @Test
    fun `configRoot is the nearest ancestor containing hxformat json`() {
        val root = mkdirs("repo")
        Files.createFile(root.resolve("hxformat.json"))
        val src = mkdirs("repo", "src", "game")
        val ctx = resolver.resolve(src.resolve("Foo.hx"))
        assertEquals(root, ctx.configRoot)
    }

    @Test
    fun `nested hxformat json closest to the file wins`() {
        val outer = mkdirs("repo"); Files.createFile(outer.resolve("hxformat.json"))
        val inner = mkdirs("repo", "sub"); Files.createFile(inner.resolve("hxformat.json"))
        val ctx = resolver.resolve(inner.resolve("deep").also(Files::createDirectories).resolve("Foo.hx"))
        assertEquals(inner, ctx.configRoot)
    }

    @Test
    fun `no hxformat json anywhere falls back to the file's own directory`() {
        // Accepted in design §6: formatter then uses its default config, like VS Code.
        val dir = mkdirs("plain", "src")
        val ctx = resolver.resolve(dir.resolve("Foo.hx"))
        assertEquals(dir, ctx.configRoot)
    }

    @Test
    fun `node_modules bin is discovered by walking up (lix shims)`() {
        val root = mkdirs("repo")
        Files.createFile(root.resolve("hxformat.json"))
        val bin = mkdirs("repo", "node_modules", ".bin")
        val src = mkdirs("repo", "src")
        val ctx = resolver.resolve(src.resolve("Foo.hx"))
        assertEquals(bin, ctx.nodeBinDir)
    }

    @Test
    fun `nodeBinDir is null when no node_modules bin exists`() {
        val root = mkdirs("repo2"); Files.createFile(root.resolve("hxformat.json"))
        val ctx = resolver.resolve(root.resolve("Foo.hx"))
        assertNull(ctx.nodeBinDir)
    }

    @Test
    fun `results are cached per directory and refreshed after invalidate`() {
        val root = mkdirs("repo3"); Files.createFile(root.resolve("hxformat.json"))
        val sub = mkdirs("repo3", "sub")
        val file = sub.resolve("Foo.hx")

        assertEquals(root, resolver.resolve(file).configRoot)

        // A closer config appears; the cached answer must still be served ...
        Files.createFile(sub.resolve("hxformat.json"))
        assertEquals(root, resolver.resolve(file).configRoot)

        // ... until invalidated (design: invalidated on invocation failure).
        resolver.invalidate(file)
        assertEquals(sub, resolver.resolve(file).configRoot)
    }
}
```

**Step 2: Run — expect compile failure**

Run: `./gradlew test --tests "com.innogames.haxeformatter.FormatterContextResolverTest"`
Expected: FAILED — unresolved reference `FormatterContextResolver`.

**Step 3: Minimal implementation**

```kotlin
package com.innogames.haxeformatter

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class FormatterContext(
    /** Directory containing the nearest hxformat.json, else the file's own directory. */
    val configRoot: Path,
    /** Nearest ancestor's node_modules/.bin (lix shims), or null. */
    val nodeBinDir: Path?,
)

/**
 * Walk-up discovery of the formatter context for a file (design §4, §5.4).
 * Worktree-correct by construction: only the file's own ancestors are consulted.
 * Cached per containing directory; invalidate() on invocation failure.
 */
class FormatterContextResolver {
    private val cache = ConcurrentHashMap<Path, FormatterContext>()

    fun resolve(filePath: Path): FormatterContext {
        val dir = filePath.toAbsolutePath().parent ?: filePath.toAbsolutePath()
        return cache.computeIfAbsent(dir, ::compute)
    }

    fun invalidate(filePath: Path) {
        val dir = filePath.toAbsolutePath().parent ?: filePath.toAbsolutePath()
        cache.remove(dir)
    }

    private fun compute(startDir: Path): FormatterContext {
        val configRoot = findUp(startDir) { Files.isRegularFile(it.resolve("hxformat.json")) } ?: startDir
        val nodeBin = findUp(startDir) { Files.isDirectory(it.resolve("node_modules").resolve(".bin")) }
            ?.resolve("node_modules")?.resolve(".bin")
        return FormatterContext(configRoot, nodeBin)
    }

    private fun findUp(from: Path, predicate: (Path) -> Boolean): Path? {
        var dir: Path? = from
        while (dir != null) {
            if (predicate(dir)) return dir
            dir = dir.parent
        }
        return null
    }
}
```

**Step 4: Run — expect pass**

Run: `./gradlew test --tests "com.innogames.haxeformatter.FormatterContextResolverTest"`
Expected: PASS (6 tests).

**Step 5: Commit**

```bash
git add src/main/kotlin/com/innogames/haxeformatter/FormatterContextResolver.kt \
        src/test/kotlin/com/innogames/haxeformatter/FormatterContextResolverTest.kt
git commit -m "feat: FormatterContextResolver walk-up discovery with per-root cache (TDD)"
```

**Success criteria:** all resolver tests green; no IntelliJ imports (pure JVM + java.nio).

---

### Task 6: FormatterCommandBuilder — argv, cwd, PATH prepend

**Files:**
- Create: `src/main/kotlin/com/innogames/haxeformatter/FormatterCommandBuilder.kt`
- Test: `src/test/kotlin/com/innogames/haxeformatter/FormatterCommandBuilderTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.innogames.haxeformatter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class FormatterCommandBuilderTest {

    private val root = Paths.get("/repo")
    private val file = Paths.get("/repo/src/Foo.hx")

    @Test
    fun `builds the stdin pipe command with the real file path (design §5-3, D2)`() {
        val cmd = FormatterCommandBuilder.build(FormatterContext(root, null), file, emptyMap())
        assertEquals(listOf("haxelib", "run", "formatter", "--stdin", "-s", "/repo/src/Foo.hx"), cmd.argv)
    }

    @Test
    fun `working directory is the configRoot`() {
        val cmd = FormatterCommandBuilder.build(FormatterContext(root, null), file, emptyMap())
        assertEquals(root, cmd.workDir)
    }

    @Test
    fun `node_modules bin is prepended to an existing PATH`() {
        val ctx = FormatterContext(root, Paths.get("/repo/node_modules/.bin"))
        val cmd = FormatterCommandBuilder.build(ctx, file, mapOf("PATH" to "/usr/bin:/bin"))
        assertEquals("/repo/node_modules/.bin:/usr/bin:/bin", cmd.environment["PATH"])
    }

    @Test
    fun `node_modules bin becomes PATH when the base env has none`() {
        val ctx = FormatterContext(root, Paths.get("/repo/node_modules/.bin"))
        val cmd = FormatterCommandBuilder.build(ctx, file, emptyMap())
        assertEquals("/repo/node_modules/.bin", cmd.environment["PATH"])
    }

    @Test
    fun `base environment entries pass through untouched`() {
        val cmd = FormatterCommandBuilder.build(
            FormatterContext(root, null), file, mapOf("PATH" to "/usr/bin", "HOME" to "/Users/dev"),
        )
        assertEquals("/usr/bin", cmd.environment["PATH"])
        assertEquals("/Users/dev", cmd.environment["HOME"])
    }
}
```

**Step 2: Run — expect compile failure**

Run: `./gradlew test --tests "com.innogames.haxeformatter.FormatterCommandBuilderTest"`
Expected: FAILED — unresolved reference `FormatterCommandBuilder`.

**Step 3: Minimal implementation**

```kotlin
package com.innogames.haxeformatter

import java.io.File
import java.nio.file.Path

/**
 * Builds the CLI invocation (design §5.4):
 *   haxelib run formatter --stdin -s <absoluteFilePath>
 * cwd = configRoot; PATH gets <root>/node_modules/.bin prepended (lix haxeshim).
 * The base env is injected (production: EnvironmentUtil.getEnvironmentMap()).
 */
object FormatterCommandBuilder {
    fun build(context: FormatterContext, filePath: Path, baseEnv: Map<String, String>): FormatterCommand {
        val env = baseEnv.toMutableMap()
        val bin = context.nodeBinDir
        if (bin != null) {
            val existing = env["PATH"].orEmpty()
            env["PATH"] = if (existing.isEmpty()) bin.toString()
                          else "$bin${File.pathSeparator}$existing"
        }
        return FormatterCommand(
            argv = listOf("haxelib", "run", "formatter", "--stdin", "-s", filePath.toAbsolutePath().toString()),
            workDir = context.configRoot,
            environment = env,
        )
    }
}
```

**Step 4: Run — expect pass**

Run: `./gradlew test --tests "com.innogames.haxeformatter.FormatterCommandBuilderTest"`
Expected: PASS (5 tests).

**Step 5: Commit**

```bash
git add src/main/kotlin/com/innogames/haxeformatter/FormatterCommandBuilder.kt \
        src/test/kotlin/com/innogames/haxeformatter/FormatterCommandBuilderTest.kt
git commit -m "feat: FormatterCommandBuilder with PATH prepend for lix shims (TDD)"
```

**Success criteria:** all builder tests green.

---

### Task 7: CliFormatterExecutor — real process spawn, UTF-8 pipes, destroy

**Files:**
- Create: `src/main/kotlin/com/innogames/haxeformatter/CliFormatterExecutor.kt`
- Test: `src/test/kotlin/com/innogames/haxeformatter/CliFormatterExecutorTest.kt`

These tests exercise real tiny POSIX processes (`/bin/cat`, `/bin/sh`) — fine, the plugin is macOS-only (D3). They are plain JUnit (no IDE fixture); `GeneralCommandLine` from the platform jars works in this context.

**Step 1: Write the failing test**

```kotlin
package com.innogames.haxeformatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import kotlin.concurrent.thread

class CliFormatterExecutorTest {

    private val executor = CliFormatterExecutor()
    private val cwd = Paths.get(System.getProperty("java.io.tmpdir"))
    private fun cmd(vararg argv: String) =
        FormatterCommand(argv.toList(), cwd, mapOf("PATH" to "/usr/bin:/bin"))

    @Test
    fun `stdin is piped to the process and stdout captured, exit 0`() {
        val result = executor.start(cmd("/bin/cat"), "class Foo {}\n").waitFor()
        assertEquals(0, result.exitCode)
        assertEquals("class Foo {}\n", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun `stderr and non-zero exit code are captured`() {
        val result = executor.start(
            cmd("/bin/sh", "-c", "cat > /dev/null; echo 'Parse error' 1>&2; exit 2"), "input",
        ).waitFor()
        assertEquals(2, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("Parse error"))
    }

    @Test
    fun `utf8 content round-trips`() {
        val text = "// überprüfe ✓ emoji 🎮\n"
        val result = executor.start(cmd("/bin/cat"), text).waitFor()
        assertEquals(text, result.stdout)
    }

    @Test
    fun `large input does not deadlock the pipes`() {
        val big = "x".repeat(2 * 1024 * 1024) + "\n" // > 64k pipe buffer
        val result = executor.start(cmd("/bin/cat"), big).waitFor()
        assertEquals(0, result.exitCode)
        assertEquals(big.length, result.stdout.length)
    }

    @Test
    fun `destroy kills a hung process and waitFor returns promptly with non-zero exit`() {
        val process = executor.start(cmd("/bin/sh", "-c", "sleep 30"), "")
        thread { Thread.sleep(200); process.destroy() }
        val start = System.currentTimeMillis()
        val result = process.waitFor()
        assertTrue("waitFor took too long", System.currentTimeMillis() - start < 10_000)
        assertNotEquals(0, result.exitCode)
    }

    @Test
    fun `working directory is respected`() {
        val result = executor.start(cmd("/bin/sh", "-c", "pwd"), "").waitFor()
        assertEquals(cwd.toRealPath().toString(), result.stdout.trim())
    }
}
```

**Step 2: Run — expect compile failure**

Run: `./gradlew test --tests "com.innogames.haxeformatter.CliFormatterExecutorTest"`
Expected: FAILED — unresolved reference `CliFormatterExecutor`.

**Step 3: Minimal implementation**

Uses `GeneralCommandLine` for env/cwd handling but plain `java.lang.Process` streams with reader threads, which is deadlock-proof for large payloads (write stdin while stdout/stderr are being drained):

```kotlin
package com.innogames.haxeformatter

import com.intellij.execution.configurations.GeneralCommandLine
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

/**
 * Real CLI invocation (design §4): GeneralCommandLine, injected environment only
 * (ParentEnvironmentType.NONE — the caller supplies EnvironmentUtil.getEnvironmentMap()),
 * UTF-8, stdin piped, stdout/stderr fully captured, destroy() for cancellation.
 */
class CliFormatterExecutor : FormatterExecutor {

    override fun start(command: FormatterCommand, stdin: String): FormatterProcess {
        val commandLine = GeneralCommandLine(command.argv)
            .withWorkDirectory(command.workDir.toFile())
            .withEnvironment(command.environment)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            .withCharset(StandardCharsets.UTF_8)

        val process = commandLine.createProcess()

        // Drain stdout/stderr concurrently, then feed stdin — never deadlocks on pipe buffers.
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readBytes() }
        val feeder = CompletableFuture.runAsync {
            process.outputStream.use { it.write(stdin.toByteArray(StandardCharsets.UTF_8)) }
        }

        return object : FormatterProcess {
            override fun waitFor(): FormatterResult {
                val exit = process.waitFor()
                runCatching { feeder.join() } // broken pipe on early exit is fine
                return FormatterResult(
                    stdout = String(stdout.join(), StandardCharsets.UTF_8),
                    stderr = String(stderr.join(), StandardCharsets.UTF_8),
                    exitCode = exit,
                )
            }

            override fun destroy() {
                process.destroyForcibly()
            }
        }
    }
}
```

**Step 4: Run — expect pass**

Run: `./gradlew test --tests "com.innogames.haxeformatter.CliFormatterExecutorTest"`
Expected: PASS (6 tests). If `createProcess()` complains about a non-existent working directory, the `cwd` in the test must exist — `java.io.tmpdir` always does.

**Step 5: Commit**

```bash
git add src/main/kotlin/com/innogames/haxeformatter/CliFormatterExecutor.kt \
        src/test/kotlin/com/innogames/haxeformatter/CliFormatterExecutorTest.kt
git commit -m "feat: CliFormatterExecutor with deadlock-free pipes and destroy (TDD)"
```

**Success criteria:** all executor tests green, including the 2 MB no-deadlock test and the destroy test.

---

## Phase 3 — The formatting service (TDD, BasePlatformTestCase)

### Task 8: HaxeExternalFormattingService with fake executor

**Files:**
- Create: `src/main/kotlin/com/innogames/haxeformatter/HaxeExternalFormattingService.kt`
- Test: `src/test/kotlin/com/innogames/haxeformatter/HaxeExternalFormattingServiceTest.kt`
- Test helper: `src/test/kotlin/com/innogames/haxeformatter/FakeFormatterExecutor.kt`

Platform-test notes (read before writing):
- Tests register the service instance **manually** on the `com.intellij.formattingService` EP. This is deliberate: the test IDE has no Haxe plugin, so `plugin.xml`'s `<depends>com.intellij.plugins.haxe</depends>` would prevent descriptor-based loading — but classes are on the classpath regardless (design §5.7: "register the service with a fake FormatterExecutor").
- Without the Haxe plugin, fixture `.hx` files are plain text — `canFormat` must therefore match by **file extension** as well as language name (design §3: "matched by language name / .hx extension string"). This is also what makes these tests possible.
- `AsyncDocumentFormattingService` runs its task synchronously in unit-test mode. If an assertion sees a stale document, pump the EDT first: `com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()`.
- If the manually registered extension loses the precedence race against `CoreFormattingService` in tests, replace `registerExtension`-style appending with `ExtensionTestUtil.maskExtensions(EP, listOf(service) + EP.extensionList, testRootDisposable)` which puts ours first (the code below already does this).

**Step 1: Write the fake executor test helper**

`src/test/kotlin/com/innogames/haxeformatter/FakeFormatterExecutor.kt`:
```kotlin
package com.innogames.haxeformatter

import java.util.concurrent.CountDownLatch

class FakeFormatterExecutor(var result: FormatterResult) : FormatterExecutor {
    var lastCommand: FormatterCommand? = null
    var lastStdin: String? = null
    var destroyed = false

    /** When set, waitFor() blocks until destroy() or the latch is released (cancellation test). */
    var blockUntil: CountDownLatch? = null

    override fun start(command: FormatterCommand, stdin: String): FormatterProcess {
        lastCommand = command
        lastStdin = stdin
        return object : FormatterProcess {
            override fun waitFor(): FormatterResult {
                blockUntil?.await()
                return result
            }
            override fun destroy() {
                destroyed = true
                blockUntil?.countDown()
            }
        }
    }
}
```

**Step 2: Write the failing platform test**

`src/test/kotlin/com/innogames/haxeformatter/HaxeExternalFormattingServiceTest.kt`:
```kotlin
package com.innogames.haxeformatter

import com.intellij.formatting.service.FormattingService
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class HaxeExternalFormattingServiceTest : BasePlatformTestCase() {

    private val ep = ExtensionPointName.create<FormattingService>("com.intellij.formattingService")
    private lateinit var fake: FakeFormatterExecutor
    private val notifications = mutableListOf<Notification>()

    private fun install(result: FormatterResult): HaxeExternalFormattingService {
        fake = FakeFormatterExecutor(result)
        val service = HaxeExternalFormattingService(fake, FormatterContextResolver()) {
            mapOf("PATH" to "/usr/bin:/bin")
        }
        // Ours first, then the existing services (CoreFormattingService stays as fallback).
        ExtensionTestUtil.maskExtensions(ep, listOf(service) + ep.extensionList, testRootDisposable)
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) { notifications += notification }
            },
        )
        return service
    }

    private fun reformat() {
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(myFixture.file, 0, myFixture.file.textLength)
        }
        UIUtil.dispatchAllInvocationEvents()
    }

    // --- canFormat -----------------------------------------------------------

    fun `test canFormat accepts hx files by extension without the Haxe plugin`() {
        val service = install(FormatterResult("", "", 0))
        myFixture.configureByText("Foo.hx", "class Foo{}")
        assertTrue(service.canFormat(myFixture.file))
    }

    fun `test canFormat rejects non-Haxe files`() {
        val service = install(FormatterResult("", "", 0))
        myFixture.configureByText("Foo.txt", "hello")
        assertFalse(service.canFormat(myFixture.file))
    }

    // --- exit-code behaviour through the real reformat pipeline ---------------

    fun `test exit 0 replaces the document with normalised stdout`() {
        install(FormatterResult("class Foo {}\n\n", "", 0))
        myFixture.configureByText("Foo.hx", "class Foo{}")
        reformat()
        assertEquals("class Foo {}\n", myFixture.editor.document.text)
        assertEmpty(notifications)
    }

    fun `test exit 1 leaves the document untouched with no notification`() {
        install(FormatterResult("class Foo{}\n", "", 1))
        myFixture.configureByText("Foo.hx", "class Foo{}")
        reformat()
        assertEquals("class Foo{}", myFixture.editor.document.text)
        assertEmpty(notifications)
    }

    fun `test exit 2 leaves the document untouched and raises a notification`() {
        install(FormatterResult("class Foo{}\n", "Parse error line 1", 2))
        myFixture.configureByText("Foo.hx", "class Foo{}")
        reformat()
        assertEquals("class Foo{}", myFixture.editor.document.text)
        assertTrue(notifications.any { it.content.contains("Parse error") })
    }

    // --- wiring: what reaches the executor ------------------------------------

    fun `test executor receives document text on stdin and the stdin flags`() {
        install(FormatterResult("class Foo {}\n\n", "", 0))
        myFixture.configureByText("Foo.hx", "class Foo{}")
        reformat()
        assertEquals("class Foo{}", fake.lastStdin)
        val argv = fake.lastCommand!!.argv
        assertEquals(listOf("haxelib", "run", "formatter", "--stdin", "-s"), argv.dropLast(1))
        assertTrue(argv.last().endsWith("Foo.hx"))
    }

    // --- D6: selection formats the whole file ---------------------------------

    fun `test selection reformat formats the whole file`() {
        install(FormatterResult("class A {}\nclass B {}\n\n", "", 0))
        myFixture.configureByText("Foo.hx", "class A{}\n<selection>class B{}</selection>")
        myFixture.performEditorAction("ReformatCode")
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("class A {}\nclass B {}\n", myFixture.editor.document.text)
    }

    // --- cancellation ----------------------------------------------------------

    fun `test cancel destroys the running process`() {
        val service = install(FormatterResult("", "", 0))
        fake.blockUntil = java.util.concurrent.CountDownLatch(1)
        myFixture.configureByText("Foo.hx", "class Foo{}")

        val request = TestFormattingRequests.forFile(myFixture.file, myFixture.editor.document.text)
        val task = service.createFormattingTask(request)!!
        val runner = kotlin.concurrent.thread { task.run() }
        // Let run() reach waitFor(), then cancel.
        Thread.sleep(200)
        assertTrue(task.cancel())
        runner.join(5_000)
        assertTrue(fake.destroyed)
    }
}
```

Also create the small stub-request helper used by the cancellation test,
`src/test/kotlin/com/innogames/haxeformatter/TestFormattingRequests.kt`:
```kotlin
package com.innogames.haxeformatter

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.FormattingMode
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.FormattingContext
import java.io.File

object TestFormattingRequests {
    fun forFile(file: PsiFile, text: String): AsyncFormattingRequest = object : AsyncFormattingRequest {
        override fun getDocumentText(): String = text
        override fun getFormattingRanges(): List<TextRange> = listOf(TextRange(0, text.length))
        override fun getIOFile(): File? = null
        override fun isQuickFormat(): Boolean = false
        override fun onTextReady(updatedText: String?) {}
        override fun onError(title: String, message: String) {}
        override fun getContext(): FormattingContext =
            FormattingContext.create(file, TextRange(0, text.length), CodeStyle.getSettings(file), FormattingMode.REFORMAT)
    }
}
```
(If `AsyncFormattingRequest` or `FormattingContext.create` signatures differ on 2025.3, adapt the stub to the actual interface — check with external navigation into the platform sources; the shape above matches 2024.3.)

**Step 3: Run — expect compile failure**

Run: `./gradlew test --tests "com.innogames.haxeformatter.HaxeExternalFormattingServiceTest"`
Expected: FAILED — unresolved reference `HaxeExternalFormattingService`.

**Step 4: Implement the service**

`src/main/kotlin/com/innogames/haxeformatter/HaxeExternalFormattingService.kt`:
```kotlin
package com.innogames.haxeformatter

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.psi.PsiFile
import com.intellij.util.EnvironmentUtil
import java.nio.file.Paths
import java.util.EnumSet

/**
 * Routes Reformat Code for Haxe files through the haxe-formatter CLI (design §3, §4).
 * - FORMAT_FRAGMENTS declared, ranges ignored: selection ⌘⌥L formats the whole file (D6).
 * - AD_HOC_FORMATTING deliberately NOT declared: never runs on typing/autosave.
 * - No LanguageFormattingRestriction anywhere (would grey out the action — design §3).
 * - request.getIOFile() deliberately unused: config discovery and excludes regexes key
 *   off the REAL path (design §5.1), which we pass via -s.
 */
class HaxeExternalFormattingService(
    private val executor: FormatterExecutor,
    private val resolver: FormatterContextResolver,
    private val baseEnvProvider: () -> Map<String, String>,
) : AsyncDocumentFormattingService() {

    /** Used by the platform when instantiating from plugin.xml. */
    @Suppress("unused")
    constructor() : this(
        CliFormatterExecutor(),
        FormatterContextResolver(),
        { EnvironmentUtil.getEnvironmentMap() }, // macOS login-shell PATH capture (design §5.4)
    )

    override fun getFeatures(): Set<FormattingService.Feature> =
        EnumSet.of(FormattingService.Feature.FORMAT_FRAGMENTS)

    override fun canFormat(file: PsiFile): Boolean =
        file.language.id == "Haxe" || file.name.endsWith(".hx", ignoreCase = true)

    override fun getName(): String = "Haxe Formatter"

    override fun getNotificationGroupId(): String = ResultPolicy.NOTIFICATION_TITLE

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        // EDT — snapshot only, no I/O (design §3).
        val file = request.context.containingFile
        val virtualFile = file.virtualFile ?: return null // let the built-in engine handle scratch-like files
        val documentText = request.documentText
        val filePath = Paths.get(virtualFile.path)

        return object : FormattingTask {
            @Volatile private var process: FormatterProcess? = null
            @Volatile private var cancelled = false

            override fun run() { // pooled background thread
                try {
                    val context = resolver.resolve(filePath)
                    val command = FormatterCommandBuilder.build(context, filePath, baseEnvProvider())
                    val started = executor.start(command, documentText)
                    process = started
                    if (cancelled) {
                        started.destroy()
                        return
                    }
                    when (val outcome = ResultPolicy.decide(documentText, started.waitFor())) {
                        is FormatOutcome.ApplyText -> request.onTextReady(outcome.text)
                        FormatOutcome.NoChange -> request.onTextReady(null)
                        is FormatOutcome.Error -> {
                            resolver.invalidate(filePath) // stale root? re-discover next time (design §4)
                            request.onError(outcome.title, outcome.message)
                        }
                    }
                } catch (e: Exception) {
                    if (!cancelled) {
                        resolver.invalidate(filePath)
                        request.onError(ResultPolicy.NOTIFICATION_TITLE, e.message ?: e.javaClass.simpleName)
                    }
                }
            }

            override fun cancel(): Boolean {
                cancelled = true
                process?.destroy()
                return true
            }

            override fun isRunUnderProgress(): Boolean = true
        }
    }
}
```

**Step 5: Run — expect pass**

Run: `./gradlew test --tests "com.innogames.haxeformatter.HaxeExternalFormattingServiceTest"`
Expected: PASS (8 tests). Known flex points if the platform disagrees (fix the test, not the design):
- stale document → add/keep the `UIUtil.dispatchAllInvocationEvents()` pump;
- precedence → the `maskExtensions` ordering shown already handles it;
- `FormattingTask` here is `AsyncDocumentFormattingService.FormattingTask` — use the exact nested type the base class declares.

**Step 6: Run the whole suite**

Run: `./gradlew test`
Expected: all Task 4–8 tests PASS.

**Step 7: Commit**

```bash
git add src/main/kotlin/com/innogames/haxeformatter/HaxeExternalFormattingService.kt \
        src/test/kotlin/com/innogames/haxeformatter/
git commit -m "feat: HaxeExternalFormattingService (AsyncDocumentFormattingService) with fake-executor platform tests (TDD)"
```

**Success criteria:** full `./gradlew test` green; document replaced only on exit 0; untouched on 1/2; notification raised on 2; selection formats whole file; cancel destroys the process.

---

### Task 9: Register the service in plugin.xml; prepare runIde smoke setup

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`

**Step 1: Add the formattingService registration**

In `plugin.xml`, extend the `<extensions>` block to:
```xml
    <extensions defaultExtensionNs="com.intellij">
        <formattingService implementation="com.innogames.haxeformatter.HaxeExternalFormattingService"/>
        <notificationGroup id="Haxe Formatter" displayType="BALLOON"/>
    </extensions>
```

**Step 2: Verify build + descriptor**

Run:
```bash
./gradlew build buildPlugin
unzip -p build/distributions/haxe-format-intellij-0.1.0.zip '*/lib/*.jar' > /dev/null 2>&1; \
unzip -o build/distributions/haxe-format-intellij-0.1.0.zip -d /private/tmp/claude-503/-Users-tobias-jansing-coding-projects-haxe-format-intellij/8734f0e5-bd53-4a3c-959c-8f0cecc6bd88/scratchpad/plugin-zip
```
Then inspect the packaged descriptor (it is inside the plugin jar):
```bash
unzip -p /private/tmp/claude-503/-Users-tobias-jansing-coding-projects-haxe-format-intellij/8734f0e5-bd53-4a3c-959c-8f0cecc6bd88/scratchpad/plugin-zip/*/lib/haxe-format-intellij-0.1.0.jar META-INF/plugin.xml
```
Expected: `<formattingService ...HaxeExternalFormattingService"/>`, `<depends>com.intellij.plugins.haxe</depends>`, and the build-patched `<idea-version since-build="253" until-build="261.*"/>`.

**Step 3 (optional but recommended): manual runIde smoke with the real fork**

```bash
mkdir -p libs
curl -L -o libs/intellij-haxe-fork.zip \
  https://github.com/CaroStobbe/intellij-haxe/releases/download/v1.8.1-fork.2-dev.5/intellij-haxe-2026.1.zip
./gradlew runIde
```
(`libs/` is gitignored; the `localPlugin` block from Task 3 picks the zip up.) In the sandbox IDE: open any Haxe project with `hxformat.json`, hit ⌘⌥L on a `.hx` file, confirm CLI-formatted output and that an intentional syntax error produces a balloon and no buffer change. This is a smoke check, not a gate — full manual checklist is Task 14.

**Step 4: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat: register formattingService in plugin.xml"
```

**Success criteria:** `buildPlugin` zip contains the descriptor with the formattingService entry, the Haxe depends, and since/until 253/261.*.

---

## Phase 4 — Integration tests (real formatter; opt-in)

### Task 10: Fixture Haxe project + goldens

**Files:**
- Create: `src/integrationTest/fixtures/project/hxformat.json`
- Create: `src/integrationTest/fixtures/project/src/Unformatted.hx`
- Create: `src/integrationTest/fixtures/project/src/excluded/ExcludedFile.hx`
- Create: `src/integrationTest/fixtures/project/src/FormatterOff.hx`
- Create: `src/integrationTest/fixtures/project/src/SyntaxError.hx`
- Create: `src/integrationTest/fixtures/golden/Unformatted.hx.golden`
- Create: `src/integrationTest/fixtures/golden/FormatterOff.hx.golden`
- Create: `src/integrationTest/fixtures/setup.sh`
- Create: `src/integrationTest/fixtures/README.md`

**Step 1: Write the fixture project**

`hxformat.json` (non-default settings so formatting is observable, plus an exclusion):
```json
{
    "excludes": ["src/excluded/.*"],
    "indentation": { "character": "    " },
    "lineEnds": { "leftCurly": "both" }
}
```

`src/Unformatted.hx` — deliberately mis-formatted:
```haxe
class Unformatted{
    public function new(){}
    public static function add(a:Int,b:Int):Int{return a+b;}
}
```

`src/excluded/ExcludedFile.hx` — mis-formatted, matched by `excludes` (expects CLI exit 1):
```haxe
class ExcludedFile{   public function new(){}   }
```

`src/FormatterOff.hx` — a `@formatter:off` region (markers must have no trailing whitespace, design §5.3):
```haxe
class FormatterOff {
    // @formatter:off
    static var matrix = [1,0,0,
                         0,1,0,
                         0,0,1];
    // @formatter:on
    public function new() {}
}
```

`src/SyntaxError.hx` — must make the formatter exit 2:
```haxe
class SyntaxError {
    public function new( {}
}
```

**Step 2: Write `setup.sh` (installs lix + formatter into the fixture; artifacts are gitignored)**

```bash
#!/usr/bin/env bash
# Prepares the integration-test fixture. Requires: macOS, node >= 18, npm.
set -euo pipefail
cd "$(dirname "$0")/project"
npm install --no-save lix
npx lix scope create || true          # writes .haxerc (gitignored)
npx lix install haxe 4.3.6            # or the team's pinned Haxe version
npx lix install haxelib:formatter#1.18.0
echo "Fixture ready. Probe:"
echo 'class X {}' | ./node_modules/.bin/haxelib run formatter --stdin -s "$(pwd)/src/Unformatted.hx" || true
```
Make it executable: `chmod +x src/integrationTest/fixtures/setup.sh`.
Alternative environments (document in the fixture README): stock haxelib works too — `brew install haxe && haxelib install formatter 1.18.0`; the tests only need `haxelib run formatter` resolvable through the same env the plugin would build.

**Step 3: Generate the goldens with the real CLI (one-time, eyeball, commit)**

Run:
```bash
src/integrationTest/fixtures/setup.sh
cd src/integrationTest/fixtures/project
cat src/Unformatted.hx | ./node_modules/.bin/haxelib run formatter --stdin -s "$(pwd)/src/Unformatted.hx" \
  > ../golden/Unformatted.hx.golden
cat src/FormatterOff.hx | ./node_modules/.bin/haxelib run formatter --stdin -s "$(pwd)/src/FormatterOff.hx" \
  > ../golden/FormatterOff.hx.golden
```
**Important:** the raw CLI output includes the extra `Sys.println` newline; decide now whether goldens store the *raw* CLI bytes (then tests normalise before compare) — recommended, since it also documents the raw behaviour. Eyeball both goldens: `Unformatted` must show 4-space indent, spaces after commas, formatted body; `FormatterOff` must keep the matrix region byte-identical. Cross-check against VS Code output on the same input (design §5.7: byte-compare with the VS Code/CI toolchain).

**Step 4: Commit (fixtures + goldens only; installed artifacts are gitignored from Task 3)**

```bash
git add src/integrationTest/fixtures/
git commit -m "test: integration fixture Haxe project and CLI-generated goldens"
```

**Success criteria:** fixture project committed; `setup.sh` runs clean on a macOS+node machine; goldens generated by the real formatter and eyeballed.

---

### Task 11: Integration tests (macOS + node + lix required — opt-in `./gradlew integrationTest`)

**Files:**
- Test: `src/test/kotlin/com/innogames/haxeformatter/it/FormatterCliIT.kt`
- Test: `src/test/kotlin/com/innogames/haxeformatter/it/TrailingNewlineIT.kt`
- Test: `src/test/kotlin/com/innogames/haxeformatter/it/LibpathProbeIT.kt`

These are plain JUnit tests in package `...haxeformatter.it` — excluded from `./gradlew test`, run by `./gradlew integrationTest` (wiring from Task 3). **They require a real environment: macOS, node, lix (or stock haxelib), and Task 10's `setup.sh` having been run.** Every test guards itself with JUnit `Assume` so an unprepared machine skips instead of fails. They cover the two deferred design-§7 checklist items (see Task 2 Step 3).

**Step 1: Write the shared harness + main IT suite**

`FormatterCliIT.kt`:
```kotlin
package com.innogames.haxeformatter.it

import com.innogames.haxeformatter.CliFormatterExecutor
import com.innogames.haxeformatter.FormatterCommandBuilder
import com.innogames.haxeformatter.FormatterContextResolver
import com.innogames.haxeformatter.FormatterResult
import com.innogames.haxeformatter.ResultPolicy
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object ItEnv {
    val fixtureRoot: Path = Paths.get("src/integrationTest/fixtures/project").toAbsolutePath()
    val goldenDir: Path = Paths.get("src/integrationTest/fixtures/golden").toAbsolutePath()
    val ready: Boolean =
        System.getenv("HAXE_FORMATTER_IT") == "1" &&
        Files.isExecutable(fixtureRoot.resolve("node_modules/.bin/haxelib"))

    fun run(fileRelPath: String, stdin: String): FormatterResult {
        val file = fixtureRoot.resolve(fileRelPath)
        val ctx = FormatterContextResolver().resolve(file)
        val cmd = FormatterCommandBuilder.build(ctx, file, System.getenv())
        return CliFormatterExecutor().start(cmd, stdin).waitFor()
    }

    fun read(p: Path): String = Files.readString(p)
}

class FormatterCliIT {

    @Before
    fun requireEnvironment() {
        // Requires macOS + node + lix and fixtures/setup.sh having been run (Task 10).
        assumeTrue("IT environment not prepared — run src/integrationTest/fixtures/setup.sh", ItEnv.ready)
    }

    @Test
    fun `golden - unformatted file formats to the exact CLI golden`() {
        val input = ItEnv.read(ItEnv.fixtureRoot.resolve("src/Unformatted.hx"))
        val result = ItEnv.run("src/Unformatted.hx", input)
        assertEquals("", result.stderr)
        assertEquals(0, result.exitCode)
        assertEquals(ItEnv.read(ItEnv.goldenDir.resolve("Unformatted.hx.golden")), result.stdout)
    }

    @Test
    fun `excluded file exits 1 and echoes input (silent no-op path)`() {
        val input = ItEnv.read(ItEnv.fixtureRoot.resolve("src/excluded/ExcludedFile.hx"))
        val result = ItEnv.run("src/excluded/ExcludedFile.hx", input)
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `formatter-off region survives byte-identically`() {
        val input = ItEnv.read(ItEnv.fixtureRoot.resolve("src/FormatterOff.hx"))
        val result = ItEnv.run("src/FormatterOff.hx", input)
        assertEquals(0, result.exitCode)
        assertEquals(ItEnv.read(ItEnv.goldenDir.resolve("FormatterOff.hx.golden")), result.stdout)
    }

    @Test
    fun `syntax error exits 2 with diagnostics on stderr and echoes input on stdout`() {
        val input = ItEnv.read(ItEnv.fixtureRoot.resolve("src/SyntaxError.hx"))
        val result = ItEnv.run("src/SyntaxError.hx", input)
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `idempotency - formatting the formatted output changes nothing (guards trailing-newline bug)`() {
        val input = ItEnv.read(ItEnv.fixtureRoot.resolve("src/Unformatted.hx"))
        val once = ResultPolicy.normaliseTrailingNewline(ItEnv.run("src/Unformatted.hx", input).stdout)
        val twiceResult = ItEnv.run("src/Unformatted.hx", once)
        assertEquals(0, twiceResult.exitCode)
        val twice = ResultPolicy.normaliseTrailingNewline(twiceResult.stdout)
        assertEquals(once, twice)                       // stable output
        assertEquals(ResultPolicy.decide(once, twiceResult), com.innogames.haxeformatter.FormatOutcome.NoChange)
    }
}
```

**Step 2: Write `TrailingNewlineIT.kt` — design §7 item 2, pinned empirically**

```kotlin
package com.innogames.haxeformatter.it

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Design §7: "Empirically byte-diff --stdin output vs input for the no-change case
 * (trailing newline, Neko vs Node)." This pins ResultPolicy.normaliseTrailingNewline's
 * rule (strip exactly one '\n') against the real CLI. If this test fails, the rule —
 * and its unit test in ResultPolicyTest — must be corrected to match reality.
 */
class TrailingNewlineIT {

    @Before
    fun requireEnvironment() = assumeTrue(ItEnv.ready)

    @Test
    fun `no-change input comes back as input plus exactly one newline`() {
        val formatted = ItEnv.read(ItEnv.goldenDir.resolve("Unformatted.hx.golden"))
            .let(com.innogames.haxeformatter.ResultPolicy::normaliseTrailingNewline)
        val result = ItEnv.run("src/Unformatted.hx", formatted)
        assertEquals(0, result.exitCode)
        assertEquals(formatted + "\n", result.stdout) // THE empirical claim from design §5.3
    }
}
```
Note: under lix with node on PATH the CLI runs via Node (`run.js`); to also cover the Neko path, temporarily run with node removed from PATH (manual, documented in the fixture README — optional, informational).

**Step 3: Write `LibpathProbeIT.kt` — design §7 item 3 (optional fast path, informational)**

```kotlin
package com.innogames.haxeformatter.it

import com.innogames.haxeformatter.CliFormatterExecutor
import com.innogames.haxeformatter.FormatterCommand
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Design §7: "Confirm `haxelib libpath formatter` works under the lix haxeshim" —
 * only needed for the optional post-v1 direct-node fast path (design §5.4). Informational.
 */
class LibpathProbeIT {

    @Before
    fun requireEnvironment() = assumeTrue(ItEnv.ready)

    @Test
    fun `haxelib libpath formatter resolves under the lix shim`() {
        val cmd = FormatterCommand(
            argv = listOf("haxelib", "libpath", "formatter"),
            workDir = ItEnv.fixtureRoot,
            environment = System.getenv() + mapOf(
                "PATH" to "${ItEnv.fixtureRoot.resolve("node_modules/.bin")}:${System.getenv("PATH")}",
            ),
        )
        val result = CliFormatterExecutor().start(cmd, "").waitFor()
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.trim().isNotEmpty())
        // Record the resolved path in the task notes — input for the future fast path.
    }
}
```

**Step 4: Run — first the guard, then for real**

Run without env prepared (or on a machine without the fixture set up):
```bash
./gradlew test
```
Expected: PASS, `it.*` tests not executed (excluded by the `test` task filter).

Run for real (macOS + node, after `src/integrationTest/fixtures/setup.sh`):
```bash
./gradlew integrationTest
```
Expected: all IT tests PASS (or SKIPPED with the assume message if the fixture is not prepared). If `TrailingNewlineIT` fails: reality disagrees with the design's `Sys.println` claim — fix `ResultPolicy.normaliseTrailingNewline` + `ResultPolicyTest` to the observed behaviour and re-run everything (this is exactly why the check exists).

**Step 5: Commit**

```bash
git add src/test/kotlin/com/innogames/haxeformatter/it/
git commit -m "test: opt-in integration suite against the real formatter (goldens, idempotency, §7 checks)"
```

**Success criteria:** `./gradlew test` stays green everywhere; `./gradlew integrationTest` green on a prepared macOS machine; §7 items 2 and 3 empirically confirmed (or ResultPolicy corrected).

---

## Phase 5 — Distribution & documentation

### Task 12: GitHub Actions — build on tag, attach zip as release asset

**Files:**
- Create: `.github/workflows/build.yml`

**Step 1: Write the workflow**

```yaml
name: Build

on:
  push:
    branches: [main]
    tags: ['v*']
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest # build + unit/platform tests are platform-neutral (fake executor)
    permissions:
      contents: write # needed for release upload on tags
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v4

      # integrationTest is NOT run here: it needs macOS + node + lix (see fixtures/README.md)
      - name: Test and build plugin
        run: ./gradlew --no-daemon check buildPlugin

      - name: Upload zip artifact
        uses: actions/upload-artifact@v4
        with:
          name: plugin-zip
          path: build/distributions/*.zip

      - name: Attach zip to GitHub release (tags only)
        if: startsWith(github.ref, 'refs/tags/v')
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: gh release create "$GITHUB_REF_NAME" build/distributions/*.zip --generate-notes
```

Note: `POSIX /bin/cat`-based `CliFormatterExecutorTest` runs fine on ubuntu. If any test turns out mac-specific, gate it with `Assume.assumeTrue(System.getProperty("os.name").contains("Mac"))` rather than switching the runner.

**Step 2: Validate the workflow syntax locally**

Run:
```bash
gh api /repos/{owner}/{repo} > /dev/null 2>&1 && echo "gh ok" || echo "gh not configured (fine — syntax check only)"
ruby -ryaml -e 'YAML.load_file(".github/workflows/build.yml"); puts "yaml ok"' \
  || python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/build.yml')); print('yaml ok')"
```
Expected: `yaml ok`.

**Step 3: Commit**

```bash
git add .github/workflows/build.yml
git commit -m "ci: build + test on push, attach plugin zip to release on v* tags"
```

**Step 4: Verify end-to-end (after the user pushes)**

When the user pushes `main` and later a `v0.1.0` tag: Actions run must be green, and the tag run must create a release with the zip attached. (Pushing is the user's call — do not run git push.)

**Success criteria:** workflow YAML valid and committed; on the first real tag, a release exists with `haxe-format-intellij-<version>.zip` attached.

---

### Task 13: README + distribution & migration documentation

**Files:**
- Modify: `README.md`

**Step 1: Write the README** with these sections (content per design §5.6, §5.8, §6):

1. **What this is** — one paragraph: routes ⌘⌥L for `.hx` through haxe-formatter (`hxformat.json`), byte-identical to VS Code/CI; macOS only; works with any Haxe repo (lix or stock haxelib) and any worktree.
2. **Requirements** — IntelliJ IDEA 2025.3–2026.1 (build 253–261.*), the team's intellij-haxe fork (`com.intellij.plugins.haxe`) installed, `haxelib`/node reachable from a login shell.
3. **Install** — via the custom plugin repository URL `https://carostobbe.github.io/intellij-haxe/updatePlugins.xml` (once this plugin's entry is added — see Publishing); fallback: Install Plugin from Disk with the release zip.
4. **Behaviour notes** — selection ⌘⌥L formats the whole file (D6); excluded files / `disableFormatting` are silent no-ops; syntax errors show a balloon and never touch the buffer; live type-and-indent stays with the IDE engine and converges on next ⌘⌥L; bulk directory reformat spawns one process per file (acceptable, explicit action); leave "Reformat code on save" (Actions on Save) **off**; a brand-new never-saved file cannot be formatted (the `-s` path must exist on disk).
5. **Publishing a release (maintainers)** — tag `vX.Y.Z` → CI attaches the zip to the GitHub release. Then the **manual step, outside this repo**: add/update this plugin's `<plugin>` entry in the existing `carostobbe.github.io/intellij-haxe` repo's `updatePlugins.xml` (design D5, recommended option — one repository URL serves both plugins). Include the exact template:
   ```xml
   <plugin id="com.innogames.haxeformatter"
           url="https://github.com/<org>/haxe-format-intellij/releases/download/vX.Y.Z/haxe-format-intellij-X.Y.Z.zip"
           version="X.Y.Z">
       <idea-version since-build="253" until-build="261.*"/>
   </plugin>
   ```
   Keep since/until in lockstep with the intellij-haxe fork's range whenever either is bumped (D7).
6. **MIGRATION (adopting projects — documentation only, applies outside this repo)** — per design §5.8:
   - Remove any format-on-save File Watcher: delete/prune `.idea/watcherTasks.xml` in the project **and in every worktree**; remove it from any shared/template `.idea` config or setup automation that seeds it.
   - Remove any setup-flow/onboarding step that installs that watcher.
   - Keep any formatter wrapper script only if pre-commit/CI/docs still call it; the plugin does not.
   - Add to onboarding: the plugin arrives via the already-configured custom repository (or manual zip install).
   - Optionally disable the bundled File Watchers plugin if nothing else uses it.
7. **Development** — `./gradlew test` (unit + platform), `./gradlew integrationTest` (opt-in; macOS + node + lix; run `src/integrationTest/fixtures/setup.sh` first), `./gradlew runIde` (drop the fork zip into `libs/` first, see Task 9), `./gradlew buildPlugin`.

**Step 2: Verify**

Proofread; confirm every claim matches the implemented behaviour (superpowers:verification-before-completion — e.g. actually re-run `./gradlew test` and `./gradlew buildPlugin` if unsure).

**Step 3: Commit**

```bash
git add README.md
git commit -m "docs: usage, publishing (updatePlugins.xml entry), and File Watcher migration"
```

**Success criteria:** README covers install, behaviour, publishing (incl. the manual updatePlugins.xml step marked as outside this repo), and the File Watcher migration checklist (documentation-only).

---

## Phase 6 — Final verification

### Task 14: Definition of done — full verification pass

**Files:** none (verification only).

**Step 1: Clean full build and test run**

Run:
```bash
./gradlew clean check buildPlugin
```
Expected: `BUILD SUCCESSFUL`; all unit + platform tests pass; `build/distributions/haxe-format-intellij-0.1.0.zip` exists.

**Step 2: Packaged descriptor spot-check**

Re-run Task 9 Step 2's unzip inspection. Expected: formattingService + notificationGroup + `<depends>com.intellij.plugins.haxe</depends>` + since/until `253`/`261.*` present in the packaged plugin.xml.

**Step 3: Integration suite (on a prepared macOS machine)**

Run:
```bash
src/integrationTest/fixtures/setup.sh   # once per machine
./gradlew integrationTest
```
Expected: all IT tests green — goldens byte-match, idempotency holds, trailing-newline rule confirmed, exit 1/2 paths confirmed, `haxelib libpath formatter` probe recorded.

**Step 4: Manual smoke checklist (design §5.7 item 4 — human, in `runIde` or a real IDE install)**

- [ ] Autosave ON; start a reformat on a large file and type during it → DocumentMerger path: edits survive, no corruption.
- [ ] ⌘Z after a reformat restores the pre-format text in one undo step.
- [ ] Project-view right-click → Reformat Code on a directory of `.hx` files → all formatted, one process per file, no hang.
- [ ] Second git worktree of the same repo → formatting picks up *that* worktree's `hxformat.json`/`.haxerc`/`node_modules` (different config proves it).
- [ ] Intentional syntax error → balloon notification, buffer untouched.
- [ ] File in an `excludes` path → silent no-op.
- [ ] Selection + ⌘⌥L → whole file formatted (D6).

**Step 5: Definition of done — all boxes ticked**

- [ ] `./gradlew clean check buildPlugin` green on a clean checkout.
- [ ] All unit tests (ResultPolicy, Resolver, CommandBuilder, CliExecutor) green.
- [ ] All platform tests (service with fake executor, incl. selection→whole-file and cancellation) green.
- [ ] Integration suite green on a prepared macOS+node+lix machine; idempotency + trailing-newline empirically pinned (§7 items 2–3 closed).
- [ ] Plugin zip produced; packaged plugin.xml carries the Haxe depends, formattingService, notificationGroup, since/until mirroring the fork (§7 item 1 closed in Task 1).
- [ ] CI workflow committed; first tagged release attaches the zip (verified after the user pushes a tag).
- [ ] README documents install, behaviour caveats, the manual updatePlugins.xml publishing step, and the File Watcher migration (docs-only).
- [ ] Manual smoke checklist executed and clean.

**Step 6: Wrap up the branch/work**

REQUIRED SUB-SKILL: superpowers:finishing-a-development-branch — present merge/PR/cleanup options to the user. Do not push or tag without the user's say-so.

---

## Out of scope (explicitly deferred — do not build now)

- Direct-node fast path (`node <libdir>/run.js`) — only if measured latency annoys (design §5.4); `LibpathProbeIT` collects the needed fact.
- Windows/Linux support (D3: macOS only; Linux likely works, untested).
- Settings UI, per-project toggles, formatter-version pinning UI — YAGNI.
- Publishing to JetBrains Marketplace — distribution is the custom repository (D5).
