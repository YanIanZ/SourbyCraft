import java.time.Instant
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java // TODO java launcher tasks
    id("dev.iyanz.sourbypatcher.canvas") version "2.0.20"
}

repositories {
    exclusiveContent {
        forRepository { mavenLocal() }
        filter { includeModule("dev.iyanz", "sourbyclip") }
    }
}

paperweight {
    filterPatches = false
    // SourbyPatcher's Canvas adapter applies Weaver unchanged for nested Paper -> Canvas
    // patch sequencing. The legacy Folia patcher is retained only in the private repository.
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
            outputFile = file("sourbyapi/build.gradle.kts")
            patchFile = file("sourbyapi/build.gradle.kts.patch")
        }
        // Two levels deep (sourbycraft -> canvas -> paper): paper-api does not exist in Canvas's
        // raw checkout, only as CANVAS's OWN nested-build output, so this must be a patchRepo
        // (wires a proper task dependency on that nested output) rather than a plain patchDir
        // (which only reads a literal path inside the immediate "canvas" checkout).
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("sourbyapi/paper-patches")
            outputDir = file("paper-api")
        }
        // One level deep: canvas-api is a literal folder in Canvas's raw checkout, so a plain
        // patchDir is correct (mirrors the server-side canvasServer patchDir wired inside
        // sourbycraft-server/build.gradle.kts.patch's own forks.register("sourbycraft") block).
        patchDir("canvasApi") {
            upstreamPath = "canvas-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("sourbyapi/canvas-patches")
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
// our own non-public artifacts (dev.iyanz.sourbycraft:sourbyapi,
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
        branch.startsWith("experimental/") || branch.startsWith("feat/") -> "EXP"
        branch.startsWith("release/") -> "REL"
        branch.contains("-dev") || branch.contains("develop") -> "DEV"
        codename == "dev" -> "DEV"
        else -> "DEV"
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
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeModule("dev.iyanz", "sourbyclip") }
        }
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven(canvasMavenPublicUrl)
        maven { url = uri("${rootDir}/sourby-maven") }
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    if (project.name == "sourbycraft-server") {
        dependencies {
            // SourbyCraft - unified TOML config (own nightconfig CommentedFileConfig, resolved
            // directly by SourbyCraftConfig). Added here instead of via build.gradle.kts.patch
            // because the weaver patcher rejects the surrounding hunk after Canvas ref bumps
            // (the patch hunk count drifts as upstream hunks shift line offsets).
            "implementation"("com.electronwill.night-config:toml:3.9.0")
            // SourbyCraft - offline GeoIP for /ping (reads a local MaxMind-DB .mmdb; no player IP
            // leaves the server). Pulls maxmind-db + jackson (databind/core/annotations/jsr310)
            // transitively.
            "implementation"("com.maxmind.geoip2:geoip2:5.1.0")
        }
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
        // SourbyCraft-on-Canvas build number: gradle.properties `sourbyBuild=43` -> effective id
        // "43c" (c = Canvas base). Composite builds (e.g. `sourbyBuild=43.1` or `44-hotfix`)
        // append the "c" suffix only to pure-numeric values; non-numeric builds use the raw string.
        // The raw value is also stored as `buildNumber` for the auto-updater's comparison logic.
        val rawBuild = providers.gradleProperty("sourbyBuild").getOrElse("1").trim()
        val sourbyBuild = if (rawBuild.matches(Regex("\\d+(\\.\\d+)*"))) rawBuild + "c" else rawBuild
        val outFile = layout.buildDirectory.file("generated-resources/META-INF/sourbycraft-build.properties")

        val engineCodename = providers.gradleProperty("codename").getOrElse("dev")
        inputs.property("internalVersion", internalVersionProvider)
        inputs.property("mcVersion", mcVersion)
        inputs.property("codename", engineCodename)
        inputs.property("sourbyBuild", sourbyBuild)
        inputs.property("rawBuild", rawBuild)
        outputs.file(outFile)
        // The branch provider is captured into the doLast closure below, which the
        // config-cache layer cannot serialise. Opting THIS task out is cheap and does
        // not affect the rest of the build.
        notCompatibleWithConfigurationCache("Reads git branch via providers.exec at task execution time.")
        // SOURCE_DATE_EPOCH is read with System.getenv inside doLast, which Gradle cannot see,
        // so without declaring it here the task stays UP-TO-DATE when it changes. That shipped a
        // jar reporting a 2023 build date to the deployment server: a reproducibility test had
        // set the variable, and the next ordinary build repackaged the stale properties file.
        inputs.property("sourceDateEpoch", providers.environmentVariable("SOURCE_DATE_EPOCH")
            .orElse("")).optional(true)

        doLast {
            val f = outFile.get().asFile
            f.parentFile.mkdirs()
            // SOURCE_DATE_EPOCH, the cross-ecosystem "build as if it were this instant"
            // convention. Without it two builds of one commit differ only in this field, so
            // nobody can verify that a published jar came from the commit it claims (T8 gate:
            // "clean checkout build is reproducible"). Unset -- every ordinary dev build --
            // this reads the clock exactly as before. Read from the environment directly
            // rather than through a provider: this task already opts out of the configuration
            // cache, and System.getenv here sees the daemon's environment at execution time.
            val timestamp = (System.getenv("SOURCE_DATE_EPOCH")?.trim()?.toLongOrNull()
                ?.let { Instant.ofEpochSecond(it) } ?: Instant.now()).toString()
            val resolved = internalVersionProvider.get()
            f.writeText(
                """
                version=$resolved
                build=$sourbyBuild
                buildNumber=$rawBuild
                mcVersion=$mcVersion
                codename=$engineCodename
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
