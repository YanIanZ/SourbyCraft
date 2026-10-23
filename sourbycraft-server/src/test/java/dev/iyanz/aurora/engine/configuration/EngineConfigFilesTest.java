package dev.iyanz.aurora.engine.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.aurora.engine.GlobalConfiguration;
import dev.iyanz.aurora.engine.WorldConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * The one-time rename of {@code config/canvas-server.yml} / {@code canvas-worlds.yml} to the
 * {@code aurora-*.yml} names (Build 49, owner decision 2026-10-22). Every case runs the real
 * {@link GlobalConfiguration} / {@link WorldConfig} key sets through the ordinary loader after the
 * migration, in a temporary directory, so the working directory's {@code config/} is never touched.
 */
class EngineConfigFilesTest {

    private static final String[] HEADER = {"New header line one", "New header line two"};
    private static final int WRAP = 90;

    @BeforeAll
    static void bootstrapMinecraft() {
        // GlobalConfiguration's constructor reads the server brand; like ConfigurationUpgradeTest.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Records what the loader reported, like the upgrade test does. */
    private static final class Recorder<C extends Part> implements Resolver<C> {
        final List<String> added = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        C loaded;
        @Override public void onDiffAdd(final String name) { this.added.add(name); }
        @Override public void onDiffRemove(final String name) { this.removed.add(name); }
        @Override public void onFinishLoad(final C instance) { this.loaded = instance; }
    }

    private static Path shippedServerFile(final Path dir, final String name) {
        final Path file = dir.resolve(name);
        ConfigurationProvider.buildSolidConfiguration(file, GlobalConfiguration::new, WRAP, new Recorder<>(),
            "Aurora / Server configuration", "", "The historical config/canvas-server.yml path is retained for existing servers.");
        return file;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(final Path file) throws IOException {
        return (Map<String, Object>) new Yaml().load(Files.readString(file));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(final Map<String, Object> root, final String key) {
        return (Map<String, Object>) root.get(key);
    }

    // ------------------------------------------------------------------------------- the matrix

    @Test
    void neitherFileMeansNothingToDoAndTheLoaderSeedsTheNewName(@TempDir final Path dir) {
        final Path current = dir.resolve(EngineConfigFiles.SERVER);
        final Path legacy = dir.resolve(EngineConfigFiles.LEGACY_SERVER);

        final EngineConfigFiles.Result result = EngineConfigFiles.migrate(current, legacy, HEADER);

        assertEquals(EngineConfigFiles.Outcome.NOTHING, result.outcome());
        assertFalse(Files.exists(current));
        assertFalse(Files.exists(legacy));
        ConfigurationProvider.buildSolidConfiguration(current, GlobalConfiguration::new, WRAP, new Recorder<>(), HEADER);
        assertTrue(Files.exists(current), "a fresh server gets only the new name");
        assertFalse(Files.exists(legacy));
    }

    @Test
    void theNewFileAloneIsLeftAlone(@TempDir final Path dir) throws IOException {
        final Path current = shippedServerFile(dir, EngineConfigFiles.SERVER);
        final String before = Files.readString(current);

        final EngineConfigFiles.Result result = EngineConfigFiles.migrate(current, dir.resolve(EngineConfigFiles.LEGACY_SERVER), HEADER);

        assertEquals(EngineConfigFiles.Outcome.NOTHING, result.outcome());
        assertEquals(before, Files.readString(current));
    }

    @Test
    void anOldFileWithOperatorValuesIsCopiedUnderTheNewNameAndRenamed(@TempDir final Path dir) throws IOException {
        final Path legacy = shippedServerFile(dir, EngineConfigFiles.LEGACY_SERVER);
        // operator edits: a LIVE key and a RESTART_REQUIRED key, plus a comment of their own
        String text = Files.readString(legacy);
        assertTrue(text.contains("guard-severity: \"LOG\""), text);
        text = text.replace("guard-severity: \"LOG\"", "# operator note: keep strict\n   guard-severity: \"THROW\"")
            .replace("autosave-frequency: \"5m\"", "autosave-frequency: \"90s\"").replace("autosave-players: true", "autosave-players: false");
        Files.writeString(legacy, text);
        final byte[] legacyBytes = Files.readAllBytes(legacy);
        final FileTime legacyTime = Files.getLastModifiedTime(legacy);
        final Path current = dir.resolve(EngineConfigFiles.SERVER);

        final EngineConfigFiles.Result result = EngineConfigFiles.migrate(current, legacy, HEADER);

        assertEquals(EngineConfigFiles.Outcome.MIGRATED, result.outcome());
        assertTrue(Files.exists(current), "published under the new name");
        assertFalse(Files.exists(legacy), "the old name is gone (renamed, not deleted)");
        final Path renamed = result.renamedLegacy();
        assertNotNull(renamed);
        assertEquals(legacy.resolveSibling(EngineConfigFiles.LEGACY_SERVER + EngineConfigFiles.MIGRATED_SUFFIX), renamed);
        assertArrayEquals(legacyBytes, Files.readAllBytes(renamed), "the old file is byte-identical under its new name");
        assertEquals(legacyTime, Files.getLastModifiedTime(renamed));
        assertFalse(Files.exists(current.resolveSibling(EngineConfigFiles.SERVER + EngineConfigFiles.MIGRATING_SUFFIX)));
        assertTrue(result.message().contains("config/canvas-server.yml -> config/aurora-server.yml"), result.message());
        assertTrue(result.message().contains("canvas-server.yml.migrated"), result.message());

        final String migrated = Files.readString(current);
        assertTrue(migrated.startsWith("# New header line one\n# New header line two\n"), migrated.substring(0, 120));
        assertFalse(migrated.contains("historical"), "the old header is dropped");
        assertTrue(migrated.contains("# operator note: keep strict"), "per-key operator comments survive");
        assertTrue(migrated.contains("guard-severity: \"THROW\""));
        assertTrue(migrated.contains("autosave-frequency: \"90s\""));

        // and the ordinary load sees the operator's values, adds nothing, removes nothing
        final Recorder<GlobalConfiguration> recorder = new Recorder<>();
        ConfigurationProvider.buildSolidConfiguration(current, GlobalConfiguration::new, WRAP, recorder, HEADER);
        assertEquals(GlobalConfiguration.RegionScheduler.GuardSeverity.THROW, recorder.loaded.regionScheduler.guardSeverity);
        assertFalse(recorder.loaded.autosave.autosavePlayers, "a second operator value is loaded too");
        assertTrue(recorder.added.isEmpty() && recorder.removed.isEmpty(), recorder.added + " " + recorder.removed);
        assertTrue(Files.readString(current).startsWith("# New header line one\n"), "the load keeps the new header");
    }

    @Test
    void everyValueOfTheRealServerKeySetSurvivesTheMigration(@TempDir final Path dir) throws IOException {
        final Path legacy = shippedServerFile(dir, EngineConfigFiles.LEGACY_SERVER);
        final Map<String, Object> before = load(legacy);
        final Path current = dir.resolve(EngineConfigFiles.SERVER);

        EngineConfigFiles.migrate(current, legacy, HEADER);
        ConfigurationProvider.buildSolidConfiguration(current, GlobalConfiguration::new, WRAP, new Recorder<>(), HEADER);

        assertEquals(before, load(current), "old -> new: identical key set and values");
    }

    @Test
    void everyValueOfTheRealWorldsKeySetSurvivesTheMigration(@TempDir final Path dir) throws IOException {
        final Path legacy = dir.resolve(EngineConfigFiles.LEGACY_WORLDS);
        ConfigurationProvider.buildSolidConfiguration(legacy, WorldConfig::new, WRAP, new Recorder<>(),
            "Aurora / World defaults", "", "The historical config/canvas-worlds.yml path is retained for existing servers.");
        final Map<String, Object> before = load(legacy);
        final Path current = dir.resolve(EngineConfigFiles.WORLDS);

        final EngineConfigFiles.Result result = EngineConfigFiles.migrate(current, legacy, HEADER);
        ConfigurationProvider.buildSolidConfiguration(current, WorldConfig::new, WRAP, new Recorder<>(), HEADER);

        assertEquals(EngineConfigFiles.Outcome.MIGRATED, result.outcome());
        assertEquals(before, load(current));
        assertFalse(Files.readString(current).contains("historical"), "the old header is dropped");
        assertTrue(Files.readString(current).startsWith("# New header line one\n"));
        assertTrue(Files.exists(dir.resolve(EngineConfigFiles.LEGACY_WORLDS + EngineConfigFiles.MIGRATED_SUFFIX)));
    }

    @Test
    void aSecondCallDoesNothingAndASecondLoadIsByteIdentical(@TempDir final Path dir) throws IOException {
        final Path legacy = shippedServerFile(dir, EngineConfigFiles.LEGACY_SERVER);
        final Path current = dir.resolve(EngineConfigFiles.SERVER);
        EngineConfigFiles.migrate(current, legacy, HEADER);
        ConfigurationProvider.buildSolidConfiguration(current, GlobalConfiguration::new, WRAP, new Recorder<>(), HEADER);
        final String settled = Files.readString(current);
        final Path renamed = dir.resolve(EngineConfigFiles.LEGACY_SERVER + EngineConfigFiles.MIGRATED_SUFFIX);
        final byte[] renamedBytes = Files.readAllBytes(renamed);

        final EngineConfigFiles.Result again = EngineConfigFiles.migrate(current, legacy, HEADER);
        ConfigurationProvider.buildSolidConfiguration(current, GlobalConfiguration::new, WRAP, new Recorder<>(), HEADER);

        assertEquals(EngineConfigFiles.Outcome.NOTHING, again.outcome());
        assertEquals(settled, Files.readString(current));
        assertArrayEquals(renamedBytes, Files.readAllBytes(renamed));
        assertFalse(Files.exists(renamed.resolveSibling(renamed.getFileName() + ".1")));
    }

    @Test
    void bothFilesPresentMeansTheNewOneWinsAndTheOldOneIsReportedStale(@TempDir final Path dir) throws IOException {
        final Path current = shippedServerFile(dir, EngineConfigFiles.SERVER);
        final Path legacy = dir.resolve(EngineConfigFiles.LEGACY_SERVER);
        Files.writeString(legacy, "region-scheduler:\n   guard-severity: \"THROW\"\n");
        final String currentBefore = Files.readString(current);
        final String legacyBefore = Files.readString(legacy);

        final EngineConfigFiles.Result result = EngineConfigFiles.migrate(current, legacy, HEADER);
        final Recorder<GlobalConfiguration> recorder = new Recorder<>();
        ConfigurationProvider.buildSolidConfiguration(current, GlobalConfiguration::new, WRAP, recorder, HEADER);

        assertEquals(EngineConfigFiles.Outcome.STALE_LEGACY, result.outcome());
        assertTrue(result.message().contains("config/aurora-server.yml is read"), result.message());
        assertTrue(result.message().contains("config/canvas-server.yml is ignored"), result.message());
        assertEquals(GlobalConfiguration.RegionScheduler.GuardSeverity.LOG, recorder.loaded.regionScheduler.guardSeverity,
            "the stale old file's THROW is not merged in");
        assertEquals(currentBefore, Files.readString(current));
        assertEquals(legacyBefore, Files.readString(legacy), "the stale file is not touched");
    }

    @Test
    void anEmptyOldFileYieldsDefaultsUnderTheNewNameAndIsStillRenamed(@TempDir final Path dir) throws IOException {
        final Path legacy = dir.resolve(EngineConfigFiles.LEGACY_SERVER);
        Files.writeString(legacy, "");
        final Path current = dir.resolve(EngineConfigFiles.SERVER);

        final EngineConfigFiles.Result result = EngineConfigFiles.migrate(current, legacy, HEADER);

        assertEquals(EngineConfigFiles.Outcome.MIGRATED, result.outcome());
        assertFalse(Files.exists(current), "nothing to copy; the loader flood-fills");
        assertTrue(Files.exists(dir.resolve(EngineConfigFiles.LEGACY_SERVER + EngineConfigFiles.MIGRATED_SUFFIX)));
        assertTrue(result.message().contains("was empty"), result.message());
        final Recorder<GlobalConfiguration> recorder = new Recorder<>();
        ConfigurationProvider.buildSolidConfiguration(current, GlobalConfiguration::new, WRAP, recorder, HEADER);
        assertEquals(GlobalConfiguration.RegionScheduler.GuardSeverity.LOG, recorder.loaded.regionScheduler.guardSeverity);
        assertTrue(Files.readString(current).startsWith("# New header line one\n"));
    }

    @Test
    void aCommentOnlyOldFileMigratesLikeAnyOther(@TempDir final Path dir) throws IOException {
        final Path legacy = dir.resolve(EngineConfigFiles.LEGACY_SERVER);
        Files.writeString(legacy, "# the operator emptied this file but kept a note\n");
        final Path current = dir.resolve(EngineConfigFiles.SERVER);

        final EngineConfigFiles.Result result = EngineConfigFiles.migrate(current, legacy, HEADER);
        final Recorder<GlobalConfiguration> recorder = new Recorder<>();
        ConfigurationProvider.buildSolidConfiguration(current, GlobalConfiguration::new, WRAP, recorder, HEADER);

        assertEquals(EngineConfigFiles.Outcome.MIGRATED, result.outcome());
        assertNotNull(recorder.loaded);
        assertFalse(recorder.added.isEmpty(), "every key is added back");
        assertTrue(Files.readString(current).startsWith("# New header line one\n"));
    }

    @Test
    void aCorruptOldFileFailsClearlyWithoutWritingOrRenamingAnything(@TempDir final Path dir) throws IOException {
        final Path legacy = dir.resolve(EngineConfigFiles.LEGACY_SERVER);
        Files.writeString(legacy, "region-scheduler:\n   guard-severity: [unterminated\n");
        final byte[] bytes = Files.readAllBytes(legacy);
        final Path current = dir.resolve(EngineConfigFiles.SERVER);

        final IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> EngineConfigFiles.migrate(current, legacy, HEADER));

        assertTrue(failure.getMessage().contains("config/canvas-server.yml could not be migrated to config/aurora-server.yml"),
            failure.getMessage());
        assertTrue(failure.getMessage().contains("nothing was written or renamed"), failure.getMessage());
        assertFalse(Files.exists(current));
        assertFalse(Files.exists(current.resolveSibling(EngineConfigFiles.SERVER + EngineConfigFiles.MIGRATING_SUFFIX)));
        assertArrayEquals(bytes, Files.readAllBytes(legacy), "the operator's file is intact for repair");
        assertFalse(Files.exists(dir.resolve(EngineConfigFiles.LEGACY_SERVER + EngineConfigFiles.MIGRATED_SUFFIX)));
    }

    @Test
    void anExistingMigratedCopyIsNeverOverwritten(@TempDir final Path dir) throws IOException {
        final Path legacy = shippedServerFile(dir, EngineConfigFiles.LEGACY_SERVER);
        final Path taken = dir.resolve(EngineConfigFiles.LEGACY_SERVER + EngineConfigFiles.MIGRATED_SUFFIX);
        Files.writeString(taken, "# an earlier migration's copy\n");
        final Path takenToo = dir.resolve(EngineConfigFiles.LEGACY_SERVER + EngineConfigFiles.MIGRATED_SUFFIX + ".1");
        Files.writeString(takenToo, "# and another\n");

        final EngineConfigFiles.Result result = EngineConfigFiles.migrate(dir.resolve(EngineConfigFiles.SERVER), legacy, HEADER);

        assertEquals("# an earlier migration's copy\n", Files.readString(taken));
        assertEquals("# and another\n", Files.readString(takenToo));
        assertEquals(dir.resolve(EngineConfigFiles.LEGACY_SERVER + EngineConfigFiles.MIGRATED_SUFFIX + ".2"), result.renamedLegacy());
        assertTrue(Files.exists(result.renamedLegacy()));
    }

    @Test
    void aStaleStagingFileFromACrashIsOverwritten(@TempDir final Path dir) throws IOException {
        final Path legacy = shippedServerFile(dir, EngineConfigFiles.LEGACY_SERVER);
        final Path current = dir.resolve(EngineConfigFiles.SERVER);
        Files.writeString(current.resolveSibling(EngineConfigFiles.SERVER + EngineConfigFiles.MIGRATING_SUFFIX), "garbage");

        EngineConfigFiles.migrate(current, legacy, HEADER);
        final Recorder<GlobalConfiguration> recorder = new Recorder<>();
        ConfigurationProvider.buildSolidConfiguration(current, GlobalConfiguration::new, WRAP, recorder, HEADER);

        assertNotNull(recorder.loaded);
        assertFalse(Files.exists(current.resolveSibling(EngineConfigFiles.SERVER + EngineConfigFiles.MIGRATING_SUFFIX)));
    }

    @Test
    void theNamesAndDisplayFormAreTheBuild49Ones() {
        assertEquals("config/aurora-server.yml", EngineConfigFiles.display(EngineConfigFiles.serverFile()));
        assertEquals("config/aurora-worlds.yml", EngineConfigFiles.display(EngineConfigFiles.worldsFile()));
        assertEquals("config/canvas-server.yml", EngineConfigFiles.display(EngineConfigFiles.legacyServerFile()));
        assertEquals("config/canvas-worlds.yml", EngineConfigFiles.display(EngineConfigFiles.legacyWorldsFile()));
        assertEquals(EngineConfigFiles.directory(), EngineConfigFiles.serverFile().getParent());
        assertNull(new EngineConfigFiles.Result(EngineConfigFiles.Outcome.NOTHING, Path.of("a"), Path.of("b"), null, "").renamedLegacy());
    }

    private static void assertArrayEquals(final byte[] expected, final byte[] actual, final String message) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, message);
    }

    private static void assertArrayEquals(final byte[] expected, final byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
