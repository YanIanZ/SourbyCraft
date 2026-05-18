plugins {
    `java-library`
    `maven-publish`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    api(projects.sourbycraftApi)
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

    from(zipTree(file("../sourbycraft-server/build/libs/sourbycraft-server-v5-REL.jar"))) {
        include("dev/iyanz/sourbycraft/swm/api/**")
    }
}

tasks.jar {
    enabled = false
}

artifacts {
    add("archives", extractApi)
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifact(extractApi.get())
        groupId = "dev.iyanz.sourbycraft"
        artifactId = "sourbycraft-swm-api"
        version = project.version.toString()
    }
}
