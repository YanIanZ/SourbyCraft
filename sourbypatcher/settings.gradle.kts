plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hyacinthusweight"

include("hyacinthusweight-core", "paperweight-lib", "hyacinthusweight-userdev")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
