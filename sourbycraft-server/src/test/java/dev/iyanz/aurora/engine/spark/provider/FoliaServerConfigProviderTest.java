package dev.iyanz.aurora.engine.spark.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import me.lucko.spark.paper.common.platform.serverconfig.ExcludedConfigFilter;
import me.lucko.spark.paper.common.platform.serverconfig.PropertiesConfigParser;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine spark: {@link FoliaServerConfigProvider}. NOTE: this provider is NOT the one the
 * server uses: {@code FoliaSparkPlugin.createServerConfigProvider()} returns
 * {@code dev.iyanz.sourbycraft.spark.SourbyServerConfigProvider}, and this class is referenced only
 * by an unused import there. These tests pin what it would do if it were wired in again: which files
 * it exposes, which paths it hides, where the canvas and paper config groups are read from. It does
 * not scrub secret-looking keys or URL passwords the way the live provider does.
 *
 * <p>Uses the package-private seams {@code files()}, {@code hiddenPaths()} and the parsers'
 * (config folder, world list) constructors; the working directory is never read.</p>
 */
class FoliaServerConfigProviderTest {

    @TempDir Path dir;

    private static ExcludedConfigFilter filter() {
        return new ExcludedConfigFilter(FoliaServerConfigProvider.hiddenPaths());
    }

