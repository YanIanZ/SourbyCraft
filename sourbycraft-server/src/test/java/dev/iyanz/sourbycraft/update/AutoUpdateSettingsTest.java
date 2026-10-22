package dev.iyanz.sourbycraft.update;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.iyanz.sourbycraft.SourbyCraftConfigTesting;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import dev.iyanz.sourbycraft.update.SourbyUpdater.ApplyMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutoUpdateSettings}: what a fresh install gets (apply-mode=notify), how the live fields
 * are read back, and how bad values are handled. The settings are static, so every test saves and
 * restores them.
 */
class AutoUpdateSettingsTest {

    private static final String P = "updater.";

    @TempDir Path dir;

    private boolean enabled, allowPrerelease, via;
    private String repo, channel, applyMode, restartMode;
    private List<String> checkTimes;
    private int interval, delay;

    @BeforeEach
    void save() {
        enabled = AutoUpdateSettings.enabled;
        repo = AutoUpdateSettings.repo;
        channel = AutoUpdateSettings.channel;
        applyMode = AutoUpdateSettings.applyMode;
        checkTimes = AutoUpdateSettings.checkTimes;
        interval = AutoUpdateSettings.checkIntervalMinutes;
        restartMode = AutoUpdateSettings.restartMode;
        delay = AutoUpdateSettings.restartDelaySeconds;
        allowPrerelease = AutoUpdateSettings.allowPrerelease;
        via = AutoUpdateSettings.viaAutoUpdate;
        resetToDocumentedDefaults();
    }

    @AfterEach
    void restore() {
        AutoUpdateSettings.enabled = enabled;
        AutoUpdateSettings.repo = repo;
        AutoUpdateSettings.channel = channel;
        AutoUpdateSettings.applyMode = applyMode;
        AutoUpdateSettings.checkTimes = checkTimes;
        AutoUpdateSettings.checkIntervalMinutes = interval;
        AutoUpdateSettings.restartMode = restartMode;
        AutoUpdateSettings.restartDelaySeconds = delay;
        AutoUpdateSettings.allowPrerelease = allowPrerelease;
        AutoUpdateSettings.viaAutoUpdate = via;
    }

    private static void resetToDocumentedDefaults() {
        AutoUpdateSettings.enabled = true;
        AutoUpdateSettings.repo = "YanIanZ/SourbyCraft";
        AutoUpdateSettings.channel = "";
        AutoUpdateSettings.applyMode = "notify";
        AutoUpdateSettings.checkTimes = List.of("06:00");
        AutoUpdateSettings.checkIntervalMinutes = 30;
        AutoUpdateSettings.restartMode = "auto";
        AutoUpdateSettings.restartDelaySeconds = 60;
        AutoUpdateSettings.allowPrerelease = false;
        AutoUpdateSettings.viaAutoUpdate = true;
    }

    private void withValues(Map<String, Object> values) throws Exception {
        SourbyCraftConfigTesting.withConfig(values, AutoUpdateSettings::loadFromToml);
    }

    private CommentedFileConfig emptyFile() {
        CommentedFileConfig f = CommentedFileConfig.builder(dir.resolve("seed.toml")).build();
        f.load();
        return f;
    }

    // ------------------------------------------------------------------ seeding

    @Test
    void seedingAnEmptyFileWritesTheDocumentedDefaultsWithNotifyAsTheApplyMode() {
        try (CommentedFileConfig f = emptyFile()) {
            boolean[] changed = {false};
            AutoUpdateSettings.seedDefaults(f, changed);
            assertTrue(changed[0]);
            assertEquals("notify", f.get(P + "apply-mode"));
            assertEquals(true, f.get(P + "enabled"));
            assertEquals("YanIanZ/SourbyCraft", f.get(P + "repository"));
            assertEquals("", f.get(P + "channel"));
            assertEquals(List.of("06:00"), f.get(P + "check-times"));
            assertEquals(30, (int) f.get(P + "check-interval-minutes"));
            assertEquals("auto", f.get(P + "restart-mode"));
            assertEquals(60, (int) f.get(P + "restart-delay-seconds"));
            assertEquals(false, f.get(P + "allow-prerelease"));
            assertEquals(true, f.get(P + "via-auto-update"));
            assertNotNull(f.getComment("updater"));
            assertNotNull(f.getComment(P + "apply-mode"));
        }
    }

