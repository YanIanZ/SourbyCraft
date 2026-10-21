package dev.iyanz.sourbycraft.guards;

import dev.iyanz.sourbycraft.execution.region.FoliaRegionBackend;
import dev.iyanz.sourbycraft.execution.region.RegionTickRate;
import java.util.Objects;

/**
 * The server-wide state behind Bukkit's {@code ServerTickManager} (freeze, step, sprint, tick rate),
 * which the region-threading base refused outright. It is vanilla's {@code ServerTickRateManager}
 * rules, re-hosted: the vanilla manager is driven by the single server tick loop, which does not
 * exist here, so this one is driven by the global tick and read by every region.
 *
 * <p><b>Semantics (they apply to every region and the global tick of every world).</b></p>
 * <ul>
 *   <li>Mutations ({@link #setFrozen}, {@link #stepGameIfFrozen}, {@link #requestGameToSprint},
 *       {@link #stopStepping}, {@link #stopSprinting}) may be called from any thread. They change the
 *       requested state at once, with vanilla's return values, and take effect at the next global
 *       tick: {@link #globalTick()} advances the state once per global tick exactly as vanilla's
 *       server tick did ({@code checkShouldSprintThisTick}, then {@code TickRateManager#tick}).</li>
 *   <li>Each region copies the published state at the start of its own tick, so one region tick
 *       sees one consistent answer. Regions tick independently, so a region may pick up a change one
 *       of its ticks later than another region: "frozen" is the same flag for every region, but the
 *       tick boundary at which each region observes it is not shared.</li>
 *   <li>Step: while frozen, {@code stepGameIfFrozen(n)} lets game elements run for the next {@code n}
 *       global ticks; a region that ticks at the same rate runs about {@code n} game ticks, a region
 *       that is behind runs fewer. Sprint: for the next {@code n} global ticks every region and the
 *       global tick run back to back instead of at the tick rate; the sprint ends when the global
 *       tick has run {@code n} ticks, not when every region has.</li>
 *   <li>A region's own pause/sprint ({@code Server#getRegionThreadingTickRateManager()}) is separate
 *       and still applies; a region runs game elements only when both allow it.</li>
 *   <li>The tick rate is one value for every region ({@link RegionTickRate}); Paper's range check
 *       (1 to 10 000) applies.</li>
 * </ul>
 */
public final class ServerTickControl {

    /** {@link #globalTick()} flag: the frozen state changed; players should be sent the ticking state. */
    public static final int STATE_CHANGED = 1;
    /** {@link #globalTick()} flag: the step count changed; players should be sent the step count. */
    public static final int STEP_CHANGED = 2;

    private static final class Holder {
        static final ServerTickControl INSTANCE = new ServerTickControl(new FoliaRegionBackend());
    }

    private final RegionTickRate tickRate;

    // Requested state; guarded by this.
    private boolean frozen;
    private int frozenTicksToRun;
    private long remainingSprintTicks;
    private long scheduledSprintTicks;
    private boolean previousFrozen;
    private int pendingChanges;

    // Published once per global tick, read by every region at the start of its tick.
    private volatile boolean runsGameElements = true;
    private volatile boolean sprintsThisTick;

    /** A control over {@code tickRate}; the server uses {@link #get()}. */
    public ServerTickControl(final RegionTickRate tickRate) {
        this.tickRate = Objects.requireNonNull(tickRate, "tickRate");
    }

    /** The server's control. */
    public static ServerTickControl get() {
        return Holder.INSTANCE;
    }

    // ---- Reads (ServerTickManager) ----

    /** Vanilla {@code runsNormally()}: game elements run in the current global tick (not frozen, or stepping). */
    public boolean isRunningNormally() {
        return this.runsGameElements;
    }

    /** Whether a sprint makes every region tick back to back in the current global tick. */
    public boolean sprintsThisTick() {
        return this.sprintsThisTick;
    }

    public synchronized boolean isStepping() {
        return this.frozenTicksToRun > 0;
    }

    public synchronized boolean isSprinting() {
        return this.scheduledSprintTicks > 0L;
    }

    public synchronized boolean isFrozen() {
        return this.frozen;
    }

