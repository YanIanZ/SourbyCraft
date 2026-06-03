plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks.jar {
    archiveBaseName.set("sanity-harness-plugin")
    archiveVersion.set("")  // produces sanity-harness-plugin.jar
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
