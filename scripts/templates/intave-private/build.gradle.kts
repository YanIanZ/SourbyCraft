import java.security.MessageDigest

plugins { java }

group = "dev.yanianz.intave"
version = "private-preparation"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.dmulloy2.net/repository/public/")
}

dependencies {
@DEPENDENCIES@
    compileOnly(files(fileTree("libs") { include("*.jar") }.files.sorted()))
}

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }
sourceSets.main { java.srcDir("generated/main/java") }
sourceSets.test { resources.srcDir("src/bundled/resources") }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

// This project verifies the relocated upstream baseline. It cannot produce a server or plugin JAR.
tasks.jar { enabled = false }
if (layout.projectDirectory.file("native-port.json").asFile.exists()) {
    tasks.withType<JavaCompile>().configureEach {
        doFirst { throw GradleException("Native sources compile in the SourbyCraft server: use root -PincludePrivateIntave=true. Upstream baseline is preserved in verified-upstream/.") }
    }
}

abstract class ResolvePrivateDependencies : DefaultTask() {
    @get:Classpath abstract val artifacts: ConfigurableFileCollection
    @TaskAction fun resolve() {
        logger.lifecycle("Resolved {} private baseline dependency files; native port is still pending.", artifacts.files.size)
    }
}
tasks.register<ResolvePrivateDependencies>("resolvePrivateDependencies") {
    artifacts.from(configurations.compileClasspath, configurations.runtimeClasspath,
        configurations.testCompileClasspath, configurations.testRuntimeClasspath)
}

abstract class VerifyPrivateFixtures : DefaultTask() {
    @get:Input abstract val expectations: ListProperty<String>
    @get:Internal abstract val sourceRoot: DirectoryProperty
    @TaskAction fun verify() {
        val failures = expectations.get().mapNotNull { expectation ->
            val (expected, name) = expectation.split('\t', limit = 2)
            val file = sourceRoot.file(name).get().asFile
            if (!file.isFile) return@mapNotNull name
            val bytes = file.readBytes()
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
            val actual = digest.digest(bytes).joinToString("") { "%02x".format(it) }
            if (actual != expected) name else null
        }
        check(failures.isEmpty()) { "Pinned fixture/native resources missing or mismatched:\n${failures.joinToString("\n")}" }
    }
}
val verifyPrivateFixtures = tasks.register<VerifyPrivateFixtures>("verifyPrivateFixtures") {
    sourceRoot.set(layout.projectDirectory)
    expectations.set(listOf(
@FIXTURES@
    ))
}
tasks.test {
    dependsOn(verifyPrivateFixtures)
    useJUnitPlatform()
    failOnNoDiscoveredTests = true
}
