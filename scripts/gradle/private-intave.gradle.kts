import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar

// Applied to the server project only with -PincludePrivateIntave=true. Public CI never opts in.
val privatePlan = JsonSlurper().parse(rootProject.file("build-data/intave-private-plan.json")) as Map<*, *>
val upstreamCommit = privatePlan["commit"] as String
val privateRoot = rootProject.layout.projectDirectory.dir(".private-intave/workspaces/$upstreamCommit")
val portFile = privateRoot.file("native-port.json").asFile
check(portFile.isFile) { "Run scripts/integrate_private_intave.py before a private native build" }
val port = JsonSlurper().parse(portFile) as Map<*, *>
check(port["commit"] == upstreamCommit && port["state"] == "NATIVE_CODE_INTEGRATED_UNVERIFIED") {
    "Private native source provenance does not match the plan"
}
val baseline = JsonSlurper().parse(privateRoot.file("workspace.json").asFile) as Map<*, *>
check(baseline["commit"] == upstreamCommit) { "Private baseline commit differs from the plan" }
val expectations = linkedMapOf<String, String>()
for (item in baseline["source_files"] as List<*>) {
    val entry = item as Map<*, *>
    expectations[entry["path"] as String] = entry["sha256"] as String
}
val removed = (port["removed_files"] as List<*>).map { it as String }
removed.forEach { name -> check(expectations.remove(name) != null) { "Unknown/duplicate removed source: $name" } }
val portNames = mutableSetOf<String>()
for (item in port["files"] as List<*>) {
    val entry = item as Map<*, *>
    val name = entry["path"] as String
    check(portNames.add(name) && name !in removed && (name.startsWith("src/") || name.startsWith("generated/"))) {
        "Unsafe/duplicate native port entry: $name"
    }
    expectations[name] = entry["sha256"] as String
}
for (item in baseline["local_compile_libraries"] as List<*>) {
    val entry = item as Map<*, *>
    expectations["libs/" + entry["name"]] = entry["sha256"] as String
}
val packetApi = privatePlan["native_packet_api"] as Map<*, *>
val protocolJar = privateRoot.file("libs/" + packetApi["file"])
check(expectations["libs/" + packetApi["file"]] == packetApi["sha256"]) { "Native packet API hash differs from the build plan" }

// Pure filesystem check, also exercised by the standalone Gradle typecheck probe.
object NativeIntaveInventory {
    fun verify(directory: java.nio.file.Path, recorded: Set<String>, fixtures: Set<String>) {
        val root = directory.toRealPath()
        for (name in listOf("src", "generated", "libs")) {
            val tree = root.resolve(name)
            check(!java.nio.file.Files.isSymbolicLink(tree)) { "Private input directory is a symlink: $name" }
            if (!java.nio.file.Files.exists(tree)) continue
            java.nio.file.Files.walk(tree).use { paths ->
                paths.forEach { file ->
                    val relative = root.relativize(file).toString().replace(java.io.File.separatorChar, '/')
                    check(!java.nio.file.Files.isSymbolicLink(file) && file.toRealPath().startsWith(root)) {
                        "Unsafe private build input: $relative"
                    }
                    if (java.nio.file.Files.isRegularFile(file) && (name != "libs" || relative.endsWith(".jar"))) {
                        check(relative in recorded || relative in fixtures) { "Unrecorded private build input: $relative" }
                    }
                }
            }
        }
    }
}

abstract class VerifyNativeIntaveSources : DefaultTask() {
    @get:Input abstract val hashes: MapProperty<String, String>
    @get:Input abstract val removedPaths: ListProperty<String>
    @get:Input abstract val pinnedFixtures: ListProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val sourceRoot: DirectoryProperty
    @TaskAction fun verify() {
        val directory = sourceRoot.get().asFile.toPath().toRealPath()
        NativeIntaveInventory.verify(directory, hashes.get().keys, pinnedFixtures.get().toSet())
        hashes.get().forEach { (name, expected) ->
            val file = directory.resolve(name).normalize()
            check(file.startsWith(directory) && java.nio.file.Files.isRegularFile(file)
                && file.toRealPath().startsWith(directory)) { "Missing/unsafe private source: $name" }
            val digest = MessageDigest.getInstance("SHA-256")
            java.nio.file.Files.newInputStream(file).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Private source/library changed after import/port: $name" }
        }
        removedPaths.get().forEach { name ->
            check(!java.nio.file.Files.exists(directory.resolve(name))) { "Legacy entrypoint returned: $name" }
        }
    }
}
val verifyPrivateSources = tasks.register<VerifyNativeIntaveSources>("verifyNativeIntaveSources") {
    sourceRoot.set(privateRoot)
    hashes.set(expectations)
    removedPaths.set(removed)
    pinnedFixtures.set((baseline["missing_fixtures"] as List<*>).map { (it as Map<*, *>)["path"] as String })
    sources.from(expectations.keys.map { privateRoot.file(it) })
    sources.from(fileTree(privateRoot.dir("src")), fileTree(privateRoot.dir("generated")), fileTree(privateRoot.dir("libs")))
}

