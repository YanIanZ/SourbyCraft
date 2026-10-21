package dev.iyanz.sourbycraft.guards;

import dev.iyanz.sourbycraft.execution.region.FoliaRegionBackend;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import dev.iyanz.sourbycraft.execution.region.RegionTickRate;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The server-wide state behind Bukkit's {@code ServerTickManager} (freeze, step, sprint, tick rate),
 * which the region-threading base refused outright.
 *
 * <p><b>One source of truth.</b> The state lives in vanilla's own {@link ServerTickRateManager}
 * ({@code MinecraftServer#tickRateManager()}), the object vanilla readers already use
 * ({@code ServerFunctionManager} checks {@code runsNormally()}, the watchdog and the MSPT report
 * read its tick length). This class does not copy its rules; it serialises access to it (the vanilla
 * manager is not thread-safe, and the API may be called from any thread), drives it once per global
 * tick in place of the single server tick loop that no longer exists, and publishes the answer the
 * regions read. Vanilla's own client updates are kept: the ticking state is sent on every freeze
 * change and the step count is sent when a step is requested or stopped, with the full count, as
 * vanilla does.</p>
 *
 * <p><b>Semantics (they apply to every region and the global tick of every world).</b></p>
 * <ul>
 *   <li>Mutations ({@link #setFrozen}, {@link #stepGameIfFrozen}, {@link #requestGameToSprint},
 *       {@link #stopStepping}, {@link #stopSprinting}) may be called from any thread. They change the
 *       requested state at once, with vanilla's return values, and take effect at the next global
 *       tick: {@link #globalTick()} (called by the global tick handle once it is marked ticking, never
 *       for a cancelled handle) runs vanilla's {@code checkShouldSprintThisTick} and
 *       {@code TickRateManager#tick} exactly as vanilla's server tick did.</li>
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
 *   <li>The tick rate is one value for every region ({@link RegionTickRate}, the engine's scheduling
 *       rate); Paper's range check (1 to 10 000) applies. The vanilla manager's rate is kept equal to
 *       it (set together, and re-aligned at each global tick if the engine's rate changed elsewhere).</li>
 * </ul>
 */
public final class ServerTickControl {

    private static final class Holder {
        // Resolved lazily: the regions read the published state (and the engine's own tests tick
        // handles) before, or without, a MinecraftServer.
        static final ServerTickControl INSTANCE = new ServerTickControl(() -> {
            final MinecraftServer server = MinecraftServer.getServer();
            return server == null ? null : server.tickRateManager();
        }, new FoliaRegionBackend());
    }

    private final Supplier<@Nullable ServerTickRateManager> vanillaSource;
    private @Nullable ServerTickRateManager vanilla; // guarded by this
    private final RegionTickRate tickRate;

    // Published once per global tick, read by every region at the start of its tick.
    private volatile boolean runsGameElements = true;
    private volatile boolean sprintsThisTick;

    /**
     * A control over {@code vanilla} and {@code tickRate}; the server uses {@link #get()}.
     *
     * @param vanilla the server's vanilla tick-rate manager, which holds the state
     * @param tickRate the engine's one server-wide scheduling rate
     */
    public ServerTickControl(final ServerTickRateManager vanilla, final RegionTickRate tickRate) {
        this(constant(Objects.requireNonNull(vanilla, "vanilla")), tickRate);
    }

    ServerTickControl(final Supplier<@Nullable ServerTickRateManager> vanillaSource, final RegionTickRate tickRate) {
        this.vanillaSource = vanillaSource;
        this.tickRate = Objects.requireNonNull(tickRate, "tickRate");
    }

    private static Supplier<@Nullable ServerTickRateManager> constant(final ServerTickRateManager manager) {
        return () -> manager;
    }

    /** The vanilla manager, or {@code null} while no server exists. Call with this held. */
    private @Nullable ServerTickRateManager vanillaOrNull() {
        if (this.vanilla == null) {
            this.vanilla = this.vanillaSource.get();
        }
        return this.vanilla;
    }

    /** The vanilla manager; the API is used only once the server exists. Call with this held. */
    private ServerTickRateManager vanilla() {
        final ServerTickRateManager manager = this.vanillaOrNull();
        if (manager == null) {
            throw new IllegalStateException("ServerTickManager used before the server exists");
        }
        return manager;
    }

    /** The server's control, over {@code MinecraftServer#tickRateManager()}. */
    public static ServerTickControl get() {
        return Holder.INSTANCE;
    }

    // ---- Reads (ServerTickManager) ----

    /** Vanilla {@code runsNormally()} as published for the current global tick (not frozen, or stepping). */
    public boolean isRunningNormally() {
        return this.runsGameElements;
    }

    /** Whether a sprint makes every region tick back to back in the current global tick. */
    public boolean sprintsThisTick() {
        return this.sprintsThisTick;
    }

    public synchronized boolean isStepping() {
        return this.vanilla().isSteppingForward();
    }

    public synchronized boolean isSprinting() {
        return this.vanilla().isSprinting();
    }

    public synchronized boolean isFrozen() {
        return this.vanilla().isFrozen();
    }

    public synchronized int getFrozenTicksToRun() {
        return this.vanilla().frozenTicksToRun();
    }

    /** Vanilla {@code isEntityFrozen} on the server-wide state only: game elements stopped, and the entity is no player and carries none. */
    public boolean isEntityFrozen(final boolean isPlayer, final int playerPassengers) {
        return !this.isRunningNormally() && !isPlayer && playerPassengers <= 0;
    }

    /**
     * Paper's {@code ServerTickManager#isFrozen(Entity)}, for any calling thread.
     *
     * <p>On the thread that owns the entity the answer is the region's own: {@code regionAnswer}
     * (the engine's per-region {@code isEntityFrozen}, which combines the region's own pause with
     * the server-wide state copied at the start of this tick, and reads the passengers on their
     * owner). Off the owner the region's tick state belongs to another thread and is not read: the
     * answer is the server-wide state, and the player passengers are counted from the entity's
     * passenger lists, which are immutable lists replaced as a whole, so the count is a consistent
     * snapshot of some recent moment (a passenger mounting at the same time may or may not be seen),
     * never a torn read.</p>
     *
     * @param entity the entity
     * @param regionAnswer the owning region's answer; called only on the owner thread
     * @return whether the entity is frozen
     */
    public boolean isEntityFrozen(final Entity entity, final Predicate<Entity> regionAnswer) {
        Objects.requireNonNull(entity, "entity");
        if (entity instanceof Player) {
            return false;
        }
        if (RegionOwnership.current().isTickThreadFor(entity)) {
            return regionAnswer.test(entity);
        }
        if (this.isRunningNormally()) {
            return false; // no need to look at the passengers at all
        }
        return this.isEntityFrozen(false, entity.countPlayerPassengers());
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
        synchronized (this) {
            this.vanilla().setTickRate(rate);
        }
        this.tickRate.setTickRate(rate);
    }

    /** Paper's {@code CraftServerTickManager#setFrozen}: freezing first stops a sprint and a step. */
    public synchronized void setFrozen(final boolean freeze) {
        if (freeze) {
            if (this.vanilla().isSprinting()) {
                this.vanilla().stopSprinting();
            }
            if (this.vanilla().isSteppingForward()) {
                this.vanilla().stopStepping();
            }
        }
        this.vanilla().setFrozen(freeze);
    }

    /** Vanilla {@code stepGameIfPaused}: only while frozen; returns whether the step was accepted. Clients are told the full count now. */
    public synchronized boolean stepGameIfFrozen(final int ticks) {
        return this.vanilla().stepGameIfPaused(ticks);
    }

    /** Vanilla {@code stopStepping}: returns whether a step was in progress. */
    public synchronized boolean stopStepping() {
        return this.vanilla().stopStepping();
    }

    /** Paper's silent {@code requestGameToSprint}: unfreezes for the sprint; returns whether a sprint was interrupted. */
    public synchronized boolean requestGameToSprint(final int ticks) {
        return this.vanilla().requestGameToSprint(ticks, true);
    }

    /** Vanilla {@code stopSprinting}: returns whether a sprint was in progress. */
    public synchronized boolean stopSprinting() {
        return this.vanilla().stopSprinting();
    }

    // ---- Driven by the global tick ----

    /**
     * Engine glue, called once per global tick by the global tick handle after it is marked
     * ticking: advances the vanilla manager by one tick (vanilla's server tick did
     * {@code checkShouldSprintThisTick}, then {@code TickRateManager#tick}) and publishes the
     * result for the global tick and the regions.
     */
    public synchronized void globalTick() {
        final ServerTickRateManager vanilla = this.vanillaOrNull();
        if (vanilla == null) {
            return; // no server (engine tests): nothing to advance, the published state stays "running"
        }
        final float engineRate = this.tickRate.currentTickRate();
        if (vanilla.tickrate() != engineRate) {
            vanilla.setTickRate(engineRate); // the engine's rate changed elsewhere (config, region API)
        }
        final boolean sprint = vanilla.isSprinting() && vanilla.checkShouldSprintThisTick();
        vanilla.tick();
        this.runsGameElements = vanilla.runsNormally();
        this.sprintsThisTick = sprint;
    }
}
