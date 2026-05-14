enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "SourbyCraft"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("test-plugin")
include("swm-plugin")

listOf("api", "server").forEach {
    // only include subprojects if buildscript can be resolved
    if (file("sourbycraft-$it/buildscript").exists()) {
        include("sourbycraft-$it")
    }
}
