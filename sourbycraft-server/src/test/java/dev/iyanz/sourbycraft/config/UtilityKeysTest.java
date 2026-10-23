package dev.iyanz.sourbycraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The Build 49 utility-key table against the Build 48 one: every old key maps to exactly one new
 * key and keeps its default, every key is documented with lifecycle and default, and every key sits
 * in a commented section. A change to a default here is a reviewed diff of this file, not a side
 * effect of the relayout.
 */
class UtilityKeysTest {

    /** Build 48: old path -> default, as seeded by SourbyCraftConfig / AutoUpdateSettings then. */
    private static final Map<String, Object> BUILD_48 = build48();

    private static Map<String, Object> build48() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("ui.console-style", "auto");
        m.put("ui.plugins-page-size", 12);
        m.put("branding.gc-advisor.enabled", true);
        m.put("sourbycraft.max-players", 0);
        m.put("sourbycraft.maxplayers.bypass-enabled", false);
        m.put("viaversion.auto-provision", true);
        m.put("protocollib.auto-provision", true);
        m.put("misc.auto_update.enabled", true);
        m.put("misc.auto_update.repo", "YanIanZ/SourbyCraft");
        m.put("misc.auto_update.channel", "");
        m.put("misc.auto_update.apply_mode", "notify");
        m.put("misc.auto_update.check_times", List.of("06:00"));
        m.put("misc.auto_update.check_interval_minutes", 30);
        m.put("misc.auto_update.restart_mode", "auto");
        m.put("misc.auto_update.restart_delay_seconds", 60);
        m.put("misc.auto_update.allow_prerelease", false);
        m.put("misc.auto_update.via_auto_update", true);
        return m;
    }

    /** Build 49: old path -> new path. The owner-visible rename table (docs/AURORA-CONFIG.md). */
    private static final Map<String, String> RENAMES = Map.ofEntries(
        Map.entry("ui.console-style", "console.style"),
        Map.entry("ui.plugins-page-size", "console.plugins-page-size"),
        Map.entry("branding.gc-advisor.enabled", "console.gc-advisor"),
        Map.entry("sourbycraft.max-players", "players.max-players"),
        Map.entry("sourbycraft.maxplayers.bypass-enabled", "players.full-server-bypass"),
        Map.entry("viaversion.auto-provision", "plugins.viaversion.auto-provision"),
        Map.entry("protocollib.auto-provision", "plugins.protocollib.auto-provision"),
        Map.entry("misc.auto_update.enabled", "updater.enabled"),
        Map.entry("misc.auto_update.repo", "updater.repository"),
        Map.entry("misc.auto_update.channel", "updater.channel"),
        Map.entry("misc.auto_update.apply_mode", "updater.apply-mode"),
        Map.entry("misc.auto_update.check_times", "updater.check-times"),
        Map.entry("misc.auto_update.check_interval_minutes", "updater.check-interval-minutes"),
        Map.entry("misc.auto_update.restart_mode", "updater.restart-mode"),
        Map.entry("misc.auto_update.restart_delay_seconds", "updater.restart-delay-seconds"),
        Map.entry("misc.auto_update.allow_prerelease", "updater.allow-prerelease"),
        Map.entry("misc.auto_update.via_auto_update", "updater.via-auto-update"));

    @Test
    void everyBuild48KeyIsRenamedToExactlyOneBuild49KeyWithTheSameDefault() {
        assertEquals(RENAMES, UtilityKeys.legacyToCurrent(), "the rename table is the documented one");
        for (final Map.Entry<String, Object> old : BUILD_48.entrySet()) {
            final String current = RENAMES.get(old.getKey());
            assertNotNull(current, old.getKey() + " has no Build 49 name");
            final ConfigKey key = UtilityKeys.byPath(current);
            assertNotNull(key, current + " is not in UtilityKeys");
            assertEquals(old.getValue(), key.defaultValue(), old.getKey() + " -> " + current + ": default changed");
        }
        assertEquals(BUILD_48.size(), UtilityKeys.KEYS.size(), "no key was added or dropped by the relayout");
    }

    @Test
    void theDefaultsAreTheOnesTheConsumersFallBackTo() {
        // ConsoleStyle.current(), PluginsCommand, GcAdvisor, MaxPlayersConfig, MaxPlayersBypass,
        // SourbyCraftBootstrap/ViaAutoUpdate/BootstrapPluginSettings, AutoUpdateSettings' static fields.
        assertEquals("auto", UtilityKeys.CONSOLE_STYLE.defaultValue());
        assertEquals(12, UtilityKeys.PLUGINS_PAGE_SIZE.defaultValue());
        assertEquals(true, UtilityKeys.GC_ADVISOR.defaultValue());
        assertEquals(0, UtilityKeys.MAX_PLAYERS.defaultValue());
        assertEquals(false, UtilityKeys.FULL_SERVER_BYPASS.defaultValue());
        assertEquals(true, UtilityKeys.VIAVERSION_AUTO_PROVISION.defaultValue());
        assertEquals(true, UtilityKeys.PROTOCOLLIB_AUTO_PROVISION.defaultValue());
        assertEquals(dev.iyanz.sourbycraft.update.AutoUpdateSettings.enabled, UtilityKeys.UPDATER_ENABLED.defaultValue());
        assertEquals("YanIanZ/SourbyCraft", UtilityKeys.UPDATER_REPOSITORY.defaultValue());
        assertEquals("notify", UtilityKeys.UPDATER_APPLY_MODE.defaultValue());
        assertEquals(List.of("06:00"), UtilityKeys.UPDATER_CHECK_TIMES.defaultValue());
        assertEquals(30, UtilityKeys.UPDATER_CHECK_INTERVAL_MINUTES.defaultValue());
        assertEquals("auto", UtilityKeys.UPDATER_RESTART_MODE.defaultValue());
        assertEquals(60, UtilityKeys.UPDATER_RESTART_DELAY_SECONDS.defaultValue());
        assertEquals(false, UtilityKeys.UPDATER_ALLOW_PRERELEASE.defaultValue());
        assertEquals(true, UtilityKeys.UPDATER_VIA_AUTO_UPDATE.defaultValue());
        assertEquals(dev.iyanz.sourbycraft.maxplayers.MaxPlayersConfig.KEY, UtilityKeys.MAX_PLAYERS.path());
        assertEquals(dev.iyanz.sourbycraft.maxplayers.MaxPlayersBypass.ENABLED_KEY, UtilityKeys.FULL_SERVER_BYPASS.path());
    }

    @Test
    void everyKeyIsDocumentedWithLifecycleAndDefaultAndSitsInACommentedSection() {
        final Set<String> sections = new HashSet<>();
        for (final UtilityKeys.Section section : UtilityKeys.SECTIONS) sections.add(section.path());
        final Set<String> paths = new HashSet<>();
        final Set<String> legacy = new HashSet<>();
        for (final ConfigKey key : UtilityKeys.KEYS) {
            assertTrue(paths.add(key.path()), "duplicate path " + key.path());
            for (final String old : key.legacyPaths()) assertTrue(legacy.add(old), "duplicate legacy path " + old);
            final String doc = key.documentation();
            assertTrue(doc.startsWith(key.lifecycle() + ". Default "), key.path() + ": " + doc);
            assertTrue(doc.length() > 40, key.path() + " needs an explanation, not just a lifecycle");
            final String table = key.table();
            final String top = table.contains(".") ? table.substring(0, table.indexOf('.')) : table;
            assertTrue(sections.contains(table) || sections.contains(top), key.path() + " is in an uncommented table " + table);
            assertFalse(key.path().contains("_"), key.path() + ": kebab-case only");
            assertEquals(key.path().toLowerCase(java.util.Locale.ROOT), key.path());
        }
        assertEquals(List.of("console", "players", "messages", "plugins", "updater"),
            UtilityKeys.SECTIONS.stream().map(UtilityKeys.Section::path).toList(), "file order");
        assertTrue(UtilityKeys.SECTIONS.get(0).comment().startsWith(UtilityKeys.HEADER), "the header is the first table's comment");
        assertTrue(UtilityKeys.HEADER.contains("docs/AURORA-CONFIG.md"));
    }

    @Test
    void theRetiredKeysAreTheMemoryTuningOnesAndNothingReadsThem() {
        assertEquals(List.of("perf.smart-swap", "swap.auto-create"), UtilityKeys.RETIRED_PREFIXES);
        for (final String prefix : UtilityKeys.RETIRED_PREFIXES) {
            for (final ConfigKey key : UtilityKeys.KEYS) {
                assertFalse(key.path().startsWith(prefix), prefix + " is retired and also a key");
                assertFalse(key.legacyPaths().contains(prefix + ".enabled"), prefix + " is retired and also a legacy name");
            }
        }
    }

    @Test
    void renderingOfDefaultsInComments() {
        assertEquals("RESTART_REQUIRED. Default \"auto\". x", ConfigKey.of("a.b", "auto", AuroraConfig.Lifecycle.RESTART_REQUIRED, "x").documentation());
        assertEquals("LIVE. Default [\"06:00\"]. x", ConfigKey.of("a.b", List.of("06:00"), AuroraConfig.Lifecycle.LIVE, "x").documentation());
        assertEquals("LIVE. Default []. x", ConfigKey.of("a.b", List.of(), AuroraConfig.Lifecycle.LIVE, "x").documentation());
        assertEquals("LIVE. Default 12. x", ConfigKey.of("a.b", 12, AuroraConfig.Lifecycle.LIVE, "x").documentation());
        assertEquals("b", ConfigKey.of("a.b", 1, AuroraConfig.Lifecycle.LIVE, "x").name());
        assertEquals("a", ConfigKey.of("a.b", 1, AuroraConfig.Lifecycle.LIVE, "x").table());
        assertEquals("", ConfigKey.of("b", 1, AuroraConfig.Lifecycle.LIVE, "x").table());
    }
}
