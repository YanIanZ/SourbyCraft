package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.iyanz.sourbycraft.execution.Admission;
import dev.iyanz.sourbycraft.execution.RegionOwnerHandoff;
import io.papermc.paper.threadedregions.EntityScheduler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.Test;

class AsyncPathCompletionTest {

    private static void deliver(final EntityScheduler scheduler, final Runnable apply,
                                final Runnable release) {
        AsyncPathCompletion.deliver(new RegionOwnerHandoff(scheduler), owner -> apply.run(), release);
    }

    @Test
    void v12RetiredSchedulerRejectsAndReleasesPendingSolve() {
        final EntityScheduler scheduler = new EntityScheduler(mock(CraftEntity.class));
        scheduler.retire();
        final AtomicBoolean pending = new AtomicBoolean(true);
        final AtomicBoolean applied = new AtomicBoolean();
        deliver(scheduler, () -> applied.set(true), () -> pending.set(false));
        assertFalse(pending.get());
        assertFalse(applied.get());
    }

    @Test
    void v12RetirementAfterAdmissionReleasesWithoutApplying() {
        final EntityScheduler scheduler = new EntityScheduler(mock(CraftEntity.class));
        final AtomicBoolean pending = new AtomicBoolean(true);
        final AtomicBoolean applied = new AtomicBoolean();
        deliver(scheduler, () -> applied.set(true), () -> pending.set(false));
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
        deliver(scheduler, () -> {
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
        deliver(scheduler, () -> fail("must not apply"), () -> pending.set(false));
        assertFalse(pending.get());
    }

    // --- the contract's reason for existing -------------------------------------------------
    //
    // These use a stand-in owner type rather than net.minecraft Entity, which cannot be
    // instantiated or mocked without a bootstrapped server. The contract is generic, and what
    // is under test here is that the owner the backend supplies is the one handed on.

    @Test
    void theOwnerDeliveredIsTheOneTheBackendSuppliesNotTheOneCaptured() {
        // A dimension transfer replaces the underlying entity, so the reference captured when
        // the solve began can be a different object than the live owner. The callback has to
        // be able to tell, which is only possible if it is handed the current one.
        final String captured = new String("owner");
        final String current = new String("owner");
        final AtomicReference<String> seen = new AtomicReference<>();

        AsyncPathCompletion.deliver((delivery, retirement) -> {
            delivery.accept(current);
            return Admission.ACCEPTED;
        }, seen::set, () -> {});

        assertSame(current, seen.get());
        assertNotSame(captured, seen.get());
    }

    @Test
    void aRefusedHandoffReleasesExactlyOnceAndNeverApplies() {
        final AtomicInteger released = new AtomicInteger();
        AsyncPathCompletion.deliver(
            (delivery, retirement) -> Admission.REJECTED,
            owner -> fail("a refused handoff must not apply"),
            released::incrementAndGet);
        assertEquals(1, released.get());
    }

    @Test
    void anAcceptedHandoffLeavesReleasingToTheCallback() {
        // If the caller released on acceptance as well, the pending flag would clear before the
        // owner ran and a second solve could be submitted for a result still in flight.
        final AtomicInteger released = new AtomicInteger();
        final AtomicReference<Consumer<? super String>> delivery = new AtomicReference<>();
        AsyncPathCompletion.<String>deliver((accepted, retirement) -> {
            delivery.set(accepted);
            return Admission.ACCEPTED;
        }, owner -> {}, released::incrementAndGet);

        assertEquals(0, released.get());
        delivery.get().accept("owner");
        assertEquals(1, released.get());
    }

    @Test
    void retirementReleasesWithoutTheOwner() {
        final AtomicInteger released = new AtomicInteger();
        final AtomicReference<Runnable> retired = new AtomicReference<>();
        AsyncPathCompletion.<String>deliver((delivery, retirement) -> {
            retired.set(retirement);
            return Admission.ACCEPTED;
        }, owner -> fail("retirement must not apply"), released::incrementAndGet);

        retired.get().run();
        assertEquals(1, released.get());
    }
}
