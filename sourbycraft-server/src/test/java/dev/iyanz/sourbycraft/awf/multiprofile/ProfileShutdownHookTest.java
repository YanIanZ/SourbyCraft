package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ProfileShutdownHook} (PM-076, Build 53): the two handler slots (the adapter capture before plugins are disabled,
 * the final capture before players are saved) run once each, on the calling thread, with the budget they are given;
 * a handler that throws an exception is isolated, an Error is rethrown (E4); an overrun is only logged; nothing registered does nothing. The slots are static, so
 * each case starts and ends with both empty.
 */
class ProfileShutdownHookTest {

    private static final LongConsumer NOTHING = budget -> {};

    private static void clear() {
        ProfileShutdownHook.register(NOTHING);
        ProfileShutdownHook.runBeforePlayersSaved(1L);
        ProfileShutdownHook.registerAdapterCapture(NOTHING);
        ProfileShutdownHook.runBeforePluginsDisabled(1L);
    }

    @BeforeEach
    void empty() {
        clear();
    }

    @AfterEach
    void cleanUp() {
        clear();
    }

    @Test
    void nothingRegisteredDoesNothing() {
        assertFalse(ProfileShutdownHook.registered());
        assertFalse(ProfileShutdownHook.adapterCaptureRegistered());
        assertFalse(ProfileShutdownHook.runBeforePlayersSaved(5_000L));
        assertFalse(ProfileShutdownHook.runBeforePluginsDisabled(5_000L));
        assertFalse(ProfileShutdownHook.unregister(NOTHING));
        assertFalse(ProfileShutdownHook.unregisterAdapterCapture(NOTHING));
        assertFalse(ProfileShutdownHook.unregister(null));
        assertThrows(NullPointerException.class, () -> ProfileShutdownHook.register(null));
        assertThrows(NullPointerException.class, () -> ProfileShutdownHook.registerAdapterCapture(null));
    }

    @Test
    void runsOnceOnTheCallingThreadWithItsBudget() {
        final List<Long> budgets = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        ProfileShutdownHook.register(budget -> {
            budgets.add(budget);
            threads.add(Thread.currentThread());
        });
        assertTrue(ProfileShutdownHook.registered());
        assertTrue(ProfileShutdownHook.runBeforePlayersSaved(7_000L));
        assertEquals(List.of(7_000L), budgets);
        assertEquals(List.of(Thread.currentThread()), threads);
        assertFalse(ProfileShutdownHook.registered(), "the handler is removed before it runs");
        assertFalse(ProfileShutdownHook.runBeforePlayersSaved(7_000L), "a second run ran the handler again");
        assertEquals(1, budgets.size());

        // A budget of 0 or less is the default deadline.
        ProfileShutdownHook.register(budgets::add);
        assertTrue(ProfileShutdownHook.runBeforePlayersSaved(0L));
        ProfileShutdownHook.register(budgets::add);
        assertTrue(ProfileShutdownHook.runBeforePlayersSaved(-5L));
        assertEquals(List.of(7_000L, ProfileShutdownHook.DEFAULT_DEADLINE_MILLIS, ProfileShutdownHook.DEFAULT_DEADLINE_MILLIS), budgets);
        assertEquals(ProfileSettings.DEFAULT_SHUTDOWN.captureDeadlineMillis(), ProfileShutdownHook.DEFAULT_DEADLINE_MILLIS);
    }

    @Test
    void theTwoSlotsAreIndependent() {
        final List<String> ran = new CopyOnWriteArrayList<>();
        ProfileShutdownHook.registerAdapterCapture(budget -> ran.add("adapters"));
        ProfileShutdownHook.register(budget -> ran.add("players"));
        assertTrue(ProfileShutdownHook.runBeforePluginsDisabled(1_000L));
        assertEquals(List.of("adapters"), ran);
        assertTrue(ProfileShutdownHook.registered(), "the adapter run removed the final capture");
        assertFalse(ProfileShutdownHook.adapterCaptureRegistered());
        assertTrue(ProfileShutdownHook.runBeforePlayersSaved(1_000L));
        assertEquals(List.of("adapters", "players"), ran);
    }

    @Test
    void aThrowingHandlerIsIsolated() {
        ProfileShutdownHook.register(budget -> {
            throw new IllegalStateException("scripted shutdown capture failure");
        });
        assertFalse(ProfileShutdownHook.runBeforePlayersSaved(1_000L), "a throwing handler reported success");
        assertFalse(ProfileShutdownHook.registered());
        ProfileShutdownHook.registerAdapterCapture(budget -> {
            throw new IllegalArgumentException("scripted adapter capture failure");
        });
        assertFalse(ProfileShutdownHook.runBeforePluginsDisabled(1_000L));
        assertFalse(ProfileShutdownHook.adapterCaptureRegistered());
    }

    /** E4: an Error is not a save outcome: it is logged and rethrown, and the handler is still removed (runs once). */
    @Test
    void anErrorIsRethrown() {
        final StackOverflowError fatal = new StackOverflowError("scripted error");
        ProfileShutdownHook.register(budget -> {
            throw fatal;
        });
        assertSame(fatal, assertThrows(StackOverflowError.class, () -> ProfileShutdownHook.runBeforePlayersSaved(1_000L)));
        assertFalse(ProfileShutdownHook.registered());
        assertFalse(ProfileShutdownHook.runBeforePlayersSaved(1_000L));
        ProfileShutdownHook.registerAdapterCapture(budget -> {
            throw new AssertionError("scripted error");
        });
        assertThrows(AssertionError.class, () -> ProfileShutdownHook.runBeforePluginsDisabled(1_000L));
        assertFalse(ProfileShutdownHook.adapterCaptureRegistered());
    }

    /** An overrun is logged, not treated as a failure; the deadline bounds the handler's own work, not this call. */
    @Test
    void anOverrunIsOnlyLogged() {
        ProfileShutdownHook.register(budget -> {
            final long until = System.nanoTime() + (budget + 3L) * 1_000_000L;
            while (System.nanoTime() < until) {
                Thread.onSpinWait();
            }
        });
        assertTrue(ProfileShutdownHook.runBeforePlayersSaved(1L));
    }

    @Test
    void unregisterRemovesOnlyTheInstalledHandler() {
        final LongConsumer first = budget -> {};
        final LongConsumer second = budget -> {};
        ProfileShutdownHook.register(first);
        ProfileShutdownHook.register(second);
        assertFalse(ProfileShutdownHook.unregister(first), "a replaced handler removed its replacement");
        assertTrue(ProfileShutdownHook.registered());
        assertTrue(ProfileShutdownHook.unregister(second));
        assertFalse(ProfileShutdownHook.registered());
        ProfileShutdownHook.registerAdapterCapture(first);
        assertFalse(ProfileShutdownHook.unregisterAdapterCapture(second));
        assertTrue(ProfileShutdownHook.unregisterAdapterCapture(first));
        assertFalse(ProfileShutdownHook.adapterCaptureRegistered());
    }
}
