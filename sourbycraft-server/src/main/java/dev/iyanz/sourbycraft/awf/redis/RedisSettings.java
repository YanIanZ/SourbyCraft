package dev.iyanz.sourbycraft.awf.redis;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How the {@code redis} AWF backend connects, from {@code [aurora.awf.redis]}. Every key is
 * RESTART_REQUIRED, like the rest of {@code aurora.awf}.
 *
 * @param uri {@code redis://[user[:password]@]host[:port][/db]} or {@code rediss://}; when the key
 *     is absent the environment variable {@code SOURBYCRAFT_AWF_REDIS_URI} is used, so a password
 *     need not be written into a config file
 * @param keyPrefix prepended to every key; servers that must not see each other's worlds use
 *     different prefixes
 * @param poolSize connections kept open
 * @param timeoutMillis connect, read and pool-wait timeout
 * @param leaseSeconds how long a server's claim on a writable world lasts without renewal; a
 *     crashed server's worlds can be opened elsewhere after this
 * @param waitForAof after each commit, wait until Redis has written it to its append-only file
 *     ({@code WAITAOF}); without it a commit is acknowledged from Redis's memory
 */
public record RedisSettings(String uri, String keyPrefix, int poolSize, int timeoutMillis, int leaseSeconds,
                            boolean waitForAof) {

    public static final String URI_KEY = "aurora.awf.redis.uri";
    public static final String PREFIX_KEY = "aurora.awf.redis.key-prefix";
    public static final String POOL_KEY = "aurora.awf.redis.pool-size";
    public static final String TIMEOUT_KEY = "aurora.awf.redis.timeout-ms";
    public static final String LEASE_KEY = "aurora.awf.redis.lease-seconds";
    public static final String AOF_KEY = "aurora.awf.redis.wait-for-aof";
    public static final String URI_ENV = "SOURBYCRAFT_AWF_REDIS_URI";
    static final List<String> KEYS = List.of(URI_KEY, PREFIX_KEY, POOL_KEY, TIMEOUT_KEY, LEASE_KEY, AOF_KEY);

    public static final RedisSettings DEFAULT =
        new RedisSettings("redis://127.0.0.1:6379/0", "sourbycraft:awf:", 8, 5000, 60, true);

    public RedisSettings {
        RedisClient.Endpoint.parse(uri);
        if (keyPrefix == null || keyPrefix.isEmpty() || keyPrefix.length() > 128 || keyPrefix.contains("{")
            || keyPrefix.contains("}")) {
            throw new IllegalArgumentException("key prefix must be 1-128 characters without braces");
        }
        if (poolSize < 1 || timeoutMillis < 100 || leaseSeconds < 5) {
            throw new IllegalArgumentException("pool-size >= 1, timeout-ms >= 100, lease-seconds >= 5");
        }
    }

    /** Settings and the keys that were present but unusable (each falls back to its default). */
    public record Parsed(RedisSettings settings, List<String> invalidKeys) {}

    public static Parsed parse(final Map<String, Object> values, final String environmentUri) {
        final List<String> invalid = new ArrayList<>();
        String uri = environmentUri != null && !environmentUri.isBlank() ? environmentUri : DEFAULT.uri();
        if (values.get(URI_KEY) instanceof String text) {
            try {
                RedisClient.Endpoint.parse(text);
                uri = text;
            } catch (final IllegalArgumentException bad) {
                invalid.add(URI_KEY);
            }
        } else if (values.containsKey(URI_KEY)) {
            invalid.add(URI_KEY);
        }
        String prefix = DEFAULT.keyPrefix();
        if (values.get(PREFIX_KEY) instanceof String text && !text.isEmpty() && text.length() <= 128
            && !text.contains("{") && !text.contains("}")) {
            prefix = text;
        } else if (values.containsKey(PREFIX_KEY)) {
            invalid.add(PREFIX_KEY);
        }
        final int pool = whole(values, POOL_KEY, DEFAULT.poolSize(), 1, 256, invalid);
        final int timeout = whole(values, TIMEOUT_KEY, DEFAULT.timeoutMillis(), 100, 600_000, invalid);
        final int lease = whole(values, LEASE_KEY, DEFAULT.leaseSeconds(), 5, 3600, invalid);
        boolean aof = DEFAULT.waitForAof();
        if (values.get(AOF_KEY) instanceof Boolean flag) {
            aof = flag;
        } else if (values.containsKey(AOF_KEY)) {
            invalid.add(AOF_KEY);
        }
        return new Parsed(new RedisSettings(uri, prefix, pool, timeout, lease, aof), invalid);
    }

    private static int whole(final Map<String, Object> values, final String key, final int fallback, final int min,
                             final int max, final List<String> invalid) {
        final Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number && number.doubleValue() == Math.floor(number.doubleValue())
            && number.longValue() >= min && number.longValue() <= max) {
            return number.intValue();
        }
        invalid.add(key);
        return fallback;
    }

    /** Reads the keys from Aurora's file over the unified file, as {@code AwfSettings.readEarly} does. */
    public static RedisSettings readEarly(final Path auroraFile, final Path unifiedFile) {
        final Map<String, Object> values = new HashMap<>();
        for (final Path file : List.of(unifiedFile, auroraFile)) {
            if (!Files.isRegularFile(file)) continue;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                final var config = new com.electronwill.nightconfig.toml.TomlParser().parse(reader);
                for (final String key : KEYS) {
                    final Object value = config.get(key);
                    if (value != null) values.put(key, value);
                }
            } catch (final Exception unreadable) {
                SourbyLogger.warn("Aurora World Fabric could not read " + file + " for the Redis backend ("
                    + unreadable.getMessage() + "); using the Redis defaults");
            }
        }
        final Parsed parsed = parse(values, System.getenv(URI_ENV));
        for (final String key : parsed.invalidKeys()) {
            SourbyLogger.warn("Aurora config key '" + key + "' is invalid; the Redis backend uses its default for it");
        }
        return parsed.settings();
    }
}
