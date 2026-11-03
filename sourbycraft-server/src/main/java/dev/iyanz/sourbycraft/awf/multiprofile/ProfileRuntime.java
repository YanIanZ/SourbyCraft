package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.AuroraProfiles;
import dev.iyanz.sourbycraft.api.profile.CoopDetails;
import dev.iyanz.sourbycraft.api.profile.CoopInviteInfo;
import dev.iyanz.sourbycraft.api.profile.CoopKickVoteInfo;
import dev.iyanz.sourbycraft.api.profile.CoopMemberInfo;
import dev.iyanz.sourbycraft.api.profile.CoopPage;
import dev.iyanz.sourbycraft.api.profile.ProfileDetails;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileOwner;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileStateAdapter;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import dev.iyanz.sourbycraft.api.profile.ProfileWorldRef;
import dev.iyanz.sourbycraft.api.event.PlayerSaveEvent;
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
import java.util.Objects;
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
 * <p>Shutdown (Build 53, PM-076): the server hands the online players to MultiProfile before it saves and
 * removes them ({@link ProfileShutdownHook}): while the plugins are still enabled every registered adapter is
 * captured per player ({@link #captureAdaptersAtShutdown}); after they are disabled each player's core state is
 * captured on the shutdown thread, merged with those adapter blobs and saved under the session's capture ticket
 * ({@link #captureAllAtShutdown}), within {@code shutdown.capture-deadline-ms}. A player not saved by the deadline
 * keeps their last committed snapshot and any pending intent, each logged, and shutdown continues. Only then does
 * {@link #close(long)} drain the engine's queued I/O within a budget before the Resource Governor storage lane is
 * shut down; a drain that times out is logged as incomplete, never as saved.</p>
 *
 * <p>Login (Build 53): while authoritative login applies ({@code login.authoritative} {@code auto} = co-op enabled,
 * {@code true}, or {@code false}) a join on a format-2 store is gated until the committed member head of the active
 * profile was restored onto the player, or the vanilla playerdata was adopted once after an unclean session
 * ({@link ProfileEngine#restoreOnLogin}); every path that cannot do either refuses the join (the gate stays until the
 * disconnect is final, so nothing of the vanilla state is captured) instead of loading vanilla playerdata. While it
 * applies, every vanilla player save of the server ({@link PlayerSaveEvent}, autosave) publishes a profile snapshot in
 * lockstep ({@link Sessions#onPlayerSaved}), and a capture or save that cannot succeed freezes and disconnects the
 * player ({@link ProfileEngine#refuseSaves}).</p>
 *
 * <p>Store format (Build 51): before the service and listeners are registered, the engine reads the
 * store's format marker on the storage lane ({@link ProfileEngine#open}). A store this build refuses
 * (unknown format, newer schema, unfinished migration) stops MultiProfile with an ERROR naming the format
 * and owner decision OD-2 (restore the pre-migration backup; no in-place downgrade). A format-1 store is
 * migrated per {@code migration.mode} (RESTART_REQUIRED): {@code dry-run} logs a report and writes
 * nothing, {@code apply} backs up and migrates before any player is admitted and refuses to start on
 * failure. On a format-2 store every join first acquires the player's session claim {@code Q}; a player
 * whose claim another live session holds is disconnected.</p>
 */
public final class ProfileRuntime {

    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.yml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.yml");
    /** Lease of a local session claim; the heartbeat renews it well before it lapses. */
    private static final int LOCAL_LEASE_SECONDS = 60;
    /** Longest the boot waits for the store format check, intent recovery and an applied migration. */
    private static final long STARTUP_BUDGET_MINUTES = 30L;

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
    private final ProfileEngine.StoreMigration migration;
    private volatile boolean closed;

    private ProfileRuntime(final Plugin plugin, final ProfileSettings settings, final ProfileEngine engine,
                           final ProfileStateAdapters adapters, final BukkitPlayerStatePort statePort,
                           final ScheduledExecutorService idleSweeper, final ScheduledExecutorService heartbeat,
                           final String nodeId, final RedisFabric routing, final ProfileEngine.StoreMigration migration) {
        this.plugin = plugin;
        this.settings = settings;
        this.engine = engine;
        this.adapters = adapters;
        this.statePort = statePort;
        this.idleSweeper = idleSweeper;
        this.heartbeat = heartbeat;
        this.nodeId = nodeId;
        this.routing = routing;
        this.migration = migration;
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
        final PlayerSessionOwnership playerSessions;
        final long leaseMillis;
        if (settings.coordination().provider() == ProfileSettings.Provider.REDIS) {
            if (fabric == null) throw new IllegalStateException("coordination.provider: redis requires the Aurora Redis Fabric");
            final RedisSettings redis = fabric.redisSettings();
            final RedisProfileOwnership redisOwnership = new RedisProfileOwnership(fabric.sessions(), nodeId, redis.timeoutMillis());
            ownership = redisOwnership;
            playerSessions = PlayerSessionOwnership.redis(redisOwnership.coopClaims(), nodeId);
            leaseMillis = fabric.settings().sessions().ttlSeconds() * 1000L;
            if (!settings.distributed() && !settings.redisRepository()) {
                SourbyLogger.warn("AWF MultiProfile: coordination.provider: redis with mode: standalone and the file"
                    + " repository coordinates claims through Redis, but the profiles stay in this server's directory,"
                    + " which no second server may use (its lock refuses one). This is a single-node setup, not"
                    + " distributed mode.");
            }
        } else {
            ownership = new LocalProfileOwnership(nodeId, LOCAL_LEASE_SECONDS);
            playerSessions = PlayerSessionOwnership.local(nodeId, LOCAL_LEASE_SECONDS);
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
                + " a claim are checked by revision or not at all. Not handled: no proxy hands players between servers, and"
                + " without login.authoritative a join keeps that server's vanilla playerdata (only a switch applies a profile"
                + " snapshot); with it the join restores the committed member head. A player who moves between servers"
                + " without authoritative login can therefore lose progress.");
        }
        if (repository instanceof FileProfileRepository file) {
            // Format-2 FILE commits validate the claims they carry at commit time; until a validator is attached,
            // every claim-bearing write is refused (OWNERSHIP_CONFLICT), so it is attached before the engine opens.
            file.attachClaims(FileProfileClaims.of(ownership, playerSessions));
            file.attachDefaultSlots(settings.defaultSlots());
        } else if (repository instanceof RedisProfileRepository redis) {
            redis.attachDefaultSlots(settings.defaultSlots());
        }
        final ProfileStateAdapters adapters = new ProfileStateAdapters(settings.adapters().required());
        final BukkitPlayerStatePort statePort = new BukkitPlayerStatePort(plugin, adapters);
        final AwfProfileWorldPort worldPort = new AwfProfileWorldPort(
            dev.iyanz.sourbycraft.awf.world.AuroraWorldsRuntime::service);
        final EventFabric events = fabric != null && fabric.settings().services().events() ? fabric.events() : null;
        final ProfileEngine engine = new ProfileEngine(settings, repository, ownership, playerSessions, statePort, worldPort,
            nodeId, ResourceGovernor.GLOBAL.lane(ResourceGovernor.Lane.STORAGE), fabric == null ? null : fabric.metrics(),
            events);
        // The store format decides every path: read it (and migrate, and resolve pending intents) on the storage
        // lane before anything is scheduled or registered, so no player is admitted before it is decided.
        final ProfileEngine.StoreMigration migration = storeMigration(settings);
        final ProfileEngine.StoreStatus store;
        try {
            store = engine.open(migration).get(STARTUP_BUDGET_MINUTES, TimeUnit.MINUTES);
        } catch (final java.util.concurrent.ExecutionException failed) {
            closeQuietly(ownership, playerSessions);
            throw new IllegalStateException(ProfileEngine.messageOf(failed.getCause()), failed.getCause());
        } catch (final java.util.concurrent.TimeoutException slow) {
            closeQuietly(ownership, playerSessions);
            throw new IllegalStateException("the profile store check did not finish within " + STARTUP_BUDGET_MINUTES
                + " minutes; MultiProfile did not start");
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeQuietly(ownership, playerSessions);
            throw new IllegalStateException("interrupted while checking the profile store");
        }
        SourbyLogger.info("AWF MultiProfile: profile store " + ProfileEngine.describe(store.marker()) + " (" + store.reason()
            + "); migration " + store.migration() + (store.unresolvedIntents().isEmpty() ? ""
                : "; " + store.unresolvedIntents().size() + " intent(s) left pending for recovery"));
        engine.onSessionLost(playerId -> disconnect(plugin, playerId,
            "Your profile session was taken over or expired. Rejoin to continue."));
        engine.onPlayerNotice((playerId, text) -> message(plugin, playerId, text));
        engine.onSaveRefused((playerId, reason) -> disconnect(plugin, playerId, "Your profile state could not be saved ("
            + reason + "). You were disconnected so nothing is lost or duplicated; contact the server staff if this repeats."));
        engine.storeWriter(writtenBy());
        // Co-op events are asynchronous: the engine calls this on its own event thread, after the durable commit.
        engine.onCoopEvent(event -> Bukkit.getPluginManager().callEvent(event));
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
            heartbeats, nodeId, routing ? fabric : null, migration);
        try {
            Bukkit.getServicesManager().register(AuroraProfiles.class, runtime.api(), plugin, ServicePriority.Normal);
            Bukkit.getPluginManager().registerEvents(runtime.new Sessions(), plugin);
            Bukkit.getPluginManager().registerEvents(runtime.new SwitchFreeze(), plugin);
            // PM-076: the server hands the online players to these before it saves and removes them.
            ProfileShutdownHook.registerAdapterCapture(runtime.shutdownAdapterCapture);
            ProfileShutdownHook.register(runtime.shutdownCapture);
        } catch (final RuntimeException failed) {
            // The caller closes the repository; the scheduled work must not outlive a failed start.
            if (sweeper != null) sweeper.shutdownNow();
            heartbeats.shutdownNow();
            ProfileShutdownHook.unregisterAdapterCapture(runtime.shutdownAdapterCapture);
            ProfileShutdownHook.unregister(runtime.shutdownCapture);
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

    /** What the startup store check decided, for {@code /awf multiprofile format}. */
    public ProfileEngine.StoreStatus storeStatus() {
        return this.engine.storeStatus();
    }

    /** Runs the format-2 migration report now, writing nothing ({@code /awf multiprofile migrate dry-run}). */
    public CompletableFuture<String> migrationDryRun() {
        return this.engine.migrationDryRun(this.migration);
    }

    private static void closeQuietly(final ProfileOwnership ownership, final PlayerSessionOwnership playerSessions) {
        try {
            ownership.close();
        } catch (final RuntimeException ignored) {
            // The start already failed; the cause is reported by the caller.
        }
        try {
            playerSessions.close();
        } catch (final RuntimeException ignored) {
            // As above.
        }
    }

    /** Sends a message to an online player through their entity scheduler; does nothing for a player who left. */
    private static void message(final Plugin plugin, final UUID playerId, final String text) {
        try {
            final Player player = Bukkit.getPlayer(playerId);
            if (player == null) return;
            player.getScheduler().execute(plugin, () -> player.sendMessage(net.kyori.adventure.text.Component.text(text)), null, 1L);
        } catch (final RuntimeException retired) {
            SourbyLogger.warn("AWF MultiProfile: could not message " + playerId + ": " + retired.getMessage());
        }
    }

    /** How often a refused join is kicked again when a plugin cancels the kick before giving up. */
    static final int KICK_ATTEMPTS = 5;
    /** Ticks between two kick attempts: long enough for an accepted kick to complete, so it is not sent twice. */
    private static final long KICK_RETRY_TICKS = 5L;

    /** Disconnects an online player through their entity scheduler; does nothing for a player who left. */
    private static void disconnect(final Plugin plugin, final UUID playerId, final String message) {
        final Player player = Bukkit.getPlayer(playerId);
        if (player != null) disconnect(plugin, player, message, KICK_ATTEMPTS);
    }

    /**
     * Kicks exactly {@code player} (not a later connection of the same account) on its entity thread; while the player
     * is still online {@value #KICK_RETRY_TICKS} ticks later (a plugin cancelled the kick), tries again, {@code attempts}
     * times in all (review P3-11). A player still online afterwards stays frozen with their saves refused until they
     * quit, logged once.
     */
    private static void disconnect(final Plugin plugin, final Player player, final String message, final int attempts) {
        try {
            player.getScheduler().execute(plugin, () -> {
                if (!player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player) return;
                player.kick(net.kyori.adventure.text.Component.text(message));
                player.getScheduler().execute(plugin, () -> {
                    if (!player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player) return;
                    if (attempts > 1) {
                        disconnect(plugin, player, message, attempts - 1);
                    } else {
                        SourbyLogger.warn("AWF MultiProfile: " + player.getUniqueId() + " could not be disconnected in " + KICK_ATTEMPTS
                            + " attempts (a plugin cancels the kick); they stay frozen and their profile saves stay refused until they quit");
                    }
                }, null, KICK_RETRY_TICKS);
            }, null, 1L);
        } catch (final RuntimeException retired) {
            SourbyLogger.warn("AWF MultiProfile: could not disconnect " + player.getUniqueId() + ": " + retired.getMessage());
        }
    }

    /**
     * The format-1 to format-2 migration the engine runs at startup ({@code migration.mode}) and for
     * {@code /awf multiprofile migrate dry-run}. {@code apply} first resolves {@code migration.backup-dir} to a
     * real path and refuses one that overlaps the store directory (the settings check is textual only).
     */
    private static ProfileEngine.StoreMigration storeMigration(final ProfileSettings settings) {
        return (repository, apply) -> {
            final Path backup = apply ? backupDirectory(settings) : null;
            return formatTwoMigration(repository, backup, apply, writtenBy());
        };
    }

    /**
     * {@link ProfileRepositoryMigration#toFormat2}: {@code apply == false} runs it in {@code dry-run} mode (reads
     * and reports only, whatever {@code migration.mode} says); {@code apply == true} runs {@code apply} with the
     * resolved {@code backup} directory: the store's writer check, the verified backup, the IN_PROGRESS marker,
     * conversion, verification, READY. A refused or failed apply throws (the engine then does not start); a
     * refused dry-run is reported in the summary. The summary is logged either way.
     */
    private static ProfileEngine.MigrationResult formatTwoMigration(final ProfileRepository repository, final Path backup,
                                                                    final boolean apply, final String writtenBy)
        throws IOException {
        final ProfileSettings.Migration mode = apply
            ? new ProfileSettings.Migration(ProfileSettings.MigrationMode.APPLY, backup.toString())
            : new ProfileSettings.Migration(ProfileSettings.MigrationMode.DRY_RUN, "");
        final ProfileRepositoryMigration.Format2Result result = ProfileRepositoryMigration.toFormat2(repository, mode, writtenBy);
        SourbyLogger.info("AWF MultiProfile: " + result.summary());
        final boolean applied = result.outcome() == ProfileRepositoryMigration.Format2Result.Outcome.MIGRATED;
        if (apply && !applied) {
            throw new IOException(result.summary());
        }
        return new ProfileEngine.MigrationResult(applied, result.summary());
    }

    /**
     * {@code migration.backup-dir} resolved against the server directory, with symbolic links of its existing
     * part resolved, refused when it is inside the store directory or contains it.
     */
    static Path backupDirectory(final ProfileSettings settings) throws IOException {
        final String configured = settings.migration().backupDir();
        if (configured.isBlank()) throw new IOException("migration.mode apply requires migration.backup-dir");
        final Path store = realOrNormal(Path.of(settings.directory()));
        final Path backup = realOrNormal(Path.of(configured));
        if (backup.startsWith(store) || store.startsWith(backup)) {
            throw new IOException("migration.backup-dir " + backup + " overlaps the profile directory " + store
                + "; choose a directory outside it");
        }
        return backup;
    }

    /** The real path of the longest existing prefix of {@code path}, with the rest appended and normalised. */
    private static Path realOrNormal(final Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !java.nio.file.Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return absolute;
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
    }

    private static String writtenBy() {
        String identity;
        try {
            identity = "SourbyCraft " + dev.iyanz.sourbycraft.brand.BuildInfo.load().releaseIdentity();
        } catch (final RuntimeException unavailable) {
            identity = "SourbyCraft";
        }
        return identity.length() <= 64 ? identity : identity.substring(0, 64);
    }

    /**
     * Copies the file repository of {@code aurora.awf.multiprofile.directory} into the running Redis
     * repository, once ({@link ProfileRepositoryMigration}); completes with what was copied and
     * verified. Runs on an administrative I/O thread. Fails when the running repository is the file
     * repository, when the Redis repository already holds data, or when the directory is in use by
     * another process. The directory is kept as it is.
     */
    public CompletableFuture<String> migrateFileRepository() {
        if (this.engine.format2()) {
            return CompletableFuture.failedFuture(new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST,
                "the running store is format 2; the file-to-Redis copy only handles format-1 stores"));
        }
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
        ProfileShutdownHook.unregisterAdapterCapture(this.shutdownAdapterCapture);
        ProfileShutdownHook.unregister(this.shutdownCapture);
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
        // The shutdown saves phase two queued may still be on the lane: wait for them for whatever is left of the configured
        // capture deadline (one deadline for the whole shutdown capture, review P2-9); without a shutdown capture (tests,
        // a runtime stopped by hand) the caller's budget applies.
        final long deadline = this.shutdownDeadlineNanos;
        final long drainMillis = deadline == 0L ? budgetMillis : Math.max(0L, (deadline - System.nanoTime()) / 1_000_000L);
        final boolean drained = this.engine.drain(drainMillis);
        if (!drained) {
            SourbyLogger.warn("AWF MultiProfile: " + drainMillis + " ms elapsed with profile I/O still queued;"
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
            final ShutdownCapture capture = this.shutdownOutcome;
            SourbyLogger.info("AWF MultiProfile: closed with " + online + " player(s) online; " + (capture == null
                ? "the shutdown capture did not run before this close, so they get no quit snapshot and their active"
                    + " profile's live state stays in vanilla playerdata"
                : "the shutdown capture saved " + capture.saved() + ", skipped " + capture.skipped() + " (no active profile or"
                    + " a running switch), failed " + capture.failed() + " and left " + capture.unfinished() + " unfinished at the"
                    + " deadline; unsaved players keep their last committed snapshot and pending intents"));
        }
    }

    // ------------------------------------------------------------------ shutdown capture (Build 53, PM-076)

    /** What {@link #captureAllAtShutdown} did, for the close() notice. */
    record ShutdownCapture(int saved, int skipped, int failed, int unfinished) {}

    private volatile ShutdownCapture shutdownOutcome;
    /** Adapter blobs captured per player while the plugins were still enabled; merged by the final capture. */
    private final ConcurrentHashMap<UUID, ProfileStateAdapters.Capture> stagedAdapters = new ConcurrentHashMap<>();
    /** Players whose required adapter failed the pre-capture: their shutdown save is skipped, the last committed snapshot stays. */
    private final ConcurrentHashMap<UUID, String> stagedFailures = new ConcurrentHashMap<>();
    private final java.util.function.LongConsumer shutdownAdapterCapture = this::captureAdaptersAtShutdown;
    private final java.util.function.LongConsumer shutdownCapture = this::captureAllAtShutdown;

    /** How long of the capture budget {@link #captureAdaptersAtShutdown} may use; the rest stays for the saves. */
    private static final double ADAPTER_PHASE_SHARE = 0.25;
    /** One adapter capture at shutdown taking longer than this is named in the log (the thread cannot be bounded, P3-13). */
    private static final long SLOW_ADAPTER_MILLIS = 500L;
    /** When the shutdown capture (phase one) began; phase two's deadline is this plus the budget: one budget, split once (P2-9). */
    private volatile long shutdownStartedNanos;
    /** The configured capture deadline, for {@link #close} to wait out the saves phase two left on the lane. */
    private volatile long shutdownDeadlineNanos;

    /**
     * Phase one of the shutdown capture, on the region shutdown thread while the plugins are still enabled: every
     * registered adapter of every online player is captured into a staging map, exactly as a live capture would
     * (best-effort failures preserved, nothing cleared without being asked). A required adapter that fails marks
     * the player: their shutdown save is skipped and they keep their last committed snapshot (never a snapshot
     * that drops required data), logged. Bounded by a quarter of the budget: players not reached in time have no
     * staged blobs, so their final capture carries the stored adapter bytes forward unchanged.
     */
    void captureAdaptersAtShutdown(final long budgetMillis) {
        if (this.closed || !this.engine.isOpen()) return;
        final long started = System.nanoTime();
        this.shutdownStartedNanos = started;
        this.shutdownDeadlineNanos = started + Math.max(1L, budgetMillis) * 1_000_000L;
        final long deadline = started + (long) (Math.max(1L, budgetMillis) * ADAPTER_PHASE_SHARE) * 1_000_000L;
        int staged = 0;
        int notReached = 0;
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (System.nanoTime() >= deadline) {
                notReached++;
                continue;
            }
            final UUID playerId = player.getUniqueId();
            try {
                if (!Bukkit.isOwnedByCurrentRegion(player)) {
                    notReached++;
                    continue;
                }
                final long before = System.nanoTime();
                this.stagedAdapters.put(playerId, this.statePort.captureAdaptersOnOwnerThread(player));
                final long tookMillis = (System.nanoTime() - before) / 1_000_000L;
                if (tookMillis > SLOW_ADAPTER_MILLIS) {
                    // The adapter contract is synchronous; a capture that waits cannot be interrupted here, only named.
                    SourbyLogger.warn("AWF MultiProfile: the adapter capture of " + playerId + " at shutdown took " + tookMillis
                        + " ms; a plugin adapter that waits on a scheduler or I/O delays shutdown (see ProfileStateAdapter)");
                }
                staged++;
            } catch (final ProfileOperationException required) {
                this.stagedFailures.put(playerId, required.getMessage());
                SourbyLogger.error("AWF MultiProfile: shutdown capture of " + playerId + ": " + required.getMessage()
                    + "; their shutdown save is skipped and their last committed snapshot stays");
            } catch (final RuntimeException failed) {
                notReached++;
                SourbyLogger.warn("AWF MultiProfile: capturing the adapters of " + playerId + " at shutdown failed ("
                    + failed.getMessage() + "); their stored adapter bytes are carried forward unchanged");
            }
        }
        if (staged > 0 || notReached > 0) {
            SourbyLogger.info("AWF MultiProfile: shutdown adapter capture staged " + staged + " player(s)" + (notReached == 0 ? ""
                : "; " + notReached + " not reached within the budget (their stored adapter bytes are kept as they are)"));
        }
    }

    /**
     * Phase two of the shutdown capture, on the region shutdown thread after the plugins were disabled and before the
     * server removes the players (PM-076): every online player with an active profile is captured directly on this
     * thread (it owns every player once the region scheduler halted), merged with the adapter blobs phase one staged,
     * and saved under their session's capture ticket, exactly like a quit. The saves run on the storage lane; this
     * thread waits for all of them until the budget ends. A player whose save did not finish by then is logged and
     * left to recovery: their last committed snapshot stays authoritative and any pending intent is retained (the
     * engine's close does not abort intents). Players mid-switch or mid-login keep that flow's outcome and are skipped.
     */
    void captureAllAtShutdown(final long budgetMillis) {
        if (this.closed || !this.engine.isOpen()) return;
        final long started = System.nanoTime();
        // One budget for both phases (review P2-9): phase one started the clock when it ran; otherwise it starts here.
        final long phaseOne = this.shutdownStartedNanos;
        final long deadlineNanos = phaseOne != 0L && phaseOne <= started && started - phaseOne < Math.max(1L, budgetMillis) * 1_000_000L
            ? phaseOne + Math.max(1L, budgetMillis) * 1_000_000L : started + Math.max(1L, budgetMillis) * 1_000_000L;
        this.shutdownDeadlineNanos = deadlineNanos;
        final ProfileEngine engine = this.engine;
        final java.util.List<CompletableFuture<Boolean>> saves = new java.util.ArrayList<>();
        final java.util.List<UUID> players = new java.util.ArrayList<>();
        int skipped = 0;
        int failed = 0;
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final UUID playerId = player.getUniqueId();
            if (this.stagedFailures.containsKey(playerId)) {
                failed++;
                continue;
            }
            if (engine.switchInProgress(playerId)) {
                skipped++;
                continue;
            }
            final ProfileEngine.CaptureTicket ticket = engine.captureTicket(playerId);
            if (engine.format2() && (ticket == null || ticket.profileId() == null)) {
                skipped++;
                continue;
            }
            try {
                // The region shutdown thread owns every player once the scheduler halted (the port refuses otherwise, P3-12).
                final PlayerStatePort.CaptureResult captured = this.statePort.captureOnOwnerThreadDetailed(player, this.settings.isolation(),
                    this.stagedAdapters.get(playerId));
                saves.add(engine.saveCapturedAndClose(playerId, captured, ticket));
                players.add(playerId);
            } catch (final RuntimeException refused) {
                failed++;
                SourbyLogger.warn("AWF MultiProfile: capturing " + playerId + " at shutdown failed (" + ProfileEngine.messageOf(refused)
                    + "); no quit snapshot, their last committed snapshot stays and their session marker stays open");
            }
        }
        int saved = 0;
        int unfinished = 0;
        for (int i = 0; i < saves.size(); i++) {
            final CompletableFuture<Boolean> save = saves.get(i);
            final UUID playerId = players.get(i);
            final long remaining = deadlineNanos - System.nanoTime();
            try {
                if (remaining <= 0L && !save.isDone()) throw new java.util.concurrent.TimeoutException();
                final boolean written = save.get(Math.max(1L, remaining), TimeUnit.NANOSECONDS);
                if (written) {
                    saved++;
                } else {
                    skipped++;
                }
            } catch (final java.util.concurrent.TimeoutException notYet) {
                unfinished++;
                SourbyLogger.warn("AWF MultiProfile: the shutdown save of " + playerId + " did not finish within "
                    + budgetMillis + " ms; it may still complete on the storage lane during the drain, otherwise their last"
                    + " committed snapshot stays and any pending intent is retained for recovery");
            } catch (final java.util.concurrent.ExecutionException refused) {
                failed++;
                SourbyLogger.warn("AWF MultiProfile: the shutdown save of " + playerId + " failed: "
                    + ProfileEngine.messageOf(refused.getCause()) + "; their last committed snapshot stays");
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                unfinished += saves.size() - i;
                break;
            }
        }
        this.stagedAdapters.clear();
        this.stagedFailures.clear();
        this.shutdownOutcome = new ShutdownCapture(saved, skipped, failed, unfinished);
        SourbyLogger.info("AWF MultiProfile: shutdown capture saved " + saved + " player(s), skipped " + skipped + ", failed " + failed
            + ", unfinished at the deadline " + unfinished + " (" + (System.nanoTime() - started) / 1_000_000L + " ms of " + budgetMillis + ")");
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

    /** Players whose quit listener ran; the server's own player save that follows it must not capture again. */
    private final java.util.Set<UUID> quitting = ConcurrentHashMap.newKeySet();
    /** Players with a lockstep save queued and not yet settled (at most one pending autosave per player, D2). */
    private final java.util.Set<UUID> autosavePending = ConcurrentHashMap.newKeySet();

    /** Join/quit integration: recovery, session claim, the active profile's worlds, save on the way out, lockstep autosave. */
    private final class Sessions implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(final PlayerJoinEvent event) {
            final Player player = event.getPlayer();
            final UUID playerId = player.getUniqueId();
            ProfileRuntime.this.quitting.remove(playerId);
            ProfileRuntime.this.autosavePending.remove(playerId);
            // Read here, on the thread that owns the player: the world the join placed them in.
            final String joinedWorld = player.getWorld().getName();
            // A quit during a switch asked for the session claim to be released when the switch
            // ends; the player is back, so the claim the switch leaves behind is theirs again.
            ProfileRuntime.this.engine.keepSessionAfterSwitch(playerId);
            // Build 53 authoritative login: from here until the committed state was restored, the player's item
            // events are cancelled and nothing of theirs is captured or saved (the join loaded vanilla playerdata).
            final UUID gate = ProfileRuntime.this.engine.gateLogin(playerId);
            final Join join = new Join(event, gate, joinedWorld);
            // Format 2: the player's session claim Q comes first, before recovery, capture or restore. A format-1
            // store admits at once.
            ProfileRuntime.this.engine.admitSession(playerId).handle((admission, failed) -> {
                if (failed != null) {
                    join.refuse(ProfileEngine.codeOf(failed), "admitting the session failed: " + ProfileEngine.messageOf(failed),
                        "Your profile session could not be started (" + ProfileEngine.codeOf(failed) + "). Try again in a moment.");
                    return false;
                }
                if (!admission.admitted() && ProfileEngine.OFFLINE.equals(admission.reason())) {
                    // The player quit while the admission ran; the engine released the claim it had just taken.
                    return false;
                }
                if (!admission.admitted()) {
                    join.refuse(ProfileErrorCode.OWNERSHIP_CONFLICT, "another live session on " + admission.holderNode(),
                        "Your profile is active on " + admission.holderNode() + ". Try again in a moment.");
                    return false;
                }
                return true;
            }).thenCompose(admitted -> !admitted ? CompletableFuture.completedFuture(null) : joinAdmitted(join))
                .whenComplete((ignored, failed) -> ProfileRuntime.this.engine.releaseLoginGate(playerId, gate));
        }

        /** One join: its player instance, gate token and the world it placed the player in; refuses hold the gate first. */
        private final class Join {
            final Player player;
            final UUID playerId;
            final UUID gate;
            final String joinedWorld;

            Join(final PlayerJoinEvent event, final UUID gate, final String joinedWorld) {
                this.player = event.getPlayer();
                this.playerId = this.player.getUniqueId();
                this.gate = gate;
                this.joinedWorld = joinedWorld;
            }

            /** Whether this join is no longer the player's current connection (quit, or joined again meanwhile). */
            boolean stale() {
                return Bukkit.getPlayer(this.playerId) != this.player
                    || (this.gate != null && !ProfileRuntime.this.engine.gateHeld(this.playerId, this.gate));
            }

            /**
             * Refuses the join (review P1-4, P3-10): the gate is held until this player's quit, so nothing the join loaded is
             * captured or saved, then exactly this connection is disconnected (review P2-5); a stale join is left alone.
             */
            void refuse(final ProfileErrorCode code, final String why, final String message) {
                SourbyLogger.warn("AWF MultiProfile: the join of " + this.playerId + " is refused (" + code + "): " + why
                    + "; the player is disconnected and nothing of theirs is written");
                if (this.gate != null) ProfileRuntime.this.engine.holdLoginGate(this.playerId, this.gate);
                if (Bukkit.getPlayer(this.playerId) != this.player) return;
                disconnect(ProfileRuntime.this.plugin, this.player, message, KICK_ATTEMPTS);
            }
        }

        /**
         * The rest of the join once the session is admitted: recovery, the solo claim, the authoritative restore of
         * the active profile's committed state (Build 53), then the active profile's worlds. While authoritative login
         * applies every failure refuses the join (review P1-4): a recovery that failed, a claim that failed or was not
         * granted, a refused restore; the player never plays on vanilla playerdata with their saves refused.
         */
        private CompletableFuture<Void> joinAdmitted(final Join join) {
            final UUID playerId = join.playerId;
            final boolean authoritative = join.gate != null;
            final java.util.concurrent.atomic.AtomicBoolean recovered = new java.util.concurrent.atomic.AtomicBoolean();
            return ProfileRuntime.this.engine.recoverPending(playerId).handle((count, failed) -> {
                if (failed != null) {
                    SourbyLogger.warn("AWF MultiProfile: recovery for " + playerId + " failed: " + ProfileEngine.messageOf(failed));
                    if (authoritative) {
                        throw new java.util.concurrent.CompletionException(new ProfileOperationException(ProfileErrorCode.RECOVERY_REQUIRED,
                            "the recovery of a pending transition failed: " + ProfileEngine.messageOf(failed)));
                    }
                    return 0;
                }
                if (count > 0) recovered.set(true);
                return count;
            }).thenCompose(ignored -> ProfileRuntime.this.engine.active(playerId)).thenCompose(active -> {
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
                            final String holder = claim.claim() == null ? "another node" : claim.claim().node();
                            if (authoritative) {
                                join.refuse(ProfileErrorCode.OWNERSHIP_CONFLICT, "the node claim on the active profile " + profileId
                                    + " is held by " + holder, "Your profile is active on " + holder + ". Try again in a moment.");
                            } else if (ProfileRuntime.this.settings.coordination().singleActiveSession()) {
                                rejectDuplicate(join.player, claim);
                            }
                            return CompletableFuture.completedFuture(null);
                        }
                        return ProfileRuntime.this.engine.restoreOnLogin(playerId, join.gate, join::stale, recovered.get())
                            .thenCompose(restore -> {
                                if (restore.outcome() == ProfileEngine.LoginRestore.Outcome.REFUSED) {
                                    join.refuse(restore.code(), restore.detail(), "Your profile state could not be restored ("
                                        + restore.code() + "). Nothing of yours was changed; try again or contact the server staff.");
                                    return CompletableFuture.completedFuture(false);
                                }
                                if (restore.outcome() != ProfileEngine.LoginRestore.Outcome.NONE) {
                                    SourbyLogger.info("AWF MultiProfile: " + playerId + " joined into profile " + restore.profileId() + ": "
                                        + restore.detail());
                                }
                                if (join.stale()) return CompletableFuture.completedFuture(false);
                                // Profile worlds are not loaded at startup: load the active profile's worlds and
                                // bring the player back into them when the join placed them elsewhere.
                                return ProfileRuntime.this.engine.prepareActive(playerId, join.joinedWorld);
                            }).thenAccept(moved -> {
                                if (moved) {
                                    SourbyLogger.info("AWF MultiProfile: returned " + playerId
                                        + " to the worlds of their active profile after joining");
                                }
                            });
                    }).handle((ignored, failed) -> {
                        if (failed == null) return null;
                        if (authoritative) {
                            join.refuse(ProfileEngine.codeOf(failed), "the session claim or restore failed: " + ProfileEngine.messageOf(failed),
                                "Your profile session could not be started (" + ProfileEngine.codeOf(failed) + "). Try again in a moment.");
                        } else {
                            SourbyLogger.warn("AWF MultiProfile: session claim for " + playerId + " failed: " + ProfileEngine.messageOf(failed));
                        }
                        return null;
                    });
            }).handle((ignored, failed) -> {
                if (failed == null) return null;
                final ProfileOperationException cause = ProfileEngine.unwrap(failed);
                if (authoritative) {
                    join.refuse(cause.code(), cause.getMessage(), "Your profile state could not be restored (" + cause.code()
                        + "). Nothing of yours was changed; try again or contact the server staff.");
                } else {
                    SourbyLogger.warn("AWF MultiProfile: the join of " + playerId + " failed: " + cause.getMessage());
                }
                return null;
            });
        }

        /**
         * Lockstep autosave (Build 53, D2): the server just wrote the player's vanilla playerdata ({@code isQuit == false};
         * autosave, or the per-player interval save). While authoritative login applies, the same state is captured here,
         * on the thread that owns the player, under a ticket taken first, and published to the active profile's member
         * stream on the storage lane; this thread never waits for it. At most one autosave per player is pending at a
         * time. Skipped during a switch, a gated login, a quit already captured, after a refused save, and while the
         * engine is closing. A capture or publish that fails with a code that repeats (STATE_TOO_LARGE,
         * ADAPTER_REQUIRED_FAILED) freezes and disconnects the player (D4); any other failure is logged and retried by the
         * next save.
         */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerSaved(final PlayerSaveEvent event) {
            if (event.isQuit() || ProfileRuntime.this.closed) return;
            final ProfileEngine engine = ProfileRuntime.this.engine;
            if (!engine.isOpen() || !engine.format2() || !ProfileRuntime.this.settings.authoritativeLogin()) return;
            final Player player = event.getPlayer();
            final UUID playerId = player.getUniqueId();
            if (ProfileRuntime.this.quitting.contains(playerId) || engine.switchInProgress(playerId) || engine.savesRefused(playerId)) return;
            if (!engine.holdsSession(playerId) || !Bukkit.isOwnedByCurrentRegion(player)) return;
            final ProfileEngine.CaptureTicket ticket = engine.captureTicket(playerId);
            if (ticket == null || ticket.profileId() == null) return;
            if (!ProfileRuntime.this.autosavePending.add(playerId)) return;
            final PlayerStatePort.CaptureResult captured;
            try {
                captured = ProfileRuntime.this.statePort.captureOnOwnerThreadDetailed(player, ProfileRuntime.this.settings.isolation(), null);
            } catch (final RuntimeException failed) {
                ProfileRuntime.this.autosavePending.remove(playerId);
                saveFailed(engine, playerId, failed, "capturing " + playerId + " at the server's player save");
                return;
            }
            engine.saveCaptured(playerId, captured, ticket).whenComplete((written, failed) -> {
                ProfileRuntime.this.autosavePending.remove(playerId);
                if (failed != null && engine.isOpen()) saveFailed(engine, playerId, failed, "the lockstep save of " + playerId);
            });
        }

        /** D4: a failure that repeats until someone acts freezes and disconnects the player; the rest is logged. */
        private void saveFailed(final ProfileEngine engine, final UUID playerId, final Throwable failed, final String what) {
            final ProfileErrorCode code = ProfileEngine.codeOf(failed);
            if (ProfileEngine.fatalSaveCode(code)) {
                engine.refuseSaves(playerId, code + ": " + ProfileEngine.messageOf(failed));
                return;
            }
            SourbyLogger.warn("AWF MultiProfile: " + what + " failed (" + code + ": " + ProfileEngine.messageOf(failed)
                + "); the last committed snapshot stays and the next player save retries");
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(final PlayerQuitEvent event) {
            final Player player = event.getPlayer();
            final UUID playerId = player.getUniqueId();
            final ProfileEngine engine = ProfileRuntime.this.engine;
            // The server's own player save follows this event synchronously; the lockstep listener must not capture again.
            // The mark is dropped on the next global tick (or at a rejoin), so the set never outgrows the players quitting now.
            ProfileRuntime.this.quitting.add(playerId);
            try {
                Bukkit.getGlobalRegionScheduler().runDelayed(ProfileRuntime.this.plugin, task -> ProfileRuntime.this.quitting.remove(playerId), 2L);
            } catch (final RuntimeException stopping) {
                // The scheduler is halting (shutdown): the set dies with the runtime.
            }
            // Format 2: the ticket is taken before the capture, and exactly this session's claim is released.
            final ProfileEngine.CaptureTicket ticket = engine.captureTicket(playerId);
            final CoopWriteGuard.PlayerClaim session = engine.playerSessionClaim(playerId);
            if (engine.switchInProgress(playerId)) {
                // A switch already captured the previous profile; a second capture here would write
                // the target's state under the profile being left. A recovery, the teleport of a join
                // preparation or a login restore still running (the player holds the join's vanilla
                // playerdata) refuses the save as well; the committed snapshot stays authoritative, and the session
                // marker stays open so the next login re-examines the state.
                engine.releaseSessionAfterSwitch(playerId);
                return;
            }
            if (!engine.isOpen() || !engine.holdsSession(playerId)) {
                // Closed: at shutdown the players were handed to the shutdown capture before this (PM-076), and
                // MultiProfile closed before the server removes them. No session claim: the join found no active
                // profile to claim. Either way nothing is written here.
                if (engine.isOpen() && session != null) releasePlayerSession(engine, playerId, session);
                return;
            }
            // Captured here, on the thread that owns the player, while the player still exists; an
            // entity task scheduled from here would run after the player was removed. A required adapter that
            // fails refuses the capture (ADAPTER_REQUIRED_FAILED): no partial snapshot is written and the session
            // marker stays open (D4), so the next login re-examines the vanilla playerdata the server writes now.
            PlayerStatePort.CaptureResult captured = null;
            try {
                captured = ProfileRuntime.this.statePort.captureOnOwnerThreadDetailed(player, ProfileRuntime.this.settings.isolation(), null);
            } catch (final RuntimeException failed) {
                SourbyLogger.warn("AWF MultiProfile: capturing " + playerId + " on quit failed (" + ProfileEngine.messageOf(failed)
                    + "); no quit snapshot was written and the last committed snapshot stays");
            }
            final CompletableFuture<Boolean> saved = captured == null
                ? CompletableFuture.completedFuture(false) : engine.saveCapturedAndClose(playerId, captured, ticket);
            saved.handle((written, failed) -> {
                if (failed != null && engine.isOpen()) {
                    SourbyLogger.warn("AWF MultiProfile: saving the active profile of " + playerId + " on quit failed: "
                        + ProfileEngine.messageOf(failed) + "; the session marker stays open");
                }
                return null;
            }).thenCompose(ignored -> engine.releaseSession(playerId))
                .thenCompose(ignored -> session == null ? CompletableFuture.completedFuture(false)
                    : engine.releasePlayerSession(playerId, session))
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

        private void releasePlayerSession(final ProfileEngine engine, final UUID playerId,
                                          final CoopWriteGuard.PlayerClaim session) {
            engine.releasePlayerSession(playerId, session).exceptionally(failed -> {
                SourbyLogger.warn("AWF MultiProfile: releasing the session claim of " + playerId + " failed: "
                    + ProfileEngine.messageOf(failed) + "; it expires on its own");
                return false;
            });
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
        public CompletableFuture<Optional<ProfileDetails>> getProfileDetails(final UUID profileId) {
            return this.engine.getDetails(profileId);
        }

        @Override
        public CompletableFuture<List<ProfileDetails>> listProfileDetails(final UUID accountId) {
            return this.engine.listDetails(accountId);
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

        // ---- co-op membership (Build 52): every call goes to the engine, which refuses COOP_DISABLED while
        // coop.enabled is false and MIGRATION_REQUIRED on a format-1 store.

        @Override
        public CompletableFuture<Optional<CoopDetails>> getCoopDetails(final UUID profileId) {
            return this.engine.coopDetails(profileId);
        }

        @Override
        public CompletableFuture<CoopDetails> convertToCoop(final UUID actorId, final UUID profileId) {
            return this.engine.convertToCoop(actorId, profileId);
        }

        @Override
        public CompletableFuture<CoopInviteInfo> invite(final UUID actorId, final UUID profileId, final UUID inviteeId) {
            return this.engine.invite(actorId, profileId, inviteeId);
        }

        @Override
        public CompletableFuture<CoopMemberInfo> acceptInvite(final UUID playerId, final UUID inviteId) {
            return this.engine.acceptInvite(playerId, inviteId);
        }

        @Override
        public CompletableFuture<Void> declineInvite(final UUID playerId, final UUID inviteId) {
            return this.engine.declineInvite(playerId, inviteId);
        }

        @Override
        public CompletableFuture<Void> cancelInvite(final UUID actorId, final UUID inviteId) {
            return this.engine.cancelInvite(actorId, inviteId);
        }

        @Override
        public CompletableFuture<CoopPage<CoopInviteInfo>> listInvitesFor(final UUID playerId, final String cursor) {
            return this.engine.invitesFor(playerId, cursor);
        }

        @Override
        public CompletableFuture<CoopPage<CoopInviteInfo>> listInvites(final UUID actorId, final UUID profileId, final String cursor) {
            return this.engine.invitesOf(Objects.requireNonNull(actorId, "actorId"), profileId, cursor);
        }

        @Override
        public CompletableFuture<Void> leave(final UUID playerId, final UUID profileId) {
            return this.engine.leave(playerId, profileId);
        }

        @Override
        public CompletableFuture<CoopKickVoteInfo> startKickVote(final UUID actorId, final UUID profileId, final UUID targetId) {
            return this.engine.startKickVote(actorId, profileId, targetId);
        }

        @Override
        public CompletableFuture<CoopKickVoteInfo> voteKick(final UUID voterId, final UUID voteId, final boolean approve) {
            return this.engine.voteKick(voterId, voteId, approve);
        }

        @Override
        public CompletableFuture<Optional<CoopKickVoteInfo>> getOpenKickVote(final UUID profileId) {
            return this.engine.openKickVote(profileId);
        }

        @Override
        public CompletableFuture<CoopPage<CoopMemberInfo>> listMembers(final UUID profileId, final String cursor) {
            return this.engine.members(profileId, cursor);
        }

        @Override
        public CompletableFuture<Void> setCoopLabel(final UUID actorId, final UUID profileId, final String label) {
            return this.engine.setCoopLabel(actorId, profileId, Objects.requireNonNull(label, "label"));
        }
    }
}
