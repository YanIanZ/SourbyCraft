package dev.iyanz.sourbycraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.iyanz.sourbycraft.awf.AwfSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Build 49 relayout of the two SourbyCraft TOML files: old names resolve on read, the files are
 * rewritten once with a {@code .migrated} copy, values never change, and a file that already has the
 * new layout is left alone.
 */
class ConfigMigrationTest {

    /** A Build 48 utility file as a real server had it: every old table, an Aurora key left behind, a retired key. */
    private static final String BUILD_48_UTILITY = """
        # operator header
        [ui]
        # my note
        console-style = "plain"
        plugins-page-size = 7

        [branding.gc-advisor]
        enabled = false

        [sourbycraft]
        max-players = 40
        [sourbycraft.maxplayers]
        bypass-enabled = true

        [messages]
        join = ["<player> joined"]

        [viaversion]
        auto-provision = false
        [protocollib]
        auto-provision = false

        [misc.auto_update]
        enabled = false
        repo = "me/fork"
        apply_mode = "stage"
        check_times = ["01:00", "13:00"]

        [aurora.cpu]
        cores = 4
        [aurora.entity]
        async-pathfinding = true
        [perf.ai]
        async-pathfinding = false
        [perf.smart-swap]
        enabled = true
        [swap.auto-create]
        enabled = true
        """;

    private static final String BUILD_48_AURORA = """
        [aurora.bridge]
        mode = "safe"
        [aurora.cpu]
        cores = 2
        """;

    private static CommentedFileConfig open(final Path file) {
        final CommentedFileConfig f = CommentedFileConfig.builder(file).sync().preserveInsertionOrder().build();
        f.load();
        return f;
    }

    private static Map<String, Object> flat(final Path file) {
        try (CommentedFileConfig f = open(file)) {
            return ConfigSnapshot.copyOf(f).values();
        }
    }

    // ------------------------------------------------------------------------------- normalize

    @Test
    void oldNamesResolveToTheCurrentOnesOnRead() {
        final ConfigMigration.Normalized n = ConfigMigration.normalize(new ConfigSnapshot(Map.of(
            "ui.console-style", "plain", "misc.auto_update.apply_mode", "auto", "messages.join", List.of("x"))));
        assertEquals("plain", n.values().values().get("console.style"));
        assertEquals("auto", n.values().values().get("updater.apply-mode"));
        assertEquals(List.of("x"), n.values().values().get("messages.join"));
        assertFalse(n.values().values().containsKey("ui.console-style"), "the old name is gone from the snapshot");
        assertEquals(List.of("ui.console-style", "misc.auto_update.apply_mode"), n.deprecatedUsed());
        assertTrue(n.staleIgnored().isEmpty());
    }

    @Test
    void theCurrentNameWinsWhenBothArePresent() {
        final ConfigMigration.Normalized n = ConfigMigration.normalize(new ConfigSnapshot(Map.of(
            "ui.plugins-page-size", 3, "console.plugins-page-size", 9)));
        assertEquals(9, n.values().values().get("console.plugins-page-size"));
        assertEquals(List.of("ui.plugins-page-size"), n.staleIgnored());
        assertTrue(n.deprecatedUsed().isEmpty());
    }

    @Test
    void aCurrentLayoutSnapshotIsUntouched() {
        final ConfigSnapshot current = new ConfigSnapshot(Map.of("console.style", "auto", "updater.enabled", false));
        final ConfigMigration.Normalized n = ConfigMigration.normalize(current);
        assertEquals(current.values(), n.values().values());
        assertTrue(n.deprecatedUsed().isEmpty() && n.staleIgnored().isEmpty());
    }

    // ------------------------------------------------------------------------------- plan + apply

