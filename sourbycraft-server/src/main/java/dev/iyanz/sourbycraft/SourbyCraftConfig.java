package dev.iyanz.sourbycraft;

/**
 * Minimal Folia-side config surface.
 *
 * <p>The full Paper SourbyCraftConfig (YAML registry, perf knobs, antixray,
 * chat, mod-loader wiring) has not been ported to the Folia base yet. Only the
 * fields/methods actually referenced by the ported console commands live here
 * so those commands compile and behave sensibly. Values are the safe defaults
 * — enrich this class when the wider config engine lands on Folia.
 */
public final class SourbyCraftConfig {

    /**
     * Slime-World-Manager is removed on the Folia line (see F0 re-platform),
     * so this is permanently {@code false} here. Referenced by {@code /sys}.
     */
    public static final boolean swmEnabled = false;

    /**
     * Reads a boolean flag from sourbycraft.yml. Until the YAML config engine is
     * ported to Folia there is no backing store, so the supplied default is
     * returned verbatim. Referenced by {@link dev.iyanz.sourbycraft.perf.spark.SparkBridge}.
     */
    public static boolean ymlBool(String key, boolean def) {
        return def;
    }

    private SourbyCraftConfig() {}
}
