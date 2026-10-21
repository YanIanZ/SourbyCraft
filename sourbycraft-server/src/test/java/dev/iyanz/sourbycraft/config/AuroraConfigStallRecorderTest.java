package dev.iyanz.sourbycraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The stall recorder's five keys: defaults, parsing, typo handling and the RESTART_REQUIRED report. */
class AuroraConfigStallRecorderTest {

    private static AuroraConfig.Parsed parse(final Map<String, Object> values) {
        return AuroraConfig.parse(new ConfigSnapshot(values));
    }

    @Test
    void defaultsAreOnTenTenSixTwenty() {
        final AuroraConfig.StallRecorder recorder = parse(Map.of()).config().diagnostics().stallRecorder();
        assertEquals(new AuroraConfig.StallRecorder(true, 10, 10, 6, 20), recorder);
        assertEquals(AuroraConfig.StallRecorder.DEFAULT, recorder);
        assertEquals(AuroraConfig.DEFAULT, parse(Map.of()).config());
    }

    @Test
    void everyKeyIsReadAndNumbersMayComeAsLongsOrWholeDoubles() {
        final var result = parse(Map.of(
            AuroraConfig.STALL_RECORDER_ENABLED_KEY, false,
            AuroraConfig.STALL_RECORDER_AFTER_KEY, 30L,
            AuroraConfig.STALL_RECORDER_REPEAT_KEY, 5,
            AuroraConfig.STALL_RECORDER_MAX_DUMPS_KEY, 12.0,
            AuroraConfig.STALL_RECORDER_KEEP_FILES_KEY, 100));
        assertTrue(result.invalidKeys().isEmpty());
        assertEquals(new AuroraConfig.StallRecorder(false, 30, 5, 12, 100), result.config().diagnostics().stallRecorder());
    }

    @Test
    void otherDiagnosticsKeysAreUnaffected() {
        final var result = parse(Map.of(AuroraConfig.LANE_SAMPLING_KEY, false,
            AuroraConfig.STALL_RECORDER_AFTER_KEY, 20));
        assertFalse(result.config().diagnostics().laneSampling());
        assertFalse(result.config().diagnostics().chunkGenerationMetrics());
        assertEquals(20, result.config().diagnostics().stallRecorder().afterSeconds());
    }

    @Test
    void anInvalidValueKeepsThatKeysDefaultAndIsReportedWithoutDisablingTheRecorder() {
        final var result = parse(Map.of(
            AuroraConfig.STALL_RECORDER_ENABLED_KEY, "no",
            AuroraConfig.STALL_RECORDER_AFTER_KEY, 0,
            AuroraConfig.STALL_RECORDER_REPEAT_KEY, 3601,
            AuroraConfig.STALL_RECORDER_MAX_DUMPS_KEY, 2.5,
            AuroraConfig.STALL_RECORDER_KEEP_FILES_KEY, "many"));
        assertEquals(AuroraConfig.StallRecorder.DEFAULT, result.config().diagnostics().stallRecorder());
        assertEquals(java.util.Set.of(AuroraConfig.STALL_RECORDER_ENABLED_KEY, AuroraConfig.STALL_RECORDER_AFTER_KEY,
            AuroraConfig.STALL_RECORDER_REPEAT_KEY, AuroraConfig.STALL_RECORDER_MAX_DUMPS_KEY,
            AuroraConfig.STALL_RECORDER_KEEP_FILES_KEY), new java.util.HashSet<>(result.invalidKeys()));
        assertEquals(5, result.invalidKeys().size());
    }

    @Test
    void aTypoInOneKeyDoesNotRevertAnotherDeliberateOne() {
        final var result = parse(Map.of(AuroraConfig.STALL_RECORDER_AFTER_KEY, 45,
            AuroraConfig.STALL_RECORDER_KEEP_FILES_KEY, -1));
        assertEquals(45, result.config().diagnostics().stallRecorder().afterSeconds());
        assertEquals(20, result.config().diagnostics().stallRecorder().keepFiles());
        assertEquals(List.of(AuroraConfig.STALL_RECORDER_KEEP_FILES_KEY), result.invalidKeys());
    }

    @Test
    void aMalformedNamespaceDiscardsEverythingUnderIt() {
        final var result = parse(Map.of("aurora.diagnostics.stall-recorder", "off",
            AuroraConfig.STALL_RECORDER_AFTER_KEY, 99));
        assertEquals(AuroraConfig.DEFAULT, result.config());
        assertEquals(List.of("aurora.diagnostics.stall-recorder"), result.invalidKeys());
    }

    @Test
    void theRecordRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> new AuroraConfig.StallRecorder(true, 0, 10, 6, 20));
        assertThrows(IllegalArgumentException.class, () -> new AuroraConfig.StallRecorder(true, 10, 0, 6, 20));
        assertThrows(IllegalArgumentException.class, () -> new AuroraConfig.StallRecorder(true, 10, 10, 101, 20));
        assertThrows(IllegalArgumentException.class, () -> new AuroraConfig.StallRecorder(true, 10, 10, 6, 1001));
    }

    @Test
    void lifecycleIsRestartRequiredAndAReloadSaysSoInsteadOfCountingItAsApplied() {
        assertEquals(AuroraConfig.Lifecycle.RESTART_REQUIRED, AuroraConfig.STALL_RECORDER.lifecycle());
        final AuroraConfig changed = parse(Map.of(AuroraConfig.STALL_RECORDER_AFTER_KEY, 30)).config();
        final String summary = changed.reloadSummary(AuroraConfig.DEFAULT);
        assertTrue(summary.contains("0 live change(s) applied"), summary);
        assertTrue(summary.contains("aurora.diagnostics.stall-recorder.*"), summary);
        assertEquals(0, changed.liveChangesComparedTo(AuroraConfig.DEFAULT));
        assertTrue(AuroraConfig.DEFAULT.reloadSummary(AuroraConfig.DEFAULT).contains("restart required: none"));
    }
}
