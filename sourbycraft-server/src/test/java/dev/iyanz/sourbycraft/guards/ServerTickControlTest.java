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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
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
    private final List<Packet<?>> sent = new ArrayList<>();
    private ServerTickRateManager vanilla;
    private ServerTickControl control;

    @BeforeEach
    void setUp() {
        final MinecraftServer server = mock(MinecraftServer.class);
        final PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        doAnswer(inv -> this.sent.add(inv.getArgument(0))).when(players).broadcastAll(any(Packet.class));
        this.vanilla = new ServerTickRateManager(server);
        this.control = new ServerTickControl(this.vanilla, new RegionTickRate() {
            @Override
            public float currentTickRate() {
                return ServerTickControlTest.this.rate[0];
            }

            @Override
            public void setTickRate(final float hz) {
                ServerTickControlTest.this.rate[0] = hz;
            }
        });
    }

    private <T> List<T> sent(final Class<T> type) {
        return this.sent.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void startsRunningNormally() {
        assertTrue(this.control.isRunningNormally());
        assertFalse(this.control.isFrozen());
        assertFalse(this.control.isStepping());
        assertFalse(this.control.isSprinting());
        this.control.globalTick();
        assertTrue(this.control.isRunningNormally());
        assertTrue(this.sent.isEmpty(), "nothing to tell players: " + this.sent);
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
        assertEquals(1, this.sent(ClientboundTickingStatePacket.class).size(), "vanilla tells clients on the change");
        assertTrue(this.sent(ClientboundTickingStatePacket.class).get(0).isFrozen());
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
        assertEquals(List.of(new ClientboundTickingStepPacket(5)), this.sent(ClientboundTickingStepPacket.class));
        this.control.globalTick();
        this.control.globalTick();
        assertEquals(1, this.sent(ClientboundTickingStepPacket.class).size(), "the count is not re-sent per tick");
        assertTrue(this.control.stopStepping());
        assertEquals(new ClientboundTickingStepPacket(0), this.sent(ClientboundTickingStepPacket.class).get(1), "stopping tells clients 0");
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
        final int stateBefore = this.sent(ClientboundTickingStatePacket.class).size();
        this.control.globalTick();
        assertEquals(stateBefore + 1, this.sent(ClientboundTickingStatePacket.class).size(), "sprint over: frozen restored, players told");
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
        final ServerTickControl serverless = new ServerTickControl(() -> null, new RegionTickRate() {
            @Override
            public float currentTickRate() {
                return 20.0F;
            }

            @Override
            public void setTickRate(final float hz) {
            }
        });
        serverless.globalTick();
        assertTrue(serverless.isRunningNormally());
        assertFalse(serverless.sprintsThisTick());
        assertThrows(IllegalStateException.class, () -> serverless.setFrozen(true));
    }
}
