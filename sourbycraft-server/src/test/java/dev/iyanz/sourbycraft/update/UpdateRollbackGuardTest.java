package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.update.UpdateRollbackGuard.BootDecision;
import dev.iyanz.sourbycraft.update.UpdateRollbackGuard.Phase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;

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

    private Properties properties() throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(guard.stateFile())) { p.load(in); }
        return p;
    }

    private void save(Properties p) throws IOException {
        try (var out = Files.newOutputStream(guard.stateFile())) { p.store(out, "test"); }
    }

    @Test
    void applyRecordsLowercaseHashAndPreservesItThroughBootingAndOk() throws Exception {
        applyNewBuild();
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(OLD));
        assertEquals(expected, properties().getProperty("prev_sha256"));
        guard.onBoot();
        assertEquals(expected, properties().getProperty("prev_sha256"));
        guard.onReady();
        assertEquals(expected, properties().getProperty("prev_sha256"));
    }

    @Test
    void matchingUppercaseHashRestoresAndIsWrittenLowercase() throws Exception {
        Path prev = applyNewBuild();
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(OLD));
        Properties p = properties();
        p.setProperty("prev_sha256", expected.toUpperCase(java.util.Locale.ROOT));
        save(p);
        guard.onBoot();
        assertEquals(expected, properties().getProperty("prev_sha256"));
        assertEquals(BootDecision.RESTORED, guard.onBoot());
        assertArrayEquals(OLD, Files.readAllBytes(launchJar));
        assertArrayEquals(OLD, Files.readAllBytes(prev));
        assertEquals(expected, properties().getProperty("prev_sha256"));
    }

    @Test
    void mismatchingHashRefusesRestoreWithoutTouchingAnyJarFiles() throws IOException {
        Path prev = applyNewBuild();
        guard.onBoot();
        String recorded = properties().getProperty("prev_sha256");
        byte[] tampered = "tampered previous build".getBytes();
        Files.write(prev, tampered);
        Path restore = Files.write(root.resolve("SourbyCraft.jar.restore"), OLD);
        assertEquals(BootDecision.RESTORE_FAILED, guard.onBoot());
        assertArrayEquals(NEW, Files.readAllBytes(launchJar));
        assertArrayEquals(tampered, Files.readAllBytes(prev));
        assertArrayEquals(OLD, Files.readAllBytes(restore));
        assertTrue(guard.isHeld());
        assertTrue(guard.holdReason().contains("SHA-256 mismatch"));
        assertTrue(guard.holdReason().contains(prev.toString()));
        assertTrue(guard.holdReason().contains("expected="));
        assertTrue(guard.holdReason().contains("actual="));
        assertEquals(recorded, properties().getProperty("prev_sha256"));
    }

    @Test
    void emptyOrMalformedHashIsNotTreatedAsMissing() throws IOException {
        Path prev = applyNewBuild();
        guard.onBoot();
        for (String invalid : new String[]{"", "not-a-sha256"}) {
            Properties p = properties();
            p.setProperty("phase", "BOOTING");
            p.setProperty("prev_sha256", invalid);
            save(p);
            assertEquals(BootDecision.RESTORE_FAILED, guard.onBoot());
            assertArrayEquals(NEW, Files.readAllBytes(launchJar));
            assertArrayEquals(OLD, Files.readAllBytes(prev));
            assertFalse(Files.exists(root.resolve("SourbyCraft.jar.restore")));
        }
    }

    @Test
    void legacyStateWithoutHashStillRestores() throws IOException {
        applyNewBuild();
        Properties p = properties();
        p.remove("prev_sha256");
        save(p);
        guard.onBoot();
        assertEquals(BootDecision.RESTORED, guard.onBoot());
        assertArrayEquals(OLD, Files.readAllBytes(launchJar));
        assertFalse(properties().containsKey("prev_sha256"));
    }

    @Test
    void launcherRollbackIsHeldAndReportedWithoutAnotherRestore() throws IOException {
        Path prev = applyNewBuild();
        Properties p = properties();
        p.setProperty("phase", "ROLLED_BACK");
        p.setProperty("note", "launcher: the boot after applying v26.2-r48 did not reach the updater stage or ready; "
            + prev.getFileName() + " copied back over " + launchJar.getFileName());
        save(p);
        assertEquals(BootDecision.HELD, guard.onBoot());
        guard.onReady();
        assertTrue(guard.isHeld());
        assertEquals("update v26.2-r48 was rolled back: " + p.getProperty("note"), guard.holdReason());
        assertArrayEquals(NEW, Files.readAllBytes(launchJar), "held states never restore twice");
        assertEquals(p, properties());
    }

}