abstract class VerifyNativeIntaveFixtures : DefaultTask() {
    @get:Input abstract val gitBlobs: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtures: ConfigurableFileCollection
    @get:Internal abstract val sourceRoot: DirectoryProperty
    @TaskAction fun verify() {
        val directory = sourceRoot.get().asFile.toPath().toRealPath()
        gitBlobs.get().forEach { (name, expected) ->
            val file = directory.resolve(name).normalize()
            check(file.startsWith(directory) && java.nio.file.Files.isRegularFile(file)
                && file.toRealPath().startsWith(directory)) { "Pinned native resource/replay is missing: $name" }
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${java.nio.file.Files.size(file)}\u0000".toByteArray(Charsets.US_ASCII))
            java.nio.file.Files.newInputStream(file).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Pinned native resource/replay hash mismatch: $name" }
        }
    }
}
val fixtureHashes = (baseline["missing_fixtures"] as List<*>).associate { item ->
    val entry = item as Map<*, *>
    (entry["path"] as String) to (entry["sha"] as String)
}
val verifyPrivateFixtures = tasks.register<VerifyNativeIntaveFixtures>("verifyNativeIntaveFixtures") {
    sourceRoot.set(privateRoot)
    gitBlobs.set(fixtureHashes)
    fixtures.from(fixtureHashes.keys.map { privateRoot.file(it) })
}

repositories {
    exclusiveContent {
        // These are published runtime APIs, separate from the private Sourby toolchain.
        forRepository { mavenCentral() }
        filter { includeGroup("ac.intave") }
    }
    maven("https://repo.opencollab.dev/maven-snapshots/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}
dependencies {
    for (item in privatePlan["dependencies"] as List<*>) {
        val dependency = item as Map<*, *>
        val coordinate = dependency["coordinate"] as String
        if (dependency["configuration"] == "implementation") {
            val artifact = create(coordinate) as org.gradle.api.artifacts.ExternalModuleDependency
            if (coordinate.startsWith("ac.intave:")) artifact.isTransitive = false
            add("implementation", artifact)
        } else if (coordinate.startsWith("org.geysermc:") || coordinate.startsWith("com.github.retrooper:")) {
            add("compileOnly", coordinate)
        }
    }
    // Old NMS APIs are compile compatibility only. Runtime remains SourbyCraft's own 26.2 server/API.
    add("compileOnly", files(fileTree(privateRoot.dir("libs")) { include("*.jar") }.files.sorted()))
    add("testCompileOnly", files(fileTree(privateRoot.dir("libs")) { include("*.jar") }.files.sorted()))
    add("testImplementation", files(protocolJar))
}
extensions.configure<SourceSetContainer> {
    named("main") {
        java.srcDir(privateRoot.dir("src/main/java"))
        java.srcDir(privateRoot.dir("generated/main/java"))
        resources.srcDir(privateRoot.dir("src/main/resources"))
        resources.srcDir(privateRoot.dir("src/bundled/resources"))
    }
    named("test") {
        java.srcDir(privateRoot.dir("src/test/java"))
        resources.srcDir(privateRoot.dir("src/test/resources"))
        resources.srcDir(privateRoot.dir("src/bundled/resources"))
    }
}
tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    dependsOn(verifyPrivateSources, verifyPrivateFixtures)
    // The server's suite-only include otherwise omits the upstream private test classes.
    include("dev/yanianz/intave/**", "de/jpx3/classloader/**")
}
tasks.named("compileJava") { dependsOn(verifyPrivateSources) }
tasks.named("processResources") { dependsOn(verifyPrivateSources) }
tasks.named<Jar>("jar") {
    dependsOn(verifyPrivateSources, verifyPrivateFixtures)
    // Embed packet-access classes as a library; no ProtocolLib plugin artifact or runtime loader.
    from(zipTree(protocolJar)) {
        exclude("plugin.yml", "paper-plugin.yml", "config.yml", "META-INF/MANIFEST.MF",
            "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }
    from(privateRoot.file("LICENSE.md")) { into("META-INF/licenses/intave") }
    manifest.attributes("SourbyCraft-Private-Intave" to upstreamCommit)
}
