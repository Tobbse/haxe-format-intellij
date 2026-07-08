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
        // IC is no longer published since 2025.3 (253) — use the unified IntelliJ IDEA distribution
        intellijIdea(providers.gradleProperty("platformVersion").get())
        testFramework(TestFrameworkType.Platform)

        // Optional: the team's intellij-haxe fork for runIde smoke testing (Task 9).
        // Not required for compilation or tests — we reference no Haxe classes (design §5.2).
        val haxeFork = layout.projectDirectory.file("libs/intellij-haxe-fork.zip")
        if (haxeFork.asFile.exists()) {
            localPlugin(haxeFork.asFile)
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
    // Reuse the platform-wired test classpath: sourceSets.test.runtimeClasspath alone lacks
    // the Kotlin stdlib + platform jars (the IntelliJ plugin only wires the built-in test task).
    classpath = tasks.test.get().classpath
    filter { includeTestsMatching("com.innogames.haxeformatter.it.*") }
    environment("HAXE_FORMATTER_IT", "1")
    outputs.upToDateWhen { false }
}
