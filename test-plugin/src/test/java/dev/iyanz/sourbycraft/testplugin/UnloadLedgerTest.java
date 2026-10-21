package dev.iyanz.sourbycraft.testplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.testplugin.UnloadLedger.Kind;
import org.junit.jupiter.api.Test;

/** The unload fixture's verdict rules, without a server. */
class UnloadLedgerTest {

    @Test
    void aDrainedWorldIsAccepted() {
        final UnloadLedger ledger = new UnloadLedger();
        final int region = ledger.register(Kind.REGION_DELAYED);
        final int entity = ledger.register(Kind.ENTITY_DELAYED);
        final int queued = ledger.register(Kind.REGION_QUEUED);
        final int entityQueued = ledger.register(Kind.ENTITY_QUEUED);
        ledger.ran(queued);                     // beat the unload
        ledger.unloadStarted();
        ledger.unloadCompleted();
        ledger.cancelled(region);
        ledger.retired(entity);
        ledger.retired(entityQueued);           // drained instead
        final UnloadLedger.Report report = ledger.report();
        assertTrue(report.ok(), report.wrong().toString());
        assertEquals(4, report.tasks());
        assertEquals(1, report.ran());
        assertEquals(2, report.retired());
        assertEquals(1, report.cancelled());
        assertEquals(0, report.leaked());
    }

    @Test
    void aTaskThatNeitherRanNorWasDrainedLeaked() {
        final UnloadLedger ledger = new UnloadLedger();
        ledger.register(Kind.REGION_QUEUED);
        ledger.unloadStarted();
        ledger.unloadCompleted();
        final UnloadLedger.Report report = ledger.report();
        assertFalse(report.ok());
        assertEquals(1, report.leaked());
        assertTrue(report.wrong().getFirst().contains("leaked"));
    }

    @Test
    void anEntityTaskNeverRetiredIsLeakedEvenIfFarInTheFuture() {
        final UnloadLedger ledger = new UnloadLedger();
        ledger.register(Kind.ENTITY_DELAYED);
        ledger.unloadCompleted();
        final UnloadLedger.Report report = ledger.report();
        assertFalse(report.ok());
        assertTrue(report.wrong().stream().anyMatch(w -> w.contains("not drained")));
    }

    @Test
    void workThatRunsAfterTheUnloadCompletedIsWrong() {
        final UnloadLedger ledger = new UnloadLedger();
        final int repeating = ledger.register(Kind.REGION_REPEATING);
        ledger.unloadStarted();
        ledger.unloadCompleted();
        ledger.ran(repeating);
        ledger.cancelled(repeating);
        final UnloadLedger.Report report = ledger.report();
        assertEquals(1, report.ranAfterUnload());
        assertFalse(report.ok());
    }

    @Test
    void aFarFutureTaskThatRanWasNotDueAndIsWrong() {
        final UnloadLedger ledger = new UnloadLedger();
        final int delayed = ledger.register(Kind.ENTITY_DELAYED);
        ledger.ran(delayed);                    // before unloadStarted: nothing but time could have run it
        ledger.retired(delayed);
        ledger.unloadStarted();
        ledger.unloadCompleted();
        final UnloadLedger.Report report = ledger.report();
        assertTrue(report.wrong().stream().anyMatch(w -> w.contains("before it was due")));
        assertTrue(report.wrong().stream().anyMatch(w -> w.contains("both ran and was retired")));
    }

    @Test
    void aFarFutureTaskRunByTheUnloadItselfIsReportedNotRejected() {
        // FoliaEntityScheduler runs the task body, not the retired callback, when the entity is not
        // removed -- which is the case when a world unload retires its entity schedulers.
        final UnloadLedger ledger = new UnloadLedger();
        final int delayed = ledger.register(Kind.ENTITY_DELAYED);
        final int repeating = ledger.register(Kind.ENTITY_REPEATING);
        ledger.unloadStarted();
        ledger.ran(delayed);
        ledger.ran(repeating);
        ledger.unloadCompleted();
        final UnloadLedger.Report report = ledger.report();
        assertTrue(report.ok(), report.wrong().toString());
        assertEquals(2, report.farFutureRanAtUnload());
        assertEquals(2, report.ranDuringUnload());
        assertEquals(0, report.leaked());
    }

    @Test
    void aOneShotThatRanAndWasAlsoDrainedIsWrongButARepeaterMayRunThenBeDrained() {
        final UnloadLedger ledger = new UnloadLedger();
        final int oneShot = ledger.register(Kind.ENTITY_QUEUED);
        final int repeater = ledger.register(Kind.ENTITY_REPEATING);
        ledger.ran(oneShot);
        ledger.retired(oneShot);
        ledger.retired(repeater);
        ledger.unloadStarted();
        ledger.unloadCompleted();
        final UnloadLedger.Report report = ledger.report();
        assertEquals(1, report.wrong().stream().filter(w -> w.contains("both ran")).count(), report.wrong().toString());
    }

    @Test
    void aRetiredCallbackRunningTwiceIsWrong() {
        final UnloadLedger ledger = new UnloadLedger();
        final int id = ledger.register(Kind.ENTITY_DELAYED);
        ledger.retired(id);
        ledger.retired(id);
        ledger.unloadCompleted();
        assertTrue(ledger.report().wrong().stream().anyMatch(w -> w.contains("retired 2x")));
    }

    @Test
    void anExecuteTaskThatNeverRanIsCountedForTheEngineCrossCheckNotCalledLeaked() {
        final UnloadLedger ledger = new UnloadLedger();
        final int ran = ledger.register(Kind.REGION_EXECUTE);
        ledger.register(Kind.REGION_EXECUTE);
        ledger.ran(ran);
        ledger.unloadStarted();
        ledger.unloadCompleted();
        final UnloadLedger.Report report = ledger.report();
        assertTrue(report.ok(), report.wrong().toString());
        assertEquals(1, report.executeNotRun());
        assertEquals(0, report.leaked());
    }

    @Test
    void regionTasksHaveNoRetiredCallbackAndEntityTasksAreNeverCancelled() {
        final UnloadLedger ledger = new UnloadLedger();
        final int region = ledger.register(Kind.REGION_DELAYED);
        final int entity = ledger.register(Kind.ENTITY_DELAYED);
        ledger.retired(region);
        ledger.cancelled(entity);
        ledger.unloadCompleted();
        final var wrong = ledger.report().wrong();
        assertTrue(wrong.stream().anyMatch(w -> w.contains("region task but has a retired callback")), wrong.toString());
        assertTrue(wrong.stream().anyMatch(w -> w.contains("entity task but was marked cancelled")), wrong.toString());
    }

    @Test
    void countsAndPendingFollowRegistration() {
        final UnloadLedger ledger = new UnloadLedger();
        final int a = ledger.register(Kind.REGION_QUEUED);
        ledger.register(Kind.REGION_QUEUED);
        ledger.register(Kind.ENTITY_QUEUED);
        assertEquals(3, ledger.size());
        assertEquals(2, ledger.count(Kind.REGION_QUEUED));
        assertEquals(3, ledger.pendingNow());
        ledger.ran(a);
        assertEquals(2, ledger.pendingNow());
        assertFalse(ledger.unloaded());
        ledger.unloadCompleted();
        assertTrue(ledger.unloaded());
    }
}
