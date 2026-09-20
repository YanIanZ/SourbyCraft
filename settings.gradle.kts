pluginManagement {
    // Private SourbyPatcher delegates to Weaver's Canvas-compatible pipeline (PR #12).
    val patcherVersion = providers.gradleProperty("patcherVersion").get()
    val localRepo = providers.systemProperty("maven.repo.local")
        .getOrElse(System.getProperty("user.home") + "/.m2/repository")
    val patcherJar = file("$localRepo/dev/iyanz/sourbypatcher/canvas-toolchain/$patcherVersion/canvas-toolchain-$patcherVersion.jar")
    check(patcherJar.isFile) {
        "Private SourbyPatcher missing. Publish the pinned private checkout to Maven Local; see docs/development/PRIVATE-TOOLCHAIN.md"
    }
    val hash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(patcherJar.readBytes()).joinToString("") { "%02x".format(it) }
    check(hash == providers.gradleProperty("patcherSha256").get()) {
        "Private SourbyPatcher SHA-256 mismatch; republish the approved private revision"
    }
    repositories {
        gradlePluginPortal()
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroupByRegex("dev\\.iyanz\\.sourbypatcher.*") }
        }
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.canvasmc.io/public/")
        maven("https://maven.canvasmc.io/releases")
        maven { url = uri("${rootDir}/sourby-maven") }
    }

    plugins {
        id("dev.iyanz.sourbypatcher.canvas") version "2.0.20"
        id("io.canvasmc.weaver.core") version "2.4.5"
        id("io.canvasmc.weaver.dependency-bridge") version "2.4.5"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "sourbycraft"

for (name in listOf("sourbyapi", "sourbycraft-server")) {
    include(name)
    file(name).mkdirs()
}

optionalInclude("test-plugin")
optionalInclude("luminol-generator")

fun optionalInclude(name: String, op: (ProjectDescriptor.() -> Unit)? = null) {
    val settingsFile = file("$name.settings.gradle.kts")
    if (settingsFile.exists()) {
        apply(from = settingsFile)
        findProject(":$name")?.let { op?.invoke(it) }
    } else {
        settingsFile.writeText(
            """
            // Uncomment to enable the '$name' project
            // include(":$name")

            """.trimIndent()
        )
    }
}

// SourbyCraft — derive the clean channel version (e.g. "26.2-REL") from the git branch, matching
// the root build's sourbycraftSuffixProvider so project.version (and therefore the API version
// reported by "Implementing API version ...", read from apiVersioning.json) stays in lockstep with
// /ver, the startup banner and the JAR manifest instead of emitting "<mc>.local-SNAPSHOT".
// The branch is read via providers.exec (config-cache compatible) rather than a raw ProcessBuilder
// at configuration time, which the configuration cache forbids.
gradle.lifecycle.beforeProject {
    val branch = providers.exec {
        commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }.getOrElse("")
    val releaseVersionFull = providers.gradleProperty("releaseVersion").getOrElse("dev").trim()
    val releaseMajor = releaseVersionFull.substringBefore('-')
    val codename = providers.gradleProperty("codename").getOrElse("dev").trim()
    val suffix = when {
        branch.startsWith("experimental/") || branch.startsWith("feat/") -> "EXP"
        branch.startsWith("release/") -> "REL"
        branch.contains("-dev") || branch.contains("develop") -> "DEV"
        codename == "dev" -> "DEV"
        else -> "DEV"
    }
    val versionString = when (suffix) {
        "EXP" -> "$releaseMajor-EXP"
        "REL" -> "$releaseMajor-REL"
        else -> "$releaseVersionFull-DEV"
    }
    version = versionString
}

include("Metal")