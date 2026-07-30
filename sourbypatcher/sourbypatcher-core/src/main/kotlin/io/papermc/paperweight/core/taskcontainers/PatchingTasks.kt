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

package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.core.tasks.patching.ApplyFeaturePatches
import io.papermc.paperweight.core.tasks.patching.ApplyFilePatches
import io.papermc.paperweight.core.tasks.patching.ApplyFilePatchesFuzzy
import io.papermc.paperweight.core.tasks.patching.FixupFilePatches
import io.papermc.paperweight.core.tasks.patching.RebuildFilePatches
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

class PatchingTasks(
    private val project: Project,
    private val forkName: String,
    private val patchSetName: String,
    private val taskGroup: String,
    private val readOnly: Boolean,
    private val filePatchDir: DirectoryProperty,
    private val rejectsDir: DirectoryProperty,
    private val featurePatchDir: DirectoryProperty,
    private val basePatchDir: DirectoryProperty,
    private val additionalAts: RegularFileProperty,
    private val baseDir: Provider<Directory>,
    private val gitFilePatches: Provider<Boolean>,
    private val filterPatches: Provider<Boolean>,
    private val outputDir: Path,
    private val tasks: TaskContainer = project.tasks,
) {
    private val namePart: String = if (readOnly) "${forkName.capitalized()}${patchSetName.capitalized()}" else patchSetName.capitalized()

    private fun ApplyFilePatches.configureApplyFilePatches() {
        group = taskGroup
        description = "Applies $patchSetName file patches"

        input.set(baseDir)
        if (readOnly) {
            output.set(layout.cache.resolve(paperTaskOutput()))
        } else {
            output.set(outputDir)
        }
        patches.set(filePatchDir.fileExists())
        rejectsDir.set(this@PatchingTasks.rejectsDir)
        gitFilePatches.set(this@PatchingTasks.gitFilePatches)
        // Apply the fork's git-format base/ patches (if any) before the files/ patches — supports
        // three-stage forks (e.g. CanvasMC paper-patches: base/ Region-Threading -> files/). No-op
        // when the fork has no base/ dir. See ApplyFilePatches.basePatchDir / ForkConfig discussion.
        basePatchDir.set(this@PatchingTasks.basePatchDir.fileExists())
        // Per-layer access transformer (e.g. CanvasMC weaver's build-data/<name>.at), applied to the
        // fresh checkout BEFORE base/file patches so the fork's exact-context patches — generated
        // against the AT'd tree — line up. No-op when the fork has no <name>.at. See ApplyFilePatches.atFile.
        // Only the server layer carries the mache/JST configurations; the API layer has neither (and no
        // ATs), so wire the JST runner only when its configurations exist — else this task fails to
        // configure on the API patchset (which never needs an AT).
        if (project.configurations.findByName(MACHE_MINECRAFT_LIBRARIES_CONFIG) != null
            && project.configurations.findByName(JST_CONFIG) != null) {
            atFile.set(additionalAts.fileExists())
            ats.jstClasspath.from(project.configurations.named(MACHE_MINECRAFT_LIBRARIES_CONFIG))
            ats.jst.from(project.configurations.named(JST_CONFIG))
        }
        baseRef.set("base")
        identifier = "$forkName $patchSetName"
    }

    val applyFilePatches = tasks.register<ApplyFilePatches>("apply${namePart}FilePatches") {
        configureApplyFilePatches()
    }

    val applyFilePatchesFuzzy = tasks.register<ApplyFilePatchesFuzzy>("apply${namePart}FilePatchesFuzzy") {
        configureApplyFilePatches()
    }

    val applyFeaturePatches = tasks.register<ApplyFeaturePatches>("apply${namePart}FeaturePatches") {
        group = taskGroup
        description = "Applies $patchSetName feature patches"
        dependsOn(applyFilePatches)

        repo.set(outputDir)
        if (readOnly) {
            base.set(applyFilePatches.flatMap { it.output })
        }
        patches.set(featurePatchDir.fileExists())
    }

    val applyPatches = tasks.register<Task>("apply${namePart}Patches") {
        group = taskGroup
        description = "Applies all $patchSetName patches"
        dependsOn(applyFilePatches, applyFeaturePatches)
    }

    val rebuildFilePatchesName = "rebuild${namePart}FilePatches"
    val fixupFilePatchesName = "fixup${namePart}FilePatches"
    val rebuildFeaturePatchesName = "rebuild${namePart}FeaturePatches"
    val rebuildPatchesName = "rebuild${namePart}Patches"

    init {
        if (!readOnly) {
            setupWritable()
        }
    }

    private fun setupWritable() {
        listOf(
            applyFilePatches,
            applyFilePatchesFuzzy,
            applyFeaturePatches,
        ).forEach {
            it.configure {
                doNotTrackState("Always run when requested")
            }
        }

        val rebuildFilePatches = tasks.register<RebuildFilePatches>(rebuildFilePatchesName) {
            group = taskGroup
            description = "Rebuilds $patchSetName file patches"

            base.set(baseDir)
            input.set(outputDir)
            patches.set(filePatchDir)
            gitFilePatches.set(this@PatchingTasks.gitFilePatches)
        }

        val fixupFilePatches = tasks.register<FixupFilePatches>(fixupFilePatchesName) {
            group = taskGroup
            description = "Puts the currently tracked source changes into the $patchSetName file patches commit"

            repo.set(outputDir)
            upstream.set("base")
        }

        val rebuildFeaturePatches = tasks.register<RebuildGitPatches>(rebuildFeaturePatchesName) {
            group = taskGroup
            description = "Rebuilds $patchSetName feature patches"
            dependsOn(rebuildFilePatches)

            inputDir.set(outputDir)
            patchDir.set(featurePatchDir)
            baseRef.set("file")
            filterPatches.set(this@PatchingTasks.filterPatches)
        }

        val rebuildPatches = tasks.register<Task>(rebuildPatchesName) {
            group = taskGroup
            description = "Rebuilds all $patchSetName patches"
            dependsOn(rebuildFilePatches, rebuildFeaturePatches)
        }

        val applyOrMoveFilePatches = tasks.register<ApplyFilePatches>("applyOrMove${namePart}FilePatches") {
            configureApplyFilePatches()
            description = "Applies $patchSetName file patches as Git patches, moving any failed patches to the rejects dir. " +
                "Useful when updating to a new Minecraft version."
            gitFilePatches = true
            moveFailedGitPatchesToRejects = true
        }
    }
}
