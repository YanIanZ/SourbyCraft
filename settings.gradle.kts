pluginManagement {
    val weightVersion: String by settings

    repositories {
        gradlePluginPortal()
        mavenLocal()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven { url = uri("${rootDir}/sourby-maven") }
    }

    plugins {
        id("dev.iyanz.sourbypatcher.patcher") version weightVersion
        id("dev.iyanz.sourbypatcher.core") version weightVersion
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "sourbycraft"

for (name in listOf("sourbycraft-api", "sourbycraft-server")) {
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
        branch.contains("experimental") || branch.contains("feat") -> "EXP"
        branch.startsWith("release/") -> "REL"
        branch.contains("-dev") || branch.contains("develop") -> "DEV"
        codename == "dev" -> "DEV"
        else -> "REL"
    }
    val versionString = when (suffix) {
        "EXP" -> "$releaseMajor-EXP"
        "REL" -> "$releaseMajor-REL"
        else -> "$releaseVersionFull-DEV"
    }
    version = versionString
}

include("Metal")