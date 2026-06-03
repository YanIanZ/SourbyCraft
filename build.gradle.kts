import io.papermc.paperweight.patcher.extension.PaperweightPatcherExtension
import io.papermc.paperweight.tasks.RebuildGitPatches
import java.security.MessageDigest
import java.time.Instant
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    id("java-library")
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.19"
}

subprojects {
    apply<JavaLibraryPlugin>()
    apply<MavenPublishPlugin>()

    configure<JavaPluginExtension> {
        withSourcesJar()

        // SourbyCraft - dual Java 21/25 support: emit Java 21 bytecode, build with 25 toolchain
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21

        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.ADOPTIUM
        }
    }

    if (!file(".notest").exists()) {
        dependencies {
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }
    }

    tasks.withType<AbstractArchiveTask> {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = 21 // SourbyCraft - dual Java 21/25: emit Java 21 bytecode for plugin compat
        options.isIncremental = true
        options.isFork = true
        options.forkOptions.memoryMaximumSize = "512M"
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }

    val thisProjectName = project.name

    // SourbyCraft v12 — emit META-INF/sourbycraft-build.properties (no variant field;
    // single-jar build means variant identity is no longer meaningful).
    val writeBuildInfoTask = tasks.register("writeBuildInfo") {
        val internalVersion = providers.gradleProperty("internalVersion").getOrElse("dev")
        val mcVersion = providers.gradleProperty("mcVersion").getOrElse("unknown")
        val outFile = layout.buildDirectory.file("generated-resources/META-INF/sourbycraft-build.properties")

        inputs.property("internalVersion", internalVersion)
        inputs.property("mcVersion", mcVersion)
        outputs.file(outFile)

        doLast {
            val f = outFile.get().asFile
            f.parentFile.mkdirs()
            val timestamp = Instant.now().toString()
            f.writeText("""
                version=$internalVersion
                mcVersion=$mcVersion
                tagline=Lightning Fast Performance · Feature Rich
                buildTimestamp=$timestamp
            """.trimIndent())
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

    // SourbyCraft v12 — broaden test include pattern for sourbycraft-server.
    // Existing pattern is "**/**TestSuite.class" (suite-only). New TDD tests live
    // alongside Java code as *Test.class without per-package suite wrappers.
    if (thisProjectName == "sourbycraft-server") {
        tasks.withType<Test>().configureEach {
            include("**/*Test.class")
        }
    }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io/")
        maven("https://maven.neoforged.net/releases/") // SourbyCraft - NeoForge
    }
}

configure<PaperweightPatcherExtension> {
    upstreams.paper {
        ref = providers.gradleProperty("paperRef")

        // paper's example project uses single file patches for buildscripts,
        // but I hate file patches as they are horrible to apply if
        // they fail once; so create a patch dir and use a symbolic
        // link to place the buildscript at the proper location
        listOf("api", "server").forEach { part ->
            val capitalizedPart = part.replaceFirstChar { it.titlecaseChar() }
            patchDir("paper${capitalizedPart}Buildscript") {
                upstreamPath = "paper-$part"
                patchesDir = file("patches/buildscript/$part")
                featurePatchDir = patchesDir.dir(".")
                outputDir = file("sourbycraft-$part/buildscript")
                // the relevant part is just the buildscript
                excludes = setOf("src", "patches")
            }
        }
        // api patching
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("patches/api")
            featurePatchDir = patchesDir.dir(".")
            outputDir = file("paper-api")
            excludes = setOf("build.gradle.kts")
        }
    }
}

// see gradle.properties
if (providers.gradleProperty("updatingMinecraft").getOrElse("false").toBoolean()) {
    tasks.withType<RebuildGitPatches> {
        filterPatches = false
    }
}

// SourbyCraft v12 — bypass paperweight 2.0 reobf-jar deprecation block.
// Paper officially supports mojmap-only since the plugin remapper handles
// legacy reobf plugins at runtime. We still ship reobf jar for operators with
// plugin sets that bypass the remapper. Flag must be set before any paperweight
// reobf task evaluates.
System.setProperty("paperweight.debug", "true")

// SourbyCraft v12 — assemble both paperclip jars into release/ with checksums.
tasks.register("assembleReleaseArtifacts") {
    group = "release"
    description = "Copy mojmap + reobf paperclip jars into release/ and regenerate checksums.txt"

    val releaseDir = rootProject.layout.projectDirectory.dir("release")
    val internalVersion = providers.gradleProperty("internalVersion").getOrElse("dev")

    // Capture task outputs at configuration time so doLast doesn't hold a script object reference.
    val mojmapOutputs = project(":sourbycraft-server").tasks
        .named("createMojmapPaperclipJar").map { it.outputs.files }
    val reobfOutputs = project(":sourbycraft-server").tasks
        .named("createReobfPaperclipJar").map { it.outputs.files }

    dependsOn(":sourbycraft-server:createMojmapPaperclipJar")
    dependsOn(":sourbycraft-server:createReobfPaperclipJar")
    inputs.files(mojmapOutputs)
    inputs.files(reobfOutputs)

    val mojmapDest = releaseDir.file("SourbyCraft-${internalVersion}.jar").asFile
    val reobfDest = releaseDir.file("SourbyCraft-${internalVersion}-reobf.jar").asFile
    val checksumsFile = releaseDir.file("checksums.txt").asFile
    val releaseDirFile = releaseDir.asFile

    outputs.file(mojmapDest)
    outputs.file(reobfDest)
    outputs.file(checksumsFile)

    doLast {
        fun firstJarFrom(files: org.gradle.api.file.FileCollection, label: String): java.io.File {
            return files.files
                .filter { it.name.endsWith(".jar") && it.exists() }
                .firstOrNull()
                ?: error("No jar output found for $label")
        }

        val mojmapSrc = firstJarFrom(mojmapOutputs.get(), "createMojmapPaperclipJar")
        val reobfSrc = firstJarFrom(reobfOutputs.get(), "createReobfPaperclipJar")

        releaseDirFile.mkdirs()
        mojmapSrc.copyTo(mojmapDest, overwrite = true)
        reobfSrc.copyTo(reobfDest, overwrite = true)

        fun sha256(f: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { b: Byte -> "%02x".format(b) }
        }
        checksumsFile.writeText(
            "${sha256(mojmapDest)}  release/${mojmapDest.name}\n" +
            "${sha256(reobfDest)}  release/${reobfDest.name}\n"
        )

        logger.lifecycle("SourbyCraft release: ${mojmapDest.name} + ${reobfDest.name}")
    }
}