    @Test
    void aBuild48FileIsRewrittenToTheBuild49LayoutWithEveryValueKeptAndACopyOfTheOriginal(@TempDir final Path dir) throws IOException {
        final Path utility = dir.resolve("sourbycraft_global_config.toml");
        final Path aurora = dir.resolve("aurora.toml");
        Files.writeString(utility, BUILD_48_UTILITY);
        Files.writeString(aurora, BUILD_48_AURORA);
        final Map<String, Object> before = flat(utility);

        final ConfigMigration.Report report;
        try (CommentedFileConfig u = open(utility); CommentedFileConfig a = open(aurora)) {
            final ConfigMigration.Plan plan = ConfigMigration.plan(u, a);
            assertFalse(plan.isEmpty());
            assertEquals(11, plan.renames().size(), plan.renames().toString());
            assertEquals(java.util.Set.of("perf.smart-swap.enabled", "swap.auto-create.enabled"), new java.util.HashSet<>(plan.retired()));
            // aurora.entity.async-pathfinding moves; aurora.cpu.cores is already set in aurora.toml (it won) and the
            // legacy perf.ai copy loses to the aurora.entity one that moved
            assertEquals(List.of(new ConfigMigration.Move(AuroraConfig.ASYNC_PATH_KEY, AuroraConfig.ASYNC_PATH_KEY, true)), plan.toAurora());
            assertEquals(java.util.Set.of("aurora.cpu.cores", AuroraConfig.LEGACY_ASYNC_PATH_KEY), new java.util.HashSet<>(plan.dropped()));
            assertTrue(plan.auroraRenames().isEmpty());
            report = ConfigMigration.apply(plan, u, utility, a, aurora);
        }

        // the copies hold the original bytes
        assertNotNull(report.utilityBackup());
        assertEquals(dir.resolve("sourbycraft_global_config.toml" + ConfigMigration.MIGRATED_SUFFIX), report.utilityBackup());
        assertEquals(BUILD_48_UTILITY, Files.readString(report.utilityBackup()));
        assertNotNull(report.auroraBackup());
        assertEquals(BUILD_48_AURORA, Files.readString(report.auroraBackup()));
        assertTrue(report.summary().contains("11 key(s) renamed"), report.summary());
        assertTrue(report.summary().contains("values unchanged"), report.summary());

        // every value survived under its new name
        final Map<String, Object> after = flat(utility);
        assertEquals("plain", after.get("console.style"));
        assertEquals(7, after.get("console.plugins-page-size"));
        assertEquals(false, after.get("console.gc-advisor"));
        assertEquals(40, after.get("players.max-players"));
        assertEquals(true, after.get("players.full-server-bypass"));
        assertEquals(List.of("<player> joined"), after.get("messages.join"));
        assertEquals(false, after.get("plugins.viaversion.auto-provision"));
        assertEquals(false, after.get("plugins.protocollib.auto-provision"));
        assertEquals(false, after.get("updater.enabled"));
        assertEquals("me/fork", after.get("updater.repository"));
        assertEquals("stage", after.get("updater.apply-mode"));
        assertEquals(List.of("01:00", "13:00"), after.get("updater.check-times"));
        for (final String old : before.keySet()) {
            assertFalse(after.containsKey(old) && !old.startsWith("messages."), old + " should be gone");
        }
        final String text = Files.readString(utility);
        for (final String gone : List.of("[ui]", "[branding", "[sourbycraft", "[viaversion]", "[protocollib]", "[misc", "[aurora", "[perf", "[swap", "= {}")) {
            assertFalse(text.contains(gone), "stale table or empty table left behind: " + gone + "\n" + text);
        }
        assertTrue(text.contains("[console]") && text.contains("[players]") && text.contains("[updater]"), text);
        assertTrue(text.contains("[plugins.viaversion]") || text.contains("viaversion.auto-provision"), text);
        assertTrue(text.contains("RESTART_REQUIRED. Default \"auto\"."), "renamed keys carry the current documentation\n" + text);

        // aurora.toml: the moved key, its own values kept, the stale duplicate not merged
        final Map<String, Object> auroraAfter = flat(aurora);
        assertEquals("safe", auroraAfter.get(AuroraConfig.BRIDGE_MODE_KEY));
        assertEquals(2, auroraAfter.get(AuroraConfig.CPU_CORES_KEY), "aurora.toml's value wins over the utility copy");
        assertEquals(true, auroraAfter.get(AuroraConfig.ASYNC_PATH_KEY));
        assertFalse(auroraAfter.containsKey(AuroraConfig.LEGACY_ASYNC_PATH_KEY));
        assertTrue(Files.readString(aurora).contains("LIVE. Default false. Aurora async pathfinding"), Files.readString(aurora));

        // what the parsers see is what they saw before (minus the dropped loser)
        final ConfigMigration.Normalized normalized = ConfigMigration.normalize(new ConfigSnapshot(after));
        assertTrue(normalized.deprecatedUsed().isEmpty() && normalized.staleIgnored().isEmpty());
        final AuroraConfig config = AuroraConfig.parse(ConfigSnapshot.layered(new ConfigSnapshot(after), new ConfigSnapshot(auroraAfter))).config();
        assertEquals(2, config.cpu().cores());
        assertEquals(AuroraConfig.BridgeMode.SAFE, config.bridge().mode());
        assertTrue(config.entity().asyncPathfinding());
    }

