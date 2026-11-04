package dev.iyanz.sourbycraft.awf.redis.fabric;

import java.util.Locale;

/**
 * Durable internal event types carried by {@link EventFabric}.
 *
 * <p>Names are part of the wire format: a stream entry stores the exact {@link #wireName()}, and a
 * consumer must ignore (acknowledge) a type it does not know instead of treating it as corruption.
 * Adding a type is backward compatible; renaming one is not.</p>
 */
public enum FabricEventType {
    PROFILE_CREATED,
    PROFILE_ACTIVATING,
    PROFILE_ACTIVATED,
    PROFILE_SWITCH_REQUESTED,
    PROFILE_SWITCH_COMPLETED,
    PROFILE_SAVE_COMPLETED,
    PROFILE_RECOVERY_REQUIRED,
    WORLD_LOAD_REQUESTED,
    WORLD_READY,
    WORLD_UNLOADED,
    NODE_HEALTH_CHANGED,
    SESSION_CLAIMED,
    SESSION_LOST;

    public String wireName() {
        return name();
    }

    /** Parses a wire name, or {@code null} when a newer node wrote an unknown type. */
    public static FabricEventType parse(final String wireName) {
        if (wireName == null || wireName.isEmpty() || wireName.length() > 64) return null;
        try {
            return valueOf(wireName.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            return null;
        }
    }
}
