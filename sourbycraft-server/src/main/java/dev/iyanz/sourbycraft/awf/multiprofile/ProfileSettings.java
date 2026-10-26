package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Settings of {@code aurora.awf.multiprofile.*}.
 *
 * <p>Every key is RESTART_REQUIRED. The engine creates its repository, its ownership provider and
 * its worker when the server starts; a reload cannot move an open profile repository or change which
 * ownership service is authoritative. The feature is disabled by default: disabled means no
 * directory is created, no thread is started and no player is touched.</p>
 */
public record ProfileSettings(boolean enabled, Mode mode, int defaultSlots, int maxSlots, Switching switching,
                              ProfileIsolation isolation, Worlds worlds, Coordination coordination, String directory,
                              Repository repository) {

    public enum Mode {
        STANDALONE,
        DISTRIBUTED;

        public static Mode parse(final String text) {
            try {
                return valueOf(text.toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException invalid) {
                throw new IllegalArgumentException("mode must be standalone or distributed, not " + text);
            }
        }
    }

    public enum Provider {
        LOCAL,
        REDIS;

        public static Provider parse(final String text) {
            try {
                return valueOf(text.toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException invalid) {
                throw new IllegalArgumentException("provider must be local or redis, not " + text);
            }
        }
    }

    /**
     * @param enabled whether switching is allowed at all
     * @param timeoutMillis one switch's budget; a switch that exceeds it is failed and recovered
     * @param requireDurableSave refuse to apply a target unless the old state's snapshot was forced to disk
     */
    public record Switching(boolean enabled, int timeoutMillis, boolean requireDurableSave) {
        public Switching {
            if (timeoutMillis < 1000 || timeoutMillis > 600_000) {
                throw new IllegalArgumentException("switching.timeout-ms must be 1000-600000");
            }
        }
    }

    /**
     * Profile worlds are always created/loaded when a profile becomes active (never at boot: a node
     * with 10,000 profiles cannot load 10,000 worlds), so the only knob is idle unloading.
     *
     * @param idleUnloadSeconds unload a profile world after this long with no player and no operation;
     *     {@code 0} disables idle unloading
     */
    public record Worlds(int idleUnloadSeconds) {
        public Worlds {
            if (idleUnloadSeconds < 0 || idleUnloadSeconds > 86_400) {
                throw new IllegalArgumentException("worlds.idle-unload-seconds must be 0-86400");
            }
        }
    }

    /**
     * @param provider where ownership lives; {@code local} is process-local and cannot coordinate nodes
     * @param fencingEnabled validate the ownership fence before every durable profile write
     * @param singleActiveSession refuse a second live session for one profile (duplicate login)
     */
    public record Coordination(Provider provider, boolean fencingEnabled, boolean singleActiveSession) {}

    /** Where profiles, snapshots and journals are stored. */
    public enum Backend {
        FILE,
        REDIS;

        public static Backend parse(final String text) {
            try {
                return valueOf(text.toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException invalid) {
                throw new IllegalArgumentException("repository must be file or redis, not " + text);
            }
        }
    }

    /**
     * @param backend {@code file} (the directory, one server) or {@code redis} (the Aurora Redis
     *     Fabric, fenced commits)
     * @param requireAof {@code redis} only: refuse to start when Redis reports {@code appendonly} off
     * @param waitForAof {@code redis} only: confirm the writes a later step relies on with
     *     {@code WAITAOF} before acknowledging them
     */
    public record Repository(Backend backend, boolean requireAof, boolean waitForAof) {
        public Repository {
            Objects.requireNonNull(backend, "backend");
        }
    }

    public static final String ENABLED_KEY = "aurora.awf.multiprofile.enabled";
    public static final String MODE_KEY = "aurora.awf.multiprofile.mode";
    public static final String DEFAULT_SLOTS_KEY = "aurora.awf.multiprofile.default-slots";
    public static final String MAX_SLOTS_KEY = "aurora.awf.multiprofile.max-slots";
    public static final String SWITCHING_ENABLED_KEY = "aurora.awf.multiprofile.switching.enabled";
    public static final String SWITCHING_TIMEOUT_KEY = "aurora.awf.multiprofile.switching.timeout-ms";
    public static final String SWITCHING_DURABLE_KEY = "aurora.awf.multiprofile.switching.require-durable-save";
    public static final String WORLDS_IDLE_KEY = "aurora.awf.multiprofile.worlds.idle-unload-seconds";
    public static final String COORDINATION_PROVIDER_KEY = "aurora.awf.multiprofile.coordination.provider";
    public static final String COORDINATION_FENCING_KEY = "aurora.awf.multiprofile.coordination.fencing-enabled";
    public static final String COORDINATION_SINGLE_SESSION_KEY = "aurora.awf.multiprofile.coordination.single-active-session";
    public static final String DIRECTORY_KEY = "aurora.awf.multiprofile.directory";
    public static final String DIRECTORY_DEFAULT = "awf/profiles";
    public static final String REPOSITORY_KEY = "aurora.awf.multiprofile.repository";
    public static final String REDIS_REQUIRE_AOF_KEY = "aurora.awf.multiprofile.redis.require-aof";
    public static final String REDIS_WAIT_FOR_AOF_KEY = "aurora.awf.multiprofile.redis.wait-for-aof";

    public static final List<String> KEYS = List.of(ENABLED_KEY, MODE_KEY, DEFAULT_SLOTS_KEY, MAX_SLOTS_KEY,
        SWITCHING_ENABLED_KEY, SWITCHING_TIMEOUT_KEY, SWITCHING_DURABLE_KEY,
        ProfileIsolation.INVENTORY_KEY, ProfileIsolation.ARMOR_KEY, ProfileIsolation.ENDER_CHEST_KEY,
        ProfileIsolation.EXPERIENCE_KEY, ProfileIsolation.HEALTH_KEY, ProfileIsolation.FOOD_KEY,
        ProfileIsolation.POTION_EFFECTS_KEY, ProfileIsolation.LOCATION_KEY,
        WORLDS_IDLE_KEY,
        COORDINATION_PROVIDER_KEY, COORDINATION_FENCING_KEY, COORDINATION_SINGLE_SESSION_KEY, DIRECTORY_KEY,
        REPOSITORY_KEY, REDIS_REQUIRE_AOF_KEY, REDIS_WAIT_FOR_AOF_KEY);

    /** The file repository; a server that sets no repository key keeps the behaviour of earlier builds. */
    public static final Repository DEFAULT_REPOSITORY = new Repository(Backend.FILE, true, true);

    public static final ProfileSettings DEFAULT = new ProfileSettings(false, Mode.STANDALONE, 3, 8,
        new Switching(true, 15_000, true), ProfileIsolation.ALL, new Worlds(300),
        new Coordination(Provider.LOCAL, true, true), DIRECTORY_DEFAULT, DEFAULT_REPOSITORY);

    /** Settings with {@link #DEFAULT_REPOSITORY}. */
    public ProfileSettings(final boolean enabled, final Mode mode, final int defaultSlots, final int maxSlots,
                           final Switching switching, final ProfileIsolation isolation, final Worlds worlds,
                           final Coordination coordination, final String directory) {
        this(enabled, mode, defaultSlots, maxSlots, switching, isolation, worlds, coordination, directory,
            DEFAULT_REPOSITORY);
    }

    public ProfileSettings {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(switching, "switching");
        Objects.requireNonNull(isolation, "isolation");
        Objects.requireNonNull(worlds, "worlds");
        Objects.requireNonNull(coordination, "coordination");
        Objects.requireNonNull(repository, "repository");
        if (defaultSlots < 1 || maxSlots < 1 || defaultSlots > maxSlots || maxSlots > PlayerAccount.MAX_SLOTS) {
            throw new IllegalArgumentException("default-slots and max-slots must satisfy 1 <= default <= max <= "
                + PlayerAccount.MAX_SLOTS);
        }
        if (directory == null || directory.isBlank() || directory.length() > 128
            || directory.contains("..") || directory.startsWith("/") || directory.contains("\\")) {
            throw new IllegalArgumentException("multiprofile.directory must be a relative path without '..': " + directory);
        }
    }

    /** Whether distributed mode was requested. Distributed additionally requires the Redis repository. */
    public boolean distributed() {
        return this.mode == Mode.DISTRIBUTED;
    }

    /** Whether profiles are stored in the Aurora Redis Fabric instead of the directory. */
    public boolean redisRepository() {
        return this.repository.backend() == Backend.REDIS;
    }

    /** Settings and the keys that were present but unusable (each falls back to its default). */
    public record Parsed(ProfileSettings settings, List<String> invalidKeys) {
        public Parsed {
            invalidKeys = List.copyOf(invalidKeys);
        }
    }

    public static Parsed parse(final Map<String, Object> values) {
        final List<String> invalid = new ArrayList<>();
        final boolean enabled = flag(values, ENABLED_KEY, DEFAULT.enabled(), invalid);
        Mode mode = DEFAULT.mode();
        if (values.get(MODE_KEY) != null) {
            try {
                mode = Mode.parse(String.valueOf(values.get(MODE_KEY)));
            } catch (final IllegalArgumentException bad) {
                invalid.add(MODE_KEY);
            }
        }
        final int defaultSlots = whole(values, DEFAULT_SLOTS_KEY, DEFAULT.defaultSlots(), 1, PlayerAccount.MAX_SLOTS, invalid);
        int maxSlots = whole(values, MAX_SLOTS_KEY, DEFAULT.maxSlots(), 1, PlayerAccount.MAX_SLOTS, invalid);
        if (maxSlots < defaultSlots) {
            invalid.add(MAX_SLOTS_KEY);
            maxSlots = Math.max(defaultSlots, DEFAULT.maxSlots());
        }
        final Switching switching = new Switching(
            flag(values, SWITCHING_ENABLED_KEY, DEFAULT.switching().enabled(), invalid),
            whole(values, SWITCHING_TIMEOUT_KEY, DEFAULT.switching().timeoutMillis(), 1000, 600_000, invalid),
            flag(values, SWITCHING_DURABLE_KEY, DEFAULT.switching().requireDurableSave(), invalid));
        final ProfileIsolation isolation = new ProfileIsolation(
            flag(values, ProfileIsolation.INVENTORY_KEY, DEFAULT.isolation().inventory(), invalid),
            flag(values, ProfileIsolation.ARMOR_KEY, DEFAULT.isolation().armor(), invalid),
            flag(values, ProfileIsolation.ENDER_CHEST_KEY, DEFAULT.isolation().enderChest(), invalid),
            flag(values, ProfileIsolation.EXPERIENCE_KEY, DEFAULT.isolation().experience(), invalid),
            flag(values, ProfileIsolation.HEALTH_KEY, DEFAULT.isolation().health(), invalid),
            flag(values, ProfileIsolation.FOOD_KEY, DEFAULT.isolation().food(), invalid),
            flag(values, ProfileIsolation.POTION_EFFECTS_KEY, DEFAULT.isolation().potionEffects(), invalid),
            flag(values, ProfileIsolation.LOCATION_KEY, DEFAULT.isolation().location(), invalid));
        final Worlds worlds = new Worlds(
            whole(values, WORLDS_IDLE_KEY, DEFAULT.worlds().idleUnloadSeconds(), 0, 86_400, invalid));
        Provider provider = DEFAULT.coordination().provider();
        if (values.get(COORDINATION_PROVIDER_KEY) != null) {
            try {
                provider = Provider.parse(String.valueOf(values.get(COORDINATION_PROVIDER_KEY)));
            } catch (final IllegalArgumentException bad) {
                invalid.add(COORDINATION_PROVIDER_KEY);
            }
        }
        final Coordination coordination = new Coordination(provider,
            flag(values, COORDINATION_FENCING_KEY, DEFAULT.coordination().fencingEnabled(), invalid),
            flag(values, COORDINATION_SINGLE_SESSION_KEY, DEFAULT.coordination().singleActiveSession(), invalid));
        String directory = DEFAULT.directory();
        if (values.get(DIRECTORY_KEY) instanceof String text) {
            try {
                new ProfileSettings(true, mode, defaultSlots, maxSlots, switching, isolation, worlds, coordination, text);
                directory = text;
            } catch (final IllegalArgumentException bad) {
                invalid.add(DIRECTORY_KEY);
            }
        } else if (values.containsKey(DIRECTORY_KEY)) {
            invalid.add(DIRECTORY_KEY);
        }
        Backend backend = DEFAULT_REPOSITORY.backend();
        if (values.get(REPOSITORY_KEY) != null) {
            try {
                backend = Backend.parse(String.valueOf(values.get(REPOSITORY_KEY)));
            } catch (final IllegalArgumentException bad) {
                invalid.add(REPOSITORY_KEY);
            }
        }
        final Repository repository = new Repository(backend,
            flag(values, REDIS_REQUIRE_AOF_KEY, DEFAULT_REPOSITORY.requireAof(), invalid),
            flag(values, REDIS_WAIT_FOR_AOF_KEY, DEFAULT_REPOSITORY.waitForAof(), invalid));
        return new Parsed(new ProfileSettings(enabled, mode, defaultSlots, maxSlots, switching, isolation, worlds,
            coordination, directory, repository), invalid);
    }

    /** The configuration rows for {@code AuroraKeys}, in file order. */
    public static Map<String, Object> defaultsAsMap() {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put(ENABLED_KEY, DEFAULT.enabled());
        values.put(MODE_KEY, DEFAULT.mode().name().toLowerCase(Locale.ROOT));
        values.put(DEFAULT_SLOTS_KEY, DEFAULT.defaultSlots());
        values.put(MAX_SLOTS_KEY, DEFAULT.maxSlots());
        values.put(SWITCHING_ENABLED_KEY, DEFAULT.switching().enabled());
        values.put(SWITCHING_TIMEOUT_KEY, DEFAULT.switching().timeoutMillis());
        values.put(SWITCHING_DURABLE_KEY, DEFAULT.switching().requireDurableSave());
        values.put(ProfileIsolation.INVENTORY_KEY, DEFAULT.isolation().inventory());
        values.put(ProfileIsolation.ARMOR_KEY, DEFAULT.isolation().armor());
        values.put(ProfileIsolation.ENDER_CHEST_KEY, DEFAULT.isolation().enderChest());
        values.put(ProfileIsolation.EXPERIENCE_KEY, DEFAULT.isolation().experience());
        values.put(ProfileIsolation.HEALTH_KEY, DEFAULT.isolation().health());
        values.put(ProfileIsolation.FOOD_KEY, DEFAULT.isolation().food());
        values.put(ProfileIsolation.POTION_EFFECTS_KEY, DEFAULT.isolation().potionEffects());
        values.put(ProfileIsolation.LOCATION_KEY, DEFAULT.isolation().location());
        values.put(WORLDS_IDLE_KEY, DEFAULT.worlds().idleUnloadSeconds());
        values.put(COORDINATION_PROVIDER_KEY, DEFAULT.coordination().provider().name().toLowerCase(Locale.ROOT));
        values.put(COORDINATION_FENCING_KEY, DEFAULT.coordination().fencingEnabled());
        values.put(COORDINATION_SINGLE_SESSION_KEY, DEFAULT.coordination().singleActiveSession());
        values.put(DIRECTORY_KEY, DEFAULT.directory());
        values.put(REPOSITORY_KEY, DEFAULT.repository().backend().name().toLowerCase(Locale.ROOT));
        values.put(REDIS_REQUIRE_AOF_KEY, DEFAULT.repository().requireAof());
        values.put(REDIS_WAIT_FOR_AOF_KEY, DEFAULT.repository().waitForAof());
        return values;
    }

    private static boolean flag(final Map<String, Object> values, final String key, final boolean fallback,
                                final List<String> invalid) {
        final Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        invalid.add(key);
        return fallback;
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
}
