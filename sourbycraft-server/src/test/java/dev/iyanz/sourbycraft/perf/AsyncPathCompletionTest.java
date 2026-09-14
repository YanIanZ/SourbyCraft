package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.papermc.paper.threadedregions.EntityScheduler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.Test;

class AsyncPathCompletionTest {
    @Test
    void v12RetiredSchedulerRejectsAndReleasesPendingSolve() {
        final EntityScheduler scheduler = new EntityScheduler(mock(CraftEntity.class));
        scheduler.retire();
        final AtomicBoolean pending = new AtomicBoolean(true);
        final AtomicBoolean applied = new AtomicBoolean();
        AsyncPathCompletion.schedule(scheduler, () -> applied.set(true), () -> pending.set(false));
        assertFalse(pending.get());
        assertFalse(applied.get());
    }

    @Test
    void v12RetirementAfterAdmissionReleasesWithoutApplying() {
        final EntityScheduler scheduler = new EntityScheduler(mock(CraftEntity.class));
        final AtomicBoolean pending = new AtomicBoolean(true);
        final AtomicBoolean applied = new AtomicBoolean();
        AsyncPathCompletion.schedule(scheduler, () -> applied.set(true), () -> pending.set(false));
        assertTrue(pending.get());
        assertFalse(applied.get());
        scheduler.retire();
        assertFalse(pending.get());
        assertFalse(applied.get());
    }

    @Test
    void v12ResultWaitsForOwnerCallbackAndReleasesBeforeApply() {
        final EntityScheduler scheduler = mock(EntityScheduler.class);
        final AtomicReference<Consumer<Entity>> callback = new AtomicReference<>();
        when(scheduler.schedule(any(), any(), eq(1L))).thenAnswer(invocation -> {
            callback.set(invocation.getArgument(0));
            return true;
        });
        final AtomicBoolean pending = new AtomicBoolean(true);
        final AtomicInteger applied = new AtomicInteger();
        AsyncPathCompletion.schedule(scheduler, () -> {
            assertFalse(pending.get());
            applied.incrementAndGet();
        }, () -> pending.set(false));
        assertEquals(0, applied.get());
        callback.get().accept(null);
        assertFalse(pending.get());
        assertEquals(1, applied.get());
    }

    @Test
    void v12SchedulingFailureReleasesPendingSolve() {
        final EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.schedule(any(), any(), eq(1L))).thenThrow(new IllegalStateException("fixture"));
        final AtomicBoolean pending = new AtomicBoolean(true);
        AsyncPathCompletion.schedule(scheduler, () -> fail("must not apply"), () -> pending.set(false));
        assertFalse(pending.get());
    }
}
