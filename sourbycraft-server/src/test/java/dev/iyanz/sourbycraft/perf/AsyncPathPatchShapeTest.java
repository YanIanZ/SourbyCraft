package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@link AsyncPathNavigationModel} honest. The async path glue lives in minecraft feature patch
 * 0006, in code that cannot run without a bootstrapped server, so the cancellation tests mirror it.
 * This reads the patch text and fails if the properties the mirror relies on stop being true: the
 * order of the checks, where the validity token is taken, and that the pending flag is released
 * through {@link AsyncPathCompletion}. It proves nothing about behaviour; it proves the mirror still
 * matches the source it mirrors.
 */
class AsyncPathPatchShapeTest {

    private static String patch() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            for (final String base : List.of("minecraft-patches/features", "sourbycraft-server/minecraft-patches/features")) {
                final Path features = dir.resolve(base);
                if (Files.isDirectory(features)) {
                    try (Stream<Path> files = Files.list(features)) {
                        final Path found = files.filter(p -> p.getFileName().toString()
                            .startsWith("0006-SourbyCraft-async-pathfinding")).findFirst().orElse(null);
                        if (found != null) {
                            return Files.readString(found, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
        return fail("feature patch 0006 (async pathfinding) not found from " + Path.of("").toAbsolutePath());
    }

    private static int at(final String text, final String needle) {
        final int index = text.indexOf(needle);
        assertTrue(index >= 0, "patch 0006 no longer contains: " + needle);
        return index;
    }

    @Test
    void theRecomputeOffloadsOnlyWithALivePathAndNoSolvePending() throws IOException {
        final String patch = patch();
        final int guard = at(patch, "AsyncPathProcessor.isEnabled()");
        assertTrue(at(patch, "this.path != null && !this.path.isDone()") > guard);
        assertTrue(at(patch, "!this.sourbyAsyncPathPending") > guard);
        assertTrue(at(patch, "this.sourbyKickAsyncRecompute(this.targetPos, this.reachRange)") > guard);
    }

    @Test
    void capacityIsCheckedBeforeTheSnapshotAndTheSubmissionIsATrySubmit() throws IOException {
        final String patch = patch();
        final int capacity = at(patch, "AsyncPathProcessor.hasCapacity()");
        final int snapshot = at(patch, "new dev.iyanz.aurora.level.SnapshotPathRegion(");
        final int submit = at(patch, ".trySubmit(() -> solver.findPath(snapshot, mob, targets");
        assertTrue(capacity < snapshot && snapshot < submit);
        assertTrue(patch.contains("if (solving == null) {"), "a refused submission falls back to the synchronous recompute");
    }

    @Test
    void theValidityTokenIsTheCurrentPathTakenBeforeTheFlagIsSet() throws IOException {
        final String patch = patch();
        final int token = at(patch, "final net.minecraft.world.level.pathfinder.Path requested = this.path;");
        final int pending = at(patch, "this.sourbyAsyncPathPending = true;");
        final int submit = at(patch, ".trySubmit(() -> solver.findPath(snapshot, mob, targets");
        assertTrue(token < pending && pending < submit);
    }

    @Test
    void resultsAreDeliveredThroughTheOwnerAndCheckedSubjectThenPathThenContent() throws IOException {
        final String patch = patch();
        final int deliver = at(patch, "AsyncPathCompletion.deliver(");
        final int handoff = at(patch, "RegionOwnerHandoff.forEntity(mob)");
        final int subject = at(patch, "AsyncPathValidity.sameSubject(owner, mob)");
        final int path = at(patch, "AsyncPathValidity.stillRequested(this.path, requested)");
        final int content = at(patch, "path != null && path.getTarget() != null");
        final int release = at(patch, "() -> this.sourbyAsyncPathPending = false");
        assertTrue(deliver < handoff && handoff < subject && subject < path && path < content && content < release,
            "owner handoff, subject check, path-token check, content check, then the release callback");
        assertTrue(at(patch, "this.targetPos = path.getTarget();") > content, "the target is taken from the applied path");
    }

    @Test
    void thePendingFlagIsClearedOnlyByTheReleaseCallbackOrARefusedSubmission() throws IOException {
        final String patch = patch();
        int clears = 0;
        for (int from = 0, i; (i = patch.indexOf("this.sourbyAsyncPathPending = false", from)) >= 0; from = i + 1) {
            clears++;
        }
        assertEquals(2, clears, "refused trySubmit and the release callback; any new clear needs a new test");
    }
}
