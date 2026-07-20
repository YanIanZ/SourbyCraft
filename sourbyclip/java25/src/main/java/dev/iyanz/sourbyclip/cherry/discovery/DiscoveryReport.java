package dev.iyanz.sourbyclip.cherry.discovery;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Cherry — the complete, read-only result of one {@link CherryDiscovery#scan(java.io.File)} pass:
 * every mixin config, access-transformer file, and access-widener file discovered across every
 * manifest format Cherry understands, already de-duplicated and (for mixin configs) sorted by
 * priority — plus everything that was found but skipped, with a reason.
 *
 * <p>Producing this report has no side effects: nothing is registered, extracted, written to disk,
 * or locked. This is what makes it safe to use both for a real boot (as the source data
 * {@link dev.iyanz.sourbyclip.cherry.CherryPluginResolver} and
 * {@link dev.iyanz.sourbyclip.cherry.fabric.CherryFabricBridge} commit from) and for
 * {@link dev.iyanz.sourbyclip.cherry.Cherry#dryRun()} / {@link dev.iyanz.sourbyclip.cherry.Cherry#status()},
 * which must be able to report "what would load" without mutating anything, even while Cherry itself
 * is disabled.
 *
 * @param mixinConfigs        every discovered mixin config, sorted by
 *                            {@link DiscoveredMixinConfig#priority()} ascending, then by plugin
 *                            name, then by config entry name, for deterministic ordering
 * @param accessTransformers  every discovered, existence-verified access-transformer file
 * @param accessWideners      every discovered access-widener file (both Leaves- and Fabric-declared)
 * @param skipped             everything found but not included above, with a reason
 * @param scannedJarCount     how many {@code .jar} files under the plugins directory were examined
 */
public record DiscoveryReport(
    List<DiscoveredMixinConfig> mixinConfigs,
    List<DiscoveredAccessTransformer> accessTransformers,
    List<DiscoveredAccessWidener> accessWideners,
    List<SkippedItem> skipped,
    int scannedJarCount
) {

    /** @return an empty report, as if a plugins directory with no jars had been scanned. */
    public static DiscoveryReport empty() {
        return new DiscoveryReport(List.of(), List.of(), List.of(), List.of(), 0);
    }

    /** @return the number of distinct plugins contributing at least one mixin config. */
    public int distinctMixinPluginCount() {
        return (int) mixinConfigs.stream().map(DiscoveredMixinConfig::pluginName).distinct().count();
    }

    /**
     * @return a single concise line summarizing this report, in the form
     * {@code "Cherry ready: N mixin plugin(s), M access-transformer(s), K widener(s)"} (plus a
     * trailing skipped-count clause when anything was skipped). Intended to be logged once, at
     * INFO, when discovery completes; per-item detail belongs at DEBUG.
     */
    public String summaryLine() {
        String base = "Cherry ready: %d mixin plugin(s), %d access-transformer(s), %d widener(s)".formatted(
            distinctMixinPluginCount(), accessTransformers.size(), accessWideners.size());
        if (skipped.isEmpty()) {
            return base;
        }
        return base + " (" + skipped.size() + " item(s) skipped, see WARN log above for reasons)";
    }

    /** @return {@link #skipped}, rendered one {@link SkippedItem#describe()} line per element. */
    public List<String> describeSkipped() {
        return skipped.stream().map(SkippedItem::describe).collect(Collectors.toUnmodifiableList());
    }
}
