package dev.iyanz.sourbycraft.bridge;

/**
 * Decides a plugin's {@link CompatibilityState} from observed evidence only.
 *
 * <p>The contract in {@code aurora-plugin-bridge.md}: a plugin cannot be presented as
 * {@link CompatibilityState#BRIDGED} until load, enable and bridge initialisation succeed and no
 * fatal compatibility violation is present. Anything weaker falls to a less favourable state, never
 * a stronger one.</p>
 *
 * <p>Today the region-threading base refuses to load a plugin that is not marked
 * {@code folia-supported} or {@code canvas-supported}, and no bridge adapter exists, so
 * {@code bridgeInitialized} is never true in a running server and BRIDGED is unreachable. The rule
 * is kept here so that the day an adapter lands, the state cannot run ahead of it.</p>
 */
public final class CompatibilityClassifier {

    /**
     * What is known about one plugin.
     *
     * @param declaresRegionSupport the descriptor marks Folia or Canvas support
     * @param enabled the plugin is currently enabled
     * @param failureRecorded a load or enable failure was captured for this plugin
     * @param bridgeInitialized the Aurora Bridge initialised an adapter for this plugin
     * @param fatalViolation the bridge recorded a fatal compatibility violation
     */
    public record Evidence(boolean declaresRegionSupport, boolean enabled, boolean failureRecorded,
                           boolean bridgeInitialized, boolean fatalViolation) {}

    private CompatibilityClassifier() {}

    public static CompatibilityState classify(final Evidence evidence) {
        if (evidence.failureRecorded() || evidence.fatalViolation()) {
            return CompatibilityState.FAILED;
        }
        if (!evidence.enabled()) {
            return CompatibilityState.DISABLED;
        }
        if (evidence.declaresRegionSupport()) {
            return CompatibilityState.NATIVE;
        }
        // Enabled without declaring support: only a bridge that actually came up may claim it.
        // Without one there is no evidence it runs correctly under region threading.
        return evidence.bridgeInitialized() ? CompatibilityState.BRIDGED : CompatibilityState.FAILED;
    }
}
