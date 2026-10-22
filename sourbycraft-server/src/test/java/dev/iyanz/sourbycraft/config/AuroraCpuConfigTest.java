package dev.iyanz.sourbycraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The processor budget, {@code aurora.cpu.cores}.
 *
 * <p>"8 means eight processors in total" is the operator-facing promise, so what is tested is
 * that a number does what it says, that a nonsense value cannot quietly cap a machine, and that
 * a reload does not claim a restart-only change took effect.</p>
 */
public class AuroraCpuConfigTest {

    private static AuroraConfig parse(final Map<String, Object> values) {
        return AuroraConfig.parse(new ConfigSnapshot(values)).config();
    }

    @Test
    void anUnsetBudgetMeansEveryProcessor() {
        final AuroraConfig config = parse(Map.of());
        assertEquals(AuroraConfig.Cpu.AUTO, config.cpu().cores());
        assertEquals(Runtime.getRuntime().availableProcessors(), config.cpu().resolved());
    }

    @Test
    void aBudgetIsTakenLiterally() {
        // The whole point of the setting: cores = 2 means two, not "two halves" or "two plus
        // overhead". It is only clamped by what the machine actually has.
        final AuroraConfig config = parse(Map.of(AuroraConfig.CPU_CORES_KEY, 2));
        assertEquals(2, config.cpu().cores());
        assertEquals(Math.min(2, Runtime.getRuntime().availableProcessors()),
            config.cpu().resolved());
    }

    @Test
    void aBudgetLargerThanTheMachineResolvesToTheMachine() {
        // Promising more parallelism than there are processors only adds context switching, and
        // a region ticks on one thread, so the surplus threads would idle regardless.
        final AuroraConfig config = parse(Map.of(AuroraConfig.CPU_CORES_KEY, 4096));
        assertEquals(4096, config.cpu().cores(), "the operator's value is kept as written");
        assertEquals(Runtime.getRuntime().availableProcessors(), config.cpu().resolved());
    }

    @Test
    void aNonsenseBudgetFallsBackToAutoAndIsReported() {
        // Falling back to auto rather than to some guessed number: a typo must not cap a machine
        // silently, and the invalid key is named so the operator can find it.
        for (final Object bad : new Object[] {"eight", -1, 2.5, true}) {
            final Map<String, Object> values = new HashMap<>();
            values.put(AuroraConfig.CPU_CORES_KEY, bad);
            final AuroraConfig.Parsed parsed = AuroraConfig.parse(new ConfigSnapshot(values));
            assertEquals(AuroraConfig.Cpu.AUTO, parsed.config().cpu().cores(), "value " + bad);
            assertTrue(parsed.invalidKeys().contains(AuroraConfig.CPU_CORES_KEY),
                "invalid value " + bad + " should be reported");
        }
    }

    @Test
    void aMalformedCpuTableDiscardsTheNamespaceRatherThanTrustingIt() {
        final AuroraConfig.Parsed parsed =
            AuroraConfig.parse(new ConfigSnapshot(Map.of("aurora.cpu", "not-a-table")));
        assertEquals(AuroraConfig.DEFAULT, parsed.config());
        assertTrue(parsed.invalidKeys().contains("aurora.cpu"));
    }

    @Test
    void aChangedBudgetIsReportedAsRestartRequiredNotApplied() {
        // The region scheduler is sized during GlobalConfiguration's load, before a reload can
        // reach it. Reporting this as a live change would tell an operator it took effect.
        final AuroraConfig before = AuroraConfig.DEFAULT;
        final AuroraConfig after = new AuroraConfig(before.entity(), before.diagnostics(),
            new AuroraConfig.Cpu(4));

        final String summary = after.reloadSummary(before);
        assertTrue(summary.contains("0 live change(s)"), summary);
        assertTrue(summary.contains(AuroraConfig.CPU_CORES_KEY), summary);
        assertTrue(summary.contains("takes effect on restart"), summary);
        assertEquals(AuroraConfig.Lifecycle.RESTART_REQUIRED, AuroraConfig.CPU_CORES.lifecycle());
    }

    @Test
    void aNegativeBudgetCannotBeConstructed() {
        assertThrows(IllegalArgumentException.class, () -> new AuroraConfig.Cpu(-1));
    }
}
