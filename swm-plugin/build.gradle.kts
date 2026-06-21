plugins {
    `java-library`
}

group = "dev.iyanz.sourbycraft"
version = "5.2.0"
description = "SourbyCraft SlimeWorldManager Plugin (ASP dev/26.2 parity)"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(fileTree("../sourbycraft-server/build/libs") { include("*-server-*.jar"); exclude("*-paperclip-*", "*-bundler-*") })
    compileOnly("org.mongodb:mongodb-driver-sync:4.11.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // SourbyCraft v10.3 — Java 21 bytecode for dual JRE 21/25 support
    options.release.set(21)
}