    @Test
    void seedingNeverOverwritesAnOperatorValueAndIsIdempotent() {
        try (CommentedFileConfig f = emptyFile()) {
            f.set(P + "apply-mode", "auto");
            f.set(P + "restart-delay-seconds", 120);
            boolean[] first = {false};
            AutoUpdateSettings.seedDefaults(f, first);
            assertEquals("auto", f.get(P + "apply-mode"), "an existing 'auto' must survive seeding");
            assertEquals(120, (int) f.get(P + "restart-delay-seconds"));
            assertTrue(first[0], "the missing keys were added");

            boolean[] second = {false};
            AutoUpdateSettings.seedDefaults(f, second);
            assertFalse(second[0], "a second seed pass changes nothing");
        }
    }

    @Test
    void seededDefaultsReadBackAsTheLiveDefaults() throws Exception {
        Map<String, Object> flat;
        try (CommentedFileConfig f = emptyFile()) {
            AutoUpdateSettings.seedDefaults(f, new boolean[] {false});
            flat = ConfigSnapshot.copyOf(f).values();
        }
        // Start from deliberately non-default fields so the test proves loadFromToml replaced them.
        AutoUpdateSettings.enabled = false;
        AutoUpdateSettings.repo = "x/y";
        AutoUpdateSettings.channel = "DEV";
        AutoUpdateSettings.applyMode = "auto";
        AutoUpdateSettings.checkTimes = List.of("01:00");
        AutoUpdateSettings.checkIntervalMinutes = 1;
        AutoUpdateSettings.restartMode = "stop";
        AutoUpdateSettings.restartDelaySeconds = 999;
        AutoUpdateSettings.allowPrerelease = true;
        AutoUpdateSettings.viaAutoUpdate = false;

        withValues(flat);

        assertTrue(AutoUpdateSettings.enabled);
        assertEquals("YanIanZ/SourbyCraft", AutoUpdateSettings.repo);
        assertEquals("", AutoUpdateSettings.channel);
        assertEquals("notify", AutoUpdateSettings.applyMode);
        assertEquals(List.of("06:00"), AutoUpdateSettings.checkTimes);
        assertEquals(30, AutoUpdateSettings.checkIntervalMinutes);
        assertEquals("auto", AutoUpdateSettings.restartMode);
        assertEquals(60, AutoUpdateSettings.restartDelaySeconds);
        assertFalse(AutoUpdateSettings.allowPrerelease);
        assertTrue(AutoUpdateSettings.viaAutoUpdate);
    }

    @Test
    void theDefaultApplyModeResolvesToNotifyNotAuto() {
        assertEquals(ApplyMode.NOTIFY, ApplyMode.parse("notify"));
        assertEquals(ApplyMode.NOTIFY, ApplyMode.parse(AutoUpdateSettings.applyMode));
    }

    // ------------------------------------------------------------------ loading valid values

