/**
 * Cherry — Gson deserialization models for every plugin-manifest format Cherry's discovery pipeline
 * understands, beyond the {@code cherry-plugin.json}/{@code leaves-plugin.json} pair already read
 * directly into the host's {@code LeavesPluginMeta} (see
 * {@link dev.iyanz.sourbyclip.cherry.CherryPluginResolver}).
 *
 * <ul>
 *   <li>{@link dev.iyanz.sourbyclip.cherry.manifest.FabricModManifest} — the Fabric-ecosystem
 *       {@code fabric.mod.json}, limited to its {@code mixins} array and {@code accessWidener}
 *       field.</li>
 *   <li>{@link dev.iyanz.sourbyclip.cherry.manifest.FabricMixinEntry} — one normalized
 *       {@code fabric.mod.json} mixins-array entry (config file + environment).</li>
 *   <li>{@link dev.iyanz.sourbyclip.cherry.manifest.MixinConfigMeta} — a standalone
 *       {@code *.mixins.json} SpongePowered Mixin config, limited to the {@code package},
 *       {@code refmap}, and {@code priority} fields Cherry needs to extract and order mixins
 *       correctly.</li>
 *   <li>{@link dev.iyanz.sourbyclip.cherry.manifest.ManifestSource} — provenance tag recording which
 *       manifest format a discovered declaration came from.</li>
 * </ul>
 *
 * <p>None of these classes apply mixins, transformers, or wideners themselves — they are pure,
 * side-effect-free parsing models consumed by {@link dev.iyanz.sourbyclip.cherry.discovery} (for
 * reporting/status) and {@link dev.iyanz.sourbyclip.cherry.fabric} (for actually staging Fabric
 * declarations onto the classpath).
 */
package dev.iyanz.sourbyclip.cherry.manifest;
