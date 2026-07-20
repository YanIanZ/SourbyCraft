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

package io.papermc.paperweight.core.tasks.patching

import codechicken.diffpatch.cli.PatchOperation
import codechicken.diffpatch.match.FuzzyLineMatcher
import codechicken.diffpatch.util.LoggingOutputStream
import codechicken.diffpatch.util.PatchMode
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.core.util.ApplySourceATs
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.URIish
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.*

abstract class ApplyFilePatches : JavaLauncherTask() {

    @get:Input
    @get:Option(
        option = "verbose",
        description = "Prints out more info about the patching process",
    )
    abstract val verbose: Property<Boolean>

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    @get:Optional
    abstract val patches: DirectoryProperty

    @get:Internal
    abstract val rejectsDir: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val gitFilePatches: Property<Boolean>

    @get:Optional
    @get:Input
    abstract val baseRef: Property<String>

    /**
     * Optional directory of git-format ("git format-patch" style) "base" patches applied (via
     * `git am --3way`) immediately after the upstream checkout and BEFORE the `base` tag is created
     * — so they are part of the base the file patches ([patches]) build on, and the rebuild/fixup
     * `base` ref includes them. Mirrors the three-stage patch model of forks whose own build tool
     * (e.g. CanvasMC's weaver) splits a patch set into base/ (git-format, may create files) →
     * files/ (diffpatch) → features/ (git-format). Upstream paperweight only had the files+features
     * stages, so a consumed fork's base/ patches were silently dropped. Unset (default) = no base
     * stage, fully backward compatible.
     */
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    abstract val basePatchDir: DirectoryProperty

    /**
     * Optional access-transformer file applied (via JST) to the freshly-checked-out sources BEFORE
     * the base + file patches and BEFORE the `base` tag. Replicates the per-layer AT application a
     * fork's own build tool performs (e.g. CanvasMC's weaver auto-applies {@code build-data/
     * paperServer.at} to its paper-server layer). Without it, members the fork widens (e.g.
     * {@code public TickThread.getThreadContext()}) stay at their original visibility, so the fork's
     * exact-context base patches — generated against the AT'd tree — fail to apply. Requires [ats]
     * (jst + jstClasspath) to be configured. Unset (default) = no AT step, fully backward compatible.
     */
    @get:Optional
    @get:InputFile
    abstract val atFile: RegularFileProperty

    /** JST access-transformer runner used by [atFile]. Populate its jst + jstClasspath when [atFile] is set. */
    @get:Nested
    val ats: ApplySourceATs = objects.newInstance()

    @get:Input
    @get:Optional
    abstract val identifier: Property<String>

    // An additional remote to add and fetch from before applying patches (to bring in objects for 3-way merge).
    @get:Input
    @get:Optional
    abstract val additionalRemote: Property<String>

    @get:Input
    abstract val additionalRemoteName: Property<String>

    @get:Input
    abstract val moveFailedGitPatchesToRejects: Property<Boolean>

    @get:Internal
    abstract val emitRejects: Property<Boolean>

    init {
        run {
            verbose.convention(false)
            gitFilePatches.convention(false)
            additionalRemoteName.convention("old")
            moveFailedGitPatchesToRejects.convention(false)
            emitRejects.convention(true)
        }
    }

