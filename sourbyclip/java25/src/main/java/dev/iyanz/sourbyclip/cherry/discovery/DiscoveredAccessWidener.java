package dev.iyanz.sourbyclip.cherry.discovery;

import dev.iyanz.sourbyclip.cherry.manifest.ManifestSource;

import java.io.File;

/**
 * Cherry — one Fabric access-widener file declaration discovered from a plugin manifest.
 *
 * <p>A {@link ManifestSource#CHERRY_PLUGIN_JSON}/{@link ManifestSource#LEAVES_PLUGIN_JSON} entry
 * (the {@code mixin.access-widener} field) is reporting-only: the Leaves engine's own
 * {@code AccessWidenerManager} already extracts and applies it. A
 * {@link ManifestSource#FABRIC_MOD_JSON} entry (the top-level {@code accessWidener} field) is
 * actionable: {@link dev.iyanz.sourbyclip.cherry.fabric.CherryFabricBridge} extracts it into a
 * cached jar and exposes its resource name so the host launcher can merge it into the same Leaves
 * {@code AccessWidenerManager} pipeline — see the repository README's "Fabric support" section for
 * the exact integration contract.
 *
 * @param pluginName the declaring plugin's name
 * @param jarFile    the plugin jar this widener file lives in
 * @param entryName  the access-widener file's path within the jar
 * @param source     which manifest declared this widener
 */
public record DiscoveredAccessWidener(String pluginName, File jarFile, String entryName, ManifestSource source) {

    /** @return true if Cherry itself must extract and expose this widener (a Fabric declaration). */
    public boolean requiresCherryExtraction() {
        return source == ManifestSource.FABRIC_MOD_JSON;
    }
}
