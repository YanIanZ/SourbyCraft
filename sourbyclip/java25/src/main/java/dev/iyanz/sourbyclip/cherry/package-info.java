/**
 * Cherry — SourbyCraft's unified server-side mixin system.
 *
 * <p>This package holds the integration points a host launcher (SourbyClip's Leaves-based
 * transforming classloader) calls into:
 * <ul>
 *   <li>{@link dev.iyanz.sourbyclip.cherry.Cherry} — the facade: the {@code -Dcherry.enable.mixin}
 *       gate, access-transformer and Fabric-mixin-bridge initialization, the per-class transform
 *       hook, and the {@link dev.iyanz.sourbyclip.cherry.Cherry#status()}/
 *       {@link dev.iyanz.sourbyclip.cherry.Cherry#dryRun()} diagnostics API.</li>
 *   <li>{@link dev.iyanz.sourbyclip.cherry.CherryPluginResolver} — commits the access-transformer
 *       files a {@link dev.iyanz.sourbyclip.cherry.discovery.DiscoveryReport} found into
 *       {@link dev.iyanz.sourbyclip.cherry.at.CherryAccessTransformers}.</li>
 *   <li>{@link dev.iyanz.sourbyclip.cherry.CherryStatus} — the record returned by
 *       {@code Cherry.status()}.</li>
 * </ul>
 *
 * <p>The manifest scanning itself — {@code cherry-plugin.json}, {@code leaves-plugin.json},
 * {@code fabric.mod.json}, and the standalone {@code *.mixins.json} files they reference — lives in
 * the child package {@link dev.iyanz.sourbyclip.cherry.discovery}; the Gson models for the Fabric
 * formats live in {@link dev.iyanz.sourbyclip.cherry.manifest}; extracting Fabric-declared mixin
 * configs/wideners onto the classpath lives in {@link dev.iyanz.sourbyclip.cherry.fabric}; the
 * access-transformer engine itself — ported from CraftCanvasMC/Horizon's
 * {@code TransformerContainer} — lives in {@link dev.iyanz.sourbyclip.cherry.at}.
 *
 * <p>See the repository README for the full merge story (LeavesMC/Leavesclip base +
 * CraftCanvasMC/Horizon access-transformer capability + Fabric-format discovery), the
 * plugin-manifest schemas, and the honest limitations of this integration.
 */
package dev.iyanz.sourbyclip.cherry;
