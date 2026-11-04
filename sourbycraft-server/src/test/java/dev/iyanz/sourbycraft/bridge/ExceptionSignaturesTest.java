package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Signature dedupe, the per-minute summary and the bounded, least-recently-seen map. Written, not run (owner rule). */
class ExceptionSignaturesTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);

    private static ExceptionSignatures.Signature signature(final String plugin, final String frame) {
        return new ExceptionSignatures.Signature(plugin, "com.example.Task", "java.lang.NullPointerException", frame);
    }

    @Test
    void theFirstOccurrenceIsLoggedInFullAndRepeatsInsideTheWindowAreSuppressed() {
        final ExceptionSignatures signatures = new ExceptionSignatures(60L * SECOND, 16);
        final ExceptionSignatures.Signature key = signature("Shopkeepers", "Level.getLocalPlayers(Level.java:861)");
        final ExceptionSignatures.Decision first = signatures.record(key, 0L);
        assertEquals(ExceptionSignatures.Decision.Kind.FULL, first.kind());
        assertEquals(1L, first.total());
        assertTrue(first.logs());
        for (int i = 1; i <= 311; i++) {
            final ExceptionSignatures.Decision repeat = signatures.record(key, i * 100L * 1_000_000L);
            assertEquals(ExceptionSignatures.Decision.Kind.SUPPRESSED, repeat.kind(), "repeat " + i);
            assertFalse(repeat.logs());
            assertEquals(i + 1L, repeat.total());
        }
        assertEquals(1, signatures.size());
    }

    @Test
    void theFirstRepeatAfterTheWindowIsOneSummaryCarryingTheCount() {
        final ExceptionSignatures signatures = new ExceptionSignatures(60L * SECOND, 16);
        final ExceptionSignatures.Signature key = signature("Shopkeepers", "frame");
        signatures.record(key, 0L);
        for (int i = 0; i < 311; i++) signatures.record(key, 30L * SECOND);
        final ExceptionSignatures.Decision summary = signatures.record(key, 60L * SECOND);
        assertEquals(ExceptionSignatures.Decision.Kind.SUMMARY, summary.kind());
        assertEquals(312L, summary.repeats(), "311 suppressed plus the one that opened the summary");
        assertEquals(313L, summary.total());
        assertEquals("repeated 312 time(s) in the last 60 s (313 in total)", signatures.summaryNote(summary));
        // The summary opened a new window: the next repeat is suppressed again, and the next summary counts afresh.
        assertEquals(ExceptionSignatures.Decision.Kind.SUPPRESSED, signatures.record(key, 61L * SECOND).kind());
        final ExceptionSignatures.Decision next = signatures.record(key, 121L * SECOND);
        assertEquals(ExceptionSignatures.Decision.Kind.SUMMARY, next.kind());
        assertEquals(2L, next.repeats());
    }

    @Test
    void distinctSignaturesHaveIndependentWindows() {
        final ExceptionSignatures signatures = new ExceptionSignatures(60L * SECOND, 16);
        final ExceptionSignatures.Signature a = signature("Shopkeepers", "frame A");
        final ExceptionSignatures.Signature b = signature("Shopkeepers", "frame B");
        final ExceptionSignatures.Signature other = signature("DeluxeMenus", "frame A");
        assertEquals(ExceptionSignatures.Decision.Kind.FULL, signatures.record(a, 0L).kind());
        assertEquals(ExceptionSignatures.Decision.Kind.FULL, signatures.record(b, 0L).kind());
        assertEquals(ExceptionSignatures.Decision.Kind.FULL, signatures.record(other, 0L).kind(),
            "the same frame in another plugin is another signature");
        assertEquals(ExceptionSignatures.Decision.Kind.SUPPRESSED, signatures.record(a, SECOND).kind());
        assertEquals(3, signatures.size());
        assertEquals(2, signatures.snapshot("Shopkeepers").size());
        assertEquals(1, signatures.snapshot("DeluxeMenus").size());
    }

    @Test
    void theMapIsBoundedAndEvictsTheLeastRecentlySeenSignature() {
        final ExceptionSignatures signatures = new ExceptionSignatures(60L * SECOND, 3);
        final ExceptionSignatures.Signature a = signature("P", "a");
        final ExceptionSignatures.Signature b = signature("P", "b");
        final ExceptionSignatures.Signature c = signature("P", "c");
        final ExceptionSignatures.Signature d = signature("P", "d");
        signatures.record(a, 0L);
        signatures.record(b, 0L);
        signatures.record(c, 0L);
        signatures.record(a, SECOND); // a is now the most recently seen; b the least
        signatures.record(d, 2L * SECOND); // past the cap: b is evicted
        assertEquals(3, signatures.size());
        assertTrue(signatures.snapshot().stream().noneMatch(entry -> entry.frame().equals("b")));
        assertEquals(ExceptionSignatures.Decision.Kind.FULL, signatures.record(b, 3L * SECOND).kind(),
            "an evicted signature is logged in full again");
        assertEquals(ExceptionSignatures.Decision.Kind.SUPPRESSED, signatures.record(a, 4L * SECOND).kind(),
            "a was kept");
    }

    @Test
    void theSnapshotListsMostFrequentFirstWithPlainStrings() {
        final ExceptionSignatures signatures = new ExceptionSignatures(60L * SECOND, 16);
        final ExceptionSignatures.Signature rare = signature("P", "rare");
        final ExceptionSignatures.Signature common = signature("P", "common");
        signatures.record(rare, 0L);
        for (int i = 0; i < 5; i++) signatures.record(common, 0L);
        final var entries = signatures.snapshot();
        assertEquals(2, entries.size());
        assertEquals("common", entries.get(0).frame());
        assertEquals(5L, entries.get(0).total());
        assertEquals(4L, entries.get(0).suppressed(), "occurrences since the last logged line");
        assertEquals("java.lang.NullPointerException at common (body com.example.Task)", entries.get(0).describe());
    }

    @Test
    void aSignatureIsTakenFromTheThrowableAndNeverFromAMessage() {
        final Throwable one = new NullPointerException("Cannot invoke getLocalPlayers() because ... is null");
        final Throwable two = new NullPointerException("a different message, same place");
        two.setStackTrace(one.getStackTrace());
        final ExceptionSignatures.Signature first = ExceptionSignatures.Signature.of("Shopkeepers", "Body", one);
        final ExceptionSignatures.Signature second = ExceptionSignatures.Signature.of("Shopkeepers", "Body", two);
        assertEquals(first, second, "the message is not part of the signature");
        assertEquals("java.lang.NullPointerException", first.exception());
        assertEquals(one.getStackTrace()[0].toString(), first.frame());
        final Throwable bare = new IllegalStateException("no frames");
        bare.setStackTrace(new StackTraceElement[0]);
        assertEquals("no stack trace", ExceptionSignatures.Signature.of("P", "Body", bare).frame());
        assertEquals("unknown", new ExceptionSignatures.Signature(null, null, null, null).plugin());
    }

    @Test
    void theLimitsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new ExceptionSignatures(0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExceptionSignatures(SECOND, 0));
        assertEquals(TimeUnit.SECONDS.toNanos(60L), ExceptionSignatures.WINDOW_NANOS);
        assertEquals(256, ExceptionSignatures.MAX_SIGNATURES);
    }
}
