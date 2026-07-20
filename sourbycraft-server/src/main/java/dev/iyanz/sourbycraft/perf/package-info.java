/**
 * What remains of the {@code perf} package after the self-tuning perf-engine (KnobEnforcer,
 * SelfTuneController, SimulationThrottle, ViewThrottle, PerfSensor's per-region sampling, ...) was
 * DEFERRED on the Canvas re-platform benchmark build (feat/canvas-engine, PR #12).
 *
 * <p>{@link dev.iyanz.sourbycraft.perf.Tier} is a display-only, three-level load tier used purely
 * to colour {@code /tps}'s inline readout — there is no CPU-tier sensor, no sampling loop, and
 * nothing scheduled behind it.
 *
 * <p>{@link dev.iyanz.sourbycraft.perf.SmartSwap} (r40) is the one exception: a standalone,
 * memory-only sensor + adaptive heap-reclaim ladder, re-introduced independent of the deferred
 * perf-engine (own sensor loop, own soft-cache trim registry, zero anti-xray/PerfSensor deps). It
 * is a memory manager, not the tier/knob engine — see its own javadoc for the honest-physics
 * &lt;7&micro;s decision-path claim and the trim/GC ladder it drives.
 */
package dev.iyanz.sourbycraft.perf;
