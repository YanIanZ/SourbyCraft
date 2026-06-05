import io.papermc.paperweight.patcher.extension.PaperweightPatcherExtension
import io.papermc.paperweight.tasks.RebuildGitPatches
import java.security.MessageDigest
import java.time.Instant
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

data class LibSpec(val paperclipPath: String, val downloadUrl: String)

// Earlier failure with full lib set was caused by stale libraries/ state from a
// crashed boot attempt — not by Paper special-casing. With clean libraries/ dir,
// paperclip's cache-hit logic correctly adds pre-downloaded jars to classpath.
// Externalize all heavy optional libs.
val externalLibs = listOf(
    LibSpec(
        "org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar",
        "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar"
    ),
    LibSpec(
        "com/mysql/mysql-connector-j/9.2.0/mysql-connector-j-9.2.0.jar",
        "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/9.2.0/mysql-connector-j-9.2.0.jar"
    ),
    LibSpec(
        "me/lucko/spark-paper/1.10.152/spark-paper-1.10.152.jar",
        "https://repo.papermc.io/repository/maven-public/me/lucko/spark-paper/1.10.152/spark-paper-1.10.152.jar"
    ),
    LibSpec(
        "com/github/technove/Flare/34637f3f87/Flare-34637f3f87.jar",
        "https://jitpack.io/com/github/technove/Flare/34637f3f87/Flare-34637f3f87.jar"
    ),
    LibSpec(
        "com/google/protobuf/protobuf-java/4.29.0/protobuf-java-4.29.0.jar",
        "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/4.29.0/protobuf-java-4.29.0.jar"
    ),
    LibSpec(
        "io/sentry/sentry/7.15.0/sentry-7.15.0.jar",
        "https://repo1.maven.org/maven2/io/sentry/sentry/7.15.0/sentry-7.15.0.jar"
    )
    // parchment-data omitted — 988K, only used for IDE mappings, upstream 404 on the
    // pinned version. Stays bundled.
)

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

