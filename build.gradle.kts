import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java // TODO java launcher tasks
    id("dev.iyanz.sourbypatcher.patcher")
}

paperweight {
    filterPatches = false
    upstreams.register("folia") {
        repo = github("PaperMC", "Folia")
        ref = providers.gradleProperty("foliaRef")

        println("Upstream commit ref: " + ref.get())

        patchFile {
            path = "folia-server/build.gradle.kts"
            outputFile = file("sourbycraft-server/build.gradle.kts")
            patchFile = file("sourbycraft-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "folia-api/build.gradle.kts"
            outputFile = file("sourbycraft-api/build.gradle.kts")
            patchFile = file("sourbycraft-api/build.gradle.kts.patch")
        }
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("sourbycraft-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("foliaApi") {
            upstreamPath = "folia-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("sourbycraft-api/folia-patches")
            outputDir = file("folia-api")
        }
    }

    // SourbyLoader — libs stripped from the fat paperclip jar + fetched on first boot
    // (SourbyBootstrap). Paths are relative to META-INF/libraries/; the download URL is
    // baseUrl + path. Only independently-resolvable libs are externalized — paperclip's own
    // downloader deps (maven-resolver/sisu/httpclient/commons-codec) and our modules stay bundled.
    val mavenCentral = "https://repo1.maven.org/maven2"
    val paperRepo = "https://repo.papermc.io/repository/maven-public"
    externalLib("org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar", mavenCentral)
    externalLib("com/github/luben/zstd-jni/1.5.7-11/zstd-jni-1.5.7-11.jar", mavenCentral)
    externalLib("me/lucko/spark-paper/1.10.152/spark-paper-1.10.152.jar", paperRepo)
    externalLib("com/mysql/mysql-connector-j/9.2.0/mysql-connector-j-9.2.0.jar", mavenCentral)
    externalLib("com/google/protobuf/protobuf-java/4.29.0/protobuf-java-4.29.0.jar", mavenCentral)
    externalLib("io/sentry/sentry/8.0.0-rc.2/sentry-8.0.0-rc.2.jar", mavenCentral)
    externalLib("net/kyori/adventure-api/5.2.0/adventure-api-5.2.0.jar", mavenCentral)
    externalLib("net/kyori/adventure-text-minimessage/5.2.0/adventure-text-minimessage-5.2.0.jar", mavenCentral)
    externalLib("org/spongepowered/configurate-yaml/4.2.0/configurate-yaml-4.2.0.jar", mavenCentral)
    externalLib("org/spongepowered/configurate-core/4.2.0/configurate-core-4.2.0.jar", mavenCentral)
    externalLib("org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar", mavenCentral)
    externalLib("commons-lang/commons-lang/2.6/commons-lang-2.6.jar", mavenCentral)
    externalLib("io/github/classgraph/classgraph/4.8.158/classgraph-4.8.158.jar", mavenCentral)
    externalLib("com/electronwill/night-config/core/3.9.0/core-3.9.0.jar", mavenCentral)
    externalLib("com/electronwill/night-config/toml/3.9.0/toml-3.9.0.jar", mavenCentral)
    externalLib("org/jline/jline-terminal/3.27.1/jline-terminal-3.27.1.jar", mavenCentral)
    externalLib("org/jline/jline-native/3.27.1/jline-native-3.27.1.jar", mavenCentral)
    externalLib("org/jline/jline-reader/3.20.0/jline-reader-3.20.0.jar", mavenCentral)
}

// Wire the SourbyPatcher slim task to this build's server paperclip + server jars.
tasks.named<io.papermc.paperweight.tasks.SlimPaperclipJar>("slimPaperclipJar") {
    val serverProj = project(":sourbycraft-server")
    dependsOn("${serverProj.path}:createPaperclipJar", "${serverProj.path}:jar")
    fatJar.fileProvider(
        serverProj.tasks.named("createPaperclipJar").map { t ->
            t.outputs.files.files.first { it.name.contains("paperclip") && it.name.endsWith(".jar") }
        }
    )
    serverJar.fileProvider(
        serverProj.tasks.named("jar").map { t ->
            t.outputs.files.files.first { it.name.endsWith(".jar") }
        }
    )
    outputJar.set(layout.buildDirectory.file("libs/SourbyCraft-slim.jar"))
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven { url = uri("${rootDir}/sourby-maven") }
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    tasks.withType<Javadoc>().configureEach {
        options {
            (this as StandardJavadocDocletOptions).apply {
                addStringOption("-add-modules", "jdk.incubator.vector")
                addStringOption("Xdoclint:none", "-quiet")
            }
        }
    }
}
