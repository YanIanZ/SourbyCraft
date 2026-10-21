package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import dev.iyanz.sourbycraft.execution.region.RegionTickRate;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ServerTickControl} over the server's own vanilla {@link ServerTickRateManager}, driven by the
 * global tick instead of the server loop (review finding 6: one source of truth, so vanilla readers
 * such as {@code ServerFunctionManager#tick} see the same state as the API and the regions).
 */
@org.bukkit.support.environment.Normal // PlayerList and the tick packets load registry-backed classes
class ServerTickControlTest {

    private final float[] rate = {20.0F};
    /** Vanilla's own broadcasts (must stay empty: they would overwrite the per-region state). */
    private final List<Packet<?>> sent = new ArrayList<>();
    /** What ServerTickControl told clients: "state" (per-region combined state) or "step:N". */
    private final List<String> told = new ArrayList<>();
    private ServerTickRateManager vanilla;
    private ServerTickControl control;

    @BeforeEach
    void setUp() {
        final MinecraftServer server = mock(MinecraftServer.class);
        final PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        doAnswer(inv -> this.sent.add(inv.getArgument(0))).when(players).broadcastAll(any(Packet.class));
        this.vanilla = new ServerTickRateManager(server);
        this.control = new ServerTickControl(() -> this.vanilla, new RegionTickRate() {
            @Override
            public float currentTickRate() {
                return ServerTickControlTest.this.rate[0];
            }

            @Override
            public void setTickRate(final float hz) {
                ServerTickControlTest.this.rate[0] = hz;
            }
        }, new ServerTickControl.Clients() {
            @Override
            public void sendTickingState() {
                ServerTickControlTest.this.told.add("state");
            }

            @Override
            public void sendStepTicks(final int ticks) {
                ServerTickControlTest.this.told.add("step:" + ticks);
            }
        });
    }

    private long count(final String what) {
        return this.told.stream().filter(what::equals).count();
    }

    private List<String> steps() {
        return this.told.stream().filter(t -> t.startsWith("step:")).toList();
    }

    @Test
    void startsRunningNormally() {
        assertTrue(this.control.isRunningNormally());
        assertFalse(this.control.isFrozen());
        assertFalse(this.control.isStepping());
        assertFalse(this.control.isSprinting());
        this.control.globalTick();
        assertTrue(this.control.isRunningNormally());
        assertTrue(this.told.isEmpty(), "nothing to tell players: " + this.told);
    }

    @Test
    void theStateLivesInTheVanillaManagerThatVanillaReadersUse() {
        this.control.setFrozen(true);
        assertTrue(this.vanilla.isFrozen(), "the API wrote the vanilla manager");
        this.control.globalTick();
        assertFalse(this.vanilla.runsNormally(), "ServerFunctionManager, which reads runsNormally(), now sees the freeze");
        assertFalse(this.control.isRunningNormally());
        this.control.setTickRate(40.0F);
        assertEquals(40.0F, this.vanilla.tickrate(), "and the vanilla tick length the watchdog and MSPT report read");
        assertEquals(40.0F, this.rate[0]);

        this.rate[0] = 30.0F; // the engine's rate changed elsewhere (config, region API)
        this.control.globalTick();
        assertEquals(30.0F, this.vanilla.tickrate(), "re-aligned at the next global tick");
    }

    @Test
    void freezingIsRequestedAtOnceAndTakesEffectAtTheNextGlobalTick() {
        this.control.setFrozen(true);
        assertTrue(this.control.isFrozen());
        assertTrue(this.control.isRunningNormally(), "regions keep the published state until the global tick advances it");
        assertEquals(1, this.count("state"), "players are told on the change (their region's combined state)");
        this.control.globalTick();
        assertFalse(this.control.isRunningNormally());
        assertTrue(this.control.isEntityFrozen(false, 0));
        assertFalse(this.control.isEntityFrozen(true, 0), "players are never frozen");
        assertFalse(this.control.isEntityFrozen(false, 1), "nor what carries a player");

        this.control.setFrozen(false);
        this.control.globalTick();
        assertTrue(this.control.isRunningNormally());
        assertFalse(this.control.isEntityFrozen(false, 0));
    }

    @Test
    void stepRunsExactlyThatManyGlobalTicksAndOnlyWhileFrozen() {
        assertFalse(this.control.stepGameIfFrozen(3), "vanilla refuses a step while running");
        this.control.setFrozen(true);
        this.control.globalTick();
        assertTrue(this.control.stepGameIfFrozen(3));
        assertTrue(this.control.isStepping());
        assertEquals(3, this.control.getFrozenTicksToRun());
        this.control.globalTick();
        assertTrue(this.control.isRunningNormally());
        this.control.globalTick();
        assertTrue(this.control.isRunningNormally());
        this.control.globalTick();
        assertTrue(this.control.isRunningNormally());
        assertFalse(this.control.isStepping());
        this.control.globalTick();
        assertFalse(this.control.isRunningNormally(), "after three stepped ticks the game is frozen again");
    }

    /**
     * Review finding 3: the step packet was sent after the global tick had already used one of the
     * stepped ticks, so clients were told one tick fewer. Vanilla sends the full count when the
     * step is requested.
     */
    @Test
    void clientsAreToldTheFullStepCountWhenTheStepIsRequested() {
        this.control.setFrozen(true);
        this.control.globalTick();
        this.control.stepGameIfFrozen(5);
        assertEquals(List.of("step:5"), this.steps());
        this.control.globalTick();
        this.control.globalTick();
        assertEquals(1, this.steps().size(), "the count is not re-sent per tick");
        assertTrue(this.control.stopStepping());
        assertEquals("step:0", this.steps().get(1), "stopping tells clients 0");
    }

    @Test
    void stopSteppingEndsTheStep() {
        this.control.setFrozen(true);
        assertFalse(this.control.stopStepping());
        this.control.stepGameIfFrozen(100);
        assertTrue(this.control.stopStepping());
        assertEquals(0, this.control.getFrozenTicksToRun());
        this.control.globalTick();
        assertFalse(this.control.isRunningNormally());
    }

    @Test
    void sprintUnfreezesRunsBackToBackForThatManyGlobalTicksThenRestoresFrozen() {
        this.control.setFrozen(true);
        this.control.globalTick();
        assertFalse(this.control.requestGameToSprint(2), "nothing interrupted");
        assertTrue(this.control.isSprinting());
        assertFalse(this.control.isFrozen(), "a sprint unfreezes, as vanilla does");
        // Vanilla's first check sees last tick's runGameElements (frozen), so it does not sprint yet.
        this.control.globalTick();
        assertFalse(this.control.sprintsThisTick());
        assertTrue(this.control.isRunningNormally());
        this.control.globalTick();
        assertTrue(this.control.sprintsThisTick());
        this.control.globalTick();
        assertTrue(this.control.sprintsThisTick());
        final long stateBefore = this.count("state");
        this.control.globalTick();
        assertEquals(stateBefore + 1, this.count("state"), "sprint over: frozen restored, players told");
        assertFalse(this.control.sprintsThisTick());
        assertFalse(this.control.isSprinting());
        assertTrue(this.control.isFrozen());
        this.control.globalTick();
        assertFalse(this.control.isRunningNormally());
    }

    @Test
    void aNewSprintReportsTheOneItInterruptedAndStopSprintingEndsIt() {
        this.control.requestGameToSprint(100);
        this.control.globalTick();
        assertTrue(this.control.requestGameToSprint(50));
        assertTrue(this.control.stopSprinting());
        assertFalse(this.control.isSprinting());
        assertFalse(this.control.stopSprinting());
    }

    @Test
    void freezingStopsASprintAndAStep() {
        this.control.requestGameToSprint(100);
        this.control.globalTick();
        this.control.globalTick();
        assertTrue(this.control.sprintsThisTick());
        this.control.setFrozen(true);
        assertFalse(this.control.isSprinting());
        assertTrue(this.control.isFrozen(), "frozen even though the sprint restored the earlier, running state");
        this.control.globalTick();
        assertFalse(this.control.sprintsThisTick());
        assertFalse(this.control.isRunningNormally());

        this.control.stepGameIfFrozen(5);
        this.control.setFrozen(true);
        assertFalse(this.control.isStepping());
    }

    @Test
    void theTickRateIsTheOneServerWideRateWithPapersRange() {
        assertEquals(20.0F, this.control.getTickRate());
        this.control.setTickRate(40.0F);
        assertEquals(40.0F, this.rate[0]);
        assertEquals(40.0F, this.control.getTickRate());
        assertThrows(IllegalArgumentException.class, () -> this.control.setTickRate(0.5F));
        assertThrows(IllegalArgumentException.class, () -> this.control.setTickRate(10_001.0F));
        assertThrows(IllegalArgumentException.class, () -> this.control.setTickRate(Float.NaN));
        assertEquals(40.0F, this.rate[0], "a rejected rate changes nothing");
    }

    /**
     * Review finding 5: {@code isFrozen(Entity)} ignored the region's own pause and read the
     * passengers off the owner. On the owner the region's answer (its pause combined with the
     * server-wide state) is used; off the owner the region is not consulted and the passengers are
     * read only when the server-wide state is frozen.
     */
    @Test
    void entityFrozenUsesTheRegionAnswerOnTheOwnerAndOnlyServerWideStateOffIt() {
        final Entity entity = mock(Entity.class);
        when(entity.countPlayerPassengers()).thenReturn(0);
        final RegionOwnership ownership = mock(RegionOwnership.class);
        final RegionOwnership original = RegionOwnership.replace(ownership);
        final List<Entity> askedRegion = new ArrayList<>();
        final Predicate<Entity> regionPaused = e -> {
            askedRegion.add(e);
            return true; // the region's own pause freezes it although the server runs normally
        };
        try {
            when(ownership.isTickThreadFor(entity)).thenReturn(true);
            assertTrue(this.control.isEntityFrozen(entity, regionPaused), "a region's own pause counts on the owner");
            assertEquals(List.of(entity), askedRegion);

            when(ownership.isTickThreadFor(entity)).thenReturn(false);
            assertFalse(this.control.isEntityFrozen(entity, e -> {
                throw new AssertionError("the region's tick state is not read off the owner");
            }), "off the owner: the server-wide state, which runs normally");
            this.control.setFrozen(true);
            this.control.globalTick();
            assertTrue(this.control.isEntityFrozen(entity, e -> {
                throw new AssertionError("the region's tick state is not read off the owner");
            }));
            when(entity.countPlayerPassengers()).thenReturn(1);
            assertFalse(this.control.isEntityFrozen(entity, e -> true), "an entity carrying a player is never frozen");
        } finally {
            RegionOwnership.replace(original);
        }
    }

    /** The engine ticks handles (and its tests run) without a MinecraftServer: the advance is then a no-op. */
    @Test
    void withoutAServerTheGlobalTickIsANoOpAndRegionsSeeRunning() {
        final float[] engine = {20.0F};
        final ServerTickControl serverless = new ServerTickControl(() -> null, new RegionTickRate() {
            @Override
            public float currentTickRate() {
                return engine[0];
            }

            @Override
            public void setTickRate(final float hz) {
                engine[0] = hz;
            }
        }, new ServerTickControl.Clients() {
            @Override
            public void sendTickingState() {
                throw new AssertionError("no clients without a server");
            }

            @Override
            public void sendStepTicks(final int ticks) {
                throw new AssertionError("no clients without a server");
            }
        });
        serverless.globalTick();
        serverless.globalTickEnd();
        assertTrue(serverless.isRunningNormally());
        assertFalse(serverless.sprintsThisTick());
        // Re-review finding 4: the reads answer the documented defaults instead of throwing.
        assertFalse(serverless.isFrozen());
        assertFalse(serverless.isStepping());
        assertFalse(serverless.isSprinting());
        assertEquals(0, serverless.getFrozenTicksToRun());
        assertEquals(20.0F, serverless.getTickRate());
        // Mutations are refused cleanly, after the argument check, and change nothing.
        assertThrows(IllegalStateException.class, () -> serverless.setFrozen(true));
        assertThrows(IllegalStateException.class, () -> serverless.setTickRate(30.0F));
        assertThrows(IllegalArgumentException.class, () -> serverless.setTickRate(0.5F));
        assertEquals(20.0F, engine[0], "a refused rate is not stored");
        assertFalse(serverless.isFrozen());
    }

    /**
     * Re-review findings 1 and 2: vanilla's broadcast carries only the server-wide freeze and
     * overwrote the combined state of players in a paused region. Vanilla's broadcasts are off; every
     * change, the sprint end and the global tick's silent rate re-alignment included, sends nothing
     * from vanilla, and state changes ask for the per-region combined state instead.
     */
    @Test
    void vanillaNeverBroadcastsAndStateChangesSendThePerRegionState() {
        this.control.setFrozen(true);
        this.control.globalTick();
        this.control.stepGameIfFrozen(2);
        this.control.stopStepping();
        this.control.setFrozen(false);
        this.control.setTickRate(30.0F);
        this.rate[0] = 25.0F; // the region API changed the engine rate (the engine told players itself)
        this.control.globalTick();
        assertEquals(25.0F, this.vanilla.tickrate());
        this.control.requestGameToSprint(1);
        for (int i = 0; i < 4; i++) {
            this.control.globalTick();
        }
        assertFalse(this.control.isSprinting());
        assertTrue(this.sent.isEmpty(), "vanilla's server-wide-only packets: " + this.sent);
        assertEquals(List.of("state", "step:2", "step:0", "state", "state", "state"), this.told,
            "freeze, step, stop, unfreeze, sprint start, sprint end; the rate changes are told by the engine");
    }

    /** Re-review finding 3: the vanilla and engine rates are set together, so racing callers leave them equal. */
    @Test
    void concurrentRateChangesLeaveVanillaAndEngineEqual() throws Exception {
        final CountDownLatch go = new CountDownLatch(1);
        final List<CompletableFuture<Void>> callers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final float base = 10.0F * (t + 1);
            callers.add(CompletableFuture.runAsync(() -> {
                try {
                    go.await();
                } catch (final InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                for (int i = 0; i < 2_000; i++) {
                    this.control.setTickRate(base + (i % 7));
                    if (i % 13 == 0) {
                        this.control.globalTick();
                    }
                }
            }));
        }
        go.countDown();
        CompletableFuture.allOf(callers.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        assertEquals(this.rate[0], this.vanilla.tickrate(), "no lost update between the two rates");
    }

    /** Re-review finding 5: reads never wait for the lock that mutations and the global tick hold. */
    @Test
    void readsDoNotWaitForTheLock() throws Exception {
        this.control.setFrozen(true);
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = new Thread(() -> {
            synchronized (this.control) {
                held.countDown();
                try {
                    release.await();
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.start();
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            final CompletableFuture<Boolean> read = CompletableFuture.supplyAsync(() ->
                this.control.isFrozen() && !this.control.isStepping() && !this.control.isSprinting()
                    && this.control.getFrozenTicksToRun() == 0);
            assertTrue(read.get(5, TimeUnit.SECONDS), "isFrozen() answered while the lock was held");
        } finally {
            release.countDown();
            holder.join();
        }
    }

    /** Re-review finding 6: the driver calls vanilla endTickWork after each sprinted global tick. */
    @Test
    void sprintedTicksAreAccounted() throws Exception {
        final Field spent = ServerTickRateManager.class.getDeclaredField("sprintTimeSpend");
        spent.setAccessible(true);
        this.control.requestGameToSprint(10);
        this.control.globalTick();
        this.control.globalTickEnd();
        this.control.globalTick();
        assertTrue(this.control.sprintsThisTick());
        Thread.sleep(2L);
        this.control.globalTickEnd();
        final long afterOne = spent.getLong(this.vanilla);
        assertTrue(afterOne >= TimeUnit.MILLISECONDS.toNanos(1L), "sprint time accounted: " + afterOne);
        this.control.globalTickEnd();
        assertEquals(afterOne, spent.getLong(this.vanilla), "accounted once per sprinted tick");
    }
}
