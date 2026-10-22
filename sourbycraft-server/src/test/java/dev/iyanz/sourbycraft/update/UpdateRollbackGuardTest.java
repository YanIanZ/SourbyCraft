package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.update.UpdateRollbackGuard.BootDecision;
import dev.iyanz.sourbycraft.update.UpdateRollbackGuard.Phase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rollback state machine (audit SEC-3), driven boot by boot over a temp directory. Each
 * {@code onBoot()} call here stands for one server start.
 */
class UpdateRollbackGuardTest {

    @TempDir Path root;
    Path autoUpdate;
    Path launchJar;
    UpdateRollbackGuard guard;

    private static final byte[] OLD = "old build".getBytes();
    private static final byte[] NEW = "new build".getBytes();

    @BeforeEach
    void setUp() throws IOException {
        autoUpdate = root.resolve("auto_update");
        launchJar = Files.write(root.resolve("SourbyCraft.jar"), OLD);
        guard = new UpdateRollbackGuard(autoUpdate);
    }

    /** What UpdateApplier does: keep .prev, swap, record. */
    private Path applyNewBuild() throws IOException {
        Path prev = guard.keepPrevious(launchJar);
        Files.write(launchJar, NEW);
        guard.recordApplied("v26.2-r48", launchJar, prev, Instant.parse("2026-10-20T00:00:00Z"));
        return prev;
    }

    @Test
    void noApplyMeansNothingToDo() {
        assertEquals(BootDecision.NONE, guard.onBoot());
        assertFalse(guard.isHeld());
    }

    @Test
    void keepPreviousCopiesTheLaunchJarBesideItself() throws IOException {
        Path prev = guard.keepPrevious(launchJar);
        assertEquals(root.resolve("SourbyCraft.jar.prev"), prev);
        assertArrayEquals(OLD, Files.readAllBytes(prev));
        assertArrayEquals(OLD, Files.readAllBytes(launchJar), "the launch jar itself is untouched");
        assertFalse(Files.exists(root.resolve("SourbyCraft.jar.prev.part")));
    }

    @Test
    void firstBootReachingReadyConfirmsTheUpdate() throws IOException {
        applyNewBuild();

        assertEquals(BootDecision.AWAIT_READY, guard.onBoot());   // boot B starts on the new jar
        assertEquals(Phase.BOOTING, guard.state().orElseThrow().phase());
        guard.onReady();                                         // boot B reaches ServerLoadEvent STARTUP
        assertEquals(Phase.OK, guard.state().orElseThrow().phase());

        assertEquals(BootDecision.NONE, guard.onBoot());         // boot C: nothing owed
        assertArrayEquals(NEW, Files.readAllBytes(launchJar));
        assertFalse(guard.isHeld());
    }

    @Test
    void firstBootNotReachingReadyRestoresPrevAndHolds() throws IOException {
        Path prev = applyNewBuild();

        assertEquals(BootDecision.AWAIT_READY, guard.onBoot());   // boot B starts ... and dies before ready

        assertEquals(BootDecision.RESTORED, guard.onBoot());      // boot C notices
        assertArrayEquals(OLD, Files.readAllBytes(launchJar), "the previous jar is back on disk");
        assertTrue(Files.exists(prev), ".prev is kept for the operator");
        assertTrue(guard.isHeld());
        assertTrue(guard.holdReason().contains("v26.2-r48"));
        assertEquals(Phase.ROLLED_BACK, guard.state().orElseThrow().phase());

        assertEquals(BootDecision.HELD, guard.onBoot());          // boot D: still held, no second restore
        assertTrue(guard.isHeld());
    }

    @Test
    void readyIsIgnoredOutsideTheBootingPhase() throws IOException {
        guard.onReady();                                          // no state at all
        assertTrue(guard.state().isEmpty());

        applyNewBuild();
        guard.onReady();                                          // APPLIED, not BOOTING: the restart has not happened
        assertEquals(Phase.APPLIED, guard.state().orElseThrow().phase());
    }

    @Test
    void missingPrevJarIsAHeldFailureNotASilentPass() throws IOException {
        Path prev = applyNewBuild();
        assertEquals(BootDecision.AWAIT_READY, guard.onBoot());
        Files.delete(prev);

        assertEquals(BootDecision.RESTORE_FAILED, guard.onBoot());
        assertArrayEquals(NEW, Files.readAllBytes(launchJar), "nothing to restore from; the jar is left alone");
        assertTrue(guard.isHeld());
        assertTrue(guard.holdReason().contains("could NOT be restored"));
    }

    @Test
    void clearHoldIsTheOperatorsWayOut() throws IOException {
        applyNewBuild();
        guard.onBoot();
        assertEquals(BootDecision.RESTORED, guard.onBoot());
        assertTrue(guard.isHeld());

        assertTrue(guard.clearHold());
        assertFalse(guard.isHeld());
        assertEquals(BootDecision.NONE, guard.onBoot());
        assertFalse(guard.clearHold(), "second clear finds nothing");
    }

    @Test
    void unreadableStateHoldsRatherThanProceeds() throws IOException {
        Files.createDirectories(autoUpdate);
        Files.writeString(autoUpdate.resolve(UpdateRollbackGuard.STATE_FILE), "phase=WHATEVER\n");
        assertTrue(guard.isHeld());
        assertEquals(BootDecision.HELD, guard.onBoot());
    }
}
