package dev.iyanz.sourbyclip.cherry.discovery;

import dev.iyanz.sourbyclip.cherry.manifest.ManifestSource;

import java.io.File;

/**
 * Cherry — one SpongePowered Mixin config declaration discovered from a plugin manifest (either the
 * Leaves {@code mixin.mixins} list or a Fabric {@code fabric.mod.json}'s {@code mixins} array),
 * normalized to a common shape regardless of which manifest declared it.
 *
 * <p>For a {@link ManifestSource#CHERRY_PLUGIN_JSON}/{@link ManifestSource#LEAVES_PLUGIN_JSON}
 * entry, this is <b>reporting-only</b> — the Leaves engine (outside this repository) already
 * extracts and registers these configs itself; Cherry's discovery of them exists purely so
 * {@link dev.iyanz.sourbyclip.cherry.CherryStatus} can show a complete, accurate picture and so
 * priority/refmap problems are caught and logged early instead of surfacing as an obscure Mixin
 * bootstrap failure.
 *
 * <p>For a {@link ManifestSource#FABRIC_MOD_JSON} entry, this record is <b>actionable</b>:
 * {@link dev.iyanz.sourbyclip.cherry.fabric.CherryFabricBridge} extracts the referenced package and
 * config (plus refmap, if any) into a cached jar and hands the config name back to the host launcher
 * to register, since the Leaves engine has no notion of {@code fabric.mod.json} at all.
 *
 * @param pluginName  the declaring plugin's name (manifest {@code name}/{@code id}, or the jar file
 *                    name if absent)
 * @param jarFile     the plugin jar this config lives in
 * @param configEntry the {@code *.mixins.json} config file's path within the jar
 * @param packageName the mixin package this config's classes live under (dot-separated), used to
 *                    know which class files to extract for a Fabric-sourced entry; may be
 *                    {@code null} if it could not be determined (config unreadable, or a Leaves
 *                    entry reported without opening its config)
 * @param priority    this config's SpongePowered Mixin {@code priority}, or
 *                    {@link dev.iyanz.sourbyclip.cherry.manifest.MixinConfigMeta#DEFAULT_PRIORITY}
 *                    if the config omits it or could not be read
 * @param refmap      the config's declared {@code refmap} file name, or {@code null} if it has none
 * @param environment for a Fabric-sourced entry, the declared environment ({@code "server"} or
 *                    {@code "*"} — {@code "client"}-only entries are filtered out before this record
 *                    is ever created); {@code "server"} for a Leaves-sourced entry, which has no
 *                    concept of environments (a Leaves/Paper server never has a client side to skip)
 * @param source      which manifest declared this config
 */
public record DiscoveredMixinConfig(
    String pluginName,
    File jarFile,
    String configEntry,
    String packageName,
    int priority,
    String refmap,
    String environment,
    ManifestSource source
) {

    /** @return true if Cherry itself must extract and register this config (a Fabric declaration). */
    public boolean requiresCherryExtraction() {
        return source == ManifestSource.FABRIC_MOD_JSON;
    }
}
