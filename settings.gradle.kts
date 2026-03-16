enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "CloudPlane"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("test-plugin")

listOf("api", "server").forEach {
    // only include subprojects if buildscript can be resolved
    if (file("cloudplane-$it/buildscript").exists()) {
        include("cloudplane-$it")
    }
}
