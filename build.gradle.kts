import java.time.Instant
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
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

// ---------------------------------------------------------------------------
// SourbyCraft server-jar SLIMMING (Path B / weaver) — restores the old
// sourbypatcher SlimPaperclipJar size win WITHOUT any sourbypatcher/paperweight
// dependency. Plain Gradle task; runs on weaver's stock createPaperclipJar output.
//
// How it works (no new manifest needed — this is the key vs the old approach):
// weaver's createPaperclipJar already writes META-INF/libraries.list (one line per
// library: `sha256 <TAB> maven-coordinate <TAB> relpath`) AND bundles each library
// under META-INF/libraries/<relpath>. SourbyClip (our Leavesclip fork) reads that
// same libraries.list at boot via FileEntry.downloadFromMvnRepo: for each entry it
//   (1) uses the on-disk copy if present + sha256-valid, else
//   (2) extracts it from inside the jar / the vanilla Mojang bundle, else
//   (3) downloads it BY COORDINATE from Sourbyclip.ALL_MAVEN_REPO_LINK_BASE
//       (aliyun central mirror, repo.papermc.io, menthamc, spongepowered).
// So simply DELETING a library jar from META-INF/libraries/ — while leaving its
// libraries.list line intact — turns it into a first-boot download. The coordinate
// in libraries.list IS the download key; no separate coordinate+sha+URL manifest is
// required (the old SlimPaperclipJar wrote sourby-bootstrap-manifest.json only
// because it drove a SEPARATE pre-SourbyClip bootstrap downloader; SourbyClip's
// native Leavesclip path makes that redundant).
//
// We externalize only libraries that are (a) heavy and (b) resolvable by coordinate
// on those public repos. Deliberately kept BUNDLED: paperclip/plugin-loader
// bootstrap deps (maven-resolver*, sisu, plexus*, commons-codec, apache httpclient),
// our own non-public artifacts (dev.iyanz.sourbycraft:sourbycraft-api,
// io.canvasmc.httpclient, ca.spottedleaf:leafpile, net.openhft:affinity), and jline
// (console-critical). Versions are matched at task-execution time by artifact-dir
// prefix, so a weaver version bump doesn't silently no-op the strip.
val externalizeArtifactDirs = listOf(
    "org/xerial/sqlite-jdbc",
    "com/github/luben/zstd-jni",
    "me/lucko/spark-paper",
    "com/mysql/mysql-connector-j",
    "com/google/protobuf/protobuf-java",
    "net/kyori/adventure-api",
    "net/kyori/adventure-text-minimessage",
    "org/spongepowered/configurate-yaml",
    "org/spongepowered/configurate-core",
    "org/yaml/snakeyaml",
    "commons-lang/commons-lang",
    "com/electronwill/night-config/core",
    "com/electronwill/night-config/toml",
    "com/maxmind/geoip2/geoip2",
    "com/maxmind/db/maxmind-db",
    "com/fasterxml/jackson/core/jackson-databind",
    "com/fasterxml/jackson/core/jackson-core",
    "com/fasterxml/jackson/core/jackson-annotations",
    "com/fasterxml/jackson/datatype/jackson-datatype-jsr310",
)

val slimServerJar = tasks.register("slimServerJar") {
    group = "sourbycraft"
    description = "Strip independently-resolvable libraries from the fat paperclip jar; " +
        "SourbyClip re-downloads them by coordinate on first boot."

    val serverProj = project(":sourbycraft-server")
    dependsOn("${serverProj.path}:createPaperclipJar")

    // Capture the fat jar as an input FILE (config-cache safe: read back via inputs.files
    // in doLast rather than dereferencing the other project's task at execution time).
    inputs.files(
        serverProj.tasks.named("createPaperclipJar").map { t ->
            t.outputs.files.files.first { it.name.contains("paperclip") && it.name.endsWith(".jar") }
        }
    )
    val prefixes = externalizeArtifactDirs
    inputs.property("externalizeArtifactDirs", prefixes)
    val outFileProvider = layout.buildDirectory.file("libs/SourbyCraft-slim.jar")
    outputs.file(outFileProvider)

    doLast {
        val fatJar = inputs.files.singleFile
        val out = outFileProvider.get().asFile
        out.parentFile.mkdirs()

        val normalizedPrefixes = prefixes.map { "META-INF/libraries/$it/" }
        var strippedCount = 0
        var strippedBytes = 0L

        JarFile(fatJar).use { jar ->
            JarOutputStream(out.outputStream().buffered()).use { jos ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    val strip = name.endsWith(".jar") && normalizedPrefixes.any { name.startsWith(it) }
                    if (strip) {
                        strippedCount++
                        if (entry.size >= 0) strippedBytes += entry.size
                        continue
                    }
                    jos.putNextEntry(JarEntry(name))
                    if (!entry.isDirectory) {
                        jar.getInputStream(entry).use { it.copyTo(jos) }
                    }
                    jos.closeEntry()
                }
            }
        }
        if (strippedCount == 0) {
            throw GradleException(
                "slimServerJar stripped 0 libraries — externalizeArtifactDirs no longer match the " +
                    "paperclip layout (weaver version/library set changed?). Refusing to emit a non-slim jar."
            )
        }
        logger.lifecycle(
            "slimServerJar: stripped $strippedCount lib jar(s) (~${strippedBytes / 1024 / 1024}M of libraries) " +
                "-> ${out.name} (${out.length() / 1024 / 1024}M, fat was ${fatJar.length() / 1024 / 1024}M)"
        )
    }
}

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
