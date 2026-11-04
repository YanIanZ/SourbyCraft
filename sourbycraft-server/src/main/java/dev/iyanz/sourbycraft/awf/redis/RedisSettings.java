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
import java.util.Objects;
import java.util.Optional;

/**
 * How the {@code redis} AWF backend connects, from {@code [aurora.awf.redis]}. Every key is
 * RESTART_REQUIRED, like the rest of {@code aurora.awf}.
 *
 * @param uri {@code redis://[user[:password]@]host[:port][/db]} or {@code rediss://}; when the key
 *     is absent the environment variable {@code SOURBYCRAFT_AWF_REDIS_URI} is used, so a password
 *     need not be written into a config file. {@code null} exactly when {@code invalidReason} is
 *     present
 * @param keyPrefix prepended to every key; servers that must not see each other's worlds use
 *     different prefixes
 * @param poolSize connections kept open
 * @param timeoutMillis connect, read and pool-wait timeout
 * @param leaseSeconds how long a server's claim on a writable world lasts without renewal; a
 *     crashed server's worlds can be opened elsewhere after this
 * @param waitForAof after each commit, wait until Redis has written it to its append-only file
 *     ({@code WAITAOF}); without it a commit is acknowledged from Redis's memory
 * @param invalidReason why the configured Redis server is unusable (the URI key or environment value
 *     does not parse, or a config file holding it could not be read), naming the key but never the
 *     value; while present nothing may connect: {@link RedisBackend} and the Aurora Redis Fabric
 *     refuse these settings instead of using the default server
 */
public record RedisSettings(String uri, String keyPrefix, int poolSize, int timeoutMillis, int leaseSeconds,
                            boolean waitForAof, Optional<String> invalidReason) {

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
        Objects.requireNonNull(invalidReason, "invalidReason");
        if (invalidReason.isEmpty()) {
            RedisClient.Endpoint.parse(uri);
        } else {
            uri = null;
        }
        if (keyPrefix == null || keyPrefix.isEmpty() || keyPrefix.length() > 128 || keyPrefix.contains("{")
            || keyPrefix.contains("}")) {
            throw new IllegalArgumentException("key prefix must be 1-128 characters without braces");
        }
        if (poolSize < 1 || timeoutMillis < 100 || leaseSeconds < 5) {
            throw new IllegalArgumentException("pool-size >= 1, timeout-ms >= 100, lease-seconds >= 5");
        }
    }

    /** Usable settings: the URI must parse. */
    public RedisSettings(final String uri, final String keyPrefix, final int poolSize, final int timeoutMillis,
                         final int leaseSeconds, final boolean waitForAof) {
        this(uri, keyPrefix, poolSize, timeoutMillis, leaseSeconds, waitForAof, Optional.empty());
    }

    /**
     * Settings and the keys that were present but unusable (each falls back to its default). An
     * unusable URI is not among them: it has no fallback and is reported by
     * {@link RedisSettings#invalidReason()}.
     */
    public record Parsed(RedisSettings settings, List<String> invalidKeys) {}

    /**
     * @param environmentUri {@code SOURBYCRAFT_AWF_REDIS_URI}; used only when {@link #URI_KEY} is
     *     absent, and a blank value counts as unset
     */
    public static Parsed parse(final Map<String, Object> values, final String environmentUri) {
        final List<String> invalid = new ArrayList<>();
        String uri = DEFAULT.uri();
        String invalidReason = null;
        if (values.containsKey(URI_KEY)) {
            invalidReason = values.get(URI_KEY) instanceof String text ? uriProblem(URI_KEY, text)
                : URI_KEY + " is not a string";
            if (invalidReason == null) uri = (String) values.get(URI_KEY);
        } else if (environmentUri != null && !environmentUri.isBlank()) {
            invalidReason = uriProblem("the environment variable " + URI_ENV + " (used because " + URI_KEY
                + " is not set)", environmentUri);
            if (invalidReason == null) uri = environmentUri;
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
        return new Parsed(new RedisSettings(uri, prefix, pool, timeout, lease, aof, Optional.ofNullable(invalidReason)),
            invalid);
    }

    /** Why {@code text} is not a usable Redis URI, naming {@code source} but never the value; {@code null} when it is. */
    private static String uriProblem(final String source, final String text) {
        try {
            RedisClient.Endpoint.parse(text);
            return null;
        } catch (final IllegalArgumentException bad) {
            // Endpoint.parse never quotes the text, so its reason is safe to log.
            return source + " is not a usable Redis URI: " + bad.getMessage();
        }
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

    /**
     * Reads the keys from Aurora's file over the unified file, as {@code AwfSettings.readEarly} does.
     * A config file that exists but cannot be read may hold the URI that wins, so unless Aurora's
     * file was read and sets the URI, the result is invalid rather than pointed at the default or
     * environment server; the other keys of an unreadable file use their defaults.
     */
    public static RedisSettings readEarly(final Path auroraFile, final Path unifiedFile) {
        final Map<String, Object> values = new HashMap<>();
        Path unreadableFile = null;
        boolean uriFromAuroraFile = false;
        for (final Path file : List.of(unifiedFile, auroraFile)) {
            final Path source = dev.iyanz.sourbycraft.config.ConfigSources.existing(file);
            if (source == null) continue;
            try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                final var config = dev.iyanz.sourbycraft.config.ConfigSources.parse(source, reader);
                for (final String key : KEYS) {
                    final Object value = config.get(key);
                    if (value != null) values.put(key, value);
                }
                if (file.equals(auroraFile) && config.get(URI_KEY) != null) uriFromAuroraFile = true;
            } catch (final Exception unreadable) {
                unreadableFile = source;
                SourbyLogger.warn("Aurora World Fabric could not read "
                    + dev.iyanz.sourbycraft.config.ConfigParseErrors.describe(source, unreadable)
                    + " for the Redis backend; the Redis keys it sets are ignored");
            }
        }
        final Parsed parsed = parse(values, System.getenv(URI_ENV));
        for (final String key : parsed.invalidKeys()) {
            SourbyLogger.warn("Aurora config key '" + key + "' is invalid; the Redis backend uses its default for it");
        }
        final RedisSettings settings = parsed.settings();
        if (unreadableFile != null && !uriFromAuroraFile && settings.invalidReason().isEmpty()) {
            return new RedisSettings(null, settings.keyPrefix(), settings.poolSize(), settings.timeoutMillis(),
                settings.leaseSeconds(), settings.waitForAof(), Optional.of(URI_KEY + " is unknown because "
                    + unreadableFile + " could not be read"));
        }
        return settings;
    }
}