tasks.register("createSlimPaperclipJar") {
    group = "build"
    description = "Strip optional libs from paperclip jar + generate bootstrap manifest"

    dependsOn(":sourbycraft-server:createMojmapPaperclipJar")
    dependsOn(":sourbycraft-server:jar")

    // Capture fat jar outputs as a serializable FileCollection (config-cache safe)
    val fatJarFiles: FileCollection = project(":sourbycraft-server").tasks
        .named("createMojmapPaperclipJar").map { it.outputs.files }.get()
    inputs.files(fatJarFiles)

    // Bootstrap classes live in the sourbycraft-server jar (NOT paperclip). Paperclip transforms
    // this jar into META-INF/versions/<mc>/server-<mc>.jar.patch which is a binary diff — unreachable
    // at outer-jar level. We extract the bootstrap .class files from the source jar and copy them
    // at top-level of the slim jar so JVM can resolve the Main-Class.
    val serverJarFiles: FileCollection = project(":sourbycraft-server").tasks
        .named("jar").map { it.outputs.files }.get()
    inputs.files(serverJarFiles)

    val slimJar = layout.buildDirectory.file("libs/SourbyCraft-slim.jar")
    outputs.file(slimJar)

    // Snapshot externalLibs as plain serializable pairs (config-cache safe — no script refs)
    val libPaths: List<String> = externalLibs.map { it.paperclipPath }
    val libUrls: List<String> = externalLibs.map { it.downloadUrl }

    doLast {
        val fatJarFile = fatJarFiles.files
            .filter { it.name.endsWith(".jar") && it.exists() }
            .firstOrNull() ?: error("No jar output from createMojmapPaperclipJar")
        val out = slimJar.get().asFile
        out.parentFile.mkdirs()

        val libs = libPaths.zip(libUrls)

        // Step A: enumerate externalized lib bytes + compute sha256 + size
        val externalizedPaths = libPaths.toSet()
        val externalizedJarEntries = externalizedPaths.map { "META-INF/libraries/$it" }.toSet()
        val manifestEntries = mutableListOf<Map<String, Any>>()

        JarFile(fatJarFile).use { jar ->
            for ((path, url) in libs) {
                val entryName = "META-INF/libraries/$path"
                val entry = jar.getJarEntry(entryName)
                    ?: error("fat jar missing expected entry: $entryName " +
                        "(check externalLibs against current paperclip output)")
                val bytes = jar.getInputStream(entry).use { it.readAllBytes() }
                val md = MessageDigest.getInstance("SHA-256")
                val sha256 = md.digest(bytes).joinToString("") { b: Byte -> "%02x".format(b) }
                manifestEntries.add(linkedMapOf(
                    "paperclipPath" to path,
                    "downloadUrl"   to url,
                    "sha256"        to sha256,
                    "sizeBytes"     to bytes.size.toLong()
                ))
            }
        }

        // Step B: build manifest JSON (deterministic order)
        val manifestJson = buildString {
            append("{\"entries\":[")
            manifestEntries.forEachIndexed { i, e ->
                if (i > 0) append(",")
                append("{")
                append("\"paperclipPath\":\"${e["paperclipPath"]}\",")
                append("\"downloadUrl\":\"${e["downloadUrl"]}\",")
                append("\"sha256\":\"${e["sha256"]}\",")
                append("\"sizeBytes\":${e["sizeBytes"]}")
                append("}")
            }
            append("]}")
        }

        // Step C: read libraries.list UNCHANGED. Paperclip's library loader uses libraries.list to:
        //   (a) decide which libs to extract from META-INF/libraries/ into ./libraries/, and
        //   (b) add each libraries/<path> to the runtime classpath.
        // If we filter out externalized libs, paperclip stops adding them to classpath → ClassNotFoundError.
        // Instead: keep libraries.list intact. Paperclip checks SHA-256 of libraries/<path>; when
        // SourbyBootstrap has already written the file with matching SHA, paperclip's SHA check passes
        // and it skips extraction. The lib still ends up on the classpath via libraries.list.
        val filteredLibrariesList: String = JarFile(fatJarFile).use { jar ->
            val listEntry = jar.getJarEntry("META-INF/libraries.list")
                ?: error("fat jar missing META-INF/libraries.list")
            jar.getInputStream(listEntry).use { String(it.readAllBytes(), Charsets.UTF_8) }
        }

        // Step D: read + rewrite MANIFEST.MF (replace Main-Class)
        val newManifestMf: String = JarFile(fatJarFile).use { jar ->
            val mfEntry = jar.getJarEntry("META-INF/MANIFEST.MF")
                ?: error("fat jar missing META-INF/MANIFEST.MF")
            val original = jar.getInputStream(mfEntry).use { String(it.readAllBytes(), Charsets.UTF_8) }
            val mainClassRe = Regex("(?m)^Main-Class:.*\\r?\\n?")
            val replacement = "Main-Class: dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap\r\n"
            if (mainClassRe.containsMatchIn(original)) {
                original.replace(mainClassRe, replacement)
            } else {
                original.trimEnd() + "\r\n" + replacement + "\r\n"
            }
        }

        // Step E1: extract bootstrap .class files from sourbycraft-server jar (Main-Class needs them at outer jar root)
        val serverJarFile = serverJarFiles.files
            .filter { it.name.endsWith(".jar") && it.exists() }
            .firstOrNull() ?: error("No jar output from :sourbycraft-server:jar")
        val bootstrapClassPrefix = "dev/iyanz/sourbycraft/bootstrap/"
        val bootstrapClassBytes: Map<String, ByteArray> = JarFile(serverJarFile).use { jar ->
            jar.entries().asSequence()
                .filter { it.name.startsWith(bootstrapClassPrefix) && it.name.endsWith(".class") }
                .associate { e -> e.name to jar.getInputStream(e).use { it.readAllBytes() } }
        }
        if (bootstrapClassBytes.isEmpty()) {
            error("No bootstrap .class files found in ${serverJarFile.name}; expected entries at $bootstrapClassPrefix*.class")
        }
        logger.lifecycle("createSlimPaperclipJar: copying ${bootstrapClassBytes.size} bootstrap class(es) to slim jar root")

        // Step E2: write slim jar
        JarFile(fatJarFile).use { jar ->
            JarOutputStream(out.outputStream().buffered()).use { jos ->
                for (entry in jar.entries()) {
                    val name = entry.name
                    when {
                        name in externalizedJarEntries -> continue                     // skip externalized libs
                        name == "META-INF/libraries.list" -> continue                  // replaced below
                        name == "META-INF/MANIFEST.MF" -> continue                     // replaced below
                        name == "speedtest" -> continue                                // bundled linux binary; Task 5 replaces
                        name in bootstrapClassBytes.keys -> continue                   // replaced below (avoid duplicate entry)
                        else -> {
                            jos.putNextEntry(JarEntry(name))
                            jar.getInputStream(entry).use { it.copyTo(jos) }
                            jos.closeEntry()
                        }
                    }
                }
                jos.putNextEntry(JarEntry("META-INF/libraries.list"))
                jos.write(filteredLibrariesList.toByteArray(Charsets.UTF_8))
                jos.closeEntry()
                jos.putNextEntry(JarEntry("META-INF/sourby-bootstrap-manifest.json"))
                jos.write(manifestJson.toByteArray(Charsets.UTF_8))
                jos.closeEntry()
                jos.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
                jos.write(newManifestMf.toByteArray(Charsets.UTF_8))
                jos.closeEntry()
                // Bootstrap classes at top-level so JVM can resolve Main-Class
                for ((entryName, bytes) in bootstrapClassBytes) {
                    jos.putNextEntry(JarEntry(entryName))
                    jos.write(bytes)
                    jos.closeEntry()
                }
            }
        }

        logger.lifecycle("createSlimPaperclipJar: wrote ${out.length() / 1024 / 1024}M to ${out.absolutePath}")
    }
}

