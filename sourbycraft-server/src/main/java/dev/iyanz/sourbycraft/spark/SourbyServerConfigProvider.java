package dev.iyanz.sourbycraft.spark;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * <p>Keeps the normal Paper and Aurora engine configuration metadata while adding a first-class
 * {@code sourbycraft/} group. The profiler is read-only: it never rewrites or migrates
 * operator configuration.</p>
 */
public final class SourbyServerConfigProvider extends ServerConfigProvider {

    static final Map<String, ConfigParser> FILES;
    static final Collection<String> HIDDEN_PATHS;

    static {
        final ImmutableMap.Builder<String, ConfigParser> files = ImmutableMap.<String, ConfigParser>builder()
            .put("server.properties", PropertiesConfigParser.INSTANCE)
            .put("bukkit.yml", YamlConfigParser.INSTANCE)
            .put("spigot.yml", YamlConfigParser.INSTANCE)
            .put("paper.yml", YamlConfigParser.INSTANCE)
            .put("aurora/", AuroraSplitParser.INSTANCE)
            .put("paper/", SplitYamlConfigParser.INSTANCE)
            .put("sourbycraft/", SourbyCraftSplitParser.INSTANCE)
            .put("purpur.yml", YamlConfigParser.INSTANCE)
            .put("pufferfish.yml", YamlConfigParser.INSTANCE);

        for (final String config : getSystemPropertyList("spark.serverconfigs.extra")) {
            files.put(config, YamlConfigParser.INSTANCE);
        }

        final ImmutableSet.Builder<String> hiddenPaths = ImmutableSet.<String>builder()
            .addAll(BASE_HIDDEN_PATHS)
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
            // The Redis URI may carry the password (redis://user:password@host). The key name
            // ends in "uri", which the credential-key pass below does not treat as a secret, so
            // the key is hidden outright; the other [aurora.awf.redis] keys stay visible.
            .add("aurora.awf.redis.uri")
            .addAll(getTimingsHiddenConfigs())
            .addAll(getSystemPropertyList("spark.serverconfigs.hiddenpaths"));

        FILES = files.build();
        HIDDEN_PATHS = hiddenPaths.build();
    }

    public SourbyServerConfigProvider() {
        super(FILES, HIDDEN_PATHS);
    }

    /**
     * {@code scheme://user:password@host}: the user-info part of a URL-shaped value. Any scheme,
     * because a plugin's database or message-queue URL leaks a password the same way a Redis URI
     * does, and the key it sits under ({@code uri}, {@code url}, {@code jdbc}) names no secret.
     */
    private static final Pattern URL_USER_INFO = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://)[^/?#@\\s]+@");

    /** Operates only on freshly parsed report JSON, never on live config or files. */
    private static JsonElement removeSecrets(final JsonElement value) {
        if (value.isJsonObject()) {
            final var entries = value.getAsJsonObject().entrySet().iterator();
            while (entries.hasNext()) {
                final var entry = entries.next();
                final String key = entry.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (key.endsWith("password") || key.endsWith("passwd") || key.endsWith("secret")
                    || key.endsWith("token") || key.endsWith("apikey") || key.endsWith("accesskey")
                    || key.endsWith("privatekey") || key.endsWith("webhook") || key.endsWith("webhookurl")
                    || key.equals("credentials") || key.equals("authorization") || key.equals("connectionstring")) {
                    entries.remove();
                } else {
                    entry.setValue(removeSecrets(entry.getValue()));
                }
            }
        } else if (value.isJsonArray()) {
            final JsonArray array = value.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                array.set(i, removeSecrets(array.get(i)));
            }
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            final String text = value.getAsString();
            final String scrubbed = scrubUserInfo(text);
            if (!scrubbed.equals(text)) {
                return new JsonPrimitive(scrubbed);
            }
        }
        return value;
    }

    /**
     * Replaces the user-info of every URL in {@code text} with {@code ***}, so a value such as
     * {@code redis://app:hunter2@10.0.0.5:6379/0} is reported as {@code redis://***@10.0.0.5:6379/0}.
     * The host, port and path stay: they are what a report needs to tell two deployments apart.
     */
    static String scrubUserInfo(final String text) {
        final Matcher matcher = URL_USER_INFO.matcher(text);
        return matcher.find() ? matcher.replaceAll("$1***@") : text;
    }

    @Unmodifiable
    private static List<String> getTimingsHiddenConfigs() {
        return Collections.emptyList();
    }

    static class YamlConfigParser implements ConfigParser {
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
            return removeSecrets(filter.apply(GSON.toJsonTree(values)));
        }

        @Override
        public Map<String, Object> parse(final BufferedReader reader) {
            final YamlConfiguration config = YamlConfiguration.loadConfiguration(reader);
            return config.getValues(false);
        }
    }

    static final class TomlConfigParser implements ConfigParser {
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
                return removeSecrets(filter.apply(GSON.toJsonTree(this.parse(reader))));
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

    static final class SourbyCraftSplitParser implements ConfigParser {
        static final SourbyCraftSplitParser INSTANCE = new SourbyCraftSplitParser();
        private final Path directory;

        SourbyCraftSplitParser() {
            this(Path.of("."));
        }

        SourbyCraftSplitParser(final Path directory) {
            this.directory = directory;
        }

        @Nullable
        @Override
        public JsonElement load(final String ignored, final ExcludedConfigFilter filter) throws IOException {
            final JsonObject root = new JsonObject();

            add(root, "global.toml", this.directory.resolve("sourbycraft_config/sourbycraft_global_config.toml"),
                TomlConfigParser.INSTANCE, filter);
            // Aurora's own file. Splitting the engine's settings out of the unified file made
            // them invisible here: a Spark report showed SourbyCraft's messages and updater while
            // the settings that decide how the engine runs -- the CPU budget, async pathfinding,
            // lane sampling -- were absent from the one place an operator looks to find out what
            // a server was configured like when it was slow.
            add(root, "aurora.toml", this.directory.resolve("sourbycraft_config/aurora.toml"),
                TomlConfigParser.INSTANCE, filter);
            add(root, "security.yml", this.directory.resolve("sourbycraft-security.yml"),
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

    /**
     * The Aurora engine files. Group {@code aurora/} (was {@code canvas/} until 2026-10-21); the entry names inside
     * are the real file names: {@code aurora-server.yml} and {@code aurora-worlds.yml} since Build 49 (the
     * {@code canvas-*.yml} names are migrated once at boot and no longer read), plus each world's
     * {@code canvas-patch.yml}, which keeps its name.
     */
    private static class AuroraSplitParser extends YamlConfigParser {
        static final AuroraSplitParser INSTANCE = new AuroraSplitParser();

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
                    root.add(entry.getKey(), removeSecrets(filter.apply(GSON.toJsonTree(values))));
                }
            }
            return root;
        }

        private static Map<String, Path> getNestedFiles(final Path configDir) {
            final Map<String, Path> files = new LinkedHashMap<>();
            // The engine's file names (Build 49), spelled here rather than imported: Aurora's own source
            // names the engine only through config/upstream/UpstreamConfigBridge (independence policy).
            files.put("aurora-server.yml", configDir.resolve("aurora-server.yml"));
            files.put("aurora-worlds.yml", configDir.resolve("aurora-worlds.yml"));
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
                    root.add(entry.getKey(), removeSecrets(filter.apply(GSON.toJsonTree(values))));
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