    @Test
    void aSecondBootFindsNothingToMigrateAndTakesNoSecondCopy(@TempDir final Path dir) throws IOException {
        final Path utility = dir.resolve("sourbycraft_global_config.toml");
        final Path aurora = dir.resolve("aurora.toml");
        Files.writeString(utility, BUILD_48_UTILITY);
        Files.writeString(aurora, BUILD_48_AURORA);
        try (CommentedFileConfig u = open(utility); CommentedFileConfig a = open(aurora)) {
            ConfigMigration.apply(ConfigMigration.plan(u, a), u, utility, a, aurora);
        }
        final String settledUtility = Files.readString(utility);
        final String settledAurora = Files.readString(aurora);

        try (CommentedFileConfig u = open(utility); CommentedFileConfig a = open(aurora)) {
            final ConfigMigration.Plan again = ConfigMigration.plan(u, a);
            assertTrue(again.isEmpty(), again.toString());
            final ConfigMigration.Report report = ConfigMigration.apply(again, u, utility, a, aurora);
            assertFalse(report.rewroteSomething());
            assertNull(report.utilityBackup());
        }
        assertEquals(settledUtility, Files.readString(utility));
        assertEquals(settledAurora, Files.readString(aurora));
        assertFalse(Files.exists(dir.resolve("sourbycraft_global_config.toml.migrated.1")));
    }

    @Test
    void anExistingMigratedCopyIsNeverOverwritten(@TempDir final Path dir) throws IOException {
        final Path utility = dir.resolve("sourbycraft_global_config.toml");
        Files.writeString(utility, "[ui]\nconsole-style = \"rich\"\n");
        final Path taken = dir.resolve("sourbycraft_global_config.toml.migrated");
        Files.writeString(taken, "earlier copy");
        try (CommentedFileConfig u = open(utility)) {
            final ConfigMigration.Report report = ConfigMigration.apply(ConfigMigration.plan(u, null), u, utility, null, dir.resolve("aurora.toml"));
            assertEquals(dir.resolve("sourbycraft_global_config.toml.migrated.1"), report.utilityBackup());
        }
        assertEquals("earlier copy", Files.readString(taken));
        assertEquals("[ui]\nconsole-style = \"rich\"\n", Files.readString(dir.resolve("sourbycraft_global_config.toml.migrated.1")));
        assertEquals("rich", flat(utility).get("console.style"));
    }

    @Test
    void withoutAnAuroraFileTheAuroraKeysStayWhereTheyAreAndTheRestStillMoves(@TempDir final Path dir) throws IOException {
        final Path utility = dir.resolve("sourbycraft_global_config.toml");
        Files.writeString(utility, "[ui]\nplugins-page-size = 5\n[aurora.cpu]\ncores = 3\n");
        try (CommentedFileConfig u = open(utility)) {
            final ConfigMigration.Plan plan = ConfigMigration.plan(u, null);
            assertTrue(plan.toAurora().isEmpty() && plan.dropped().isEmpty());
            assertEquals(1, plan.renames().size());
            final ConfigMigration.Report report = ConfigMigration.apply(plan, u, utility, null, dir.resolve("aurora.toml"));
            assertNull(report.auroraBackup());
        }
        final Map<String, Object> after = flat(utility);
        assertEquals(5, after.get("console.plugins-page-size"));
        assertEquals(3, after.get("aurora.cpu.cores"), "still read through the layering");
        assertFalse(Files.exists(dir.resolve("aurora.toml")));
    }

