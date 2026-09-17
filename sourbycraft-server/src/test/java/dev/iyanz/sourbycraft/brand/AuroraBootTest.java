package dev.iyanz.sourbycraft.brand;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The boot bar has to mean stages completed, not time elapsed. */
public class AuroraBootTest {

    private static String plain(final String rendered) {
        return rendered.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    @Test
    void thePercentageIsStagesDoneNotTimeElapsed() {
        assertTrue(plain(AuroraBoot.render(0, 11, "metrics runtime", false)).contains("0%"));
        assertTrue(plain(AuroraBoot.render(11, 11, "gc tracker", false)).contains("100%"));
        // 5 of 11 rounds to 45, not to a tidy half.
        assertTrue(plain(AuroraBoot.render(5, 11, "plugin diagnostics", false)).contains("45%"));
    }

    @Test
    void theBarFillsInProportionToProgress() {
        assertEquals(0, filled(AuroraBoot.render(0, 8, "start", false)));
        assertEquals(AuroraBoot.CELLS, filled(AuroraBoot.render(8, 8, "done", false)));
        assertEquals(AuroraBoot.CELLS / 2, filled(AuroraBoot.render(4, 8, "half", false)));
    }

    @Test
    void aStageNameIsAlwaysShownSoASlowBootSaysWhereItIs() {
        assertTrue(plain(AuroraBoot.render(3, 11, "Virtual Executor", false)).contains("virtual executor"));
    }

    @Test
    void progressBeyondTheDeclaredStagesCannotExceedFull() {
        // A miscounted total must not render 130% or overflow the bar.
        final String line = plain(AuroraBoot.render(20, 8, "overrun", false));
        assertTrue(line.contains("100%"));
        assertEquals(AuroraBoot.CELLS, filled(AuroraBoot.render(20, 8, "overrun", false)));
    }

    @Test
    void noStagesDeclaredRendersEmptyRatherThanDividingByZero() {
        final String line = plain(AuroraBoot.render(0, 0, "nothing", false));
        assertTrue(line.contains("0%"));
        assertEquals(0, filled(AuroraBoot.render(0, 0, "nothing", false)));
    }

    @Test
    void theSummarySaysDegradedRatherThanReportingSuccess() {
        // The failure mode this exists to prevent: a bar that fills to 100% and a line that says
        // everything is fine, while a stage threw on the way past.
        assertTrue(plain(AuroraBoot.summary(11, 0, 42L)).contains("11 stages online"));
        final String degraded = plain(AuroraBoot.summary(11, 2, 42L));
        assertTrue(degraded.contains("2 of 11 stages degraded"));
        assertFalse(degraded.contains("online"));
    }

    @Test
    void theSummaryReportsHowLongTheEngineTook() {
        assertTrue(plain(AuroraBoot.summary(11, 0, 137L)).contains("137ms"));
    }

    @Test
    void nothingRenderedIsAnEmoji() {
        // Explicitly not decorated: the console is a log, and an operator greps it.
        for (final String line : new String[] {
            AuroraBoot.render(5, 11, "commands", false),
            AuroraBoot.render(5, 11, "commands", true),
            AuroraBoot.summary(11, 1, 10L)}) {
            line.codePoints().forEach(cp -> assertTrue(cp < 0x1F000,
                "unexpected pictograph U+" + Integer.toHexString(cp)));
        }
    }

    private static int filled(final String rendered) {
        return (int) plain(rendered).chars().filter(c -> c == '\u2588').count();
    }
}
