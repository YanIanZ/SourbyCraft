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

package io.papermc.paperweight.core.extension

import io.papermc.paperweight.util.*
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.*

abstract class ForkConfig @Inject constructor(
    private val configName: String,
    providers: ProviderFactory,
    objects: ObjectFactory,
    project: Project,
) : Named {
    override fun getName(): String {
        return configName
    }

    val rootDirectory: DirectoryProperty = objects.directoryProperty().convention(project.rootProject.layout.projectDirectory).finalizedOnRead()
    val serverDirectory: DirectoryProperty = objects.dirFrom(rootDirectory, providers.provider { "$name-server" })
    val serverPatchesDir: DirectoryProperty = objects.dirFrom(serverDirectory, "minecraft-patches")
    val rejectsDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "rejected")
    val sourcePatchDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "sources")
    val resourcePatchDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "resources")
    val featurePatchDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "features")

    /**
     * Foundational git-format "base" Minecraft patches (`minecraft-patches/base`), applied by
     * [io.papermc.paperweight.core.tasks.SetupForkMinecraftSources] AFTER ATs + library imports and
     * BEFORE the diffpatch-format `sources/` file patches. See
     * [io.papermc.paperweight.core.tasks.SetupForkMinecraftSources.basePatchDir] for the full
     * three-stage-fork rationale (e.g. consuming CanvasMC, whose weaver splits Minecraft patches
     * into base/sources/features). No-op when the directory is absent, so forks with only the
     * classic sources+features layout are unaffected.
     */
    val basePatchDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "base")

    val buildDataDir: DirectoryProperty = objects.dirFrom(rootDirectory, "build-data")
    val devImports: RegularFileProperty = objects.fileFrom(buildDataDir, "dev-imports.txt")
    val additionalAts: RegularFileProperty = objects.fileFrom(buildDataDir, providers.provider { "$name.at" })
    val reobfMappingsPatch: RegularFileProperty = objects.fileFrom(buildDataDir, "reobf-mappings-patch.tiny")

    val forks: Property<ForkConfig> = objects.property()
    val forksPaper: Property<Boolean> = objects.property<Boolean>().convention(forks.map { false }.orElse(true))

    /**
     * Per-fork override for the project-wide `gitFilePatches` flag
     * ([io.papermc.paperweight.core.extension.PaperweightCoreExtension.gitFilePatches]).
     *
     * When set, THIS fork's Minecraft file-patch tasks (`apply<Name>MinecraftSourcePatches` /
     * `...ResourcePatches`) route through the chosen applier independently of the global flag:
     * `true` = real `git apply --3way` ([io.papermc.paperweight.core.tasks.patching.ApplyFilePatches.applyWithGit]),
     * `false` = the internal java-diff-utils applier. **Unset (the default) inherits the
     * project-wide value**, so existing forks are unaffected.
     *
     * Motivation: a downstream that consumes a Paper fork as a read-only upstream (e.g.
     * SourbyCraft-on-Canvas) can hit cases where the fork's own Minecraft patches apply 100%
     * cleanly under `git apply` but the stricter diff-utils applier rejects a fraction of hunks
     * (context drift from re-deriving the tree against a different mache/AT pipeline). Flipping the
     * project-wide flag would also force `git apply` on OTHER layers (e.g. the Paper resource
     * patches) that regress under it — so the override is scoped to exactly the one fork that needs
     * it, leaving every other layer on the default applier.
     */
    val gitFilePatches: Property<Boolean> = objects.property()

    private val upstreamProvider: Provider<UpstreamConfig> = forks.map<UpstreamConfig> {
        objects.newInstance(it.name, false)
    }.orElse(
        providers.provider { objects.newInstance("paper", false) }
    )

    val upstream: UpstreamConfig by lazy {
        upstreamProvider.get()
    }

    fun upstream(op: Action<UpstreamConfig>) {
        op.execute(upstream)
    }
}
