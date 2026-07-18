package dev.iyanz.sourbycraft.perf.knob;

import java.util.Map;

/**
 * Static declaration site for all SourbyCraft performance knobs. Each public-static-final
 * field declares one knob; class init triggers KnobRegistry registration. Hot-path readers
 * call {@code Knobs.<KNOB>.get()}; controllers and commands call {@code Knobs.<KNOB>.set(...)}.
 */
public final class Knobs {

    private Knobs() {}

    /** Skip-rate for entity ticking. 1 = tick every server tick (vanilla); 20 = once per second. */
    public static final IntKnob ENTITY_TICK_RATE =
        new IntKnob("perf.entity-tick-rate", 20, 1, 20);

    // === P2 Lag-Machine Protection knobs ===

    /** Disables NBT saving for Snowball entities. Saved snowballs are a known lag-machine vector
     *  (despawn-on-load thousands per chunk → slow chunk load). Default true per UniverseSpigot rec. */
    public static final BoolKnob LAG_MACHINE_DISABLE_SAVING_SNOWBALLS =
        new BoolKnob("perf.lag-machine.disable-saving-snowballs", true);

    /** Disables NBT saving for FireworkRocket entities. Same vector as snowballs. */
    public static final BoolKnob LAG_MACHINE_DISABLE_SAVING_FIREWORKS =
        new BoolKnob("perf.lag-machine.disable-saving-fireworks", true);

    /** Max total projectile-triggered chunk loads per server tick. 0 = unlimited. */
    public static final IntKnob LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK =
        new IntKnob("perf.lag-machine.max-projectile-loads-per-tick", 10, 0, 1000);

    /** Max chunk loads a single projectile can trigger before being discarded. 0 = unlimited. */
    public static final IntKnob LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE =
        new IntKnob("perf.lag-machine.max-projectile-loads-per-projectile", 10, 0, 100);

    /** Enables removing excess minecarts on collision (vehicle cap). */
    public static final BoolKnob LAG_MACHINE_REMOVE_EXCESS_MINECARTS =
        new BoolKnob("perf.lag-machine.remove-excess-minecarts", false);

    /** Threshold for "excess" minecarts at a collision point. */
    public static final IntKnob LAG_MACHINE_EXCESS_MINECARTS_LIMIT =
        new IntKnob("perf.lag-machine.excess-minecarts-limit", 10, 1, 1000);

    /** Enables removing excess boats on collision (vehicle cap). */
    public static final BoolKnob LAG_MACHINE_REMOVE_EXCESS_BOATS =
        new BoolKnob("perf.lag-machine.remove-excess-boats", false);

    /** Threshold for "excess" boats at a collision point. */
    public static final IntKnob LAG_MACHINE_EXCESS_BOATS_LIMIT =
        new IntKnob("perf.lag-machine.excess-boats-limit", 10, 1, 1000);

    // === P3 Adaptive Entity AI knobs ===

    /** Distance (blocks) beyond the nearest player at which mob aiStep gets throttled.
     *  0 = disabled (vanilla every-tick AI for all mobs). */
    public static final IntKnob AI_THROTTLE_BEYOND_DISTANCE =
        new IntKnob("perf.ai.throttle-beyond-distance", 0, 0, 256);

    /** When throttled (nearest player beyond AI_THROTTLE_BEYOND_DISTANCE), run aiStep only
     *  every N server ticks. Higher = cheaper but more visibly frozen. */
    public static final IntKnob AI_THROTTLE_TICK_INTERVAL =
        new IntKnob("perf.ai.throttle-tick-interval", 4, 1, 40);

    // === F-perfup: load-gated behavior-affecting-but-load-appropriate knobs ===
    // These tighten ONLY under load. GREEN/YELLOW hold the base default (no regression); the perf
    // engine engages the tighter value at ORANGE and below. Enforced onto the real base config
    // fields by KnobEnforcer; set by tier by SelfTuneController.

    /** Master gate for the Kaiiju per-region per-entity-type tick/removal limiter
     *  ({@code sourby_entity_limits.yml}). Bridged to {@code KaiijuEntityLimits.enabled}.
     *  Default false = base behaviour (limiter off, vanilla entity ticking). The perf engine
     *  flips it ON under load so per-type entity caps engage only when the server is struggling. */
    public static final BoolKnob KAIIJU_ENTITY_LIMITER_ENABLED =
        new BoolKnob("perf.entity-limiter.enabled", false);

    /** Gate for the Pufferfish inactive-goal-selector throttle. Bridged to
     *  {@code EntityGoalSelectorInactiveTickConfig.enabled}. Default true = the SourbyCraft base
     *  default (throttle inactive AI goal-selectors to 1-in-20 ticks — behaviour-neutral, see F3-5).
     *  The perf engine keeps it forced ON under load so an operator who disabled it still gets the
     *  cheap throttle back while the server is struggling. */
    public static final BoolKnob GOAL_SELECTOR_INACTIVE_TICK_ENABLED =
        new BoolKnob("perf.ai.goal-selector-inactive-throttle", true);

