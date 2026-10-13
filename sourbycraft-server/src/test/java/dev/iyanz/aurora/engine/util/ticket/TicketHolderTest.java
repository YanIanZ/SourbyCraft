package dev.iyanz.aurora.engine.util.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.canvasmc.canvas.WorldUnloadResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** AWF-2: a save ticket is never lost to a racing consume, and a second save joins the first. */
class TicketHolderTest {

    @Test
    void propagateTwiceCoalescesAndRunsBothCallbacks() {
        final TicketHolder<SaveAllTicket> holder = new TicketHolder<>();
        final List<String> ran = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        holder.propagate(new SaveAllTicket(() -> ran.add("first"), failures::add, false));
        holder.propagate(new SaveAllTicket(() -> ran.add("second"), failures::add, true));

        final SaveAllTicket merged = holder.pop();
        assertTrue(merged.flush(), "flush if either caller asked for it");
        assertNull(holder.pop(), "one ticket, consumed once");
        merged.exceptionPropagator().accept(new RuntimeException("boom"));
        merged.callback().run();
        assertEquals(List.of("first", "second"), ran);
        assertEquals(2, failures.size(), "both callers hear about a failure");
    }

    @Test
    void aThrowingCallbackDoesNotSkipTheOtherCallersCallback() {
        final TicketHolder<SaveAllTicket> holder = new TicketHolder<>();
        final AtomicBoolean second = new AtomicBoolean();
        holder.propagate(new SaveAllTicket(() -> { throw new IllegalStateException("first broke"); }, t -> {}, false));
        holder.propagate(new SaveAllTicket(() -> second.set(true), t -> {}, false));
        assertThrows(IllegalStateException.class, () -> holder.pop().callback().run());
        assertTrue(second.get());
    }

    @Test
    void unloadTicketsCoalesceToo() {
        final TicketHolder<UnloadTicket> holder = new TicketHolder<>();
        final List<WorldUnloadResult> results = new ArrayList<>();
        holder.propagate(new UnloadTicket(results::add, false));
        holder.propagate(new UnloadTicket(results::add, true));
        final UnloadTicket merged = holder.getOrThrow();
        assertTrue(merged.save());
        merged.callback().accept(WorldUnloadResult.SUCCESS);
        assertEquals(List.of(WorldUnloadResult.SUCCESS, WorldUnloadResult.SUCCESS), results);
    }

    private record Plain(int value) {}

    @Test
    void aTicketThatCannotCoalesceIsStillRefused() {
        final TicketHolder<Plain> holder = new TicketHolder<>();
        holder.propagate(new Plain(1));
        assertThrows(IllegalStateException.class, () -> holder.propagate(new Plain(2)));
        assertEquals(1, holder.popOrThrow().value());
        assertFalse(holder.isPresent());
    }

    @Test
    void concurrentPropagateAndConsumeNeverLoseACallback() throws Exception {
        final TicketHolder<SaveAllTicket> holder = new TicketHolder<>();
        final int producers = 4;
        final int perProducer = 5_000;
        final AtomicLong callbacks = new AtomicLong();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicBoolean producing = new AtomicBoolean(true);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        holder.propagate(new SaveAllTicket(callbacks::incrementAndGet, t -> errors.incrementAndGet(), false));
                    }
                } catch (final Throwable failed) {
                    errors.incrementAndGet();
                }
            }));
        }
        // The region tick: consumes whatever is there, as MinecraftServer does each tick.
        final Thread consumer = Thread.ofPlatform().start(() -> {
            try {
                start.await();
            } catch (final InterruptedException interrupted) {
                return;
            }
            while (producing.get()) holder.consumeIfPresent(ticket -> ticket.callback().run());
        });
        start.countDown();
        for (final Thread thread : threads) thread.join();
        producing.set(false);
        consumer.join();
        holder.consumeIfPresent(ticket -> ticket.callback().run());

        assertEquals(0, errors.get(), "propagate never throws 'Ticket already propagated'");
        assertEquals((long) producers * perProducer, callbacks.get(), "callbacks == propagations");
    }
}
