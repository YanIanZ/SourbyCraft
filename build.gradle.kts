import java.time.Instant
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java // TODO java launcher tasks
    id("io.canvasmc.weaver.patcher") version "2.4.5"
}

paperweight {
    filterPatches = false
    // SourbyCraft-on-Canvas (feat/canvas-engine, PR #12) — "Path B": build on Canvas's OWN weaver
    // toolchain instead of forcing our paperweight fork (sourbypatcher) to consume Canvas.
    // sourbypatcher got 90% there but hit a hard wall: Canvas's weaver sequences ATs + base
    // patches differently than paperweight does, and reconciling that generically caused git-am
    // conflicts (TickThread.java/CraftServer.java). `upstreams.canvas { ... }` is a BUILT-IN
    // convenience on weaver's own PaperweightPatcherExtension (alongside `paper` and `folia`),
    // added by CanvasMC specifically so downstream forks of Canvas can consume it — it defaults
    // `applyUpstreamNested = true`, meaning the checked-out Canvas repo (which itself applies
    // `io.canvasmc.weaver.patcher` with `upstreams.paper { ... }`) is resolved recursively by
    // weaver's native nested-build mechanism: the exact same code path Canvas already uses
    // successfully to consume Paper, one level further down.
    upstreams.canvas {
        ref = providers.gradleProperty("canvasRef")

        println("Upstream commit ref: " + ref.get())

        patchFile {
            path = "canvas-server/build.gradle.kts"
            outputFile = file("sourbycraft-server/build.gradle.kts")
            patchFile = file("sourbycraft-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "canvas-api/build.gradle.kts"
            outputFile = file("sourbycraft-api/build.gradle.kts")
            patchFile = file("sourbycraft-api/build.gradle.kts.patch")
        }
        // Two levels deep (sourbycraft -> canvas -> paper): paper-api does not exist in Canvas's
        // raw checkout, only as CANVAS's OWN nested-build output, so this must be a patchRepo
        // (wires a proper task dependency on that nested output) rather than a plain patchDir
        // (which only reads a literal path inside the immediate "canvas" checkout).
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("sourbycraft-api/paper-patches")
            outputDir = file("paper-api")
        }
        // One level deep: canvas-api is a literal folder in Canvas's raw checkout, so a plain
        // patchDir is correct (mirrors the server-side canvasServer patchDir wired inside
        // sourbycraft-server/build.gradle.kts.patch's own forks.register("sourbycraft") block).
        patchDir("canvasApi") {
            upstreamPath = "canvas-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("sourbycraft-api/canvas-patches")
            outputDir = file("canvas-api")
        }
    }
}

// NOTE: the SourbyLoader lib-slimming optimization (externalLib(...) DSL + SlimPaperclipJar task)
// was a custom addition to sourbypatcher's OWN fork of paperweight-core and has no equivalent in
// weaver. Dropped for this benchmark build (goal is a small utilities-only jar to compare Canvas
// vs the old Folia base, not to re-ship the lib-slimming optimization) — createPaperclipJar ships
// a normal fat jar. Revisit porting externalLib/SlimPaperclipJar onto weaver if this graduates
// past a benchmark.

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"
val canvasMavenPublicUrl = "https://maven.canvasmc.io/public/"

// SourbyCraft — resolve the human-facing suffix version once (banner + /ver read this
// through META-INF/sourbycraft-build.properties). Branch is read via providers.exec so
// the value is correct on CI and locally; the writeBuildInfo task opts itself out of the
// config cache because the branch provider cannot be serialised into a doLast closure.
val sourbycraftBranchProvider: Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

val sourbycraftSuffixProvider: Provider<String> = sourbycraftBranchProvider.map { branch ->
    val releaseVersionFull = providers.gradleProperty("releaseVersion").getOrElse("dev")
    val releaseMajor = releaseVersionFull.substringBefore('-')
    val codename = providers.gradleProperty("codename").getOrElse("dev")
    val suffix = when {
        branch.contains("experimental") || branch.contains("feat") -> "EXP"
        branch.startsWith("release/") -> "REL"
        branch.contains("-dev") || branch.contains("develop") -> "DEV"
        codename == "dev" -> "DEV"
        else -> "REL"
    }
    when (suffix) {
        "EXP" -> "$releaseMajor-EXP"
        "REL" -> "$releaseMajor-REL"
        else -> "$releaseVersionFull-DEV"
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven(canvasMavenPublicUrl)
        maven { url = uri("${rootDir}/sourby-maven") }
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }

    // SourbyCraft — emit META-INF/sourbycraft-build.properties so BuildInfo.load()
    // (banner + /ver) reports the real version, MC version and build timestamp instead
    // of the "unknown"/"dev" fallbacks. Only sourbycraft-server bundles it.
    val thisProjectName = project.name
    val internalVersionProvider = sourbycraftSuffixProvider
    val writeBuildInfoTask = tasks.register("writeBuildInfo") {
        val mcVersion = providers.gradleProperty("mcVersion").getOrElse("unknown")
        // SourbyCraft-on-Canvas build number: gradle.properties `sourbyBuild=4` -> effective id
        // "4c" (c = Canvas base, replacing the old "f" = Folia suffix). Surfaced as `build=4c`
        // here so BuildInfo (banner + /ver) can render "26.2-EXP (build 4c)", matching the
        // sourbycraft-server jar manifest's own Implementation-Version (same "c" suffix, see
        // sourbycraft-server/build.gradle.kts.patch). Bump sourbyBuild per release -> 5, 6, ...
        val sourbyBuild = providers.gradleProperty("sourbyBuild").getOrElse("1").trim() + "c"
        val outFile = layout.buildDirectory.file("generated-resources/META-INF/sourbycraft-build.properties")

        inputs.property("internalVersion", internalVersionProvider)
        inputs.property("mcVersion", mcVersion)
        inputs.property("sourbyBuild", sourbyBuild)
        outputs.file(outFile)
        // The branch provider is captured into the doLast closure below, which the
        // config-cache layer cannot serialise. Opting THIS task out is cheap and does
        // not affect the rest of the build.
        notCompatibleWithConfigurationCache("Reads git branch via providers.exec at task execution time.")

        doLast {
            val f = outFile.get().asFile
            f.parentFile.mkdirs()
            val timestamp = Instant.now().toString()
            val resolved = internalVersionProvider.get()
            f.writeText(
                """
                version=$resolved
                build=$sourbyBuild
                mcVersion=$mcVersion
                tagline=Lightning Fast Performance Feature Rich
                buildTimestamp=$timestamp
                """.trimIndent()
            )
        }
    }

    if (thisProjectName == "sourbycraft-server") {
        tasks.withType<ProcessResources>().configureEach {
            dependsOn(writeBuildInfoTask)
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            from(layout.buildDirectory.dir("generated-resources"))
        }
        tasks.withType<Jar>().configureEach {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }

    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    tasks.withType<Javadoc>().configureEach {
        options {
            (this as StandardJavadocDocletOptions).apply {
                addStringOption("-add-modules", "jdk.incubator.vector")
                addStringOption("Xdoclint:none", "-quiet")
            }
        }
    }
}
