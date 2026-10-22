package dev.iyanz.aurora.engine.world.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * OBS-14 engine world/block: the Lithium sleeping block-entity pair, {@link SleepingBlockEntity}
 * (swap a block entity's ticker for a do-nothing one, and back) and
 * {@link SleepUntilTimeBlockEntityTickInvoker} (wake it again at a game time). A block entity that is
 * put to sleep and never woken stops ticking for good, so the swap in both directions is what matters.
 */
class SleepingBlockEntityTest {

    /** The smallest implementation of the interface: two fields, everything else is the default code under test. */
    private static final class Sleeper implements SleepingBlockEntity {
        LevelChunk.RebindableTickingBlockEntityWrapper wrapper;
        TickingBlockEntity sleeping;

        @Override public LevelChunk.RebindableTickingBlockEntityWrapper lithium$getTickWrapper() { return this.wrapper; }
        @Override public void lithium$setTickWrapper(final LevelChunk.RebindableTickingBlockEntityWrapper tickWrapper) { this.wrapper = tickWrapper; }
        @Override public TickingBlockEntity lithium$getSleepingTicker() { return this.sleeping; }
        @Override public void lithium$setSleepingTicker(final TickingBlockEntity sleepingTicker) { this.sleeping = sleepingTicker; }
    }

    /** The wrapper's constructor is private; a real-method mock gives its real {@code ticker}/{@code rebind}. */
    private static LevelChunk.RebindableTickingBlockEntityWrapper wrapperAround(final TickingBlockEntity ticker) {
        final LevelChunk.RebindableTickingBlockEntityWrapper wrapper = mock(LevelChunk.RebindableTickingBlockEntityWrapper.class, CALLS_REAL_METHODS);
        wrapper.ticker = ticker;
        return wrapper;
    }

    @Test
    void withoutATickWrapperStartingToSleepChangesNothing() {
        final Sleeper sleeper = new Sleeper();

        sleeper.lithium$startSleeping();

        assertFalse(sleeper.isSleeping());
        assertNull(sleeper.sleeping);
    }

    @Test
    void sleepingRemembersTheRealTickerAndRebindsTheWrapperToTheSleepingOne() {
        final TickingBlockEntity real = mock(TickingBlockEntity.class);
        final Sleeper sleeper = new Sleeper();
        sleeper.wrapper = wrapperAround(real);

        sleeper.lithium$startSleeping();

        assertTrue(sleeper.isSleeping());
        assertSame(real, sleeper.sleeping);
        assertTrue(sleeper.wrapper.ticker instanceof SleepingBlockEntity.SleepingTicker);
        assertEquals("<lithium_sleeping>", sleeper.wrapper.ticker.getType());
        assertFalse(sleeper.wrapper.ticker.isRemoved(), "a sleeping ticker is never reported removed");
        sleeper.wrapper.tick();
        verify(real, never()).tick();
    }

    @Test
    void fallingAsleepTwiceKeepsTheOriginalTicker() {
        final TickingBlockEntity real = mock(TickingBlockEntity.class);
        final Sleeper sleeper = new Sleeper();
        sleeper.wrapper = wrapperAround(real);

        sleeper.lithium$startSleeping();
        final TickingBlockEntity sleepingTicker = sleeper.wrapper.ticker;
        sleeper.lithium$startSleeping();

        assertSame(real, sleeper.sleeping, "the real ticker is not overwritten by the sleeping one");
        assertSame(sleepingTicker, sleeper.wrapper.ticker);
    }

    @Test
    void wakingUpRestoresTheRealTickerAndForgetsTheSleepingState() {
        final TickingBlockEntity real = mock(TickingBlockEntity.class);
        final Sleeper sleeper = new Sleeper();
        sleeper.wrapper = wrapperAround(real);
        sleeper.lithium$startSleeping();

        sleeper.wakeUpNow();

        assertFalse(sleeper.isSleeping());
        assertSame(real, sleeper.wrapper.ticker);
        sleeper.wrapper.tick();
        verify(real, times(1)).tick();
    }

    @Test
    void wakingAnAwakeBlockEntityIsHarmless() {
        final TickingBlockEntity real = mock(TickingBlockEntity.class);
        final Sleeper sleeper = new Sleeper();
        sleeper.wrapper = wrapperAround(real);

        sleeper.wakeUpNow();

        assertSame(real, sleeper.wrapper.ticker);
        assertFalse(sleeper.isSleeping());
    }

    @Test
    void sleepWakeSleepCyclesDoNotLoseTheTicker() {
        final TickingBlockEntity real = mock(TickingBlockEntity.class);
        final Sleeper sleeper = new Sleeper();
        sleeper.wrapper = wrapperAround(real);

        for (int cycle = 0; cycle < 3; cycle++) {
            sleeper.lithium$startSleeping();
            assertNotSame(real, sleeper.wrapper.ticker);
            sleeper.wakeUpNow();
            assertSame(real, sleeper.wrapper.ticker);
        }
    }

    // ---- SleepUntilTimeBlockEntityTickInvoker

    private static BlockEntity sleepingBlockEntityAtGameTime(final long gameTime) {
        final BlockEntity entity = mock(BlockEntity.class, withSettings().extraInterfaces(SleepingBlockEntity.class));
        final Level level = mock(Level.class);
        when(level.getGameTime()).thenReturn(gameTime);
        when(entity.getLevel()).thenReturn(level);
        when(entity.getBlockPos()).thenReturn(new BlockPos(1, 2, 3));
        return entity;
    }

    @Test
    void theInvokerKeepsSleepingBeforeTheWakeTime() {
        final BlockEntity entity = sleepingBlockEntityAtGameTime(99L);
        final TickingBlockEntity delegate = mock(TickingBlockEntity.class);

        new SleepUntilTimeBlockEntityTickInvoker(entity, 100L, delegate).tick();

        verify((SleepingBlockEntity) entity, never()).setTicker(delegate);
        verify(delegate, never()).tick();
    }

    @Test
    void theInvokerWakesAtTheWakeTimeRebindsThenTicksOnceInThatOrder() {
        final BlockEntity entity = sleepingBlockEntityAtGameTime(100L);
        final TickingBlockEntity delegate = mock(TickingBlockEntity.class);

        new SleepUntilTimeBlockEntityTickInvoker(entity, 100L, delegate).tick();

        final InOrder order = inOrder((SleepingBlockEntity) entity, delegate);
        order.verify((SleepingBlockEntity) entity).setTicker(delegate);
        order.verify(delegate).tick();
        verify(delegate, times(1)).tick();
    }

    @Test
    void theInvokerWakesLateToo() {
        final BlockEntity entity = sleepingBlockEntityAtGameTime(5_000L);
        final TickingBlockEntity delegate = mock(TickingBlockEntity.class);

        new SleepUntilTimeBlockEntityTickInvoker(entity, 100L, delegate).tick();

        verify((SleepingBlockEntity) entity).setTicker(delegate);
        verify(delegate).tick();
    }

    @Test
    void theInvokerReportsTheEntityItsPositionAndItself() {
        final BlockEntity entity = sleepingBlockEntityAtGameTime(0L);
        final SleepUntilTimeBlockEntityTickInvoker invoker =
            new SleepUntilTimeBlockEntityTickInvoker(entity, 10L, mock(TickingBlockEntity.class));

        assertSame(entity, invoker.getTileEntity());
        assertEquals(new BlockPos(1, 2, 3), invoker.getPos());
    }
}