    /** Global scale applied to every Kaiiju per-type entity tick cap. Bridged to
     *  {@code KaiijuEntityLimits.limitScale}. Default 1.0 = base behaviour (caps used as-is, no
     *  down-scaling; GREEN, no regression). The perf engine scales it DOWN under load (e.g. 0.75 RED,
     *  0.5 EMERGENCY) so fewer entities tick per tick while the server is struggling. Only takes
     *  effect while the entity limiter itself is enabled ({@link #KAIIJU_ENTITY_LIMITER_ENABLED}). */
    public static final DoubleKnob KAIIJU_ENTITY_LIMITER_SCALE =
        new DoubleKnob("perf.entity-limiter.scale", 1.0D,
            KnobMeta.active("Global multiplier on every per-type entity tick cap.",
                "1.0 = base behaviour (no down-scaling); the perf-engine scales it down under load."));

    /** Cadence (in inactive ticks) of the Pufferfish inactive goal-selector throttle. Bridged to
     *  {@code EntityGoalSelectorInactiveTickConfig.inactiveTickInterval}. Default 20 = the exact base
     *  behaviour (tick the goal selector once every 20 inactive ticks; GREEN, no regression). The perf
     *  engine WIDENS it under load (e.g. 30 ORANGE, 40 RED, 60 EMERGENCY) so the selector ticks even
     *  less often. Only takes effect while the throttle is enabled ({@link #GOAL_SELECTOR_INACTIVE_TICK_ENABLED}). */
    public static final IntKnob GOAL_SELECTOR_INACTIVE_TICK_INTERVAL =
        new IntKnob("perf.ai.goal-selector-interval", 20, 1, 200);

    // === F-perfup2: chunk-throughput knobs (raise the player ceiling at high view-distance) ===
    // At view-distance 10 with many players the tick wall is the chunk pipeline (each player streams
    // 21x21=441 chunks). These three are bridged by KnobEnforcer onto Paper's
    // GlobalConfiguration.chunkLoadingBasic fields, which Moonrise's RegionizedPlayerChunkLoader reads
    // LIVE every update per player (feeding per-player StaggeredRateLimiters). Lowering the per-player
    // rate spreads chunk send/load/gen over more ticks -> the aggregate per-tick cost across N players
    // stays bounded -> MSPT flattens -> more players fit before EMERGENCY. GREEN/YELLOW hold the base
    // defaults (no regression); the perf engine tightens at ORANGE and below. Value <= 0 = unlimited
    // (the base contract), so the generate-rate default -1 means "uncapped until a load tier caps it".

    /** Per-player chunk SEND rate (chunks/sec). Base default 75. Lowered under load. */
    public static final DoubleKnob CHUNK_SEND_RATE =
        new DoubleKnob("perf.chunk.send-rate", 75.0D,
            KnobMeta.active("Per-player chunk-send rate (chunks/sec); Paper playerMaxChunkSendRate.",
                "Base 75; the perf-engine lowers it under load to spread chunk packets over more ticks."));

    /** Per-player chunk LOAD rate (chunks/sec; also gates generation, since a load tests gen first).
     *  Base default 100. Lowered under load. */
    public static final DoubleKnob CHUNK_LOAD_RATE =
        new DoubleKnob("perf.chunk.load-rate", 100.0D,
            KnobMeta.active("Per-player chunk-load rate (chunks/sec); Paper playerMaxChunkLoadRate.",
                "Base 100; the perf-engine lowers it under load. A load tests generation first, so this also bounds gen."));

    /** Per-player chunk GENERATE rate (chunks/sec). Base default -1 (unlimited) — the uncapped
     *  generation of fast-moving players into fresh terrain is a prime cause of multi-second MSPT
     *  stalls. GREEN keeps -1 (no regression); the perf-engine imposes a finite cap under load. */
    public static final DoubleKnob CHUNK_GENERATE_RATE =
        new DoubleKnob("perf.chunk.generate-rate", -1.0D,
            KnobMeta.active("Per-player chunk-generation rate (chunks/sec); Paper playerMaxChunkGenerateRate.",
                "Base -1 (unlimited); the perf-engine imposes a finite cap under load to kill gen-burst stalls."));

    public static Map<String, Object> snapshot() {
        return KnobRegistry.snapshot();
    }

    public static void loadFromYml() {
        KnobRegistry.loadAllFromYml();
    }

    /** Logs the current knob snapshot under context "boot". Convenience for boot-time call site. */
    public static void logLoaded() { KnobRegistry.logLoaded("boot"); }

    /** Logs the current knob snapshot under a caller-chosen context label (e.g. "tier-transition"). */
    public static void logLoaded(String context) { KnobRegistry.logLoaded(context); }
}
