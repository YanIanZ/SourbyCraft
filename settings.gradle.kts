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

// Include Star library as composite build
includeBuild("../Plugin-Dev/star") {
    dependencySubstitution {
        substitute(module("dev.yanianz:star-common")).using(project(":star-common"))
        substitute(module("dev.yanianz:star-reflection")).using(project(":star-reflection"))
        substitute(module("dev.yanianz:star-config")).using(project(":star-config"))
        substitute(module("dev.yanianz:star-chat")).using(project(":star-chat"))
        substitute(module("dev.yanianz:star-data")).using(project(":star-data"))
        substitute(module("dev.yanianz:star-skins")).using(project(":star-skins"))
        substitute(module("dev.yanianz:star-items")).using(project(":star-items"))
        substitute(module("dev.yanianz:star-inventories")).using(project(":star-inventories"))
        // substitute(module("dev.yanianz:star-protection")).using(project(":star-protection")) // disabled in star build
        substitute(module("dev.yanianz:star-recipes")).using(project(":star-recipes"))
        substitute(module("dev.yanianz:star-updater")).using(project(":star-updater"))
        substitute(module("dev.yanianz:star-scheduling")).using(project(":star-scheduling"))
        substitute(module("dev.yanianz:star-swm")).using(project(":star-swm"))
        substitute(module("dev.yanianz:star-api")).using(project(":star-api"))
    }
}

// Include SoulBy as composite build (eco rebrand)
includeBuild("../Plugin-Dev/eco/.worktrees/soulby-rebrand") {
    dependencySubstitution {
        substitute(module("dev.yanianz:soulby")).using(project(":soulby-api"))
    }
}
