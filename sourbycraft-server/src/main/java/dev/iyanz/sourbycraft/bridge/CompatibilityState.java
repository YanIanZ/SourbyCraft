package dev.iyanz.sourbycraft.bridge;

/**
 * A plugin's compatibility state, as {@code docs/architecture/aurora-plugin-bridge.md} defines it.
 *
 * <p>The colour belongs to the state, not to a surface, so {@code /plugins}, {@code /sys} and any
 * later panel agree on what blue or green means. Green means "running through Aurora Bridge"; it
 * never means official Folia support, and it is never shown without the evidence
 * {@link CompatibilityClassifier} requires.</p>
 */
public enum CompatibilityState {
    /** Declares Folia or SourbyCraft (Canvas) region-threading support and is enabled. */
    NATIVE("Native", "#4DA3FF"),
    /** A legacy plugin that loaded, enabled and initialised through the bridge with no fatal violation. */
    BRIDGED("Bridged", "#57D68D"),
    /** Load, enable or fatal bridge failure. */
    FAILED("Failed", "#FF5C70"),
    /** Present but not running, with no recorded failure. */
    DISABLED("Disabled", "#8B949E");

    private final String display;
    private final String hex;

    CompatibilityState(final String display, final String hex) {
        this.display = display;
        this.hex = hex;
    }

    public String display() {
        return this.display;
    }

    /** The {@code #RRGGBB} colour this state renders with on every surface. */
    public String hex() {
        return this.hex;
    }
}
