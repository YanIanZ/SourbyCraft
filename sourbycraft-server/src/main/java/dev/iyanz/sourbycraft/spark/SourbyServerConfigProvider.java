package dev.iyanz.sourbycraft.spark;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.lucko.spark.paper.common.platform.serverconfig.ConfigParser;
import me.lucko.spark.paper.common.platform.serverconfig.ExcludedConfigFilter;
import me.lucko.spark.paper.common.platform.serverconfig.PropertiesConfigParser;
import me.lucko.spark.paper.common.platform.serverconfig.ServerConfigProvider;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.Nullable;

/**
 * SourbyCraft-owned spark configuration provider.
 *
 * <p>Keeps the normal Paper/Canvas configuration metadata while adding a first-class
 * {@code sourbycraft/} group. The profiler is read-only: it never rewrites or migrates
 * operator configuration.</p>
 */
public final class SourbyServerConfigProvider extends ServerConfigProvider {

    private static final Map<String, ConfigParser> FILES;
    private static final Collection<String> HIDDEN_PATHS;

    static {
        final ImmutableMap.Builder<String, ConfigParser> files = ImmutableMap.<String, ConfigParser>builder()
            .put("server.properties", PropertiesConfigParser.INSTANCE)
            .put("bukkit.yml", YamlConfigParser.INSTANCE)
            .put("spigot.yml", YamlConfigParser.INSTANCE)
            .put("paper.yml", YamlConfigParser.INSTANCE)
            .put("canvas/", CanvasSplitParser.INSTANCE)
            .put("paper/", SplitYamlConfigParser.INSTANCE)
            .put("sourbycraft/", SourbyCraftSplitParser.INSTANCE)
            .put("purpur.yml", YamlConfigParser.INSTANCE)
            .put("pufferfish.yml", YamlConfigParser.INSTANCE);

        for (final String config : getSystemPropertyList("spark.serverconfigs.extra")) {
            files.put(config, YamlConfigParser.INSTANCE);
        }

        final ImmutableSet.Builder<String> hiddenPaths = ImmutableSet.<String>builder()
            .add("database")
            .add("settings.bungeecord-addresses")
            .add("settings.velocity-support.secret")
            .add("proxies.velocity.secret")
            .add("server-ip")
            .add("motd")
            .add("resource-pack")
            .add("rcon<dot>password")
            .add("rcon<dot>ip")
            .add("level-seed")
            .add("world-settings.*.feature-seeds")
            .add("world-settings.*.seed-*")
            .add("feature-seeds")
            .add("seed-*")
            .addAll(getTimingsHiddenConfigs())
            .addAll(getSystemPropertyList("spark.serverconfigs.hiddenpaths"));

        FILES = files.build();
        HIDDEN_PATHS = hiddenPaths.build();
    }

    public SourbyServerConfigProvider() {
        super(FILES, HIDDEN_PATHS);
    }

    @Unmodifiable
    private static List<String> getTimingsHiddenConfigs() {
        return Collections.emptyList();
    }

    private static class YamlConfigParser implements ConfigParser {
        static final YamlConfigParser INSTANCE = new YamlConfigParser();
        static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(MemorySection.class,
                (JsonSerializer<MemorySection>) (obj, _, ctx) -> ctx.serialize(obj.getValues(false)))
            .create();

        @Nullable
        @Override
        public JsonElement load(final String file, final ExcludedConfigFilter filter) throws IOException {
            final Map<String, Object> values = this.parse(Paths.get(file));
            if (values == null) {
                return null;
            }
            return filter.apply(GSON.toJsonTree(values));
        }

        @Override
        public Map<String, Object> parse(final BufferedReader reader) {
            final YamlConfiguration config = YamlConfiguration.loadConfiguration(reader);
            return config.getValues(false);
        }
    }

    private static final class TomlConfigParser implements ConfigParser {
        static final TomlConfigParser INSTANCE = new TomlConfigParser();
        private static final Gson GSON = new Gson();

        @Nullable
        @Override
        public JsonElement load(final String file, final ExcludedConfigFilter filter) throws IOException {
            final Path path = Paths.get(file);
            if (!Files.exists(path)) {
                return null;
            }
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                return filter.apply(GSON.toJsonTree(this.parse(reader)));
            }
        }

        @Override
        public Map<String, Object> parse(final BufferedReader reader) {
            final UnmodifiableConfig config = new TomlParser().parse(reader);
            return normalizeMap(config.valueMap());
        }

