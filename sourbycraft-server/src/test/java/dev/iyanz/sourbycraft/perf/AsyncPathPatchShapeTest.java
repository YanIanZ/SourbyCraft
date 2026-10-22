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

    /** Patch 0047: the navigation request generation that closes the null-token gap. */
    private static String generationPatch() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            for (final String base : List.of("minecraft-patches/features", "sourbycraft-server/minecraft-patches/features")) {
                final Path features = dir.resolve(base);
                if (Files.isDirectory(features)) {
                    try (Stream<Path> files = Files.list(features)) {
                        final Path found = files.filter(p -> p.getFileName().toString()
                            .contains("-SourbyCraft-invalidate-in-flight-async-paths")).findFirst().orElse(null);
                        if (found != null) {
                            return Files.readString(found, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
        return fail("feature patch 0047 (navigation generation) not found from " + Path.of("").toAbsolutePath());
    }

    private static int count(final String text, final String needle) {
        int count = 0;
        for (int from = 0, i; (i = text.indexOf(needle, from)) >= 0; from = i + 1) {
            count++;
        }
        return count;
    }

    @Test
    void theGenerationIsTakenWithTheTokenAndCheckedAfterThePathBeforeTheContent() throws IOException {
        final String patch = generationPatch();
        final int token = at(patch, " final net.minecraft.world.level.pathfinder.Path requested = this.path;");
        final int generation = at(patch, "+        final int requestedGeneration = this.sourbyNavigationGeneration;");
        final int pending = at(patch, " this.sourbyAsyncPathPending = true;");
        assertTrue(token < generation && generation < pending, "taken right after the token, before the flag");
        final int path = at(patch, "AsyncPathValidity.stillRequested(this.path, requested)");
        final int check = at(patch, "AsyncPathValidity.sameGeneration(this.sourbyNavigationGeneration, requestedGeneration)");
        final int content = at(patch, " if (path != null && path.getTarget() != null) {");
        assertTrue(path < check && check < content, "path token, then generation, then content");
    }

    @Test
    void everyInvalidationAdvancesTheGenerationIncludingAStopOnANullPath() throws IOException {
        final String patch = generationPatch();
        final int stop = at(patch, " public void stop() {");
        final int stopBump = patch.indexOf("+        ++this.sourbyNavigationGeneration;", stop);
        final int stopClear = patch.indexOf(" this.path = null;", stop);
        assertTrue(stopBump > stop && stopBump < stopClear, "stop() advances unconditionally, before clearing");
        // stop, moveTo(null), moveTo(new path), createPath's new request, the synchronous recompute,
        // and an applied delivery.
        assertEquals(6, count(patch, "++this.sourbyNavigationGeneration;"), "an added or removed bump needs a new test");
    }

    @Test
    void createPathAdvancesTheGenerationWhereItTakesANewTarget() throws IOException {
        // moveTo keeps the old path object for a sameAs (nodes-only) route, so the retarget is only
        // visible through this bump.
        final String patch = generationPatch();
        final int found = at(patch, " Path path = this.pathFinder.findPath(region, this.mob, targets, maxPathLength, reachRange, this.maxVisitedNodesMultiplier);");
        final int bump = patch.indexOf("+            ++this.sourbyNavigationGeneration;", found);
        final int target = patch.indexOf(" this.targetPos = path.getTarget();", found);
        assertTrue(bump > found && bump < target, "bumped before targetPos/reachRange are replaced");
    }

    /** Patch 0039: the immutable mob snapshot; it is also where the per-type counters get their type. */
    private static String snapshotPatch() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            for (final String base : List.of("minecraft-patches/features", "sourbycraft-server/minecraft-patches/features")) {
                final Path features = dir.resolve(base);
                if (Files.isDirectory(features)) {
                    try (Stream<Path> files = Files.list(features)) {
                        final Path found = files.filter(p -> p.getFileName().toString()
                            .contains("-SourbyCraft-immutable-mob-snapshot-for-async-pathfinding")).findFirst().orElse(null);
                        if (found != null) {
                            return Files.readString(found, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
        return fail("feature patch 0039 (mob snapshot) not found from " + Path.of("").toAbsolutePath());
    }

    @Test
    void theMobIsCapturedOnTheSubmittingThreadRightBeforeTheSubmitForPerTypeCounters() throws IOException {
        // AsyncPathTypeStats hands the entity type from MobPathSnapshot.captureDetached to the next
        // AsyncPathProcessor.trySubmit on the same thread. That only holds while the patch captures
        // and submits in one straight line: nothing between them may submit, capture or return.
        final String patch = snapshotPatch();
        final int capture = at(patch, "MobPathSnapshot.captureDetached(mob)");
        final int submit = at(patch, ".trySubmit(() -> solver.findPath(snapshot, subject, targets");
        assertTrue(capture < submit, "capture before submit");
        final String between = patch.substring(capture, submit);
        assertFalse(between.substring("captureDetached(mob)".length()).contains("captureDetached("),
            "no second capture between them");
        assertFalse(between.contains("return"), "no early exit between capture and submit");
        assertFalse(between.contains("hasCapacity"), "capacity is checked before the capture");
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
