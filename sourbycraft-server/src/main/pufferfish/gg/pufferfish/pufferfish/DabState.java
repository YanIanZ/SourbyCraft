package gg.pufferfish.pufferfish;

import net.minecraft.world.entity.EntityType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for the Pufferfish-added {@code EntityType.dabEnabled} field.
 *
 * The original Pufferfish "Dynamic Activation of Brains" patch added a
 * {@code dabEnabled} boolean directly on {@link EntityType}. That patch
 * was bulk-deleted during the Paper 26.1.2 migration (commit 02e11ab)
 * to land a clean baseline. The config writers in
 * {@link PufferfishConfig#dynamicActivationOfBrains()} still exist and
 * need a place to record the per-type enable flag without restoring the
 * full Pufferfish patch chain.
 *
 * Default is {@code true} (enabled) — matching the original reset-then-
 * disable-blacklist semantics. If/when the DAB ticking code is restored
 * via a follow-up patch, point its read site at {@link #isEnabled}.
 */
public final class DabState {

    private static final Map<EntityType<?>, Boolean> ENABLED = new ConcurrentHashMap<>();

    private DabState() {}

    public static void setEnabled(EntityType<?> type, boolean enabled) {
        ENABLED.put(type, enabled);
    }

    public static boolean isEnabled(EntityType<?> type) {
        return ENABLED.getOrDefault(type, true);
    }
}
