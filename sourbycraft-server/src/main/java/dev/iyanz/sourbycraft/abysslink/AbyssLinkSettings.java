package dev.iyanz.sourbycraft.abysslink;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import dev.iyanz.sourbycraft.config.AuroraKeys;
import dev.iyanz.sourbycraft.config.ConfigKey;
import dev.iyanz.sourbycraft.config.ConfigSources;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable boot settings. All settings are RESTART_REQUIRED; TLS cannot be disabled.
 *
 * <p>Read-only: the keys are part of {@link AuroraKeys#KEYS}, so
 * {@code SourbyCraftConfig.init()} seeds them (with their comments) into
 * {@code sourbycraft_config/aurora.yml} before this runs. Reading never rewrites the operator's file;
 * a missing key takes its documented default. The YAML file is resolved through
 * {@link ConfigSources}, so the legacy TOML still works until it has been converted.</p>
 */
public record AbyssLinkSettings(boolean enabled, String host, int port, String nodeId,
        String identity, String trustCertificate, String clientCertificate, String clientKey,
        int reconnectMinMillis, int reconnectMaxMillis, int heartbeatMillis, int timeoutMillis,
        int pingCacheMillis, int maxPendingRequests, int telemetryMillis, boolean threatsEnabled,
        int maxSampleAgeMillis) {
    public static final Path FILE = Path.of("sourbycraft_config", "aurora.yml");
    private static final String ROOT = "aurora.abysslink";
    private static final java.util.Map<String, ConfigKey> KEYS = AuroraKeys.ABYSSLINK_KEYS.stream()
        .collect(Collectors.toUnmodifiableMap(ConfigKey::name, key -> key));

    private static ConfigKey key(final String name) {
        return KEYS.get(name);
    }

    public static AbyssLinkSettings load(final Path file) throws Exception {
        final Path source = ConfigSources.existing(file);
        final CommentedConfig yaml = source == null ? null : ConfigSources.read(source);
        final Object section = yaml == null ? null : yaml.get(ROOT);
        if (section instanceof Config config) {
            final Set<String> known = AuroraKeys.ABYSSLINK_KEYS.stream().map(ConfigKey::name)
                .collect(Collectors.toSet());
            for (final String key : config.valueMap().keySet()) {
                if (!known.contains(key)) throw new IllegalArgumentException("Unknown AbyssLink option: " + key);
            }
        }
        if (!flag(yaml, key("tls"))) {
            throw new IllegalArgumentException("AbyssLink requires verified TLS");
        }
        final String host = text(yaml, key("host"));
        final String node = text(yaml, key("node-id"));
        final String identity = text(yaml, key("authenticated-identity"));
        if (host.isBlank() || host.length() > 253 || identity.isBlank() || identity.length() > 253
            || !node.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("Invalid AbyssLink identity/host");
        }
        final String cert = text(yaml, key("client-certificate"));
        final String key = text(yaml, key("client-key"));
        if (cert.isEmpty() != key.isEmpty()) {
            throw new IllegalArgumentException("Both client certificate and key are required");
        }
        final boolean enabled = flag(yaml, key("enabled"));
        if (enabled && cert.isEmpty()) {
            throw new IllegalArgumentException("Leviathan requires a backend client certificate");
        }
        final var settings = new AbyssLinkSettings(enabled, host,
            integer(yaml, key("port"), 1, 65535), node, identity,
            text(yaml, key("trust-certificate")), cert, key,
            integer(yaml, key("reconnect-min-ms"), 100, 60000),
            integer(yaml, key("reconnect-max-ms"), 100, 300000),
            integer(yaml, key("heartbeat-ms"), 1000, 60000),
            integer(yaml, key("timeout-ms"), 100, 2000),
            integer(yaml, key("ping-cache-ms"), 0, 2000),
            integer(yaml, key("max-pending-requests"), 1, 256),
            integer(yaml, key("telemetry-ms"), 1000, 300000),
            flag(yaml, key("threat-reporting")),
            integer(yaml, key("max-sample-age-ms"), 100, 10000));
        if (settings.reconnectMinMillis > settings.reconnectMaxMillis) {
            throw new IllegalArgumentException("Invalid reconnect interval");
        }
        return settings;
    }

    /** The key's value from the file, or its documented default when the file omits it. */
    private static Object value(final CommentedConfig yaml, final ConfigKey key) {
        final Object stored = yaml == null ? null : yaml.get(key.path());
        return stored != null ? stored : key.defaultValue();
    }

    private static String text(final CommentedConfig yaml, final ConfigKey key) {
        final Object value = value(yaml, key);
        if (!(value instanceof String text) || text.length() > 4096) {
            throw new IllegalArgumentException("Invalid AbyssLink " + key.name());
        }
        return text;
    }

    private static boolean flag(final CommentedConfig yaml, final ConfigKey key) {
        final Object value = value(yaml, key);
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("Invalid AbyssLink " + key.name());
        return flag;
    }

    private static int integer(final CommentedConfig yaml, final ConfigKey key, final int min, final int max) {
        final Object value = value(yaml, key);
        if (!(value instanceof Number number) || number.intValue() < min || number.intValue() > max) {
            throw new IllegalArgumentException("Invalid AbyssLink " + key.name());
        }
        return number.intValue();
    }
}