    public synchronized int getFrozenTicksToRun() {
        return this.frozenTicksToRun;
    }

    /** Vanilla {@code isEntityFrozen}: game elements stopped, and the entity is no player and carries none. */
    public boolean isEntityFrozen(final boolean isPlayer, final int playerPassengers) {
        return !this.isRunningNormally() && !isPlayer && playerPassengers <= 0;
    }

    public float getTickRate() {
        return this.tickRate.currentTickRate();
    }

    // ---- Mutations (ServerTickManager) ----

    /** Paper's {@code CraftServerTickManager#setTickRate}: 1 to 10 000 ticks per second, for every region. */
    public void setTickRate(final float rate) {
        if (!(rate >= 1.0F && rate <= 10_000.0F)) {
            throw new IllegalArgumentException("The given tick rate must not be less than 1.0 or greater than 10,000.0");
        }
        this.tickRate.setTickRate(rate);
    }

    /** Paper's {@code CraftServerTickManager#setFrozen}: freezing first stops a sprint and a step. */
    public synchronized void setFrozen(final boolean freeze) {
        if (freeze) {
            if (this.isSprinting()) {
                this.stopSprinting();
            }
            if (this.isStepping()) {
                this.stopStepping();
            }
        }
        this.setFrozenState(freeze);
    }

    /** Vanilla {@code stepGameIfPaused}: only while frozen; returns whether the step was accepted. */
    public synchronized boolean stepGameIfFrozen(final int ticks) {
        if (!this.frozen) {
            return false;
        }
        this.frozenTicksToRun = ticks;
        this.pendingChanges |= STEP_CHANGED;
        return true;
    }

    /** Vanilla {@code stopStepping}: returns whether a step was in progress. */
    public synchronized boolean stopStepping() {
        if (this.frozenTicksToRun > 0) {
            this.frozenTicksToRun = 0;
            this.pendingChanges |= STEP_CHANGED;
            return true;
        }
        return false;
    }

    /** Vanilla {@code requestGameToSprint}: unfreezes for the sprint; returns whether a sprint was interrupted. */
    public synchronized boolean requestGameToSprint(final int ticks) {
        final boolean interrupted = this.remainingSprintTicks > 0L;
        this.scheduledSprintTicks = ticks;
        this.remainingSprintTicks = ticks;
        this.previousFrozen = this.frozen;
        this.setFrozenState(false);
        return interrupted;
    }

    /** Vanilla {@code stopSprinting}: returns whether a sprint was in progress. */
    public synchronized boolean stopSprinting() {
        if (this.remainingSprintTicks > 0L) {
            this.finishSprint();
            return true;
        }
        return false;
    }

    // ---- Driven by the global tick ----

    /**
     * Engine glue: advances the state by one global tick and publishes it, before the global tick
     * and the regions read it. Vanilla's server tick did the same with
     * {@code checkShouldSprintThisTick} and {@code TickRateManager#tick}.
     *
     * @return {@link #STATE_CHANGED} and/or {@link #STEP_CHANGED} for what players must be told
     */
    public synchronized int globalTick() {
        boolean sprint = false;
        if (this.scheduledSprintTicks > 0L) {
            if (!this.runsGameElements) {
                sprint = false;
            } else if (this.remainingSprintTicks > 0L) {
                this.remainingSprintTicks--;
                sprint = true;
            } else {
                this.finishSprint();
            }
        }
        final boolean runs = !this.frozen || this.frozenTicksToRun > 0;
        if (this.frozenTicksToRun > 0) {
            this.frozenTicksToRun--;
        }
        this.runsGameElements = runs;
        this.sprintsThisTick = sprint;
        final int changes = this.pendingChanges;
        this.pendingChanges = 0;
        return changes;
    }

    private void finishSprint() {
        this.scheduledSprintTicks = 0L;
        this.remainingSprintTicks = 0L;
        this.setFrozenState(this.previousFrozen);
    }

    private void setFrozenState(final boolean freeze) {
        this.frozen = freeze;
        this.pendingChanges |= STATE_CHANGED; // vanilla sends the ticking state on every setFrozen
    }
}
