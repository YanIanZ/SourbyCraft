package dev.iyanz.sourbycraft.api;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 54. A SourbyCraft feature asked for through {@link SourbyAPI} is not available on
 * this server: the server is not SourbyCraft, the feature is switched off, or its service did not
 * start. Unchecked; use the {@code find…} methods of {@link SourbyAPI} to test without catching.
 */
@NullMarked
public final class FeatureUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String feature;
    private final @Nullable String configKey;

    /**
     * @param feature what was asked for, e.g. {@code AWF MultiProfile (AuroraProfiles)}
     * @param configKey the setting that enables it, or {@code null} when no setting does
     * @param reason why it is unavailable, for the message
     */
    public FeatureUnavailableException(final String feature, final @Nullable String configKey, final String reason) {
        super(message(feature, configKey, reason));
        this.feature = Objects.requireNonNull(feature, "feature");
        this.configKey = configKey;
    }

    private static String message(final String feature, final @Nullable String configKey, final String reason) {
        final StringBuilder out = new StringBuilder(feature).append(" is not available: ").append(reason);
        if (configKey != null) out.append(" (enable it with ").append(configKey).append(')');
        return out.toString();
    }

    /** What was asked for. */
    public String feature() {
        return this.feature;
    }

    /** The setting that enables the feature, when one does. */
    public Optional<String> configKey() {
        return Optional.ofNullable(this.configKey);
    }
}
