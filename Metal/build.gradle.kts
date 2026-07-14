plugins {
    val indraVer = "3.2.0"
    //id("net.kyori.indra") version indraVer
    //id("net.kyori.indra.publishing") version indraVer
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.18")
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("at.yawk.lz4:lz4-java:1.11.0")
    implementation("com.github.luben:zstd-jni:1.5.7-11")
    implementation("org.yaml:snakeyaml:2.6")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    manifest {
        attributes("FMLModType" to "GAMELIBRARY")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()

    maxHeapSize = "1G"

    testLogging {
        events("passed")
    }
}

/*indra {
    javaVersions {
        target(25)
    }
    publishSnapshotsTo("paperSnapshots", "https://repo.papermc.io/repository/maven-snapshots/")
    publishReleasesTo("paperReleases", "https://repo.papermc.io/repository/maven-releases/")
    gpl3OnlyLicense()
    github("Tuinity", "LeafPile")
    configurePublications {
        pom {
            developers {
                developer {
                    id = "spottedleaf"
                }
            }
        }
    }
    signWithKeyFromProperties("signingKey", "signingPassword")
}*/