    @TaskAction
    open fun run() {
        io.papermc.paperweight.util.Git.checkForGit()

        val outputPath = output.path
        recreateCloneDirectory(outputPath)

        checkoutRepoFromUpstream(
            Git(outputPath),
            input.path,
            baseRef.getOrElse("main"),
            "upstream",
            "main",
            baseRef.isPresent,
        )

        if (additionalRemote.isPresent) {
            val jgit = Git.open(outputPath.toFile())
            jgit.remoteRemove().setRemoteName(additionalRemoteName.get()).call()
            jgit.remoteAdd().setName(additionalRemoteName.get()).setUri(URIish(additionalRemote.get())).call()
            jgit.fetch().setRemote(additionalRemoteName.get()).call()
        }

        setupGitHook(outputPath)

        // Per-layer access transformers (e.g. CanvasMC weaver's build-data/paperServer.at), applied
        // to the checkout BEFORE base/file patches so the fork's exact-context patches — generated
        // against the AT'd tree — line up. No-op when atFile is absent. See atFile kdoc.
        if (atFile.isPresent && atFile.path.readText().isNotBlank()) {
            applyAccessTransformers(outputPath)
        }

        // Three-stage forks (e.g. CanvasMC): apply the fork's git-format base/ patches BEFORE
        // tagging `base`, so the file patches below build on the base-applied tree and the rebuild
        // `base` ref includes them. No-op when basePatchDir is absent. See basePatchDir kdoc.
        if (basePatchDir.isPresent) {
            applyBasePatches(outputPath)
        }

        tagBase()

        val result = if (!patches.isPresent) {
            commit()
            0
        } else if (gitFilePatches.get()) {
            applyWithGit(outputPath)
        } else {
            applyWithDiffPatch()
        }

        if (!verbose.get()) {
            logger.lifecycle("Applied $result patches")
        }
    }

    private fun recreateCloneDirectory(target: Path) {
        if (target.exists()) {
            if (target.resolve(".git").isDirectory()) {
                val git = Git(target)
                git("clean", "-fxd").runSilently(silenceErr = true)
                git("reset", "--hard", "HEAD").runSilently(silenceErr = true)
            } else {
                for (entry in target.listDirectoryEntries()) {
                    entry.deleteRecursive()
                }
                target.createDirectories()
            }
        } else {
            target.createDirectories()
        }
    }

    private fun tagBase() {
        val git = Git.open(output.path.toFile())
        val ident = PersonIdent("base", "noreply+automated@papermc.io")
        git.tagDelete().setTags("base").call()
        git.tag().setName("base").setTagger(ident).setSigned(false).call()
        git.close()
    }

    private fun applyAccessTransformers(outputPath: Path) {
        logger.lifecycle("Applying ${identifier.getOrElse("")} access transformers (${atFile.path.fileName})...")
        val workDir = layout.cache.resolve(io.papermc.paperweight.util.constants.paperTaskOutput(name = "${name}_atWorkingDir"))
        // JST in place: read + rewrite the same checkout dir with the access-widened members.
        ats.run(launcher.get(), outputPath, outputPath, atFile.path, workDir)
        val git = Git.open(outputPath.toFile())
        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage("Access Transformers")
            .setAuthor(PersonIdent("AT", "noreply+automated@papermc.io"))
            .setAllowEmpty(true)
            .setSign(false)
            .call()
        git.close()
    }

    private fun applyBasePatches(outputPath: Path) {
        val patchFiles = basePatchDir.path.filesMatchingRecursive("*.patch").sorted()
        if (patchFiles.isEmpty()) {
            return
        }
        logger.lifecycle("Applying ${patchFiles.size} ${identifier.getOrElse("")} base patches...")
        val git = Git(outputPath)
        git("am", "--abort").runSilently(silenceErr = true)
        layout.cache.createDirectories()
        val tempDir = createTempDirectory(layout.cache, "paperweight-base")
        try {
            val mailDir = tempDir.resolve("new")
            mailDir.createDirectories()
            for (patch in patchFiles) {
                patch.copyTo(mailDir.resolve(patch.fileName))
            }
            val result = git("am", "--3way", "--ignore-whitespace", tempDir.absolutePathString()).captureOut(false)
            if (result.exit != 0) {
                git("am", "--abort").runSilently(silenceErr = true)
                logger.lifecycle(result.out)
                throw PaperweightException("Failed to apply ${identifier.getOrElse("")} base patches")
            }
        } finally {
            tempDir.deleteRecursive()
        }
    }

