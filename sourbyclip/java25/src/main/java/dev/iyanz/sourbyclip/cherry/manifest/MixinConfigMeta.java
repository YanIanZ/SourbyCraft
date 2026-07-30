package dev.iyanz.sourbyclip.cherry.manifest;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Cherry — the Gson deserialization target for a standalone SpongePowered Mixin config file (a
 * {@code *.mixins.json}, e.g. {@code myplugin.mixins.json}), limited to the fields Cherry itself
 * needs to read ahead of actually registering the config with {@code Mixins.addConfiguration}.
 *
 * <p>Cherry never re-implements what these fields <i>mean</i> to Mixin — that is entirely
 * SpongePowered Mixin's own job once the config is registered. Cherry reads them only so it can:
 * <ul>
 *   <li>know the mixin <b>package</b> to extract class files for (mirroring how the Leaves engine
 *       extracts {@code mixin.package-name} out of a {@code cherry-plugin.json}/
 *       {@code leaves-plugin.json}'s {@code mixin} block);</li>
 *   <li>stage the <b>refmap</b> file (if declared) alongside the config so Mixin can find it on the
 *       classpath — see {@link dev.iyanz.sourbyclip.cherry.fabric.CherryFabricBridge};</li>
 *   <li>sort Cherry's own discovery report and registration order by <b>priority</b> deterministically
 *       (see {@link dev.iyanz.sourbyclip.cherry.discovery.DiscoveryReport}) — this does not replace
 *       Mixin's own priority-driven transform ordering, which applies regardless of what order
 *       configs are registered in; it only makes Cherry's own logging/status output and
 *       registration call order deterministic and priority-aware.</li>
 * </ul>
 */
public final class MixinConfigMeta {

    /** SpongePowered Mixin's own default config priority, used when a config omits the field. */
    public static final int DEFAULT_PRIORITY = 1000;

    @SerializedName("package")
    private String packageName;

    private String refmap;
    private Integer priority;
    private List<String> mixins;
    private List<String> client;
    private List<String> server;

    /** @return the mixin package (dot-separated), or {@code null} if the config omits it. */
    public String getPackageName() {
        return packageName;
    }

    /** @return the declared refmap file name, or {@code null} if this config has none. */
    public String getRefmap() {
        return refmap;
    }

    /** @return the declared {@code priority}, or {@link #DEFAULT_PRIORITY} if omitted. */
    public int priorityOrDefault() {
        return priority == null ? DEFAULT_PRIORITY : priority;
    }

    /**
     * @return the total number of mixin classes this config declares across its {@code mixins},
     * {@code server}, and {@code client} lists (each may be absent). Purely informational, for
     * diagnostics — Cherry does not otherwise care which classes a config lists, since Mixin applies
     * them once the config is registered.
     */
    public int declaredMixinCount() {
        return size(mixins) + size(server) + size(client);
    }

    private static int size(List<String> list) {
        return list == null ? 0 : list.size();
    }
}
