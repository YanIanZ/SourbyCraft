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

    // SourbyCraft v12 — variant-specific resource overlay.
    // Copies ONLY the variant overlay dir into build/variant-resources/. Baseline
    // resources are bundled normally via src/main/resources (with variant-overlay/**
    // excluded — see ProcessResources block below). Overlay's sourbycraft.yml is
    // renamed to sourbycraft-variant-overlay.yml so SourbyCraftConfig can deep-merge
    // at runtime; other overlay files (server.properties, paper-global.yml etc) ship
    // at root as first-boot seed files.
    val thisProjectName = project.name
    val variantOverlayTask = tasks.register<Copy>("processVariantResources") {
        val variant = providers.gradleProperty("variant").getOrElse("normal")
        val overlay = file("${rootProject.projectDir}/sourbycraft-server/src/main/resources/variant-overlay/$variant")
        val overlayExistsProvider = provider { overlay.exists() }
        val isServerProject = thisProjectName == "sourbycraft-server"

        onlyIf { isServerProject && overlayExistsProvider.get() }

        if (overlay.exists()) {
            from(overlay) {
                rename("sourbycraft\\.yml", "sourbycraft-variant-overlay.yml")
            }
        }
        into(layout.buildDirectory.dir("variant-resources"))

        inputs.property("variant", variant)
    }

    if (thisProjectName == "sourbycraft-server") {
        tasks.withType<ProcessResources>().configureEach {
            dependsOn(variantOverlayTask)
            // variant-resources comes FIRST so overlay's server.properties etc win
            // over any baseline copy. Use EXCLUDE to silently drop later duplicates.
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            from(layout.buildDirectory.dir("variant-resources"))
            exclude("variant-overlay/**")
        }
        tasks.withType<Jar>().configureEach {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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

// SourbyCraft v12 — track last-applied variant so paperweight re-applies on switch.
// Paperweight caches the applied source tree — when switching variants we MUST
// invalidate that tree, else PVP code persists in a normal build (and vice versa).
// Runs at CONFIGURATION TIME so applyXxxPatches sees a wiped tree and re-applies.
run {
    val marker = file("build/.sourbycraft-applied-variant")
    val last = if (marker.exists()) marker.readText().trim() else ""
    if (last != sourbycraftVariant) {
        logger.lifecycle("SourbyCraft variant changed (\"$last\" -> \"$sourbycraftVariant\"); resetting NMS tree")
        // Only sourbycraft-server/src/minecraft/ hosts PVP patches (9XXX-PVP-* in
        // patches/minecraft/). paper-server, paper-api don't receive PVP patches —
        // wiping them breaks Gradle subproject resolution.
        val nmsDir = file("sourbycraft-server/src/minecraft")
        if (nmsDir.exists()) {
            nmsDir.deleteRecursively()
        }
        marker.parentFile.mkdirs()
        marker.writeText(sourbycraftVariant)
    }
}

// SourbyCraft v12 — filter PVP patches out of normal builds.
// PVP-only patches use 9XXX-PVP-*.patch naming and live in any of:
//   patches/server/ patches/api/ patches/minecraft/
// They are stashed before applyXxxPatches runs and restored after.
fun patchKindFor(taskName: String): String? = when (taskName) {
    "applyServerPatches" -> "server"
    "applyApiPatches" -> "api"
    "applyMinecraftPatches",
    "applyMinecraftSourcePatches",
    "applyMinecraftFilePatches",
    "applyMinecraftFeaturePatches" -> "minecraft"
    else -> null
}

// SourbyCraft v12 — apply filter to BOTH root + every subproject's tasks
// (paperweight task lives in :sourbycraft-server, not the root project).
allprojects {
    tasks.matching { patchKindFor(it.name) != null }.configureEach {
        val taskNameLocal = this.name
        doFirst {
            if (!isPvpVariant) {
                val patchKind = patchKindFor(taskNameLocal) ?: return@doFirst
                val patchDir = rootProject.file("patches/$patchKind")
                val pvpPatches = patchDir.listFiles { f: File -> f.name.matches(Regex("^9\\d{3}-.*\\.patch$")) } ?: emptyArray()
                val stashDir = rootProject.file("build/sourbycraft-pvp-patches-stashed/$patchKind")
                stashDir.mkdirs()
                pvpPatches.forEach { pf ->
                    val dest = File(stashDir, pf.name)
                    logger.lifecycle("  stash PVP patch (normal build): $patchKind/${pf.name}")
                    pf.renameTo(dest)
                }
            }
        }
        doLast {
            val patchKind = patchKindFor(taskNameLocal) ?: return@doLast
            val stashDir = rootProject.file("build/sourbycraft-pvp-patches-stashed/$patchKind")
            if (stashDir.exists()) {
                val patchDir = rootProject.file("patches/$patchKind")
                stashDir.listFiles().orEmpty().forEach { sf ->
                    sf.renameTo(File(patchDir, sf.name))
                }
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