    @Test
    void aLegacyKeyInsideAuroraTomlIsRenamedThere(@TempDir final Path dir) throws IOException {
        final Path utility = dir.resolve("sourbycraft_global_config.toml");
        final Path aurora = dir.resolve("aurora.toml");
        Files.writeString(utility, "[console]\nstyle = \"auto\"\n");
        Files.writeString(aurora, "[perf.ai]\nasync-pathfinding = true\n[aurora.cpu]\ncores = 0\n");
        try (CommentedFileConfig u = open(utility); CommentedFileConfig a = open(aurora)) {
            final ConfigMigration.Plan plan = ConfigMigration.plan(u, a);
            assertFalse(plan.touchesUtility());
            assertEquals(List.of(new ConfigMigration.Move(AuroraConfig.LEGACY_ASYNC_PATH_KEY, AuroraConfig.ASYNC_PATH_KEY, true)), plan.auroraRenames());
            final ConfigMigration.Report report = ConfigMigration.apply(plan, u, utility, a, aurora);
            assertNull(report.utilityBackup(), "the utility file was not touched, so not copied");
            assertNotNull(report.auroraBackup());
        }
        assertEquals("[console]\nstyle = \"auto\"\n", Files.readString(utility));
        final Map<String, Object> after = flat(aurora);
        assertEquals(true, after.get(AuroraConfig.ASYNC_PATH_KEY));
        assertFalse(after.containsKey(AuroraConfig.LEGACY_ASYNC_PATH_KEY));
        assertFalse(Files.readString(aurora).contains("[perf"));
    }

    @Test
    void aFileAlreadyInTheBuild49LayoutIsLeftByteIdentical(@TempDir final Path dir) throws IOException {
        final Path utility = dir.resolve("sourbycraft_global_config.toml");
        final Path aurora = dir.resolve("aurora.toml");
        final String text = "# mine\n[console]\nstyle = \"rich\"\n\n[updater]\napply-mode = \"auto\"\n";
        Files.writeString(utility, text);
        Files.writeString(aurora, "[aurora.cpu]\ncores = 0\n");
        try (CommentedFileConfig u = open(utility); CommentedFileConfig a = open(aurora)) {
            final ConfigMigration.Plan plan = ConfigMigration.plan(u, a);
            assertTrue(plan.isEmpty());
            ConfigMigration.apply(plan, u, utility, a, aurora);
        }
        assertEquals(text, Files.readString(utility));
        assertFalse(Files.exists(dir.resolve("sourbycraft_global_config.toml.migrated")));
    }

    @Test
    void whenTheCopyCannotBeWrittenNothingIsChanged(@TempDir final Path dir) throws IOException {
        // The file cannot be read for the copy (no permission), so apply fails before any key moves.
        org.junit.jupiter.api.Assumptions.assumeTrue(
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        final Path utility = dir.resolve("sourbycraft_global_config.toml");
        Files.writeString(utility, "[ui]\nconsole-style = \"rich\"\n");
        final java.util.Set<java.nio.file.attribute.PosixFilePermission> previous = Files.getPosixFilePermissions(utility);
        try (CommentedFileConfig u = open(utility)) {
            final ConfigMigration.Plan plan = ConfigMigration.plan(u, null);
            assertFalse(plan.isEmpty());
            Files.setPosixFilePermissions(utility, java.util.Set.of());
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(utility), "running as a user the permission bits bind");
            assertThrows(IOException.class, () -> ConfigMigration.apply(plan, u, utility, null, dir.resolve("aurora.toml")));
            assertEquals("rich", u.get("ui.console-style"), "no key moved");
            assertFalse(u.contains("console.style"));
        } finally {
            Files.setPosixFilePermissions(utility, previous);
        }
        assertEquals("[ui]\nconsole-style = \"rich\"\n", Files.readString(utility));
        assertFalse(Files.exists(dir.resolve("sourbycraft_global_config.toml.migrated")));
    }

    @Test
    void emptiedTablesArePrunedAndTheirCommentsDropped(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("x.toml");
        Files.writeString(file, "# misc comment\n[misc.auto_update]\nenabled = true\n[keep]\na = 1\n");
        try (CommentedFileConfig f = open(file)) {
            f.remove("misc.auto_update.enabled");
            ConfigMigration.pruneEmptyTables(f);
            assertFalse(f.contains("misc"));
            assertNull(f.getComment("misc"));
            assertEquals(1, (int) f.get("keep.a"));
            f.save();
        }
        assertFalse(Files.readString(file).contains("misc"));
    }

    @Test
    void theAuroraDefaultsSeenByEarlyReadersDoNotChange() {
        // AwfSettings.readEarly / AuroraBridge.readEarly parse the same keys the migration moves; a moved key is
        // the same key, so the typed defaults are the reference for both halves.
        assertEquals(AwfSettings.DEFAULT, AwfSettings.parse(Map.of()).settings());
        assertEquals(AuroraConfig.DEFAULT, AuroraConfig.parse(new ConfigSnapshot(Map.of())).config());
    }
}
