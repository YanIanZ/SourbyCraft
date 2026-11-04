package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.update.UpdateApplier.SwapResult;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order of the in-place swap (audit SEC-3 follow-up): the rollback record must exist before
 * the new jar takes the launch path, so a restart never runs a new jar the guard does not track.
 */
class UpdateApplierSwapTest {

    @TempDir Path root;
    Path launchJar;
    Path stagedJar;

    private static final byte[] OLD = "old build".getBytes();
    private static final byte[] NEW = "new build".getBytes();
    /** The release digest swapInPlace re-checks the copy against. */
    private static final String NEW_SHA = sha256(NEW);

    private static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        launchJar = Files.write(root.resolve("SourbyCraft.jar"), OLD);
        stagedJar = Files.write(root.resolve("staged.jar"), NEW);
    }

    @Test
    void aSwapIsRecordedAndItsFailedFirstBootIsRestored() throws IOException {
        UpdateRollbackGuard guard = new UpdateRollbackGuard(root.resolve("auto_update"));
        assertEquals(SwapResult.SWAPPED, UpdateApplier.swapInPlace("v26.2-r49", stagedJar, NEW_SHA, launchJar, guard));
        assertArrayEquals(NEW, Files.readAllBytes(launchJar));
        assertEquals(Phase.APPLIED, guard.state().orElseThrow().phase());
        assertEquals(BootDecision.AWAIT_READY, guard.onBoot());
        assertEquals(BootDecision.RESTORED, guard.onBoot());
        assertArrayEquals(OLD, Files.readAllBytes(launchJar));
    }

    @Test
    void anUnrecordableApplyIsNotSwappedSoNoUntrackedJarIsBooted() throws IOException {
        // The state directory cannot be created (a regular file sits at its path): recordApplied fails.
        Path blocked = Files.write(root.resolve("auto_update"), new byte[0]);
        UpdateRollbackGuard guard = new UpdateRollbackGuard(blocked);
        assertEquals(SwapResult.ABORTED, UpdateApplier.swapInPlace("v26.2-r49", stagedJar, NEW_SHA, launchJar, guard));
        assertArrayEquals(OLD, Files.readAllBytes(launchJar), "the launch jar still holds the old build");
        assertArrayEquals(NEW, Files.readAllBytes(stagedJar), "the staged jar stays for a later attempt");
    }

    @Test
    void anUnrecordableApplyAfterAnEarlierConfirmedUpdateIsNotSwapped() throws IOException {
        // An earlier update was confirmed (phase OK); a lost record now must not leave that OK in charge.
        Path dir = root.resolve("auto_update");
        UpdateRollbackGuard guard = new UpdateRollbackGuard(dir);
        Path prev = guard.keepPrevious(launchJar);
        guard.recordApplied("v26.2-r48", launchJar, prev, Instant.parse("2026-10-20T00:00:00Z"));
        guard.onBoot();
        guard.onReady();
        assertEquals(Phase.OK, guard.state().orElseThrow().phase());
        // The state file can no longer be rewritten (its temp sibling is a directory): recordApplied fails.
        Files.createDirectory(dir.resolve(UpdateRollbackGuard.STATE_FILE + ".part"));
        assertEquals(SwapResult.ABORTED, UpdateApplier.swapInPlace("v26.2-r49", stagedJar, NEW_SHA, launchJar, guard));
        assertArrayEquals(OLD, Files.readAllBytes(launchJar));
        assertEquals(Phase.OK, guard.state().orElseThrow().phase());
    }

    @Test
    void aFailedRenameLeavesTheEarlierStateAsItWas() throws IOException {
        UpdateRollbackGuard guard = new UpdateRollbackGuard(root.resolve("auto_update"));
        Files.delete(stagedJar); // nothing to copy beside the launch jar: aborted before any record
        assertEquals(SwapResult.ABORTED, UpdateApplier.swapInPlace("v26.2-r49", stagedJar, NEW_SHA, launchJar, guard));
        assertArrayEquals(OLD, Files.readAllBytes(launchJar));
        assertTrue(guard.state().isEmpty(), "no APPLIED record for a swap that did not happen");
        assertEquals(BootDecision.NONE, guard.onBoot());
    }
}