        private static Map<String, Object> normalizeMap(final Map<String, Object> source) {
            final Map<String, Object> out = new LinkedHashMap<>(source.size());
            source.forEach((key, value) -> out.put(key, normalize(value)));
            return out;
        }

        private static Object normalize(final Object value) {
            if (value instanceof UnmodifiableConfig config) {
                return normalizeMap(config.valueMap());
            }
            if (value instanceof List<?> list) {
                final List<Object> out = new ArrayList<>(list.size());
                for (final Object element : list) {
                    out.add(normalize(element));
                }
                return out;
            }
            return value;
        }
    }

    private static final class SourbyCraftSplitParser implements ConfigParser {
        static final SourbyCraftSplitParser INSTANCE = new SourbyCraftSplitParser();

        @Nullable
        @Override
        public JsonElement load(final String ignored, final ExcludedConfigFilter filter) throws IOException {
            final JsonObject root = new JsonObject();

            add(root, "global.toml", Path.of("sourbycraft_config", "sourbycraft_global_config.toml"),
                TomlConfigParser.INSTANCE, filter);
            add(root, "security.yml", Path.of("sourbycraft-security.yml"),
                YamlConfigParser.INSTANCE, filter);

            return root.size() == 0 ? null : root;
        }

        @Override
        public Map<String, Object> parse(final BufferedReader reader) {
            throw new UnsupportedOperationException("SourbyCraft config group is composed from multiple files");
        }

        private static void add(
            final JsonObject root,
            final String name,
            final Path path,
            final ConfigParser parser,
            final ExcludedConfigFilter filter
        ) throws IOException {
            if (!Files.exists(path)) {
                return;
            }
            final JsonElement value = parser.load(path.toString(), filter);
            if (value != null) {
                root.add(name, value);
            }
        }
    }

    private static class CanvasSplitParser extends YamlConfigParser {
        static final CanvasSplitParser INSTANCE = new CanvasSplitParser();

        @Nullable
        @Override
        public JsonElement load(final String group, final ExcludedConfigFilter filter) throws IOException {
            final Path configDir = Paths.get("config");
            if (!Files.exists(configDir)) {
                return null;
            }

            final JsonObject root = new JsonObject();
            for (final Map.Entry<String, Path> entry : getNestedFiles(configDir).entrySet()) {
                final Map<String, Object> values = this.parse(entry.getValue());
                if (values != null) {
                    root.add(entry.getKey(), filter.apply(GSON.toJsonTree(values)));
                }
            }
            return root;
        }

        private static Map<String, Path> getNestedFiles(final Path configDir) {
            final Map<String, Path> files = new LinkedHashMap<>();
            files.put("canvas-server.yml", configDir.resolve("canvas-server.yml"));
            files.put("world-defaults.yml", configDir.resolve("canvas-worlds.yml"));
            for (final World world : Bukkit.getWorlds()) {
                files.put(world.getName() + ".yml", world.getWorldFolder().toPath().resolve("canvas-patch.yml"));
            }
            return files;
        }
    }

    private static class SplitYamlConfigParser extends YamlConfigParser {
        static final SplitYamlConfigParser INSTANCE = new SplitYamlConfigParser();

        @Nullable
        @Override
        public JsonElement load(final String group, final ExcludedConfigFilter filter) throws IOException {
            final String prefix = group.replace("/", "");
            final Path configDir = Paths.get("config");
            if (!Files.exists(configDir)) {
                return null;
            }

            final JsonObject root = new JsonObject();
            for (final Map.Entry<String, Path> entry : getNestedFiles(configDir, prefix).entrySet()) {
                final Map<String, Object> values = this.parse(entry.getValue());
                if (values != null) {
                    root.add(entry.getKey(), filter.apply(GSON.toJsonTree(values)));
                }
            }
            return root;
        }

        private static Map<String, Path> getNestedFiles(final Path configDir, final String prefix) {
            final Map<String, Path> files = new LinkedHashMap<>();
            files.put("global.yml", configDir.resolve(prefix + "-global.yml"));
            files.put("world-defaults.yml", configDir.resolve(prefix + "-world-defaults.yml"));
            for (final World world : Bukkit.getWorlds()) {
                files.put(world.getName() + ".yml", world.getWorldFolder().toPath().resolve(prefix + "-world.yml"));
            }
            return files;
        }
    }
}