    @Test
    void operatorValuesAreReadBack() throws Exception {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put(P + "enabled", false);
        v.put(P + "repository", "  me/fork  ");
        v.put(P + "channel", "DEV");
        v.put(P + "apply-mode", "  STAGE ");
        v.put(P + "check-times", List.of("07:30", "19:00"));
        v.put(P + "check-interval-minutes", 15);
        v.put(P + "restart-mode", "exit");
        v.put(P + "restart-delay-seconds", 90);
        v.put(P + "allow-prerelease", true);
        v.put(P + "via-auto-update", false);
        withValues(v);

        assertFalse(AutoUpdateSettings.enabled);
        assertEquals("me/fork", AutoUpdateSettings.repo, "trimmed");
        assertEquals("DEV", AutoUpdateSettings.channel);
        assertEquals("STAGE", AutoUpdateSettings.applyMode, "trimmed; ApplyMode.parse is case-insensitive");
        assertEquals(ApplyMode.STAGE, ApplyMode.parse(AutoUpdateSettings.applyMode));
        assertEquals(List.of("07:30", "19:00"), AutoUpdateSettings.checkTimes);
        assertEquals(15, AutoUpdateSettings.checkIntervalMinutes);
        assertEquals("exit", AutoUpdateSettings.restartMode);
        assertEquals(90, AutoUpdateSettings.restartDelaySeconds);
        assertTrue(AutoUpdateSettings.allowPrerelease);
        assertFalse(AutoUpdateSettings.viaAutoUpdate);
    }

    @Test
    void anEmptySnapshotYieldsTheDocumentedDefaults() throws Exception {
        withValues(Map.of());
        assertTrue(AutoUpdateSettings.enabled);
        assertEquals("notify", AutoUpdateSettings.applyMode);
        assertEquals(List.of("06:00"), AutoUpdateSettings.checkTimes);
        assertEquals(30, AutoUpdateSettings.checkIntervalMinutes);
        assertEquals(60, AutoUpdateSettings.restartDelaySeconds);
    }

    @Test
    void aKeyRemovedBeforeAReloadFallsBackToTheDefaultNotTheEarlierLiveValue() throws Exception {
        AutoUpdateSettings.enabled = false;
        AutoUpdateSettings.applyMode = "auto";
        AutoUpdateSettings.allowPrerelease = true;
        AutoUpdateSettings.channel = "DEV";
        AutoUpdateSettings.checkTimes = List.of("01:00");
        AutoUpdateSettings.restartDelaySeconds = 300;
        withValues(Map.of());

        assertEquals("notify", AutoUpdateSettings.applyMode, "a deleted apply_mode must not keep an earlier auto");
        assertFalse(AutoUpdateSettings.allowPrerelease);
        assertTrue(AutoUpdateSettings.enabled);
        assertEquals("", AutoUpdateSettings.channel);
        assertEquals(List.of("06:00"), AutoUpdateSettings.checkTimes);
        assertEquals(60, AutoUpdateSettings.restartDelaySeconds);
    }

    // ------------------------------------------------------------------ bad values

    @Test
    void blankStringsFallBackToTheSafeDefaults() throws Exception {
        AutoUpdateSettings.applyMode = "auto";
        AutoUpdateSettings.repo = "x/y";
        AutoUpdateSettings.restartMode = "stop";
        Map<String, Object> v = new LinkedHashMap<>();
        v.put(P + "apply-mode", "   ");
        v.put(P + "repository", "");
        v.put(P + "restart-mode", " ");
        withValues(v);

        assertEquals("notify", AutoUpdateSettings.applyMode, "a blank apply_mode is notify, never auto");
        assertEquals("YanIanZ/SourbyCraft", AutoUpdateSettings.repo);
        assertEquals("auto", AutoUpdateSettings.restartMode);
    }

    @Test
    void numericLimitsAreClamped() throws Exception {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put(P + "restart-delay-seconds", 1);
        v.put(P + "check-interval-minutes", -5);
        withValues(v);
        assertEquals(5, AutoUpdateSettings.restartDelaySeconds, "minimum warning window is 5 s");
        assertEquals(0, AutoUpdateSettings.checkIntervalMinutes, "negative interval means only daily checks");

        v.put(P + "restart-delay-seconds", -100);
        withValues(v);
        assertEquals(5, AutoUpdateSettings.restartDelaySeconds);
    }

