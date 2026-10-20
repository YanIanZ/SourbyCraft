package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * docs/TODO.md:58 (2026-10-20 harness, server-w1-redis-1): one {@code forceload add} with Redis down made
 * 731 failed reads in one second, one ERROR each. {@link ReadFailureLog} keeps the first failure of
 * every window with its stack and folds the rest into one summary.
 */
class ReadFailureLogTest {

    private static final long WINDOW = 10_000_000_000L;

    record Entry(String message, Throwable stack) {}

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final List<Entry> logged = new ArrayList<>();
    private final ReadFailureLog log = new ReadFailureLog("world/region", WINDOW, this.clock::get,
        (message, stack) -> this.logged.add(new Entry(message, stack)));

    @Test
    void theFirstFailureIsLoggedAtOnceWithItsStack() {
        final Exception cause = new java.net.ConnectException("Connection refused");
        assertTrue(this.log.failed("AWF could not read chunk 100,100", cause), "the first failure of a window");
        assertEquals(1, this.logged.size(), "never silenced");
        assertEquals("AWF could not read chunk 100,100", this.logged.get(0).message());
        assertEquals(cause, this.logged.get(0).stack(), "logged with its stack trace");
    }

    @Test
    void repeatsInsideTheWindowAreCountedAndSummarisedOnceItEnds() {
        this.log.failed("AWF could not read chunk 100,100", new java.io.IOException("Connection refused"));
        for (int i = 0; i < 728; i++) {
            this.clock.addAndGet(1_000_000L);
            assertFalse(this.log.failed("AWF could not read chunk " + i, new java.io.IOException("x")));
        }
        this.log.flush();
        assertEquals(1, this.logged.size(), "nothing more while the window is open");

        this.clock.set(1_000L + WINDOW);
        this.log.flush();
        assertEquals(2, this.logged.size());
        final Entry summary = this.logged.get(1);
        assertNull(summary.stack(), "the summary carries no stack");
        assertTrue(summary.message().contains("729 time(s)"), summary.message());
        assertTrue(summary.message().contains("world/region"), summary.message());
        assertTrue(summary.message().contains("first error: AWF could not read chunk 100,100"), summary.message());
        assertEquals(728, this.log.suppressed());

        this.log.flush();
        assertEquals(2, this.logged.size(), "a summary is written once");
    }

    @Test
    void aWindowWithOneFailureNeedsNoSummary() {
        this.log.failed("only one", new java.io.IOException("x"));
        this.clock.addAndGet(2 * WINDOW);
        this.log.flush();
        this.log.close();
        assertEquals(1, this.logged.size());
    }

    @Test
    void aFailureAfterTheWindowSummarisesTheOldOneAndOpensANewOneWithItsStack() {
        this.log.failed("first", new java.io.IOException("a"));
        this.log.failed("second", new java.io.IOException("b"));
        this.clock.addAndGet(WINDOW);
        final Exception later = new java.io.IOException("c");
        assertTrue(this.log.failed("third", later));
        assertEquals(3, this.logged.size());
        assertTrue(this.logged.get(1).message().contains("2 time(s)"), this.logged.get(1).message());
        assertEquals("third", this.logged.get(2).message());
        assertEquals(later, this.logged.get(2).stack());
        assertFalse(this.log.failed("fourth", new java.io.IOException("d")));
    }

    @Test
    void closeWritesAPendingSummaryEvenInsideTheWindow() {
        this.log.failed("first", new java.io.IOException("a"));
        this.log.failed("second", new java.io.IOException("b"));
        this.log.close();
        assertEquals(2, this.logged.size());
        assertNotNull(this.logged.get(1).message());
        assertTrue(this.logged.get(1).message().contains("2 time(s)"), this.logged.get(1).message());
    }

    @Test
    void aBurstOfNFailuresProducesAtMostTwoRecordsPerWindow() {
        final int windows = 3;
        for (int w = 0; w < windows; w++) {
            for (int i = 0; i < 1_000; i++) {
                this.log.failed("burst " + w + " " + i, new java.io.IOException("x"));
                this.clock.addAndGet(WINDOW / 2_000);
            }
            this.clock.addAndGet(WINDOW);
            this.log.flush();
        }
        assertEquals(2 * windows, this.logged.size(), "first (with stack) + summary per window");
    }
}
