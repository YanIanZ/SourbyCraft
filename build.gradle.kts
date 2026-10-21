import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java // TODO java launcher tasks
    id("dev.iyanz.sourbypatcher.paper")
}

repositories {
    exclusiveContent {
        forRepository { mavenLocal() }
        filter { includeModule("dev.iyanz", "sourbyclip") }
    }
}

paperweight {
    filterPatches = false
    // Paper is SourbyCraft's only upstream. Region threading and every other change that used to
    // come from Canvas/Folia now lives in SourbyCraft's own patches and sources.
    upstreams.paper {
        ref = providers.gradleProperty("paperRef")

        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("sourbycraft-server/build.gradle.kts")
            patchFile = file("sourbycraft-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("sourbyapi/build.gradle.kts")
            patchFile = file("sourbyapi/build.gradle.kts.patch")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("sourbyapi/paper-patches")
            outputDir = file("paper-api")
        }
    }
}

// ---------------------------------------------------------------------------
// SourbyCraft server-jar SLIMMING (paperweight) — restores the old
// sourbypatcher SlimPaperclipJar size win WITHOUT any sourbypatcher/paperweight
// dependency. Plain Gradle task; runs on paperweight's stock createPaperclipJar output.
//
// How it works (no new manifest needed — this is the key vs the old approach):
// paperweight's createPaperclipJar already writes META-INF/libraries.list (one line per
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
// ca.spottedleaf:leafpile, net.openhft:affinity), and jline
// (console-critical). Versions are matched at task-execution time by artifact-dir
// prefix, so a paperweight version bump doesn't silently no-op the strip.
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
    // Entry timestamps come from SOURCE_DATE_EPOCH (reproducible slim jar, Build 49). Declared as
    // an input so a changed value re-runs the task instead of leaving a jar with stale entry times.
    val sourceDateEpoch = providers.environmentVariable("SOURCE_DATE_EPOCH").orElse("")
    inputs.property("sourceDateEpoch", sourceDateEpoch)
    val outFileProvider = layout.buildDirectory.file("libs/SourbyCraft-slim.jar")
    outputs.file(outFileProvider)

    doLast {
        val fatJar = inputs.files.singleFile
        val out = outFileProvider.get().asFile
        out.parentFile.mkdirs()

        val normalizedPrefixes = prefixes.map { "META-INF/libraries/$it/" }
        var strippedCount = 0
        var strippedBytes = 0L

        // Every written entry carries one fixed timestamp, so two builds of one commit differ in no
        // entry header. SOURCE_DATE_EPOCH (seconds), clamped to the ZIP/DOS range [1980-01-01, 2107];
        // unset -> exactly 1980-01-01T00:00:00 (the DOS epoch), never the clock. Written with
        // setTimeLocal so the DOS fields hold this UTC wall time whatever the machine's time zone is.
        val dosEpochSecond = 315532800L          // 1980-01-01T00:00:00Z
        val dosMaxSecond = 4354819198L           // 2107-12-31T23:59:58Z
        val entryEpoch = (sourceDateEpoch.get().trim().toLongOrNull() ?: dosEpochSecond)
            .coerceIn(dosEpochSecond, dosMaxSecond)
        val entryTime = LocalDateTime.ofEpochSecond(entryEpoch, 0, ZoneOffset.UTC)

        JarFile(fatJar).use { jar ->
            JarOutputStream(out.outputStream().buffered()).use { jos ->
                // Deterministic order: MANIFEST.MF first (readers expect it early), then by name.
                // Never the source jar's own order, which is whatever the producing task emitted.
                val manifestName = "META-INF/MANIFEST.MF"
                val ordered = jar.entries().toList().sortedWith(
                    compareBy<JarEntry>({ it.name != "META-INF/" }, { it.name != manifestName }, { it.name })
                )
                for (entry in ordered) {
                    val name = entry.name
                    val strip = name.endsWith(".jar") && normalizedPrefixes.any { name.startsWith(it) }
                    if (strip) {
                        strippedCount++
                        if (entry.size >= 0) strippedBytes += entry.size
                        continue
                    }
                    val outEntry = JarEntry(name)
                    outEntry.setTimeLocal(entryTime)
                    jos.putNextEntry(outEntry)
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
                    "paperclip layout (paperweight version/library set changed?). Refusing to emit a non-slim jar."
            )
        }
        logger.lifecycle(
            "slimServerJar: stripped $strippedCount lib jar(s) (~${strippedBytes / 1024 / 1024}M of libraries) " +
                "-> ${out.name} (${out.length() / 1024 / 1024}M, fat was ${fatJar.length() / 1024 / 1024}M)"
        )
    }
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

// SourbyCraft — resolve the human-facing suffix version once (banner + /ver read this
// through META-INF/sourbycraft-build.properties). Branch is read via providers.exec so
// the value is correct on CI and locally; the writeBuildInfo task opts itself out of the
// config cache because the branch provider cannot be serialised into a doLast closure.
val sourbycraftBranchSnapshot = try {
    providers.exec {
        commandLine("git", "--no-optional-locks", "rev-parse", "--abbrev-ref", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
} catch (_: Exception) { "unknown" }
val sourbycraftBranchProvider: Provider<String> = providers.provider { sourbycraftBranchSnapshot }
extra["sourbycraftBranchProvider"] = sourbycraftBranchProvider

// Realize exactly once: every manifest/properties consumer uses this same snapshot.
// Missing Python/Git or unsupported source layouts remain local unknown + dirty builds.
val sourbycraftSourceSnapshot = try {
    providers.exec {
        commandLine("python3", rootProject.file("scripts/build_identity.py").absolutePath)
    }.standardOutput.asText.get()
} catch (_: Exception) {
    logger.warn("Build provenance unavailable; using unknown + dirty (publication disabled).")
    """{"commit":"unknown","treeHash":"unknown","dirty":"true"}"""
}
val sourbycraftSourceIdentity: Provider<String> = providers.provider { sourbycraftSourceSnapshot }
extra["sourbycraftSourceIdentity"] = sourbycraftSourceIdentity

// SourbyCraft — build provenance embedded next to the source identity (TODO Inovasi "provenance
// manifest"): scripts/build_identity.py's "provenance" object (SHA-256 of each patch directory, the
// engine-baseline manifest, the toolchain lock and the pinned toolchain jar hashes) plus the JDK
// that compiles the sources (the Java 25 toolchain, not the JVM running Gradle). writeBuildInfo
// writes it as provenance.<Name>=... and the server manifest as Provenance-<Name>: ...; both read
// this one provider, which is what keeps them in lockstep. verify_build_identity.py checks both.
val sourbycraftBuildJdk: Provider<String> = extensions.getByType<JavaToolchainService>()
    .compilerFor { languageVersion = JavaLanguageVersion.of(25) }
    .map { "${it.metadata.vendor} ${it.metadata.javaRuntimeVersion}".trim() }
    .orElse("unknown")
val sourbycraftProvenance: Provider<Map<String, String>> = sourbycraftSourceIdentity.zip(sourbycraftBuildJdk) { json, jdk ->
    val parsed = groovy.json.JsonSlurper().parseText(json) as Map<*, *>
    val out = sortedMapOf<String, String>()
    (parsed["provenance"] as? Map<*, *>)?.forEach { (k, v) -> out[k.toString()] = v.toString() }
    out["Jdk"] = jdk
    out
}
extra["sourbycraftProvenance"] = sourbycraftProvenance

val sourbycraftSuffixProvider: Provider<String> = sourbycraftBranchProvider.map { branch ->
    val releaseVersionFull = providers.gradleProperty("releaseVersion").getOrElse("dev")
    val releaseMajor = releaseVersionFull.substringBefore('-')
    val codename = providers.gradleProperty("codename").getOrElse("dev")
    val suffix = when {
        // CI publishes releases from 26.2 and passes -PsourbyChannel=REL for that build only.
        providers.gradleProperty("sourbyChannel").orNull == "REL" -> "REL"
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
        maven { url = uri("${rootDir}/sourby-maven") }
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    if (project.name == "sourbycraft-server") {
        dependencies {
            // SourbyCraft - unified TOML config (own nightconfig CommentedFileConfig, resolved
            // directly by SourbyCraftConfig). Added here instead of via build.gradle.kts.patch so an
            // upstream bump that shifts the dependencies block cannot reject the hunk.
            "implementation"("com.electronwill.night-config:toml:3.9.0")
            // SourbyCraft - offline GeoIP for /ping (reads a local MaxMind-DB .mmdb; no player IP
            // leaves the server). Pulls maxmind-db + jackson (databind/core/annotations/jsr310)
            // transitively.
            "implementation"("com.maxmind.geoip2:geoip2:5.2.0")
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    // javac names a synthetic local after the identity hash of a tree node (Lower.abstractLval, e.g. the
    // temporary for `boxedField++`) and, inside a lambda, writes it to the LocalVariableTable. Two compiles
    // of the same source then differ (observed: io/papermc/paper/command/subcommands/EntityCommand.class,
    // `$1022440802` vs `$2023237490`), which makes the server jar, the paperclip patch and the slim jar
    // differ run to run. A build stamped with SOURCE_DATE_EPOCH (CI, reproducibility checks) therefore
    // omits the local-variable table (`-g:source,lines`); ordinary dev builds keep the default `-g`.
    // Line numbers and source file names stay, so stack traces are unchanged.
    val reproducibleBuild = providers.environmentVariable("SOURCE_DATE_EPOCH").isPresent
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        if (reproducibleBuild) options.debugOptions.debugLevel = "source,lines"
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
        // Build 47+: public build identity belongs to SourbyCraft and intentionally does not
        // encode the current upstream platform as a suffix.
        val rawBuild = providers.gradleProperty("sourbyBuild").getOrElse("1").trim()
        val provenance = sourbycraftSourceIdentity
        inputs.property("sourceIdentity", provenance)
        val buildProvenance = sourbycraftProvenance
        inputs.property("buildProvenance", buildProvenance)
        val sourbyBuild = rawBuild
        val outFile = layout.buildDirectory.file("generated-resources/META-INF/sourbycraft-build.properties")

        val engineCodename = providers.gradleProperty("codename").getOrElse("dev")
        inputs.property("internalVersion", internalVersionProvider)
        inputs.property("gitBranch", sourbycraftBranchProvider)
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
            val identity = groovy.json.JsonSlurper().parseText(provenance.get()) as Map<*, *>
            val dirty = identity["dirty"].toString()
            val buildLabel = sourbyBuild + if (dirty == "true") "-dirty" else ""
            val resolved = internalVersionProvider.get()
            f.writeText(
                """
                version=$resolved
                build=$buildLabel
                buildNumber=$rawBuild
                gitCommit=${identity["commit"]}
                gitBranch=${sourbycraftBranchProvider.get()}
                treeHash=${identity["treeHash"]}
                dirty=$dirty
                mcVersion=$mcVersion
                codename=$engineCodename
                tagline=Reliable Stable High Performance
                buildTimestamp=$timestamp
                """.trimIndent() + buildProvenance.get().entries.joinToString("") { "\nprovenance.${it.key}=${it.value}" } + "\n"
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
