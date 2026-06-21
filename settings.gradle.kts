enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "SourbyCraft"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("test-plugin")

listOf("api", "swm-api", "server").forEach {
    // only include subprojects if buildscript can be resolved
    if (file("sourbycraft-$it/buildscript").exists()) {
        include("sourbycraft-$it")
    }
}

// sourbycraft-swm-api doesn't need buildscript submodule
include("sourbycraft-swm-api")

// SourbyCraft v12 — NMS-compat smoke harness plugin (loaded into TestServer-mojmap/plugins/).
include("test-harness:sanity-harness-plugin")
project(":test-harness:sanity-harness-plugin").projectDir = file("test-harness/sanity-harness-plugin")


