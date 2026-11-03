package dev.iyanz.sourbycraft.api;

import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.api.profile.AuroraProfiles;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeRegistry;
import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 54. The one entry point to SourbyCraft's services for a plugin compiled against
 * {@code sourbyapi}: Aurora World Fabric worlds, AWF MultiProfile profiles and their types, and the
 * read-only metrics.
 *
 * <pre>{@code
 * if (SourbyAPI.isSourbyCraft()) {
 *     AuroraWorlds worlds = SourbyAPI.worlds();          // throws FeatureUnavailableException if absent
 *     worlds.createInstance("island_base", "isle_" + id)
 *         .thenAccept(world -> getLogger().info("ready: " + world.getName()));
 *
 *     SourbyAPI.findProfiles().ifPresent(profiles ->      // empty while MultiProfile is off
 *         profiles.getActiveProfile(player.getUniqueId())
 *             .thenAccept(active -> active.ifPresent(p -> getLogger().info("profile " + p.id()))));
 * }
 * }</pre>
 *
 * <p>Every accessor comes in two forms: {@code worlds()} returns the service or throws
 * {@link FeatureUnavailableException}, which names the feature and, where one exists, the setting
 * that enables it; {@code findWorlds()} returns it or empty. Nothing here is restricted to a
 * thread: the services are looked up in Bukkit's {@link org.bukkit.plugin.ServicesManager}, which
 * is thread-safe, and the answer is cached until a service is registered or unregistered (on a
 * SourbyCraft server, which tells this class about those events). Whether the services' own
 * methods may be called on a given thread is documented on each service.</p>
 *
 * <p>On a server that is not SourbyCraft (for example Paper running a plugin that shades this
 * class), {@link #isSourbyCraft()} is false, every {@code find…} method returns empty and every
 * other accessor throws {@link FeatureUnavailableException}.</p>
 *
 * <p>Since Build 54 MultiProfile is still switched off by default
 * ({@code aurora.awf.multiprofile.enabled: false} in {@code sourbycraft_config/aurora.yml},
 * RESTART_REQUIRED); {@link #profiles()} and {@link #profileTypes()} throw until it is on. The Build 54 co-op
 * bank, collections and salvage are methods of {@link AuroraProfiles} ({@code bank}, {@code deposit},
 * {@code withdraw}, {@code depositPersonal}, {@code withdrawPersonal}, {@code transfer}, {@code applyInterest},
 * {@code collections}, {@code contribute}, {@code salvage}, {@code claimSalvage}); they additionally need
 * {@code aurora.awf.multiprofile.coop.enabled: true} (default false) and fail with
 * {@link dev.iyanz.sourbycraft.api.profile.ProfileErrorCode#COOP_DISABLED} otherwise.</p>
 */
@NullMarked
public final class SourbyAPI {

    /** The setting that switches AWF MultiProfile on (RESTART_REQUIRED). */
    public static final String MULTIPROFILE_ENABLED_KEY = "aurora.awf.multiprofile.enabled";

    private static final String BRAND = "sourbycraft:sourbycraft";
    private static final String BUILD_PROPERTIES = "META-INF/sourbycraft-build.properties";

    /** Bumped by every service (un)registration and plugin disable; a cached lookup is valid only for its own value. */
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final ConcurrentHashMap<Class<?>, Cached> CACHE = new ConcurrentHashMap<>();
    /** Whether service events reach {@link #GENERATION}; without them nothing is cached. */
    private static volatile boolean eventsBound;
    private static volatile @Nullable Boolean sourbyCraft;
    private static volatile @Nullable SourbyBuild buildInfo;

    private record Cached(long generation, Object service) {}

    private SourbyAPI() {
    }

    // ------------------------------------------------------------------ server identity

    /**
     * Whether this server is SourbyCraft: its {@code ServerBuildInfo} brand is
     * {@code sourbycraft:sourbycraft}. False before the Bukkit server exists.
     */
    public static boolean isSourbyCraft() {
        final Boolean known = sourbyCraft;
        if (known != null) return known;
        if (Bukkit.getServer() == null) return false;
        boolean answer;
        try {
            answer = BRAND.equals(io.papermc.paper.ServerBuildInfo.buildInfo().brandId().asString());
        } catch (final LinkageError | RuntimeException notPaper) {
            answer = false;
        }
        sourbyCraft = answer;
        return answer;
    }

    /**
     * The running server's build: number, codename and API version.
     *
     * @throws FeatureUnavailableException on a server that is not SourbyCraft, or whose jar has no
     *     readable build information
     */
    public static SourbyBuild build() {
        return findBuild().orElseThrow(() -> new FeatureUnavailableException("SourbyCraft build information", null,
            isSourbyCraft() ? "the server jar has no readable " + BUILD_PROPERTIES : notSourbyCraft()));
    }

    /** {@link #build()}, or empty where it would throw. Read from the jar once, then cached. */
    public static Optional<SourbyBuild> findBuild() {
        final SourbyBuild known = buildInfo;
        if (known != null) return Optional.of(known);
        if (!isSourbyCraft()) return Optional.empty();
        final Properties properties = readBuildProperties();
        if (properties == null) return Optional.empty();
        final String label = properties.getProperty("build", "").trim();
        String number = properties.getProperty("buildNumber", "").trim();
        if (number.isEmpty()) {
            final int dash = label.indexOf('-');
            number = dash < 0 ? label : label.substring(0, dash);
        }
        final SourbyBuild read = new SourbyBuild(number, label, properties.getProperty("codename", "").trim(),
            properties.getProperty("version", "").trim(), properties.getProperty("mcVersion", "").trim(),
            properties.getProperty("gitCommit", "").trim());
        buildInfo = read;
        return Optional.of(read);
    }

    private static @Nullable Properties readBuildProperties() {
        final Server server = Bukkit.getServer();
        final ClassLoader[] loaders = {server == null ? null : server.getClass().getClassLoader(),
            SourbyAPI.class.getClassLoader()};
        for (final ClassLoader loader : loaders) {
            if (loader == null) continue;
            try (InputStream in = loader.getResourceAsStream(BUILD_PROPERTIES)) {
                if (in == null) continue;
                final Properties properties = new Properties();
                properties.load(in);
                return properties;
            } catch (final IOException | IllegalArgumentException unreadable) {
                // try the next loader; none readable means no build information
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ services

    /**
     * Aurora World Fabric's runtime worlds and templates.
     *
     * @throws FeatureUnavailableException when the service is not registered
     */
    public static AuroraWorlds worlds() {
        return findWorlds().orElseThrow(() -> new FeatureUnavailableException("Aurora World Fabric worlds (AuroraWorlds)",
            null, isSourbyCraft() ? "the service is not registered: it registers before plugins enable, and is missing"
                + " when it failed to start (see the server log; an unreadable sourbycraft_config/aurora-worlds.json"
                + " stops it)" : notSourbyCraft()));
    }

    /** {@link #worlds()}, or empty where it would throw. */
    public static Optional<AuroraWorlds> findWorlds() {
        return Optional.ofNullable(lookup(AuroraWorlds.class));
    }

    /**
     * AWF MultiProfile: durable player profiles, co-op, and their state. Returned only while the
     * service is registered and {@link AuroraProfiles#isEnabled()}.
     *
     * @throws FeatureUnavailableException when MultiProfile is off, did not start, or is not ready
     */
    public static AuroraProfiles profiles() {
        return findProfiles().orElseThrow(() -> new FeatureUnavailableException("AWF MultiProfile (AuroraProfiles)",
            MULTIPROFILE_ENABLED_KEY, isSourbyCraft() ? "the service is not registered or not enabled (it is off by"
                + " default; when it is on and still missing, its start failed: see the server log)" : notSourbyCraft()));
    }

    /** {@link #profiles()}, or empty where it would throw. */
    public static Optional<AuroraProfiles> findProfiles() {
        final AuroraProfiles profiles = lookup(AuroraProfiles.class);
        return profiles != null && profiles.isEnabled() ? Optional.of(profiles) : Optional.empty();
    }

    /**
     * The profile type registry: built-in and plugin-registered profile types and their action
     * rules. Registered only while MultiProfile is running.
     *
     * @throws FeatureUnavailableException when it is not registered
     */
    public static ProfileTypeRegistry profileTypes() {
        return findProfileTypes().orElseThrow(() -> new FeatureUnavailableException(
            "AWF MultiProfile profile types (ProfileTypeRegistry)", MULTIPROFILE_ENABLED_KEY, isSourbyCraft()
                ? "the registry is registered only while MultiProfile is running" : notSourbyCraft()));
    }

    /** {@link #profileTypes()}, or empty where it would throw. */
    public static Optional<ProfileTypeRegistry> findProfileTypes() {
        return Optional.ofNullable(lookup(ProfileTypeRegistry.class));
    }

    /**
     * The read-only performance metrics.
     *
     * @throws FeatureUnavailableException when the service is not registered
     */
    public static SourbyMetrics metrics() {
        return findMetrics().orElseThrow(() -> new FeatureUnavailableException("SourbyCraft metrics (SourbyMetrics)",
            null, isSourbyCraft() ? "the service is not registered (it failed to start, see the server log, or the"
                + " server is stopping)" : notSourbyCraft()));
    }

    /** {@link #metrics()}, or empty where it would throw. */
    public static Optional<SourbyMetrics> findMetrics() {
        return Optional.ofNullable(lookup(SourbyMetrics.class));
    }

    private static String notSourbyCraft() {
        return Bukkit.getServer() == null ? "the Bukkit server is not running" : "this server is not SourbyCraft";
    }

    /**
     * The highest-priority provider of {@code type}, from the cache while it is still valid. A
     * lookup reads the generation before asking the services manager and caches its answer under
     * that generation, so an answer that raced a (un)registration is never served afterwards.
     */
    private static <T> @Nullable T lookup(final Class<T> type) {
        if (Bukkit.getServer() == null || !isSourbyCraft()) return null;
        if (!eventsBound) return Bukkit.getServicesManager().load(type);
        final long generation = GENERATION.get();
        final Cached cached = CACHE.get(type);
        if (cached != null && cached.generation() == generation) return type.cast(cached.service());
        final T found = Bukkit.getServicesManager().load(type);
        if (found != null) CACHE.put(type, new Cached(generation, found));
        else CACHE.remove(type);
        return found;
    }

    /**
     * Server use only: connects the lookup cache to service registration events, with
     * {@code owner} (the server's own internal plugin) owning the listener. Called once at startup by
     * SourbyCraft; calling it again does nothing. Without it nothing is cached and every lookup asks
     * the services manager. When {@code owner} is disabled the cache is switched off again.
     */
    @ApiStatus.Internal
    public static synchronized void bindServiceEvents(final Plugin owner) {
        if (eventsBound) return;
        final Listener listener = new Listener() {
        };
        final var plugins = Bukkit.getPluginManager();
        plugins.registerEvent(ServiceRegisterEvent.class, listener, EventPriority.MONITOR,
            (ignored, event) -> GENERATION.incrementAndGet(), owner, false);
        plugins.registerEvent(ServiceUnregisterEvent.class, listener, EventPriority.MONITOR,
            (ignored, event) -> GENERATION.incrementAndGet(), owner, false);
        plugins.registerEvent(PluginDisableEvent.class, listener, EventPriority.MONITOR, (ignored, event) -> {
            GENERATION.incrementAndGet();
            if (event instanceof PluginDisableEvent disabled && disabled.getPlugin() == owner) unbind();
        }, owner, false);
        GENERATION.incrementAndGet();
        eventsBound = true;
    }

    private static synchronized void unbind() {
        eventsBound = false;
        CACHE.clear();
        GENERATION.incrementAndGet();
    }
}
