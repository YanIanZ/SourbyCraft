package dev.iyanz.aurora.engine.threadedregions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.iyanz.aurora.engine.util.ticket.UnloadTicket;
import dev.iyanz.sourbycraft.api.world.WorldUnloadResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** AWF-8: a world unload that throws still completes its callback, so its future never hangs. */
class WorldUnloadCompletionTest {

    @Test
    void aFailedUnloadStillInvokesTheCallbackWithFailUnknown() {
        final List<WorldUnloadResult> results = new ArrayList<>();
        final UnloadTicket ticket = new UnloadTicket(results::add, true);
        final IllegalStateException halt = new IllegalStateException("chunk system did not halt");
        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> WorldUnloadCompletion.run(() -> ticket, () -> { throw halt; }));
        assertSame(halt, thrown, "the failure still propagates (the thread's handler stops the server)");
        assertEquals(List.of(WorldUnloadResult.FAIL_UNKNOWN), results);
    }

    @Test
    void aSuccessfulUnloadInvokesTheCallbackOnceWithSuccess() {
        final List<WorldUnloadResult> results = new ArrayList<>();
        final UnloadTicket ticket = new UnloadTicket(results::add, true);
        WorldUnloadCompletion.run(() -> ticket, () -> {});
        assertEquals(List.of(WorldUnloadResult.SUCCESS), results);
    }

    @Test
    void aThrowingCallbackDoesNotHideTheUnloadFailure() {
        final UnloadTicket ticket = new UnloadTicket(result -> { throw new RuntimeException("callback"); }, true);
        final Error thrown = assertThrows(Error.class,
            () -> WorldUnloadCompletion.run(() -> ticket, () -> { throw new Error("unload"); }));
        assertEquals("unload", thrown.getMessage());
        assertEquals("callback", thrown.getSuppressed()[0].getMessage());
    }

    /**
     * PR #18 review: a chunk save that throws ends the unload before WorldUnloadDrain, which released
     * the bridged legacy tasks anchored in the world only at its end; they stayed queued for good.
     */
    @Test
    void theBridgeReleaseRunsBeforeTheCallbackWhenTheUnloadThrowsAndWhenItSucceeds() {
        final List<String> order = new ArrayList<>();
        final UnloadTicket ticket = new UnloadTicket(result -> order.add("callback " + result), true);
        assertThrows(IllegalStateException.class, () -> WorldUnloadCompletion.run(() -> ticket,
            () -> { throw new IllegalStateException("save failed"); }, () -> order.add("release")));
        assertEquals(List.of("release", "callback FAIL_UNKNOWN"), order);

        order.clear();
        WorldUnloadCompletion.run(() -> ticket, () -> order.add("unload"), () -> order.add("release"));
        assertEquals(List.of("unload", "release", "callback SUCCESS"), order);
    }

    @Test
    void aThrowingReleaseDoesNotHideTheUnloadFailureNorSkipTheCallback() {
        final List<WorldUnloadResult> results = new ArrayList<>();
        final UnloadTicket ticket = new UnloadTicket(results::add, true);
        final Error thrown = assertThrows(Error.class, () -> WorldUnloadCompletion.run(() -> ticket,
            () -> { throw new Error("unload"); }, () -> { throw new RuntimeException("release"); }));
        assertEquals("unload", thrown.getMessage());
        assertEquals("release", thrown.getSuppressed()[0].getMessage());
        assertEquals(List.of(WorldUnloadResult.FAIL_UNKNOWN), results);
    }
    @Test
    void aThrowingReleaseAfterSuccessfulUnloadStillCompletesExactlyOnce() {
        final List<WorldUnloadResult> results = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        final UnloadTicket ticket = new UnloadTicket(results::add, true);
        assertThrows(IllegalStateException.class, () -> WorldUnloadCompletion.run(() -> ticket, () -> {}, () -> {
            releases.incrementAndGet();
            throw new IllegalStateException("release failed");
        }));
        assertEquals(1, releases.get());
        assertEquals(List.of(WorldUnloadResult.FAIL_UNKNOWN), results);
    }

}
