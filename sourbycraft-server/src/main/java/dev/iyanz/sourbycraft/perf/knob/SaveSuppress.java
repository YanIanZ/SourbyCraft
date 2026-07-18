package dev.iyanz.sourbycraft.perf.knob;

/**
 * In-tick actuator holder for the lag-machine save-suppression knobs
 * ({@code perf.lag-machine.disable-saving-snowballs} / {@code -fireworks}).
 *
 * <p>Snowballs and firework rockets are short-lived, replaceable projectiles whose persisted NBT is
 * a known lag-machine vector: a redstone contraption mass-spawning them makes every chunk save write
 * (and later re-load + re-tick) thousands of throwaway entities. When suppressed, {@code shouldBeSaved}
 * returns false for them, so they simply vanish on chunk unload/save instead of persisting — no
 * gameplay loss (they would have expired in seconds anyway).
 *
 * <p>Written by {@code KnobEnforcer}; read in {@code Snowball#shouldBeSaved} /
 * {@code FireworkRocketEntity#shouldBeSaved}. Plain volatile statics (config-write on the enforcement
 * thread, read on region threads during save). Both default to the SourbyCraft base default (on);
 * flip the config off to persist them.
 */
public final class SaveSuppress {
    public static volatile boolean snowballs = true;
    public static volatile boolean fireworks = true;

    private SaveSuppress() {}
}
