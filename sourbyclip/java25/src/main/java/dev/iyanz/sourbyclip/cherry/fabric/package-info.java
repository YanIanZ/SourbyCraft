/**
 * Cherry — the Fabric-format bridge: extracts server-side mixin configs, refmaps, and
 * access-wideners declared the Fabric way ({@code fabric.mod.json}) into a cached,
 * classpath-loadable jar, since the Leaves engine this repository builds on has no notion of that
 * manifest format at all.
 *
 * <p>{@link dev.iyanz.sourbyclip.cherry.fabric.CherryFabricBridge#resolve} is the entry point,
 * consuming a {@link dev.iyanz.sourbyclip.cherry.discovery.DiscoveryReport} and producing a
 * {@link dev.iyanz.sourbyclip.cherry.fabric.FabricResolution} — see that record's javadoc for the
 * exact contract the host launcher must implement to finish the wiring (timing relative to
 * {@code MixinURLClassLoader} construction matters and is outside this repository's control).
 *
 * <p><b>Honest scope.</b> This package loads Fabric-format <i>server-side</i> mixin/access-widener
 * declarations into the SpongePowered Mixin environment Leaves already bootstraps. It does not run
 * a Fabric mod loader: no entrypoints, no mod dependency resolution, no client-side anything. See the
 * repository README's "Fabric support" section.
 */
package dev.iyanz.sourbyclip.cherry.fabric;
