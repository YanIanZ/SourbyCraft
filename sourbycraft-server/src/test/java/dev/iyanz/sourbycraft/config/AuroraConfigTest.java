package dev.iyanz.sourbycraft.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.perf.AsyncPathProcessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class AuroraConfigTest {
    private static AuroraConfig.Parsed parse(Map<String, Object> values) {
        return AuroraConfig.parse(new ConfigSnapshot(values));
    }

    @Test void absentSettingsRemainDefaultOff() {
        var result = parse(Map.of());
        assertEquals(AuroraConfig.DEFAULT, result.config());
        assertTrue(result.invalidKeys().isEmpty());
        assertTrue(result.deprecatedKeys().isEmpty());
    }

    @Test void newKeyWinsEvenWhenExplicitlyFalse() {
        var result = parse(Map.of(AuroraConfig.ASYNC_PATH_KEY, false,
            AuroraConfig.LEGACY_ASYNC_PATH_KEY, true));
        assertFalse(result.config().entity().asyncPathfinding());
        assertTrue(result.deprecatedKeys().isEmpty());
    }

    @Test void legacyOnlyRemainsReadableAndReportsDeprecation() {
        var result = parse(Map.of(AuroraConfig.LEGACY_ASYNC_PATH_KEY, true));
        assertTrue(result.config().entity().asyncPathfinding());
        assertEquals(List.of(AuroraConfig.LEGACY_ASYNC_PATH_KEY), result.deprecatedKeys());
    }

    @Test void invalidModernKeyNeverEnablesLegacyTrue() {
        for (Object invalid : List.of("true", 1, List.of(true))) {
            var result = parse(Map.of(AuroraConfig.ASYNC_PATH_KEY, invalid,
                AuroraConfig.LEGACY_ASYNC_PATH_KEY, true));
            assertFalse(result.config().entity().asyncPathfinding());
            assertEquals(List.of(AuroraConfig.ASYNC_PATH_KEY), result.invalidKeys());
        }
    }

    @Test void malformedNamespacesDoNotFallThroughToLegacy() {
        for (String parent : List.of("aurora", "aurora.entity")) {
            var result = parse(Map.of(parent, "invalid", AuroraConfig.LEGACY_ASYNC_PATH_KEY, true));
            assertFalse(result.config().entity().asyncPathfinding());
            assertEquals(List.of(parent), result.invalidKeys());
        }
    }

    @Test void invalidLegacySettingRemainsOff() {
        var result = parse(Map.of(AuroraConfig.LEGACY_ASYNC_PATH_KEY, "yes"));
        assertFalse(result.config().entity().asyncPathfinding());
        assertEquals(List.of(AuroraConfig.LEGACY_ASYNC_PATH_KEY), result.invalidKeys());
    }

    @Test void typedValuesAndDiagnosticsCannotAliasParserInput() {
        var input = new HashMap<String, Object>();
        input.put(AuroraConfig.LEGACY_ASYNC_PATH_KEY, true);
        var result = parse(input);
        input.put(AuroraConfig.LEGACY_ASYNC_PATH_KEY, false);
        assertTrue(result.config().entity().asyncPathfinding());
        assertThrows(UnsupportedOperationException.class, () -> result.deprecatedKeys().clear());
    }

    @Test void liveReloadSummaryDoesNotInventRestartRequiredChanges() {
        var enabled = new AuroraConfig(new AuroraConfig.Entity(true));
        assertEquals(AuroraConfig.Lifecycle.LIVE, AuroraConfig.ASYNC_PATH.lifecycle());
        assertTrue(enabled.reloadSummary(AuroraConfig.DEFAULT).contains("1 live change(s)"));
        assertTrue(enabled.reloadSummary(enabled).contains("0 live change(s)"));
        assertTrue(enabled.reloadSummary(AuroraConfig.DEFAULT).contains("restart required: none"));
    }

    @Test void newFilesSeedAuroraWhileExistingLegacyFilesKeepFallback(@TempDir Path dir) throws Exception {
        var flag = SourbyCraftConfig.class.getDeclaredField("newFile");
        flag.setAccessible(true);
        boolean previous = flag.getBoolean(null);
        var seed = SourbyCraftConfig.class.getDeclaredMethod("seedDefaults", CommentedFileConfig.class);
        seed.setAccessible(true);
        Path existing = dir.resolve("existing.toml");
        String original = "# operator\n[perf.ai]\nasync-pathfinding=true\n";
        Files.writeString(existing, original);
        try {
            flag.setBoolean(null, false);
            try (var file = CommentedFileConfig.of(existing)) {
                file.load();
                seed.invoke(null, file);
                assertFalse(file.contains(AuroraConfig.ASYNC_PATH_KEY));
                assertTrue(AuroraConfig.parse(ConfigSnapshot.copyOf(file)).config().entity().asyncPathfinding());
                assertEquals(original, Files.readString(existing));
            }
            flag.setBoolean(null, true);
            Path created = dir.resolve("new.toml");
            // FileConfig defaults to asynchronous saves; this fixture verifies seeding,
            // so use synchronous persistence rather than racing the writer thread.
            try (var file = CommentedFileConfig.builder(created).sync().build()) {
                seed.invoke(null, file);
                assertEquals(false, file.get(AuroraConfig.ASYNC_PATH_KEY));
                assertTrue(Files.readString(created).contains("async-pathfinding"));
            }
        } finally {
            flag.setBoolean(null, previous);
        }
    }

    @Test void disablingLiveAdmissionLetsAnAdmittedSolveFinish() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        try {
            AsyncPathProcessor.setEnabled(true);
            var result = AsyncPathProcessor.submit(() -> {
                started.countDown();
                try {
                    if (!finish.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("timeout");
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
                return 42;
            });
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            AsyncPathProcessor.setEnabled(false);
            assertFalse(AsyncPathProcessor.isEnabled());
            assertFalse(result.isDone());
            finish.countDown();
            assertEquals(42, result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            finish.countDown();
            AsyncPathProcessor.shutdown();
        }
    }

    @Test void loadingAndReloadingLegacyFilePreservesBytesAndUpdatesRuntime(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("operator.toml");
        String legacy = "# preserve spacing and comments\n[perf.ai]\nasync-pathfinding = true\n";
        Files.writeString(path, legacy);
        var field = SourbyCraftConfig.class.getDeclaredField("loaded");
        field.setAccessible(true);
        Object previous = field.get(null);
        boolean previousEnabled = AsyncPathProcessor.isEnabled();
        var load = SourbyCraftConfig.class.getDeclaredMethod("loadSnapshot", CommentedFileConfig.class);
        load.setAccessible(true);
        try (var file = CommentedFileConfig.of(path)) {
            file.load();
            load.invoke(null, file);
            assertTrue(SourbyCraftConfig.aurora().entity().asyncPathfinding());
            assertTrue(AsyncPathProcessor.isEnabled());
            assertEquals(legacy, Files.readString(path));
            String modern = legacy + "\n[aurora.entity]\nasync-pathfinding = false\n";
            Files.writeString(path, modern);
            file.load();
            load.invoke(null, file);
            assertFalse(SourbyCraftConfig.aurora().entity().asyncPathfinding());
            assertFalse(AsyncPathProcessor.isEnabled());
            assertEquals(modern, Files.readString(path));
        } finally {
            AsyncPathProcessor.shutdown();
            AsyncPathProcessor.setEnabled(previousEnabled);
            field.set(null, previous);
        }
    }
}
