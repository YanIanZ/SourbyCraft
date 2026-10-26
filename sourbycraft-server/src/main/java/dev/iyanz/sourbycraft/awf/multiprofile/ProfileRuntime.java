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
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
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
 * as saved. The server closes SourbyCraft services before it saves and removes players, so players
 * still online then get no quit snapshot; their active profile's live state stays in vanilla
 * playerdata, which the next switch captures.</p>
 */
public final class ProfileRuntime {

    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.yml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.yml");
    /** Lease of a local session claim; the heartbeat renews it well before it lapses. */
    private static final int LOCAL_LEASE_SECONDS = 60;

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
    /** The fabric this node advertises itself in, or {@code null} when routing is off. */
    private final RedisFabric routing;
    private volatile boolean closed;

    private ProfileRuntime(final Plugin plugin, final ProfileSettings settings, final ProfileEngine engine,
                           final ProfileStateAdapters adapters, final BukkitPlayerStatePort statePort,
                           final ScheduledExecutorService idleSweeper, final ScheduledExecutorService heartbeat,
                           final String nodeId, final RedisFabric routing) {
        this.plugin = plugin;
        this.settings = settings;
        this.engine = engine;
        this.adapters = adapters;
        this.statePort = statePort;
        this.idleSweeper = idleSweeper;
        this.heartbeat = heartbeat;
        this.nodeId = nodeId;
        this.routing = routing;
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

    /**
     * Refuses settings this build cannot run safely. Static and Bukkit-free so the refusal can be
     * checked without a server.
     *
     * <p>{@code repository: redis} and {@code mode: distributed} both need the running fabric, Redis
     * ownership and fencing: the Redis repository fences each write with the session claim it carries,
     * so a write without that claim kind, or unfenced, would bypass it. Distributed mode additionally
     * needs the Redis repository: the file repository compares revisions in one process only and cannot
     * fence a write with Redis ownership, so two servers must never share it.</p>
     *
     * @param fabricEnabled whether the Aurora Redis Fabric is running and enabled
     * @throws IllegalStateException naming the first setting that is refused
     */
    static void validate(final ProfileSettings settings, final boolean fabricEnabled) {
        if (!settings.distributed() && !settings.redisRepository()) return;
        final String what = settings.distributed() ? "mode: distributed" : "repository: redis";
        if (!fabricEnabled) {
            throw new IllegalStateException(what + " requires aurora.awf.redis.fabric.enabled: true and a reachable"
                + " Redis; refusing to start with local-only ownership");
        }
        if (settings.coordination().provider() != ProfileSettings.Provider.REDIS) {
            throw new IllegalStateException(what + " requires coordination.provider: redis");
        }
        if (!settings.coordination().fencingEnabled()) {
            throw new IllegalStateException(what + " requires coordination.fencing-enabled: true; without it a stale"
                + " owner could commit");
        }
        if (settings.distributed() && !settings.redisRepository()) {
            throw new IllegalStateException("mode: distributed requires repository: redis: the file repository cannot"
                + " atomically fence its writes with Redis ownership, so two servers must never share its directory."
                + " Use mode: standalone with a directory exclusive to this server, or repository: redis.");
        }
    }

    private static ProfileRuntime create(final Plugin plugin, final ProfileSettings settings) throws IOException {
        final String nodeId = resolveNodeId();
        final RedisFabric fabric = RedisFabric.running();
        validate(settings, fabric != null && fabric.settings().enabled());
        // The file repository takes the directory's exclusive lock: a second server on it fails here.
        // The Redis repository refuses a Redis it cannot run on safely (see RedisProfileRepository.open).
        final ProfileRepository repository = settings.redisRepository()
            ? RedisProfileRepository.open(fabric, settings.repository(), Path.of(settings.directory()))
            : new FileProfileRepository(Path.of(settings.directory()), nodeId);
        try {
            return create(plugin, settings, nodeId, fabric, repository);
        } catch (final IOException | RuntimeException failed) {
            repository.close();
            throw failed;
        }
    }

    private static ProfileRuntime create(final Plugin plugin, final ProfileSettings settings, final String nodeId,
                                         final RedisFabric fabric, final ProfileRepository repository)
        throws IOException {
        final ProfileOwnership ownership;
        final long leaseMillis;
        if (settings.coordination().provider() == ProfileSettings.Provider.REDIS) {
            if (fabric == null) throw new IllegalStateException("coordination.provider: redis requires the Aurora Redis Fabric");
            final RedisSettings redis = fabric.redisSettings();
            ownership = new RedisProfileOwnership(fabric.sessions(), nodeId, redis.timeoutMillis());
            leaseMillis = fabric.settings().sessions().ttlSeconds() * 1000L;
            if (!settings.distributed() && !settings.redisRepository()) {
                SourbyLogger.warn("AWF MultiProfile: coordination.provider: redis with mode: standalone and the file"
                    + " repository coordinates claims through Redis, but the profiles stay in this server's directory,"
                    + " which no second server may use (its lock refuses one). This is a single-node setup, not"
                    + " distributed mode.");
            }
        } else {
            ownership = new LocalProfileOwnership(nodeId, LOCAL_LEASE_SECONDS);
            leaseMillis = LOCAL_LEASE_SECONDS * 1000L;
        }
        if (settings.switching().timeoutMillis() >= leaseMillis) {
            SourbyLogger.warn("AWF MultiProfile: switching.timeout-ms (" + settings.switching().timeoutMillis()
                + " ms) is not below the claim lease (" + leaseMillis + " ms). The heartbeat renews the claims of a"
                + " running switch, but a recovery's claim is not renewed: a rollback that runs longer than the lease"
                + " loses it and its account write is refused (OWNERSHIP_CONFLICT). Keep timeout-ms below the lease.");
        }
        if (settings.distributed() || settings.redisRepository()) {
            SourbyLogger.warn("AWF MultiProfile: profiles in the shared Redis repository are EXPERIMENTAL and not"
                + " qualified on two nodes (owner test MP-010). A profile write made under a session claim is fenced in"
                + " Redis by that claim; creating a profile, slot limits and journal phases written before a switch holds"
                + " a claim are checked by revision or not at all. Not handled yet: a player's live state does not follow them to another server (a join"
                + " keeps that server's vanilla playerdata, only a switch applies a profile snapshot, and the next save"
                + " or quit snapshot stores that state), players online at shutdown get no quit snapshot, and no proxy"
                + " hands players between servers. A player who moves between servers can therefore lose progress.");
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
        final long fabricPeriod = Math.max(1L, fabric == null ? 10L : fabric.settings().sessions().heartbeatSeconds());
        // Session claims are leases under both providers. A Redis claim is renewed on the fabric's own
        // cadence, or it would expire after sessions.ttl-seconds and a second node could take the
        // profile over; a local claim is renewed at a quarter of its lease, because a save validates
        // the session claim and would refuse once the lease lapsed.
        final long renewPeriod = settings.coordination().provider() == ProfileSettings.Provider.REDIS
            ? fabricPeriod : LOCAL_LEASE_SECONDS / 4;
        final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "SourbyCraft-AWF-profile-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeats.scheduleWithFixedDelay(() -> engine.renewSessions().exceptionally(failed -> {
            SourbyLogger.warn("AWF MultiProfile: session renewal failed: " + ProfileEngine.messageOf(failed));
            return 0;
        }), renewPeriod, renewPeriod, TimeUnit.SECONDS);
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
            heartbeats.scheduleWithFixedDelay(advertise, fabricPeriod, fabricPeriod, TimeUnit.SECONDS);
        }
        final ProfileRuntime runtime = new ProfileRuntime(plugin, settings, engine, adapters, statePort, sweeper,
            heartbeats, nodeId, routing ? fabric : null);
        try {
            Bukkit.getServicesManager().register(AuroraProfiles.class, runtime.api(), plugin, ServicePriority.Normal);
            Bukkit.getPluginManager().registerEvents(runtime.new Sessions(), plugin);
            Bukkit.getPluginManager().registerEvents(runtime.new SwitchFreeze(), plugin);
        } catch (final RuntimeException failed) {
            // The caller closes the repository; the scheduled work must not outlive a failed start.
            if (sweeper != null) sweeper.shutdownNow();
            heartbeats.shutdownNow();
            throw failed;
        }
        SourbyLogger.info("AWF MultiProfile: enabled (" + settings.mode().name().toLowerCase(java.util.Locale.ROOT)
            + ", node " + nodeId + ", repository " + repository.describe() + ", ownership " + ownership.describe()
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

    /**
     * Copies the file repository of {@code aurora.awf.multiprofile.directory} into the running Redis
     * repository, once ({@link ProfileRepositoryMigration}); completes with what was copied and
     * verified. Runs on an administrative I/O thread. Fails when the running repository is the file
     * repository, when the Redis repository already holds data, or when the directory is in use by
     * another process. The directory is kept as it is.
     */
    public CompletableFuture<String> migrateFileRepository() {
        if (!(this.engine.repository() instanceof RedisProfileRepository redis)) {
            return CompletableFuture.failedFuture(new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST,
                "the running repository is " + this.engine.repository().describe() + "; set"
                    + " aurora.awf.multiprofile.repository: redis and restart before migrating into it"));
        }
        return dev.iyanz.sourbycraft.util.VirtualExecutor.supply(() -> {
            try (FileProfileRepository file = new FileProfileRepository(Path.of(this.settings.directory()), this.nodeId)) {
                final String summary = ProfileRepositoryMigration.fileToRedis(file, redis).summary();
                SourbyLogger.info("AWF MultiProfile: migrated " + file.root() + " into Redis: " + summary
                    + "; the directory was kept unchanged");
                return summary;
            }
        });
    }

    /** Drains queued profile I/O, then closes the engine. Never claims success it cannot prove. */
    public void close(final long budgetMillis) {
        if (this.closed) return;
        this.closed = true;
        if (this.idleSweeper != null) this.idleSweeper.shutdownNow();
        if (this.heartbeat != null) this.heartbeat.shutdownNow();
        if (this.routing != null) {
            // Best effort: the entry also expires on its own TTL. Proxies and plugins reading the node
            // directory stop routing here as soon as it is gone.
            try {
                this.routing.routing().unregister(this.nodeId);
            } catch (final IOException | RuntimeException failed) {
                SourbyLogger.warn("AWF MultiProfile: removing this node from the routing directory failed ("
                    + failed.getMessage() + "); its entry expires on its own");
            }
        }
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
        int online = 0;
        try {
            online = Bukkit.getOnlinePlayers().size();
        } catch (final RuntimeException unavailable) {
            // Only the notice below depends on it.
        }
        if (online > 0) {
            SourbyLogger.info("AWF MultiProfile: closed with " + online + " player(s) online; they get no quit"
                + " snapshot, and their active profile's live state stays in vanilla playerdata");
        }
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

    /** Join/quit integration: recovery, session claim, the active profile's worlds, save on the way out. */
    private final class Sessions implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(final PlayerJoinEvent event) {
            final UUID playerId = event.getPlayer().getUniqueId();
            // Read here, on the thread that owns the player: the world the join placed them in.
            final String joinedWorld = event.getPlayer().getWorld().getName();
            // A quit during a switch asked for the session claim to be released when the switch
            // ends; the player is back, so the claim the switch leaves behind is theirs again.
            ProfileRuntime.this.engine.keepSessionAfterSwitch(playerId);
            ProfileRuntime.this.engine.recoverPending(playerId).exceptionally(failed -> {
                SourbyLogger.warn("AWF MultiProfile: recovery for " + playerId + " failed: "
                    + ProfileEngine.messageOf(failed));
                return 0;
            }).thenCompose(recovered -> ProfileRuntime.this.engine.active(playerId)).thenCompose(active -> {
                if (active.isEmpty()) return CompletableFuture.completedFuture(null);
                final UUID profileId = active.get().id();
                return ProfileRuntime.this.engine.claimSession(playerId, profileId)
                    .thenCompose(claim -> {
                        if (claim.granted() && !ProfileRuntime.this.statePort.online(playerId)) {
                            // The player quit while recovery and the claim ran; their quit found no
                            // session to release, so this one is released here instead of lingering.
                            ProfileRuntime.this.engine.releaseSession(playerId);
                            return CompletableFuture.completedFuture(null);
                        }
                        if (!claim.granted()) {
                            if (ProfileRuntime.this.settings.coordination().singleActiveSession()) {
                                rejectDuplicate(event.getPlayer(), claim);
                            }
                            return CompletableFuture.completedFuture(null);
                        }
                        // Profile worlds are not loaded at startup: load the active profile's worlds and
                        // bring the player back into them when the join placed them elsewhere.
                        return ProfileRuntime.this.engine.prepareActive(playerId, joinedWorld).thenAccept(moved -> {
                            if (moved) {
                                SourbyLogger.info("AWF MultiProfile: returned " + playerId
                                    + " to the worlds of their active profile after joining");
                            }
                        });
                    });
            }).exceptionally(failed -> {
                SourbyLogger.warn("AWF MultiProfile: session claim for " + playerId + " failed: "
                    + ProfileEngine.messageOf(failed));
                return null;
            });
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(final PlayerQuitEvent event) {
            final Player player = event.getPlayer();
            final UUID playerId = player.getUniqueId();
            final ProfileEngine engine = ProfileRuntime.this.engine;
            if (engine.switchInProgress(playerId)) {
                // A switch already captured the previous profile; a second capture here would write
                // the target's state under the profile being left. A recovery or the teleport of a join
                // preparation refuses the save as well; the live state stays in vanilla playerdata.
                engine.releaseSessionAfterSwitch(playerId);
                return;
            }
            if (!engine.isOpen() || !engine.holdsSession(playerId)) {
                // Closed: shutdown closes MultiProfile before the server removes players. No session
                // claim: the join found no active profile to claim. Either way nothing is written.
                return;
            }
            // Captured here, on the thread that owns the player, while the player still exists; an
            // entity task scheduled from here would run after the player was removed.
            ProfileState state = null;
            try {
                state = ProfileRuntime.this.statePort.captureOnOwnerThread(player, ProfileRuntime.this.settings.isolation());
            } catch (final RuntimeException failed) {
                SourbyLogger.warn("AWF MultiProfile: capturing " + playerId + " on quit failed (" + failed.getMessage()
                    + "); no quit snapshot was written");
            }
            final CompletableFuture<Boolean> saved = state == null
                ? CompletableFuture.completedFuture(false) : engine.saveCaptured(playerId, state);
            saved.handle((written, failed) -> {
                if (failed != null && engine.isOpen()) {
                    SourbyLogger.warn("AWF MultiProfile: saving the active profile of " + playerId + " on quit failed: "
                        + ProfileEngine.messageOf(failed));
                }
                return null;
            }).thenCompose(ignored -> engine.releaseSession(playerId))
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

    /**
     * Cancels the item, experience and interaction events of a player a running switch froze: between
     * its capture and its commit or rollback, a pickup would be lost and an item dropped, placed,
     * consumed or put into a container would exist twice once the target state is applied. The engine
     * unfreezes on every exit path of the switch. Handlers run on the player's thread and only read a
     * concurrent set. Changes that fire none of these events (damage, hunger, commands or plugins that
     * edit an inventory directly) are not held back.
     */
    private final class SwitchFreeze implements Listener {

        private boolean frozen(final Object entity) {
            return entity instanceof Player player && ProfileRuntime.this.engine.frozen(player.getUniqueId());
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onDrop(final PlayerDropItemEvent event) {
            if (frozen(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onPickup(final EntityPickupItemEvent event) {
            if (frozen(event.getEntity())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onInventoryClick(final InventoryClickEvent event) {
            if (frozen(event.getWhoClicked())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onInventoryDrag(final InventoryDragEvent event) {
            if (frozen(event.getWhoClicked())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onInteract(final PlayerInteractEvent event) {
            if (!frozen(event.getPlayer())) return;
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onInteractEntity(final PlayerInteractEntityEvent event) {
            if (frozen(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onPlace(final BlockPlaceEvent event) {
            if (frozen(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onConsume(final PlayerItemConsumeEvent event) {
            if (frozen(event.getPlayer())) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onSwapHands(final PlayerSwapHandItemsEvent event) {
            if (frozen(event.getPlayer())) event.setCancelled(true);
        }

        /** Experience is part of the profile too; the orb stays and is picked up after the switch. */
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onPickupExperience(final com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent event) {
            if (frozen(event.getPlayer())) event.setCancelled(true);
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
        public CompletableFuture<Void> setProfileLocked(final UUID profileId, final boolean locked) {
            return this.engine.setLocked(profileId, locked);
        }

        @Override
        public CompletableFuture<Integer> setProfileSlotLimit(final UUID accountId, final int slots) {
            return this.engine.setSlotLimit(accountId, slots);
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
