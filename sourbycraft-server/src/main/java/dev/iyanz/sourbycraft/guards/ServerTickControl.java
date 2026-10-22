package dev.iyanz.sourbycraft.guards;

import dev.iyanz.sourbycraft.execution.region.FoliaRegionBackend;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import dev.iyanz.sourbycraft.execution.region.RegionTickRate;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
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
 * regions read.</p>
 *
 * <p><b>Client updates.</b> Vanilla's own broadcasts are switched off (feature patch 0074): its
 * ticking-state packet carries only the server-wide freeze and would overwrite what a player in a
 * region paused through {@code getRegionThreadingTickRateManager()} must see. Instead, whenever
 * vanilla would have told clients, this class asks every player's owner to send that player its
 * region's combined state ({@code ScheduledHandleTickState#sendStateToPlayer}: frozen when the
 * region is paused or the server is frozen), and sends the step count (the full count when a step
 * is requested, 0 when it is stopped), as vanilla does. Both are sent after the lock is released.</p>
 *
 * <p><b>Threads.</b> Every read is a lock-free read of a snapshot published at the end of each
 * locked change, so a region thread asking {@link #isFrozen()} while it sends a player the state
 * never waits for a mutation or the global tick. Mutations and the global tick serialise on this
 * object and never send packets while holding it.</p>
 *
 * <p><b>Before the server exists</b> (the engine's own tests tick handles without one): the reads
 * return the defaults (not frozen, not stepping, not sprinting, running normally, 0 frozen ticks to
 * run, the engine's rate, 20 unless changed), the global tick is a no-op, and every mutation,
 * {@link #setTickRate} included, is refused with {@link IllegalStateException} after its argument
 * check, changing nothing.</p>
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
 *       it: both are set together under the lock, and the vanilla rate is re-aligned (silently, the
 *       engine already told players) at each global tick if the engine's rate changed elsewhere
 *       (config, region API).</li>
 *   <li>Sprint time is accounted as in vanilla ({@code endTickWork} after each sprinted tick), over
 *       the global tick's own work.</li>
 * </ul>
 */
public final class ServerTickControl {

    /** Where the client updates go; the server's sends to every online player. */
    interface Clients {
        /** Has every player's owner send that player its region's combined ticking state. */
        void sendTickingState();

        /** Sends every player the step count. */
        void sendStepTicks(int ticks);
    }

    private static final Clients SERVER_CLIENTS = new Clients() {
        @Override
        public void sendTickingState() {
            // The region API's own per-player send (each player's owner sends its region's combined state).
            if (MinecraftServer.getServer() != null && org.bukkit.Bukkit.getServer() != null) {
                org.bukkit.Bukkit.getServer().getRegionThreadingTickRateManager().sendUpdateToAllPlayers();
            }
        }

        @Override
        public void sendStepTicks(final int ticks) {
            final MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                server.getPlayerList().broadcastAll(new ClientboundTickingStepPacket(ticks));
            }
        }
    };

    private static final class Holder {
        // Resolved lazily: the regions read the published state (and the engine's own tests tick
        // handles) before, or without, a MinecraftServer.
        static final ServerTickControl INSTANCE = new ServerTickControl(() -> {
            final MinecraftServer server = MinecraftServer.getServer();
            return server == null ? null : server.tickRateManager();
        }, new FoliaRegionBackend(), SERVER_CLIENTS);
    }

    private static final int NO_STEP_PACKET = -1;

    private final Supplier<@Nullable ServerTickRateManager> vanillaSource;
    private @Nullable ServerTickRateManager vanilla; // guarded by this
    private final RegionTickRate tickRate;
    private final Clients clients;
    private boolean sprintedThisTick; // guarded by this

    // Published once per global tick, read by every region at the start of its tick.
    private volatile boolean runsGameElements = true;
    private volatile boolean sprintsThisTick;
    // Snapshot of the vanilla state, published at the end of every locked change: reads never lock.
    private volatile boolean frozen;
    private volatile boolean sprinting;
    private volatile int frozenTicksToRun;

    /**
     * A control over {@code vanilla} and {@code tickRate}; the server uses {@link #get()}.
     *
     * @param vanilla the server's vanilla tick-rate manager, which holds the state
     * @param tickRate the engine's one server-wide scheduling rate
     */
    public ServerTickControl(final ServerTickRateManager vanilla, final RegionTickRate tickRate) {
        this(constant(Objects.requireNonNull(vanilla, "vanilla")), tickRate, SERVER_CLIENTS);
    }

    ServerTickControl(final Supplier<@Nullable ServerTickRateManager> vanillaSource, final RegionTickRate tickRate, final Clients clients) {
        this.vanillaSource = vanillaSource;
        this.tickRate = Objects.requireNonNull(tickRate, "tickRate");
        this.clients = Objects.requireNonNull(clients, "clients");
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

    /** The vanilla manager for a mutation; refused while no server exists. Call with this held. */
    private ServerTickRateManager vanilla() {
        final ServerTickRateManager manager = this.vanillaOrNull();
        if (manager == null) {
            throw new IllegalStateException("ServerTickManager changed before the server exists");
        }
        return manager;
    }

    /** Publishes the vanilla state for the lock-free reads. Call with this held. */
    private void publish(final ServerTickRateManager manager) {
        this.frozen = manager.isFrozen();
        this.sprinting = manager.isSprinting();
        this.frozenTicksToRun = manager.frozenTicksToRun();
    }

    /** Sends what a locked change decided, with the lock released. */
    private void tellClients(final boolean tickingState, final int stepTicks) {
        if (tickingState) {
            this.clients.sendTickingState();
        }
        if (stepTicks != NO_STEP_PACKET) {
            this.clients.sendStepTicks(stepTicks);
        }
    }

    /** The server's control, over {@code MinecraftServer#tickRateManager()}. */
    public static ServerTickControl get() {
        return Holder.INSTANCE;
    }

    // ---- Reads (ServerTickManager); lock-free, never throw ----

    /** Vanilla {@code runsNormally()} as published for the current global tick (not frozen, or stepping). */
    public boolean isRunningNormally() {
        return this.runsGameElements;
    }

    /** Whether a sprint makes every region tick back to back in the current global tick. */
    public boolean sprintsThisTick() {
        return this.sprintsThisTick;
    }

    public boolean isStepping() {
        return this.frozenTicksToRun > 0;
    }

    public boolean isSprinting() {
        return this.sprinting;
    }

    /** The requested freeze (also read by region threads when they send a player its state). */
    public boolean isFrozen() {
        return this.frozen;
    }

    public int getFrozenTicksToRun() {
        return this.frozenTicksToRun;
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

    /**
     * Paper's {@code CraftServerTickManager#setTickRate}: 1 to 10 000 ticks per second, for every
     * region. The engine's rate and the vanilla rate are set together under the lock, so concurrent
     * callers and the global tick's re-alignment always leave both on the last caller's value; the
     * engine tells players (each gets its region's combined state). Refused before the server exists.
     */
    public void setTickRate(final float rate) {
        if (!(rate >= 1.0F && rate <= 10_000.0F)) {
            throw new IllegalArgumentException("The given tick rate must not be less than 1.0 or greater than 10,000.0");
        }
        synchronized (this) {
            final ServerTickRateManager manager = this.vanilla();
            manager.setTickRate(rate); // silent (0074); onTickRateChanged is a no-op under region threading
            this.tickRate.setTickRate(rate); // only schedules a per-player state send; never waits on a region
            this.publish(manager);
        }
    }

    /** Paper's {@code CraftServerTickManager#setFrozen}: freezing first stops a sprint and a step. */
    public void setFrozen(final boolean freeze) {
        int step = NO_STEP_PACKET;
        synchronized (this) {
            final ServerTickRateManager manager = this.vanilla();
            if (freeze) {
                if (manager.isSprinting()) {
                    manager.stopSprinting();
                }
                if (manager.stopStepping()) {
                    step = 0;
                }
            }
            manager.setFrozen(freeze);
            this.publish(manager);
        }
        this.tellClients(true, step); // vanilla tells clients on every setFrozen
    }

    /** Vanilla {@code stepGameIfPaused}: only while frozen; returns whether the step was accepted. Clients are told the full count now. */
    public boolean stepGameIfFrozen(final int ticks) {
        final boolean accepted;
        synchronized (this) {
            final ServerTickRateManager manager = this.vanilla();
            accepted = manager.stepGameIfPaused(ticks);
            this.publish(manager);
        }
        this.tellClients(false, accepted ? ticks : NO_STEP_PACKET);
        return accepted;
    }

    /** Vanilla {@code stopStepping}: returns whether a step was in progress. */
    public boolean stopStepping() {
        final boolean stopped;
        synchronized (this) {
            final ServerTickRateManager manager = this.vanilla();
            stopped = manager.stopStepping();
            this.publish(manager);
        }
        this.tellClients(false, stopped ? 0 : NO_STEP_PACKET);
        return stopped;
    }

    /** Paper's silent {@code requestGameToSprint}: unfreezes for the sprint; returns whether a sprint was interrupted. */
    public boolean requestGameToSprint(final int ticks) {
        final boolean interrupted;
        synchronized (this) {
            final ServerTickRateManager manager = this.vanilla();
            interrupted = manager.requestGameToSprint(ticks, true);
            this.publish(manager);
        }
        this.tellClients(true, NO_STEP_PACKET); // vanilla's requestGameToSprint calls setFrozen(false)
        return interrupted;
    }

    /** Vanilla {@code stopSprinting}: returns whether a sprint was in progress. */
    public boolean stopSprinting() {
        final boolean stopped;
        synchronized (this) {
            final ServerTickRateManager manager = this.vanilla();
            stopped = manager.stopSprinting();
            this.publish(manager);
        }
        this.tellClients(stopped, NO_STEP_PACKET); // finishing a sprint restores the earlier freeze through setFrozen
        return stopped;
    }

    // ---- Driven by the global tick ----

    /**
     * Engine glue, called once per global tick by the global tick handle after it is marked
     * ticking: advances the vanilla manager by one tick (vanilla's server tick did
     * {@code checkShouldSprintThisTick}, then {@code TickRateManager#tick}) and publishes the
     * result for the global tick and the regions.
     */
    public void globalTick() {
        final boolean sprintEnded;
        synchronized (this) {
            final ServerTickRateManager manager = this.vanillaOrNull();
            if (manager == null) {
                return; // no server (engine tests): nothing to advance, the published state stays "running"
            }
            final float engineRate = this.tickRate.currentTickRate();
            if (manager.tickrate() != engineRate) {
                manager.setTickRate(engineRate); // silent: the engine told players when its rate changed
            }
            final boolean wasSprinting = manager.isSprinting();
            final boolean sprint = wasSprinting && manager.checkShouldSprintThisTick();
            sprintEnded = wasSprinting && !manager.isSprinting(); // finishTickSprint restored the earlier freeze
            manager.tick();
            this.sprintedThisTick = sprint;
            this.publish(manager);
            this.runsGameElements = manager.runsNormally();
            this.sprintsThisTick = sprint;
        }
        this.tellClients(sprintEnded, NO_STEP_PACKET);
    }

    /**
     * Engine glue, called by the global tick handle after the global tick's work: vanilla's
     * {@code endTickWork} for a sprinted tick, so the sprint's time spent is accounted.
     */
    public synchronized void globalTickEnd() {
        final ServerTickRateManager manager = this.vanillaOrNull();
        if (manager != null && this.sprintedThisTick) {
            this.sprintedThisTick = false;
            manager.endTickWork();
        }
    }
}
