package dev.iyanz.sourbyclip.cherry.discovery;

import dev.iyanz.sourbyclip.cherry.manifest.ManifestSource;

import java.io.File;

/**
 * Cherry — one access-transformer ({@code .at}) file declaration discovered from a plugin manifest,
 * verified to actually exist as an entry in the jar (existence is checked at discovery time; content
 * is only read later, when {@link dev.iyanz.sourbyclip.cherry.CherryPluginResolver} commits it into
 * {@link dev.iyanz.sourbyclip.cherry.at.CherryAccessTransformers}).
 *
 * @param pluginName the declaring plugin's name (from the manifest, or the jar file name if absent)
 * @param jarFile    the plugin jar this AT file lives in, re-opened by the caller to read its content
 * @param entryName  the {@code .at} file's path within the jar, as declared in the manifest
 * @param source     which manifest declared this AT file
 */
public record DiscoveredAccessTransformer(String pluginName, File jarFile, String entryName, ManifestSource source) {
}
