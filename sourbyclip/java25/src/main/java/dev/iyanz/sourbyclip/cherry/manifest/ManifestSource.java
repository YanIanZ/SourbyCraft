package dev.iyanz.sourbyclip.cherry.manifest;

/**
 * Cherry — which on-disk manifest a discovered mixin/access-transformer/access-widener declaration
 * came from. Purely provenance metadata: it does not change how a declaration is applied, only how
 * it is labeled in {@link dev.iyanz.sourbyclip.cherry.discovery.DiscoveryReport} and
 * {@link dev.iyanz.sourbyclip.cherry.CherryStatus} for logging and diagnostics.
 */
public enum ManifestSource {

    /** {@code cherry-plugin.json} — Cherry's preferred unified manifest. */
    CHERRY_PLUGIN_JSON("cherry-plugin.json"),

    /** {@code leaves-plugin.json} — the legacy Leaves-only manifest, kept for back-compat. */
    LEAVES_PLUGIN_JSON("leaves-plugin.json"),

    /** {@code fabric.mod.json} — the Fabric mod-metadata manifest (server-side subset only). */
    FABRIC_MOD_JSON("fabric.mod.json");

    private final String fileName;

    ManifestSource(String fileName) {
        this.fileName = fileName;
    }

    /** @return the manifest file name this source is read from, e.g. {@code "fabric.mod.json"}. */
    public String fileName() {
        return fileName;
    }
}
