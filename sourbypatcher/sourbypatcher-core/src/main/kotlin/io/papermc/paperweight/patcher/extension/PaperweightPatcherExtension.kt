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

package io.papermc.paperweight.patcher.extension

import io.papermc.paperweight.core.extension.UpstreamConfig
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.*

@Suppress("unused", "MemberVisibilityCanBePrivate")
abstract class PaperweightPatcherExtension @Inject constructor(private val objects: ObjectFactory) {

    val gitFilePatches: Property<Boolean> = objects.property<Boolean>().convention(false)
    val filterPatches: Property<Boolean> = objects.property<Boolean>().convention(true)

    val upstreams: NamedDomainObjectContainer<UpstreamConfig> = objects.domainObjectContainer(UpstreamConfig::class) {
        objects.newInstance(it, true)
    }

    /**
     * One externalizable runtime library: [paperclipPath] is the path relative to
     * `META-INF/libraries/` inside the fat paperclip jar; [baseUrl] is the repo
     * root to fetch it from on first boot (the full download URL is baseUrl + path).
     */
    data class LibSpec(val paperclipPath: String, val baseUrl: String)

    /**
     * Libraries the SourbyLoader slim task ([io.papermc.paperweight.tasks.SlimPaperclipJar])
     * strips from the fat paperclip jar and downloads on first boot. Populate via
     * [externalLib] in the consuming build's `paperweight { }` block.
     */
    val externalLibs: ListProperty<LibSpec> = objects.listProperty(LibSpec::class.java)

    /** Fully-qualified Main-Class the slim jar delegates first-boot through. */
    val slimJarMainClass: Property<String> =
        objects.property<String>().convention("dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap")

    /** Slash-form package prefix whose `.class` files are copied to the slim jar root. */
    val slimBootstrapClassPrefix: Property<String> =
        objects.property<String>().convention("dev/iyanz/sourbycraft/bootstrap/")

    /** Register an externalized library by its paperclip-relative path and repo base URL. */
    fun externalLib(paperclipPath: String, baseUrl: String) {
        externalLibs.add(LibSpec(paperclipPath, baseUrl))
    }

    fun NamedDomainObjectContainer<UpstreamConfig>.paper(
        op: Action<UpstreamConfig>
    ): NamedDomainObjectProvider<UpstreamConfig> = register("paper") {
        repo.convention(github("PaperMC", "Paper"))
        applyUpstreamNested.convention(false)
        op.execute(this)
    }
}
