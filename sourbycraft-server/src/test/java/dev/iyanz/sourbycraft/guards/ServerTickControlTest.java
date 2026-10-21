package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.execution.region.RegionTickRate;
import org.junit.jupiter.api.Test;

/** Vanilla {@code ServerTickRateManager} rules, driven by the global tick instead of the server loop. */
class ServerTickControlTest {

    private final float[] rate = {20.0F};
    private final ServerTickControl control = new ServerTickControl(new RegionTickRate() {
        @Override
        public float currentTickRate() {
            return ServerTickControlTest.this.rate[0];
        }

        @Override
        public void setTickRate(final float hz) {
            ServerTickControlTest.this.rate[0] = hz;
        }
    });

    @Test
    void startsRunningNormally() {
        assertTrue(this.control.isRunningNormally());
        assertFalse(this.control.isFrozen());
        assertFalse(this.control.isStepping());
        assertFalse(this.control.isSprinting());
        assertEquals(0, this.control.globalTick(), "nothing to tell players");
        assertTrue(this.control.isRunningNormally());
    }

    @Test
    void freezingIsRequestedAtOnceAndTakesEffectAtTheNextGlobalTick() {
        this.control.setFrozen(true);
        assertTrue(this.control.isFrozen());
        assertTrue(this.control.isRunningNormally(), "regions keep the published state until the global tick advances it");
        assertEquals(ServerTickControl.STATE_CHANGED, this.control.globalTick());
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
        assertEquals(ServerTickControl.STEP_CHANGED, this.control.globalTick());
        assertTrue(this.control.isRunningNormally());
        this.control.globalTick();
        assertTrue(this.control.isRunningNormally());
        this.control.globalTick();
        assertTrue(this.control.isRunningNormally());
        assertFalse(this.control.isStepping());
        this.control.globalTick();
        assertFalse(this.control.isRunningNormally(), "after three stepped ticks the game is frozen again");
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
        assertEquals(ServerTickControl.STATE_CHANGED, this.control.globalTick(), "sprint over: frozen restored, players told");
        assertFalse(this.control.sprintsThisTick());
        assertFalse(this.control.isSprinting());
        assertTrue(this.control.isFrozen());
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
}
