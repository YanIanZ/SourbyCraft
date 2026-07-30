package dev.iyanz.sourbyclip.cherry.manifest;

/**
 * Cherry — one normalized entry from a {@code fabric.mod.json}'s {@code mixins} array.
 *
 * <p>Per the Fabric mod-metadata schema, each element of {@code mixins} is either a bare string
 * (the mixin config file name, applying to every environment) or an object of the shape
 * {@code {"config": "...", "environment": "client"|"server"|"*"}}. {@link FabricModManifest}
 * normalizes both shapes into this one record so the rest of Cherry never has to branch on JSON
 * shape again.
 *
 * @param config      the SpongePowered Mixin config file name (resolved relative to the jar root),
 *                    e.g. {@code "myplugin.mixins.json"}
 * @param environment the declared environment: {@code "client"}, {@code "server"}, or {@code "*"}
 *                    (universal); defaults to {@code "*"} when a bare string entry is used, per the
 *                    Fabric schema. Never {@code null} — an unrecognized or missing value is
 *                    normalized to {@code "*"} by {@link FabricModManifest}.
 */
public record FabricMixinEntry(String config, String environment) {

    /**
     * @return true if this entry applies to a dedicated server, i.e. its environment is
     * {@code "server"} or the universal {@code "*"}. False for {@code "client"}-only entries and
     * for any unrecognized environment string, since Cherry only ever loads server-side
     * declarations and a value it cannot confidently classify as server-applicable is treated as
     * not applicable (fail closed, not open).
     */
    public boolean appliesToServer() {
        return "*".equals(environment) || "server".equalsIgnoreCase(environment);
    }
}
