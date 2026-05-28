plugins {
    `java-library`
    `maven-publish`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// SourbyCraft v10.3 - guard against missing sourbycraft-api project during CI fresh-clone
// (buildscript dir doesn't exist until applyAllPatches runs)
dependencies {
    if (rootProject.findProject(":sourbycraft-api") != null) {
        api(project(":sourbycraft-api"))
    }
}

// No compilation — SWM API classes extracted from built server JAR.
// This avoids NMS circular dependency: server compiles SWM API internally,
// this module repackages the API classes for external plugin consumption.

tasks.compileJava {
    enabled = false
}

tasks.processResources {
    enabled = false
}

val extractApi by tasks.registering(Jar::class) {
    dependsOn(":sourbycraft-server:jar")
    archiveFileName.set("sourbycraft-swm-api-${project.version}.jar")

    from(zipTree(file("../sourbycraft-server/build/libs/sourbycraft-server-v7-REL.jar"))) {
        include("dev/iyanz/sourbycraft/swm/api/**")
    }
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(extractApi)
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifact(extractApi.get())
        groupId = "dev.iyanz.sourbycraft"
        artifactId = "sourbycraft-swm-api"
        version = project.version.toString()
    }
}
