package dev.iyanz.sourbycraft.brand;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The engine summary has to mean stages completed, and a failure can never read as success. */
public class AuroraBootTest {

    private static final AuroraBoot.Stage OK = new AuroraBoot.Stage("metrics runtime", true, 7);
    private static final AuroraBoot.Stage SLOW = new AuroraBoot.Stage("startup index", true, 23);
    private static final AuroraBoot.Stage BAD = new AuroraBoot.Stage("configuration", false, 20);

    @Test
    void aCompleteBootIsOneLineWithTheVerdictCountsTimeAndSlowestStage() {
        assertEquals("Engine COMPLETE | 3/3 stages ok | 0 failed | 106 ms | slowest: startup index 23 ms",
            AuroraBoot.summary(List.of(OK, SLOW, new AuroraBoot.Stage("gc tracker", true, 1)), 3, 106L));
    }

    @Test
    void theSummarySaysDegradedAndNamesTheFailedStagesRatherThanReportingSuccess() {
        // The failure mode this exists to prevent: a line that says everything is fine while a
        // stage threw on the way past.
        final String degraded = AuroraBoot.summary(List.of(OK, BAD, SLOW), 3, 42L);
        assertEquals("Engine DEGRADED | 2/3 stages ok | 1 failed: configuration | 42 ms | slowest: startup index 23 ms", degraded);
        assertFalse(degraded.contains("COMPLETE"));
        assertFalse(degraded.contains("online"));
        assertFalse(degraded.contains("Done ("), "the summary must not trigger readiness probes");
    }

    @Test
    void everyStageFailingIsFailedNotDegraded() {
        final String failed = AuroraBoot.summary(List.of(BAD, new AuroraBoot.Stage("commands", false, 1)), 2, 9L);
        assertTrue(failed.startsWith("Engine FAILED | 0/2 stages ok | 2 failed: configuration, commands | 9 ms"), failed);
        assertEquals("FAILED", AuroraBoot.verdict(2, 2));
        assertEquals("DEGRADED", AuroraBoot.verdict(2, 1));
        assertEquals("COMPLETE", AuroraBoot.verdict(2, 0));
    }

    @Test
    void theSummaryMatchesWhatRunBaselineParses() {
        // scripts/run_baseline.py BOOT_SUMMARY; keep in lockstep.
        final java.util.regex.Pattern parsed = java.util.regex.Pattern.compile(
            "Engine (COMPLETE|DEGRADED|FAILED) \\| (\\d+)/(\\d+) stages ok \\| (\\d+) failed");
        assertTrue(parsed.matcher(AuroraBoot.summary(List.of(OK), 1, 1L)).find());
        assertTrue(parsed.matcher(AuroraBoot.summary(List.of(OK, BAD), 2, 1L)).find());
    }

    @Test
    void stagesBeyondTheDeclaredTotalCannotReadAsMoreThanFull() {
        // A miscounted total must not render 20/8.
        assertTrue(AuroraBoot.summary(java.util.Collections.nCopies(20, OK), 8, 5L).contains("| 8/8 stages ok |"));
        assertTrue(AuroraBoot.summary(List.of(), 0, 0L).startsWith("Engine COMPLETE | 0/0 stages ok | 0 failed | 0 ms"));
    }

    @Test
    void theDebugStageLineAndTheFailureWarningNameTheStage() {
        assertEquals("  3/15  configuration             20 ms  FAILED", AuroraBoot.stageLine(3, 15, BAD));
        assertEquals("  1/15  metrics runtime            7 ms  ok", AuroraBoot.stageLine(1, 15, OK));
        final String warning = AuroraBoot.failureLine(3, 15, BAD);
        assertTrue(warning.startsWith("Stage 3/15 \"configuration\" FAILED after 20 ms"), warning);
        assertTrue(warning.contains("continues without it"), warning);
    }

    @Test
    void nothingRenderedIsAnEmojiOrAnEscapeCode() {
        // Explicitly not decorated: the console is a log, an operator greps it, and colour is the
        // layout's job (terminal only).
        for (final String line : new String[] {
            AuroraBoot.stageLine(5, 11, OK), AuroraBoot.failureLine(5, 11, BAD),
            AuroraBoot.summary(List.of(OK, BAD), 11, 10L)}) {
            line.codePoints().forEach(cp -> assertTrue(cp < 0x1F000 && cp != 0x1B,
                "unexpected code point U+" + Integer.toHexString(cp) + " in " + line));
        }
    }
}
