package dev.iyanz.sourbycraft.perf;

/**
 * Coarse display-only load tier for the benchmark-build utility commands.
 *
 * <p>The full self-tuning perf-engine (KnobEnforcer, SelfTuneController, SimulationThrottle,
 * ViewThrottle, PerfSensor's per-region sampling, ...) is DEFERRED on this Canvas re-platform
 * benchmark build (feat/canvas-engine, PR #12) — there is no sensor class behind this enum, no
 * sampling loop, nothing scheduled. {@code /tps}, {@code /mspt} and {@code /tpsbar} read
 * {@link org.bukkit.Bukkit#getTPS()} / {@link org.bukkit.Bukkit#getAverageTickTime()} directly and
 * derive a {@link Tier} inline (see each call site) purely to pick a colour. Three tiers only (no
 * ORANGE/EMERGENCY) — it is purely a display hint.
 */
public enum Tier {
    GREEN, YELLOW, RED
}
