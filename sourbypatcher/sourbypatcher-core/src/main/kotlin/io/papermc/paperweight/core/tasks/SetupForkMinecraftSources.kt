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

package io.papermc.paperweight.core.tasks

import io.papermc.paperweight.core.util.ApplySourceATs
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.paperTaskOutput
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*

abstract class SetupForkMinecraftSources : JavaLauncherTask() {

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val atWorkingDir: DirectoryProperty

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance()

    @get:InputFile
    @get:Optional
    abstract val atFile: RegularFileProperty

    @get:Optional
    @get:InputDirectory
    abstract val libraryImports: DirectoryProperty

    /**
     * Optional directory of git-format ("git format-patch" style, with `From`/`Subject` headers)
     * "base" feature patches applied to the fork's Minecraft sources AFTER access-transformers +
     * library imports but BEFORE the diffpatch-format `sources/` file patches.
     *
     * This mirrors the three-stage patch model of forks whose own build tool (e.g. CanvasMC's
     * weaver) splits Minecraft patches into `base/` (foundational git-format patches that may
     * CREATE new files — e.g. region-threading — and are relied on for context by the later file
     * patches), `sources/` (per-file diffpatch patches) and `features/` (git-format feature
     * patches). Upstream paperweight only had the sources+features stages, so when such a fork is
     * consumed as a read-only upstream its `base/` patches were silently dropped, leaving the
     * `sources/` file patches without the files/context they expect. Setting this makes the fork's
     * `base/` patches materialise before the file patches. Unset (the default) = no base stage,
     * fully backward compatible with forks that have no `base/` dir.
     */
    @get:Optional
    @get:InputDirectory
    abstract val basePatchDir: DirectoryProperty

    @get:Input
    abstract val identifier: Property<String>

    override fun init() {
        super.init()
        atWorkingDir.set(layout.cache.resolve(paperTaskOutput(name = "${name}_atWorkingDir")))
    }

    @TaskAction
    fun run() {
        val out = outputDir.path.cleanDir()
        inputDir.path.copyRecursivelyTo(out)

        val git = Git.open(outputDir.path.toFile())

        if (atFile.isPresent && atFile.path.readText().isNotBlank()) {
            println("Applying access transformers...")
            ats.run(
                launcher.get(),
                inputDir.path,
                outputDir.path,
                atFile.path,
                atWorkingDir.path,
            )
            commitAndTag(git, "ATs", "${identifier.get()} ATs")
        }

        if (libraryImports.isPresent) {
            libraryImports.path.walk().forEach {
                val outFile = out.resolve(it.relativeTo(libraryImports.path).invariantSeparatorsPathString)
                // The file may already exist if upstream imported it
                if (!outFile.exists()) {
                    it.copyTo(outFile.createParentDirectories())
                }
            }

            commitAndTag(git, "Imports", "${identifier.get()} Imports")
        }

        // Fork "base" feature patches (git format-patch style), applied last so the later
        // diffpatch-format sources/ file patches see the files + context these create. See the
        // basePatchDir kdoc for the three-stage-fork rationale. git am handles the format natively.
        if (basePatchDir.isPresent) {
            applyBasePatches(outputDir.path)
        }

        git.close()
    }

    private fun applyBasePatches(repoPath: java.nio.file.Path) {
        val patchFiles = basePatchDir.path.useDirectoryEntries("*.patch") { it.sorted().toMutableList() }
        if (patchFiles.isEmpty()) {
            return
        }
        println("Applying ${patchFiles.size} ${identifier.get()} base patches...")
        // Use the command-line git wrapper (io.papermc.paperweight.util.Git) for `git am`, matching
        // ApplyFeaturePatches; the file already binds the bare `Git` name to org.eclipse.jgit, so
        // the util one is fully qualified here.
        val cmdGit = io.papermc.paperweight.util.Git(repoPath)
        cmdGit("am", "--abort").runSilently(silenceErr = true)

        layout.cache.createDirectories()
        val tempDir = createTempDirectory(layout.cache, "paperweight-base")
        try {
            val mailDir = tempDir.resolve("new")
            mailDir.createDirectories()
            for (patch in patchFiles) {
                patch.copyTo(mailDir.resolve(patch.fileName))
            }
            val result = cmdGit("am", "--3way", "--ignore-whitespace", tempDir.absolutePathString()).captureOut(false)
            if (result.exit != 0) {
                cmdGit("am", "--abort").runSilently(silenceErr = true)
                logger.lifecycle(result.out)
                throw io.papermc.paperweight.PaperweightException(
                    "Failed to apply ${identifier.get()} base patches"
                )
            }
        } finally {
            tempDir.deleteRecursive()
        }
    }
}
