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
    compileOnly("io.papermc.paper:paper-api:1.21.3-R0.1-SNAPSHOT")
    compileOnly(files("../sourbycraft-server/build/libs/sourbycraft-server-v3-REL.jar"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}