    @Test
    void anyNumberTypeIsAcceptedForIntegerKeys() throws Exception {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put(P + "check-interval-minutes", 45L);
        v.put(P + "restart-delay-seconds", 12.9);
        withValues(v);
        assertEquals(45, AutoUpdateSettings.checkIntervalMinutes);
        assertEquals(12, AutoUpdateSettings.restartDelaySeconds, "a TOML float is truncated, not rejected");
    }

    @Test
    void wrongTypedValuesAreIgnoredAndTheDocumentedDefaultApplies() throws Exception {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put(P + "enabled", "yes");
        v.put(P + "apply-mode", 5);
        v.put(P + "repository", List.of("a"));
        v.put(P + "check-interval-minutes", "often");
        v.put(P + "restart-delay-seconds", "soon");
        v.put(P + "allow-prerelease", "true");
        v.put(P + "via-auto-update", 1);
        v.put(P + "check-times", "06:00");
        withValues(v);

        assertTrue(AutoUpdateSettings.enabled);
        assertEquals("notify", AutoUpdateSettings.applyMode);
        assertEquals("YanIanZ/SourbyCraft", AutoUpdateSettings.repo);
        assertEquals(30, AutoUpdateSettings.checkIntervalMinutes);
        assertEquals(60, AutoUpdateSettings.restartDelaySeconds);
        assertFalse(AutoUpdateSettings.allowPrerelease, "the string \"true\" is not a boolean");
        assertTrue(AutoUpdateSettings.viaAutoUpdate);
        assertEquals(List.of("06:00"), AutoUpdateSettings.checkTimes, "a scalar is not a list");
    }

    @Test
    void checkTimesDropsNonStringEntriesAndKeepsTheRestAndAnEmptyListIsTheDefault() throws Exception {
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put(P + "check-times", new ArrayList<Object>(List.of("07:30", 5, "19:00")));
        withValues(mixed);
        assertEquals(List.of("07:30", "19:00"), AutoUpdateSettings.checkTimes);

        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put(P + "check-times", new ArrayList<String>());
        withValues(empty);
        assertEquals(List.of("06:00"), AutoUpdateSettings.checkTimes,
            "an empty list is the default schedule, not a disabled daily check");
    }

    // ------------------------------------------------------------------ ApplyMode.parse

    @Test
    void applyModeParsesTheDocumentedNamesCaseInsensitively() {
        assertEquals(ApplyMode.NOTIFY, ApplyMode.parse("notify"));
        assertEquals(ApplyMode.NOTIFY, ApplyMode.parse("NOTIFY"));
        assertEquals(ApplyMode.STAGE, ApplyMode.parse("stage"));
        assertEquals(ApplyMode.STAGE, ApplyMode.parse(" Stage "));
        assertEquals(ApplyMode.AUTO, ApplyMode.parse("auto"));
        assertEquals(ApplyMode.AUTO, ApplyMode.parse("AUTO"));
        assertEquals(ApplyMode.AUTO, ApplyMode.parse("restart"));
        assertEquals(ApplyMode.OFF, ApplyMode.parse("off"));
        assertEquals(ApplyMode.OFF, ApplyMode.parse("disabled"));
        assertEquals(ApplyMode.OFF, ApplyMode.parse("false"));
        assertEquals(ApplyMode.OFF, ApplyMode.parse("OFF"));
    }

    @Test
    void anUnknownOrMissingApplyModeIsTheSafeNotifyNeverAuto() {
        assertEquals(ApplyMode.NOTIFY, ApplyMode.parse(null));
        assertEquals(ApplyMode.NOTIFY, ApplyMode.parse(""));
        assertEquals(ApplyMode.NOTIFY, ApplyMode.parse("   "));
        assertEquals(ApplyMode.NOTIFY, ApplyMode.parse("yolo"));
        assertEquals(ApplyMode.NOTIFY, ApplyMode.parse("autoo"));
        assertEquals(ApplyMode.NOTIFY, ApplyMode.parse("true"));
        assertEquals(ApplyMode.NOTIFY, ApplyMode.parse("1"));
    }
}
