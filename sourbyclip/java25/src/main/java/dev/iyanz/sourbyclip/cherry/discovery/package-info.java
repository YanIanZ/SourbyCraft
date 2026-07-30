/**
 * Cherry — the unified, multi-format plugin discovery pipeline: read-only scanning, parsing,
 * de-duplication, and priority ordering across every manifest shape Cherry understands
 * ({@code cherry-plugin.json}, {@code leaves-plugin.json}, {@code fabric.mod.json}, and the
 * standalone {@code *.mixins.json} files those manifests reference).
 *
 * <p>{@link dev.iyanz.sourbyclip.cherry.discovery.CherryDiscovery#scan(java.io.File)} is the single
 * entry point; it returns a {@link dev.iyanz.sourbyclip.cherry.discovery.DiscoveryReport} — a plain,
 * immutable snapshot of everything found (and everything skipped, with a reason). Nothing in this
 * package registers, extracts, or applies anything: {@link dev.iyanz.sourbyclip.cherry.CherryPluginResolver}
 * and {@link dev.iyanz.sourbyclip.cherry.fabric.CherryFabricBridge} are the two consumers that act on
 * a report.
 *
 * <p>{@link dev.iyanz.sourbyclip.cherry.discovery.CherryPluginFilter} is the per-plugin opt-out
 * (alongside {@link dev.iyanz.sourbyclip.cherry.Cherry#enabled()}'s global one), consulted by
 * {@code CherryDiscovery} itself so a disabled plugin never contributes anything to a report in the
 * first place.
 */
package dev.iyanz.sourbyclip.cherry.discovery;
