package dev.iyanz.sourbyclip.cherry;

import dev.iyanz.sourbyclip.cherry.at.CherryAccessTransformers;
import dev.iyanz.sourbyclip.cherry.discovery.DiscoveryReport;
import dev.iyanz.sourbyclip.cherry.fabric.FabricResolution;

/**
 * Cherry — a full diagnostic snapshot, returned by {@link Cherry#status()}: what a fresh scan of
 * {@code plugins/} would load ({@link #discovery()}) layered with what the two engines actually have
 * active right now. Intended as the data source for a future {@code /cherry} operator command (not
 * added by this repository — see the class javadoc on {@link Cherry}).
 *
 * <p>{@link #discovery()} is always freshly computed and reflects "what would load", independent of
 * whether Cherry is {@linkplain Cherry#enabled() enabled} or has ever been initialized — this is the
 * same data {@link Cherry#dryRun()} returns. The remaining fields reflect the <i>actually committed</i>
 * state of each engine, which stays at its initial empty/unlocked value until an {@code init} method
 * has run — so a disabled or not-yet-initialized Cherry will show discoveries with nothing active,
 * which is itself a useful, honest diagnostic (it shows what enabling Cherry would add).
 *
 * @param enabled                        the current value of {@link Cherry#enabled()}
 * @param discovery                      a fresh discovery report (see above)
 * @param accessTransformersLocked       true once the AT registry has been locked by an init method
 * @param accessTransformerDefinitionCount the number of AT definitions currently registered (0 if never initialized)
 * @param accessTransformerTargetClassCount the number of distinct target classes those definitions apply to
 * @param fabricResolution               the most recent {@link Cherry#initFabricMixins()}/{@link Cherry#init()}
 *                                        result; {@link FabricResolution#empty()} if neither has run
 */
public record CherryStatus(
    boolean enabled,
    DiscoveryReport discovery,
    boolean accessTransformersLocked,
    int accessTransformerDefinitionCount,
    int accessTransformerTargetClassCount,
    FabricResolution fabricResolution
) {

    /**
     * @return a multi-line, log/console-ready rendering: the discovery summary line, the active
     * {@link CherryAccessTransformers} state, the active Fabric bridge state, and one line per
     * skipped item.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Cherry: enabled=").append(enabled).append('\n');
        sb.append(discovery.summaryLine()).append('\n');
        sb.append("  access-transformers: ")
            .append(accessTransformerDefinitionCount).append(" definition(s) across ")
            .append(accessTransformerTargetClassCount).append(" target class(es), locked=")
            .append(accessTransformersLocked).append('\n');
        sb.append("  fabric bridge: ")
            .append(fabricResolution.mixinConfigNames().size()).append(" mixin config(s), ")
            .append(fabricResolution.accessWidenerConfigs().size()).append(" widener(s) across ")
            .append(fabricResolution.jarUrls().size()).append(" jar(s)").append('\n');
        for (String line : discovery.describeSkipped()) {
            sb.append("  skipped: ").append(line).append('\n');
        }
        for (var skip : fabricResolution.skipped()) {
            sb.append("  skipped: ").append(skip.describe()).append('\n');
        }
        return sb.toString();
    }
}
