package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.AuroraProfiles;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileOwner;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileStateAdapter;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import dev.iyanz.sourbycraft.api.profile.ProfileWorldRef;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.EventFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricConfig;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricSettings;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric;
import dev.iyanz.sourbycraft.execution.ResourceGovernor;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

/**
 * Boots and owns AWF MultiProfile: settings, repository, ownership, the engine, the
 * {@link AuroraProfiles} service and the join/quit listener.
 *
 * <p>Disabled by default. A disabled runtime creates no directory and registers no service, so a
 * plugin that looks it up in {@code onEnable} gets {@code null} exactly as on any server without the
 * feature.</p>
 *
 * <p>Shutdown: {@link #close(long)} drains the engine's queued I/O within a budget before the
 * Resource Governor storage lane is shut down; a drain that times out is logged as incomplete, never
 * as saved.</p>
 */
public final class ProfileRuntime {

    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.yml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.yml");

    private static volatile ProfileRuntime running;

    private final Plugin plugin;
    private final ProfileSettings settings;
    private final ProfileEngine engine;
    private final ProfileStateAdapters adapters;
    private final BukkitPlayerStatePort statePort;
    private final AuroraProfiles api;
    private final ScheduledExecutorService idleSweeper;
    private final ScheduledExecutorService heartbeat;
    private final String nodeId;
    private volatile boolean closed;

    private ProfileRuntime(final Plugin plugin, final ProfileSettings settings, final ProfileEngine engine,
                           final ProfileStateAdapters adapters, final BukkitPlayerStatePort statePort,
                           final ScheduledExecutorService idleSweeper, final ScheduledExecutorService heartbeat,
                           final String nodeId) {
        this.plugin = plugin;
        this.settings = settings;
        this.engine = engine;
        this.adapters = adapters;
        this.statePort = statePort;
        this.idleSweeper = idleSweeper;
        this.heartbeat = heartbeat;
        this.nodeId = nodeId;
        this.api = new Api();
    }

    /** The running runtime, or {@code null} when MultiProfile is disabled. */
    public static ProfileRuntime running() {
        return running;
    }

    public static ProfileEngine engineOrNull() {
        final ProfileRuntime runtime = running;
        return runtime == null ? null : runtime.engine;
    }

    public static ProfileSettings settingsOrDisable() {
        final ProfileRuntime runtime = running;
        return runtime == null ? ProfileSettings.DEFAULT : runtime.settings;
    }

    /**
     * Starts the runtime. Idempotent. A disabled feature returns without creating anything; an
     * enabled feature that cannot start logs the reason and fails its boot stage, so the boot
     * summary never reports a subsystem as running when it refused to start.
     */
    public static synchronized void start(final Plugin plugin) {
        if (running != null) return;
        final ProfileSettings settings = ProfileConfig.readEarly(AURORA_FILE, UNIFIED_FILE);
        if (!settings.enabled()) return;
        try {
            running = create(plugin, settings);
        } catch (final RuntimeException | IOException failed) {
            // A configured feature that refused to start must fail its boot stage, not report a
            // running subsystem. The stage logs the failure and the server keeps running without it.
            SourbyLogger.error("AWF MultiProfile could not start: " + failed.getMessage(), failed);
            throw new IllegalStateException("AWF MultiProfile could not start: " + failed.getMessage(), failed);
        }
    }

