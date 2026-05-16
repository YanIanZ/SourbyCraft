plugins {
    `java-library`
}

group = "dev.iyanz.sourbycraft"
version = "3.0.0"
description = "SourbyCraft SlimeWorldManager Plugin"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(fileTree("../sourbycraft-server/build/libs") { include("*-server-*-REL.jar") })
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}
