import io.papermc.paperweight.patcher.extension.PaperweightPatcherExtension
import io.papermc.paperweight.tasks.RebuildGitPatches
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

    // SourbyCraft v12 — variant-specific resource overlay
    // Capture project name at config time so onlyIf{} doesn't break Gradle config cache.
    val thisProjectName = project.name
    val variantOverlayTask = tasks.register<Copy>("processVariantResources") {
        val variant = providers.gradleProperty("variant").getOrElse("normal")
        val baseline = file("${rootProject.projectDir}/sourbycraft-server/src/main/resources")
        val overlay = file("${rootProject.projectDir}/sourbycraft-server/src/main/resources/variant-overlay/$variant")
        val baselineExistsProvider = provider { baseline.exists() }
        val isServerProject = thisProjectName == "sourbycraft-server"

        onlyIf { isServerProject && baselineExistsProvider.get() }

        from(baseline) {
            exclude("variant-overlay/**")
        }
        if (overlay.exists()) {
            from(overlay) {
                duplicatesStrategy = DuplicatesStrategy.INCLUDE
            }
        }
        into(layout.buildDirectory.dir("variant-resources"))

        inputs.property("variant", variant)
    }

    if (thisProjectName == "sourbycraft-server") {
        tasks.withType<ProcessResources>().configureEach {
            dependsOn(variantOverlayTask)
            from(layout.buildDirectory.dir("variant-resources")) {
                duplicatesStrategy = DuplicatesStrategy.INCLUDE
            }
            exclude("variant-overlay/**")
        }
    }

    // SourbyCraft v12 — emit META-INF/sourbycraft-build.properties
    val writeBuildInfoTask = tasks.register("writeBuildInfo") {
        val variant = providers.gradleProperty("variant").getOrElse("normal")
        val internalVersion = providers.gradleProperty("internalVersion").getOrElse("dev")
        val mcVersion = providers.gradleProperty("mcVersion").getOrElse("unknown")
        val outFile = layout.buildDirectory.file("variant-resources/META-INF/sourbycraft-build.properties")

        inputs.property("variant", variant)
        inputs.property("internalVersion", internalVersion)
        inputs.property("mcVersion", mcVersion)
        outputs.file(outFile)

        doLast {
            val f = outFile.get().asFile
            f.parentFile.mkdirs()
            val timestamp = Instant.now().toString()
            f.writeText("""
                variant=$variant
                version=$internalVersion
                mcVersion=$mcVersion
                tagline=Lightning Fast Performance · Feature Rich
                buildTimestamp=$timestamp
            """.trimIndent())
        }
    }

    tasks.named("processVariantResources").configure {
        finalizedBy(writeBuildInfoTask)
    }

    if (thisProjectName == "sourbycraft-server") {
        tasks.withType<ProcessResources>().configureEach {
            dependsOn(writeBuildInfoTask)
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

// SourbyCraft v12 — build variant selection
val sourbycraftVariant = providers.gradleProperty("variant").getOrElse("normal")
val isPvpVariant = sourbycraftVariant == "pvp"

// Logged at configuration time so operators see which variant is building
logger.lifecycle("SourbyCraft variant: $sourbycraftVariant (PvP patches: ${if (isPvpVariant) "INCLUDED" else "EXCLUDED"})")

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

// SourbyCraft v12 — filter PVP patches out of normal builds
tasks.matching { it.name == "applyServerPatches" || it.name == "applyApiPatches" }.configureEach {
    doFirst {
        if (!isPvpVariant) {
            val patchKind = if (name == "applyServerPatches") "server" else "api"
            val patchDir = file("patches/$patchKind")
            val pvpPatches = patchDir.listFiles { f -> f.name.matches(Regex("^9\\d{3}-.*\\.patch$")) } ?: emptyArray()
            val stashDir = file("build/sourbycraft-pvp-patches-stashed/$patchKind").apply { mkdirs() }
            pvpPatches.forEach { pf ->
                val dest = stashDir.resolve(pf.name)
                logger.lifecycle("  stash PVP patch (normal build): ${pf.name}")
                pf.renameTo(dest)
            }
        }
    }
    doLast {
        val patchKind = if (name == "applyServerPatches") "server" else "api"
        val stashDir = file("build/sourbycraft-pvp-patches-stashed/$patchKind")
        if (stashDir.exists()) {
            val patchDir = file("patches/$patchKind")
            stashDir.listFiles().orEmpty().forEach { sf ->
                sf.renameTo(patchDir.resolve(sf.name))
            }
        }
    }
}

// SourbyCraft v12 — suffix paperclip jar with variant
gradle.projectsEvaluated {
    val variant = providers.gradleProperty("variant").getOrElse("normal")
    val suffix = if (variant == "pvp") "-PVP" else ""
    val internalVersion = providers.gradleProperty("internalVersion").getOrElse("dev")

    subprojects.filter { it.name == "sourbycraft-server" }.forEach { sp ->
        sp.tasks.matching { it.name.endsWith("PaperclipJar") }.configureEach {
            doLast {
                val outputs = outputs.files.files.filter { it.name.endsWith(".jar") && it.exists() }
                outputs.forEach { origJar ->
                    val newName = "SourbyCraft${suffix}-${internalVersion}.jar"
                    val newFile = origJar.resolveSibling(newName)
                    origJar.copyTo(newFile, overwrite = true)
                    logger.lifecycle("SourbyCraft jar: ${newFile.name}")
                }
            }
        }
    }
}
