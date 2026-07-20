/**
 * What remains of the {@code perf} package after the self-tuning perf-engine (KnobEnforcer,
 * SelfTuneController, SimulationThrottle, ViewThrottle, PerfSensor's per-region sampling, ...) was
 * DEFERRED on the Canvas re-platform benchmark build (feat/canvas-engine, PR #12).
 *
 * <p>{@link dev.iyanz.sourbycraft.perf.Tier} is a display-only, three-level load tier used purely
 * to colour {@code /tps}'s inline readout — there is no sensor, no sampling loop, and nothing
 * scheduled behind it.
 */
package dev.iyanz.sourbycraft.perf;
