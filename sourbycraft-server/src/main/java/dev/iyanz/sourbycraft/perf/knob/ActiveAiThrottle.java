package dev.iyanz.sourbycraft.perf.knob;

/**
 * In-tick actuator holder for the ACTIVE-mob distance AI throttle (the real implementation behind
 * {@code perf.ai.throttle-beyond-distance} / {@code perf.ai.throttle-tick-interval}).
 *
 * <p>The base already sends mobs OUTSIDE the entity activation range to the cheap
 * {@code Mob#inactiveTick}. This adds a second, load-gated tier for mobs that are still ACTIVE but
 * far from any player: their expensive AI decisions (sensing + goal/target selectors) run only once
 * every {@link #interval} ticks, while movement/controls keep running every tick so they never
 * visibly freeze mid-air. {@code KnobEnforcer} writes these fields from the perf-engine tier; they
 * are read on the mob's owning region thread in {@code Mob#serverAiStep}.
 *
 * <p>Disabled at GREEN/YELLOW ({@code beyond-distance} default 0), so there is zero behaviour change
 * until a load tier (ORANGE/RED/EMERGENCY) turns it on — exactly when trading distant-mob AI
 * responsiveness for TPS is the right call. Plain volatile statics: written on the global-region
 * enforcement thread, read on many region threads; a torn read only mis-throttles one mob for one
 * tick, which is harmless.
 */
public final class ActiveAiThrottle {
    /** True when the distance throttle is active (beyond-distance &gt; 0 AND interval &gt; 1). */
    public static volatile boolean enabled = false;
    /** Search radius (blocks): a mob with NO player within this radius is "beyond distance". */
    public static volatile double distance = 0.0;
    /** Run the expensive AI decisions once every this many ticks while throttled. */
    public static volatile int interval = 1;

    private ActiveAiThrottle() {}
}