    private static ProfileRuntime create(final Plugin plugin, final ProfileSettings settings) throws IOException {
        final String nodeId = resolveNodeId();
        final RedisFabric fabric = RedisFabric.running();
        if (settings.distributed() && (fabric == null || !fabric.settings().enabled())) {
            throw new IllegalStateException("mode: distributed requires aurora.awf.redis.fabric.enabled: true"
                + " and a reachable Redis; refusing to start with local-only ownership");
        }
        if (settings.distributed() && settings.coordination().provider() != ProfileSettings.Provider.REDIS) {
            throw new IllegalStateException("mode: distributed requires coordination.provider: redis");
        }
        if (settings.distributed() && !settings.coordination().fencingEnabled()) {
            throw new IllegalStateException("mode: distributed requires coordination.fencing-enabled: true;"
                + " without it a stale owner could commit");
        }
        if (settings.distributed()) {
            throw new IllegalStateException("distributed MultiProfile is unavailable in Build 50: the FILE profile"
                + " repository cannot atomically fence writes with Redis ownership. Use mode: standalone with a"
                + " directory exclusive to this server; two-node takeover is not supported yet.");
        }
        final ProfileRepository repository = new FileProfileRepository(Path.of(settings.directory()));
        final ProfileOwnership ownership;
        if (settings.coordination().provider() == ProfileSettings.Provider.REDIS) {
            if (fabric == null) throw new IllegalStateException("coordination.provider: redis requires the Aurora Redis Fabric");
            final RedisSettings redis = fabric.redisSettings();
            ownership = new RedisProfileOwnership(fabric.sessions(), nodeId, redis.timeoutMillis());
        } else {
            ownership = new LocalProfileOwnership(nodeId, 60);
        }
        final ProfileStateAdapters adapters = new ProfileStateAdapters();
        final BukkitPlayerStatePort statePort = new BukkitPlayerStatePort(plugin, adapters);
        final AwfProfileWorldPort worldPort = new AwfProfileWorldPort(
            dev.iyanz.sourbycraft.awf.world.AuroraWorldsRuntime::service);
        final EventFabric events = fabric != null && fabric.settings().services().events() ? fabric.events() : null;
        final ProfileEngine engine = new ProfileEngine(settings, repository, ownership, statePort, worldPort, nodeId,
            ResourceGovernor.GLOBAL.lane(ResourceGovernor.Lane.STORAGE), fabric == null ? null : fabric.metrics(), events);
        ScheduledExecutorService sweeper = null;
        if (settings.worlds().idleUnloadSeconds() > 0) {
            sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
                final Thread thread = new Thread(task, "SourbyCraft-AWF-profile-idle");
                thread.setDaemon(true);
                return thread;
            });
            sweeper.scheduleWithFixedDelay(() -> {
                try {
                    engine.sweepIdleWorlds();
                } catch (final RuntimeException failed) {
                    SourbyLogger.warn("AWF MultiProfile: idle world sweep failed: " + failed.getMessage());
                }
            }, 30, 30, TimeUnit.SECONDS);
        }
        final boolean routing = fabric != null && fabric.settings().enabled()
            && fabric.settings().services().routing();
        ScheduledExecutorService heartbeats = null;
        if (settings.coordination().provider() == ProfileSettings.Provider.REDIS || routing) {
            // A session lease is renewed on the fabric's own cadence; without this the claim would
            // expire after sessions.ttl-seconds and a second node could take the profile over.
            final long period = Math.max(1L, fabric == null ? 10L : fabric.settings().sessions().heartbeatSeconds());
            heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
                final Thread thread = new Thread(task, "SourbyCraft-AWF-profile-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            if (settings.coordination().provider() == ProfileSettings.Provider.REDIS) {
                heartbeats.scheduleWithFixedDelay(() -> engine.renewSessions().exceptionally(failed -> {
                    SourbyLogger.warn("AWF MultiProfile: session renewal failed: " + ProfileEngine.messageOf(failed));
                    return 0;
                }), period, period, TimeUnit.SECONDS);
            }
            if (routing) {
                final Runnable advertise = () -> {
                    try {
                        fabric.routing().register(new dev.iyanz.sourbycraft.awf.redis.fabric.RoutingFabric.NodeInfo(
                            nodeId, "", Math.max(1, Bukkit.getMaxPlayers()), Bukkit.getOnlinePlayers().size(),
                            "HEALTHY", System.currentTimeMillis()));
                    } catch (final RuntimeException | java.io.IOException failed) {
                        SourbyLogger.warn("AWF MultiProfile: advertising this node failed: " + failed.getMessage());
                    }
                };
                advertise.run();
                heartbeats.scheduleWithFixedDelay(advertise, period, period, TimeUnit.SECONDS);
            }
        }
        final ProfileRuntime runtime = new ProfileRuntime(plugin, settings, engine, adapters, statePort, sweeper,
            heartbeats, nodeId);
        Bukkit.getServicesManager().register(AuroraProfiles.class, runtime.api(), plugin, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(runtime.new Sessions(), plugin);
        SourbyLogger.info("AWF MultiProfile: enabled (" + settings.mode().name().toLowerCase(java.util.Locale.ROOT)
            + ", node " + nodeId + ", directory " + settings.directory() + ", ownership " + ownership.describe()
            + (sweeper == null ? ", idle unload off" : ", idle unload after " + settings.worlds().idleUnloadSeconds() + " s")
            + ")");
        return runtime;
    }

    public ProfileEngine engine() {
        return this.engine;
    }

    public ProfileSettings settings() {
        return this.settings;
    }

    public AuroraProfiles api() {
        return this.api;
    }

    public String nodeId() {
        return this.nodeId;
    }

    /** Drains queued profile I/O, then closes the engine. Never claims success it cannot prove. */
    public void close(final long budgetMillis) {
        if (this.closed) return;
        this.closed = true;
        if (this.idleSweeper != null) this.idleSweeper.shutdownNow();
        if (this.heartbeat != null) this.heartbeat.shutdownNow();
        final boolean drained = this.engine.drain(budgetMillis);
        if (!drained) {
            SourbyLogger.warn("AWF MultiProfile: " + budgetMillis + " ms elapsed with profile I/O still queued;"
                + " pending transitions remain in the journal");
        }
        this.engine.releaseSessionsNow();
        try {
            Bukkit.getServicesManager().unregister(AuroraProfiles.class, this.api());
        } catch (final RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: unregistering the service failed: " + failed.getMessage());
        }
        this.engine.close();
    }

    /** Releases the static handle; used by bootstrap and tests. */
    public static synchronized void stop(final long budgetMillis) {
        final ProfileRuntime runtime = running;
        running = null;
        if (runtime != null) runtime.close(budgetMillis);
    }

    private static String resolveNodeId() {
        return dev.iyanz.sourbycraft.awf.redis.fabric.FabricRuntime.nodeId();
    }

    /** Join/quit integration: recovery, session claim, save on the way out. */
    private final class Sessions implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(final PlayerJoinEvent event) {
            final UUID playerId = event.getPlayer().getUniqueId();
            ProfileRuntime.this.engine.recoverPending(playerId).exceptionally(failed -> {
                SourbyLogger.warn("AWF MultiProfile: recovery for " + playerId + " failed: "
                    + ProfileEngine.messageOf(failed));
                return 0;
            }).thenCompose(recovered -> ProfileRuntime.this.engine.active(playerId)).thenCompose(active -> {
                if (active.isEmpty()) return CompletableFuture.completedFuture(null);
                final UUID profileId = active.get().id();
                if (!active.get().worlds().isEmpty()) {
                    ProfileRuntime.this.engine.touch(active.get().worlds().getFirst());
                }
                return ProfileRuntime.this.engine.claimSession(playerId, profileId)
                    .thenAccept(claim -> {
                        if (!claim.granted() && ProfileRuntime.this.settings.coordination().singleActiveSession()) {
                            rejectDuplicate(event.getPlayer(), claim);
                        }
                    });
            }).exceptionally(failed -> {
                SourbyLogger.warn("AWF MultiProfile: session claim for " + playerId + " failed: "
                    + ProfileEngine.messageOf(failed));
                return null;
            });
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(final PlayerQuitEvent event) {
            final UUID playerId = event.getPlayer().getUniqueId();
            if (ProfileRuntime.this.engine.switchInProgress(playerId)) {
                // The switch already captured the previous profile; a second capture here would
                // write the target's state under the profile being left.
                ProfileRuntime.this.engine.releaseSessionAfterSwitch(playerId);
                return;
            }
            ProfileRuntime.this.engine.active(playerId).thenCompose(active -> {
                if (active.isEmpty()) return CompletableFuture.completedFuture(null);
                final UUID profileId = active.get().id();
                return ProfileRuntime.this.engine.save(profileId).handle((ignored, failed) -> {
                    if (failed != null) {
                        SourbyLogger.warn("AWF MultiProfile: saving profile " + profileId + " on quit failed: "
                            + ProfileEngine.messageOf(failed));
                    }
                    return null;
                });
            }).whenComplete((ignored, failed) -> ProfileRuntime.this.engine.releaseSession(playerId))
                .exceptionally(failed -> {
                    SourbyLogger.warn("AWF MultiProfile: quit handling for " + playerId + " failed: "
                        + ProfileEngine.messageOf(failed));
                    return null;
                });
        }

        /** A disabled plugin's adapters must not keep being called. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginDisable(final org.bukkit.event.server.PluginDisableEvent event) {
            final int removed = ProfileRuntime.this.adapters.unregisterAll(event.getPlugin());
            if (removed > 0) {
                SourbyLogger.info("AWF MultiProfile: removed " + removed + " state adapter(s) of "
                    + event.getPlugin().getName());
            }
        }

        private void rejectDuplicate(final Player player, final ClaimResult claim) {
            final String holder = claim.claim() == null ? "another node" : claim.claim().node();
            try {
                player.getScheduler().execute(ProfileRuntime.this.plugin, () -> player.kick(
                    net.kyori.adventure.text.Component.text("Your profile is active on " + holder
                        + ". Try again in a moment.")), null, 1L);
            } catch (final RuntimeException retired) {
                SourbyLogger.warn("AWF MultiProfile: could not reject a duplicate session for "
                    + player.getUniqueId() + ": " + retired.getMessage());
            }
        }
    }

    /** The {@link AuroraProfiles} implementation registered in the services manager. */
    private final class Api implements AuroraProfiles {

        private final ProfileEngine engine;
        private final ProfileStateAdapters adapters;
        private Api() {
            this.engine = ProfileRuntime.this.engine;
            this.adapters = ProfileRuntime.this.adapters;
        }

        @Override
        public boolean isEnabled() {
            return !ProfileRuntime.this.closed && this.engine.isOpen();
        }

        @Override
        public CompletableFuture<ProfileInfo> createProfile(final ProfileRequest request) {
            return this.engine.create(request);
        }

        @Override
        public CompletableFuture<List<ProfileInfo>> listProfiles(final UUID accountId) {
            return this.engine.list(accountId);
        }

        @Override
        public CompletableFuture<Optional<ProfileInfo>> getProfile(final UUID profileId) {
            return this.engine.get(profileId);
        }

        @Override
        public CompletableFuture<Boolean> deleteProfile(final UUID profileId) {
            return this.engine.delete(profileId);
        }

        @Override
        public CompletableFuture<Optional<ProfileInfo>> getActiveProfile(final UUID accountId) {
            return this.engine.active(accountId);
        }

        @Override
        public CompletableFuture<ProfileSwitchResult> switchProfile(final UUID playerId, final UUID profileId) {
            return this.engine.switchProfile(playerId, profileId);
        }

        @Override
        public CompletableFuture<Void> saveProfile(final UUID profileId) {
            return this.engine.save(profileId);
        }

        @Override
        public CompletableFuture<Void> bindProfileWorlds(
                final dev.iyanz.sourbycraft.api.profile.ProfileWorldBindingRequest request) {
            return this.engine.bindWorlds(request);
        }

        @Override
        public CompletableFuture<List<ProfileWorldRef>> getProfileWorlds(final UUID profileId) {
            return this.engine.worlds(profileId);
        }

        @Override
        public CompletableFuture<Optional<ProfileOwner>> resolveProfileOwnership(final UUID profileId) {
            return this.engine.owner(profileId);
        }

        @Override
        public void registerProfileStateAdapter(final Plugin owner, final ProfileStateAdapter adapter) {
            if (owner == null || !owner.isEnabled()) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST,
                    "the registering plugin is not enabled");
            }
            this.adapters.register(owner, adapter);
        }

        @Override
        public void unregisterProfileStateAdapter(final Plugin owner, final String adapterId) {
            this.adapters.unregister(owner, adapterId);
        }
    }
}
