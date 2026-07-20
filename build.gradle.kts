import java.time.Instant
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java // TODO java launcher tasks
    id("dev.iyanz.sourbypatcher.patcher")
}

paperweight {
    filterPatches = false
    // SourbyCraft-on-Canvas (feat/canvas-engine, PR #12): upstream swapped from PaperMC/Folia to
    // CraftCanvasMC/Canvas. Canvas is itself a paperweight/weaver-style fork of Paper (its own
    // build.gradle.kts registers `upstreams.paper { ... }` with an identical patchFile/patchDir
    // DSL shape), so it is registered here exactly the way Folia was: a downstream upstream whose
    // OWN nested Paper layer is resolved recursively by the nested build, with our own
    // paper-api patches layered on top via patchRepo("paperApi") same as before.
    upstreams.register("canvas") {
        repo = github("CraftCanvasMC", "Canvas")
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
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("sourbycraft-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("canvasApi") {
            upstreamPath = "canvas-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("sourbycraft-api/canvas-patches")
            outputDir = file("canvas-api")
        }
    }

    // SourbyLoader — libs stripped from the fat paperclip jar + fetched on first boot
    // (SourbyBootstrap). Paths are relative to META-INF/libraries/; the download URL is
    // baseUrl + path. Only independently-resolvable libs are externalized — paperclip's own
    // downloader deps (maven-resolver/sisu/httpclient/commons-codec) and our modules stay bundled.
    val mavenCentral = "https://repo1.maven.org/maven2"
    val paperRepo = "https://repo.papermc.io/repository/maven-public"
    externalLib("org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar", mavenCentral)
    externalLib("com/github/luben/zstd-jni/1.5.7-11/zstd-jni-1.5.7-11.jar", mavenCentral)
    externalLib("me/lucko/spark-paper/1.10.172/spark-paper-1.10.172.jar", paperRepo)
    externalLib("com/mysql/mysql-connector-j/9.2.0/mysql-connector-j-9.2.0.jar", mavenCentral)
    externalLib("com/google/protobuf/protobuf-java/4.29.0/protobuf-java-4.29.0.jar", mavenCentral)
    externalLib("io/sentry/sentry/8.0.0-rc.2/sentry-8.0.0-rc.2.jar", mavenCentral)
    externalLib("net/kyori/adventure-api/5.2.0/adventure-api-5.2.0.jar", mavenCentral)
    externalLib("net/kyori/adventure-text-minimessage/5.2.0/adventure-text-minimessage-5.2.0.jar", mavenCentral)
    externalLib("org/spongepowered/configurate-yaml/4.2.0/configurate-yaml-4.2.0.jar", mavenCentral)
    externalLib("org/spongepowered/configurate-core/4.2.0/configurate-core-4.2.0.jar", mavenCentral)
    externalLib("org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar", mavenCentral)
    externalLib("commons-lang/commons-lang/2.6/commons-lang-2.6.jar", mavenCentral)
    externalLib("io/github/classgraph/classgraph/4.8.158/classgraph-4.8.158.jar", mavenCentral)
    externalLib("com/electronwill/night-config/core/3.9.0/core-3.9.0.jar", mavenCentral)
    externalLib("com/electronwill/night-config/toml/3.9.0/toml-3.9.0.jar", mavenCentral)
    externalLib("org/jline/jline-terminal/3.27.1/jline-terminal-3.27.1.jar", mavenCentral)
    externalLib("org/jline/jline-native/3.27.1/jline-native-3.27.1.jar", mavenCentral)
    externalLib("org/jline/jline-reader/3.20.0/jline-reader-3.20.0.jar", mavenCentral)
    // Offline geoip for /ping (com.maxmind.geoip2:geoip2 + its runtime closure).
    externalLib("com/maxmind/geoip2/geoip2/5.1.0/geoip2-5.1.0.jar", mavenCentral)
    externalLib("com/maxmind/db/maxmind-db/4.1.0/maxmind-db-4.1.0.jar", mavenCentral)
    externalLib("com/fasterxml/jackson/core/jackson-databind/2.21.3/jackson-databind-2.21.3.jar", mavenCentral)
    externalLib("com/fasterxml/jackson/core/jackson-core/2.21.3/jackson-core-2.21.3.jar", mavenCentral)
    externalLib("com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar", mavenCentral)
    externalLib("com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.21.3/jackson-datatype-jsr310-2.21.3.jar", mavenCentral)
}

// Wire the SourbyPatcher slim task to this build's server paperclip + server jars.
tasks.named<io.papermc.paperweight.tasks.SlimPaperclipJar>("slimPaperclipJar") {
    val serverProj = project(":sourbycraft-server")
    dependsOn("${serverProj.path}:createPaperclipJar", "${serverProj.path}:jar")
    fatJar.fileProvider(
        serverProj.tasks.named("createPaperclipJar").map { t ->
            t.outputs.files.files.first { it.name.contains("paperclip") && it.name.endsWith(".jar") }
        }
    )
    serverJar.fileProvider(
        serverProj.tasks.named("jar").map { t ->
            t.outputs.files.files.first { it.name.endsWith(".jar") }
        }
    )
    outputJar.set(layout.buildDirectory.file("libs/SourbyCraft-slim.jar"))
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
        // SourbyCraft — Folia build number: gradle.properties `sourbyBuild=1` -> effective id "1f"
        // (f = Folia). Surfaced as `build=1f` here so BuildInfo (banner + /ver) can render
        // "26.2-REL (build 1f)". Bump sourbyBuild per release -> 2f, 3f, ...
        val sourbyBuild = providers.gradleProperty("sourbyBuild").getOrElse("1").trim() + "f"
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
