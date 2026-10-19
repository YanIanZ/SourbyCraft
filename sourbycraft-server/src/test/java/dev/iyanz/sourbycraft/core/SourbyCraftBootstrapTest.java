package dev.iyanz.sourbycraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.brand.AuroraBoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code SourbyCraftBootstrap}'s stage accounting: the boot bar's denominator is a hand-kept
 * constant, and the COMPLETE/DEGRADED/FAILED verdict is derived from it.
 */
public class SourbyCraftBootstrapTest {

    private static final Pattern STAGE_CALL = Pattern.compile("^\\s*stage\\(\"", Pattern.MULTILINE);
    private static final Pattern TOTAL = Pattern.compile("static final int TOTAL_STAGES\\s*=\\s*(\\d+)\\s*;");

    private static String source() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            final String tail = "src/main/java/dev/iyanz/sourbycraft/core/SourbyCraftBootstrap.java";
            for (final Path candidate : new Path[] {
                dir.resolve("sourbycraft-server").resolve(tail), dir.resolve(tail)}) {
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate);
                }
            }
        }
        throw new IllegalStateException("could not locate SourbyCraftBootstrap.java");
    }

    @Test
    void totalStagesEqualsTheNumberOfStageCalls() throws IOException {
        final String source = source();
        final Matcher total = TOTAL.matcher(source);
        assertTrue(total.find(), "TOTAL_STAGES declaration not found");
        int calls = 0;
        final Matcher matcher = STAGE_CALL.matcher(source);
        while (matcher.find()) {
            calls++;
        }
        assertTrue(calls > 0, "no stage(\"...\") calls found; the pattern is stale");
        assertEquals(Integer.parseInt(total.group(1)), calls,
            "TOTAL_STAGES is the boot bar's denominator and the verdict's basis: update it with the stage() calls");
    }

    @Test
    void verdictIsRunningDegradedOrFailed() {
        assertEquals(AuroraRuntime.State.RUNNING, SourbyCraftBootstrap.verdict(0, 14));
        assertEquals(AuroraRuntime.State.DEGRADED, SourbyCraftBootstrap.verdict(1, 14));
        assertEquals(AuroraRuntime.State.DEGRADED, SourbyCraftBootstrap.verdict(13, 14));
        assertEquals(AuroraRuntime.State.FAILED, SourbyCraftBootstrap.verdict(14, 14));
    }

    @Test
    void aThrowingStageIsCountedRecordedAndStepPast() throws Exception {
        try (AutoCloseable reset = SourbyCraftBootstrap.resetStagesForTesting()) {
            final boolean[] ranAfter = {false};
            SourbyCraftBootstrap.stage("ok first", () -> {});
            SourbyCraftBootstrap.stage("explodes", () -> {
                throw new IllegalStateException("boom");
            });
            SourbyCraftBootstrap.stage("still runs", () -> ranAfter[0] = true);

            assertTrue(ranAfter[0], "a failing stage must not skip the stages after it");
            assertEquals(1, SourbyCraftBootstrap.failureCountForTesting());
            assertEquals(AuroraRuntime.State.DEGRADED,
                SourbyCraftBootstrap.verdict(SourbyCraftBootstrap.failureCountForTesting(), 14));

            final List<AuroraBoot.Stage> stages = SourbyCraftBootstrap.publishBootStagesForTesting();
            assertEquals(stages, SourbyCraftBootstrap.bootStages());
            assertEquals(List.of("ok first", "explodes", "still runs"),
                stages.stream().map(AuroraBoot.Stage::name).toList());
            assertEquals(List.of(true, false, true),
                stages.stream().map(AuroraBoot.Stage::successful).toList());
        }
        assertTrue(SourbyCraftBootstrap.bootStages().isEmpty(), "the reset closer restores an empty state");
    }

    @Test
    void aCleanRunRecordsNoFailure() throws Exception {
        try (AutoCloseable reset = SourbyCraftBootstrap.resetStagesForTesting()) {
            SourbyCraftBootstrap.stage("a", () -> {});
            SourbyCraftBootstrap.stage("b", () -> {});
            assertEquals(0, SourbyCraftBootstrap.failureCountForTesting());
            assertTrue(SourbyCraftBootstrap.publishBootStagesForTesting().stream()
                .allMatch(AuroraBoot.Stage::successful));
            assertFalse(SourbyCraftBootstrap.bootStages().isEmpty());
        }
    }
}
