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
 * Keeps {@link AsyncPathNavigationModel} honest and pins the shape of the async pathfinding seam.
 * Since 2026-10-22 the offload glue is {@link AsyncPathRecompute} (Sourby source) and minecraft
 * feature patch 0006 keeps one delegating call plus the navigation-state seams; patch 0047 adds the
 * request generation. This reads the helper's source and the patch text and fails if the properties
 * the model relies on stop being true: the order of the checks, where the validity token is taken,
 * that the pending flag is released through {@link AsyncPathCompletion}, and that the upstream site is
 * a single flag-gated call. It proves nothing about behaviour; it proves the mirror still matches the
 * source it mirrors.
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

    /** The offload glue (perf/AsyncPathRecompute.java), Sourby source since 2026-10-22. */
    private static String source() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            for (final String base : List.of("src/main/java", "sourbycraft-server/src/main/java")) {
                final Path file = dir.resolve(base).resolve("dev/iyanz/sourbycraft/perf/AsyncPathRecompute.java");
                if (Files.isRegularFile(file)) {
                    return Files.readString(file, StandardCharsets.UTF_8);
                }
            }
        }
        return fail("AsyncPathRecompute.java not found from " + Path.of("").toAbsolutePath());
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
        final String source = source();
        final int token = at(source, "final Path requested = navigation.getPath();");
        final int generation = at(source, "final int requestedGeneration = navigation.sourbyNavigationGeneration();");
        final int pending = at(source, "navigation.sourbyAsyncPathPending = true;");
        assertTrue(token < generation && generation < pending, "taken right after the token, before the flag");
        final int path = at(source, "AsyncPathValidity.stillRequested(navigation.getPath(), requested)");
        final int check = at(source, "AsyncPathValidity.sameGeneration(navigation.sourbyNavigationGeneration(), requestedGeneration)");
        final int content = at(source, "if (path != null && path.getTarget() != null) {");
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
        // and an applied delivery (in sourbyApplyAsyncPath).
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
        // AsyncPathProcessor.trySubmit on the same thread. That only holds while the helper captures
        // and submits in one straight line: nothing between them may submit, capture or return.
        final String source = source();
        final int capture = at(source, "MobPathSnapshot.captureDetached(mob)");
        final int submit = at(source, ".trySubmit(() -> solver.findPath(snapshot, subject, targets");
        assertTrue(capture < submit, "capture before submit");
        final String between = source.substring(capture, submit);
        assertFalse(between.substring("captureDetached(mob)".length()).contains("captureDetached("),
            "no second capture between them");
        assertFalse(between.contains("return"), "no early exit between capture and submit");
        assertFalse(between.contains("hasCapacity"), "capacity is checked before the capture");
    }

    private static int at(final String text, final String needle) {
        final int index = text.indexOf(needle);
        assertTrue(index >= 0, "the async path seam no longer contains: " + needle);
        return index;
    }

    @Test
    void theUpstreamSiteIsOneFlagGatedCallAndTheRecomputeOffloadsOnlyWithALivePathAndNoSolvePending() throws IOException {
        final String patch = patch();
        final int guard = at(patch, "+            if (dev.iyanz.sourbycraft.perf.AsyncPathProcessor.isEnabled()");
        assertTrue(at(patch, "AsyncPathRecompute.offload(this, this.mob, this.level, this.targetPos, this.reachRange)") > guard,
            "the flag is read first, so a disabled feature costs one volatile read and no call");
        assertTrue(patch.lines().noneMatch(line -> line.startsWith("+") && line.contains("sourbyKickAsyncRecompute")),
            "the offload logic stays out of PathNavigation");
        assertTrue(patch.lines().noneMatch(line -> line.startsWith("+") && line.contains("EntityPathfindEvent")),
            "the event fires from AsyncPathRecompute");
        final String source = source();
        final int live = at(source, "if (current == null || current.isDone() || navigation.sourbyAsyncPathPending) {");
        assertTrue(at(source, "if (solveWritesMob(navigation, mob)) {") > live);
    }

    @Test
    void capacityIsCheckedBeforeTheSnapshotAndTheSubmissionIsATrySubmit() throws IOException {
        final String source = source();
        final int refusals = at(source, "if (solveWritesMob(navigation, mob)) {");
        final int capacity = at(source, "if (!AsyncPathProcessor.hasCapacity()) {");
        final int event = at(source, "new EntityPathfindEvent(");
        final int snapshot = at(source, "new SnapshotPathRegion(");
        final int submit = at(source, ".trySubmit(() -> solver.findPath(snapshot, subject, targets");
        assertTrue(refusals < capacity && capacity < event && event < snapshot && snapshot < submit);
        assertTrue(source.contains("if (solving == null) {"), "a refused submission falls back to the synchronous recompute");
    }

    @Test
    void theMaxPathLengthIsReadAfterTheEventAndTheTokenBeforeTheFlagIsSet() throws IOException {
        // Lazy, as in the old inline code: an EntityPathfindEvent listener may change the follow range
        // or the path before these are read.
        final String source = source();
        final int event = at(source, "new EntityPathfindEvent(");
        assertTrue(at(source, "navigation.sourbyMaxPathLength()") > event);
        assertTrue(at(source, "navigation.sourbyVisitedNodesMultiplier()") > event);
        final int token = at(source, "final Path requested = navigation.getPath();");
        final int pending = at(source, "navigation.sourbyAsyncPathPending = true;");
        final int submit = at(source, ".trySubmit(() -> solver.findPath(snapshot, subject, targets");
        assertTrue(event < token && token < pending && pending < submit);
    }

    @Test
    void resultsAreDeliveredThroughTheOwnerAndCheckedSubjectThenPathThenContent() throws IOException {
        final String source = source();
        final int deliver = at(source, "AsyncPathCompletion.deliver(");
        final int handoff = at(source, "RegionOwnerHandoff.forEntity(mob)");
        final int release = at(source, "() -> navigation.sourbyAsyncPathPending = false));");
        assertTrue(deliver < handoff && handoff < release, "owner handoff, then the release callback");
        final int subject = at(source, "AsyncPathValidity.sameSubject(owner, mob)");
        final int path = at(source, "AsyncPathValidity.stillRequested(navigation.getPath(), requested)");
        final int content = at(source, "path != null && path.getTarget() != null");
        final int apply = at(source, "navigation.sourbyApplyAsyncPath(path, reachRange);");
        assertTrue(subject < path && path < content && content < apply, "subject, path token, content, then apply");
        final String patch = patch();
        final int write = at(patch, "+    public final void sourbyApplyAsyncPath(final Path path, final int reachRange) {");
        assertTrue(at(patch, "+        this.targetPos = path.getTarget();") > write, "the target is taken from the applied path");
    }

    @Test
    void thePendingFlagIsClearedOnlyByTheReleaseCallbackOrARefusedSubmission() throws IOException {
        assertEquals(2, count(source(), "navigation.sourbyAsyncPathPending = false"),
            "refused trySubmit and the release callback; any new clear needs a new test");
        assertEquals(0, count(patch(), "this.sourbyAsyncPathPending = false"), "PathNavigation never clears it");
    }
}
