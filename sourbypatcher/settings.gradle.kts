plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "sourbypatcher"

include("sourbypatcher-core", "paperweight-lib", "sourbypatcher-userdev")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
