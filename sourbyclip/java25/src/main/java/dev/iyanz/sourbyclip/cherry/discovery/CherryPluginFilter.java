package dev.iyanz.sourbyclip.cherry.discovery;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cherry — the per-plugin opt-out, alongside the existing global kill-switch
 * ({@link dev.iyanz.sourbyclip.cherry.Cherry#enabled()}).
 *
 * <p>An operator who finds that one specific plugin's Cherry-loaded declarations (mixins it
 * discovered from a {@code fabric.mod.json}, or an access-transformer) are causing a problem can
 * disable just that plugin's Cherry contributions, without disabling Cherry entirely and without
 * repackaging the plugin, via:
 *
 * <pre>{@code -Dcherry.disable.plugins=SomePlugin,AnotherPlugin}</pre>
 *
 * <p>Matching is exact and case-sensitive against the plugin name Cherry resolved from its manifest
 * (falling back to the jar file name when a manifest declares none). This only ever affects what
 * <b>Cherry itself</b> loads (access-transformers, and Fabric-format mixin configs/wideners) — a
 * disabled plugin's {@code mixin} block handled by the Leaves engine is entirely outside Cherry's
 * control and is unaffected; see the repository README's Limitations section.
 */
public final class CherryPluginFilter {

    private static final String PROPERTY = "cherry.disable.plugins";

    private CherryPluginFilter() {
    }

    /**
     * @param pluginName the plugin name to check (as resolved by {@link CherryDiscovery}), must not
     *                    be {@code null}
     * @return true if this plugin is listed in {@code -Dcherry.disable.plugins} and every one of its
     * Cherry-owned declarations should be skipped
     */
    public static boolean isDisabled(String pluginName) {
        return disabledNames().contains(pluginName);
    }

    private static Set<String> disabledNames() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Stream.of(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }
}