// SourbyCraft v12 — assemble mojmap paperclip jar into release/ with checksums.
// Reobf jar dropped: paperweight 2.0 deprecates reobf builds, and the reobf
// paperclip jar produced via debug-mode bypass fails at boot with
// ExceptionInInitializerError in ca.spottedleaf.dataconverter.MCTypeRegistry.
// Paper's runtime plugin remapper handles legacy reobf plugins on the mojmap jar.
tasks.register("assembleReleaseArtifacts") {
    group = "release"
    description = "Copy mojmap paperclip jar into release/ and regenerate checksums.txt"

    val releaseDir = rootProject.layout.projectDirectory.dir("release")
    val internalVersion = providers.gradleProperty("internalVersion").getOrElse("dev")

    // Release uses slim jar — externalizes only JDBC drivers (sqlite + mysql, ~17M) which
    // Paper does NOT special-case. Boot test required after every externalLibs change.
    val mojmapOutputs = tasks.named("createSlimPaperclipJar").map { it.outputs.files }

    dependsOn("createSlimPaperclipJar")
    inputs.files(mojmapOutputs)

    val mojmapDest = releaseDir.file("SourbyCraft-${internalVersion}.jar").asFile
    val checksumsFile = releaseDir.file("checksums.txt").asFile
    val releaseDirFile = releaseDir.asFile

    outputs.file(mojmapDest)
    outputs.file(checksumsFile)

    doLast {
        fun firstJarFrom(files: org.gradle.api.file.FileCollection, label: String): java.io.File {
            return files.files
                .filter { it.name.endsWith(".jar") && it.exists() }
                .firstOrNull()
                ?: error("No jar output found for $label")
        }

        val mojmapSrc = firstJarFrom(mojmapOutputs.get(), "createMojmapPaperclipJar")

        releaseDirFile.mkdirs()
        mojmapSrc.copyTo(mojmapDest, overwrite = true)

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
            "${sha256(mojmapDest)}  release/${mojmapDest.name}\n"
        )

        logger.lifecycle("SourbyCraft release: ${mojmapDest.name}")
    }
}

