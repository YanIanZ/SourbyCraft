package dev.iyanz.sourbyclip.cherry.fabric;

import dev.iyanz.sourbyclip.cherry.discovery.SkippedItem;

import java.net.URL;
import java.util.List;

/**
 * Cherry — the result of {@link CherryFabricBridge#resolve}: everything the host launcher needs in
 * order to finish wiring Fabric-declared mixin configs and access-wideners into the same pipeline
 * the Leaves engine uses for its own {@code leaves-plugin.json} declarations.
 *
 * <p>This mirrors, field-for-field, the three pieces of state LeavesMC/Leavesclip's own
 * {@code MixinJarResolver} exposes ({@code jarUrls}, {@code mixinConfigs}, {@code accessWidenerConfigs})
 * — intentionally, so a host integration that already consumes {@code MixinJarResolver}'s fields can
 * merge these in with minimal glue. See the repository README's "Fabric support" section for the
 * exact integration contract (timing relative to {@code MixinURLClassLoader} construction matters).
 *
 * @param mixinConfigNames     Fabric-declared mixin config resource names, in the same
 *                             priority-then-name order as {@code DiscoveryReport.mixinConfigs()},
 *                             filtered to only the ones that were successfully extracted. Feed each
 *                             one to {@code Mixins.addConfiguration(String)}, in this order, after
 *                             the corresponding jar URLs (below) are on the classloader's classpath.
 * @param jarUrls              one cached, classpath-loadable jar per plugin that contributed at
 *                             least one successfully-extracted Fabric mixin config or widener; add
 *                             these to the URL array passed to {@code MixinURLClassLoader}'s
 *                             constructor <b>before</b> constructing it (mirroring how
 *                             {@code MixinJarResolver.jarUrls} is merged in {@code Leavesclip.main}),
 *                             since a config/widener resource must already be resolvable on the
 *                             classpath at that point
 * @param accessWidenerConfigs Fabric-declared ({@code fabric.mod.json}'s {@code accessWidener} field)
 *                             widener resource names that were successfully extracted; merge these
 *                             into whatever list feeds {@code AccessWidenerManager.initAccessWidener}
 * @param skipped              everything Cherry's Fabric bridge found but could not extract, with a
 *                             reason (jar I/O failure, etc. — parse-time skips already appear in the
 *                             {@link dev.iyanz.sourbyclip.cherry.discovery.DiscoveryReport} this was
 *                             built from)
 */
public record FabricResolution(
    List<String> mixinConfigNames,
    List<URL> jarUrls,
    List<String> accessWidenerConfigs,
    List<SkippedItem> skipped
) {

    /** @return a resolution with nothing to load, as produced when no plugin declares any Fabric-format mixin/widener. */
    public static FabricResolution empty() {
        return new FabricResolution(List.of(), List.of(), List.of(), List.of());
    }
}