    private fun applyWithGit(outputPath: Path): Int {
        val git = Git(outputPath)
        val patchFiles = patches.path.filesMatchingRecursive("*.patch")
        if (moveFailedGitPatchesToRejects.get() && rejectsDir.isPresent) {
            patchFiles.forEach { patch ->
                val patchPathFromGit = outputPath.relativize(patch)
                val responseCode =
                    git(
                        "-c",
                        "rerere.enabled=false",
                        "apply",
                        "--3way",
                        patchPathFromGit.pathString
                    ).runSilently(silenceOut = !verbose.get(), silenceErr = !verbose.get())
                when {
                    responseCode == 0 -> {}
                    responseCode > 1 -> throw PaperweightException("Failed to apply patch $patch: $responseCode")
                    responseCode == 1 -> {
                        val relativePatch = patches.path.relativize(patch)
                        val failedFile = relativePatch.parent.resolve(relativePatch.fileName.toString().substringBeforeLast(".patch"))
                        if (outputPath.resolve(failedFile).exists()) {
                            git("reset", "--", failedFile.pathString).executeSilently(silenceOut = !verbose.get(), silenceErr = !verbose.get())
                            git("restore", failedFile.pathString).executeSilently(silenceOut = !verbose.get(), silenceErr = !verbose.get())
                        }

                        val rejectFile = rejectsDir.path.resolve(relativePatch)
                        patch.moveTo(rejectFile.createParentDirectories(), overwrite = true)
                    }
                }
            }
        } else {
            val patchStrings = patchFiles.map { outputPath.relativize(it).pathString }
            patchStrings.chunked(12).forEach {
                git("apply", "--3way", *it.toTypedArray()).executeSilently(silenceOut = !verbose.get(), silenceErr = !verbose.get())
            }
        }

        commit()

        return patchFiles.size
    }

    private fun applyWithDiffPatch(): Int {
        val printStream = PrintStream(LoggingOutputStream(logger, LogLevel.LIFECYCLE))
        val builder = PatchOperation.builder()
            .logTo(printStream)
            .basePath(output.path)
            .patchesPath(patches.path)
            .outputPath(output.path)
            .level(if (verbose.get()) codechicken.diffpatch.util.LogLevel.ALL else codechicken.diffpatch.util.LogLevel.INFO)
            .mode(mode())
            .minFuzz(minFuzz())
            .summary(verbose.get())
            .lineEnding("\n")
            .ignorePrefix(".git")
        if (rejectsDir.isPresent && emitRejects.get()) {
            builder.rejectsPath(rejectsDir.path)
        }

        val result = builder.build().operate()

        commit()

        if (result.exit != 0) {
            val total = result.summary.failedMatches + result.summary.exactMatches +
                result.summary.accessMatches + result.summary.offsetMatches + result.summary.fuzzyMatches
            throw Exception("Failed to apply ${result.summary.failedMatches}/$total hunks")
        }

        return result.summary.changedFiles
    }

    private fun setupGitHook(outputPath: Path) {
        val hook = outputPath.resolve(".git/hooks/post-rewrite")
        hook.parent.createDirectories()
        hook.writeText(javaClass.getResource("/post-rewrite.sh")!!.readText())
        hook.toFile().setExecutable(true)
    }

    private fun commit() {
        val ident = PersonIdent(PersonIdent("File", "noreply+automated@papermc.io"), Instant.parse("1997-04-20T13:37:42.69Z"))
        val git = Git.open(output.path.toFile())
        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage("${identifier.get()} File Patches")
            .setAuthor(ident)
            .setAllowEmpty(true)
            .setSign(false)
            .call()
        git.tagDelete().setTags("file").call()
        git.tag().setName("file").setTagger(ident).setSigned(false).call()
        git.close()
    }

    internal open fun mode(): PatchMode {
        return PatchMode.OFFSET
    }

    internal open fun minFuzz(): Float {
        return FuzzyLineMatcher.DEFAULT_MIN_MATCH_SCORE
    }
}
