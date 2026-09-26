package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Ownership violations count toward quarantine; ordinary plugin bugs do not. */
class ViolationClassifierTest {

    @Test
    void theBasesRefusalMessagesAreViolations() {
        assertTrue(ViolationClassifier.isRegionViolation(new UnsupportedOperationException("Unsupported in region threading")));
        assertTrue(ViolationClassifier.isRegionViolation(new UnsupportedOperationException("Not on any region")));
        assertTrue(ViolationClassifier.isRegionViolation(
            new IllegalStateException("Accessing entity state off owning region's thread")));
    }

    @Test
    void aTickThreadCheckIsAViolationWhateverItsMessage() {
        final IllegalStateException fromTickThread = new IllegalStateException("Cannot add goal async");
        fromTickThread.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("ca.spottedleaf.moonrise.common.util.TickThread", "ensureTickThread", "TickThread.java", 1)});
        assertTrue(ViolationClassifier.isRegionViolation(fromTickThread));
    }

    @Test
    void aWrappedViolationIsFound() {
        assertTrue(ViolationClassifier.isRegionViolation(
            new RuntimeException("task failed", new UnsupportedOperationException("Unsupported in region threading"))));
    }

    @Test
    void ordinaryBugsAreNotViolations() {
        assertFalse(ViolationClassifier.isRegionViolation(new NullPointerException("x")));
        assertFalse(ViolationClassifier.isRegionViolation(new IllegalStateException("config missing")));
        assertFalse(ViolationClassifier.isRegionViolation(new UnsupportedOperationException("read-only list")));
    }

    @Test
    void aSelfCausedThrowableDoesNotLoop() {
        final RuntimeException self = new RuntimeException("loop") {
            @Override public synchronized Throwable getCause() { return this; }
        };
        assertFalse(ViolationClassifier.isRegionViolation(self));
    }
}
