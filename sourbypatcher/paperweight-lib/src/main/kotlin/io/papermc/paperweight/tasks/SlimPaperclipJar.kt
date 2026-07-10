/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*

/**
 * Strips a configurable set of optional runtime libraries out of a fat paperclip
 * jar, injects a `META-INF/sourby-bootstrap-manifest.json` describing them (path,
 * download URL, sha256, size), rewrites `Main-Class` to a first-boot bootstrap,
 * and copies the bootstrap `.class` files to the slim jar root so the JVM can
 * resolve the new Main-Class.
 *
 * The bootstrap (SourbyLoader) downloads the externalized libs into the paperclip
 * `libraries/` dir on first boot with SHA-256 verification, then delegates to the
 * paperclip fork's real Main-Class (hyacinthusclip on Folia). This ports the
 * legacy Paper `createSlimPaperclipJar` logic into a reusable SourbyPatcher task.
 *
 * The libraries.list file is preserved UNCHANGED: paperclip's loader uses it to
 * add every `libraries/<path>` to the runtime classpath and it verifies each file
 * by SHA-256. When the bootstrap has already written a lib with a matching SHA,
 * paperclip's check passes and it skips (re-)extraction — the lib still lands on
 * the classpath. Filtering libraries.list here would drop those libs from the
 * classpath entirely.
 */
abstract class SlimPaperclipJar : DefaultTask() {

    @get:Inject
    abstract val objects: ObjectFactory

    /** The fat paperclip jar produced by createPaperclipJar. */
    @get:Classpath
    abstract val fatJar: RegularFileProperty

    /**
     * The server module jar containing the bootstrap `.class` files. Paperclip
     * folds the server jar into a binary `.jar.patch` inside the fat jar, so the
     * bootstrap classes are unreachable at the outer-jar level — we extract them
     * from the plain server jar and re-add them at the slim jar root.
     */
    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val serverJar: RegularFileProperty

    /**
     * Paths (relative to `META-INF/libraries/`) to externalize, paired with the
     * base repository URL to download each from. Order is preserved for a
     * deterministic manifest.
     */
    @get:Input
    abstract val libPaths: ListProperty<String>

    @get:Input
    abstract val libUrls: ListProperty<String>

    /** Fully-qualified class name to write as the slim jar's Main-Class. */
    @get:Input
    abstract val mainClass: Property<String>

    /** Package prefix (slash form) whose `.class` files are copied to the jar root. */
    @get:Input
    abstract val bootstrapClassPrefix: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    init {
        mainClass.convention("dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap")
        bootstrapClassPrefix.convention("dev/iyanz/sourbycraft/bootstrap/")
    }

