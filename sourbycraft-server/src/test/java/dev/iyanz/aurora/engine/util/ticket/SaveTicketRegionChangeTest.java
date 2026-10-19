package dev.iyanz.aurora.engine.util.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.TickRegions;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * ENG-5: a save ticket survives its region merging or splitting before it ticked, and is reported
 * only when every chunk it covered was saved. ENG-10: a ticket's future is completed off the thread
 * running the callback, which can hold the regioniser's write lock.
 */
class SaveTicketRegionChangeTest {

    @Test
    void forwardingMovesTheTicketAndCoalescesWithOneAlreadyThere() {
        final List<String> ran = new ArrayList<>();
        final TicketHolder<SaveAllTicket> from = new TicketHolder<>();
        final TicketHolder<SaveAllTicket> into = new TicketHolder<>();
        from.propagate(new SaveAllTicket(() -> ran.add("merged"), thrown -> {}, true));
        into.propagate(new SaveAllTicket(() -> ran.add("target"), thrown -> {}, false));

        assertTrue(from.forwardTo(into));
        assertNull(from.get(), "the destroyed region no longer reports the save");
        final SaveAllTicket target = into.pop();
        assertTrue(target.flush());
        target.callback().run();
        assertEquals(List.of("target", "merged"), ran);
        assertFalse(from.forwardTo(into), "nothing to forward");
        assertNull(into.get());
    }

    @Test
    void aSplitTicketReportsOnceWhenEveryPartHasSaved() {
        final AtomicInteger reported = new AtomicInteger();
        final List<Throwable> failures = new ArrayList<>();
        final SaveAllTicket whole = new SaveAllTicket(reported::incrementAndGet, failures::add, true);
        final List<SaveAllTicket> parts = whole.split(3);
        assertEquals(3, parts.size());
        parts.forEach(part -> assertTrue(part.flush()));

        parts.get(0).callback().run();
        parts.get(0).callback().run(); // a part reported twice still counts once
        parts.get(2).callback().run();
        assertEquals(0, reported.get(), "a part of the old region is still unsaved");
        final IllegalStateException failed = new IllegalStateException("disk full");
        parts.get(1).exceptionPropagator().accept(failed);
        parts.get(1).callback().run();
        assertEquals(1, reported.get());
        assertEquals(List.of(failed), failures, "a part's failure reaches the original caller");
    }

    @Test
    void aSplitIntoOneRegionIsTheTicketItself() {
        final SaveAllTicket whole = new SaveAllTicket(() -> {}, thrown -> {}, false);
        assertSame(whole, whole.split(1).get(0));
        assertThrows(IllegalArgumentException.class, () -> whole.split(0));
    }

    @Test
    void theCompletingTicketCompletesItsFutureOffTheCallingThread() throws Exception {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final AtomicReference<Thread> dependentThread = new AtomicReference<>();
        final CompletableFuture<Void> dependent = future.thenRun(() -> dependentThread.set(Thread.currentThread()));
        final SaveAllTicket ticket = SaveAllTicket.completing(future, true);
        assertTrue(ticket.flush());
        ticket.callback().run();
        dependent.get(5, TimeUnit.SECONDS);
        assertFalse(Thread.currentThread() == dependentThread.get(),
            "a synchronous dependent must not run on the thread holding the regioniser lock");
    }

    @Test
    void aReportedFailureWinsOverTheCompletionThatFollows() {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final SaveAllTicket ticket = SaveAllTicket.completing(future, false);
        final IllegalStateException failed = new IllegalStateException("save failed");
        // The engine's consumer: failure callback in catch, completion callback in finally.
        ticket.exceptionPropagator().accept(failed);
        ticket.callback().run();
        final ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertSame(failed, thrown.getCause());
    }

    @Test
    void theRegionCallbacksUseTheseHelpers() throws IOException {
        // The wiring lives in TickRegions (MC feature patch 0031), which needs live regions to run.
        assertTrue(invokes(TickRegions.TickRegionData.class, "mergeInto", "forwardTo"),
            "a merge forwards the from-region's save ticket");
        assertTrue(invokes(TickRegions.TickRegionData.class, "split", "split"),
            "a split fans the save ticket out to the new regions");
        assertTrue(invokes(TickRegions.TickRegionData.class, "propagateSaveAllTicket", "completing"),
            "the API future completes off the calling thread");
    }

    private static boolean invokes(final Class<?> type, final String method, final String callee) throws IOException {
        final String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertTrue(input != null);
            for (final MethodModel model : ClassFile.of().parse(input.readAllBytes()).methods()) {
                if (!model.methodName().stringValue().equals(method)) continue;
                for (final CodeElement element : model.code().orElseThrow()) {
                    if (element instanceof InvokeInstruction invoke
                        && invoke.owner().asInternalName().startsWith("dev/iyanz/aurora/engine/util/ticket/")
                        && invoke.name().stringValue().equals(callee)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