    private World world(final String name) throws Exception {
        final Path folder = Files.createDirectories(this.dir.resolve("worlds").resolve(name));
        final World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getWorldFolder()).thenReturn(new File(folder.toString()));
        return world;
    }

    @Test
    void exposesTheExpectedFilesAndGroups() {
        assertEquals(Set.of("server.properties", "bukkit.yml", "spigot.yml", "paper.yml", "canvas/", "paper/", "purpur.yml", "pufferfish.yml"),
            FoliaServerConfigProvider.files().keySet(), "extra files come only from -Dspark.serverconfigs.extra");
        assertEquals(PropertiesConfigParser.INSTANCE, FoliaServerConfigProvider.files().get("server.properties"));
        assertEquals(FoliaServerConfigProvider.CanvasSplitParser.class, FoliaServerConfigProvider.files().get("canvas/").getClass());
        assertEquals(FoliaServerConfigProvider.SplitYamlConfigParser.class, FoliaServerConfigProvider.files().get("paper/").getClass());
        assertEquals(FoliaServerConfigProvider.YamlConfigParser.class, FoliaServerConfigProvider.files().get("spigot.yml").getClass());
    }

    @Test
    void hidesExactlyTheDocumentedPaths() {
        final Collection<String> hidden = FoliaServerConfigProvider.hiddenPaths();
        assertTrue(hidden.containsAll(List.of(
            "database", "settings.bungeecord-addresses", "settings.velocity-support.secret", "proxies.velocity.secret",
            "server-ip", "motd", "resource-pack", "rcon<dot>password", "rcon<dot>ip", "level-seed",
            "world-settings.*.feature-seeds", "world-settings.*.seed-*", "feature-seeds", "seed-*")), hidden.toString());
    }

    @Test
    void everyPathItHidesIsAlsoHiddenByTheLiveProvider() throws Exception {
        final Class<?> live = Class.forName("dev.iyanz.sourbycraft.spark.SourbyServerConfigProvider");
        final Field field = live.getDeclaredField("HIDDEN_PATHS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") final Collection<String> liveHidden = (Collection<String>) field.get(null);

        final java.util.Set<String> missing = new java.util.TreeSet<>(FoliaServerConfigProvider.hiddenPaths());
        missing.removeAll(liveHidden);
        assertTrue(missing.isEmpty(), "paths this class hides that the live provider does not: " + missing);
    }

    @Test
    void serverPropertiesLoseTheirSecretsAndKeepTheirSettings() throws Exception {
        final Path file = this.dir.resolve("server.properties");
        Files.writeString(file, "rcon.password=synthetic-rcon\nrcon.ip=10.9.8.7\nserver-ip=10.1.2.3\nmotd=synthetic motd\n"
            + "level-seed=synthetic-seed\nresource-pack=synthetic-pack\nmax-players=20\nview-distance=10\n");

        final JsonObject out = PropertiesConfigParser.INSTANCE.load(file.toString(), filter()).getAsJsonObject();

        assertFalse(out.toString().contains("synthetic"), out.toString());
        assertFalse(out.toString().contains("10.9.8.7") || out.toString().contains("10.1.2.3"), out.toString());
        assertEquals(20, out.get("max-players").getAsInt());
        assertEquals(10, out.get("view-distance").getAsInt());
    }

    @Test
    void yamlFilesLoseProxySecretsAndWorldSeedsIncludingWildcardedWorlds() throws Exception {
        final Path file = this.dir.resolve("spigot.yml");
        Files.writeString(file, """
            settings:
              bungeecord-addresses:
                - 10.0.0.1
              velocity-support:
                secret: synthetic-velocity
                enabled: true
            world-settings:
              default:
                seed-village: 1234
                view-distance: 10
              nether:
                seed-fortress: 99
                feature-seeds: {a: 1}
            """);

        final JsonObject out = FoliaServerConfigProvider.YamlConfigParser.INSTANCE.load(file.toString(), filter()).getAsJsonObject();

        final String text = out.toString();
        assertFalse(text.contains("synthetic") || text.contains("10.0.0.1") || text.contains("seed-village") || text.contains("seed-fortress") || text.contains("feature-seeds"), text);
        assertTrue(out.getAsJsonObject("settings").getAsJsonObject("velocity-support").get("enabled").getAsBoolean());
        assertEquals(10, out.getAsJsonObject("world-settings").getAsJsonObject("default").get("view-distance").getAsInt());
    }

    @Test
    void aMissingYamlFileIsReportedAsAbsentNotAsAnError() throws Exception {
        assertNull(FoliaServerConfigProvider.YamlConfigParser.INSTANCE.load(this.dir.resolve("purpur.yml").toString(), filter()));
    }

    @Test
    void canvasGroupReadsTheEngineFilesAndEachWorldsPatch() throws Exception {
        final Path config = Files.createDirectories(this.dir.resolve("config"));
        Files.writeString(config.resolve("canvas-server.yml"), "region-scheduler:\n  default-tick-rate: 20.0\nmotd: synthetic-hidden\n");
        Files.writeString(config.resolve("canvas-worlds.yml"), "chunk-system:\n  fluid: vanilla\n");
        final World overworld = world("world");
        Files.writeString(overworld.getWorldFolder().toPath().resolve("canvas-patch.yml"), "chunk-system:\n  fluid: fast\n");
        final World nether = world("world_nether");   // no patch file written

        final JsonElement out = new FoliaServerConfigProvider.CanvasSplitParser(config, () -> List.of(overworld, nether))
            .load("canvas/", filter());

        final JsonObject root = out.getAsJsonObject();
        assertEquals(Set.of("canvas-server.yml", "world-defaults.yml", "world.yml"), root.keySet(),
            "canvas-worlds.yml is reported as world-defaults.yml; a world without a patch file is left out");
        assertEquals(20.0, root.getAsJsonObject("canvas-server.yml").getAsJsonObject("region-scheduler").get("default-tick-rate").getAsDouble());
        assertEquals("fast", root.getAsJsonObject("world.yml").getAsJsonObject("chunk-system").get("fluid").getAsString());
        assertFalse(root.toString().contains("synthetic-hidden"), "the hidden-path filter runs on every nested file");
    }

    @Test
    void canvasGroupWithoutAConfigFolderIsAbsent() throws Exception {
        assertNull(new FoliaServerConfigProvider.CanvasSplitParser(this.dir.resolve("no-config"), List::of).load("canvas/", filter()));
    }

    @Test
    void paperGroupReadsTheSplitFilesUnderTheGroupPrefix() throws Exception {
        final Path config = Files.createDirectories(this.dir.resolve("config"));
        Files.writeString(config.resolve("paper-global.yml"), "proxies:\n  velocity:\n    secret: synthetic-secret\n    enabled: true\n");
        Files.writeString(config.resolve("paper-world-defaults.yml"), "chunks:\n  max-auto-save-chunks-per-tick: 24\n");
        final World world = world("world");
        Files.writeString(world.getWorldFolder().toPath().resolve("paper-world.yml"), "chunks:\n  max-auto-save-chunks-per-tick: 8\n");

        final JsonObject root = new FoliaServerConfigProvider.SplitYamlConfigParser(config, () -> List.of(world))
            .load("paper/", filter()).getAsJsonObject();

        assertEquals(Set.of("global.yml", "world-defaults.yml", "world.yml"), root.keySet());
        assertFalse(root.toString().contains("synthetic-secret"), root.toString());
        assertTrue(root.getAsJsonObject("global.yml").getAsJsonObject("proxies").getAsJsonObject("velocity").get("enabled").getAsBoolean());
        assertEquals(24, root.getAsJsonObject("world-defaults.yml").getAsJsonObject("chunks").get("max-auto-save-chunks-per-tick").getAsInt());
        assertEquals(8, root.getAsJsonObject("world.yml").getAsJsonObject("chunks").get("max-auto-save-chunks-per-tick").getAsInt());
    }

    @Test
    void theGroupPrefixIsTheGroupNameWithoutItsSlash() throws Exception {
        final Path config = Files.createDirectories(this.dir.resolve("config"));
        Files.writeString(config.resolve("purpur-global.yml"), "a: 1\n");

        final JsonObject root = new FoliaServerConfigProvider.SplitYamlConfigParser(config, List::of).load("purpur/", filter()).getAsJsonObject();

        assertEquals(Set.of("global.yml"), root.keySet());
    }
}