    @TaskAction
    fun run() {
        val fatJarFile = fatJar.get().asFile
        require(fatJarFile.exists()) { "fat jar does not exist: $fatJarFile" }
        val serverJarFile = serverJar.get().asFile
        require(serverJarFile.exists()) { "server jar does not exist: $serverJarFile" }

        val out = outputJar.get().asFile
        out.parentFile.mkdirs()

        val paths = libPaths.get()
        val urls = libUrls.get()
        require(paths.size == urls.size) { "libPaths / libUrls length mismatch" }
        val libs = paths.zip(urls)

        val externalizedJarEntries = paths.map { "META-INF/libraries/$it" }.toSet()
        val manifestEntries = mutableListOf<Map<String, Any>>()

        // Step A: enumerate externalized lib bytes, compute sha256 + size.
        JarFile(fatJarFile).use { jar ->
            for ((path, baseUrl) in libs) {
                val entryName = "META-INF/libraries/$path"
                val entry = jar.getJarEntry(entryName)
                    ?: throw PaperweightException(
                        "fat jar missing expected entry: $entryName " +
                            "(check externalLibs against current paperclip output)"
                    )
                val bytes = jar.getInputStream(entry).use { it.readAllBytes() }
                val md = MessageDigest.getInstance("SHA-256")
                val sha256 = md.digest(bytes).joinToString("") { b: Byte -> "%02x".format(b) }
                val downloadUrl = joinUrl(baseUrl, path)
                manifestEntries.add(
                    linkedMapOf(
                        "paperclipPath" to path,
                        "downloadUrl" to downloadUrl,
                        "sha256" to sha256,
                        "sizeBytes" to bytes.size.toLong()
                    )
                )
            }
        }

        // Step B: build the manifest JSON (deterministic order).
        val manifestJson = buildString {
            append("{\"entries\":[")
            manifestEntries.forEachIndexed { i, e ->
                if (i > 0) append(",")
                append("{")
                append("\"paperclipPath\":\"${e["paperclipPath"]}\",")
                append("\"downloadUrl\":\"${e["downloadUrl"]}\",")
                append("\"sha256\":\"${e["sha256"]}\",")
                append("\"sizeBytes\":${e["sizeBytes"]}")
                append("}")
            }
            append("]}")
        }

        // Step C: read libraries.list UNCHANGED (see class doc).
        val librariesList: String = JarFile(fatJarFile).use { jar ->
            val listEntry = jar.getJarEntry("META-INF/libraries.list")
                ?: throw PaperweightException("fat jar missing META-INF/libraries.list")
            jar.getInputStream(listEntry).use { String(it.readAllBytes(), Charsets.UTF_8) }
        }

        // Step D: rewrite MANIFEST.MF Main-Class.
        val newMainClass = mainClass.get()
        val newManifestMf: String = JarFile(fatJarFile).use { jar ->
            val mfEntry = jar.getJarEntry("META-INF/MANIFEST.MF")
                ?: throw PaperweightException("fat jar missing META-INF/MANIFEST.MF")
            val original = jar.getInputStream(mfEntry).use { String(it.readAllBytes(), Charsets.UTF_8) }
            val mainClassRe = Regex("(?m)^Main-Class:.*\\r?\\n?")
            val replacement = "Main-Class: $newMainClass\r\n"
            if (mainClassRe.containsMatchIn(original)) {
                original.replace(mainClassRe, replacement)
            } else {
                original.trimEnd() + "\r\n" + replacement + "\r\n"
            }
        }

        // Step E1: extract bootstrap .class files from the server jar.
        val prefix = bootstrapClassPrefix.get()
        val bootstrapClassBytes: Map<String, ByteArray> = JarFile(serverJarFile).use { jar ->
            jar.entries().asSequence()
                .filter { it.name.startsWith(prefix) && it.name.endsWith(".class") }
                .associate { e -> e.name to jar.getInputStream(e).use { it.readAllBytes() } }
        }
        if (bootstrapClassBytes.isEmpty()) {
            throw PaperweightException(
                "No bootstrap .class files found in ${serverJarFile.name}; expected entries at $prefix*.class"
            )
        }
        logger.lifecycle("slimPaperclipJar: copying ${bootstrapClassBytes.size} bootstrap class(es) to slim jar root")

        // Step E2: write the slim jar.
        JarFile(fatJarFile).use { jar ->
            JarOutputStream(out.outputStream().buffered()).use { jos ->
                for (entry in jar.entries()) {
                    val name = entry.name
                    when {
                        name in externalizedJarEntries -> continue // externalized lib
                        name == "META-INF/libraries.list" -> continue // re-added below
                        name == "META-INF/MANIFEST.MF" -> continue // re-added below
                        name in bootstrapClassBytes.keys -> continue // re-added below (avoid dupe)
                        else -> {
                            jos.putNextEntry(JarEntry(name))
                            jar.getInputStream(entry).use { it.copyTo(jos) }
                            jos.closeEntry()
                        }
                    }
                }
                jos.putNextEntry(JarEntry("META-INF/libraries.list"))
                jos.write(librariesList.toByteArray(Charsets.UTF_8))
                jos.closeEntry()
                jos.putNextEntry(JarEntry("META-INF/sourby-bootstrap-manifest.json"))
                jos.write(manifestJson.toByteArray(Charsets.UTF_8))
                jos.closeEntry()
                jos.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
                jos.write(newManifestMf.toByteArray(Charsets.UTF_8))
                jos.closeEntry()
                for ((entryName, bytes) in bootstrapClassBytes) {
                    jos.putNextEntry(JarEntry(entryName))
                    jos.write(bytes)
                    jos.closeEntry()
                }
            }
        }

        logger.lifecycle("slimPaperclipJar: wrote ${out.length() / 1024 / 1024}M to ${out.absolutePath}")
    }

    private fun joinUrl(base: String, path: String): String =
        if (base.endsWith("/")) base + path else "$base/$path"
}
