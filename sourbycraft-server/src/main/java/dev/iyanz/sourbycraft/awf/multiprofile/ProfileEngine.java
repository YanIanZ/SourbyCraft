package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileDetails;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileOwner;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import dev.iyanz.sourbycraft.api.profile.ProfileWorldRef;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRepository.CommitResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.EventFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricEvent;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricEventType;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricMetrics;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The AWF MultiProfile engine: durable profiles, fenced ownership, a recoverable switch protocol and
 * AWF world bindings.
 *
 * <p>Threading: every method returns immediately. Repository and ownership work runs through
 * {@link ProfileIoQueue}, one step at a time on the supplied bounded I/O executor; player reads and
 * writes are delegated to {@link PlayerStatePort}, which hops to the owning entity thread. No future
 * is joined here outside an I/O step, so the engine is safe to call from a region thread as long as
 * the caller only attaches a callback.</p>
 *
 * <p>Recoverable, not atomic: the account's active-profile reference is the commit point. Before it
 * is written, a failure after the target state reached the player rolls the player back to the
 * captured state, or leaves the journal {@code RECOVERING} for the next join when that cannot run
 * now; a failure before that point applies nothing. After it is written, recovery completes the
 * switch forward. A crash can therefore leave a journal entry, never two authoritative profiles. The
 * applied target state itself lives in the player's vanilla playerdata, which the server saves at
 * its own autosave and quit; a crash after the commit and before that save is not covered here.</p>
 *
 * <p>Every repository write carries the claim it is made under ({@link ProfileWriteGuard}). A shared
 * repository ({@link RedisProfileRepository}) refuses it atomically when that claim no longer holds
 * the profile; with a remote repository a commit whose reply is lost is settled from the account,
 * behind a fresh claim, before anything is rolled back.</p>
 *
 * <p><b>Store format (Build 51).</b> {@link #open} reads the store's format marker on the I/O lane
 * before any player is admitted and decides the paths ({@link StoreFormat#gate}): a format-1 store
 * keeps the PR #62 paths above unchanged; a format-2 store sends every mutation through the guarded
 * format-2 methods ({@link ProfileRepository#commitAccount}, {@link ProfileRepository#commitProfile},
 * {@link ProfileRepository#commitBinding}, {@link ProfileRepository#publishMemberSnapshot}) with
 * {@link CoopWriteGuard} expected revisions, under the player's session claim {@code Q}
 * ({@link PlayerSessionOwnership}) and, for profile metadata, a short transaction claim {@code P}
 * ({@link ProfileOwnership#claimTransaction}). Snapshot freshness is then the member head
 * ({@link MemberStateHead}), never the profile revision. A {@link CommitResult.Status#UNKNOWN} outcome is
 * settled by reading the receipt or the record back; when that does not establish it the operation
 * fails with {@link ProfileErrorCode#UNKNOWN_OUTCOME} and nothing is compensated. A store this build
 * refuses never gets an engine. The solo profile claim ({@code claim}/{@code release}) is kept beside
 * {@code Q} in format 2: Build 51 profiles have only their owner, and the claim still marks the node
 * that plays the profile's worlds (owner decision OD-1).</p>
 *
 * <p><b>Co-op membership (Build 52).</b> With {@code coop.enabled} (default false) and a format-2 store, the
 * co-op methods ({@link #convertToCoop}, {@link #invite}, {@link #acceptInvite}, {@link #declineInvite},
 * {@link #cancelInvite}, {@link #leave}, {@link #startKickVote}, {@link #voteKick}, {@link #setCoopLabel} and the
 * listings) run each {@link CoopMembership} plan as one intent, like the solo intents above: under {@code P}, the
 * actor's {@code Q} and, for a kick, the removed member's {@code Q}, with store-clock deadlines. Before the first
 * co-op record is prepared the store's feature level is raised ({@link ProfileRepository#requireStoreFeature}:
 * schema 2, minimum build 52). Refused with a reason: a membership change or a switch while another node holds the
 * profile ({@code WRONG_NODE}, OD-1), owner leave, kick and transfer ({@code OWNER_ACTION_REFUSED}). Removal writes
 * an UNAVAILABLE salvage record; nothing is delivered. Co-op events fire on the engine's own event thread after the
 * durable commit.</p>
 *
 * <p><b>Co-op play (Build 53, COOP-DESIGN slice 5 and RFC E03).</b> A member switches into the co-op profile like
 * any other ({@link #switchProfile}; {@code NOT_A_MEMBER} for a non-member, {@code COOP_DISABLED} while co-op is off,
 * {@code COOP_SWITCH_UNAVAILABLE} while authoritative login does not apply): the switch captures the previous state into
 * the player's own member stream as the rollback point, applies the exact snapshot the target member head names
 * (an UNINITIALIZED head applies the empty initial state), and commits the account pointer under
 * {@link CoopWriteGuard#switchActive}; saves and quits publish into that stream under a {@link CaptureTicket} minted
 * before the capture. One node claim per profile is shared by every member playing it here ({@link #nodeClaim}).
 * While authoritative login applies every join is gated ({@link #gateLogin}) until the committed head of the active
 * profile is restored ({@link #restoreOnLogin}); a head that cannot be read refuses the join ({@code HEAD_UNREADABLE}).
 * Required adapters (RFC E03) refuse a capture or apply they cannot complete ({@code ADAPTER_REQUIRED_FAILED},
 * {@code ADAPTER_MISSING}); best-effort ones keep their stored bytes when they fail. A member whose active profile
 * is the co-op can leave or be kicked: online here they are switched out first, offline their pointer is moved by
 * the removal intent ({@link Evacuation}); {@code MEMBER_ONLINE} remains for a member whose session is live on another
 * node or who cannot be moved safely. The store level is raised to Build 53 ({@link StoreFormat#MEMBER_PLAY_MINIMUM_BUILD})
 * by the first switch journal of a non-owner member (Build 52's rollback could not handle it); a store without one keeps
 * Build 52's marker. Session markers ({@link SessionMarker}) live outside the store format.</p>
 *
 * <p><b>Authoritative login, D1-D4 (Build 53 review).</b> {@code login.authoritative} is {@code auto} (authoritative iff
 * co-op is enabled), {@code true} or {@code false}. While it applies: every vanilla player save of the server (autosave)
 * publishes a snapshot in lockstep ({@link #saveCaptured}); a per-account {@link SessionMarker} records the open
 * session and the vanilla provenance, is closed by the committed quit or shutdown capture
 * ({@link #saveCapturedAndClose}) and decides at the next login whether a newer vanilla playerdata is adopted once into
 * the active profile ({@link #restoreOnLogin}); a save that cannot succeed freezes and disconnects the player
 * ({@link #refuseSaves}); and every path that cannot restore the committed state refuses the join.</p>
 */
public final class ProfileEngine {

    /** Profile steps that may wait for their turn on the I/O lane; beyond this an operation is refused. */
    static final int IO_QUEUE_CAPACITY = 4096;
    /** Snapshots kept per profile, besides those a pending journal still names. */
    private static final int SNAPSHOTS_KEPT = 3;

    private final ProfileSettings settings;
    private final ProfileRepository repository;
    private final ProfileOwnership ownership;
    private final PlayerStatePort players;
    private final ProfileWorldPort worlds;
    private final String nodeId;
    private final ProfileIoQueue ioQueue;
    private final FabricMetrics metrics;
    private final EventFabric events;
    private final ConcurrentHashMap<UUID, UUID> switches = new ConcurrentHashMap<>();
    /** Players whose state a running switch captured; the runtime's freeze listener cancels their item events. */
    private final java.util.Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    /** The claim that keeps a player's active profile owned on this node, by player id. */
    private final ConcurrentHashMap<UUID, OwnerClaim> sessionClaims = new ConcurrentHashMap<>();
    /** Players who quit mid-switch; their adopted claim is released when the switch ends. */
    private final java.util.Set<UUID> releaseOnSwitchEnd = ConcurrentHashMap.newKeySet();
    /** Running switches; the heartbeat renews the claims they hold until they end. */
    private final java.util.Set<SwitchRun> runs = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> worldUse = new ConcurrentHashMap<>();
    private final AtomicInteger pendingIo = new AtomicInteger();
    private volatile IdleSweep lastIdleSweep;
    private volatile boolean closed;

    /** Most pending-intent index pages {@link #open} reads; more refuses startup rather than scan without end. */
    private static final int MAX_INTENT_PAGES = 256;
    /** Lease of a process-local player session claim when the caller supplies no provider. */
    private static final int LOCAL_SESSION_LEASE_SECONDS = 60;
    static final String ROLLBACK_RULE = "Owner decision OD-2: a format-2 store is never downgraded in place and no exporter"
        + " exists; the only way back is restoring the complete pre-migration backup into an isolated store";

    /** The player session claims {@code Q} (format 2). */
    private final PlayerSessionOwnership playerSessions;
    /** The admitted format-2 session of each player online on this node. */
    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    /** Players whose session claim was confirmed lost; frozen until they quit. */
    private final java.util.Set<UUID> lostSessions = ConcurrentHashMap.newKeySet();
    /** Pending intents {@link #open} could not resolve, by every player they name. */
    private final ConcurrentHashMap<UUID, java.util.Set<UUID>> unresolvedIntents = new ConcurrentHashMap<>();
    /** Pending intents {@link #open} could not attribute to a player (a corrupt index); refuses every format-2 mutation. */
    private final java.util.Set<UUID> unattributedIntents = ConcurrentHashMap.newKeySet();
    private volatile Consumer<UUID> sessionLostHandler = player -> {};
    /** Where a message for an online player goes (the runtime sends it on the player's thread); no-op until set. */
    private volatile java.util.function.BiConsumer<UUID, String> playerNotice = (player, text) -> {};
    /**
     * Players whose join is gated until the committed state of their active profile was restored (Build 53
     * authoritative login): their captures, saves and switches are refused and the freeze listener cancels their
     * item events, so nothing of the vanilla playerdata the join loaded is written anywhere or traded.
     */
    private final ConcurrentHashMap<UUID, LoginGate> loginGates = new ConcurrentHashMap<>();

    /** A login gate; {@code sticky} once the join was refused: only the player's quit lifts it then. */
    private record LoginGate(UUID token, boolean sticky) {}
    /**
     * Players whose state could not be saved during play (STATE_TOO_LARGE, the aggregate adapter budget, a REQUIRED
     * adapter failure): frozen and disconnected (Build 53, D4); the quit capture is still attempted, and when it fails
     * too the session marker stays open so the next login re-examines the vanilla playerdata.
     */
    private final java.util.Set<UUID> saveRefused = ConcurrentHashMap.newKeySet();
    /** Where a player whose saves were refused is reported (the runtime disconnects them); no-op until set. */
    private volatile java.util.function.BiConsumer<UUID, String> saveRefusedHandler = (player, reason) -> {};
    /** Logged once: the repository stores no session markers, so every authoritative login applies the upgrade rule. */
    private final AtomicBoolean markersUnsupportedLogged = new AtomicBoolean();
    /** What {@link #open} decided; {@code null} until it ran, which keeps the format-1 paths. */
    private volatile StoreStatus storeStatus;

    public ProfileEngine(final ProfileSettings settings, final ProfileRepository repository,
                         final ProfileOwnership ownership, final PlayerStatePort players,
                         final ProfileWorldPort worlds, final String nodeId, final Executor io,
                         final FabricMetrics metrics, final EventFabric events) {
        this(settings, repository, ownership, PlayerSessionOwnership.local(nodeId, LOCAL_SESSION_LEASE_SECONDS), players,
            worlds, nodeId, io, metrics, events);
    }

    /**
     * @param playerSessions the player session claims {@code Q} a format-2 store is written under; the engine
     *     closes it on {@link #close}
     */
    public ProfileEngine(final ProfileSettings settings, final ProfileRepository repository,
                         final ProfileOwnership ownership, final PlayerSessionOwnership playerSessions,
                         final PlayerStatePort players, final ProfileWorldPort worlds, final String nodeId,
                         final Executor io, final FabricMetrics metrics, final EventFabric events) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.playerSessions = Objects.requireNonNull(playerSessions, "playerSessions");
        this.players = Objects.requireNonNull(players, "players");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.ioQueue = new ProfileIoQueue(Objects.requireNonNull(io, "io"), IO_QUEUE_CAPACITY);
        this.metrics = metrics;
        this.events = events;
    }

    public ProfileSettings settings() {
        return this.settings;
    }

    public ProfileOwnership ownership() {
        return this.ownership;
    }

    public ProfileRepository repository() {
        return this.repository;
    }

    /** The player session claims {@code Q}. */
    public PlayerSessionOwnership playerSessions() {
        return this.playerSessions;
    }

    public String nodeId() {
        return this.nodeId;
    }

    public boolean switching() {
        return this.settings.enabled() && this.settings.switching().enabled();
    }

    // ------------------------------------------------------------------ store format (Build 51)

    /**
     * What {@link #open} found and decided.
     *
     * @param marker the store's format marker
     * @param gate the decision ({@link StoreFormat#gate}); never {@code REFUSED}, a refused store has no engine
     * @param reason the gate's explanation
     * @param migration what the startup migration did (off, the dry-run report, the applied summary)
     * @param unresolvedIntents pending intents left for recovery; their players' format-2 mutations are refused
     */
    public record StoreStatus(StoreFormat.Marker marker, StoreFormat.Gate gate, String reason, String migration,
                              List<UUID> unresolvedIntents) {

        public StoreStatus {
            unresolvedIntents = List.copyOf(unresolvedIntents);
        }
    }

    /**
     * The format-1 to format-2 migration, supplied by the runtime ({@link ProfileRepositoryMigration}).
     * Offline only: {@link #open} runs it before any player is admitted.
     */
    public interface StoreMigration {
        /**
         * Reads the format-1 store and reports what a migration does ({@code apply == false}: writes nothing), or
         * backs it up and migrates it ({@code apply == true}). Throws when the migration did not complete; the
         * store is then not reported as format 2.
         */
        MigrationResult run(ProfileRepository repository, boolean apply) throws IOException;
    }

    /** @param applied whether the store was migrated @param summary the operator-facing report */
    public record MigrationResult(boolean applied, String summary) {

        public MigrationResult {
            Objects.requireNonNull(summary, "summary");
        }
    }

    /**
     * Reads the store format on the I/O lane and decides the paths before any player is admitted; call
     * once, before the service and listeners are registered. A refused store ({@link StoreFormat.Gate#REFUSED})
     * fails with {@link ProfileErrorCode#STORE_FORMAT_REFUSED} naming the format and the OD-2 rollback rule.
     * A format-1 store is migrated per {@code migration.mode}: {@code off} leaves it (format-1 paths),
     * {@code dry-run} logs the report and writes nothing, {@code apply} migrates it and then requires the store
     * to read back as format 2, failing the start otherwise. A store whose marker says a migration is in
     * progress (a crash mid-migration) is refused unless {@code apply} resumes it (the migration requires its
     * complete verified backup); {@code dry-run} reports it and still refuses. On a format-2 store every pending intent is
     * resolved ({@link ProfileRepository#resolveIntent}); one that cannot be resolved stays pending and its
     * players' format-2 mutations are refused with {@link ProfileErrorCode#RECOVERY_REQUIRED}.
     */
    public CompletableFuture<StoreStatus> open(final StoreMigration migration) {
        Objects.requireNonNull(migration, "migration");
        return io(() -> {
            StoreFormat.Marker marker = this.repository.storeFormat();
            StoreFormat.Decision decision = StoreFormat.gate(marker);
            // A migration this build started and that did not finish is refused like any other REFUSED marker,
            // except that migration.mode: apply resumes it (the migration verifies the backup first) and dry-run
            // reports it; the store is never used while the marker says IN_PROGRESS.
            final boolean interrupted = marker.format() == StoreFormat.CURRENT
                && marker.migration() == StoreFormat.MigrationState.IN_PROGRESS;
            if (decision.gate() == StoreFormat.Gate.REFUSED && !interrupted) throw refusedStore(marker, decision);
            final ProfileSettings.MigrationMode mode = this.settings.migration().mode();
            String migrated;
            if (decision.gate() == StoreFormat.Gate.LEGACY || interrupted) {
                switch (mode) {
                    case OFF -> {
                        if (interrupted) {
                            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, "AWF MultiProfile refuses"
                                + " the profile store (STORE_FORMAT_REFUSED): " + decision.reason() + ". Marker: " + describe(marker)
                                + ". Set migration.mode: apply (with migration.backup-dir holding the backup it wrote) to resume"
                                + " the migration offline, or restore that backup. " + ROLLBACK_RULE
                                + ". Nothing was written and MultiProfile did not start");
                        }
                        migrated = "off: the store stays format 1 (no stored profile names or types; co-op needs format 2)";
                    }
                    case DRY_RUN -> {
                        // A dry-run writes nothing, so its failure leaves the store as it was and does not
                        // stop the start of a format-1 store; it is reported instead.
                        String summary;
                        try {
                            summary = "dry-run, nothing written: " + migration.run(this.repository, false).summary();
                            SourbyLogger.info("AWF MultiProfile: format-2 migration " + summary);
                        } catch (final IOException | RuntimeException failed) {
                            summary = "dry-run failed, nothing written: " + failed.getMessage();
                            SourbyLogger.error("AWF MultiProfile: the format-2 migration dry-run of "
                                + this.repository.describe() + " failed: " + failed.getMessage()
                                + "; the store was not changed");
                        }
                        if (interrupted) {
                            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, "AWF MultiProfile refuses"
                                + " the profile store (STORE_FORMAT_REFUSED): " + decision.reason() + ". Marker: " + describe(marker)
                                + ". Dry-run report: " + summary + ". Set migration.mode: apply to resume it offline, or restore the"
                                + " backup. Nothing was written and MultiProfile did not start");
                        }
                        migrated = summary;
                    }
                    case APPLY -> {
                        final MigrationResult result;
                        try {
                            result = migration.run(this.repository, true);
                        } catch (final IOException | RuntimeException failed) {
                            throw new ProfileOperationException(ProfileErrorCode.MIGRATION_REQUIRED, "the format-2 migration"
                                + " (migration.mode: apply) failed: " + failed.getMessage() + ". MultiProfile did not start"
                                + " and no player was admitted; fix the cause and restart, or restore the backup in "
                                + this.settings.migration().backupDir(), failed);
                        }
                        marker = this.repository.storeFormat();
                        decision = StoreFormat.gate(marker);
                        if (!result.applied() || decision.gate() != StoreFormat.Gate.FORMAT_2) {
                            throw new ProfileOperationException(ProfileErrorCode.MIGRATION_REQUIRED, "the format-2 migration"
                                + " reported " + (result.applied() ? "success" : "nothing applied") + " but the store reads"
                                + " as " + describe(marker) + " (" + decision.reason() + "); MultiProfile did not start");
                        }
                        migrated = "applied: " + result.summary();
                        SourbyLogger.info("AWF MultiProfile: migrated " + this.repository.describe() + " to format 2: "
                            + result.summary());
                    }
                    default -> throw new IllegalStateException("migration mode " + mode);
                }
            } else {
                migrated = mode == ProfileSettings.MigrationMode.OFF ? "not needed (format 2)"
                    : "ignored: the store is already format 2 (migration.mode " + mode.id() + ")";
            }
            // Recorded before intent recovery: recovery uses the format-2 paths.
            this.storeStatus = new StoreStatus(marker, decision.gate(), decision.reason(), migrated, List.of());
            final List<UUID> unresolved = decision.gate() == StoreFormat.Gate.FORMAT_2 ? resolveIntentsAtStartup() : List.of();
            this.storeStatus = new StoreStatus(marker, decision.gate(), decision.reason(), migrated, unresolved);
            return this.storeStatus;
        });
    }

    /** What {@link #open} decided, or {@code null} before it ran. */
    public StoreStatus storeStatus() {
        final StoreStatus status = this.storeStatus;
        if (status == null) return null;
        final List<UUID> unresolved = new ArrayList<>(this.unattributedIntents);
        this.unresolvedIntents.values().forEach(ids -> ids.forEach(id -> {
            if (!unresolved.contains(id)) unresolved.add(id);
        }));
        return new StoreStatus(status.marker(), status.gate(), status.reason(), status.migration(), unresolved);
    }

    /** Whether the store is format 2 and the format-2 paths are used. */
    public boolean format2() {
        final StoreStatus status = this.storeStatus;
        return status != null && status.gate() == StoreFormat.Gate.FORMAT_2;
    }

    /**
     * Runs the migration's read-only report against the open store now ({@code /awf multiprofile migrate
     * dry-run}); writes nothing. A store that is already format 2 reports that instead.
     */
    public CompletableFuture<String> migrationDryRun(final StoreMigration migration) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            if (format2()) return "the store is already format 2; there is nothing to migrate";
            return migration.run(this.repository, false).summary();
        });
    }

    private static ProfileOperationException refusedStore(final StoreFormat.Marker marker, final StoreFormat.Decision decision) {
        return new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, "AWF MultiProfile refuses the profile"
            + " store (STORE_FORMAT_REFUSED): " + decision.reason() + ". Marker: " + describe(marker) + ". " + ROLLBACK_RULE
            + ". Nothing was written and MultiProfile did not start");
    }

    /** One line describing a store format marker, for logs and commands. */
    public static String describe(final StoreFormat.Marker marker) {
        return "format " + marker.format() + ", migration " + marker.migration() + ", schema " + marker.schema()
            + (marker.legacyFormat() ? "" : ", requires Build " + marker.requiredBuild())
            + (marker.writtenBy().isEmpty() ? "" : ", written by " + marker.writtenBy())
            + (marker.writtenAtMillis() == 0L ? "" : " at " + java.time.Instant.ofEpochMilli(marker.writtenAtMillis()));
    }

    /**
     * Resolves every pending intent of the global index before admission; returns the ones left pending.
     * Runs inside the {@link #open} step. A PREPARED intent nobody can still be running (standalone, or past its
     * deadline) is aborted, which clears only its own reservations; a committed one rolls forward; an
     * unresolved outcome stays for recovery.
     */
    private List<UUID> resolveIntentsAtStartup() throws IOException {
        final List<UUID> ids = new ArrayList<>();
        ProfileIndexPage.Cursor cursor = null;
        for (int pages = 0; ; pages++) {
            if (pages == MAX_INTENT_PAGES) {
                throw error(ProfileErrorCode.RECOVERY_REQUIRED, "more than " + MAX_INTENT_PAGES * ProfileIndexPage.LISTING_PAGE
                    + " pending intents; refusing to start without resolving them (offline recovery needed)");
            }
            final ProfileIndexPage page = this.repository.index(ProfileIndexPage.Kind.PENDING_INTENTS,
                ProfileIndexPage.GLOBAL, null, null, cursor);
            for (final ProfileIndexPage.Entry entry : page.entries()) ids.add(entry.id());
            cursor = page.cursorAfterLast();
            if (cursor == null || page.entries().size() < ProfileIndexPage.LISTING_PAGE) break;
        }
        final List<UUID> unresolved = new ArrayList<>();
        for (final UUID id : ids) {
            if (!resolveIntent(id, Map.of())) unresolved.add(id);
        }
        if (!ids.isEmpty()) {
            SourbyLogger.info("AWF MultiProfile: " + ids.size() + " pending intent(s) at startup, " + (ids.size() - unresolved.size())
                + " resolved" + (unresolved.isEmpty() ? "" : "; " + unresolved.size() + " left pending, their players'"
                    + " profile changes are refused until recovery: " + unresolved));
        }
        return unresolved;
    }

    /**
     * Resolves one intent under the claims it names: the player claims in {@code held} are used as they are
     * (the player's own session at login), others are claimed briefly; a claim another live session holds
     * leaves the intent pending. Returns whether the intent is terminal afterwards; records it as unresolved
     * otherwise. Runs inside an I/O step.
     */
    private boolean resolveIntent(final UUID operationId, final Map<UUID, PlayerClaim> held) {
        final List<PlayerClaim> acquired = new ArrayList<>(2);
        ProfileClaim transaction = null;
        ProfileIntent intent = null;
        try {
            final Optional<ProfileIntent> found = this.repository.intent(operationId);
            if (found.isEmpty()) {
                // The pending index names an intent that is not stored: the index is corrupt; fail closed.
                SourbyLogger.error("AWF MultiProfile: the pending-intent index names " + operationId + ", which is not"
                    + " stored; every format-2 profile change is refused until an offline index rebuild");
                this.unattributedIntents.add(operationId);
                return false;
            }
            intent = found.get();
            if (!intent.pending()) {
                forgetUnresolved(intent);
                return true;
            }
            PlayerClaim actor = null;
            PlayerClaim affected = null;
            for (final ProfileIntent.ClaimRef ref : intent.playerClaims()) {
                PlayerClaim claim = held.get(ref.id());
                if (claim == null) {
                    final PlayerSessionOwnership.Grant grant = this.playerSessions.claim(ref.id(), UUID.randomUUID()).join();
                    if (!grant.granted()) {
                        markUnresolved(intent, "the session claim of " + ref.id() + " is " + grant.reason());
                        return false;
                    }
                    claim = grant.claim();
                    acquired.add(claim);
                }
                // The intent names the actor first and, for a kick, the removed member's account second.
                if (actor == null) {
                    actor = claim;
                } else if (affected == null) {
                    affected = claim;
                }
            }
            if (intent.profileClaim() != null) {
                final ProfileOwnership.TransactionGrant grant = this.ownership
                    .claimTransaction(intent.profileClaim().id(), UUID.randomUUID()).join();
                if (!grant.granted()) {
                    markUnresolved(intent, "the transaction claim of " + intent.profileClaim().id() + " is " + grant.reason());
                    return false;
                }
                transaction = grant.claim();
            }
            final CoopOperation op = new CoopOperation(intent.operationId(), intent.kind(), intent.actor(),
                intent.requestDigest(), intent.createdAtMillis());
            this.repository.resolveIntent(operationId, CoopWriteGuard.intent(op, transaction, actor, affected, intent.revision(),
                intent.expectedAfter(), Set.of(operationId)));
            ProfileIntent after = this.repository.intent(operationId).orElse(intent);
            if (after.pending() && !after.committed() && after.phase() == ProfileIntent.Phase.PREPARED
                && (!this.ownership.distributed() || after.pastDeadline(this.repository.storeClockMillis()))) {
                // Never committed, and no other process can still be running it: abort clears its own
                // reservations only. A committed intent always rolls forward instead.
                this.repository.abortIntent(operationId, CoopWriteGuard.intent(op, transaction, actor, affected, after.revision(),
                    after.expectedAfter(), Set.of(operationId)));
                after = this.repository.intent(operationId).orElse(after);
                SourbyLogger.warn("AWF MultiProfile: aborted the uncommitted " + after.kind() + " intent " + operationId
                    + " left by an earlier process; its reservations were cleared");
            }
            if (!after.pending()) {
                forgetUnresolved(after);
                return true;
            }
            markUnresolved(after, "it is " + after.phase());
            return false;
        } catch (final IOException | RuntimeException failed) {
            if (intent != null) {
                markUnresolved(intent, messageOf(failed));
            } else {
                SourbyLogger.error("AWF MultiProfile: intent " + operationId + " could not be read (" + messageOf(failed)
                    + "); every format-2 profile change is refused until it is recovered");
                this.unattributedIntents.add(operationId);
            }
            return false;
        } finally {
            if (transaction != null) releaseTransactionClaim(transaction);
            releasePlayerClaims(acquired);
        }
    }

    private void markUnresolved(final ProfileIntent intent, final String why) {
        final java.util.Set<UUID> players = new java.util.LinkedHashSet<>();
        players.add(intent.actor());
        for (final ProfileIntent.ClaimRef ref : intent.playerClaims()) players.add(ref.id());
        for (final UUID player : players) {
            this.unresolvedIntents.computeIfAbsent(player, ignored -> ConcurrentHashMap.newKeySet()).add(intent.operationId());
        }
        SourbyLogger.warn("AWF MultiProfile: " + intent.kind() + " intent " + intent.operationId() + " stays pending (" + why
            + "); profile changes of " + players + " are refused until it is resolved");
    }

    private void forgetUnresolved(final ProfileIntent intent) {
        this.unresolvedIntents.values().forEach(ids -> ids.remove(intent.operationId()));
        this.unresolvedIntents.values().removeIf(java.util.Set::isEmpty);
    }

    /** Refuses a format-2 mutation of an account that a pending intent still names (or any, for a corrupt index). */
    private void requireNoUnresolvedIntent(final UUID accountId) {
        if (!this.unattributedIntents.isEmpty()) {
            throw error(ProfileErrorCode.RECOVERY_REQUIRED, "the pending-intent index is unreadable or names a missing intent ("
                + this.unattributedIntents + "); format-2 profile changes are refused until an offline recovery");
        }
        final java.util.Set<UUID> pending = this.unresolvedIntents.get(accountId);
        if (pending != null && !pending.isEmpty()) {
            throw error(ProfileErrorCode.RECOVERY_REQUIRED, "intent(s) " + pending + " of " + accountId + " are unresolved;"
                + " the player's next login retries them, or see /awf multiprofile format");
        }
    }

    // ------------------------------------------------------------------ profiles

    /** Profiles of an account, in account order. */
    public CompletableFuture<List<ProfileInfo>> list(final UUID accountId) {
        return listDetails(accountId).thenApply(details -> details.stream().map(ProfileDetails::info).toList());
    }

    /** Profiles of an account with their name and policy type, in account order. */
    public CompletableFuture<List<ProfileDetails>> listDetails(final UUID accountId) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            final PlayerAccount account = this.repository.loadAccount(accountId, this.settings.defaultSlots());
            final List<ProfileDetails> details = new ArrayList<>();
            final List<AuroraProfile> profiles = this.repository.profiles(accountId);
            final Map<UUID, String> legacyNames = ProfileRepositoryMigration.legacyNames(profiles);
            for (final AuroraProfile profile : profiles) {
                if (profile.status() == ProfileStatus.DELETED) continue;
                details.add(details(profile, account.activeProfile(), legacyNames));
            }
            return List.copyOf(details);
        });
    }

    public CompletableFuture<Optional<ProfileInfo>> get(final UUID profileId) {
        return getDetails(profileId).thenApply(found -> found.map(ProfileDetails::info));
    }

    /** One profile with its name and policy type. */
    public CompletableFuture<Optional<ProfileDetails>> getDetails(final UUID profileId) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> describeDetails(profileId));
    }

    public CompletableFuture<Optional<ProfileInfo>> active(final UUID accountId) {
        return list(accountId).thenApply(infos -> infos.stream().filter(ProfileInfo::active).findFirst());
    }

    /** Creates a profile; see {@link #createDetailed}. */
    public CompletableFuture<ProfileInfo> create(final ProfileRequest request) {
        return createDetailed(request).thenApply(ProfileDetails::info);
    }

    /**
     * Creates a profile. Slot limit and account revision are checked in one serialised I/O step.
     *
     * <p>The policy type is the request's, else {@code types.default}; one outside {@code types.allowed} is
     * {@link ProfileErrorCode#TYPE_NOT_ALLOWED}. The name ({@code names.policy: cute}) is server-assigned from
     * the {@link CuteNames} pool and unique in the account ignoring case; a requested name must be a pool
     * name (optionally with a numeric suffix), else {@link ProfileErrorCode#NAME_INVALID}, and one the account
     * already uses is {@link ProfileErrorCode#NAME_TAKEN}. Both are stored with the profile and never change.
     * A format-1 store stores neither: there a request naming a name or a type other than {@code classic}
     * (including a {@code types.default} other than classic) is refused with
     * {@link ProfileErrorCode#MIGRATION_REQUIRED}, and the profile reads back with its id-derived name.</p>
     *
     * <p>Format 2: one intent under the account's session claim {@code Q} (COOP-DESIGN §5): the staged
     * after-images are the new profile record ({@code M} absent, revision 0; the store checks the name is free in
     * the account at prepare and at commit) and the account with the slot and its alias ({@code A} + 2 after the
     * reservation); PREPARE reserves the account, COMMIT installs both. An interruption leaves a PREPARED or
     * COMMITTED intent that startup or the player's next login resolves (abort clears the reservation, commit
     * rolls forward); never a profile record no account lists.</p>
     */
    public CompletableFuture<ProfileDetails> createDetailed(final ProfileRequest request) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            if (this.switches.containsKey(request.accountId())) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "account transition in progress");
            }
            final dev.iyanz.sourbycraft.api.profile.ProfileType type = request.profileType() != null
                ? request.profileType() : this.settings.types().defaultType();
            if (!this.settings.types().allows(type)) {
                throw error(ProfileErrorCode.TYPE_NOT_ALLOWED, "profile type " + type.id() + " is not in types.allowed "
                    + ProfileSettings.typeIds(this.settings.types().allowed()));
            }
            final PlayerAccount account = this.repository.loadAccount(request.accountId(), this.settings.defaultSlots());
            if (account.full() || account.profileIds().size() >= this.settings.maxSlots()) {
                throw error(ProfileErrorCode.SLOT_LIMIT, "the account already owns " + account.profileIds().size()
                    + " of " + account.slotLimit() + " profiles");
            }
            return format2() ? createFormat2(request, type, account) : createFormat1(request, type, account);
        });
    }

    private ProfileDetails createFormat1(final ProfileRequest request, final dev.iyanz.sourbycraft.api.profile.ProfileType type,
                                         final PlayerAccount account) throws IOException {
        if (request.name() != null || type != dev.iyanz.sourbycraft.api.profile.ProfileType.CLASSIC) {
            throw error(ProfileErrorCode.MIGRATION_REQUIRED, "the profile store is format 1, which stores no profile name"
                + " and no type besides classic; " + (request.name() != null ? "a requested name" : "type " + type.id())
                + " cannot be kept. Migrate the store to format 2 (migration.mode: apply) first. Nothing was written");
        }
        final AuroraProfile profile;
        try {
            profile = new AuroraProfile(UUID.randomUUID(), request.accountId(), request.type(),
                request.metadata(), System.currentTimeMillis(), 0L, ProfileStatus.ACTIVE);
        } catch (final IllegalArgumentException invalid) {
            // A bad type or metadata is the caller's mistake, not a server fault.
            throw error(ProfileErrorCode.INVALID_REQUEST, invalid.getMessage());
        }
        this.repository.saveProfile(profile, ProfileWriteGuard.of(null, ProfileWriteGuard.ABSENT));
        this.repository.saveAccount(account.withProfile(profile.id()),
            ProfileWriteGuard.of(null, account.revision()));
        // Published inside the step: a callback attached after the future completed would run the
        // blocking publish on the caller's thread, which may be a region thread.
        publish(FabricEventType.PROFILE_CREATED, Map.of("profile", profile.id().toString(),
            "account", profile.accountId().toString(), "type", profile.type()));
        return details(profile, null);
    }

    private ProfileDetails createFormat2(final ProfileRequest request, final dev.iyanz.sourbycraft.api.profile.ProfileType type,
                                         final PlayerAccount account) throws IOException {
        final UUID accountId = request.accountId();
        requireNoUnresolvedIntent(accountId);
        if (account.pendingIntent() != null) {
            throw error(ProfileErrorCode.RECOVERY_REQUIRED, "the account is reserved by intent " + account.pendingIntent());
        }
        final java.util.Set<String> taken = new java.util.HashSet<>();
        for (final AuroraProfile existing : this.repository.profiles(accountId)) taken.add(CuteNames.key(existing.name()));
        final String name;
        if (request.name() != null) {
            final String requested = request.name().strip();
            if (!CuteNames.valid(requested)) {
                throw error(ProfileErrorCode.NAME_INVALID, "'" + request.name() + "' is not a profile name: names.policy cute"
                    + " accepts a name from the server's fruit and vegetable pool, optionally followed by a space and a"
                    + " number 1-999");
            }
            name = CuteNames.normalize(requested);
            if (taken.contains(CuteNames.key(name))) {
                throw error(ProfileErrorCode.NAME_TAKEN, "the account already has a profile named " + name
                    + " (names are compared ignoring case)");
            }
        } else {
            try {
                name = CuteNames.generate(taken);
            } catch (final IllegalStateException exhausted) {
                throw error(ProfileErrorCode.NAME_TAKEN, "no free profile name is left for this account");
            }
        }
        final AuroraProfile profile;
        try {
            profile = AuroraProfile.create(accountId, request.type(), type, name, request.metadata(), System.currentTimeMillis());
        } catch (final IllegalArgumentException invalid) {
            throw error(ProfileErrorCode.INVALID_REQUEST, invalid.getMessage());
        }
        final String alias = CuteNames.allocateAlias(CuteNames.alias(name), account.takenAliases());
        // After-images carry the revisions COMMIT installs: the account after its reservation (A + 1) advances once
        // more (A + 2); the new profile record starts at 0.
        final long accountRevision = account.revision();
        final PlayerAccount after = account.withProfile(profile.id(), alias)
            .atRevision(CoopWriteGuard.next(CoopWriteGuard.next(accountRevision, "account " + accountId), "account " + accountId));
        final List<PlayerClaim> acquired = new ArrayList<>(1);
        try {
            final PlayerClaim q = actorClaim(accountId, acquired);
            final CoopOperation op = operation(CoopOperation.Kind.CREATE_PROFILE, accountId, profile.id(), name, type,
                request.type(), alias, accountRevision);
            runIntent(op, null, q,
                List.of(Expected.account(accountId, accountRevision), Expected.metadata(profile.id(), CoopWriteGuard.ABSENT)),
                List.of(Expected.account(accountId, accountRevision + 1L), Expected.metadata(profile.id(), CoopWriteGuard.ABSENT)),
                List.of(new AfterImage(ProfileRecords.TYPE_PROFILE, profile.id(), 0L, ProfileRecords.profilePayload2(profile)),
                    new AfterImage(ProfileRecords.TYPE_ACCOUNT, accountId, after.revision(), ProfileRecords.accountPayload2(after))));
            accountCommitted(after);
        } finally {
            releasePlayerClaims(acquired);
        }
        publish(FabricEventType.PROFILE_CREATED, Map.of("profile", profile.id().toString(),
            "account", profile.accountId().toString(), "type", profile.type(), "profile-type", type.id(), "name", name));
        return details(profile, after.activeProfile());
    }

    /** Tombstones a profile; its AWF worlds are left alone (a consumer may own them). */
    public CompletableFuture<Boolean> delete(final UUID profileId) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            final Optional<AuroraProfile> found = this.repository.profile(profileId);
            if (found.isEmpty()) return false;
            final AuroraProfile profile = found.get();
            if (this.switches.containsKey(profile.accountId())) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch is running for "
                    + profile.accountId() + "; the profile cannot be deleted");
            }
            requireNoPendingJournal(profile.accountId());
            // Checked before the administrative claim: the active profile may carry this process's session
            // claim, which a claim of its own would supersede. The account revision read here is the one
            // the write expects, so a change in between is still refused.
            final PlayerAccount account = this.repository.loadAccount(profile.accountId(), this.settings.defaultSlots());
            if (profileId.equals(account.activeProfile())) {
                throw error(ProfileErrorCode.PROFILE_LOCKED, "the active profile cannot be deleted; switch away first");
            }
            if (format2()) return deleteFormat2(profile, account);
            final OwnerClaim admin = adminClaim(profileId);
            try {
                this.repository.saveProfile(profile.withStatus(ProfileStatus.DELETED),
                    ProfileWriteGuard.of(admin, profile.revision()));
                this.repository.saveAccount(account.withoutProfile(profileId), ProfileWriteGuard.of(admin, account.revision()));
                this.repository.deleteProfileData(profileId, ProfileWriteGuard.claimed(admin));
                return true;
            } finally {
                releaseAdminClaim(admin);
            }
        });
    }

    /**
     * Format-2 tombstone as one intent under {@code P} (the profile's transaction claim) and the account's
     * {@code Q}: PREPARE reserves the metadata record and the account, COMMIT installs the DELETED record
     * ({@code M} + 2) and the account without the slot and its alias ({@code A} + 2). An interruption is recovered
     * by the intent protocol (abort or roll forward), never finished by a second, separately guarded step. A
     * profile already DELETED that the account still lists gets the account step only. Member snapshots and the
     * binding are kept as records; nothing touches AWF world data.
     */
    private boolean deleteFormat2(final AuroraProfile profile, final PlayerAccount account) throws IOException {
        final UUID accountId = profile.accountId();
        final UUID profileId = profile.id();
        requireNoUnresolvedIntent(accountId);
        final boolean tombstone = profile.status() != ProfileStatus.DELETED;
        final boolean unlist = account.owns(profileId);
        if (!tombstone && !unlist) return false;
        if (tombstone && profile.coop() && profile.members().size() > 1) {
            // The delete intent changes the owner's account only; the members' accounts would keep listing a tombstone.
            throw error(ProfileErrorCode.OWNER_ACTION_REFUSED, "co-op profile " + profileId + " still has " + (profile.members().size() - 1)
                + " other member(s); they must leave or be kicked before the owner deletes it. Nothing was written");
        }
        if (tombstone && profile.pendingIntent() != null) {
            throw error(ProfileErrorCode.RECOVERY_REQUIRED, "profile " + profileId + " is reserved by intent " + profile.pendingIntent());
        }
        if (unlist && account.pendingIntent() != null) {
            throw error(ProfileErrorCode.RECOVERY_REQUIRED, "the account is reserved by intent " + account.pendingIntent());
        }
        final List<Expected> before = new ArrayList<>(2);
        final List<Expected> after = new ArrayList<>(2);
        final List<AfterImage> images = new ArrayList<>(2);
        if (tombstone) {
            final long m = profile.revision();
            final AuroraProfile deleted = profile.withStatus(ProfileStatus.DELETED)
                .atRevision(CoopWriteGuard.next(CoopWriteGuard.next(m, "profile " + profileId), "profile " + profileId));
            before.add(Expected.metadata(profileId, m));
            after.add(Expected.metadata(profileId, m + 1L));
            images.add(new AfterImage(ProfileRecords.TYPE_PROFILE, profileId, deleted.revision(), ProfileRecords.profilePayload2(deleted)));
        }
        PlayerAccount unlisted = null;
        if (unlist) {
            final long a = account.revision();
            unlisted = account.withoutProfile(profileId)
                .atRevision(CoopWriteGuard.next(CoopWriteGuard.next(a, "account " + accountId), "account " + accountId));
            before.add(Expected.account(accountId, a));
            after.add(Expected.account(accountId, a + 1L));
            images.add(new AfterImage(ProfileRecords.TYPE_ACCOUNT, accountId, unlisted.revision(), ProfileRecords.accountPayload2(unlisted)));
        }
        final List<PlayerClaim> acquired = new ArrayList<>(1);
        ProfileClaim transaction = null;
        try {
            final PlayerClaim q = actorClaim(accountId, acquired);
            if (tombstone) transaction = transactionClaim(profileId);
            runIntent(operation(CoopOperation.Kind.DELETE_PROFILE, accountId, profileId, profile.revision(), account.revision()),
                transaction, q, before, after, images);
            if (unlisted != null) accountCommitted(unlisted);
            return true;
        } finally {
            if (transaction != null) releaseTransactionClaim(transaction);
            releasePlayerClaims(acquired);
        }
    }

    /**
     * The claim an administrative write on an inactive profile is made under. With distributed
     * ownership and fencing it is a short claim of its own, so the repository fences those writes and a
     * profile another process holds (a switch to it, a recovery) is refused; the caller releases it with
     * {@link #releaseAdminClaim}. Otherwise no claim is taken ({@code null}), after checking that no
     * other process holds the profile. Runs inside an I/O step, which no other step of this engine
     * interleaves with, and the callers first refuse an account with a running switch and the account's
     * active profile (the one a session claim is on), so the claim never replaces one this process uses.
     */
    private OwnerClaim adminClaim(final UUID profileId) {
        if (this.ownership.distributed() && this.settings.coordination().fencingEnabled()) {
            final ClaimResult claimed = this.ownership.claim(profileId, this.nodeId, UUID.randomUUID()).join();
            if (!claimed.granted()) {
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profileId + " is held by "
                    + (claimed.claim() == null ? "another node" : claimed.claim().node()));
            }
            return claimed.claim();
        }
        final OwnerClaim foreign = this.ownership.foreignHolder(profileId).join();
        if (foreign != null) {
            throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profileId + " is owned by " + foreign.node());
        }
        return null;
    }

    private void releaseAdminClaim(final OwnerClaim claim) {
        if (claim == null) return;
        try {
            this.ownership.release(claim).join();
        } catch (final RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: releasing an administrative claim on " + claim.id() + " failed ("
                + messageOf(failed) + "); it expires on its own");
        }
    }

    /**
     * Puts an inactive profile on administrative hold ({@code LOCKED}: listed, refuses switches) or
     * releases it. The active profile cannot be locked; switch away first.
     */
    public CompletableFuture<Void> setLocked(final UUID profileId, final boolean locked) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            final AuroraProfile profile = this.repository.profile(profileId)
                .filter(found -> found.status() != ProfileStatus.DELETED)
                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + profileId));
            if (this.switches.containsKey(profile.accountId())) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "account transition in progress");
            }
            // A pending rollback may make this profile active again; it must find it as it was.
            requireNoPendingJournal(profile.accountId());
            final PlayerAccount account = this.repository.loadAccount(profile.accountId(), this.settings.defaultSlots());
            if (locked && profileId.equals(account.activeProfile())) {
                throw error(ProfileErrorCode.PROFILE_LOCKED, "the active profile cannot be locked; switch away first");
            }
            final ProfileStatus next = locked ? ProfileStatus.LOCKED : ProfileStatus.ACTIVE;
            if (profile.status() != next && format2()) {
                // Format 2: a hold is a metadata mutation (M advances); freshness lives on the member heads.
                requireNoUnresolvedIntent(profile.accountId());
                if (profile.pendingIntent() != null) {
                    throw error(ProfileErrorCode.RECOVERY_REQUIRED, "profile " + profileId + " is reserved by intent "
                        + profile.pendingIntent());
                }
                final List<PlayerClaim> acquired = new ArrayList<>(1);
                ProfileClaim transaction = null;
                try {
                    final PlayerClaim q = actorClaim(profile.accountId(), acquired);
                    transaction = transactionClaim(profileId);
                    commitProfile(profile.withStatus(next), CoopWriteGuard.metadata(operation(CoopOperation.Kind.SET_STATUS,
                        profile.accountId(), profileId, next, profile.revision()), transaction, q, account.revision(),
                        profile.revision()));
                } finally {
                    if (transaction != null) releaseTransactionClaim(transaction);
                    releasePlayerClaims(acquired);
                }
            } else if (profile.status() != next) {
                final OwnerClaim admin = adminClaim(profileId);
                try {
                    this.repository.saveProfile(profile.withHold(next), ProfileWriteGuard.of(admin, profile.revision()));
                } finally {
                    releaseAdminClaim(admin);
                }
            }
            return null;
        });
    }

    /**
     * Sets how many profiles an account may own, between 1 and {@code max-slots}. Profiles beyond a
     * lowered limit are kept; creating more is refused until the account is below it.
     */
    public CompletableFuture<Integer> setSlotLimit(final UUID accountId, final int slots) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        if (slots < 1 || slots > this.settings.maxSlots()) {
            return failed(ProfileErrorCode.INVALID_REQUEST, "the slot limit must be 1-" + this.settings.maxSlots()
                + " (max-slots)");
        }
        return io(() -> {
            final PlayerAccount account = this.repository.loadAccount(accountId, this.settings.defaultSlots());
            if (!format2()) {
                this.repository.saveAccount(account.withSlotLimit(slots), ProfileWriteGuard.of(null, account.revision()));
                return slots;
            }
            requireNoUnresolvedIntent(accountId);
            final List<PlayerClaim> acquired = new ArrayList<>(1);
            try {
                final PlayerClaim q = actorClaim(accountId, acquired);
                commitAccount(account.withSlotLimit(slots), accountGuard(operation(CoopOperation.Kind.ACCOUNT_LIMIT, accountId,
                    "slot-limit", slots, account.revision()), q, account.revision(), null));
            } finally {
                releasePlayerClaims(acquired);
            }
            return slots;
        });
    }

    /** Switches the player's active profile; never throws, failures are in the result. */
    public CompletableFuture<ProfileSwitchResult> switchProfile(final UUID playerId, final UUID targetId) {
        final long began = System.currentTimeMillis();
        if (!this.settings.enabled()) {
            return CompletableFuture.completedFuture(failure(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled",
                null, began));
        }
        if (!this.settings.switching().enabled()) {
            return CompletableFuture.completedFuture(failure(ProfileErrorCode.SWITCHING_DISABLED,
                "profile switching is disabled by configuration", null, began));
        }
        return io(() -> {
            final PlayerAccount account = this.repository.loadAccount(playerId, this.settings.defaultSlots());
            final Optional<AuroraProfile> target = this.repository.profile(targetId);
            if (target.isEmpty() || target.get().status() == ProfileStatus.DELETED) {
                throw error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + targetId);
            }
            if (!target.get().accountId().equals(playerId)) {
                if (!target.get().coop()) {
                    throw error(ProfileErrorCode.NOT_OWNED, "profile " + targetId + " belongs to another account");
                }
                if (target.get().member(playerId) == null) {
                    throw error(ProfileErrorCode.NOT_A_MEMBER, playerId + " is not a member of co-op profile " + targetId);
                }
                // Build 53: a member plays the co-op profile from their own member stream. Their state is only safe
                // when the join restores the committed member head (login.authoritative); without it a rejoin would
                // load whatever vanilla playerdata this server last wrote.
                if (!this.settings.coopEnabled()) {
                    throw error(ProfileErrorCode.COOP_DISABLED, "profile " + targetId + " is a co-op profile and co-op is off ("
                        + ProfileSettings.COOP_ENABLED_KEY + "); only its owner can switch into it");
                }
                if (!this.settings.authoritativeLogin()) {
                    throw error(ProfileErrorCode.COOP_SWITCH_UNAVAILABLE, "a member can play co-op profile " + targetId + " only while"
                        + " authoritative login applies (" + ProfileSettings.LOGIN_AUTHORITATIVE_KEY + ": auto with co-op enabled, or true;"
                        + " RESTART_REQUIRED), which restores the member's committed state at join; it is "
                        + this.settings.login().authoritative().id() + " on this server");
                }
            }
            if (target.get().coop() && format2()) {
                // OD-1: a co-op profile is played on the node that holds its bound world; no cross-node routing.
                requireProfileNode(targetId);
            }
            if (target.get().status() != ProfileStatus.ACTIVE) {
                throw error(ProfileErrorCode.PROFILE_LOCKED, "profile " + targetId + " is " + target.get().status());
            }
            if (!target.get().usable()) {
                throw error(ProfileErrorCode.PROFILE_LOCKED, "profile " + targetId + " is not usable");
            }
            if (targetId.equals(account.activeProfile())) {
                throw error(ProfileErrorCode.INVALID_REQUEST, "profile " + targetId + " is already active;"
                    + " nothing to switch");
            }
            requireNoPendingJournal(playerId);
            requireNoLoginGate(playerId);
            if (this.saveRefused.contains(playerId)) {
                throw error(ProfileErrorCode.STATE_TOO_LARGE, "the state of " + playerId + " could not be saved in this session;"
                    + " they are frozen until they rejoin and nothing is switched");
            }
            if (format2() && !target.get().accountId().equals(playerId)) {
                // Build 53 (review P3-17): a non-owner's switch journal names a co-op the member does not own; Build 52's
                // rollback would commit that pointer for a non-owner and checkCoopAccess would then refuse recovery, so a
                // store that holds one is marked minimum build 53 first.
                requireMemberPlayStoreLevel();
            }
            return begin(new SwitchStart(account, target.get(), format2() ? switchAdmission(account, target.get()) : null),
                targetId, began);
        }).thenCompose(result -> result)
            .exceptionally(failed -> failure(codeOf(failed), messageOf(failed), null, began));
    }

    private CompletableFuture<ProfileSwitchResult> begin(final SwitchStart start, final UUID targetId, final long began) {
        final UUID playerId = start.account().playerId();
        final long deadline = System.currentTimeMillis() + this.settings.switching().timeoutMillis();
        final ProfileTransition started = ProfileTransition.started(playerId, start.account().activeProfile(),
            targetId, deadline, 0L);
        final SwitchCoop coop = start.coop();
        final ProfileTransition journal = coop == null ? started : started.withCoop(new ProfileTransition.Coop(
            coop.operation().operationId(), coop.operation().requestDigest(), 0L, coop.claim().fence(),
            truncateIncarnation(coop.claim().incarnation()), coop.sessionGeneration(), coop.fromEpoch(), coop.toEpoch(),
            coop.accountRevision(), Math.max(0L, coop.fromMetadata()), coop.toMetadata(), Math.max(0L, coop.fromHead()),
            Math.max(0L, coop.toHead()), 0L, null));
        if (this.switches.putIfAbsent(playerId, journal.transitionId()) != null) {
            if (this.metrics != null) this.metrics.profileFailure();
            return CompletableFuture.completedFuture(failure(ProfileErrorCode.SWITCH_IN_PROGRESS,
                "another profile switch is already running for this player", null, began));
        }
        final UUID ownerToken = UUID.randomUUID();
        final SwitchRun run = new SwitchRun(journal, start.account(), start.target(), ownerToken, began, coop);
        this.runs.add(run);
        return run.execute().whenComplete((result, failed) -> {
            this.runs.remove(run);
            transitionEnded(playerId, journal.transitionId());
        });
    }

    /**
     * Every exit path of a switch or a recovery ends here: the freeze must never outlive the run, and a
     * quit during the run asked for the session claim to be released now.
     */
    private void transitionEnded(final UUID playerId, final UUID transitionId) {
        this.frozen.remove(playerId);
        this.switches.remove(playerId, transitionId);
        if (this.releaseOnSwitchEnd.remove(playerId)) {
            // The player quit while the run was going on; the claim it left behind belongs to nobody now.
            this.releaseSession(playerId);
            if (this.sessions.containsKey(playerId)) this.releasePlayerSession(playerId, null);
        }
    }

    /**
     * Captures and saves the current state of a profile whose owner is online on this node.
     *
     * <p>Format 2: the capture ticket (the session claim {@code Q}, session generation, {@code A}, active
     * profile and membership epoch) is read before the capture and compared after it; the snapshot is published
     * into the member stream under {@link CoopWriteGuard#memberSave}, so the store refuses it when any of
     * {@code A}, {@code M} or {@code R} moved.</p>
     */
    public CompletableFuture<Void> save(final UUID profileId) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        if (format2()) return saveMembers(profileId);
        return io(() -> {
            final AuroraProfile profile = this.repository.profile(profileId)
                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + profileId));
            if (this.switches.containsKey(profile.accountId())) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch is running for "
                    + profile.accountId() + "; its capture is the authoritative save");
            }
            requireNoLoginGate(profile.accountId());
            final PlayerAccount account = this.repository.loadAccount(profile.accountId(), this.settings.defaultSlots());
            if (!profile.id().equals(account.activeProfile()) || profile.status() != ProfileStatus.ACTIVE) {
                throw error(ProfileErrorCode.PROFILE_LOCKED, "only the active profile can be saved");
            }
            requireNoPendingJournal(profile.accountId());
            return new SaveStart(profile, account.revision(), writeAuthority(profile), null);
        }).thenCompose(start -> this.players.captureDetailed(start.profile().accountId(), this.settings.isolation())
            .thenCompose(captured -> io(() -> {
                final AuroraProfile profile = start.profile();
                final ProfileState state = captured.state();
                // The switch may have started while the player thread was capturing. Writing now
                // would store the target's state under the profile being left, so it is refused.
                if (this.switches.containsKey(profile.accountId())) {
                    throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch started while the state"
                        + " was captured; the snapshot was not written");
                }
                final PlayerAccount current = this.repository.loadAccount(profile.accountId(),
                    this.settings.defaultSlots());
                if (!profile.id().equals(current.activeProfile()) || current.revision() != start.accountRevision()) {
                    throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "the active profile changed while the state"
                        + " was captured; the snapshot was not written");
                }
                // Capture may outlive a lease or a switch away and back (ABA). Validate the exact
                // claim checked before the capture, not whatever holds the profile now.
                if (start.claim() != null && this.settings.coordination().fencingEnabled()
                    && !this.ownership.validate(start.claim()).join()) {
                    throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "ownership expired during capture");
                }
                writeSnapshot(profile, state, start.claim(), captured.adapters());
                publish(FabricEventType.PROFILE_SAVE_COMPLETED, Map.of("profile", profileId.toString()));
                return null;
            })));
    }

    /**
     * Format-2 {@link #save}: every member of the profile who plays it on this node with an admitted session (the owner
     * included; review E1) is captured on their own thread and published into their own member stream under their own
     * {@link CaptureTicket}. Members mid-switch, gated at login or frozen after a refused save are skipped and named; no
     * member playing it here fails with {@link ProfileErrorCode#PLAYER_OFFLINE}. Every capture is attempted; the first
     * failure is reported after all ran.
     */
    private CompletableFuture<Void> saveMembers(final UUID profileId) {
        return io(() -> {
            final AuroraProfile profile = this.repository.profile(profileId)
                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + profileId));
            if (profile.status() != ProfileStatus.ACTIVE) {
                throw error(ProfileErrorCode.PROFILE_LOCKED, "profile " + profileId + " is " + profile.status() + "; only an ACTIVE"
                    + " profile is saved");
            }
            final List<CaptureTicket> tickets = new ArrayList<>();
            final List<String> skipped = new ArrayList<>();
            for (final Map.Entry<UUID, PlayerSession> entry : this.sessions.entrySet()) {
                final UUID member = entry.getKey();
                final PlayerSession session = entry.getValue();
                if (!profileId.equals(session.activeProfile()) || !this.players.online(member)) continue;
                if (this.switches.containsKey(member) || this.loginGates.containsKey(member) || this.saveRefused.contains(member)) {
                    skipped.add(member + " (switching, logging in, or frozen after a refused save)");
                    continue;
                }
                requireNoUnresolvedIntent(member);
                requireNoPendingJournal(member);
                requireLive(member, session);
                if (session.activeEpoch() == null || !profile.hasMember(member, session.activeEpoch())) {
                    throw error(ProfileErrorCode.STALE_STATE, "the account of " + member + " names a membership epoch that is not a"
                        + " current membership of " + profileId + "; the snapshot was not written");
                }
                tickets.add(new CaptureTicket(member, session.claim(), session.sessionGeneration(), session.accountRevision(), profileId,
                    session.activeEpoch()));
            }
            if (tickets.isEmpty()) {
                throw error(ProfileErrorCode.PLAYER_OFFLINE, "no member of profile " + profileId + " plays it on this node with an"
                    + " admitted session" + (skipped.isEmpty() ? "" : "; skipped: " + skipped) + "; nothing was written");
            }
            if (!skipped.isEmpty()) {
                SourbyLogger.info("AWF MultiProfile: saving profile " + profileId + " skips " + skipped);
            }
            return tickets;
        }).thenCompose(tickets -> {
            final List<CompletableFuture<Void>> saves = new ArrayList<>(tickets.size());
            for (final CaptureTicket ticket : tickets) {
                saves.add(this.players.captureDetailed(ticket.playerId(), this.settings.isolation())
                    .thenCompose(captured -> this.<Void>io(() -> {
                        if (this.switches.containsKey(ticket.playerId())) {
                            throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch of " + ticket.playerId() + " started while"
                                + " the state was captured; the snapshot was not written");
                        }
                        requireNoLoginGate(ticket.playerId());
                        final long began = System.nanoTime();
                        publishCaptured(ticket, captured.state(), captured.adapters());
                        if (this.metrics != null) this.metrics.snapshot(System.nanoTime() - began);
                        markVanillaSynced(ticket, "the save of " + ticket.playerId());
                        return null;
                    })));
            }
            return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).handle((ignored, failed) -> {
                publish(FabricEventType.PROFILE_SAVE_COMPLETED, Map.of("profile", profileId.toString()));
                if (failed != null) {
                    throw failed instanceof CompletionException completion ? completion : new CompletionException(failed);
                }
                return null;
            });
        });
    }

    private record SaveStart(AuroraProfile profile, long accountRevision, OwnerClaim claim, CaptureTicket ticket) {}

    /**
     * What a format-2 capture is attributed to, recorded <em>before</em> the capture: the session claim
     * {@code Q}, the session generation, {@code A}, the active profile and the membership epoch. A write of the
     * captured state compares all of them, so a capture that outlived its session, a rejoin, or a switch away and
     * back (which moves {@code A}) is refused instead of being stored under whichever profile is active later.
     *
     * @param profileId the active profile at ticket time, or {@code null} when the account had none
     */
    public record CaptureTicket(UUID playerId, PlayerClaim claim, long sessionGeneration, long accountRevision,
                                UUID profileId, UUID epoch) {}

    /**
     * The capture ticket of a player's admitted session on this node, from the session record (no I/O): the
     * quit listener takes it before it captures. {@code null} on a format-1 store or without an admitted session.
     */
    public CaptureTicket captureTicket(final UUID playerId) {
        if (!format2()) return null;
        final PlayerSession session = this.sessions.get(playerId);
        if (session == null) return null;
        return new CaptureTicket(playerId, session.claim(), session.sessionGeneration(), session.accountRevision(),
            session.activeProfile(), session.activeEpoch());
    }

    /**
     * Publishes a format-2 capture into the member stream named by its ticket, after comparing the ticket with
     * the current session and account. Runs inside an I/O step.
     */
    private ProfileSnapshot publishCaptured(final CaptureTicket ticket, final ProfileState state,
                                            final ProfileStateAdapters.Capture adapters) throws IOException {
        final UUID accountId = ticket.playerId();
        final PlayerSession session = this.sessions.get(accountId);
        if (session == null || !session.claim().equals(ticket.claim())) {
            throw error(ProfileErrorCode.CLAIM_LOST, "the player session that captured this state is no longer the"
                + " admitted one; the snapshot was not written");
        }
        requireLive(accountId, session);
        requireNoUnresolvedIntent(accountId);
        final PlayerAccount account = this.repository.loadAccount(accountId, this.settings.defaultSlots());
        if (!ticket.profileId().equals(account.activeProfile()) || !ticket.epoch().equals(account.activeEpoch())
            || account.sessionGeneration() != ticket.sessionGeneration() || account.revision() != ticket.accountRevision()) {
            throw error(ProfileErrorCode.STALE_STATE, "the account changed while the state was captured (revision "
                + ticket.accountRevision() + " -> " + account.revision() + ", session " + ticket.sessionGeneration() + " -> "
                + account.sessionGeneration() + "); the snapshot was not written");
        }
        final AuroraProfile profile = this.repository.profile(ticket.profileId())
            .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + ticket.profileId()));
        if (profile.status() != ProfileStatus.ACTIVE) {
            throw error(ProfileErrorCode.PROFILE_LOCKED, "profile " + profile.id() + " is " + profile.status());
        }
        if (!profile.hasMember(accountId, ticket.epoch())) {
            throw error(ProfileErrorCode.STALE_STATE, "membership " + ticket.epoch() + " of " + accountId + " in "
                + profile.id() + " is not current; the snapshot was not written");
        }
        return publishMemberState(profile, accountId, ticket.epoch(), state, adapters, ticket.claim(), ticket.accountRevision());
    }

    /**
     * Publishes {@code state} as the next snapshot of the stream {@code (profile, member, epoch)} under
     * {@link CoopWriteGuard#memberSave}: {@code Q(member)}, expected {@code A}, {@code M} (compared, not advanced)
     * and the head's {@code R} ({@link CoopWriteGuard#ABSENT} initialises the stream). The state keeps the
     * adapter entries of the head's snapshot no adapter of this boot accounts for. Runs inside an I/O step.
     */
    private ProfileSnapshot publishMemberState(final AuroraProfile profile, final UUID member, final UUID epoch,
                                               final ProfileState state, final ProfileStateAdapters.Capture adapters,
                                               final PlayerClaim q, final long expectedAccount)
        throws IOException {
        final Optional<MemberStateHead> head = this.repository.memberHead(profile.id(), member, epoch);
        if (head.isPresent() && head.get().state() == MemberStateHead.State.CONSUMED) {
            throw error(ProfileErrorCode.STALE_STATE, "member stream " + head.get().describe() + " was consumed; it is never"
                + " written again");
        }
        if (head.isPresent() && head.get().pendingIntent() != null) {
            throw error(ProfileErrorCode.RECOVERY_REQUIRED, "member stream " + head.get().describe()
                + " is reserved by intent " + head.get().pendingIntent());
        }
        final MemberStateHead base = head.orElseGet(() -> MemberStateHead.uninitialized(profile.id(), member, epoch));
        final long expectedHead = head.isPresent() ? head.get().revision() : CoopWriteGuard.ABSENT;
        final CoopOperation op = operation(CoopOperation.Kind.SAVE_MEMBER, member, profile.id(), epoch, expectedHead);
        final ProfileState carried = withCarriedAdapters(state, adapters, () -> head.isPresent() ? exactHeadSnapshot(head.get())
            : Optional.empty(), profile.id());
        final byte[] encoded = encode(profile.id(), carried);
        final ProfileSnapshot snapshot = ProfileSnapshot.forMember(profile.id(), member, epoch,
            CoopWriteGuard.next(base.revision(), "member head " + base.describe()), encoded, op.operationId());
        final MemberStateHead next = base.published(snapshot);
        final CommitResult result = this.repository.publishMemberSnapshot(next, snapshot, SNAPSHOTS_KEPT,
            CoopWriteGuard.memberSave(op, q, profile.id(), epoch, expectedAccount, profile.revision(), expectedHead));
        if (result.unknown()) {
            settle("the snapshot of " + next.describe(), result, () -> receipted(profile.id(), op)
                || next.equals(this.repository.memberHead(profile.id(), member, epoch).orElse(null)));
        }
        return snapshot;
    }

    /**
     * Writes a state the caller captured on the player's owning thread as a snapshot of the account's
     * active profile; see {@link #saveCaptured(UUID, ProfileState, CaptureTicket)}. On a format-2 store a capture
     * without a ticket is refused ({@link ProfileErrorCode#CLAIM_LOST}): it cannot be attributed.
     */
    public CompletableFuture<Boolean> saveCaptured(final UUID accountId, final ProfileState state) {
        return saveCaptured(accountId, state, null);
    }

    /**
     * Writes a state the caller captured on the player's owning thread as a snapshot of the account's
     * active profile. The quit listener uses it: it captures inside {@code PlayerQuitEvent}, while the
     * player still exists. Completes {@code false} when nothing was written because the account has no
     * active profile or the engine was already closed when called (shutdown closes it before the server
     * removes players); an engine closing while the step waits fails it instead. Otherwise the checks
     * of {@link #save} apply: no running switch, no pending journal, and the session claim must still
     * be valid. On a format-2 store {@code ticket} ({@link #captureTicket}, taken before the capture) decides
     * the stream and is compared with the current session and account.
     */
    public CompletableFuture<Boolean> saveCaptured(final UUID accountId, final ProfileState state, final CaptureTicket ticket) {
        return saveCaptured(accountId, PlayerStatePort.CaptureResult.of(Objects.requireNonNull(state, "state")), ticket);
    }

    /**
     * {@link #saveCaptured(UUID, ProfileState, CaptureTicket)} with the adapter registry's decisions for the capture
     * ({@link PlayerStatePort#captureDetailed}): best-effort adapters that failed keep their stored bytes, adapters
     * that returned nothing lose them.
     */
    public CompletableFuture<Boolean> saveCaptured(final UUID accountId, final PlayerStatePort.CaptureResult captured,
                                                   final CaptureTicket ticket) {
        return saveCaptured(accountId, captured, ticket, false);
    }

    /**
     * {@link #saveCaptured(UUID, PlayerStatePort.CaptureResult, CaptureTicket)} for the final capture of a session (quit,
     * shutdown): a committed publish also <em>closes</em> the account's {@link SessionMarker}; a refused one leaves it
     * open, so the next authoritative login re-examines the vanilla playerdata (D3/D4). Without an active profile the
     * marker is closed without a publish.
     */
    public CompletableFuture<Boolean> saveCapturedAndClose(final UUID accountId, final PlayerStatePort.CaptureResult captured,
                                                           final CaptureTicket ticket) {
        return saveCaptured(accountId, captured, ticket, true);
    }

    private CompletableFuture<Boolean> saveCaptured(final UUID accountId, final PlayerStatePort.CaptureResult captured,
                                                    final CaptureTicket ticket, final boolean closing) {
        Objects.requireNonNull(captured, "captured");
        final ProfileState state = captured.state();
        if (!this.settings.enabled() || this.closed) return CompletableFuture.completedFuture(false);
        return io(() -> {
            if (this.switches.containsKey(accountId)) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch is running for " + accountId
                    + "; its capture is the authoritative save");
            }
            requireNoLoginGate(accountId);
            if (format2()) {
                if (ticket == null || !ticket.playerId().equals(accountId)) {
                    throw error(ProfileErrorCode.CLAIM_LOST, "no capture ticket of an admitted session of " + accountId
                        + "; the captured state cannot be attributed and was not written");
                }
                if (ticket.profileId() == null) {
                    if (closing) markSessionClosed(ticket, false);
                    return false;
                }
                requireNoPendingJournal(accountId);
                final long began = System.nanoTime();
                final ProfileSnapshot written = publishCaptured(ticket, state, captured.adapters());
                if (this.metrics != null) this.metrics.snapshot(System.nanoTime() - began);
                publish(FabricEventType.PROFILE_SAVE_COMPLETED, Map.of("profile", written.profileId().toString()));
                if (closing) {
                    markSessionClosed(ticket, true);
                } else {
                    markVanillaSynced(ticket, "the save of " + accountId);
                }
                return true;
            }
            final PlayerAccount account = this.repository.loadAccount(accountId, this.settings.defaultSlots());
            if (account.activeProfile() == null) return false;
            final AuroraProfile profile = this.repository.profile(account.activeProfile())
                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + account.activeProfile()));
            if (profile.status() != ProfileStatus.ACTIVE) {
                throw error(ProfileErrorCode.PROFILE_LOCKED, "profile " + profile.id() + " is " + profile.status());
            }
            requireNoPendingJournal(accountId);
            writeSnapshot(profile, state, writeAuthority(profile), captured.adapters());
            publish(FabricEventType.PROFILE_SAVE_COMPLETED, Map.of("profile", profile.id().toString()));
            return true;
        });
    }

    /**
     * The claim a snapshot of the account's active profile is written under: this node's own session
     * claim on it, validated when fencing is on. Without one, a write is refused when another node
     * holds the profile, and in distributed mode always; a standalone node writes without a claim.
     */
    private OwnerClaim writeAuthority(final AuroraProfile profile) {
        final OwnerClaim session = this.sessionClaims.get(profile.accountId());
        if (session != null && session.id().equals(profile.id().toString())) {
            if (this.settings.coordination().fencingEnabled() && !this.ownership.validate(session).join()) {
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "the session claim on " + profile.id()
                    + " is no longer valid; the snapshot was not written");
            }
            return session;
        }
        // Another process, not merely another node id: a restarted server or a duplicate id shares it.
        final OwnerClaim foreign = this.ownership.foreignHolder(profile.id()).join();
        if (foreign != null) {
            throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profile.id() + " is owned by " + foreign.node());
        }
        if (this.ownership.distributed() && this.settings.coordination().fencingEnabled()) {
            throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profile.id()
                + " has no session claim on this node; distributed mode refuses to write without one");
        }
        return null;
    }

    /** Refuses while the account has an incomplete journal other than the running switch's own. */
    private void requireNoPendingJournal(final UUID accountId) throws IOException {
        final UUID running = this.switches.get(accountId);
        for (final ProfileTransition transition : this.repository.transitionsFor(accountId)) {
            if (!transition.terminal() && !transition.transitionId().equals(running)) {
                throw error(ProfileErrorCode.RECOVERY_REQUIRED, "transition " + transition.transitionId()
                    + " is incomplete; run /awf multiprofile recover " + transition.transitionId());
            }
        }
    }

    /** Replace an inactive profile's binding without creating, unloading or deleting worlds. */
    public CompletableFuture<Void> bindWorlds(final dev.iyanz.sourbycraft.api.profile.ProfileWorldBindingRequest request) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            final AuroraProfile profile = this.repository.profile(request.profileId())
                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + request.profileId()));
            final PlayerAccount account = this.repository.loadAccount(profile.accountId(), this.settings.defaultSlots());
            if (this.switches.containsKey(profile.accountId())) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "account transition in progress");
            }
            if (profile.status() != ProfileStatus.ACTIVE || profile.id().equals(account.activeProfile())) {
                throw error(ProfileErrorCode.PROFILE_LOCKED, "only an inactive, non-deleted profile can be rebound");
            }
            requireNoPendingJournal(profile.accountId());
            if (this.ownership.holder(profile.id()).join() != null) {
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "release the profile ownership before rebinding");
            }
            final Map<String, ProfileWorldRole> roles = new java.util.LinkedHashMap<>();
            request.worlds().forEach((world, role) -> roles.put(world, ProfileWorldRole.valueOf(role.name())));
            final ProfileWorldBinding binding = new ProfileWorldBinding(profile.id(), roles, request.template());
            if (format2()) {
                // Format 2: P on the profile, Q of the account, expected A, M and the binding's own revision.
                requireNoUnresolvedIntent(profile.accountId());
                final List<PlayerClaim> acquired = new ArrayList<>(1);
                ProfileClaim transaction = null;
                try {
                    final PlayerClaim q = actorClaim(profile.accountId(), acquired);
                    transaction = transactionClaim(profile.id());
                    final OptionalLong stored = this.repository.bindingRevision(profile.id());
                    final long expected = stored.isPresent() ? stored.getAsLong() : CoopWriteGuard.ABSENT;
                    final long next = stored.isPresent() ? CoopWriteGuard.next(expected, "binding of " + profile.id()) : 0L;
                    final CoopOperation op = operation(CoopOperation.Kind.BIND_WORLD, profile.accountId(), profile.id(),
                        roles.toString(), request.template(), expected);
                    final CommitResult result = this.repository.commitBinding(binding, next, CoopWriteGuard.binding(op,
                        transaction, q, account.revision(), profile.revision(), expected));
                    if (result.unknown()) {
                        settle("the binding of " + profile.id(), result, () -> receipted(profile.id(), op)
                            || (binding.equals(this.repository.binding(profile.id()).orElse(null))
                                && this.repository.bindingRevision(profile.id()).orElse(-2L) == next));
                    }
                } finally {
                    if (transaction != null) releaseTransactionClaim(transaction);
                    releasePlayerClaims(acquired);
                }
                return null;
            }
            final OwnerClaim admin = adminClaim(profile.id());
            try {
                this.repository.saveBinding(binding, ProfileWriteGuard.claimed(admin));
            } finally {
                releaseAdminClaim(admin);
            }
            return null;
        });
    }

    public CompletableFuture<List<ProfileWorldRef>> worlds(final UUID profileId) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            final Optional<ProfileWorldBinding> binding = this.repository.binding(profileId);
            if (binding.isEmpty()) return List.of();
            final List<ProfileWorldRef> refs = new ArrayList<>();
            binding.get().worlds().forEach((world, role) ->
                refs.add(new ProfileWorldRef(world, role.name(), this.worlds.loaded(world))));
            return List.copyOf(refs);
        });
    }

    public CompletableFuture<Optional<ProfileOwner>> owner(final UUID profileId) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> this.ownership.holder(profileId).join()).thenApply(claim -> Optional.ofNullable(claim)
            .map(found -> new ProfileOwner(found.node(), Math.max(1L, found.epoch()), found.expiresAtMillis(),
                this.ownership.distributed())));
    }

    /**
     * Operator recovery for a profile that another process still holds (a crashed server whose claim
     * has not expired): takes the claim over only when its fence is exactly {@code expectedFence} (the
     * epoch {@code /awf multiprofile owner} shows) and releases it at once. The profile is then free, and
     * every write the old holder makes under its claim is refused by the fenced repository. Refused when
     * nobody else holds the profile, which includes a player online on this server (let them quit).
     */
    public CompletableFuture<Boolean> reclaim(final UUID profileId, final long expectedFence) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            if (this.ownership.foreignHolder(profileId).join() == null) {
                throw error(ProfileErrorCode.INVALID_REQUEST, "profile " + profileId
                    + " is not held by another process; there is nothing to reclaim");
            }
            final ClaimResult taken = this.ownership.reclaim(profileId, expectedFence, this.nodeId, UUID.randomUUID()).join();
            if (!taken.granted()) {
                throw error(ProfileErrorCode.STALE_STATE, "the claim on " + profileId + " is no longer at fence "
                    + expectedFence + "; look it up again with /awf multiprofile owner");
            }
            this.ownership.release(taken.claim()).join();
            return true;
        });
    }

    /** Releases a claim on the engine's I/O lane; used by the join/quit listener. */
    public CompletableFuture<Boolean> release(final OwnerClaim claim) {
        if (claim == null) return CompletableFuture.completedFuture(false);
        return io(() -> this.ownership.release(claim).join());
    }

    /**
     * Claims the player's active profile for this node and records it, so quit and shutdown release
     * the same claim a switch may have installed. Refused while a switch is running: that switch owns
     * the target claim and adopts it on commit.
     */
    public CompletableFuture<ClaimResult> claimSession(final UUID playerId, final UUID profileId) {
        if (!this.settings.enabled()) {
            return CompletableFuture.failedFuture(error(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled"));
        }
        if (this.switches.containsKey(playerId)) {
            // The running switch owns the target claim and adopts it on commit; claiming the old
            // profile here would release the wrong lease at quit.
            return CompletableFuture.failedFuture(error(ProfileErrorCode.SWITCH_IN_PROGRESS,
                "a profile switch is running for " + playerId));
        }
        // Claim and record in one I/O step: the lane may dispatch the next step before a completion callback runs, so a
        // quit or save queued right after would not yet see the recorded claim (review P2).
        return io(() -> {
            final ClaimResult claim = nodeClaim(profileId, UUID.randomUUID());
            if (claim.granted() && this.switches.containsKey(playerId)) {
                // A switch started while this join was claiming the old profile; the switch's
                // adopted claim is authoritative. Release this one (unless another session or the switch itself
                // holds the same node claim), but do not turn a normal join into a duplicate-session kick.
                releaseNodeClaimUnlessHeld(claim.claim(), playerId);
                return claim;
            }
            if (claim.granted()) this.sessionClaims.put(playerId, claim.claim());
            return claim;
        });
    }

    /**
     * Releases the session claim of a player; {@code false} when none was recorded. The sharing check and the release
     * run in one I/O step (review P2-6): a join adopting the same node claim in between cannot have it released under it.
     */
    public CompletableFuture<Boolean> releaseSession(final UUID playerId) {
        final OwnerClaim claim = this.sessionClaims.remove(playerId);
        if (claim == null) return CompletableFuture.completedFuture(false);
        return io(() -> {
            releaseNodeClaimUnlessHeld(claim, playerId);
            return true;
        });
    }

    /**
     * Releases a node claim unless another player's session on this node, or a running switch or recovery, still holds
     * the same incarnation of it (a co-op profile other members play here keeps its one node claim, OD-1). Runs inside
     * an I/O step. Never releases a claim any session holds (review P2-6, P3-16).
     */
    private void releaseNodeClaimUnlessHeld(final OwnerClaim claim, final UUID exceptPlayer) {
        if (claim == null || heldByAnother(claim, exceptPlayer)) return;
        this.ownership.release(claim).join();
    }

    /** Whether a session other than {@code exceptPlayer}'s, or a running switch, holds the same incarnation of {@code claim}. */
    private boolean heldByAnother(final OwnerClaim claim, final UUID exceptPlayer) {
        if (sharedWithAnother(claim, exceptPlayer)) return true;
        final List<OwnerClaim> running = new ArrayList<>();
        for (final SwitchRun run : this.runs) {
            if (!run.playerId().equals(exceptPlayer)) run.heldClaims(running);
        }
        for (final OwnerClaim held : running) {
            if (held.sameIncarnation(claim)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ one node claim per profile (OD-1)

    /**
     * Claims a profile for this node, reusing the live claim this process already holds on it (another member's
     * session, a running switch or a recovery) instead of superseding it with a new token: a co-op profile that
     * several members play on one node has exactly one node claim, shared by their session records, and the claim
     * marks the node that plays the profile's bound world (owner decision OD-1). Without a live claim of this
     * process, the profile is claimed with {@code ownerToken}. Runs inside an I/O step.
     */
    private ClaimResult nodeClaim(final UUID profileId, final UUID ownerToken) {
        final OwnerClaim live = liveNodeClaim(profileId);
        if (live != null) return ClaimResult.granted(live);
        return this.ownership.claim(profileId, this.nodeId, ownerToken).join();
    }

    /** The validated live claim this process holds on {@code profileId}, or {@code null} (unowned, lapsed, or another process). */
    private OwnerClaim liveNodeClaim(final UUID profileId) {
        final OwnerClaim holder = this.ownership.holder(profileId).join();
        if (holder == null || !holder.node().equals(this.nodeId)) return null;
        // The same node id can name another process (a restarted server sharing the id): the provider compares processes.
        if (this.ownership.foreignHolder(profileId).join() != null) return null;
        return this.ownership.validate(holder).join() ? holder : null;
    }

    /** Whether a player other than {@code exceptPlayer} has this same claim (same profile, owner and fence) as their session claim. */
    private boolean sharedWithAnother(final OwnerClaim claim, final UUID exceptPlayer) {
        for (final Map.Entry<UUID, OwnerClaim> entry : this.sessionClaims.entrySet()) {
            if (!entry.getKey().equals(exceptPlayer) && entry.getValue().sameIncarnation(claim)) return true;
        }
        return false;
    }


    /** Whether this node recorded a session claim for the player (claimed on join or adopted by a switch). */
    public boolean holdsSession(final UUID playerId) {
        return this.sessionClaims.containsKey(playerId);
    }

    /**
     * Asks for the session claim to be released when the player's running switch ends. A quit in
     * the middle of a switch cannot release the target claim yet: the switch installs it on commit.
     * When the switch ended in the meantime, the claim is released now instead.
     */
    public void releaseSessionAfterSwitch(final UUID playerId) {
        // A quit lifts a login gate (a restore still running finds no player; a refused join is over) and a refused-save freeze.
        sessionFlagsCleared(playerId);
        this.releaseOnSwitchEnd.add(playerId);
        // The switch may have ended between the caller's check and the add above; its end handler
        // then never saw the request. Whoever removes the entry first releases, exactly once.
        if (!this.switches.containsKey(playerId) && this.releaseOnSwitchEnd.remove(playerId)) {
            this.releaseSession(playerId);
            if (this.sessions.containsKey(playerId)) this.releasePlayerSession(playerId, null);
        }
    }

    /** Cancels a pending {@link #releaseSessionAfterSwitch}: the player joined again before the switch ended. */
    public void keepSessionAfterSwitch(final UUID playerId) {
        this.releaseOnSwitchEnd.remove(playerId);
    }

    // ------------------------------------------------------------------ player sessions Q (format 2)

    /**
     * One admitted format-2 player session: its claim {@code Q} and what the account said at admission or
     * after this node's last commit of it (the {@link CaptureTicket} source).
     */
    private record PlayerSession(PlayerClaim claim, long sessionGeneration, long accountRevision, UUID activeProfile,
                                 UUID activeEpoch) {

        PlayerSession with(final PlayerAccount account) {
            return new PlayerSession(this.claim, account.sessionGeneration(), account.revision(), account.activeProfile(),
                account.activeEpoch());
        }
    }

    /**
     * The outcome of {@link #admitSession}: admitted, or refused with the holder's node when another session holds
     * {@code Q}, or not admitted with reason {@link #OFFLINE} (and no holder) when the player left during admission
     * and the claim was released again.
     */
    public record Admission(boolean admitted, String holderNode, String reason) {}

    /** {@link Admission#reason()} of a join whose player left before the admission finished. */
    public static final String OFFLINE = "the player left during admission";

    /**
     * Admits a joining player on a format-2 store, before any capture, restore or recovery: acquires the
     * player's session claim {@code Q} (strict: another live session of the player, on any node or this one,
     * refuses), then advances the account's session generation and {@code A} under it, and retries the
     * player's unresolved intents. A previous session of the player on this node whose quit has not released it
     * yet is released first (its quit save ran before this step: the I/O lane is FIFO). Completes
     * {@code admitted == false} when another session holds {@code Q}; fails on any other error, releasing
     * {@code Q} again. A format-1 store admits without a claim.
     */
    public CompletableFuture<Admission> admitSession(final UUID playerId) {
        if (!this.settings.enabled()) {
            return CompletableFuture.failedFuture(error(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled"));
        }
        if (!format2()) return CompletableFuture.completedFuture(new Admission(true, null, "format 1"));
        return io(() -> {
            if (this.switches.containsKey(playerId)) {
                // A switch of the previous session still runs under its claim; it must end before a new session.
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch of the player's previous session is"
                    + " still running; rejoin once it ended");
            }
            final PlayerSession previous = this.sessions.remove(playerId);
            if (previous != null) releaseQuietly(previous.claim());
            this.lostSessions.remove(playerId);
            this.saveRefused.remove(playerId);
            final PlayerSessionOwnership.Grant grant = this.playerSessions.claim(playerId, UUID.randomUUID()).join();
            if (!grant.granted()) {
                if ("exhausted".equals(grant.reason())) {
                    throw error(ProfileErrorCode.COUNTER_EXHAUSTED, "the session fence counter of " + playerId
                        + " is exhausted; the store needs an offline migration");
                }
                return new Admission(false, grant.holder() == null ? "another node" : grant.holder().node(), grant.reason());
            }
            final PlayerClaim claim = grant.claim();
            try {
                PlayerAccount account = this.repository.loadAccount(playerId, this.settings.defaultSlots());
                this.sessions.put(playerId, new PlayerSession(claim, account.sessionGeneration(), account.revision(),
                    account.activeProfile(), account.activeEpoch()));
                // The player's pending intents first, under the fresh Q: an account still carrying a reservation
                // would refuse the login commit below (a direct commit never touches a reservation). Not only the
                // ones this process remembers: the account's own reservation and the store's per-player index name
                // intents left by another process, a lost prepare reply or a failed abort.
                final java.util.Set<UUID> pending = new java.util.LinkedHashSet<>();
                final java.util.Set<UUID> remembered = this.unresolvedIntents.get(playerId);
                if (remembered != null) pending.addAll(remembered);
                if (account.pendingIntent() != null) pending.add(account.pendingIntent());
                pending.addAll(indexedIntents(playerId));
                if (!pending.isEmpty()) {
                    final List<UUID> unresolved = new ArrayList<>();
                    for (final UUID intent : pending) {
                        if (!resolveIntent(intent, Map.of(playerId, claim))) unresolved.add(intent);
                    }
                    if (!unresolved.isEmpty()) {
                        // Admitting now would commit LOGIN over a record a later roll-forward expects unchanged,
                        // which would leave that intent RECOVERY_REQUIRED for good.
                        throw error(ProfileErrorCode.RECOVERY_REQUIRED, "intent(s) " + unresolved + " of " + playerId
                            + " are still unresolved (another process may hold their claims, or recovery failed); the join is"
                            + " refused until they are resolved at a later join or the next start");
                    }
                    account = this.repository.loadAccount(playerId, this.settings.defaultSlots());
                    this.sessions.put(playerId, new PlayerSession(claim, account.sessionGeneration(), account.revision(),
                        account.activeProfile(), account.activeEpoch()));
                }
                final CoopOperation op = operation(CoopOperation.Kind.LOGIN, playerId, "session", claim.fence(),
                    account.sessionGeneration());
                commitAccount(account.nextSession(), accountGuard(op, claim, account.revision(), null));
                carryMarkerForward(playerId, account.sessionGeneration() + 1L);
                if (!this.players.online(playerId)) {
                    // The player quit while the admission ran; their quit found no session to release, so Q is
                    // released here instead of lingering until its lease lapses.
                    final PlayerSession admitted = this.sessions.get(playerId);
                    if (admitted != null && admitted.claim().equals(claim)) this.sessions.remove(playerId, admitted);
                    releaseQuietly(claim);
                    return new Admission(false, null, OFFLINE);
                }
                return new Admission(true, this.nodeId, "admitted");
            } catch (final IOException | RuntimeException failed) {
                this.sessions.remove(playerId);
                releaseQuietly(claim);
                throw failed;
            }
        });
    }

    /** The operations the store's per-player intent index names for {@code playerId} (bounded pages). */
    private List<UUID> indexedIntents(final UUID playerId) throws IOException {
        final List<UUID> ids = new ArrayList<>();
        ProfileIndexPage.Cursor cursor = null;
        for (int pages = 0; ; pages++) {
            if (pages == MAX_INTENT_PAGES) {
                throw error(ProfileErrorCode.RECOVERY_REQUIRED, "more than " + MAX_INTENT_PAGES * ProfileIndexPage.LISTING_PAGE
                    + " pending intents are indexed for " + playerId + "; the join is refused until an offline recovery");
            }
            final ProfileIndexPage page = this.repository.index(ProfileIndexPage.Kind.INTENTS_BY_PLAYER, playerId, null, null, cursor);
            for (final ProfileIndexPage.Entry entry : page.entries()) ids.add(entry.id());
            cursor = page.cursorAfterLast();
            if (cursor == null || page.entries().size() < ProfileIndexPage.LISTING_PAGE) break;
        }
        return ids;
    }

    /** The session claim {@code Q} of a player's admitted session on this node, or {@code null}. */
    public PlayerClaim playerSessionClaim(final UUID playerId) {
        final PlayerSession session = this.sessions.get(playerId);
        return session == null ? null : session.claim();
    }

    /**
     * Ends a player's format-2 session on this node: releases exactly {@code expected} ({@code null}: whatever
     * session is recorded), so a quit that runs after the player joined again never releases the new session.
     * Also clears a recorded claim loss. Runs on the I/O lane, after the quit save queued before it.
     */
    public CompletableFuture<Boolean> releasePlayerSession(final UUID playerId, final PlayerClaim expected) {
        return io(() -> {
            final PlayerSession session = this.sessions.get(playerId);
            if (session == null || (expected != null && !session.claim().equals(expected))) {
                if (expected == null || session == null) this.lostSessions.remove(playerId);
                return false;
            }
            if (!this.sessions.remove(playerId, session)) return false;
            this.lostSessions.remove(playerId);
            this.saveRefused.remove(playerId);
            return this.playerSessions.release(session.claim()).join();
        });
    }

    /**
     * Called with the player id when a session claim is confirmed lost (renewal or validation answered "not
     * held"). The runtime disconnects the player through its entity scheduler; the engine has already frozen
     * them and refuses their captures and mutations. Called on an I/O worker.
     */
    public void onSessionLost(final Consumer<UUID> handler) {
        this.sessionLostHandler = Objects.requireNonNull(handler, "handler");
    }

    /** Validates an admitted session's claim; a confirmed loss freezes the player and refuses with {@link ProfileErrorCode#CLAIM_LOST}. */
    private void requireLive(final UUID playerId, final PlayerSession session) {
        final boolean live;
        try {
            live = this.playerSessions.validate(session.claim()).join();
        } catch (final RuntimeException uncertain) {
            // A timeout is not proof of expiry: refuse the mutation, keep the session.
            throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "the player session claim of " + playerId + " could not be"
                + " validated (" + messageOf(uncertain) + "); nothing was written");
        }
        if (!live) {
            sessionLost(playerId, session, "validation found it no longer held");
            throw error(ProfileErrorCode.CLAIM_LOST, "the player session claim of " + playerId + " is no longer held;"
                + " nothing was written and the player is frozen until they rejoin");
        }
    }

    private void sessionLost(final UUID playerId, final PlayerSession session, final String why) {
        // Matched by claim: the record's account fields change with this node's own commits.
        final PlayerSession current = this.sessions.get(playerId);
        if (current == null || !current.claim().equals(session.claim()) || !this.sessions.remove(playerId, current)) return;
        this.lostSessions.add(playerId);
        SourbyLogger.error("AWF MultiProfile: the session claim of " + playerId + " (fence " + session.claim().fence()
            + ") was lost: " + why + ". The player is frozen, their captures and profile changes are refused, and they"
            + " are disconnected; the claim is not taken again for this session");
        try {
            this.sessionLostHandler.accept(playerId);
        } catch (final RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: disconnecting " + playerId + " after a lost session claim failed: "
                + failed.getMessage());
        }
    }

    /**
     * The {@code Q} a format-2 mutation of {@code accountId} is made under: the player's admitted session on this
     * node (validated), else a short administrative claim of its own, added to {@code acquired} for the caller to
     * release. A live session of the player elsewhere refuses ({@link ProfileErrorCode#OWNERSHIP_CONFLICT}).
     */
    private PlayerClaim actorClaim(final UUID accountId, final List<PlayerClaim> acquired) {
        final PlayerSession session = this.sessions.get(accountId);
        if (session != null) {
            requireLive(accountId, session);
            return session.claim();
        }
        if (this.lostSessions.contains(accountId)) {
            throw error(ProfileErrorCode.CLAIM_LOST, "the session claim of " + accountId + " was lost on this node; nothing"
                + " is written for the account until the player rejoins or quits");
        }
        final PlayerSessionOwnership.Grant grant = this.playerSessions.claim(accountId, UUID.randomUUID()).join();
        if (!grant.granted()) {
            if ("exhausted".equals(grant.reason())) {
                throw error(ProfileErrorCode.COUNTER_EXHAUSTED, "the session fence counter of " + accountId
                    + " is exhausted; the store needs an offline migration");
            }
            throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "the account " + accountId + " is in use by a session on "
                + (grant.holder() == null ? "another node" : grant.holder().node()) + "; nothing was written");
        }
        acquired.add(grant.claim());
        return grant.claim();
    }

    private void releasePlayerClaims(final List<PlayerClaim> acquired) {
        for (final PlayerClaim claim : acquired) releaseQuietly(claim);
        acquired.clear();
    }

    private void releaseQuietly(final PlayerClaim claim) {
        try {
            this.playerSessions.release(claim).join();
        } catch (final RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: releasing the session claim of " + claim.playerId() + " failed ("
                + messageOf(failed) + "); it expires on its own");
        }
    }

    /** {@code P}: the short exclusive transaction claim of a profile; refused while another live token holds it. */
    private ProfileClaim transactionClaim(final UUID profileId) {
        final ProfileOwnership.TransactionGrant grant = this.ownership.claimTransaction(profileId, UUID.randomUUID()).join();
        if (grant.granted()) return grant.claim();
        if ("exhausted".equals(grant.reason())) {
            throw error(ProfileErrorCode.COUNTER_EXHAUSTED, "the transaction fence counter of " + profileId
                + " is exhausted; the store needs an offline migration");
        }
        throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profileId + " is in a transaction on "
            + (grant.holder() == null ? "another node" : grant.holder().node()) + "; nothing was written");
    }

    private void releaseTransactionClaim(final ProfileClaim claim) {
        try {
            this.ownership.releaseTransaction(claim).join();
        } catch (final RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: releasing the transaction claim on " + claim.profileId() + " failed ("
                + messageOf(failed) + "); it expires on its own");
        }
    }


    /**
     * Makes the active profile's bound worlds ready for a player who just joined, and returns the
     * player to the profile when the join left them outside its worlds: profile worlds are not loaded
     * at startup, so a player whose last location was in one is placed elsewhere by the server. The
     * player is moved only with {@code isolation.location} on, only while no journal of the account is
     * pending, only when they are in none of the profile's worlds and one of those was not loaded, and
     * only to a destination inside them: the last location of the profile's latest snapshot, or the
     * spawn of its PRIMARY world when that snapshot holds no location. A last location outside the
     * bound worlds moves nobody. The latest snapshot can be older than the player's last session (no
     * quit snapshot is written at shutdown), and then so is that location.
     *
     * <p>The whole preparation is bounded by {@code switching.timeout-ms}; once it timed out it issues
     * no teleport. The teleport itself runs under a switch reservation ({@link #switchInProgress}), so
     * no switch interleaves with it, and only after the account was read again and still names the
     * same active profile with no pending journal: a switch that ran while the worlds loaded wins.
     * Completes {@code true} when the player was moved; never fails, a failure is logged.</p>
     *
     * @param joinedWorld the world the player is in, read at join on the thread that owns the player
     */
    public CompletableFuture<Boolean> prepareActive(final UUID playerId, final String joinedWorld) {
        if (!this.settings.enabled() || this.closed) return CompletableFuture.completedFuture(false);
        final AtomicBoolean ended = new AtomicBoolean();
        final String current = joinedWorld.toLowerCase(java.util.Locale.ROOT);
        return io(() -> joinTarget(playerId, current))
            .thenCompose(target -> target == null ? CompletableFuture.completedFuture(false)
                : this.worlds.ensureReady(target.binding()).thenCompose(ready -> returnToProfile(playerId, target, ended)))
            .orTimeout(this.settings.switching().timeoutMillis(), TimeUnit.MILLISECONDS)
            .handle((moved, failed) -> {
                ended.set(true);
                if (failed == null) return moved;
                SourbyLogger.warn("AWF MultiProfile: preparing the active profile of " + playerId + " on join failed: "
                    + (unwrapCompletion(failed) instanceof TimeoutException
                        ? "it did not finish within switching.timeout-ms" : messageOf(failed)));
                return false;
            });
    }

    /** What {@link #prepareActive} makes ready, and where it moves the player. */
    private record JoinTarget(UUID profile, ProfileWorldBinding binding, ProfileState.Location destination,
                              String primary) {}

    /** Reads the active profile's binding and the join destination; runs inside an I/O step. */
    private JoinTarget joinTarget(final UUID playerId, final String current) throws IOException {
        final PlayerAccount account = this.repository.loadAccount(playerId, this.settings.defaultSlots());
        final UUID profile = account.activeProfile();
        if (profile == null) return null;
        final ProfileWorldBinding binding = this.repository.binding(profile).orElse(null);
        if (binding == null || binding.isEmpty()) return null;
        binding.worlds().keySet().forEach(this::touch);
        final java.util.Set<String> bound = binding.worlds().keySet();
        final boolean placedOutside = !bound.contains(current)
            && bound.stream().anyMatch(world -> !this.worlds.loaded(world));
        if (!placedOutside || !this.settings.isolation().location() || pendingJournal(playerId)) {
            return new JoinTarget(profile, binding, null, null);
        }
        // Format 2: the member head's exact snapshot, never the latest one of the profile.
        final Optional<ProfileSnapshot> stored = format2()
            ? (account.activeEpoch() == null ? Optional.empty() : headSnapshot(profile, playerId, account.activeEpoch()))
            : this.repository.latestSnapshot(profile);
        final ProfileState.Location last = stored
            .map(snapshot -> ProfileStateCodec.decode(snapshot.state()).returnPoint())
            .orElse(null);
        if (last == null) return new JoinTarget(profile, binding, null, primaryWorld(binding));
        return new JoinTarget(profile, binding, bound.contains(last.world()) ? last : null, null);
    }

    /**
     * Teleports a joined player to the {@link JoinTarget} destination once its worlds are ready, under a
     * switch reservation that ends with the teleport (bounded by {@code switching.timeout-ms}).
     */
    private CompletableFuture<Boolean> returnToProfile(final UUID playerId, final JoinTarget target,
                                                       final AtomicBoolean ended) {
        if (target.destination() == null && target.primary() == null) return CompletableFuture.completedFuture(false);
        // A preparation that already timed out, or a player who left, is not moved.
        if (ended.get() || this.closed || !this.players.online(playerId)) return CompletableFuture.completedFuture(false);
        final UUID reservation = UUID.randomUUID();
        if (this.switches.putIfAbsent(playerId, reservation) != null) {
            // A switch or a recovery runs; it decides where the player goes.
            return CompletableFuture.completedFuture(false);
        }
        return io(() -> target.profile().equals(this.repository.loadAccount(playerId, this.settings.defaultSlots())
                .activeProfile()) && !pendingJournal(playerId))
            .thenCompose(unchanged -> {
                if (!unchanged || ended.get() || !this.players.online(playerId)) {
                    return CompletableFuture.completedFuture(false);
                }
                final ProfileState.Location destination = target.destination() != null ? target.destination()
                    : this.worlds.spawnOf(target.primary());
                if (destination == null) {
                    return CompletableFuture.<Boolean>failedFuture(error(ProfileErrorCode.WORLD_UNAVAILABLE,
                        "the PRIMARY world '" + target.primary() + "' is not loaded"));
                }
                return this.players.teleport(playerId, destination).thenApply(moved -> true);
            })
            .orTimeout(this.settings.switching().timeoutMillis(), TimeUnit.MILLISECONDS)
            .whenComplete((moved, failed) -> transitionEnded(playerId, reservation));
    }

    // ------------------------------------------------------------------ authoritative login (Build 53)

    /**
     * Gates a joining player until {@link #restoreOnLogin} ran (format 2 with {@code login.authoritative}): from this
     * call their captures, saves and switches are refused and their item events are cancelled, so nothing of the
     * vanilla playerdata the join loaded is written to a profile or traded. Call on the thread that owns the player,
     * inside the join event, before the session is admitted. Returns the gate token, or {@code null} when no gate
     * applies (feature off, format 1, or {@code login.authoritative} false); every exit of the join passes the token
     * to {@link #releaseLoginGate}.
     */
    public UUID gateLogin(final UUID playerId) {
        if (!this.settings.enabled() || this.closed || !format2() || !this.settings.authoritativeLogin()) return null;
        final UUID token = UUID.randomUUID();
        this.loginGates.put(playerId, new LoginGate(token, false));
        return token;
    }

    /**
     * Lifts the gate {@code token} installed by {@link #gateLogin}; another player's or a later join's gate, and a gate
     * {@link #holdLoginGate held} after a refused join, are left alone.
     */
    public void releaseLoginGate(final UUID playerId, final UUID token) {
        if (token != null) this.loginGates.remove(playerId, new LoginGate(token, false));
    }

    /**
     * Keeps the gate until the player quits (the runtime disconnects a refused join): the vanilla playerdata, or a
     * half-applied state, on the player must not be captured by the quit that follows. The quit clears every gate.
     */
    public void holdLoginGate(final UUID playerId, final UUID token) {
        if (token != null) this.loginGates.replace(playerId, new LoginGate(token, false), new LoginGate(token, true));
    }

    /** Whether the gate installed for {@code token} is still the player's current one (a quit or a later join replaces it). */
    public boolean gateHeld(final UUID playerId, final UUID token) {
        final LoginGate gate = this.loginGates.get(playerId);
        return token != null && gate != null && gate.token().equals(token);
    }

    // ------------------------------------------------------------------ refused saves (Build 53, D4)

    /**
     * Records that the player's state cannot be saved (a capture or publish failed with a code that repeats until an
     * operator or plugin acts: {@link ProfileErrorCode#STATE_TOO_LARGE}, {@link ProfileErrorCode#ADAPTER_REQUIRED_FAILED}):
     * the player is frozen (item events cancelled, switches refused) and the runtime disconnects them. Cleared when
     * their session ends. Idempotent.
     */
    public void refuseSaves(final UUID playerId, final String reason) {
        if (!this.saveRefused.add(playerId)) return;
        SourbyLogger.error("AWF MultiProfile: the state of " + playerId + " cannot be saved (" + reason + "); the player is frozen"
            + " and disconnected, their quit capture is attempted once more, and the next login re-examines their vanilla playerdata");
        try {
            this.saveRefusedHandler.accept(playerId, reason);
        } catch (final RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: disconnecting " + playerId + " after a refused save failed: " + failed.getMessage());
        }
    }

    /** Whether a failed save with this code repeats until someone acts, so the player must not keep playing on it. */
    public static boolean fatalSaveCode(final ProfileErrorCode code) {
        return code == ProfileErrorCode.STATE_TOO_LARGE || code == ProfileErrorCode.ADAPTER_REQUIRED_FAILED;
    }

    /** Called with the player id and reason when {@link #refuseSaves} first fires; called on the calling thread. */
    public void onSaveRefused(final java.util.function.BiConsumer<UUID, String> handler) {
        this.saveRefusedHandler = Objects.requireNonNull(handler, "handler");
    }

    /** Whether {@link #refuseSaves} fired for the player's current session. */
    public boolean savesRefused(final UUID playerId) {
        return this.saveRefused.contains(playerId);
    }

    private void requireNoLoginGate(final UUID playerId) {
        if (this.loginGates.containsKey(playerId)) {
            throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "the join of " + playerId + " is still restoring the committed state of"
                + " their active profile; nothing is captured, saved or switched until it finished");
        }
    }

    /** What {@link #restoreOnLogin} did. */
    public record LoginRestore(Outcome outcome, UUID profileId, ProfileErrorCode code, String detail) {

        public enum Outcome {
            /** No active profile, format 1, or {@code login.authoritative} off: the join keeps what it loaded. */
            NONE,
            /** The committed member head (or the member's empty initial state) was applied to the player. */
            RESTORED,
            /** The vanilla playerdata the join loaded was adopted once into the active profile's head (D3); nothing was applied. */
            ADOPTED,
            /** The join must be refused: the player is disconnected with {@code code} and {@code detail}. */
            REFUSED
        }

        static LoginRestore none(final String detail) {
            return new LoginRestore(Outcome.NONE, null, null, detail);
        }
    }

    /** {@link #restoreOnLogin(UUID, UUID, java.util.function.BooleanSupplier, boolean)} without a gate binding or recovery flag. */
    public CompletableFuture<LoginRestore> restoreOnLogin(final UUID playerId) {
        return restoreOnLogin(playerId, null, () -> false, false);
    }

    /**
     * Authoritative login (COOP-DESIGN §6, Build 53): restores the committed member head of the player's active
     * profile onto the player, replacing the vanilla playerdata the join loaded, or adopts that vanilla playerdata
     * once into the head (D3), and refuses the join when neither can be done. Runs after {@link #admitSession} and
     * {@link #recoverPending}, under the gate of {@link #gateLogin}; the caller lifts the gate afterwards and
     * disconnects a {@code REFUSED} player with the recorded reason. Decisions, in order:
     * <ul>
     *   <li>no active profile, format 1, {@code login.authoritative} not applying: {@code NONE}, nothing is applied;</li>
     *   <li>a pending journal of the player that {@link #recoverPending} could not resolve, or an unresolved intent:
     *       {@code REFUSED RECOVERY_REQUIRED} (never vanilla playerdata over an undecided state, review P1-4);</li>
     *   <li>the active profile is a co-op whose bound world another node holds: {@code REFUSED WRONG_NODE} (OD-1);</li>
     *   <li>the account names a membership epoch the profile no longer has, or a CONSUMED stream: {@code REFUSED
     *       HEAD_UNREADABLE} (the account and the profile disagree; nothing is guessed);</li>
     *   <li>the member head cannot be read, its exact snapshot is missing or differs, or the state does not decode:
     *       {@code REFUSED HEAD_UNREADABLE}; vanilla playerdata is never accepted instead;</li>
     *   <li><b>adoption (D3):</b> the account's {@link SessionMarker} is read. The previous session counts as
     *       <em>unclean</em> when the marker is still open for the previous generation, names another generation
     *       (sessions ran under Build 52 or without authoritative login), or is absent (the upgrade from Build 52).
     *       Then, when this server's vanilla playerdata file of the player is newer than the head's snapshot
     *       ({@code createdAtMillis}; an UNINITIALIZED head counts as never written), no journal was recovered at
     *       this login, and the marker's vanilla provenance names the active profile and epoch on this node (an
     *       absent or other-generation marker is taken to mean Build 52 semantics: vanilla holds the active profile's
     *       live state), the state the join loaded is captured on the player's thread and published as the head's next
     *       snapshot under the login's ticket: {@code ADOPTED}, nothing is applied, and {@link ProfileState#empty()} is
     *       never applied over existing vanilla data. A publish that is refused (STATE_TOO_LARGE, a REQUIRED adapter,
     *       a reserved stream, CAS) {@code REFUSES} the join with that code;</li>
     *   <li>the state holds data of a required adapter that cannot be applied here: {@code REFUSED ADAPTER_MISSING} or
     *       {@code ADAPTER_REQUIRED_FAILED} ({@link ProfileStateAdapters#validateApply});</li>
     *   <li>an {@code UNINITIALIZED} head (first entry of a member into a profile, or a profile never saved since it
     *       was applied) with no adoption: the member's <b>initial state</b> is {@link ProfileState#empty()} — empty
     *       inventory, armor, off-hand and ender chest, level 0, full health and food, no effects, no respawn point, no
     *       adapter data — which is applied and then published as the stream's first snapshot under the session's
     *       ticket; a failure to publish it is logged, the state is applied all the same and the next save publishes;</li>
     *   <li>a {@code READY} head: its exact snapshot is applied on the player's thread, bounded by
     *       {@code switching.timeout-ms}; an apply that fails or times out {@code REFUSES} the join
     *       ({@code ADAPTER_REQUIRED_FAILED} for a required adapter, else {@code TIMEOUT}/{@code INTERNAL_ERROR}): the
     *       player is disconnected with the gate still refusing captures, so the half-applied state is never saved,
     *       and the next join restores the same committed head again.</li>
     * </ul>
     * The marker was carried to this session's generation at admission; a restore writes it <em>open</em> with the
     * provenance cleared before the gate lifts (a marker that cannot be written {@code REFUSES} the join), an adoption
     * writes it open with the provenance set only once its publish committed, so a refused adoption is retried at the
     * next login. The profile's
     * bound worlds are made ready before the apply, so a respawn point in one of them is kept. Never fails: an
     * unexpected error is a {@code REFUSED} result.
     *
     * @param gate the token {@link #gateLogin} returned; every step checks it is still the player's gate (a quit or a
     *     later join of the same account replaces it) and {@code REFUSES} otherwise, so a stale chain never applies to
     *     a new connection (review P2-5); {@code null} skips that check
     * @param stale the runtime's own staleness check (the {@code Player} instance of the join is no longer the online
     *     one); checked before every apply and publish
     * @param recoveredJournal whether {@link #recoverPending} resolved a journal at this login: the recovery decided the
     *     state, so the vanilla playerdata is never adopted
     */
    public CompletableFuture<LoginRestore> restoreOnLogin(final UUID playerId, final UUID gate,
                                                          final java.util.function.BooleanSupplier stale, final boolean recoveredJournal) {
        if (!this.settings.enabled() || this.closed || !format2() || !this.settings.authoritativeLogin()) {
            return CompletableFuture.completedFuture(LoginRestore.none("authoritative login is off"));
        }
        final AtomicBoolean abandoned = new AtomicBoolean();
        final java.util.function.BooleanSupplier abandon = () -> abandoned.get() || stale.getAsBoolean()
            || (gate != null && !gateHeld(playerId, gate));
        final long budget = this.settings.switching().timeoutMillis();
        return io(() -> {
                requireCurrentLogin(playerId, abandon);
                return loginSource(playerId, recoveredJournal);
            })
            .thenCompose(source -> {
                // A refusal or a NONE decided inside loginSource reaches the caller as is: nothing is applied or written
                // for it (the documented refusal code, not an INTERNAL_ERROR from applying a null state).
                if (source.restore().outcome() == LoginRestore.Outcome.NONE
                    || source.restore().outcome() == LoginRestore.Outcome.REFUSED) {
                    return CompletableFuture.completedFuture(source.restore);
                }
                if (source.adopt()) return adoptVanilla(playerId, source, abandon);
                final CompletableFuture<Void> worlds = source.binding() == null || source.binding().isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : this.worlds.ensureReady(source.binding()).exceptionally(failed -> {
                        SourbyLogger.warn("AWF MultiProfile: the worlds of " + source.profileId() + " could not be made ready"
                            + " before restoring " + playerId + " (" + messageOf(failed) + "); a respawn point in one of them"
                            + " is cleared by the apply");
                        return null;
                    });
                return worlds.thenCompose(ignored -> this.players.apply(playerId, source.state(), this.settings.isolation(), abandon))
                    .orTimeout(budget, TimeUnit.MILLISECONDS)
                    .thenCompose(applied -> source.initialise() ? io(() -> {
                        requireCurrentLogin(playerId, abandon);
                        publishInitialState(playerId, source);
                        return source.restore;
                    }) : CompletableFuture.completedFuture(source.restore))
                    .handle((restored, failed) -> {
                        if (failed == null) return restored;
                        abandoned.set(true);
                        final Throwable cause = unwrapCompletion(failed);
                        final ProfileErrorCode code = cause instanceof TimeoutException ? ProfileErrorCode.TIMEOUT : codeOf(failed);
                        return new LoginRestore(LoginRestore.Outcome.REFUSED, source.profileId(), code,
                            "restoring the committed state of profile " + source.profileId() + " onto the player did not complete ("
                                + (cause instanceof TimeoutException ? "not within switching.timeout-ms" : messageOf(failed))
                                + "); the join is refused and the same committed state is restored at the next join");
                    });
            })
            .exceptionally(failed -> new LoginRestore(LoginRestore.Outcome.REFUSED, null, codeOf(failed), messageOf(failed)));
    }

    /** Refuses a login step whose join is no longer current (gate replaced, player instance gone, or abandoned). */
    private static void requireCurrentLogin(final UUID playerId, final java.util.function.BooleanSupplier abandon) {
        if (abandon.getAsBoolean()) {
            throw error(ProfileErrorCode.STALE_STATE, "the join of " + playerId + " that started this restore is no longer the"
                + " current one (the player quit or joined again); nothing is applied or written for it");
        }
    }

    /**
     * D3 adoption: captures the state the join loaded (on the player's thread) and publishes it as the next snapshot of
     * the active profile's member stream under the login's ticket, then records the vanilla provenance. A refusal
     * anywhere {@code REFUSES} the join; nothing is applied to the player either way.
     */
    private CompletableFuture<LoginRestore> adoptVanilla(final UUID playerId, final LoginSource source,
                                                         final java.util.function.BooleanSupplier abandon) {
        final long budget = this.settings.switching().timeoutMillis();
        return this.players.captureDetailed(playerId, this.settings.isolation())
            .orTimeout(budget, TimeUnit.MILLISECONDS)
            .thenCompose(captured -> io(() -> {
                requireCurrentLogin(playerId, abandon);
                final PlayerSession session = this.sessions.get(playerId);
                if (session == null || !session.claim().equals(source.ticket().claim())) {
                    throw error(ProfileErrorCode.CLAIM_LOST, "the session that started this login is no longer admitted");
                }
                final CaptureTicket ticket = new CaptureTicket(playerId, session.claim(), session.sessionGeneration(),
                    session.accountRevision(), source.profileId(), source.epoch());
                final ProfileSnapshot written = publishCaptured(ticket, captured.state(), captured.adapters());
                markVanillaSynced(ticket, "the login adoption of " + playerId);
                return new LoginRestore(LoginRestore.Outcome.ADOPTED, source.profileId(), null, "adopted the vanilla playerdata ("
                    + source.adoptWhy() + ") as snapshot " + written.snapshotId() + " (R " + written.revision() + ") of member stream "
                    + source.profileId() + "/" + playerId + "/" + source.epoch() + "; nothing was applied to the player");
            }))
            .handle((adopted, failed) -> {
                if (failed == null) return adopted;
                final Throwable cause = unwrapCompletion(failed);
                final ProfileErrorCode code = cause instanceof TimeoutException ? ProfileErrorCode.TIMEOUT : codeOf(failed);
                return new LoginRestore(LoginRestore.Outcome.REFUSED, source.profileId(), code, "the vanilla playerdata of " + playerId
                    + " is newer than the committed state of profile " + source.profileId() + " (" + source.adoptWhy() + ") but could not"
                    + " be adopted into it (" + (cause instanceof TimeoutException ? "not within switching.timeout-ms" : messageOf(failed))
                    + "); the join is refused so neither state is lost or applied twice. Fix the cause and rejoin");
            });
    }

    /**
     * What the login restores: the state, where it came from, whether the stream is initialised afterwards, and whether
     * the vanilla playerdata is adopted instead ({@code adopt}, with {@code adoptWhy} for the log).
     */
    private record LoginSource(LoginRestore restore, UUID profileId, UUID epoch, ProfileState state, ProfileWorldBinding binding,
                               boolean initialise, CaptureTicket ticket, boolean adopt, String adoptWhy) {

        static LoginSource none(final String detail) {
            return new LoginSource(LoginRestore.none(detail), null, null, null, null, false, null, false, null);
        }

        static LoginSource refused(final UUID profileId, final ProfileErrorCode code, final String detail) {
            return new LoginSource(new LoginRestore(LoginRestore.Outcome.REFUSED, profileId, code, detail), profileId, null, null,
                null, false, null, false, null);
        }
    }

    /** Reads the account, profile, head and exact snapshot of the login and decides the adoption; runs inside an I/O step. */
    private LoginSource loginSource(final UUID playerId, final boolean recoveredJournal) throws IOException {
        final PlayerSession session = this.sessions.get(playerId);
        if (session == null) return LoginSource.none("no admitted session");
        final PlayerAccount account = this.repository.loadAccount(playerId, this.settings.defaultSlots());
        final UUID profileId = account.activeProfile();
        if (profileId == null) return LoginSource.none("no active profile");
        if (pendingJournal(playerId)) {
            // Review P1-4: recovery did not resolve it; the vanilla state must not be played (or saved) over an undecided one.
            return LoginSource.refused(profileId, ProfileErrorCode.RECOVERY_REQUIRED, "a transition of " + playerId + " is still pending"
                + " after recovery; the join is refused until it is resolved (/awf multiprofile recover) so vanilla playerdata is"
                + " never played over an undecided state");
        }
        try {
            requireNoUnresolvedIntent(playerId);
        } catch (final ProfileOperationException pending) {
            return LoginSource.refused(profileId, pending.code(), pending.getMessage());
        }
        final AuroraProfile profile = this.repository.profile(profileId).orElse(null);
        if (profile == null || profile.status() == ProfileStatus.DELETED) {
            return LoginSource.refused(profileId, ProfileErrorCode.HEAD_UNREADABLE, "the account names active profile " + profileId
                + ", which is " + (profile == null ? "not stored" : "deleted") + "; nothing is restored and the join is refused");
        }
        if (profile.coop()) {
            final OwnerClaim foreign = this.ownership.foreignHolder(profileId).join();
            if (foreign != null) {
                return LoginSource.refused(profileId, ProfileErrorCode.WRONG_NODE, "co-op profile " + profileId + " is played on node "
                    + foreign.node() + " (owner decision OD-1: one node per co-op, no routing); join that server, or switch to another"
                    + " profile from there");
            }
        }
        final UUID epoch = account.activeEpoch();
        if (epoch == null || !profile.hasMember(playerId, epoch)) {
            return LoginSource.refused(profileId, ProfileErrorCode.HEAD_UNREADABLE, "the account names membership epoch " + epoch
                + " of " + profileId + ", which the profile does not have; the committed state cannot be identified");
        }
        final ProfileWorldBinding binding = this.repository.binding(profileId).orElse(null);
        final MemberStateHead head;
        final ProfileState state;
        boolean initialise = false;
        long headWrittenAt = 0L;
        try {
            head = this.repository.memberHead(profileId, playerId, epoch)
                .orElseGet(() -> MemberStateHead.uninitialized(profileId, playerId, epoch));
            switch (head.state()) {
                case CONSUMED -> {
                    return LoginSource.refused(profileId, ProfileErrorCode.HEAD_UNREADABLE, "member stream " + head.describe()
                        + " was consumed by " + head.consumingOperationId() + " but the account still names it active; nothing is"
                        + " restored");
                }
                case UNINITIALIZED -> {
                    state = ProfileState.empty();
                    initialise = head.pendingIntent() == null;
                }
                case READY -> {
                    final Optional<ProfileSnapshot> snapshot = exactHeadSnapshot(head);
                    if (snapshot.isEmpty()) {
                        return LoginSource.refused(profileId, ProfileErrorCode.HEAD_UNREADABLE, "member head " + head.describe()
                            + " names no readable snapshot");
                    }
                    state = ProfileStateCodec.decode(snapshot.get().state());
                    headWrittenAt = snapshot.get().createdAtMillis();
                }
                default -> throw new IllegalStateException("head state " + head.state());
            }
        } catch (final IOException | RuntimeException unreadable) {
            final String why = unreadable instanceof ProfileOperationException operation ? operation.getMessage()
                : unreadable.getMessage();
            return LoginSource.refused(profileId, ProfileErrorCode.HEAD_UNREADABLE, "the committed state of " + playerId + " in "
                + profileId + " (epoch " + epoch + ") could not be read: " + why + "; vanilla playerdata is not used instead and"
                + " the join is refused");
        }
        final CaptureTicket ticket = new CaptureTicket(playerId, session.claim(), session.sessionGeneration(),
            session.accountRevision(), profileId, epoch);
        // D3: decide the adoption from the session marker (carried to this generation at admission) before anything is
        // applied. An adoption leaves the marker as it is until its publish committed (a refused adoption is retried at
        // the next login); a restore opens this session's marker with the provenance cleared first.
        final String adoptWhy = adoptionReason(playerId, session, profileId, epoch, headWrittenAt, recoveredJournal);
        if (adoptWhy != null) {
            if (binding != null) binding.worlds().keySet().forEach(this::touch);
            return new LoginSource(new LoginRestore(LoginRestore.Outcome.ADOPTED, profileId, null, adoptWhy), profileId, epoch, null,
                binding, false, ticket, true, adoptWhy);
        }
        try {
            if (!this.repository.writeSessionMarker(SessionMarker.opened(playerId, session.sessionGeneration(), this.nodeId))
                && this.markersUnsupportedLogged.compareAndSet(false, true)) {
                SourbyLogger.warn("AWF MultiProfile: the repository " + this.repository.describe() + " stores no session markers; every"
                    + " authoritative login treats the previous session as unclean and applies the upgrade rule");
            }
        } catch (final IOException | RuntimeException failed) {
            return LoginSource.refused(profileId, ProfileErrorCode.IO_FAILURE, "the session marker of " + playerId + " could not be"
                + " written (" + messageOf(failed) + "); without it a crash in this session could not be told apart from a clean"
                + " quit, so the join is refused");
        }
        try {
            this.players.validateApply(state);
        } catch (final ProfileOperationException missing) {
            return LoginSource.refused(profileId, missing.code(), missing.getMessage());
        }
        if (binding != null) binding.worlds().keySet().forEach(this::touch);
        return new LoginSource(new LoginRestore(LoginRestore.Outcome.RESTORED, profileId, null, head.state() == MemberStateHead.State.READY
            ? "restored snapshot " + head.snapshotId() + " (R " + head.revision() + ")" : "applied the member's initial state"),
            profileId, epoch, state, binding, initialise, ticket, false, null);
    }

    /**
     * The D3 adoption rule; {@code null} when the committed head is restored, else the reason the vanilla playerdata is
     * adopted. Runs inside an I/O step. Unclean previous session: marker open for generation {@code g-1}, of another
     * generation, or absent. Vanilla newer: the file's modification time is after the head snapshot's
     * {@code createdAtMillis} (0 for an UNINITIALIZED head). Provenance: the marker names this profile, epoch and node,
     * or (absent / other generation) Build 52 semantics are assumed. Never after a recovered journal.
     */
    private String adoptionReason(final UUID playerId, final PlayerSession session, final UUID profileId, final UUID epoch,
                                  final long headWrittenAt, final boolean recoveredJournal) throws IOException {
        if (recoveredJournal) return null;
        final long generation = session.sessionGeneration();
        final Optional<SessionMarker> marker;
        try {
            marker = this.repository.sessionMarker(playerId);
        } catch (final IOException corrupt) {
            // Safe choice: the committed head is restored (progress in vanilla playerdata since the last committed capture
            // may be lost, nothing is duplicated); the admission of the next login replaces the record. The operator can
            // force the same outcome with /awf multiprofile session <player> reset-marker.
            SourbyLogger.warn("AWF MultiProfile: the session marker of " + playerId + " is unreadable (" + corrupt.getMessage()
                + "); the committed head is restored and the vanilla playerdata is not adopted");
            return null;
        }
        final String unclean;
        final boolean provenanceKnown;
        if (marker.isEmpty()) {
            unclean = "no session marker (first authoritative login of this account, e.g. the upgrade from Build 52)";
            provenanceKnown = false;
        } else if (marker.get().generation() != generation) {
            // The admission carries a marker of the previous generation forward, so a gap means sessions without
            // authoritative login (Build 52, or login.authoritative off) between the marker and now.
            unclean = "the session marker is of generation " + marker.get().generation() + ", this session is " + generation
                + " (sessions ran without authoritative login in between)";
            provenanceKnown = false;
        } else if (marker.get().open()) {
            unclean = "the previous session (generation " + (generation - 1L) + ") was not closed by a committed capture (crash, kill,"
                + " shutdown deadline, a refused save or a refused join)";
            provenanceKnown = true;
        } else {
            return null;
        }
        if (provenanceKnown && !marker.get().provenanceIs(profileId, epoch, this.nodeId)) {
            SourbyLogger.info("AWF MultiProfile: " + playerId + ": " + unclean + ", but the vanilla playerdata of this node last held "
                + (marker.get().vanillaProfile() == null ? "an unknown profile" : marker.get().vanillaProfile() + "/" + marker.get().vanillaEpoch()
                + " on " + marker.get().node()) + ", not the active " + profileId + "/" + epoch + "; the committed head is restored");
            return null;
        }
        final java.util.OptionalLong vanilla = this.players.vanillaPlayerdataModifiedMillis(playerId);
        if (vanilla.isEmpty()) {
            SourbyLogger.info("AWF MultiProfile: " + playerId + ": " + unclean + ", but this node has no vanilla playerdata for them;"
                + " the committed head is restored");
            return null;
        }
        if (vanilla.getAsLong() <= headWrittenAt) {
            SourbyLogger.info("AWF MultiProfile: " + playerId + ": " + unclean + "; the vanilla playerdata (" + vanilla.getAsLong()
                + ") is not newer than the committed head (" + headWrittenAt + "), which is restored");
            return null;
        }
        return unclean + "; the vanilla playerdata (" + vanilla.getAsLong() + ") is newer than the committed head ("
            + (headWrittenAt == 0L ? "never written" : String.valueOf(headWrittenAt)) + ") and "
            + (provenanceKnown ? "the marker records it holds this profile" : "Build 52 semantics say it holds the active profile");
    }

    /**
     * Records, after a publish, that this node's vanilla playerdata of the player holds the state of the ticket's profile
     * (a lockstep save, or the login adoption). Advisory: a failure is logged, never fails the save. Runs inside an I/O
     * step; does nothing while authoritative login does not apply.
     */
    private void markVanillaSynced(final CaptureTicket ticket, final String what) {
        if (!this.settings.authoritativeLogin()) return;
        try {
            final SessionMarker current = this.repository.sessionMarker(ticket.playerId())
                .filter(marker -> marker.generation() == ticket.sessionGeneration())
                .orElseGet(() -> SessionMarker.opened(ticket.playerId(), ticket.sessionGeneration(), this.nodeId));
            this.repository.writeSessionMarker(current.synced(ticket.profileId(), ticket.epoch(), this.nodeId, System.currentTimeMillis()));
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: the session marker of " + ticket.playerId() + " was not updated after " + what + " ("
                + messageOf(failed) + "); the next login then restores the committed head rather than adopting vanilla playerdata");
        }
    }

    /**
     * Closes the session marker after the committed final capture of a quit or shutdown (D3). Advisory like
     * {@link #markVanillaSynced}: a failure leaves the marker open, so the next login re-examines the vanilla playerdata
     * (which then holds the same state the capture published).
     */
    private void markSessionClosed(final CaptureTicket ticket, final boolean published) {
        if (!this.settings.authoritativeLogin()) return;
        try {
            SessionMarker current = this.repository.sessionMarker(ticket.playerId())
                .filter(marker -> marker.generation() == ticket.sessionGeneration())
                .orElseGet(() -> SessionMarker.opened(ticket.playerId(), ticket.sessionGeneration(), this.nodeId));
            if (published) current = current.synced(ticket.profileId(), ticket.epoch(), this.nodeId, System.currentTimeMillis());
            this.repository.writeSessionMarker(current.closed());
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: the session marker of " + ticket.playerId() + " was not closed (" + messageOf(failed)
                + "); the next login treats the session as unclean and re-examines the vanilla playerdata");
        }
    }

    /**
     * Admission of an authoritative login (SB-3): carries the player's marker of the previous generation to the new one,
     * open flag and provenance kept, so a refusal between admission and the login decision (WRONG_NODE, HEAD_UNREADABLE,
     * a claim that failed) never turns the next login's reading into "another generation" (Build 52 semantics). A marker
     * of an older generation is left alone (sessions ran without authoritative login); an unreadable one is replaced by
     * an open marker without provenance (WARN): it never adopts. A write that fails fails the admission. Runs inside an
     * I/O step; does nothing while authoritative login does not apply.
     */
    private void carryMarkerForward(final UUID playerId, final long generation) throws IOException {
        if (!this.settings.authoritativeLogin() || !this.repository.supportsSessionMarkers()) return;
        Optional<SessionMarker> marker;
        try {
            marker = this.repository.sessionMarker(playerId);
        } catch (final IOException corrupt) {
            SourbyLogger.warn("AWF MultiProfile: the session marker of " + playerId + " is unreadable (" + corrupt.getMessage()
                + "); it is replaced by an open marker without provenance, so this login restores the committed head");
            marker = Optional.of(SessionMarker.opened(playerId, generation - 1L, this.nodeId));
        }
        if (marker.isEmpty() || marker.get().generation() != generation - 1L) return;
        this.repository.writeSessionMarker(marker.get().atGeneration(generation));
    }

    /**
     * A switch is about to apply another profile's state (review P2): the vanilla playerdata will no longer hold the
     * profile the marker names once the server saves it, so the provenance is cleared first. A write that fails fails the
     * switch before anything is applied. Runs inside an I/O step.
     */
    private void clearVanillaProvenance(final UUID playerId) throws IOException {
        if (!this.settings.authoritativeLogin() || !this.repository.supportsSessionMarkers()) return;
        final PlayerSession session = this.sessions.get(playerId);
        if (session == null) return;
        final Optional<SessionMarker> marker = this.repository.sessionMarker(playerId);
        if (marker.isEmpty() || marker.get().generation() != session.sessionGeneration() || marker.get().vanillaProfile() == null) return;
        this.repository.writeSessionMarker(marker.get().withoutProvenance());
    }

    /**
     * Operator escape ({@code /awf multiprofile session <player> reset-marker}): writes a closed marker of the account's
     * current session generation with no provenance, so the player's next authoritative login restores the committed
     * head and does not adopt the vanilla playerdata (the safe choice: nothing is duplicated; progress held only in
     * vanilla playerdata since the last committed capture is dropped). Completes with a description.
     */
    public CompletableFuture<String> resetSessionMarker(final UUID playerId) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            if (!format2()) throw error(ProfileErrorCode.MIGRATION_REQUIRED, "session markers exist on a format-2 store only");
            if (!this.repository.supportsSessionMarkers()) {
                throw error(ProfileErrorCode.INVALID_REQUEST, "the repository " + this.repository.describe() + " stores no session markers");
            }
            final PlayerAccount account = this.repository.loadAccount(playerId, this.settings.defaultSlots());
            final SessionMarker reset = SessionMarker.reset(playerId, account.sessionGeneration(), this.nodeId);
            this.repository.writeSessionMarker(reset);
            SourbyLogger.warn("AWF MultiProfile: the session marker of " + playerId + " was reset by an operator (" + reset.describe()
                + "); the next login restores the committed head and adopts no vanilla playerdata");
            return "session marker of " + playerId + " reset to " + reset.describe() + "; the next login restores the committed head"
                + " of the active profile and the vanilla playerdata is not adopted" + (this.sessions.containsKey(playerId)
                    ? " (the player is online here: their quit closes this marker as usual)" : "");
        });
    }

    /**
     * Operator escape ({@code /awf multiprofile session <player> clear-refusal}): lifts the freeze {@link #refuseSaves}
     * installed for the player's current session, so their next save is attempted again. {@code false} when none was set
     * (a new session clears it on its own).
     */
    public boolean clearSaveRefusal(final UUID playerId) {
        final boolean cleared = this.saveRefused.remove(playerId);
        if (cleared) SourbyLogger.warn("AWF MultiProfile: the refused-save freeze of " + playerId + " was lifted by an operator");
        return cleared;
    }

    /**
     * Publishes the empty initial state as the first snapshot of an UNINITIALIZED stream, under the login's ticket;
     * the head becomes READY at R 1. A refusal (the account moved, the stream was initialised meanwhile, the store
     * declined) is logged, never fatal: the state is on the player and the next save publishes. Runs inside an I/O step.
     */
    private void publishInitialState(final UUID playerId, final LoginSource source) {
        try {
            final PlayerSession session = this.sessions.get(playerId);
            if (session == null || !session.claim().equals(source.ticket().claim())) return;
            final CaptureTicket ticket = new CaptureTicket(playerId, session.claim(), session.sessionGeneration(), session.accountRevision(),
                source.profileId(), source.epoch());
            if (!source.profileId().equals(session.activeProfile())) return;
            publishCaptured(ticket, source.state(), ProfileStateAdapters.Capture.EMPTY);
        } catch (final IOException | RuntimeException refused) {
            SourbyLogger.warn("AWF MultiProfile: the initial empty state of " + playerId + " in " + source.profileId() + " was applied"
                + " but not published (" + messageOf(refused) + "); the next save publishes the stream's first snapshot");
        }
    }

    /**
     * Where a message for an online player goes: the runtime sends it on the player's thread. Called on an I/O
     * worker; the hook must not block.
     */
    public void onPlayerNotice(final java.util.function.BiConsumer<UUID, String> hook) {
        this.playerNotice = Objects.requireNonNull(hook, "hook");
    }

    private void notify(final UUID playerId, final String text) {
        try {
            this.playerNotice.accept(playerId, text);
        } catch (final RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: could not message " + playerId + ": " + failed.getMessage());
        }
    }

    /** Whether the account has an incomplete journal; runs inside an I/O step. */
    private boolean pendingJournal(final UUID playerId) throws IOException {
        for (final ProfileTransition transition : this.repository.transitionsFor(playerId)) {
            if (!transition.terminal()) return true;
        }
        return false;
    }

    /**
     * Renews every live session claim and the claims running switches hold, in one batch
     * ({@link ProfileOwnership#renewAll}); returns how many session claims are held afterwards. A
     * session claim whose renewal failed while no switch or recovery runs for its player is claimed
     * again when the player is still online here (see {@link #reclaimSession}), otherwise dropped. When
     * the provider call fails, nothing is dropped and every claim is retried on the next heartbeat.
     */
    public CompletableFuture<Integer> renewSessions() {
        renewPlayerSessions();
        final List<Map.Entry<UUID, OwnerClaim>> sessions = List.copyOf(this.sessionClaims.entrySet());
        final List<OwnerClaim> batch = new ArrayList<>(sessions.size() + 2 * this.runs.size());
        for (final Map.Entry<UUID, OwnerClaim> entry : sessions) batch.add(entry.getValue());
        // A running switch's claims are renewed too, so a switch that takes longer than the lease keeps
        // them; their results are not acted on, the switch's fenced writes check them.
        for (final SwitchRun run : this.runs) run.heldClaims(batch);
        if (batch.isEmpty()) return CompletableFuture.completedFuture(0);
        return io(() -> {
            final List<Boolean> renewed;
            try {
                renewed = this.ownership.renewAll(batch).join();
            } catch (final RuntimeException failed) {
                // One provider serves every claim: a failure says nothing about any single claim, and
                // paying one timeout per player on the I/O lane would only delay the next heartbeat.
                SourbyLogger.warn("AWF MultiProfile: session renewal failed; every claim is retried on the next"
                    + " heartbeat: " + messageOf(failed));
                return 0;
            }
            int held = 0;
            for (int i = 0; i < sessions.size(); i++) {
                final UUID playerId = sessions.get(i).getKey();
                final OwnerClaim claim = sessions.get(i).getValue();
                if (i < renewed.size() && renewed.get(i)) {
                    held++;
                } else if (!this.switches.containsKey(playerId) && reclaimSession(playerId, claim)) {
                    // While a switch or recovery runs it may have superseded this claim; a switch
                    // installs its own claim when it ends, and one still invalid afterwards is handled
                    // on a later heartbeat.
                    held++;
                }
            }
            return held;
        });
    }

    /**
     * Renews every admitted player session claim {@code Q} in one batch. A claim the provider answers "not
     * renewed" is a confirmed loss ({@link #onSessionLost}): it is never claimed again for that session. A
     * failed call is uncertain: nothing is dropped, every mutation validates its claim before it commits, and
     * the next heartbeat retries.
     */
    private void renewPlayerSessions() {
        final List<Map.Entry<UUID, PlayerSession>> admitted = new ArrayList<>(this.sessions.size());
        for (final Map.Entry<UUID, PlayerSession> entry : this.sessions.entrySet()) {
            // A player who already left is not renewed: their quit (queued on the I/O lane) releases the claim,
            // and otherwise it lapses; renewing it would keep a session alive for nobody.
            if (this.players.online(entry.getKey())) admitted.add(entry);
        }
        if (admitted.isEmpty()) return;
        final List<PlayerClaim> batch = new ArrayList<>(admitted.size());
        for (final Map.Entry<UUID, PlayerSession> entry : admitted) batch.add(entry.getValue().claim());
        this.<Void>io(() -> {
            final List<Boolean> renewed;
            try {
                renewed = this.playerSessions.renewAll(batch).join();
            } catch (final RuntimeException failed) {
                SourbyLogger.warn("AWF MultiProfile: player session renewal failed; no session is treated as lost and every"
                    + " claim is retried on the next heartbeat: " + messageOf(failed));
                return null;
            }
            for (int i = 0; i < admitted.size() && i < renewed.size(); i++) {
                if (!renewed.get(i)) sessionLost(admitted.get(i).getKey(), admitted.get(i).getValue(), "its renewal was refused");
            }
            return null;
        }).exceptionally(failed -> null);
    }

    /**
     * A session claim that could not be renewed while no switch or recovery runs for its player. When
     * the player is still online here the profile is claimed again: a claim that only lapsed (nobody
     * took the profile) is taken over with a new fence, and writes made under the old claim stay
     * refused. When another process holds the profile now, or the player is gone, the claim is dropped:
     * kept, it would fail every save and warn on every heartbeat. Runs inside an I/O step.
     */
    private boolean reclaimSession(final UUID playerId, final OwnerClaim lapsed) {
        final OwnerClaim recorded = this.sessionClaims.get(playerId);
        if (!lapsed.equals(recorded)) {
            // A switch that ended since the batch was built installed its own claim (possibly on this same
            // profile, which a claim made here would supersede), or the player quit: nothing to re-claim.
            return recorded != null;
        }
        if (this.players.online(playerId)) {
            final UUID profileId = UUID.fromString(lapsed.id());
            // Another member's session, a switch or a recovery of this process may hold the profile live already (a
            // co-op several members play here): adopt that claim instead of superseding it, so the node converges on
            // one claim per profile rather than two sessions re-claiming it from each other on every heartbeat.
            final OwnerClaim live = liveNodeClaim(profileId);
            if (live != null && !live.sameIncarnation(lapsed)) {
                this.sessionClaims.replace(playerId, lapsed, live);
                return true;
            }
            final ClaimResult claimed = this.ownership.claim(profileId, this.nodeId, UUID.randomUUID()).join();
            if (claimed.granted()) {
                if (this.sessionClaims.replace(playerId, lapsed, claimed.claim())) {
                    SourbyLogger.warn("AWF MultiProfile: the session claim of " + playerId + " on " + lapsed.id()
                        + " lapsed and was claimed again (fence " + lapsed.fence() + " -> " + claimed.claim().fence() + ")");
                    // Other members sharing the lapsed claim follow it to the new one.
                    for (final Map.Entry<UUID, OwnerClaim> entry : this.sessionClaims.entrySet()) {
                        if (entry.getValue().sameIncarnation(lapsed)) this.sessionClaims.replace(entry.getKey(), entry.getValue(), claimed.claim());
                    }
                    return true;
                }
                // The player quit, or a switch installed its claim, in the meantime: this one is nobody's.
                this.ownership.release(claimed.claim()).join();
                return false;
            }
            if (this.sessionClaims.remove(playerId, lapsed)) {
                SourbyLogger.warn("AWF MultiProfile: the session claim of " + playerId + " on " + lapsed.id()
                    + " was taken over by " + (claimed.claim() == null ? "another node" : claimed.claim().node())
                    + " and was dropped; saves for this player are refused until their next join claims it again");
            }
            return false;
        }
        if (this.sessionClaims.remove(playerId, lapsed)) {
            SourbyLogger.warn("AWF MultiProfile: the session claim of " + playerId + " is no longer valid and was"
                + " dropped; the player's next join claims the profile again");
        }
        return false;
    }

    /** Whether a switch, a recovery or the teleport of a join preparation ({@link #prepareActive}) runs for the player. */
    public boolean switchInProgress(final UUID playerId) {
        return this.switches.containsKey(playerId) || this.loginGates.containsKey(playerId);
    }

    /** Clears the per-session flags a quit ends: the login gate and a refused-save freeze. */
    private void sessionFlagsCleared(final UUID playerId) {
        this.loginGates.remove(playerId);
        this.saveRefused.remove(playerId);
    }

    /**
     * Whether the player is frozen: a running switch captured their state and has neither committed
     * nor rolled back. Cheap enough for an event handler on the player's thread.
     */
    public boolean frozen(final UUID playerId) {
        return this.frozen.contains(playerId) || this.lostSessions.contains(playerId) || this.loginGates.containsKey(playerId)
            || this.saveRefused.contains(playerId);
    }

    /** Releases every claim on the calling thread; shutdown only, after the I/O drain. */
    void releaseSessionsNow() {
        RuntimeException firstFailure = null;
        final List<OwnerClaim> distinct = new ArrayList<>();
        for (final OwnerClaim claim : this.sessionClaims.values()) {
            if (distinct.stream().noneMatch(claim::sameIncarnation)) distinct.add(claim);
        }
        for (final OwnerClaim claim : distinct) {
            try {
                this.ownership.release(claim).join();
            } catch (final RuntimeException failed) {
                // One provider serves every claim; after the first failure the rest would only pay
                // another timeout each. They expire on their own.
                firstFailure = failed;
                break;
            }
        }
        if (firstFailure != null) {
            SourbyLogger.warn("AWF MultiProfile: could not release every session claim at shutdown ("
                + messageOf(firstFailure) + "); " + this.sessionClaims.size() + " claim(s) expire on their own");
        }
        this.sessionClaims.clear();
        for (final PlayerSession session : this.sessions.values()) {
            try {
                this.playerSessions.release(session.claim()).join();
            } catch (final RuntimeException failed) {
                SourbyLogger.warn("AWF MultiProfile: could not release every player session claim at shutdown ("
                    + messageOf(failed) + "); " + this.sessions.size() + " claim(s) expire on their own");
                break;
            }
        }
        this.sessions.clear();
    }

    /**
     * Records that a world bound to a profile was used, for idle unloading. Only binding worlds are
     * passed here, so the sweep never tracks a world no profile is bound to.
     */
    public void touch(final String world) {
        this.worldUse.put(world, System.nanoTime());
        this.worlds.touch(world);
    }

    /** The outcome of the last idle-unload sweep that found idle worlds. */
    public record IdleSweep(long finishedAtMillis, int unloaded, int failed) {}

    /** The last sweep that found idle worlds, or {@code null} when none did yet; for status output. */
    public IdleSweep lastIdleSweep() {
        return this.lastIdleSweep;
    }

    /**
     * Unloads bound worlds that have been idle beyond the configured time; completes with how many
     * unloaded. Only worlds a binding touched are tracked, and only AWF worlds are unloaded
     * ({@link ProfileWorldPort#managed}; never the server's main world): an idle entry for another
     * world, or for a world that is no longer loaded, is dropped (a later switch or join touches it
     * again). A world with a player inside counts as used: it is touched again and skipped, so an
     * occupied world is neither saved nor reported.
     */
    public CompletableFuture<Integer> sweepIdleWorlds() {
        if (!this.settings.enabled() || this.settings.worlds().idleUnloadSeconds() <= 0) {
            return CompletableFuture.completedFuture(0);
        }
        final long idleNanos = this.settings.worlds().idleUnloadSeconds() * 1_000_000_000L;
        final long now = System.nanoTime();
        final List<String> idle = new ArrayList<>();
        this.worldUse.forEach((world, used) -> {
            if (now - used < idleNanos) return;
            if (!this.worlds.managed(world) || !this.worlds.loaded(world)) {
                this.worldUse.remove(world, used);
            } else if (this.worlds.occupied(world)) {
                this.worldUse.replace(world, used, now);
            } else {
                idle.add(world);
            }
        });
        if (idle.isEmpty()) return CompletableFuture.completedFuture(0);
        final AtomicInteger unloaded = new AtomicInteger();
        final AtomicInteger refused = new AtomicInteger();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (final String world : idle) {
            final long usedAt = this.worldUse.getOrDefault(world, 0L);
            chain = chain.thenCompose(ignored -> {
                // A player may have entered since the scan: still in use, not an unload failure.
                if (this.worlds.occupied(world)) {
                    this.worldUse.replace(world, usedAt, System.nanoTime());
                    return CompletableFuture.completedFuture(null);
                }
                // A switch may have touched the world since the scan; a conditional remove keeps
                // that touch, so the world is not unloaded under the teleport and is swept later.
                if (!this.worldUse.remove(world, usedAt)) return CompletableFuture.completedFuture(null);
                return this.worlds.saveAndUnload(world).thenRun(unloaded::incrementAndGet).exceptionally(failed -> {
                    refused.incrementAndGet();
                    // Keep it eligible for a later retry; never overwrite a newer touch.
                    this.worldUse.putIfAbsent(world, System.nanoTime());
                    SourbyLogger.warn("AWF MultiProfile: idle unload of '" + world + "' failed: " + messageOf(failed));
                    return null;
                });
            });
        }
        return chain.thenApply(ignored -> {
            final IdleSweep sweep = new IdleSweep(System.currentTimeMillis(), unloaded.get(), refused.get());
            this.lastIdleSweep = sweep;
            SourbyLogger.info("AWF MultiProfile: idle unload sweep unloaded " + sweep.unloaded() + " world(s)"
                + (sweep.failed() == 0 ? "" : "; " + sweep.failed() + " failed and stay eligible"));
            return sweep.unloaded();
        });
    }

    /**
     * Finishes or resolves an incomplete transition.
     *
     * <p>Roll forward when the account already committed the target. Otherwise, a journal that never
     * reached {@code APPLYING_TARGET} applied nothing and is removed; one that did is rolled back to its
     * rollback point, which needs that exact snapshot and an online player. A terminal journal is a
     * record and is removed on request.</p>
     */
    public CompletableFuture<ProfileSwitchResult> recover(final UUID transitionId) {
        final long began = System.currentTimeMillis();
        if (!this.settings.enabled()) {
            return CompletableFuture.completedFuture(failure(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled",
                null, began));
        }
        if (this.switches.containsValue(transitionId)) {
            return CompletableFuture.completedFuture(failure(ProfileErrorCode.SWITCH_IN_PROGRESS,
                "transition " + transitionId + " is running in this process", null, began));
        }
        return io(() -> {
            final ProfileTransition journal = this.repository.transition(transitionId)
                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no transition " + transitionId));
            if (this.switches.putIfAbsent(journal.playerId(), transitionId) != null) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "account transition in progress");
            }
            try {
                final PlayerAccount account = this.repository.loadAccount(journal.playerId(), this.settings.defaultSlots());
                return new Recovery(journal, account);
            } catch (final Throwable failed) {
                this.switches.remove(journal.playerId(), transitionId);
                throw failed;
            }
        }).thenCompose(recovery -> recoverReserved(recovery, transitionId, began)
            .whenComplete((result, failed) -> transitionEnded(recovery.journal().playerId(), transitionId)))
            .exceptionally(failed -> {
                if (this.metrics != null) this.metrics.profileFailure();
                return failure(codeOf(failed), messageOf(failed), null, began);
            });
    }

    private CompletableFuture<ProfileSwitchResult> recoverReserved(final Recovery recovery,
                                                                  final UUID transitionId, final long began) {
        final ProfileTransition journal = recovery.journal();
        if (journal.terminal()) {
            // A terminal journal is a record, not a lock: forget it on request.
            return io(() -> {
                this.repository.deleteTransition(transitionId, ProfileWriteGuard.UNGUARDED);
                return null;
            }).thenApply(ignored -> failure(ProfileErrorCode.RECOVERED, journal.phase() == TransitionPhase.FAILED
                ? "failed journal " + transitionId + " removed; it recorded: " + journal.failure()
                : "completed journal " + transitionId + " removed; nothing was pending", null, began));
        }
        final boolean forward = journal.toProfile().equals(recovery.account().activeProfile());
        if (!forward && !journal.targetMayBeApplied()) {
            // The switch stopped before the target state was handed to the player, and the account
            // still names the previous profile: the player's own state is authoritative. Applying the
            // old snapshot would revert whatever happened since, so nothing is applied. With
            // distributed ownership the removal is fenced by a claim on the target: a switch that is
            // still running on another node holds that claim, so its journal is not removed under it.
            final CompletableFuture<OwnerClaim> fence = this.ownership.distributed()
                ? claimForRecovery(journal.toProfile(), journal.toFence(), journal)
                : CompletableFuture.completedFuture(null);
            return fence.thenCompose(claim -> io(() -> {
                this.repository.deleteTransition(transitionId, ProfileWriteGuard.claimed(claim));
                return null;
            }).handle((removed, failed) -> releaseRecoveryClaim(claim, journal.playerId()).thenApply(released -> {
                if (failed != null) {
                    throw failed instanceof CompletionException completion ? completion : new CompletionException(failed);
                }
                return removed;
            })).thenCompose(settled -> settled)).thenApply(ignored -> failure(ProfileErrorCode.RECOVERED, "transition "
                + transitionId + " stopped at " + journal.phase() + ", before the target state was applied; journal"
                + " removed, nothing changed", null, began));
        }
        final UUID contact = forward ? journal.toProfile() : journal.fromProfile();
        final long recordedFence = forward ? journal.toFence() : journal.fromFence();
        return claimForRecovery(contact, recordedFence, journal).thenCompose(claim -> {
            final CompletableFuture<ProfileSwitchResult> outcome = forward
                ? io(() -> {
                    this.repository.deleteTransition(transitionId, ProfileWriteGuard.claimed(claim));
                    return null;
                }).thenCompose(ignored -> get(journal.toProfile()))
                    .thenApply(profile -> ProfileSwitchResult.success(profile.orElse(null), transitionId,
                        System.currentTimeMillis() - began))
                : rollbackTo(recovery, began, claim);
            return outcome.handle((result, failed) -> releaseRecoveryClaim(claim, journal.playerId()).thenApply(released -> {
                if (failed != null) {
                    throw failed instanceof CompletionException completion ? completion : new CompletionException(failed);
                }
                return result;
            })).thenCompose(settled -> settled);
        });
    }

    /**
     * Recovery writes are fenced like a switch: take the profile being made authoritative and refuse a
     * journal whose fence on that same profile is ahead of the fresh claim (a takeover happened after
     * the journal was written). Fences are compared only when they are comparable: issued by the same
     * provider incarnation, or for a journal of an earlier build (no incarnation) only by a provider
     * whose counters survive a restart.
     *
     * <p>When the player's own session claim on this node is on that profile and still valid, it is
     * used as it is: claiming again would replace its owner token (same process) and leave the
     * session with a claim that no longer validates. {@link #releaseRecoveryClaim} keeps it.</p>
     */
    private CompletableFuture<OwnerClaim> claimForRecovery(final UUID profileId, final long recordedFence,
                                                           final ProfileTransition journal) {
        if (profileId == null || !this.settings.coordination().fencingEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        final boolean comparable = journal.incarnation() == null
            ? this.ownership.distributed() : journal.incarnation().equals(this.ownership.incarnation());
        return io(() -> {
            final OwnerClaim session = this.sessionClaims.get(journal.playerId());
            if (session != null && session.id().equals(profileId.toString()) && this.ownership.validate(session).join()) {
                return session;
            }
            // The live node claim other members' sessions share is reused, never superseded by a new token (review P2-6).
            final ClaimResult claimed = nodeClaim(profileId, UUID.randomUUID());
            if (!claimed.granted()) {
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profileId + " is held by "
                    + (claimed.claim() == null ? "another node" : claimed.claim().node())
                    + "; recovery did not write");
            }
            if (comparable && recordedFence > 0L && claimed.claim().fence() < recordedFence) {
                releaseNodeClaimUnlessHeld(claimed.claim(), journal.playerId());
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "transition " + journal.transitionId()
                    + " was recorded under fence " + recordedFence + " on " + profileId + " but this claim is fence "
                    + claimed.claim().fence() + "; refusing to write with a stale fence");
            }
            return claimed.claim();
        });
    }

    /**
     * Releases a claim {@link #claimForRecovery} took; the player's session claim it borrowed, and a node claim any other
     * session or run on this node holds, are kept (checked inside the I/O step, review P2-6).
     */
    private CompletableFuture<Void> releaseRecoveryClaim(final OwnerClaim claim, final UUID playerId) {
        if (claim == null || claim.equals(this.sessionClaims.get(playerId))) return CompletableFuture.completedFuture(null);
        return this.<Void>io(() -> {
            if (claim.equals(this.sessionClaims.get(playerId))) return null;
            releaseNodeClaimUnlessHeld(claim, playerId);
            return null;
        }).handle((released, failed) -> {
            if (failed != null) {
                SourbyLogger.warn("AWF MultiProfile: releasing a recovery claim failed: " + messageOf(failed));
            }
            return null;
        });
    }

    private CompletableFuture<ProfileSwitchResult> rollbackTo(final Recovery recovery, final long began,
                                                              final OwnerClaim claim) {
        final ProfileTransition journal = recovery.journal();
        final ProfileWriteGuard journalGuard = ProfileWriteGuard.claimed(claim);
        if (journal.fromSnapshotId() == null) {
            return io(() -> {
                this.repository.saveTransition(journal.failed("no durable rollback point was recorded"), journalGuard);
                return null;
            }).thenApply(ignored -> failure(ProfileErrorCode.RECOVERY_REQUIRED,
                "the previous state has no durable snapshot; refusing to guess", null, began));
        }
        if (!this.players.online(journal.playerId())) {
            // Leave the journal non-terminal: the next join retries the rollback.
            return io(() -> {
                this.repository.saveTransition(journal.at(TransitionPhase.RECOVERING), journalGuard);
                return null;
            }).thenApply(ignored -> failure(ProfileErrorCode.PLAYER_OFFLINE,
                "the player must be online to recover this transition; the journal stays pending", null, began));
        }
        final long budget = this.settings.switching().timeoutMillis();
        return io(() -> {
            final Optional<ProfileSnapshot> snapshot = rollbackSnapshot(journal);
            if (snapshot.isEmpty()) {
                this.repository.saveTransition(journal.failed("the rollback snapshot " + journal.fromSnapshotId()
                    + " no longer exists"), journalGuard);
                throw error(ProfileErrorCode.RECOVERY_REQUIRED, "the rollback snapshot " + journal.fromSnapshotId()
                    + " of transition " + journal.transitionId() + " no longer exists; refusing to guess");
            }
            this.repository.saveTransition(journal.at(TransitionPhase.RECOVERING), journalGuard);
            final ProfileState state = ProfileStateCodec.decode(snapshot.get().state());
            // Required adapter data without a registration here: the rollback would be partial; the journal stays
            // pending for a node that has the adapter (ADAPTER_MISSING).
            this.players.validateApply(state);
            return state;
        }).thenCompose(state -> this.players.apply(journal.playerId(), state, this.settings.isolation())
                .orTimeout(budget, TimeUnit.MILLISECONDS)
                .exceptionallyCompose(failed -> CompletableFuture.failedFuture(
                    unwrapCompletion(failed) instanceof TimeoutException
                        ? error(ProfileErrorCode.TIMEOUT, "the rollback did not finish within switching.timeout-ms;"
                            + " the journal stays pending")
                        : failed)))
            .thenCompose(ignored -> io(() -> {
                if (claim != null && this.settings.coordination().fencingEnabled()
                    && !this.ownership.validate(claim).join()) {
                    throw error(ProfileErrorCode.OWNERSHIP_CONFLICT,
                        "the recovery claim lapsed before the account was rewritten");
                }
                final PlayerAccount account = this.repository.loadAccount(journal.playerId(),
                    this.settings.defaultSlots());
                final UUID previous = journal.fromProfile();
                if (previous != null && account.owns(previous) && !previous.equals(account.activeProfile())) {
                    if (format2()) {
                        restoreActiveFormat2(journal, account, previous);
                    } else {
                        this.repository.saveAccount(account.withActive(previous), ProfileWriteGuard.of(claim, account.revision()));
                    }
                }
                this.repository.deleteTransition(journal.transitionId(), journalGuard);
                return null;
            }))
            .thenApply(ignored -> failure(ProfileErrorCode.RECOVERED,
                "transition rolled back to the previous state", null, began));
    }

    /**
     * Format-2 rollback of the account pointer: the previous profile and the epoch the journal recorded, under
     * the player's {@code Q}, expected {@code A} and the current active profile. Runs inside an I/O step.
     */
    private void restoreActiveFormat2(final ProfileTransition journal, final PlayerAccount account, final UUID previous)
        throws IOException {
        if (journal.coop() == null || journal.coop().fromEpoch() == null) {
            throw error(ProfileErrorCode.RECOVERY_REQUIRED, "transition " + journal.transitionId() + " records no"
                + " membership epoch of " + previous + "; refusing to guess which membership to restore");
        }
        final List<PlayerClaim> acquired = new ArrayList<>(1);
        try {
            final PlayerClaim q = actorClaim(journal.playerId(), acquired);
            commitAccount(account.withActive(previous, journal.coop().fromEpoch()), accountGuard(operation(
                CoopOperation.Kind.SWITCH_ACTIVE, journal.playerId(), journal.transitionId(), previous, account.revision()),
                q, account.revision(), activeOf(account)));
        } finally {
            releasePlayerClaims(acquired);
        }
    }

    /** The exact rollback point a journal names; a first switch names the archived pre-profile state. */
    private Optional<ProfileSnapshot> rollbackSnapshot(final ProfileTransition journal) throws IOException {
        if (journal.fromProfile() != null && format2()) {
            // Format 2: the exact snapshot of the player's own stream; never a profile-wide or latest one.
            if (journal.coop() == null || journal.coop().fromEpoch() == null) {
                throw error(ProfileErrorCode.RECOVERY_REQUIRED, "transition " + journal.transitionId() + " records no"
                    + " membership epoch; its rollback point cannot be looked up exactly");
            }
            return this.repository.memberSnapshot(journal.fromProfile(), journal.playerId(), journal.coop().fromEpoch(),
                journal.fromSnapshotId());
        }
        if (journal.fromProfile() != null) {
            return this.repository.snapshotById(journal.fromProfile(), journal.fromSnapshotId());
        }
        return this.repository.legacySnapshot(journal.playerId())
            .filter(snapshot -> snapshot.snapshotId().equals(journal.fromSnapshotId()));
    }

    /** Recovers every incomplete transition of one player; called on join. */
    public CompletableFuture<Integer> recoverPending(final UUID playerId) {
        if (!this.settings.enabled()) return CompletableFuture.completedFuture(0);
        return io(() -> this.repository.transitionsFor(playerId).stream()
            .filter(transition -> !transition.terminal()
                && !transition.transitionId().equals(this.switches.get(playerId)))
            .map(ProfileTransition::transitionId)
            .toList()).thenCompose(ids -> {
                CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
                for (final UUID id : ids) {
                    chain = chain.thenCompose(done -> recover(id).thenApply(result -> done + (result.success() ? 1 : 0)));
                }
                return chain;
            });
    }

    /** Journal state for status output: pending and failed journals, and files set aside as unreadable. */
    public record JournalStatus(List<ProfileTransition> pending, List<ProfileTransition> failed,
                                List<String> quarantined) {}

    public CompletableFuture<JournalStatus> journalStatus() {
        if (!this.settings.enabled()) {
            return CompletableFuture.completedFuture(new JournalStatus(List.of(), List.of(), List.of()));
        }
        return io(() -> {
            final List<ProfileTransition> journals = this.repository.transitions();
            return new JournalStatus(
                journals.stream().filter(transition -> !transition.terminal()).toList(),
                journals.stream().filter(transition -> transition.phase() == TransitionPhase.FAILED).toList(),
                this.repository.quarantinedJournals());
        });
    }

    public void close() {
        this.closed = true;
        final java.util.concurrent.ThreadPoolExecutor events = this.coopEventLane;
        if (events != null) events.shutdown();
        this.switches.clear();
        this.runs.clear();
        this.frozen.clear();
        this.sessionClaims.clear();
        this.releaseOnSwitchEnd.clear();
        this.worldUse.clear();
        this.sessions.clear();
        this.lostSessions.clear();
        this.loginGates.clear();
        this.saveRefused.clear();
        this.ownership.close();
        try {
            this.playerSessions.close();
        } catch (final RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: closing the player session claims failed: " + failed.getMessage());
        }
        this.repository.close();
    }

    /** Whether the engine is still accepting work; the API reports this. */
    public boolean isOpen() {
        return !this.closed;
    }

    // ------------------------------------------------------------------ switch protocol

    private final class SwitchRun {
        private final AtomicReference<ProfileTransition> journal;
        private final PlayerAccount account;
        private final AuroraProfile target;
        private final UUID ownerToken;
        private final long began;
        private final AtomicReference<OwnerClaim> claim = new AtomicReference<>();
        private final AtomicReference<OwnerClaim> previousClaim = new AtomicReference<>();
        /** The target claim was this node's live claim already (another member plays the co-op here): never released by this run. */
        private volatile boolean targetClaimShared;
        /** The previous profile's claim was this node's live claim already: never released by this run. */
        private volatile boolean previousClaimShared;
        private final AtomicBoolean commitPoint = new AtomicBoolean();
        /** Set immediately before the target state is handed to the player's entity thread. */
        private final AtomicBoolean targetApplyStarted = new AtomicBoolean();
        /** Set once the run failed; a target apply still waiting for the entity thread then does nothing. */
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private volatile Throwable failure;
        private volatile ProfileState previousState;
        /** Compensation left the journal {@code RECOVERING}: the rollback has not completed. */
        private volatile boolean rollbackPending;
        /**
         * The account revision the commit expected, when its write failed on a remote repository and so
         * may still have been applied; {@code -1} otherwise. Compensation settles it first.
         */
        private volatile long uncertainCommit = -1L;
        /** The target's world binding as {@link #worldReady} read it; {@code null} when it has none. */
        private volatile ProfileWorldBinding targetBinding;
        /** Format-2 admission, or {@code null} on a format-1 store. */
        private final SwitchCoop coop;
        /** {@code R} of the source stream after this run published its rollback point there. */
        private volatile long fromHead;
        /**
         * Set when the format-2 commit's outcome is UNKNOWN and reading the account back did not settle it:
         * compensation then never rolls back, it leaves the journal for recovery to decide from the account.
         */
        private volatile String unknownCommit;

        private SwitchRun(final ProfileTransition journal, final PlayerAccount account, final AuroraProfile target,
                          final UUID ownerToken, final long began, final SwitchCoop coop) {
            this.journal = new AtomicReference<>(journal);
            this.account = account;
            this.target = target;
            this.ownerToken = ownerToken;
            this.began = began;
            this.coop = coop;
            this.fromHead = coop == null ? CoopWriteGuard.ABSENT : coop.fromHead();
        }

        private UUID playerId() {
            return this.account.playerId();
        }

        /** Adds the claims this run holds right now (target, previous profile) for the heartbeat. */
        private void heldClaims(final List<OwnerClaim> into) {
            final OwnerClaim target = this.claim.get();
            if (target != null) into.add(target);
            final OwnerClaim previous = this.previousClaim.get();
            if (previous != null) into.add(previous);
        }

        private CompletableFuture<ProfileSwitchResult> execute() {
            CompletableFuture<Void> chain = phase(TransitionPhase.VALIDATING);
            chain = chain.thenCompose(ignored -> {
                if (!ProfileEngine.this.players.online(playerId())) {
                    throw error(ProfileErrorCode.PLAYER_OFFLINE, "the player is not online");
                }
                return phase(TransitionPhase.PREPARING_TARGET);
            });
            // The target claim and its world come first, so the player is not frozen while a world
            // is created or loaded.
            chain = chain.thenCompose(ignored -> claimTarget());
            chain = chain.thenCompose(ignored -> worldReady());
            chain = chain.thenCompose(ignored -> {
                // From the capture until this run ends, the runtime's freeze listener cancels the
                // player's item and interaction events: a pickup in between would be lost and an item
                // put into a container would exist twice once the target state is applied.
                ProfileEngine.this.frozen.add(playerId());
                return phase(TransitionPhase.FREEZING);
            });
            chain = chain.thenCompose(ignored -> claimPrevious());
            chain = chain.thenCompose(ignored -> capturePrevious());
            chain = chain.thenCompose(ignored -> applyTarget());
            chain = chain.thenCompose(ignored -> commit());
            chain = chain.thenCompose(ignored -> afterCommit());
            return chain.handle((ignored, failed) -> {
                    this.failure = failed;
                    return failed;
                })
                .thenCompose(failed -> {
                    if (failed == null) return CompletableFuture.<Void>completedFuture(null);
                    return compensate(failed);
                })
                .thenCompose(ignored -> describeTarget())
                .thenApply(found -> {
                    final ProfileTransition current = this.journal.get();
                    if (this.commitPoint.get()) {
                        return ProfileSwitchResult.success(found, current.transitionId(),
                            System.currentTimeMillis() - this.began);
                    }
                    final ProfileErrorCode code = this.rollbackPending
                        ? ProfileErrorCode.RECOVERY_REQUIRED : switchFailureCode(this.failure);
                    return failure(code, current.failure() == null
                            ? "the switch did not commit: " + messageOf(this.failure) : current.failure(),
                        found, current.transitionId(), this.began);
                });
        }

        /** Claims the target; the journal records the fence on the next phase write. */
        private CompletableFuture<Void> claimTarget() {
            return io(() -> {
                final OwnerClaim live = liveNodeClaim(this.target.id());
                this.targetClaimShared = live != null;
                final ClaimResult claimed = live != null ? ClaimResult.granted(live) : ProfileEngine.this.ownership
                    .claim(this.target.id(), ProfileEngine.this.nodeId, this.ownerToken).join();
                if (!claimed.granted()) {
                    throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + this.target.id()
                        + " is held by " + (claimed.claim() == null ? "another node" : claimed.claim().node()));
                }
                this.claim.set(claimed.claim());
                this.journal.set(this.journal.get().withToFence(claimed.claim().fence(),
                    ProfileEngine.this.ownership.incarnation()));
                return null;
            });
        }

        /** Claims the profile being left, so its snapshot is a fenced write too. */
        private CompletableFuture<Void> claimPrevious() {
            final UUID previous = this.account.activeProfile();
            if (previous == null || previous.equals(this.target.id())) {
                return CompletableFuture.completedFuture(null);
            }
            return io(() -> {
                // The player's own session claim (or another member's) on the profile being left is reused when it is
                // live; claiming anew with this run's token would supersede it under the other members' sessions.
                final OwnerClaim live = liveNodeClaim(previous);
                this.previousClaimShared = live != null && sharedWithAnother(live, playerId());
                final ClaimResult claimed = live != null ? ClaimResult.granted(live) : ProfileEngine.this.ownership
                    .claim(previous, ProfileEngine.this.nodeId, this.ownerToken).join();
                if (!claimed.granted()) {
                    throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "the previous profile " + previous
                        + " is held by " + (claimed.claim() == null ? "another node" : claimed.claim().node()));
                }
                this.previousClaim.set(claimed.claim());
                this.journal.set(this.journal.get().withFromFence(claimed.claim().fence(),
                    ProfileEngine.this.ownership.incarnation()));
                return null;
            });
        }

        /**
         * Captures the player and makes the capture the durable rollback point: the previous profile's
         * snapshot, or on the player's first switch an archive of the state they had before any
         * profile, which applying the target would otherwise erase.
         */
        private CompletableFuture<Void> capturePrevious() {
            checkDeadline();
            final UUID previous = this.account.activeProfile();
            return bounded(ProfileEngine.this.players.captureDetailed(playerId(), ProfileEngine.this.settings.isolation()),
                    "capturing the player's state")
                .thenCompose(captured -> io(() -> {
                    final ProfileState state = captured.state();
                    this.previousState = state;
                    final OwnerClaim held = this.previousClaim.get();
                    if (ProfileEngine.this.settings.coordination().fencingEnabled() && held != null
                        && !ProfileEngine.this.ownership.validate(held).join()) {
                        throw error(ProfileErrorCode.OWNERSHIP_CONFLICT,
                            "the claim on the previous profile lapsed; its snapshot was not written");
                    }
                    try {
                        final ProfileSnapshot snapshot;
                        if (previous == null) {
                            snapshot = archivePreProfileState(playerId(), state, this.claim.get());
                        } else if (this.coop != null) {
                            // Format 2: the rollback point is the next snapshot of the player's own stream.
                            final AuroraProfile source = ProfileEngine.this.repository.profile(previous)
                                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + previous));
                            if (source.revision() != this.coop.fromMetadata()) {
                                throw error(ProfileErrorCode.STALE_STATE, "profile " + previous + " changed since the switch"
                                    + " was admitted; the rollback point was not written");
                            }
                            snapshot = publishMemberState(source, playerId(), this.coop.fromEpoch(), state, captured.adapters(),
                                this.coop.claim(), this.coop.accountRevision());
                            this.fromHead = snapshot.revision();
                        } else {
                            snapshot = writeSnapshotFor(previous, state, captured.adapters(), held);
                        }
                        this.journal.set(this.journal.get().withFromSnapshot(snapshot.snapshotId()));
                    } catch (final IOException | ProfileOperationException failed) {
                        if (ProfileEngine.this.settings.switching().requireDurableSave()) throw failed;
                        SourbyLogger.warn("AWF MultiProfile: storing the rollback point failed and"
                            + " require-durable-save is false; continuing without one: " + messageOf(failed));
                    }
                    return null;
                }))
                .thenCompose(ignored -> phase(TransitionPhase.SAVING));
        }

        private CompletableFuture<Void> worldReady() {
            checkDeadline();
            return bounded(io(() -> ProfileEngine.this.repository.binding(this.target.id()).orElse(null))
                .thenCompose(binding -> {
                    if (binding == null || binding.isEmpty()) return CompletableFuture.completedFuture(null);
                    this.targetBinding = binding;
                    binding.worlds().keySet().forEach(ProfileEngine.this::touch);
                    return ProfileEngine.this.worlds.ensureReady(binding).exceptionallyCompose(failed ->
                        CompletableFuture.failedFuture(error(ProfileErrorCode.WORLD_UNAVAILABLE,
                            "profile world is not available: " + messageOf(failed))));
                }), "making the profile world ready");
        }

        private CompletableFuture<Void> applyTarget() {
            checkDeadline();
            return io(() -> {
                final AuroraProfile current = ProfileEngine.this.repository.profile(this.target.id())
                    .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "the target disappeared"));
                if (this.coop != null) return targetStateFormat2(current);
                final Optional<ProfileSnapshot> snapshot = ProfileEngine.this.repository.latestSnapshot(this.target.id());
                if (snapshot.isPresent() && snapshot.get().revision() < current.revision()) {
                    throw error(ProfileErrorCode.STALE_STATE, "the stored state of " + this.target.id()
                        + " is revision " + snapshot.get().revision() + ", the profile is " + current.revision());
                }
                snapshot.ifPresent(found -> this.journal.set(this.journal.get().withToSnapshot(found.snapshotId())));
                return snapshot.map(ProfileSnapshot::state).map(ProfileStateCodec::decode).orElseGet(ProfileState::empty);
            }).thenApply(state -> {
                // Required adapter data with no registration refuses the switch here (ADAPTER_MISSING), before the
                // APPLYING_TARGET phase: nothing of the target reached the player, so the journal ends FAILED.
                ProfileEngine.this.players.validateApply(state);
                return state;
            }).thenCompose(state -> this.coop == null ? CompletableFuture.completedFuture(state) : io(() -> {
                // The vanilla playerdata stops holding the previous profile once the target is applied and saved (review P2).
                clearVanillaProvenance(playerId());
                return state;
            })).thenCompose(state -> phase(TransitionPhase.APPLYING_TARGET).thenApply(written -> state))
                .thenCompose(state -> {
                    this.targetApplyStarted.set(true);
                    return bounded(ProfileEngine.this.players.apply(playerId(), state,
                            ProfileEngine.this.settings.isolation(), this.abandoned::get), "applying the target state")
                        .thenCompose(ignored -> moveToTarget(state));
                });
        }

        /**
         * Format 2: the target state is exactly what the player's member head names, compared with the head and
         * with what was admitted ({@code M}, {@code R}); a stream with no published state applies an empty state,
         * never the player's current inventory. Runs inside an I/O step.
         */
        private ProfileState targetStateFormat2(final AuroraProfile current) throws IOException {
            if (current.revision() != this.coop.toMetadata()) {
                throw error(ProfileErrorCode.STALE_STATE, "profile " + current.id() + " changed since the switch was"
                    + " admitted (metadata revision " + this.coop.toMetadata() + " -> " + current.revision() + ")");
            }
            if (!current.hasMember(playerId(), this.coop.toEpoch())) {
                throw error(ProfileErrorCode.STALE_STATE, "membership " + this.coop.toEpoch() + " of " + playerId()
                    + " in " + current.id() + " is not current");
            }
            final Optional<MemberStateHead> head = ProfileEngine.this.repository.memberHead(current.id(), playerId(),
                this.coop.toEpoch());
            final long revision = head.map(MemberStateHead::revision).orElse(CoopWriteGuard.ABSENT);
            if (revision != this.coop.toHead()) {
                throw error(ProfileErrorCode.STALE_STATE, "the member head of " + playerId() + " in " + current.id()
                    + " moved since the switch was admitted");
            }
            if (head.isEmpty()) return ProfileState.empty();
            if (head.get().state() == MemberStateHead.State.CONSUMED) {
                throw error(ProfileErrorCode.STALE_STATE, "member stream " + head.get().describe() + " was consumed; it is"
                    + " never restored");
            }
            if (head.get().pendingIntent() != null) {
                throw error(ProfileErrorCode.RECOVERY_REQUIRED, "member stream " + head.get().describe()
                    + " is reserved by intent " + head.get().pendingIntent());
            }
            final Optional<ProfileSnapshot> snapshot = exactHeadSnapshot(head.get());
            if (snapshot.isEmpty()) return ProfileState.empty();
            this.journal.set(this.journal.get().withToSnapshot(snapshot.get().snapshotId()));
            return ProfileStateCodec.decode(snapshot.get().state());
        }

        /**
         * Moves the player into the target profile: to its last location, else its respawn point, else
         * the spawn of its PRIMARY bound world, which is also where a profile without a stored location
         * (a new one) goes. A destination that cannot be reached (its world is not loaded, the teleport
         * is refused) falls back to the next one with a warning; when none is left, the last failure
         * fails the switch. When the profile stores no location and its PRIMARY world's spawn is not
         * known, the player stays where they are, with a warning, as with every new profile before Build
         * 50. With {@code isolation.location} off the profile owns no location and the player stays where
         * they are.
         */
        private CompletableFuture<Void> moveToTarget(final ProfileState state) {
            if (!ProfileEngine.this.settings.isolation().location()) return CompletableFuture.completedFuture(null);
            final List<ProfileState.Location> stored = new ArrayList<>(2);
            if (state.returnPoint() != null) stored.add(state.returnPoint());
            if (state.spawn() != null && !state.spawn().equals(state.returnPoint())) stored.add(state.spawn());
            final ProfileWorldBinding binding = this.targetBinding;
            final String primary = primaryWorld(binding);
            if (stored.isEmpty()) {
                if (primary == null) return CompletableFuture.completedFuture(null);
                if (ProfileEngine.this.worlds.spawnOf(primary) == null) {
                    // Nothing of the profile's own state is left unapplied: the PRIMARY spawn is only where a
                    // profile without a location is placed, so a port that does not know it (or a world
                    // unloaded since it was made ready) does not fail a switch that applied everything else.
                    SourbyLogger.warn("AWF MultiProfile: switch " + this.journal.get().transitionId() + ": the spawn of"
                        + " the PRIMARY world '" + primary + "' is not known (the world is not loaded); " + playerId()
                        + " stays where they are");
                    return CompletableFuture.completedFuture(null);
                }
            }
            // Keep the bound worlds from being idle-unloaded under the teleport.
            if (binding != null) binding.worlds().keySet().forEach(ProfileEngine.this::touch);
            return phase(TransitionPhase.TELEPORTING).thenCompose(written -> teleportFrom(stored, 0, primary));
        }

        /** Tries {@code destinations} from {@code index} on, then the PRIMARY world's spawn when there is one. */
        private CompletableFuture<Void> teleportFrom(final List<ProfileState.Location> destinations, final int index,
                                                     final String primary) {
            if (index == destinations.size()) {
                // Reached only with a PRIMARY world: without one, the last stored destination's failure ends the chain.
                final ProfileState.Location spawn = ProfileEngine.this.worlds.spawnOf(primary);
                if (spawn == null) {
                    return failed(ProfileErrorCode.WORLD_UNAVAILABLE, "the PRIMARY world '" + primary + "' is not loaded");
                }
                return bounded(ProfileEngine.this.players.teleport(playerId(), spawn), "teleporting the player");
            }
            final ProfileState.Location destination = destinations.get(index);
            return bounded(ProfileEngine.this.players.teleport(playerId(), destination), "teleporting the player")
                .exceptionallyCompose(failed -> {
                    final boolean last = index + 1 == destinations.size() && primary == null;
                    if (last || codeOf(failed) == ProfileErrorCode.TIMEOUT) return CompletableFuture.failedFuture(failed);
                    SourbyLogger.warn("AWF MultiProfile: switch " + this.journal.get().transitionId() + " could not move "
                        + playerId() + " into world '" + destination.world() + "' (" + messageOf(failed) + "); trying "
                        + (index + 1 < destinations.size() ? "the profile's respawn point"
                            : "the spawn of its PRIMARY world '" + primary + "'"));
                    return teleportFrom(destinations, index + 1, primary);
                });
        }

        private CompletableFuture<Void> commit() {
            return phase(TransitionPhase.COMMITTING).thenCompose(written -> io(() -> {
                final OwnerClaim held = this.claim.get();
                if (ProfileEngine.this.settings.coordination().fencingEnabled() && held != null
                    && !ProfileEngine.this.ownership.validate(held).join()) {
                    throw error(ProfileErrorCode.OWNERSHIP_CONFLICT,
                        "the profile ownership fence is no longer valid; nothing was committed");
                }
                final PlayerAccount current = ProfileEngine.this.repository.loadAccount(playerId(),
                    ProfileEngine.this.settings.defaultSlots());
                if (this.coop != null) {
                    commitFormat2(current);
                    this.commitPoint.set(true);
                    if (held != null) ProfileEngine.this.sessionClaims.put(playerId(), held);
                    return null;
                }
                try {
                    ProfileEngine.this.repository.saveAccount(current.withActive(this.target.id()),
                        ProfileWriteGuard.of(held, current.revision()));
                } catch (final IOException lost) {
                    // A remote repository may have run the commit and lost the reply: rolling the
                    // player back now could leave the account naming the target under the previous
                    // state. Compensation establishes the outcome first.
                    if (ProfileEngine.this.repository.remote()) this.uncertainCommit = current.revision();
                    throw lost;
                }
                this.commitPoint.set(true);
                // The switch owns the target claim from here on; quit and shutdown must release
                // this one, not the claim the previous profile had at join time.
                if (held != null) ProfileEngine.this.sessionClaims.put(playerId(), held);
                return null;
            }));
        }

        /**
         * The format-2 commit point: the account names the target and the player's epoch there, under
         * {@link CoopWriteGuard#switchActive} — {@code Q}, expected {@code A}, the active profile before the switch,
         * both profiles' {@code M} and both streams' {@code R} (the source's after this run's rollback point). A
         * failure before publication is an exception and nothing was committed; an UNKNOWN outcome that reading the
         * account back does not settle leaves the switch for recovery and is never rolled back.
         */
        private void commitFormat2(final PlayerAccount current) throws IOException {
            final PlayerSession session = ProfileEngine.this.sessions.get(playerId());
            if (session == null || !session.claim().equals(this.coop.claim())) {
                throw error(ProfileErrorCode.CLAIM_LOST, "the player session the switch was admitted under ended; nothing"
                    + " was committed");
            }
            requireLive(playerId(), session);
            if (current.revision() != this.coop.accountRevision()
                || current.sessionGeneration() != this.coop.sessionGeneration()) {
                throw error(ProfileErrorCode.STALE_STATE, "the account changed during the switch (revision "
                    + this.coop.accountRevision() + " -> " + current.revision() + "); nothing was committed");
            }
            final UUID previous = this.account.activeProfile();
            final List<Expected> vector = new ArrayList<>(4);
            vector.add(Expected.metadata(this.target.id(), this.coop.toMetadata()));
            vector.add(Expected.head(this.target.id(), playerId(), this.coop.toEpoch(), this.coop.toHead()));
            if (previous != null) {
                vector.add(Expected.metadata(previous, this.coop.fromMetadata()));
                vector.add(Expected.head(previous, playerId(), this.coop.fromEpoch(), this.fromHead));
            }
            final PlayerAccount after = current.withActive(this.target.id(), this.coop.toEpoch());
            final CommitResult result = ProfileEngine.this.repository.commitAccount(after,
                CoopWriteGuard.switchActive(this.coop.operation(), this.coop.claim(), current.revision(),
                    current.activeProfile(), vector));
            if (result.unknown()) {
                try {
                    settle("the switch commit of " + playerId(), result, () -> receipted(playerId(), this.coop.operation())
                        || after.equals(ProfileEngine.this.repository.loadAccount(playerId(),
                            ProfileEngine.this.settings.defaultSlots())));
                } catch (final ProfileOperationException unknown) {
                    this.unknownCommit = messageOf(unknown);
                    throw unknown;
                }
            }
            accountCommitted(after);
            final ProfileTransition journal = this.journal.get();
            if (journal.coop() != null) {
                this.journal.set(journal.withCoop(journal.coop().committed(after.revision(), "committed")));
            }
        }

        /** After the commit point a failure is not a rollback: the account already names the target. */
        private CompletableFuture<Void> afterCommit() {
            return releasePrevious().handle((released, failed) -> {
                if (failed != null) {
                    SourbyLogger.warn("AWF MultiProfile: releasing the previous profile failed after commit: "
                        + messageOf(failed));
                }
                return (Void) null;
            }).thenCompose(ignored -> ProfileEngine.this.<Void>io(() -> {
                ProfileEngine.this.repository.deleteTransition(this.journal.get().transitionId(), journalGuard());
                return null;
            }).handle((deleted, failed) -> {
                if (failed != null) {
                    // The journal still says COMMITTING, so the next join completes it forward.
                    SourbyLogger.warn("AWF MultiProfile: could not remove a completed journal: " + messageOf(failed));
                }
                return (Void) null;
            }));
        }

        /**
         * The commit's write failed on a remote repository, so it may have been applied. Claiming the
         * target again with a new owner token supersedes the claim the commit was sent under, and the
         * repository fences on that token, so a commit still on its way can no longer apply; the
         * account read after that decides. Applied: the switch is complete and finishes as committed.
         * Not applied: the usual compensation. When the claim or the read fails too, the outcome stays
         * unknown ({@link #leaveUnknown}).
         */
        private CompletableFuture<Void> settleCommit(final Throwable failed) {
            final long expected = this.uncertainCommit;
            this.uncertainCommit = -1L;
            return ProfileEngine.this.<Boolean>io(() -> {
                final ClaimResult barrier = ProfileEngine.this.ownership
                    .claim(this.target.id(), ProfileEngine.this.nodeId, UUID.randomUUID()).join();
                this.claim.set(barrier.granted() ? barrier.claim() : null);
                final PlayerAccount account = ProfileEngine.this.repository.loadAccount(playerId(),
                    ProfileEngine.this.settings.defaultSlots());
                return this.target.id().equals(account.activeProfile()) && account.revision() > expected;
            }).handle((applied, unknown) -> {
                if (unknown != null) return leaveUnknown(failed, unknown);
                if (applied) return completeCommitted();
                return compensate(failed);
            }).thenCompose(next -> next);
        }

        /** The lost commit was applied: finish exactly as the switch does after a commit. */
        private CompletableFuture<Void> completeCommitted() {
            this.commitPoint.set(true);
            final OwnerClaim held = this.claim.get();
            if (held != null) {
                ProfileEngine.this.sessionClaims.put(playerId(), held);
            } else {
                // The recorded claim is still the one on the profile just left; kept, the heartbeat would
                // claim that profile again although the account no longer names it.
                ProfileEngine.this.sessionClaims.remove(playerId());
            }
            SourbyLogger.warn("AWF MultiProfile: switch " + this.journal.get().transitionId() + " lost the reply of its"
                + " commit, but the account shows it was applied; the switch completed"
                + (held == null ? ", and another node holds the target now, so this player's saves are refused until"
                    + " they join again" : ""));
            return afterCommit();
        }

        /**
         * Neither the claim nor the account could be read after a lost commit reply: the outcome is
         * unknown. Nothing is rolled back, because the account may already name the target; the player
         * keeps the state they have, and the journal is left pending, which refuses their saves and
         * switches until recovery decides from the account (forward when it names the target, back to the
         * rollback point otherwise). The target claim is kept until its lease ends.
         */
        private CompletableFuture<Void> leaveUnknown(final Throwable failed, final Throwable unknown) {
            if (ProfileEngine.this.metrics != null) ProfileEngine.this.metrics.profileFailure();
            adoptPreviousClaim();
            this.rollbackPending = true;
            final String reason = messageOf(failed) + "; whether the commit was applied is unknown (" + messageOf(unknown)
                + "), so nothing was rolled back and recovery decides from the account";
            SourbyLogger.error("AWF MultiProfile: switch " + this.journal.get().transitionId() + ": " + reason);
            return record(this.journal.get().recovering(reason));
        }

        private CompletableFuture<Void> releasePrevious() {
            final UUID previous = this.journal.get().fromProfile();
            if (previous == null || previous.equals(this.target.id())) {
                return CompletableFuture.completedFuture(null);
            }
            final OwnerClaim held = this.previousClaim.getAndSet(null);
            return ProfileEngine.this.io(() -> {
                if (held != null) {
                    ProfileEngine.this.sessionClaims.remove(playerId(), held);
                    // Other members of a co-op still play the profile just left on this node: the node keeps its claim.
                    releaseNodeClaimUnlessHeld(held, playerId());
                    return null;
                }
                final OwnerClaim holder = ProfileEngine.this.ownership.holder(previous).join();
                if (holder != null && holder.node().equals(ProfileEngine.this.nodeId)
                    && ProfileEngine.this.ownership.foreignHolder(previous).join() == null) {
                    // This process holds it without a recorded claim of this run: release it only when no other session
                    // or run on this node shares it (review P3-16).
                    ProfileEngine.this.sessionClaims.remove(playerId(), holder);
                    releaseNodeClaimUnlessHeld(holder, playerId());
                }
                return null;
            });
        }

        /** Releases the claim a failed switch took on the target; the player never committed to it. */
        private CompletableFuture<Void> releaseTargetClaim() {
            final OwnerClaim held = this.claim.getAndSet(null);
            if (held == null) return CompletableFuture.completedFuture(null);
            // A node claim other members' sessions share is not this run's to release; checked in the I/O step (review P2-6).
            if (this.targetClaimShared) return CompletableFuture.completedFuture(null);
            return ProfileEngine.this.<Void>io(() -> {
                releaseNodeClaimUnlessHeld(held, playerId());
                return null;
            }).handle((released, failed) -> {
                if (failed != null) {
                    SourbyLogger.warn("AWF MultiProfile: releasing the claim of a failed switch failed: "
                        + messageOf(failed));
                }
                return null;
            });
        }

        /**
         * The player stays on the previous profile. Its claim was taken again with this switch's
         * token, which superseded the session claim from the join, so it becomes the session claim:
         * quit and the heartbeat then use a claim that is still valid.
         */
        private void adoptPreviousClaim() {
            final OwnerClaim previous = this.previousClaim.getAndSet(null);
            if (previous != null) ProfileEngine.this.sessionClaims.put(playerId(), previous);
        }

        private CompletableFuture<Void> compensate(final Throwable failed) {
            this.abandoned.set(true);
            final String reason = messageOf(failed);
            if (this.commitPoint.get()) {
                // The account already names the target and the journal still says COMMITTING, so
                // recovery completes the switch forward; nothing is rolled back.
                SourbyLogger.warn("AWF MultiProfile: switch " + this.journal.get().transitionId()
                    + " failed after its commit (" + reason + "); recovery completes it");
                return CompletableFuture.completedFuture(null);
            }
            if (this.uncertainCommit >= 0L) return settleCommit(failed);
            if (this.unknownCommit != null) {
                // Format 2: never compensate an UNKNOWN commit; recovery decides from the account.
                return leaveUnknown(failed, error(ProfileErrorCode.UNKNOWN_OUTCOME, this.unknownCommit));
            }
            if (ProfileEngine.this.metrics != null) ProfileEngine.this.metrics.profileFailure();
            final Map<String, String> recovery = Map.of("player", playerId().toString(),
                "transition", this.journal.get().transitionId().toString(), "reason", truncate(reason));
            adoptPreviousClaim();
            return releaseTargetClaim()
                .thenCompose(ignored -> ProfileEngine.this.<Void>io(() -> {
                    publish(FabricEventType.PROFILE_RECOVERY_REQUIRED, recovery);
                    return null;
                }))
                .handle((ignored, publishFailed) -> null)
                .thenCompose(ignored -> rollBack(reason));
        }

        private CompletableFuture<Void> rollBack(final String reason) {
            if (!this.targetApplyStarted.get()) {
                // Nothing of the target reached the player: their live state is still the previous
                // profile's. Applying the capture again would revert whatever happened since.
                return record(this.journal.get().failed(reason));
            }
            final ProfileState captured = this.previousState;
            if (captured == null || !ProfileEngine.this.players.online(playerId())) {
                return leavePending(reason + "; the player is offline, so the rollback runs at the next join");
            }
            try {
                ProfileEngine.this.players.validateApply(captured);
            } catch (final ProfileOperationException unappliable) {
                // Required adapter data of the captured state lost its registration meanwhile: the rollback would be
                // partial, so it stays pending for a join on a node that has the adapter.
                return leavePending(reason + "; the rollback cannot apply completely: " + unappliable.getMessage());
            }
            return ProfileEngine.this.players.apply(playerId(), captured, ProfileEngine.this.settings.isolation())
                .orTimeout(ProfileEngine.this.settings.switching().timeoutMillis(), TimeUnit.MILLISECONDS)
                .handle((applied, rollbackFailed) -> rollbackFailed)
                .thenCompose(rollbackFailed -> {
                    if (rollbackFailed == null) {
                        return record(this.journal.get().failed(reason + "; rolled back to the captured state"));
                    }
                    final String why = unwrapCompletion(rollbackFailed) instanceof TimeoutException
                        ? "the rollback did not finish within switching.timeout-ms"
                        : "the rollback did not complete: " + messageOf(rollbackFailed);
                    SourbyLogger.warn("AWF MultiProfile: " + why + " (switch " + this.journal.get().transitionId() + ")");
                    return leavePending(reason + "; " + why);
                });
        }

        /** The target state may still be on the player: keep a rollback for the next join when one can run. */
        private CompletableFuture<Void> leavePending(final String reason) {
            final ProfileTransition current = this.journal.get();
            if (current.fromSnapshotId() == null) {
                // require-durable-save: false and the rollback point could not be stored: a later
                // rollback would have nothing to apply, so the journal is a terminal record.
                return record(current.failed(reason + "; no durable rollback point exists"));
            }
            this.rollbackPending = true;
            return record(current.recovering(reason));
        }

        private CompletableFuture<Void> record(final ProfileTransition outcome) {
            this.journal.set(outcome);
            return ProfileEngine.this.<Void>io(() -> {
                ProfileEngine.this.repository.saveTransition(outcome, journalGuard());
                return null;
            }).handle((written, failed) -> {
                if (failed != null) {
                    SourbyLogger.error("AWF MultiProfile: could not record the outcome of switch "
                        + outcome.transitionId() + " (" + outcome.phase() + "): " + messageOf(failed)
                        + "; the journal keeps its last phase, which the next join resolves from its rollback point");
                }
                return null;
            });
        }

        /** Publishes a committed switch and describes the target for the result; never fails the result. */
        private CompletableFuture<ProfileInfo> describeTarget() {
            return ProfileEngine.this.io(() -> {
                if (this.commitPoint.get()) {
                    publish(FabricEventType.PROFILE_SWITCH_COMPLETED, Map.of(
                        "player", playerId().toString(),
                        "profile", this.target.id().toString(),
                        "from", String.valueOf(this.journal.get().fromProfile())));
                }
                return describe(this.target.id()).orElse(null);
            }).exceptionally(failed -> null);
        }

        /** The claim the journal is written under: the target's, else the previous profile's. */
        private ProfileWriteGuard journalGuard() {
            final OwnerClaim held = this.claim.get();
            return ProfileWriteGuard.claimed(held != null ? held : this.previousClaim.get());
        }

        private CompletableFuture<Void> phase(final TransitionPhase next) {
            return io(() -> {
                checkDeadline();
                final ProfileTransition updated = this.journal.get().at(next);
                ProfileEngine.this.repository.saveTransition(updated, journalGuard());
                this.journal.set(updated);
                return null;
            });
        }

        /** Bounds a player or world step by what is left of switching.timeout-ms. */
        private <T> CompletableFuture<T> bounded(final CompletableFuture<T> step, final String what) {
            final long remaining = Math.max(1L, this.journal.get().deadlineMillis() - System.currentTimeMillis());
            return step.orTimeout(remaining, TimeUnit.MILLISECONDS)
                .exceptionallyCompose(failed -> CompletableFuture.failedFuture(
                    unwrapCompletion(failed) instanceof TimeoutException
                        ? error(ProfileErrorCode.TIMEOUT, what + " did not finish within switching.timeout-ms ("
                            + ProfileEngine.this.settings.switching().timeoutMillis() + " ms)")
                        : failed));
        }

        private void checkDeadline() {
            if (this.journal.get().pastDeadline()) {
                throw error(ProfileErrorCode.TIMEOUT, "the switch exceeded switching.timeout-ms ("
                    + ProfileEngine.this.settings.switching().timeoutMillis() + " ms)");
            }
        }
    }

    private record SwitchStart(PlayerAccount account, AuroraProfile target, SwitchCoop coop) {}

    /**
     * What a format-2 switch was admitted with: the player's session claim {@code Q}, the operation, both
     * membership epochs, {@code A}, both profiles' {@code M} and both member heads' {@code R}
     * ({@link CoopWriteGuard#ABSENT} for a stream with no head; {@code fromMetadata}/{@code fromHead} are
     * {@code ABSENT} when there is no previous profile).
     */
    private record SwitchCoop(PlayerClaim claim, CoopOperation operation, UUID fromEpoch, UUID toEpoch, long accountRevision,
                              long sessionGeneration, long fromMetadata, long toMetadata, long fromHead, long toHead) {}

    /**
     * Format-2 switch admission, inside the switch's first I/O step: the player needs an admitted session on
     * this node and a membership row on the target; the previous profile needs the account's active epoch.
     */
    private SwitchCoop switchAdmission(final PlayerAccount account, final AuroraProfile target) throws IOException {
        final UUID playerId = account.playerId();
        final PlayerSession session = this.sessions.get(playerId);
        if (session == null) {
            throw error(ProfileErrorCode.PLAYER_OFFLINE, "the player has no admitted session on this node; a format-2"
                + " switch is made under it");
        }
        requireLive(playerId, session);
        requireNoUnresolvedIntent(playerId);
        if (account.pendingIntent() != null) {
            throw error(ProfileErrorCode.RECOVERY_REQUIRED, "the account is reserved by intent " + account.pendingIntent());
        }
        final ProfileMember row = target.member(playerId);
        if (row == null) {
            throw error(ProfileErrorCode.NOT_OWNED, "the player is not a member of profile " + target.id());
        }
        if (target.pendingIntent() != null) {
            throw error(ProfileErrorCode.RECOVERY_REQUIRED, "profile " + target.id() + " is reserved by intent "
                + target.pendingIntent());
        }
        final UUID from = account.activeProfile();
        long fromMetadata = CoopWriteGuard.ABSENT;
        long fromHead = CoopWriteGuard.ABSENT;
        if (from != null) {
            if (account.activeEpoch() == null) {
                throw error(ProfileErrorCode.STALE_STATE, "the account names active profile " + from + " without a"
                    + " membership epoch; refusing to switch");
            }
            final AuroraProfile previous = this.repository.profile(from)
                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "the active profile " + from + " is not stored"));
            fromMetadata = previous.revision();
            fromHead = this.repository.memberHead(from, playerId, account.activeEpoch())
                .map(MemberStateHead::revision).orElse(CoopWriteGuard.ABSENT);
        }
        final long toHead = this.repository.memberHead(target.id(), playerId, row.epoch())
            .map(MemberStateHead::revision).orElse(CoopWriteGuard.ABSENT);
        final CoopOperation op = operation(CoopOperation.Kind.SWITCH_ACTIVE, playerId, from, target.id(),
            account.revision(), account.sessionGeneration());
        return new SwitchCoop(session.claim(), op, account.activeEpoch(), row.epoch(), account.revision(),
            account.sessionGeneration(), fromMetadata, target.revision(), fromHead, toHead);
    }

    private static String truncateIncarnation(final String incarnation) {
        final String value = incarnation == null || incarnation.isEmpty() ? "unknown" : incarnation;
        return value.length() <= ProfileTransition.MAX_INCARNATION_CHARS ? value
            : value.substring(0, ProfileTransition.MAX_INCARNATION_CHARS);
    }

    private record Recovery(ProfileTransition journal, PlayerAccount account) {}

    // ------------------------------------------------------------------ state and snapshots

    private void writeSnapshot(final AuroraProfile profile, final ProfileState state, final OwnerClaim claim,
                               final ProfileStateAdapters.Capture adapters) throws IOException {
        final long began = System.nanoTime();
        writeSnapshotFor(profile.id(), state, adapters, claim);
        if (this.metrics != null) this.metrics.snapshot(System.nanoTime() - began);
    }

    /**
     * Publishes a snapshot at {@code revision + 1} and the profile record bumped to it. The guard
     * carries the revision read here, so the repository refuses the publish if another writer moved
     * the profile on in between. The state keeps the adapter entries of the profile's latest snapshot
     * that the capture does not account for ({@link #withCarriedAdapters}).
     */
    private ProfileSnapshot writeSnapshotFor(final UUID profileId, final ProfileState state,
                                             final ProfileStateAdapters.Capture adapters, final OwnerClaim claim)
        throws IOException {
        final AuroraProfile profile = this.repository.profile(profileId)
            .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + profileId));
        final byte[] encoded = encode(profileId, withCarriedAdapters(profileId, state, adapters));
        final ProfileSnapshot snapshot = new ProfileSnapshot(profileId, UUID.randomUUID(), ProfileState.SCHEMA,
            profile.revision() + 1, encoded, ProfileStateCodec.checksum(encoded), System.currentTimeMillis());
        this.repository.publishSnapshot(profile.nextRevision(), snapshot, SNAPSHOTS_KEPT,
            ProfileWriteGuard.of(claim, profile.revision()));
        return snapshot;
    }

    /**
     * The captured state with the adapter entries of the profile's latest snapshot that no adapter
     * registered on this boot accounts for ({@link PlayerStatePort#carryAdapters}), so the data of a
     * plugin that is missing on this boot survives the save. Runs inside an I/O step. When the latest
     * snapshot cannot be read, the capture is stored as it is, with a warning: the entries only that
     * snapshot held are then not kept, and the save is not refused for it.
     */
    private ProfileState withCarriedAdapters(final UUID profileId, final ProfileState state,
                                             final ProfileStateAdapters.Capture adapters) {
        final ProfileState previous;
        try {
            final Optional<ProfileSnapshot> latest = this.repository.latestSnapshot(profileId);
            if (latest.isEmpty()) return state;
            previous = ProfileStateCodec.decode(latest.get().state());
        } catch (final IOException | IllegalArgumentException unreadable) {
            SourbyLogger.warn("AWF MultiProfile: the latest snapshot of " + profileId + " could not be read ("
                + unreadable.getMessage() + "); adapter data only it held is not kept in the new snapshot");
            return state;
        }
        return previous.adapters().isEmpty() ? state : this.players.carryAdapters(state, previous, adapters);
    }

    /**
     * {@link #withCarriedAdapters(UUID, ProfileState, ProfileStateAdapters.Capture)} for a format-2 stream, from the
     * head's exact snapshot. The merge fails with {@link ProfileErrorCode#STATE_TOO_LARGE} when the entries do not
     * fit the adapter budget together: the snapshot is refused rather than written without some of them.
     */
    private ProfileState withCarriedAdapters(final ProfileState state, final ProfileStateAdapters.Capture adapters,
                                             final SnapshotRead source, final UUID owner) {
        final ProfileState previous;
        try {
            final Optional<ProfileSnapshot> named = source.read();
            if (named.isEmpty()) return state;
            previous = ProfileStateCodec.decode(named.get().state());
        } catch (final IOException | RuntimeException unreadable) {
            SourbyLogger.warn("AWF MultiProfile: the head snapshot of " + owner + " could not be read ("
                + unreadable.getMessage() + "); adapter data only it held is not kept in the new snapshot");
            return state;
        }
        return previous.adapters().isEmpty() ? state : this.players.carryAdapters(state, previous, adapters);
    }

    private interface SnapshotRead {
        Optional<ProfileSnapshot> read() throws IOException;
    }

    /**
     * The exact snapshot a member head names, checked with {@link MemberStateHead#matches}; empty for a head that
     * is not READY. A READY head whose snapshot is missing or differs (older or newer) fails closed with
     * {@link ProfileErrorCode#STALE_STATE}; another snapshot is never substituted.
     */
    private Optional<ProfileSnapshot> exactHeadSnapshot(final MemberStateHead head) throws IOException {
        if (!head.restorable()) return Optional.empty();
        final Optional<ProfileSnapshot> snapshot = this.repository.memberSnapshot(head.profileId(), head.memberId(),
            head.epoch(), head.snapshotId());
        if (snapshot.isEmpty() || !head.matches(snapshot.get())) {
            throw error(ProfileErrorCode.STALE_STATE, "member head " + head.describe() + " names snapshot " + head.snapshotId()
                + " at revision " + head.revision() + ", which " + (snapshot.isEmpty() ? "is not stored" : "does not match it")
                + "; refusing to use another snapshot");
        }
        return snapshot;
    }

    /** The exact head snapshot of a stream, or empty when the stream has no head or is not READY. */
    private Optional<ProfileSnapshot> headSnapshot(final UUID profileId, final UUID member, final UUID epoch) throws IOException {
        final Optional<MemberStateHead> head = this.repository.memberHead(profileId, member, epoch);
        return head.isEmpty() ? Optional.empty() : exactHeadSnapshot(head.get());
    }

    // ------------------------------------------------------------------ format-2 commits

    private static CoopOperation operation(final CoopOperation.Kind kind, final UUID actor, final Object... request) {
        final Object[] parts = new Object[request.length + 2];
        parts[0] = kind;
        parts[1] = actor;
        System.arraycopy(request, 0, parts, 2, request.length);
        return CoopOperation.start(kind, actor, CoopOperation.digest(parts));
    }

    /** A write of the account record only: {@code Q(account)}, expected {@code A}, optionally the active profile. */
    private static CoopWriteGuard accountGuard(final CoopOperation op, final PlayerClaim q, final long expectedAccount,
                                               final UUID expectedActive) {
        return new CoopWriteGuard(op, null, q, null, List.of(Expected.account(q.playerId(), expectedAccount)),
            expectedActive, Set.of());
    }

    /** The active-profile predicate value of an account ({@link CoopWriteGuard#NO_ACTIVE_PROFILE} for none). */
    private static UUID activeOf(final PlayerAccount account) {
        return account.activeProfile() == null ? CoopWriteGuard.NO_ACTIVE_PROFILE : account.activeProfile();
    }

    /** {@link ProfileRepository#commitAccount}; an UNKNOWN outcome is settled from the receipt or the stored account. */
    private void commitAccount(final PlayerAccount after, final CoopWriteGuard guard) throws IOException {
        final CommitResult result = this.repository.commitAccount(after, guard);
        if (result.unknown()) {
            settle("the account " + after.playerId(), result, () -> receipted(after.playerId(), guard.operation())
                || after.equals(this.repository.loadAccount(after.playerId(), this.settings.defaultSlots())));
        }
        accountCommitted(after);
    }

    /** {@link ProfileRepository#commitProfile}; an UNKNOWN outcome is settled from the receipt or the stored record. */
    private void commitProfile(final AuroraProfile after, final CoopWriteGuard guard) throws IOException {
        final CommitResult result = this.repository.commitProfile(after, guard);
        if (result.unknown()) {
            settle("profile " + after.id(), result, () -> receipted(after.id(), guard.operation())
                || after.equals(this.repository.profile(after.id()).orElse(null)));
        }
    }

    /** Keeps an admitted session's capture-ticket fields in step with this node's own commits of its account. */
    private void accountCommitted(final PlayerAccount after) {
        this.sessions.computeIfPresent(after.playerId(), (player, session) -> session.with(after));
    }

    // ------------------------------------------------------------------ format-2 intents (PREPARE -> COMMIT)

    /** How long a solo intent may stay PREPARED before another node's startup recovery aborts it (distributed mode). */
    private static final long INTENT_DEADLINE_MILLIS = TimeUnit.MINUTES.toMillis(2);

    /** One after-image an intent installs: a non-stream record encoded at the revision COMMIT writes, without a reservation. */
    private record AfterImage(byte recordType, UUID id, long revision, byte[] payload) {}

    /**
     * Runs one solo operation as a single intent (COOP-DESIGN §5): stages the after-images, PREPAREs under the
     * claims (installing this operation's reservations on the records {@code after} names at their pre-reservation
     * revisions {@code before}), then COMMITs; an interrupted run is left as a PREPARED or COMMITTED intent that
     * {@link #resolveIntent} recovers (abort clears only its reservations, a committed one rolls forward), never
     * half-applied. A refused commit aborts the intent and rethrows the refusal. An UNKNOWN outcome is settled
     * from the stored intent's phase (both backends keep it permanently), never by retrying or compensating:
     * COMMITTED or FINALIZED counts as applied, PREPARED is aborted and reported as not applied. Runs inside an
     * I/O step; {@code q} is required, {@code p} when the operation changes profile metadata.
     */
    private void runIntent(final CoopOperation op, final ProfileClaim p, final PlayerClaim q, final List<Expected> before,
                           final List<Expected> after, final List<AfterImage> images) throws IOException {
        final UUID id = op.operationId();
        final List<ProfileIntent.BlobRef> refs = new ArrayList<>(images.size());
        for (final AfterImage image : images) {
            refs.add(this.repository.stageAfterImage(id, image.recordType(), image.id(), null, null, image.revision(), image.payload()));
        }
        final CoopWriteGuard prepareGuard = CoopWriteGuard.intent(op, p, q, CoopWriteGuard.ABSENT, before, Set.of());
        // The deadline is compared against the store's clock (Redis TIME in distributed mode), never a node's.
        final ProfileIntent intent = ProfileIntent.prepared(prepareGuard, after, List.of(), refs, List.of(),
            this.repository.storeClockMillis() + INTENT_DEADLINE_MILLIS);
        driveIntent(op, p, q, null, prepareGuard, intent);
    }

    /**
     * Runs one co-op membership plan ({@link CoopMembership}) exactly like {@link #runIntent}: stages its framed
     * after-images (member heads with their member and epoch), PREPAREs under {@code P}, the actor's {@code Q} and,
     * for a kick, the removed member's {@code Q} ({@code affected}), then COMMITs; the same UNKNOWN, abort and
     * recovery rules apply. Before the first co-op record of the store is prepared the store's feature level is
     * raised ({@link #requireCoopStoreLevel}), so no build that cannot read a co-op record opens the store after it.
     */
    private void runPlan(final CoopIntentPlan plan, final ProfileClaim p, final PlayerClaim q, final PlayerClaim affected)
        throws IOException {
        requireCoopStoreLevel();
        final List<ProfileIntent.BlobRef> refs = plan.stage(this.repository);
        final CoopWriteGuard prepareGuard = plan.prepareGuard(p, q, affected);
        driveIntent(plan.op(), p, q, affected, prepareGuard, plan.prepared(prepareGuard, refs));
    }

    /** PREPARE then COMMIT of a staged intent; see {@link #runIntent}. Runs inside an I/O step. */
    private void driveIntent(final CoopOperation op, final ProfileClaim p, final PlayerClaim q, final PlayerClaim affected,
                             final CoopWriteGuard prepareGuard, final ProfileIntent intent) throws IOException {
        final UUID id = op.operationId();
        final CommitResult prepared = this.repository.prepareIntent(intent, prepareGuard);
        ProfileIntent stored = this.repository.intent(id).orElse(null);
        if (stored == null) {
            if (prepared.unknown()) {
                // The prepare's reply was lost and no intent is stored yet: the request may still reach the store
                // and install its reservations. Nothing is applied now; should it land, the intent is PREPARED and
                // the player's next admission (which also reads the account's reservation and the per-player index)
                // or the next start aborts it. Until then the account's format-2 changes are refused.
                markUnresolved(intent, "its prepare reply was lost and the intent was not stored when read back");
                throw error(ProfileErrorCode.UNKNOWN_OUTCOME, op.kind() + " " + id + " was not prepared when read back ("
                    + prepared.detail() + "); if the store still applies the prepare, recovery aborts it");
            }
            throw error(ProfileErrorCode.IO_FAILURE, op.kind() + " " + id + " was not prepared (" + prepared.detail()
                + "); nothing was applied");
        }
        if (prepared.unknown() && stored.phase() == ProfileIntent.Phase.PREPARED) {
            // The intent is durable but its reservations may be incomplete: resolve installs the rest, or finds a
            // record that moved, in which case the commit below is refused as stale and the intent aborted.
            this.repository.resolveIntent(id, intentGuard(op, p, q, affected, stored));
            stored = this.repository.intent(id).orElse(stored);
        }
        if (!stored.pending()) {
            if (stored.committed()) return;
            throw error(ProfileErrorCode.IO_FAILURE, op.kind() + " " + id + " was aborted before it committed (" + stored.outcome()
                + "); nothing was applied");
        }
        final CommitResult committed;
        try {
            committed = this.repository.commitIntent(id, intentGuard(op, p, q, affected, stored));
        } catch (final IOException | RuntimeException refused) {
            abortUncommitted(op, p, q, affected, refused);
            throw refused;
        }
        if (!committed.unknown()) return;
        final ProfileIntent outcome = this.repository.intent(id).orElse(null);
        if (outcome != null && outcome.committed()) {
            acceptCommitted(op, p, q, affected, outcome, committed.detail());
            return;
        }
        if (outcome != null && outcome.phase() == ProfileIntent.Phase.PREPARED) {
            // The abort races a commit request that may still be in flight: the intent read after the abort
            // attempt decides. Committed: it landed first and counts as applied. Aborted: nothing was applied.
            // Anything else: unknown, and the operation stays unresolved for recovery.
            final ProfileIntent decided = abortUncommitted(op, p, q, affected, null);
            if (decided != null && decided.committed()) {
                acceptCommitted(op, p, q, affected, decided, committed.detail() + "; a delayed commit landed before the abort");
                return;
            }
            if (decided != null && decided.phase() == ProfileIntent.Phase.ABORTED) {
                throw error(ProfileErrorCode.IO_FAILURE, op.kind() + " " + id + " did not commit (" + committed.detail()
                    + "); the intent was aborted and nothing was applied");
            }
            markUnresolved(decided != null ? decided : outcome, "its commit reply was lost and the abort did not decide it");
            throw error(ProfileErrorCode.UNKNOWN_OUTCOME, "the store did not confirm the commit of " + op.kind() + " " + id + " ("
                + committed.detail() + ") and aborting it did not decide it either (intent " + (decided == null ? "unreadable"
                : decided.phase()) + "); it stays pending for recovery, neither retried nor compensated");
        }
        throw error(ProfileErrorCode.UNKNOWN_OUTCOME, "the store did not confirm the commit of " + op.kind() + " " + id + " ("
            + committed.detail() + ") and reading the intent back did not establish it; it was neither retried nor compensated");
    }

    /**
     * An intent read back as COMMITTED or FINALIZED after an unconfirmed commit counts as applied once the store
     * confirms durability again (COOP-DESIGN §5); a COMMITTED one is rolled forward now, and a failed or UNKNOWN
     * roll-forward leaves the operation unresolved, so no later commit moves a record the roll-forward expects.
     */
    private void acceptCommitted(final CoopOperation op, final ProfileClaim p, final PlayerClaim q, final PlayerClaim affected,
                                 final ProfileIntent outcome, final String detail) {
        final UUID id = op.operationId();
        if (!durable()) {
            markUnresolved(outcome, "its commit was read back but not confirmed durable");
            throw error(ProfileErrorCode.UNKNOWN_OUTCOME, "the store did not confirm the commit of " + op.kind() + " " + id
                + " (" + detail + "); reading it back shows the intent is " + outcome.phase() + " but the store"
                + " did not confirm it durable; it is neither retried nor compensated and stays pending for recovery");
        }
        SourbyLogger.warn("AWF MultiProfile: the store did not confirm the commit of " + op.kind() + " " + id + " ("
            + detail + "); reading it back shows the intent is " + outcome.phase() + ", so it counts as applied");
        if (outcome.phase() == ProfileIntent.Phase.COMMITTED) {
            try {
                final CommitResult finished = this.repository.resolveIntent(id, intentGuard(op, p, q, affected, outcome));
                if (finished.unknown()) markUnresolved(outcome, finished.detail());
            } catch (final IOException | RuntimeException later) {
                markUnresolved(outcome, "finishing it failed: " + messageOf(later));
            }
        }
    }

    /** The guard of a commit, abort or resolve of {@code intent}: its revision, its post-reservation vector, its own reservation. */
    private static CoopWriteGuard intentGuard(final CoopOperation op, final ProfileClaim p, final PlayerClaim q,
                                              final PlayerClaim affected, final ProfileIntent intent) {
        return CoopWriteGuard.intent(op, p, q, affected, intent.revision(), intent.expectedAfter(), Set.of(op.operationId()));
    }

    /**
     * Aborts the operation's intent while it is still PREPARED (clearing only its own reservations); a committed
     * intent is left to roll forward and logged. Failures are logged: the intent then stays for recovery.
     */
    private ProfileIntent abortUncommitted(final CoopOperation op, final ProfileClaim p, final PlayerClaim q, final PlayerClaim affected,
                                           final Throwable cause) {
        final UUID id = op.operationId();
        ProfileIntent intent = null;
        try {
            intent = this.repository.intent(id).orElse(null);
            if (intent == null || !intent.pending()) return intent;
            if (intent.committed()) {
                SourbyLogger.warn("AWF MultiProfile: " + op.kind() + " " + id + " reported a failure (" + (cause == null ? "unconfirmed"
                    : messageOf(cause)) + ") but its intent is committed; it rolls forward at recovery and is not aborted");
                return intent;
            }
            this.repository.abortIntent(id, intentGuard(op, p, q, affected, intent));
            return this.repository.intent(id).orElse(intent);
        } catch (final IOException | RuntimeException failed) {
            // A refused abort (DECIDED: a delayed commit landed first) or a failed one: the phase read now decides
            // for the caller; until it is terminal the operation stays unresolved for recovery.
            SourbyLogger.warn("AWF MultiProfile: aborting the " + op.kind() + " intent " + id + " failed (" + messageOf(failed)
                + "); it is resolved at the player's next login or the next start");
            try {
                final ProfileIntent after = this.repository.intent(id).orElse(intent);
                if (after != null && after.pending()) markUnresolved(after, "aborting it failed: " + messageOf(failed));
                return after;
            } catch (final IOException | RuntimeException unreadable) {
                if (intent != null) markUnresolved(intent, "aborting it failed: " + messageOf(failed));
                return intent;
            }
        }
    }

    private interface Applied {
        boolean check() throws IOException;
    }

    /**
     * Settles an UNKNOWN outcome by reading authority (the receipt, or the record compared with the exact
     * after-image). Established: returns, the write counts as committed. Otherwise, including when the read
     * itself fails: {@link ProfileErrorCode#UNKNOWN_OUTCOME}; nothing is compensated or retried.
     */
    private void settle(final String what, final CommitResult result, final Applied applied) {
        boolean committed;
        String readFailure = null;
        try {
            // Established from a read of the store's memory: the durability confirmation is required again (§5).
            committed = applied.check() && durable();
        } catch (final IOException | RuntimeException failed) {
            committed = false;
            readFailure = messageOf(failed);
        }
        if (committed) {
            SourbyLogger.warn("AWF MultiProfile: the store did not confirm the write of " + what + " (" + result.detail()
                + "); reading it back shows operation " + result.operationId() + " was applied");
            return;
        }
        throw error(ProfileErrorCode.UNKNOWN_OUTCOME, "the store did not confirm the write of " + what + " ("
            + result.detail() + ") and reading it back did not establish it" + (readFailure == null ? "" : " (" + readFailure + ")")
            + "; operation " + result.operationId() + " was neither retried nor compensated");
    }

    /** {@link ProfileRepository#durable()}; a failed probe is "not durable". */
    private boolean durable() {
        try {
            return this.repository.durable();
        } catch (final IOException | RuntimeException unavailable) {
            return false;
        }
    }

    /** Whether the store has a receipt of {@code op} on {@code resource}; a failed lookup is "not established". */
    private boolean receipted(final UUID resource, final CoopOperation op) {
        try {
            return this.repository.receipt(resource, op.operationId()).isPresent();
        } catch (final IOException | RuntimeException unavailable) {
            return false;
        }
    }

    /** The first world the binding gives the PRIMARY role, or {@code null}. */
    private static String primaryWorld(final ProfileWorldBinding binding) {
        if (binding == null) return null;
        for (final Map.Entry<String, ProfileWorldRole> entry : binding.worlds().entrySet()) {
            if (entry.getValue() == ProfileWorldRole.PRIMARY) return entry.getKey();
        }
        return null;
    }

    /** Archives the state a player had before their first profile; the first switch's rollback point. */
    private ProfileSnapshot archivePreProfileState(final UUID playerId, final ProfileState state, final OwnerClaim claim)
        throws IOException {
        final byte[] encoded = encode(playerId, state);
        final ProfileSnapshot snapshot = new ProfileSnapshot(playerId, UUID.randomUUID(), ProfileState.SCHEMA, 0L,
            encoded, ProfileStateCodec.checksum(encoded), System.currentTimeMillis());
        this.repository.saveLegacySnapshot(playerId, snapshot, ProfileWriteGuard.claimed(claim));
        SourbyLogger.info("AWF MultiProfile: archived the pre-profile state of " + playerId + " (" + encoded.length
            + " bytes) before its first profile was applied");
        return snapshot;
    }

    /**
     * Encodes a state. The codec's only encoding refusal is the size limit, so one too large to store is
     * {@link ProfileErrorCode#STATE_TOO_LARGE}, reported with its size.
     */
    private static byte[] encode(final UUID owner, final ProfileState state) {
        try {
            return ProfileStateCodec.encode(state);
        } catch (final ProfileStateCodec.ProfileStateFormatException tooLarge) {
            SourbyLogger.warn("AWF MultiProfile: the state of " + owner + " cannot be stored: " + tooLarge.getMessage());
            throw error(ProfileErrorCode.STATE_TOO_LARGE, "the profile state cannot be stored: " + tooLarge.getMessage());
        }
    }

    private Optional<ProfileInfo> describe(final UUID profileId) throws IOException {
        return describeDetails(profileId).map(ProfileDetails::info);
    }

    private Optional<ProfileDetails> describeDetails(final UUID profileId) throws IOException {
        final Optional<AuroraProfile> profile = this.repository.profile(profileId);
        if (profile.isEmpty() || profile.get().status() == ProfileStatus.DELETED) return Optional.empty();
        final PlayerAccount account = this.repository.loadAccount(profile.get().accountId(),
            this.settings.defaultSlots());
        final Map<UUID, String> legacyNames = profile.get().legacy()
            ? ProfileRepositoryMigration.legacyNames(this.repository.profiles(profile.get().accountId())) : Map.of();
        return Optional.of(details(profile.get(), account.activeProfile(), legacyNames));
    }

    private ProfileDetails details(final AuroraProfile profile, final UUID activeProfile) {
        return details(profile, activeProfile, Map.of());
    }

    /**
     * The profile with its name and policy type. A format-1 record carries neither: it reads with the name the
     * format-2 migration will store ({@link ProfileRepositoryMigration#legacyNames}: {@link CuteNames#fromId},
     * suffixed when an older profile of the account derives the same name, so names are unique within the account
     * and never change at migration) and {@code classic}. Without the account's other profiles the bare derived
     * name is used.
     */
    private ProfileDetails details(final AuroraProfile profile, final UUID activeProfile, final Map<UUID, String> legacyNames) {
        final String name = profile.legacy() ? legacyNames.getOrDefault(profile.id(), profile.name()) : profile.name();
        return new ProfileDetails(info(profile, activeProfile), name, profile.profileType());
    }

    private ProfileInfo info(final AuroraProfile profile, final UUID activeProfile) {
        final List<String> worlds = new ArrayList<>();
        try {
            this.repository.binding(profile.id()).ifPresent(binding -> worlds.addAll(binding.worlds().keySet()));
        } catch (final IOException failed) {
            SourbyLogger.warn("AWF MultiProfile: could not read the binding of " + profile.id() + ": " + failed.getMessage());
        }
        return new ProfileInfo(profile.id(), profile.accountId(), profile.type(), profile.metadata(),
            profile.createdAtMillis(), profile.revision(), profile.status().name(),
            profile.id().equals(activeProfile), worlds);
    }

    /** Blocking publish; callers run it inside an I/O step. A failure is logged, never propagated. */
    private void publish(final FabricEventType type, final Map<String, String> payload) {
        if (this.events == null) return;
        try {
            this.events.publish(FabricEvent.of(type, this.nodeId, payload));
        } catch (final IOException | RuntimeException notPublished) {
            SourbyLogger.warn("AWF MultiProfile: could not publish " + type + ": " + notPublished.getMessage());
        }
    }

    // ------------------------------------------------------------------ co-op membership (Build 52)

    /** Co-op events waiting for the event thread; beyond this an event is dropped with a warning (events are not a journal). */
    private static final int COOP_EVENT_QUEUE = 256;
    /** Expired invites or votes one operation closes before it runs (a bounded cleanup; the rest wait for the next one). */
    private static final int COOP_CLEANUP_LIMIT = 32;
    /** Index pages an open-invite read walks: the §9 bound of 64 open invites per profile or invitee, plus one. */
    private static final int COOP_INVITE_PAGES = ProfileInvite.MAX_OPEN / ProfileIndexPage.LISTING_PAGE + 1;
    private static final String MEMBER_CURSOR_PREFIX = "members:";

    private final Object coopEventLock = new Object();
    private volatile Consumer<org.bukkit.event.Event> coopEventSink = event -> {};
    private volatile java.util.concurrent.ThreadPoolExecutor coopEventLane;
    /** The build identity a raised store marker names as its writer. */
    private volatile String storeWriter = "SourbyCraft";
    /** Set once this engine raised (or found) the store's co-op feature level; it is never lowered. */
    private volatile boolean coopStoreLevel;

    /**
     * Where the co-op events go ({@code CoopMemberJoinEvent}, {@code CoopMemberLeaveEvent}, {@code CoopInviteEvent}):
     * the runtime passes the Bukkit plugin manager. The sink is called on the engine's own co-op event thread, never
     * on a region, entity, global tick or Netty thread, after the operation is durably committed.
     */
    public void onCoopEvent(final Consumer<org.bukkit.event.Event> sink) {
        this.coopEventSink = Objects.requireNonNull(sink, "sink");
    }

    /** The build identity written into a raised store marker ({@code writtenBy}, at most 64 characters). */
    public void storeWriter(final String writer) {
        if (writer != null && !writer.isBlank()) this.storeWriter = writer.length() <= 64 ? writer : writer.substring(0, 64);
    }

    /**
     * The co-op view of a profile; empty for a missing, deleted or solo profile. Fails {@code COOP_DISABLED} while
     * co-op is disabled and {@code MIGRATION_REQUIRED} on a format-1 store. This form is the operator's and the
     * plugin API's (no membership check); a player-facing caller uses {@link #coopDetails(UUID, UUID)}.
     */
    public CompletableFuture<Optional<dev.iyanz.sourbycraft.api.profile.CoopDetails>> coopDetails(final UUID profileId) {
        return coopDetails(null, profileId);
    }

    /**
     * {@link #coopDetails(UUID)} as seen by {@code viewer}: a co-op profile's members and label are shown to its
     * members only ({@code NOT_A_MEMBER} otherwise); {@code null} is the operator's unchecked view.
     */
    public CompletableFuture<Optional<dev.iyanz.sourbycraft.api.profile.CoopDetails>> coopDetails(final UUID viewer, final UUID profileId) {
        return coop(() -> {
            final Optional<AuroraProfile> found = this.repository.profile(profileId);
            if (found.isEmpty() || found.get().status() == ProfileStatus.DELETED || !found.get().coop()) return Optional.empty();
            requireViewer(found.get(), viewer);
            return Optional.of(coopView(found.get()));
        });
    }

    /** A player-facing read of a co-op profile is for its members; {@code null} is the operator's or a plugin's. */
    private static void requireViewer(final AuroraProfile profile, final UUID viewer) {
        if (viewer != null && profile.member(viewer) == null) {
            throw error(ProfileErrorCode.NOT_A_MEMBER, viewer + " is not a member of co-op profile " + profile.id()
                + "; its members, label and votes are shown to members only");
        }
    }

    /**
     * The one-way conversion of the actor's solo profile to co-op (COOP-DESIGN §10 slice 4). The actor must own it
     * ({@code NOT_OWNED}); its policy type must be in {@code coop.allowed-types} ({@code TYPE_NOT_ALLOWED}); OD-1: no
     * other node may hold the profile ({@code WRONG_NODE}). One intent under {@code P} and the actor's {@code Q}.
     */
    public CompletableFuture<dev.iyanz.sourbycraft.api.profile.CoopDetails> convertToCoop(final UUID actor, final UUID profileId) {
        return coop(() -> {
            final AuroraProfile profile = coopProfile(profileId, false);
            if (!profile.accountId().equals(actor)) {
                throw error(ProfileErrorCode.NOT_OWNED, actor + " is not the owner of profile " + profileId + "; only the owner"
                    + " converts it to co-op. Nothing was written");
            }
            if (!this.settings.coop().allows(profile.profileType())) {
                throw error(ProfileErrorCode.TYPE_NOT_ALLOWED, "profile " + profileId + " has policy type " + profile.profileType().id()
                    + ", which is not in coop.allowed-types " + ProfileSettings.typeIds(this.settings.coop().allowedTypes())
                    + ". Nothing was written");
            }
            requireProfileNode(profileId);
            requireProfileReady(profile);
            final PlayerAccount account = loadReadyAccount(actor);
            final long storeNow = this.repository.storeClockMillis();
            runCoopPlan(CoopMembership.convertToCoop(operation(CoopOperation.Kind.CONVERT_COOP, actor, profileId, profile.revision(),
                account.revision()), profile, account, storeNow + INTENT_DEADLINE_MILLIS));
            return coopView(coopProfile(profileId, true));
        });
    }

    /**
     * Sets ({@code label}) or clears ({@code null}) the shared display label of a co-op profile; the OWNER only. Changes
     * the profile record only, never an account alias. An invalid label is {@code NAME_INVALID}.
     */
    public CompletableFuture<Void> setCoopLabel(final UUID actor, final UUID profileId, final String label) {
        return coop(() -> {
            if (label != null && !AuroraProfile.validDisplayLabel(label)) {
                throw error(ProfileErrorCode.NAME_INVALID, "the display label must be 1-" + AuroraProfile.MAX_DISPLAY_LABEL
                    + " printable characters without surrounding whitespace. Nothing was written");
            }
            final AuroraProfile profile = coopProfile(profileId, true);
            requireProfileNode(profileId);
            requireProfileReady(profile);
            final PlayerAccount account = loadReadyAccount(actor);
            if (Objects.equals(profile.displayLabel(), label) && profile.member(actor) != null
                && profile.member(actor).role() == ProfileRole.OWNER) {
                return null;
            }
            final long storeNow = this.repository.storeClockMillis();
            runCoopPlan(CoopMembership.renameLabel(operation(CoopOperation.Kind.RENAME_DISPLAY, actor, profileId,
                label == null ? "" : label, profile.revision()), profile, account, label, storeNow + INTENT_DEADLINE_MILLIS));
            return null;
        });
    }

    /**
     * Invites {@code invitee} to a co-op profile (OWNER or MANAGER). Expired invites of the profile and of the invitee
     * still in their indexes are closed first (bounded), so the §9 index bounds count open invitations only. Fires
     * {@code CoopInviteEvent} (OPEN) after the commit.
     */
    public CompletableFuture<dev.iyanz.sourbycraft.api.profile.CoopInviteInfo> invite(final UUID actor, final UUID profileId,
                                                                                    final UUID invitee) {
        return coop(() -> {
            if (actor.equals(invitee)) throw error(ProfileErrorCode.INVALID_REQUEST, "a member cannot invite itself. Nothing was written");
            final AuroraProfile first = coopProfile(profileId, true);
            requireProfileNode(profileId);
            requireProfileReady(first);
            final PlayerAccount account = loadReadyAccount(actor);
            final long storeNow = this.repository.storeClockMillis();
            final List<ProfileInvite> open = openInvites(ProfileIndexPage.Kind.INVITES_BY_PROFILE, profileId, actor, storeNow);
            openInvites(ProfileIndexPage.Kind.INVITES_BY_INVITEE, invitee, actor, storeNow);
            final PlayerAccount inviteeAccount = this.repository.loadAccount(invitee, this.settings.defaultSlots());
            // Each cleanup close reserved and released M (and compared A): the profile and the clock are read again.
            final AuroraProfile profile = readyAgain(profileId, first);
            final long issuedAt = this.repository.storeClockMillis();
            final CoopIntentPlan plan = CoopMembership.createInvite(operation(CoopOperation.Kind.INVITE_CREATE, actor, profileId, invitee,
                profile.revision()), profile, account, invitee, inviteeAccount, open, coopPolicy(), issuedAt, issuedAt + INTENT_DEADLINE_MILLIS);
            runCoopPlan(plan);
            final ProfileInvite created = this.repository.invite(plan.result().recordId()).orElseThrow(() -> readBack("invite",
                plan.result().recordId()));
            final dev.iyanz.sourbycraft.api.profile.CoopInviteInfo info = inviteInfo(created, storeNow);
            fireCoopEvent(new dev.iyanz.sourbycraft.api.profile.CoopInviteEvent(info));
            return info;
        });
    }

    /**
     * The invitee accepts: one intent consumes the invite, admits a MEMBER row with a fresh epoch, creates its head and
     * gives the account access (one profile slot) under an account-unique alias. The invite's expiry is the commit
     * deadline (store clock): an accept that commits after it is {@code INVITE_EXPIRED}. A repeat of a committed accept
     * of the same invite returns the same membership. OD-1: refused {@code WRONG_NODE} while another node holds the
     * profile. Fires {@code CoopMemberJoinEvent}. The new member cannot switch into the profile before Build 53.
     */
    public CompletableFuture<dev.iyanz.sourbycraft.api.profile.CoopMemberInfo> acceptInvite(final UUID player, final UUID inviteId) {
        return coop(() -> {
            final ProfileInvite invite = inviteOf(player, inviteId);
            final AuroraProfile profile = coopProfile(invite.profileId(), true);
            if (invite.status() == ProfileInvite.Status.ACCEPTED) {
                final ProfileMember row = profile.member(player);
                if (row != null && row.epoch().equals(invite.acceptedEpoch())) return memberInfo(row);
                throw error(ProfileErrorCode.INVITE_NOT_FOUND, "invite " + inviteId + " was accepted earlier and that membership has"
                    + " ended; a new invitation is needed. Nothing was written");
            }
            requireProfileNode(profile.id());
            requireProfileReady(profile);
            final PlayerAccount account = loadReadyAccount(player);
            final long storeNow = this.repository.storeClockMillis();
            if (invite.open() && invite.expired(storeNow)) {
                closeQuietly(invite, player, ProfileInvite.Status.EXPIRED, storeNow);
                throw error(ProfileErrorCode.INVITE_EXPIRED, "invite " + inviteId + " expired at " + invite.expiresAtMillis()
                    + " (store clock " + storeNow + "). Nothing was written");
            }
            final CoopIntentPlan plan = CoopMembership.acceptInvite(operation(CoopOperation.Kind.INVITE_ACCEPT, player, inviteId,
                invite.revision()), profile, invite, account, coopPolicy(), undecidedVotes(profile), storeNow, storeNow + INTENT_DEADLINE_MILLIS);
            try {
                runCoopPlan(plan);
            } catch (final ProfileOperationException refused) {
                // Only the invite's own expiry is INVITE_EXPIRED: the plan's deadline is the invite's expiry exactly when
                // it is the earlier bound (both backends compare that deadline); the intent deadline (2 min) stays TIMEOUT.
                if (refused.code() == ProfileErrorCode.TIMEOUT && plan.deadlineMillis() == invite.expiresAtMillis()) {
                    throw new ProfileOperationException(ProfileErrorCode.INVITE_EXPIRED, "invite " + inviteId + " expired before the accept"
                        + " committed (judged by the store clock at commit): " + refused.getMessage(), refused);
                }
                throw refused;
            }
            final ProfileMember row = coopProfile(profile.id(), true).member(player);
            if (row == null || !row.epoch().equals(plan.result().epoch())) {
                throw error(ProfileErrorCode.UNKNOWN_OUTCOME, "the accept of invite " + inviteId + " committed but the membership of "
                    + player + " does not read back with epoch " + plan.result().epoch() + "; list the members before retrying");
            }
            fireCoopEvent(new dev.iyanz.sourbycraft.api.profile.CoopMemberJoinEvent(profile.id(), player, role(row.role()), inviteId));
            return memberInfo(row);
        });
    }

    /** The invitee declines an open invitation. Fires {@code CoopInviteEvent} (DECLINED). */
    public CompletableFuture<Void> declineInvite(final UUID player, final UUID inviteId) {
        return coop(() -> {
            final ProfileInvite invite = inviteOf(player, inviteId);
            closeInvite(invite, player, ProfileInvite.Status.DECLINED);
            return null;
        });
    }

    /**
     * Cancels an open invitation: the OWNER any of the profile's, a MANAGER only those it issued. Fires
     * {@code CoopInviteEvent} (CANCELLED).
     */
    public CompletableFuture<Void> cancelInvite(final UUID actor, final UUID inviteId) {
        return coop(() -> {
            final ProfileInvite invite = this.repository.invite(inviteId).orElseThrow(() -> error(ProfileErrorCode.INVITE_NOT_FOUND,
                "no invite " + inviteId));
            final AuroraProfile profile = coopProfile(invite.profileId(), true);
            final ProfileMember row = profile.member(actor);
            if (row == null) throw error(ProfileErrorCode.NOT_A_MEMBER, actor + " is not a member of " + profile.id());
            if (row.role() == ProfileRole.MANAGER && !invite.inviter().equals(actor)) {
                throw error(ProfileErrorCode.ROLE_FORBIDDEN, "a MANAGER cancels only the invitations it issued; invite " + inviteId
                    + " was issued by " + invite.inviter() + ". Nothing was written");
            }
            closeInvite(invite, actor, ProfileInvite.Status.REVOKED);
            return null;
        });
    }

    /** One page of the open invitations addressed to {@code player}, oldest first; expired ones are left out. */
    public CompletableFuture<dev.iyanz.sourbycraft.api.profile.CoopPage<dev.iyanz.sourbycraft.api.profile.CoopInviteInfo>> invitesFor(
        final UUID player, final String cursor) {
        return coop(() -> {
            final CoopMembership.Page<ProfileInvite> page = CoopMembership.invitesByInvitee(this.repository, player,
                listingCursor(cursor, ProfileIndexPage.Kind.INVITES_BY_INVITEE, player));
            final long now = this.repository.storeClockMillis();
            final List<dev.iyanz.sourbycraft.api.profile.CoopInviteInfo> items = new ArrayList<>();
            for (final ProfileInvite invite : page.items()) {
                if (invite.open() && !invite.expired(now) && invite.invitee().equals(player)) items.add(inviteInfo(invite, now));
            }
            return new dev.iyanz.sourbycraft.api.profile.CoopPage<>(items, nextCursor(page));
        });
    }

    /**
     * One page of the open invitations of a co-op profile. {@code actor} must be its OWNER or a MANAGER;
     * {@code null} is the operator's administrative listing (no role check).
     */
    public CompletableFuture<dev.iyanz.sourbycraft.api.profile.CoopPage<dev.iyanz.sourbycraft.api.profile.CoopInviteInfo>> invitesOf(
        final UUID actor, final UUID profileId, final String cursor) {
        return coop(() -> {
            final AuroraProfile profile = coopProfile(profileId, true);
            if (actor != null) {
                final ProfileMember row = profile.member(actor);
                if (row == null) throw error(ProfileErrorCode.NOT_A_MEMBER, actor + " is not a member of " + profileId);
                if (!row.role().allows(ProfileRole.Permission.INVITE)) {
                    throw error(ProfileErrorCode.ROLE_FORBIDDEN, actor + " is " + row.role() + " of " + profileId + "; the owner and"
                        + " managers list its invitations");
                }
            }
            final CoopMembership.Page<ProfileInvite> page = CoopMembership.invitesByProfile(this.repository, profileId,
                listingCursor(cursor, ProfileIndexPage.Kind.INVITES_BY_PROFILE, profileId));
            final long now = this.repository.storeClockMillis();
            final List<dev.iyanz.sourbycraft.api.profile.CoopInviteInfo> items = new ArrayList<>();
            for (final ProfileInvite invite : page.items()) {
                if (invite.open() && !invite.expired(now) && invite.profileId().equals(profileId)) items.add(inviteInfo(invite, now));
            }
            return new dev.iyanz.sourbycraft.api.profile.CoopPage<>(items, nextCursor(page));
        });
    }

    /**
     * The player leaves a co-op profile: its row is dropped, its epoch consumed, an UNAVAILABLE salvage record written
     * and the account loses access. Refused for the OWNER ({@code OWNER_ACTION_REFUSED}) and only while this profile is
     * the player's <em>active</em> profile ({@code MEMBER_ONLINE}, Build 53): a player online in another profile leaves.
     * Fires {@code CoopMemberLeaveEvent} (LEFT).
     */
    public CompletableFuture<Void> leave(final UUID player, final UUID profileId) {
        return coop(() -> {
            final AuroraProfile profile = coopProfile(profileId, true);
            final ProfileMember row = profile.member(player);
            if (row == null) throw error(ProfileErrorCode.NOT_A_MEMBER, player + " is not a member of " + profileId + ". Nothing was written");
            if (row.role() == ProfileRole.OWNER) {
                throw error(ProfileErrorCode.OWNER_ACTION_REFUSED, "the OWNER cannot leave " + profileId + "; owner transfer is not"
                    + " available, and a sole owner deletes the profile instead. Nothing was written");
            }
            requireProfileNode(profileId);
            requireProfileReady(profile);
            final PlayerAccount account = loadReadyAccount(player);
            return evacuationFor(account, profile);
        }).thenCompose(evacuation -> evacuate(evacuation).thenCompose(moved -> this.<Void>coop(() -> {
            final AuroraProfile profile = coopProfile(profileId, true);
            final ProfileMember row = profile.member(player);
            if (row == null) throw error(ProfileErrorCode.NOT_A_MEMBER, player + " is not a member of " + profileId + ". Nothing was written");
            requireProfileNode(profileId);
            requireProfileReady(profile);
            final PlayerAccount account = loadReadyAccount(player);
            final CoopMembership.NextActive next = evacuation.nextFor(account, profileId, this);
            final MemberStateHead head = this.repository.memberHead(profileId, player, row.epoch()).orElse(null);
            final long storeNow = this.repository.storeClockMillis();
            // The member's open invitations are revoked and the profile's undecided votes cancelled in the same intent;
            // the expired-invite cleanup on the way reserved M, so the profile is read again.
            final List<ProfileInvite> issued = openInvites(ProfileIndexPage.Kind.INVITES_BY_PROFILE, profileId, player, storeNow);
            final AuroraProfile current = readyAgain(profileId, profile);
            runCoopPlan(CoopMembership.leave(operation(CoopOperation.Kind.MEMBER_REMOVE, player, profileId, row.epoch(),
                current.revision()), current, account, head, issued, undecidedVotes(current), next, storeNow, storeNow + INTENT_DEADLINE_MILLIS));
            fireCoopEvent(new dev.iyanz.sourbycraft.api.profile.CoopMemberLeaveEvent(profileId, player,
                dev.iyanz.sourbycraft.api.profile.CoopMemberLeaveEvent.Reason.LEFT, null));
            if (evacuation.mode() != Evacuation.Mode.NONE) {
                notify(player, "You left co-op profile " + current.shownLabel() + "; you now play profile "
                    + evacuation.describeNext() + ". Your items in the co-op are recorded for salvage.");
            }
            return null;
        })).exceptionallyCompose(failed -> undoEvacuation(evacuation, failed)));
    }

    // ------------------------------------------------------------------ removing an active member (Build 53)

    /**
     * How a member whose <em>active</em> profile is the co-op being left is moved out before the removal commits, so
     * no item exists twice (in the co-op's salvage source and on the player) or is lost:
     * <ul>
     *   <li>{@link Mode#NONE}: the co-op is not the member's active profile; the Build 52 removal runs as it is;</li>
     *   <li>{@link Mode#SWITCH_OUT}: the member is online on this node with an admitted session: a regular switch
     *       ({@link #switchProfile}) captures their co-op state into their member stream (the gated capture ticket)
     *       and applies the next profile's committed state; only a committed switch lets the removal run, a refused
     *       one fails the removal with {@code MEMBER_ONLINE} and the member stays;</li>
     *   <li>{@link Mode#REPOINT}: the member is offline (no session anywhere; their quit captured their state): the
     *       removal intent itself moves the account's active pointer to the next profile, so their next
     *       authoritative login restores that profile's committed head and the co-op items never come back onto
     *       them. Needs {@code login.authoritative}; refused {@code MEMBER_ONLINE} without it, and when the member's
     *       session is live on another node (the affected {@code Q} is held there).</li>
     * </ul>
     * The next profile is the first other ACTIVE, usable profile the account has access to, in account order; without
     * one a fresh solo profile is created for the member first ({@link #createForEvacuation}), taking the slot the
     * co-op membership is about to free.
     */
    private record Evacuation(Mode mode, UUID member, UUID profileId, UUID nextProfile, UUID nextEpoch, String nextName,
                              boolean createdFresh) {

        enum Mode { NONE, SWITCH_OUT, REPOINT }

        static final Evacuation NONE_NEEDED = new Evacuation(Mode.NONE, null, null, null, null, null, false);

        String describeNext() {
            return this.nextName == null ? String.valueOf(this.nextProfile) : this.nextName;
        }

        /** The removal plan's next pointer: only a REPOINT names one; a switched-out member's account already moved. */
        CoopMembership.NextActive nextFor(final PlayerAccount account, final UUID leaving, final ProfileEngine engine) throws IOException {
            if (this.mode == Mode.SWITCH_OUT && leaving.equals(account.activeProfile())) {
                throw error(ProfileErrorCode.MEMBER_ONLINE, this.member + " switched back into " + leaving + " before the removal"
                    + " committed; nothing was written. Retry");
            }
            if (this.mode != Mode.REPOINT) return null;
            final AuroraProfile next = engine.repository.profile(this.nextProfile)
                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "the next profile " + this.nextProfile + " disappeared"));
            final MemberStateHead head = engine.repository.memberHead(next.id(), this.member, this.nextEpoch).orElse(null);
            return new CoopMembership.NextActive(next, this.nextEpoch, head);
        }
    }

    /**
     * Decides how {@code account}'s member is moved out of {@code profile} when it is their active profile, and
     * creates the fresh solo profile when they have no other. Runs inside an I/O step; see {@link Evacuation}.
     */
    private Evacuation evacuationFor(final PlayerAccount account, final AuroraProfile profile) throws IOException {
        final UUID member = account.playerId();
        if (!profile.id().equals(account.activeProfile())) return Evacuation.NONE_NEEDED;
        final boolean online = this.players.online(member);
        final PlayerSession session = this.sessions.get(member);
        final Evacuation.Mode mode;
        if (online && session != null) {
            if (!this.settings.switching().enabled()) {
                throw error(ProfileErrorCode.MEMBER_ONLINE, member + " plays " + profile.id() + " on this node and switching is disabled ("
                    + ProfileSettings.SWITCHING_ENABLED_KEY + "), so they cannot be switched out first. Nothing was written");
            }
            mode = Evacuation.Mode.SWITCH_OUT;
        } else if (online) {
            throw error(ProfileErrorCode.MEMBER_ONLINE, member + " is online on this node but their profile session is not admitted"
                + " (joining, or admission failed); retry in a moment. Nothing was written");
        } else {
            if (this.lostSessions.contains(member)) {
                throw error(ProfileErrorCode.MEMBER_ONLINE, member + "'s session claim was lost on this node and is not released yet;"
                    + " retry once they quit. Nothing was written");
            }
            if (!this.settings.authoritativeLogin()) {
                throw error(ProfileErrorCode.MEMBER_ONLINE, member + " is offline with " + profile.id() + " active; while authoritative"
                    + " login does not apply (" + ProfileSettings.LOGIN_AUTHORITATIVE_KEY + " " + this.settings.login().authoritative().id()
                    + ") their next join would load the co-op items from vanilla playerdata beside the salvage record, so the removal"
                    + " is refused. Nothing was written");
            }
            mode = Evacuation.Mode.REPOINT;
        }
        // The next profile: another ACTIVE, usable one the member has a membership in (a solo profile first; a co-op only
        // when no other node holds it, else the member could not play it here, review P3-15), else a fresh solo profile.
        AuroraProfile coopFallback = null;
        for (final UUID candidate : account.profileIds()) {
            if (candidate.equals(profile.id())) continue;
            final AuroraProfile other = this.repository.profile(candidate).orElse(null);
            if (other == null || other.status() != ProfileStatus.ACTIVE || !other.usable()) continue;
            final ProfileMember row = other.member(member);
            if (row == null) continue;
            if (other.coop()) {
                if (coopFallback == null && this.ownership.foreignHolder(other.id()).join() == null) coopFallback = other;
                continue;
            }
            return new Evacuation(mode, member, profile.id(), other.id(), row.epoch(), other.shownLabel(), false);
        }
        if (coopFallback != null) {
            return new Evacuation(mode, member, profile.id(), coopFallback.id(), coopFallback.member(member).epoch(),
                coopFallback.shownLabel(), false);
        }
        final AuroraProfile created;
        try {
            created = createForEvacuation(account, profile.id());
        } catch (final ProfileOperationException busy) {
            if (busy.code() != ProfileErrorCode.OWNERSHIP_CONFLICT) throw busy;
            throw new ProfileOperationException(ProfileErrorCode.MEMBER_ONLINE, member + " has a live session on another node, so no"
                + " fresh profile could be created for them: " + busy.getMessage(), busy);
        }
        final ProfileMember owner = created.member(member);
        if (owner == null) throw error(ProfileErrorCode.INTERNAL_ERROR, "the fresh profile " + created.id() + " has no owner row");
        return new Evacuation(mode, member, profile.id(), created.id(), owner.epoch(), created.shownLabel(), true);
    }

    /** Runs the {@link Evacuation}: a {@code SWITCH_OUT} switches the member; the other modes do nothing here. */
    private CompletableFuture<Evacuation> evacuate(final Evacuation evacuation) {
        if (evacuation.mode() != Evacuation.Mode.SWITCH_OUT) return CompletableFuture.completedFuture(evacuation);
        return switchProfile(evacuation.member(), evacuation.nextProfile()).thenApply(result -> {
            if (!result.success()) {
                throw error(ProfileErrorCode.MEMBER_ONLINE, evacuation.member() + " plays " + evacuation.profileId() + " on this node and"
                    + " could not be switched out to " + evacuation.describeNext() + " first (" + result.errorCode() + ": " + result.message()
                    + "); the membership was not removed");
            }
            return evacuation;
        });
    }

    /**
     * Undoes what a failed removal left behind (review E3): the fresh solo profile {@link #createForEvacuation} made for
     * the member is deleted again when nothing was switched into it (the account still names the co-op active); when it
     * cannot be deleted, or the member already switched into it, the failure names the profile that remains. The original
     * failure is rethrown with its code.
     */
    private <T> CompletableFuture<T> undoEvacuation(final Evacuation evacuation, final Throwable failed) {
        final ProfileOperationException cause = unwrap(failed);
        if (evacuation == null || !evacuation.createdFresh() || evacuation.nextProfile() == null) {
            return CompletableFuture.failedFuture(cause);
        }
        return io(() -> {
            final PlayerAccount account = this.repository.loadAccount(evacuation.member(), this.settings.defaultSlots());
            return !evacuation.nextProfile().equals(account.activeProfile());
        }).thenCompose(deletable -> deletable ? delete(evacuation.nextProfile()).thenApply(deleted -> deleted ? "" : null)
            : CompletableFuture.completedFuture((String) null))
            .handle((removed, deleteFailed) -> {
                final String note;
                if (deleteFailed == null && "".equals(removed)) {
                    note = " The fresh solo profile " + evacuation.describeNext() + " created for the member was deleted again; nothing"
                        + " else was written";
                } else {
                    note = " The fresh solo profile " + evacuation.describeNext() + " (" + evacuation.nextProfile() + ") created for the"
                        + " member remains in their account" + (deleteFailed == null ? "" : " (deleting it failed: " + messageOf(deleteFailed) + ")")
                        + "; delete it or keep it, the membership was not removed";
                }
                throw new CompletionException(new ProfileOperationException(cause.code(), cause.getMessage() + "." + note, cause));
            });
    }

    /**
     * Creates the fresh solo profile a member is moved to when they have no other ({@link Evacuation}): the default
     * type, a generated name, under the member's {@code Q}. The slot rule counts the co-op membership being removed
     * as free (it is consumed by the same operation's next step); every other rule of {@link #createDetailed} holds.
     * Runs inside an I/O step.
     */
    private AuroraProfile createForEvacuation(final PlayerAccount account, final UUID vacating) throws IOException {
        final UUID member = account.playerId();
        if (!format2()) throw error(ProfileErrorCode.MIGRATION_REQUIRED, "a fresh profile needs a format-2 store");
        final dev.iyanz.sourbycraft.api.profile.ProfileType type = this.settings.types().defaultType();
        // The co-op slot is not counted as free (review P3-15): the fresh profile exists before the removal commits, and a
        // removal that fails must not leave the account over its limit.
        final int occupied = account.profileIds().size();
        if (occupied >= account.slotLimit() || occupied >= this.settings.maxSlots()) {
            throw error(ProfileErrorCode.SLOT_LIMIT, member + " has no other profile to move to and no free slot for a fresh one ("
                + occupied + " of " + account.slotLimit() + " used, the co-op being left included). Nothing was written");
        }
        final ProfileDetails created = createFormat2(new ProfileRequest(member, "solo", Map.of(), type, null), type, account);
        return this.repository.profile(created.info().id())
            .orElseThrow(() -> readBack("profile", created.info().id()));
    }

    /**
     * Opens a kick vote against {@code target} (OWNER or MANAGER), with the starter's approval counted. Undecided votes
     * of the profile that expired or whose electorate changed are closed first. When the starter is the whole
     * electorate (two-member co-op, target inactive long enough) the removal runs in the same call; a removal that
     * fails leaves the PASSED vote recorded and is retried with {@link #voteKick} ({@code approve = true}).
     */
    public CompletableFuture<dev.iyanz.sourbycraft.api.profile.CoopKickVoteInfo> startKickVote(final UUID actor, final UUID profileId,
                                                                                            final UUID target) {
        return coop(() -> {
            final AuroraProfile first = coopProfile(profileId, true);
            requireProfileNode(profileId);
            requireProfileReady(first);
            final PlayerAccount account = loadReadyAccount(actor);
            final long storeNow = this.repository.storeClockMillis();
            final List<KickVote> undecided = undecidedVotes(first, actor, storeNow);
            // Each cleanup close reserved and released M: the profile is read again before the plan is built.
            final AuroraProfile profile = readyAgain(profileId, first);
            final long openedAt = this.repository.storeClockMillis();
            final CoopIntentPlan plan = CoopMembership.createVote(operation(CoopOperation.Kind.VOTE_CREATE, actor, profileId, target,
                profile.revision()), profile, account, target, undecided, coopPolicy(), openedAt, openedAt + INTENT_DEADLINE_MILLIS);
            runCoopPlan(plan);
            return this.repository.vote(plan.result().recordId()).orElseThrow(() -> readBack("vote", plan.result().recordId()));
        }).thenCompose(vote -> vote.status() == KickVote.Status.PASSED ? executeKick(actor, vote) : CompletableFuture.completedFuture(vote))
            .thenCompose(vote -> coop(() -> voteInfo(vote, this.repository.storeClockMillis())));
    }

    /**
     * Casts {@code voter}'s ballot: approve, or decline (closes the vote DECLINED). A vote that expired is closed and
     * refused ({@code VOTE_NOT_FOUND}); one whose electorate no longer matches the members is cancelled and refused
     * ({@code ELECTORATE_CHANGED}). The approval that completes the electorate runs the removal in the same call;
     * approving a PASSED vote retries its removal.
     */
    public CompletableFuture<dev.iyanz.sourbycraft.api.profile.CoopKickVoteInfo> voteKick(final UUID voter, final UUID voteId,
                                                                                       final boolean approve) {
        return coop(() -> {
            final KickVote vote = this.repository.vote(voteId).orElseThrow(() -> error(ProfileErrorCode.VOTE_NOT_FOUND, "no vote " + voteId));
            final AuroraProfile profile = coopProfile(vote.profileId(), true);
            if (profile.member(voter) == null) throw error(ProfileErrorCode.NOT_A_MEMBER, voter + " is not a member of " + profile.id());
            requireProfileNode(profile.id());
            requireProfileReady(profile);
            final PlayerAccount account = loadReadyAccount(voter);
            final long storeNow = this.repository.storeClockMillis();
            if (!vote.undecided()) throw error(ProfileErrorCode.VOTE_NOT_FOUND, "vote " + voteId + " is " + vote.status());
            if (vote.expired(storeNow)) {
                closeQuietly(vote, voter, KickVote.Status.EXPIRED, storeNow);
                throw error(ProfileErrorCode.VOTE_NOT_FOUND, "vote " + voteId + " expired at " + vote.expiresAtMillis() + " (store clock "
                    + storeNow + ") and was closed. Nothing else was written");
            }
            if (!vote.electorateCurrent(profile.members())) {
                closeQuietly(vote, voter, KickVote.Status.CANCELLED, storeNow);
                throw error(ProfileErrorCode.ELECTORATE_CHANGED, "the members of " + profile.id() + " changed since vote " + voteId
                    + " opened; the vote was cancelled. Start a new one");
            }
            if (vote.status() == KickVote.Status.PASSED) {
                if (!approve) {
                    throw error(ProfileErrorCode.INVALID_REQUEST, "vote " + voteId + " has passed; approve it to run the removal."
                        + " Nothing was written");
                }
                return vote;
            }
            if (approve && vote.approvals().containsKey(voter)) {
                throw error(ProfileErrorCode.INVALID_REQUEST, voter + " already approved vote " + voteId + ". Nothing was written");
            }
            runCoopPlan(CoopMembership.castVote(operation(CoopOperation.Kind.VOTE_CAST, voter, voteId, approve, vote.revision()),
                profile, vote, account, approve, storeNow, storeNow + INTENT_DEADLINE_MILLIS));
            return this.repository.vote(voteId).orElseThrow(() -> readBack("vote", voteId));
        }).thenCompose(after -> after.status() == KickVote.Status.PASSED ? executeKick(voter, after) : CompletableFuture.completedFuture(after))
            .thenCompose(after -> coop(() -> voteInfo(after, this.repository.storeClockMillis())));
    }

    /** The undecided (OPEN or PASSED, unexpired) kick vote of a co-op profile, if any; the operator's and the API's form. */
    public CompletableFuture<Optional<dev.iyanz.sourbycraft.api.profile.CoopKickVoteInfo>> openKickVote(final UUID profileId) {
        return openKickVote(null, profileId);
    }

    /** {@link #openKickVote(UUID)} for {@code viewer}, a member of the profile ({@code NOT_A_MEMBER} otherwise; {@code null} = operator). */
    public CompletableFuture<Optional<dev.iyanz.sourbycraft.api.profile.CoopKickVoteInfo>> openKickVote(final UUID viewer, final UUID profileId) {
        return coop(() -> {
            requireViewer(coopProfile(profileId, true), viewer);
            final long now = this.repository.storeClockMillis();
            for (final KickVote vote : CoopMembership.votesByProfile(this.repository, profileId, null).items()) {
                if (vote.profileId().equals(profileId) && vote.undecided() && !vote.expired(now)) return Optional.of(voteInfo(vote, now));
            }
            return Optional.empty();
        });
    }

    /** One page of the members of a co-op profile, owner first, in record order; the operator's and the API's form. */
    public CompletableFuture<dev.iyanz.sourbycraft.api.profile.CoopPage<dev.iyanz.sourbycraft.api.profile.CoopMemberInfo>> members(
        final UUID profileId, final String cursor) {
        return members(null, profileId, cursor);
    }

    /** {@link #members(UUID, String)} for {@code viewer}, a member of the profile ({@code NOT_A_MEMBER} otherwise; {@code null} = operator). */
    public CompletableFuture<dev.iyanz.sourbycraft.api.profile.CoopPage<dev.iyanz.sourbycraft.api.profile.CoopMemberInfo>> members(
        final UUID viewer, final UUID profileId, final String cursor) {
        return coop(() -> {
            final AuroraProfile profile = coopProfile(profileId, true);
            requireViewer(profile, viewer);
            final int from = memberCursor(cursor, profileId);
            final List<CoopMembership.MemberView> views = CoopMembership.members(this.repository, profile, from);
            final List<dev.iyanz.sourbycraft.api.profile.CoopMemberInfo> items = new ArrayList<>(views.size());
            for (final CoopMembership.MemberView view : views) items.add(memberInfo(view.member()));
            final int next = from + views.size();
            return new dev.iyanz.sourbycraft.api.profile.CoopPage<>(items,
                next < profile.members().size() && !views.isEmpty() ? MEMBER_CURSOR_PREFIX + profileId + ":" + next : null);
        });
    }

    // ---- co-op internals (every method below runs inside an I/O step)

    /** The co-op gate: MultiProfile and co-op enabled, then (on the lane) a format-2 store. */
    private <T> CompletableFuture<T> coop(final IoSupplier<T> work) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        if (!this.settings.coopEnabled()) {
            return failed(ProfileErrorCode.COOP_DISABLED, "co-op is disabled (" + ProfileSettings.COOP_ENABLED_KEY + ": false,"
                + " RESTART_REQUIRED). Nothing was written");
        }
        return io(() -> {
            if (!format2()) {
                throw error(ProfileErrorCode.MIGRATION_REQUIRED, "co-op needs a format-2 profile store and this one is format 1:"
                    + " /awf multiprofile migrate dry-run, then migration.mode: apply and restart. Nothing was written");
            }
            return work.get();
        });
    }

    private CoopMembership.Policy coopPolicy() {
        final ProfileSettings.Coop coop = this.settings.coop();
        return new CoopMembership.Policy(coop.maxMembers(), coop.inviteExpiryMillis(), coop.kickVoteExpiryMillis(), coop.inactiveMillis());
    }

    private AuroraProfile coopProfile(final UUID profileId, final boolean requireCoop) throws IOException {
        final AuroraProfile profile = this.repository.profile(profileId).filter(found -> found.status() != ProfileStatus.DELETED)
            .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + profileId));
        if (requireCoop && !profile.coop()) {
            throw error(ProfileErrorCode.NOT_COOP, "profile " + profileId + " is a solo profile; its owner converts it to co-op first");
        }
        return profile;
    }

    /**
     * The profile read again after a cleanup intent may have moved {@code M}: still co-op, not deleted, not reserved.
     * Returns {@code first} unchanged when its revision did not move.
     */
    private AuroraProfile readyAgain(final UUID profileId, final AuroraProfile first) throws IOException {
        final AuroraProfile current = coopProfile(profileId, true);
        if (current.revision() == first.revision()) return first;
        requireProfileReady(current);
        return current;
    }

    /**
     * A commit that succeeded but whose record does not read back: the outcome is not known to the caller, never
     * reported as a plain I/O failure (the record may well be installed).
     */
    private static ProfileOperationException readBack(final String what, final UUID id) {
        return error(ProfileErrorCode.UNKNOWN_OUTCOME, "the " + what + " " + id + " committed but could not be read back; the change may"
            + " be installed. Read the " + what + " or the members before retrying");
    }

    /** The undecided votes of a profile as stored (no cleanup); the plans that change the membership cancel them. */
    private List<KickVote> undecidedVotes(final AuroraProfile profile) throws IOException {
        final List<KickVote> undecided = new ArrayList<>();
        for (final KickVote vote : CoopMembership.votesByProfile(this.repository, profile.id(), null).items()) {
            if (vote.undecided() && vote.profileId().equals(profile.id())) undecided.add(vote);
        }
        return undecided;
    }

    private ProfileInvite inviteOf(final UUID invitee, final UUID inviteId) throws IOException {
        return this.repository.invite(inviteId).filter(found -> found.invitee().equals(invitee))
            .orElseThrow(() -> error(ProfileErrorCode.INVITE_NOT_FOUND, "no invite " + inviteId + " addressed to " + invitee));
    }

    /**
     * OD-1: a membership change runs on the node that holds the profile's bound world, which is the node holding the
     * profile's claim (the owner playing it); refused {@code WRONG_NODE} while another node holds it.
     */
    private void requireProfileNode(final UUID profileId) {
        final OwnerClaim foreign = this.ownership.foreignHolder(profileId).join();
        if (foreign != null) {
            throw error(ProfileErrorCode.WRONG_NODE, "profile " + profileId + " is held by node " + foreign.node() + " (its bound world"
                + " is played there); owner decision OD-1: a co-op profile is played and changed on that node only, or while nobody"
                + " plays it; there is no cross-node routing. Nothing was written");
        }
    }

    private static void requireProfileReady(final AuroraProfile profile) {
        if (profile.pendingIntent() != null) {
            throw error(ProfileErrorCode.RECOVERY_REQUIRED, "profile " + profile.id() + " is reserved by intent " + profile.pendingIntent()
                + "; it is resolved at the next start or the owner's next login. Nothing was written");
        }
    }

    private PlayerAccount loadReadyAccount(final UUID accountId) throws IOException {
        final PlayerAccount account = this.repository.loadAccount(accountId, this.settings.defaultSlots());
        requireAccountReady(account);
        return account;
    }

    private void requireAccountReady(final PlayerAccount account) {
        final UUID accountId = account.playerId();
        requireNoUnresolvedIntent(accountId);
        if (this.switches.containsKey(accountId)) {
            throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch is running for " + accountId + ". Nothing was written");
        }
        requireNoLoginGate(accountId);
        if (account.pendingIntent() != null) {
            throw error(ProfileErrorCode.RECOVERY_REQUIRED, "the account " + accountId + " is reserved by intent " + account.pendingIntent()
                + ". Nothing was written");
        }
    }

    /**
     * Runs a plan under its claims: the actor's {@code Q} (its session on this node, else a short claim), for a kick
     * the removed member's {@code Q} ({@link CoopIntentPlan#affectedAccount()}), then {@code P}; every claim taken here
     * is released afterwards. Accounts the plan rewrites are re-read before the claims go, so an admitted session's
     * capture ticket follows the commit.
     */
    private void runCoopPlan(final CoopIntentPlan plan) throws IOException {
        requireNoUnresolvedIntent(plan.op().actor());
        if (plan.affectedAccount() != null) requireNoUnresolvedIntent(plan.affectedAccount());
        final List<PlayerClaim> acquired = new ArrayList<>(2);
        ProfileClaim transaction = null;
        try {
            final PlayerClaim q;
            try {
                q = actorClaim(plan.op().actor(), acquired);
            } catch (final ProfileOperationException busy) {
                // A leave is a MEMBER_REMOVE whose removed member is the actor: CoopMembership.leave sets no affected
                // account (only a kick names one), so the actor's own claim is the member's claim.
                if (busy.code() != ProfileErrorCode.OWNERSHIP_CONFLICT || plan.op().kind() != CoopOperation.Kind.MEMBER_REMOVE
                    || plan.affectedAccount() != null) {
                    throw busy;
                }
                // A leave by a member whose session is live on another node: the API contract is MEMBER_ONLINE.
                throw new ProfileOperationException(ProfileErrorCode.MEMBER_ONLINE, "the leaving member " + plan.op().actor()
                    + " has a live session on another node: " + busy.getMessage() + ". Leave from that node or while offline", busy);
            }
            PlayerClaim affected = null;
            if (plan.affectedAccount() != null) {
                try {
                    affected = actorClaim(plan.affectedAccount(), acquired);
                } catch (final ProfileOperationException busy) {
                    if (busy.code() != ProfileErrorCode.OWNERSHIP_CONFLICT) throw busy;
                    throw new ProfileOperationException(ProfileErrorCode.MEMBER_ONLINE, "the member to remove, "
                        + plan.affectedAccount() + ", has a live session on another node: " + busy.getMessage() + ". Run the removal on"
                        + " that node or while the member is offline", busy);
                }
            }
            transaction = transactionClaim(plan.profileId());
            runPlan(plan, transaction, q, affected);
            for (final CoopIntentPlan.Image image : plan.images()) {
                if (image.recordType() == ProfileRecords.TYPE_ACCOUNT) refreshAccount(image.id());
            }
        } finally {
            if (transaction != null) releaseTransactionClaim(transaction);
            releasePlayerClaims(acquired);
        }
    }

    private void refreshAccount(final UUID accountId) {
        try {
            accountCommitted(this.repository.loadAccount(accountId, this.settings.defaultSlots()));
        } catch (final IOException | RuntimeException unreadable) {
            SourbyLogger.warn("AWF MultiProfile: the account " + accountId + " could not be re-read after a co-op commit ("
                + messageOf(unreadable) + "); its next save may be refused as stale and is then retried by the player");
        }
    }

    /**
     * Raises the store's feature level before the first co-op record is prepared (minimumBuild 52, schema 2), durably,
     * so no build that cannot read a co-op record opens the store afterwards; a failure refuses the operation.
     */
    private volatile boolean memberPlayStoreLevel;

    /**
     * Raises the store to minimum build {@link StoreFormat#MEMBER_PLAY_MINIMUM_BUILD} before the first non-owner member switch
     * journal is written (review P3-17), after {@link #requireCoopStoreLevel}; a failure refuses the switch.
     */
    private void requireMemberPlayStoreLevel() throws IOException {
        if (this.memberPlayStoreLevel) return;
        requireCoopStoreLevel();
        final StoreFormat.Marker raised = this.repository.requireStoreFeature(StoreFormat.COOP_SCHEMA, StoreFormat.MEMBER_PLAY_MINIMUM_BUILD,
            this.storeWriter);
        if (!raised.covers(StoreFormat.COOP_SCHEMA, StoreFormat.MEMBER_PLAY_MINIMUM_BUILD)) {
            throw error(ProfileErrorCode.STORE_FORMAT_REFUSED, "the store marker was not raised to Build " + StoreFormat.MEMBER_PLAY_MINIMUM_BUILD
                + " (" + describe(raised) + "); no member switch journal is written");
        }
        this.memberPlayStoreLevel = true;
        final StoreStatus status = this.storeStatus;
        if (status != null) {
            this.storeStatus = new StoreStatus(raised, status.gate(), status.reason(), status.migration(), status.unresolvedIntents());
        }
    }

    private void requireCoopStoreLevel() throws IOException {
        if (this.coopStoreLevel) return;
        final StoreFormat.Marker raised = this.repository.requireStoreFeature(StoreFormat.COOP_SCHEMA, StoreFormat.COOP_MINIMUM_BUILD,
            this.storeWriter);
        if (!raised.covers(StoreFormat.COOP_SCHEMA, StoreFormat.COOP_MINIMUM_BUILD)) {
            throw error(ProfileErrorCode.STORE_FORMAT_REFUSED, "the store marker was not raised to Build " + StoreFormat.COOP_MINIMUM_BUILD
                + " (" + describe(raised) + "); no co-op record is written");
        }
        this.coopStoreLevel = true;
        final StoreStatus status = this.storeStatus;
        if (status != null) {
            this.storeStatus = new StoreStatus(raised, status.gate(), status.reason(), status.migration(), status.unresolvedIntents());
        }
    }

    /** Closes {@code invite} as {@code next} by {@code actor} and fires the invite event. */
    private void closeInvite(final ProfileInvite invite, final UUID actor, final ProfileInvite.Status next) throws IOException {
        final AuroraProfile profile = coopProfile(invite.profileId(), true);
        if (!invite.open()) throw error(ProfileErrorCode.INVITE_NOT_FOUND, "invite " + invite.id() + " is " + invite.status());
        final PlayerAccount account = loadReadyAccount(actor);
        final long storeNow = this.repository.storeClockMillis();
        runCoopPlan(CoopMembership.closeInvite(operation(CoopOperation.Kind.INVITE_REVOKE, actor, invite.id(), next, invite.revision()),
            profile, invite, account, next, storeNow, storeNow + INTENT_DEADLINE_MILLIS));
        final ProfileInvite closed = this.repository.invite(invite.id()).orElseThrow(() -> readBack("invite", invite.id()));
        fireCoopEvent(new dev.iyanz.sourbycraft.api.profile.CoopInviteEvent(inviteInfo(closed, storeNow)));
    }

    /** A cleanup close (an expired invite): best effort, never an event; a failure is logged and retried by a later cleanup. */
    private void closeQuietly(final ProfileInvite invite, final UUID actor, final ProfileInvite.Status next, final long storeNow) {
        try {
            final AuroraProfile profile = this.repository.profile(invite.profileId()).orElse(null);
            if (profile == null) return;
            final PlayerAccount account = this.repository.loadAccount(actor, this.settings.defaultSlots());
            runCoopPlan(CoopMembership.closeInvite(operation(CoopOperation.Kind.INVITE_REVOKE, actor, invite.id(), next, invite.revision()),
                profile, invite, account, next, storeNow, storeNow + INTENT_DEADLINE_MILLIS));
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: closing invite " + invite.id() + " as " + next + " failed (" + messageOf(failed)
                + "); it stays indexed until a later cleanup");
        }
    }

    /** A cleanup close of a vote (expired, or its electorate changed): best effort, logged on failure. */
    private void closeQuietly(final KickVote vote, final UUID actor, final KickVote.Status next, final long storeNow) {
        try {
            final AuroraProfile profile = this.repository.profile(vote.profileId()).orElse(null);
            if (profile == null) return;
            final PlayerAccount account = this.repository.loadAccount(actor, this.settings.defaultSlots());
            runCoopPlan(CoopMembership.cancelVote(operation(CoopOperation.Kind.VOTE_CANCEL, actor, vote.id(), next, vote.revision()),
                profile, vote, account, next, storeNow, storeNow + INTENT_DEADLINE_MILLIS));
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: closing vote " + vote.id() + " as " + next + " failed (" + messageOf(failed)
                + "); it stays indexed until a later cleanup");
        }
    }

    /**
     * The open, unexpired invites of an index (a profile's or an invitee's), reading every page up to the §9 bound;
     * up to {@link #COOP_CLEANUP_LIMIT} expired ones still indexed are closed as EXPIRED by {@code actor} on the way.
     */
    private List<ProfileInvite> openInvites(final ProfileIndexPage.Kind kind, final UUID resource, final UUID actor, final long storeNow)
        throws IOException {
        final List<ProfileInvite> open = new ArrayList<>();
        final List<ProfileInvite> expired = new ArrayList<>();
        ProfileIndexPage.Cursor cursor = null;
        for (int pages = 0; pages < COOP_INVITE_PAGES; pages++) {
            final CoopMembership.Page<ProfileInvite> page = kind == ProfileIndexPage.Kind.INVITES_BY_PROFILE
                ? CoopMembership.invitesByProfile(this.repository, resource, cursor)
                : CoopMembership.invitesByInvitee(this.repository, resource, cursor);
            for (final ProfileInvite invite : page.items()) {
                if (!invite.open()) continue;
                if (invite.expired(storeNow)) {
                    expired.add(invite);
                } else {
                    open.add(invite);
                }
            }
            if (page.next() == null || page.items().size() < ProfileIndexPage.LISTING_PAGE) break;
            cursor = page.next();
        }
        for (int i = 0; i < expired.size() && i < COOP_CLEANUP_LIMIT; i++) {
            closeQuietly(expired.get(i), actor, ProfileInvite.Status.EXPIRED, storeNow);
        }
        return open;
    }

    /**
     * The undecided votes of a profile after closing those that expired (EXPIRED) or whose electorate no longer
     * matches the members (CANCELLED), by {@code actor}.
     */
    private List<KickVote> undecidedVotes(final AuroraProfile profile, final UUID actor, final long storeNow) throws IOException {
        final List<KickVote> undecided = new ArrayList<>();
        for (final KickVote vote : CoopMembership.votesByProfile(this.repository, profile.id(), null).items()) {
            if (!vote.undecided() || !vote.profileId().equals(profile.id())) continue;
            if (vote.expired(storeNow)) {
                closeQuietly(vote, actor, KickVote.Status.EXPIRED, storeNow);
            } else if (!vote.electorateCurrent(profile.members())) {
                closeQuietly(vote, actor, KickVote.Status.CANCELLED, storeNow);
            } else {
                undecided.add(vote);
            }
        }
        // A close that failed leaves its vote undecided in the store; the plan's index bound refuses then.
        return undecided;
    }

    /**
     * Runs the removal of a PASSED vote by {@code actor} (an eligible voter): one intent under {@code P}, the actor's
     * {@code Q} and the target's {@code Q}. Fires {@code CoopMemberLeaveEvent} (KICKED) after the commit. A failure keeps
     * the PASSED vote and says how to retry.
     */
    private CompletableFuture<KickVote> executeKick(final UUID actor, final KickVote passed) {
        final UUID profileId = passed.profileId();
        return coop(() -> {
            final AuroraProfile first = coopProfile(profileId, true);
            final KickVote vote = this.repository.vote(passed.id()).orElse(passed);
            final PlayerAccount targetAccount = this.repository.loadAccount(vote.target(), this.settings.defaultSlots());
            requireAccountReady(targetAccount);
            // A target playing the co-op here is captured and switched out first; an offline one is repointed (Build 53).
            return evacuationFor(targetAccount, first);
        }).thenCompose(evacuation -> evacuate(evacuation).thenCompose(moved -> this.<KickVote>coop(() -> {
            final AuroraProfile first = coopProfile(profileId, true);
            final KickVote vote = this.repository.vote(passed.id()).orElse(passed);
            final PlayerAccount targetAccount = this.repository.loadAccount(vote.target(), this.settings.defaultSlots());
            requireAccountReady(targetAccount);
            final CoopMembership.NextActive next = evacuation.nextFor(targetAccount, first.id(), this);
            final long storeNow = this.repository.storeClockMillis();
            // The target's open invitations are revoked in the same intent; the cleanup on the way may have moved M.
            final List<ProfileInvite> issued = openInvites(ProfileIndexPage.Kind.INVITES_BY_PROFILE, first.id(), actor, storeNow);
            final AuroraProfile profile = readyAgain(first.id(), first);
            final ProfileMember target = profile.member(vote.target());
            final MemberStateHead head = target == null ? null
                : this.repository.memberHead(profile.id(), target.playerId(), target.epoch()).orElse(null);
            runCoopPlan(CoopMembership.kick(operation(CoopOperation.Kind.MEMBER_REMOVE, actor, vote.id(), vote.target(),
                profile.revision()), profile, vote, targetAccount, head, issued, coopPolicy(), next, storeNow, storeNow + INTENT_DEADLINE_MILLIS));
            fireCoopEvent(new dev.iyanz.sourbycraft.api.profile.CoopMemberLeaveEvent(first.id(), vote.target(),
                dev.iyanz.sourbycraft.api.profile.CoopMemberLeaveEvent.Reason.KICKED, vote.id()));
            notify(vote.target(), "You were removed from co-op profile " + profile.shownLabel() + " by vote"
                + (evacuation.mode() == Evacuation.Mode.NONE ? "." : "; you now play profile " + evacuation.describeNext() + ".")
                + " Your items there are recorded for salvage.");
            return this.repository.vote(vote.id()).orElse(vote);
        })).exceptionallyCompose(failed -> undoEvacuation(evacuation, failed))).exceptionallyCompose(failed -> {
            final ProfileOperationException cause = unwrap(failed);
            final String outcome = cause.code() == ProfileErrorCode.UNKNOWN_OUTCOME ? "its outcome is unknown; list the members before"
                + " retrying" : "it was not committed; approve the vote again to retry";
            return CompletableFuture.failedFuture(new ProfileOperationException(cause.code(), "kick vote " + passed.id()
                + " passed and is recorded, but the removal of " + passed.target() + " did not complete (" + cause.getMessage() + "); "
                + outcome, cause));
        });
    }

    /** Hands a co-op event to the event thread; dropped with a warning when the engine is closed or the queue is full. */
    private void fireCoopEvent(final org.bukkit.event.Event event) {
        if (this.closed) return;
        try {
            coopEventLane().execute(() -> {
                try {
                    this.coopEventSink.accept(event);
                } catch (final RuntimeException failed) {
                    SourbyLogger.warn("AWF MultiProfile: a handler of " + event.getEventName() + " failed: " + failed.getMessage());
                }
            });
        } catch (final RejectedExecutionException full) {
            SourbyLogger.warn("AWF MultiProfile: " + event.getEventName() + " was dropped (the co-op event queue of "
                + COOP_EVENT_QUEUE + " is full or closed); the store is the source of truth");
        }
    }

    private java.util.concurrent.ThreadPoolExecutor coopEventLane() {
        java.util.concurrent.ThreadPoolExecutor lane = this.coopEventLane;
        if (lane != null) return lane;
        synchronized (this.coopEventLock) {
            if (this.coopEventLane == null) {
                final java.util.concurrent.ThreadPoolExecutor created = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30L,
                    TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(COOP_EVENT_QUEUE), task -> {
                        final Thread thread = new Thread(task, "SourbyCraft-AWF-coop-events");
                        thread.setDaemon(true);
                        return thread;
                    });
                created.allowCoreThreadTimeOut(true);
                this.coopEventLane = created;
            }
            return this.coopEventLane;
        }
    }

    private static ProfileIndexPage.Cursor listingCursor(final String text, final ProfileIndexPage.Kind kind, final UUID resource) {
        if (text == null) return null;
        final ProfileIndexPage.Cursor cursor;
        try {
            cursor = ProfileIndexPage.Cursor.decode(text);
        } catch (final IOException malformed) {
            throw error(ProfileErrorCode.INVALID_REQUEST, malformed.getMessage() + "; restart the listing with a null cursor");
        }
        if (cursor.kind() != kind || !cursor.resource().equals(resource)) {
            throw error(ProfileErrorCode.INVALID_REQUEST, "the cursor belongs to another listing; restart with a null cursor");
        }
        return cursor;
    }

    private static String nextCursor(final CoopMembership.Page<?> page) {
        return page.next() == null || page.items().size() < ProfileIndexPage.LISTING_PAGE ? null : page.next().encode();
    }

    private static int memberCursor(final String text, final UUID profileId) {
        if (text == null) return 0;
        final String prefix = MEMBER_CURSOR_PREFIX + profileId + ":";
        if (!text.startsWith(prefix)) {
            throw error(ProfileErrorCode.INVALID_REQUEST, "the cursor belongs to another listing; restart with a null cursor");
        }
        try {
            final int from = Integer.parseInt(text.substring(prefix.length()));
            if (from < 0 || from > AuroraProfile.MAX_MEMBERS) throw new NumberFormatException("out of range");
            return from;
        } catch (final NumberFormatException malformed) {
            throw error(ProfileErrorCode.INVALID_REQUEST, "malformed member cursor; restart the listing with a null cursor");
        }
    }

    private dev.iyanz.sourbycraft.api.profile.CoopDetails coopView(final AuroraProfile profile) {
        final List<dev.iyanz.sourbycraft.api.profile.CoopMemberInfo> members = new ArrayList<>(profile.members().size());
        for (final ProfileMember member : profile.members()) members.add(memberInfo(member));
        return new dev.iyanz.sourbycraft.api.profile.CoopDetails(profile.id(), profile.displayLabel() == null ? "" : profile.displayLabel(),
            members, this.settings.coop().maxMembers());
    }

    private static dev.iyanz.sourbycraft.api.profile.CoopRole role(final ProfileRole role) {
        return switch (role) {
            case OWNER -> dev.iyanz.sourbycraft.api.profile.CoopRole.OWNER;
            case MANAGER -> dev.iyanz.sourbycraft.api.profile.CoopRole.MANAGER;
            case MEMBER -> dev.iyanz.sourbycraft.api.profile.CoopRole.MEMBER;
        };
    }

    private static dev.iyanz.sourbycraft.api.profile.CoopMemberInfo memberInfo(final ProfileMember member) {
        return new dev.iyanz.sourbycraft.api.profile.CoopMemberInfo(member.playerId(), role(member.role()), member.joinedAtMillis(),
            member.lastSeenAtMillis());
    }

    private static dev.iyanz.sourbycraft.api.profile.CoopInviteInfo inviteInfo(final ProfileInvite invite, final long now) {
        final dev.iyanz.sourbycraft.api.profile.CoopInviteState state = switch (invite.status()) {
            case OPEN -> invite.expired(now) ? dev.iyanz.sourbycraft.api.profile.CoopInviteState.EXPIRED
                : dev.iyanz.sourbycraft.api.profile.CoopInviteState.OPEN;
            case ACCEPTED -> dev.iyanz.sourbycraft.api.profile.CoopInviteState.ACCEPTED;
            case DECLINED -> dev.iyanz.sourbycraft.api.profile.CoopInviteState.DECLINED;
            case REVOKED -> dev.iyanz.sourbycraft.api.profile.CoopInviteState.CANCELLED;
            case EXPIRED -> dev.iyanz.sourbycraft.api.profile.CoopInviteState.EXPIRED;
        };
        return new dev.iyanz.sourbycraft.api.profile.CoopInviteInfo(invite.id(), invite.profileId(), invite.inviter(), invite.invitee(),
            invite.createdAtMillis(), invite.expiresAtMillis(), state);
    }

    private static dev.iyanz.sourbycraft.api.profile.CoopKickVoteInfo voteInfo(final KickVote vote, final long now) {
        final boolean lapsed = vote.undecided() && vote.expired(now);
        final dev.iyanz.sourbycraft.api.profile.CoopKickVoteState state = lapsed ? dev.iyanz.sourbycraft.api.profile.CoopKickVoteState.EXPIRED
            : switch (vote.status()) {
                case OPEN -> dev.iyanz.sourbycraft.api.profile.CoopKickVoteState.OPEN;
                case PASSED -> dev.iyanz.sourbycraft.api.profile.CoopKickVoteState.PASSED;
                case EXECUTED -> dev.iyanz.sourbycraft.api.profile.CoopKickVoteState.EXECUTED;
                case DECLINED -> dev.iyanz.sourbycraft.api.profile.CoopKickVoteState.DECLINED;
                case CANCELLED -> dev.iyanz.sourbycraft.api.profile.CoopKickVoteState.CANCELLED;
                case EXPIRED -> dev.iyanz.sourbycraft.api.profile.CoopKickVoteState.EXPIRED;
            };
        return new dev.iyanz.sourbycraft.api.profile.CoopKickVoteInfo(vote.id(), vote.profileId(), vote.target(), vote.approvals().size(),
            vote.electorate().size(), vote.createdAtMillis(), vote.expiresAtMillis(), state);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Queues one repository/ownership step. Steps run one at a time ({@link ProfileIoQueue}), so a
     * step's read-modify-write and the switch admission checks never interleave with another step;
     * a step never waits on another step.
     */
    private <T> CompletableFuture<T> io(final IoSupplier<T> work) {
        this.pendingIo.incrementAndGet();
        final CompletableFuture<T> future = new CompletableFuture<>();
        final CompletableFuture<T> tracked = future.whenComplete((ignored, failed) -> this.pendingIo.decrementAndGet());
        final boolean queued = this.ioQueue.offer(new ProfileIoQueue.Step() {
            @Override
            public Runnable run() {
                try {
                    final T value = runIo(work);
                    return () -> future.complete(value);
                } catch (final Throwable failed) {
                    return () -> future.completeExceptionally(failed);
                }
            }

            @Override
            public void rejected(final RejectedExecutionException rejected) {
                // The storage lane is bounded and may be saturated or already shut down. The API says
                // these futures never throw, and a counter that never returns would block the drain.
                future.completeExceptionally(error(ProfileErrorCode.INTERNAL_ERROR,
                    "the profile I/O lane refused the task: " + rejected.getMessage()));
            }
        });
        if (!queued) {
            future.completeExceptionally(error(ProfileErrorCode.INTERNAL_ERROR, "the profile I/O queue is full ("
                + IO_QUEUE_CAPACITY + " steps waiting); the operation was refused"));
        }
        return tracked;
    }

    private <T> T runIo(final IoSupplier<T> work) {
        if (this.closed) throw error(ProfileErrorCode.INTERNAL_ERROR, "the profile engine is closed");
        try {
            return work.get();
        } catch (final ProfileOperationException expected) {
            throw expected;
        } catch (final IOException failed) {
            throw new ProfileOperationException(ProfileErrorCode.IO_FAILURE, failed.getMessage(), failed);
        } catch (final RuntimeException failed) {
            throw new ProfileOperationException(ProfileErrorCode.INTERNAL_ERROR, failed.getMessage(), failed);
        }
    }

    /**
     * Waits for queued profile I/O, up to {@code budgetMillis}. Returns {@code true} only when nothing
     * is pending; a timeout is reported, never treated as a completed save.
     */
    public boolean drain(final long budgetMillis) {
        final long deadline = System.nanoTime() + Math.max(1L, budgetMillis) * 1_000_000L;
        while (this.pendingIo.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return this.pendingIo.get() == 0;
    }

    /** Queued I/O operations, for diagnostics and the shutdown drain. */
    public int pendingIo() {
        return this.pendingIo.get();
    }

    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static <T> CompletableFuture<T> failed(final ProfileErrorCode code, final String message) {
        return CompletableFuture.failedFuture(error(code, message));
    }

    private static ProfileOperationException error(final ProfileErrorCode code, final String message) {
        return new ProfileOperationException(code, message);
    }

    private static Throwable unwrapCompletion(final Throwable failed) {
        Throwable current = failed;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }

    static ProfileOperationException unwrap(final Throwable failed) {
        final Throwable current = unwrapCompletion(failed);
        if (current instanceof ProfileOperationException operation) return operation;
        return new ProfileOperationException(ProfileErrorCode.INTERNAL_ERROR,
            current == null ? "unknown failure" : current.getMessage(), current);
    }

    static ProfileErrorCode codeOf(final Throwable failed) {
        return unwrap(failed).code();
    }

    static String messageOf(final Throwable failed) {
        final ProfileOperationException operation = unwrap(failed);
        return operation.getMessage() == null ? operation.code().name() : operation.getMessage();
    }

    /** Keep a specific, actionable cause; anything unknown means the state may be partial. */
    private static ProfileErrorCode switchFailureCode(final Throwable failed) {
        if (failed == null) return ProfileErrorCode.RECOVERY_REQUIRED;
        final ProfileErrorCode code = codeOf(failed);
        return switch (code) {
            case STALE_STATE, OWNERSHIP_CONFLICT, WORLD_UNAVAILABLE, PLAYER_OFFLINE, INVALID_REQUEST, STATE_TOO_LARGE,
                 PROFILE_NOT_FOUND, NOT_OWNED, PROFILE_LOCKED, SWITCHING_DISABLED, DISABLED, TIMEOUT,
                 COUNTER_EXHAUSTED, CLAIM_LOST, UNKNOWN_OUTCOME, STORE_FORMAT_REFUSED, MIGRATION_REQUIRED,
                 ADAPTER_REQUIRED_FAILED, ADAPTER_MISSING, NOT_A_MEMBER, WRONG_NODE, COOP_DISABLED, COOP_SWITCH_UNAVAILABLE,
                 SWITCH_IN_PROGRESS, RECOVERY_REQUIRED -> code;
            default -> ProfileErrorCode.RECOVERY_REQUIRED;
        };
    }

    private static String truncate(final String text) {
        if (text == null) return "";
        return text.length() <= 200 ? text : text.substring(0, 200);
    }

    private static ProfileSwitchResult failure(final ProfileErrorCode code, final String message,
                                               final ProfileInfo profile, final long began) {
        return ProfileSwitchResult.failure(code, message, profile, null, System.currentTimeMillis() - began);
    }

    private static ProfileSwitchResult failure(final ProfileErrorCode code, final String message,
                                               final ProfileInfo profile, final UUID transitionId, final long began) {
        return ProfileSwitchResult.failure(code, message, profile, transitionId, System.currentTimeMillis() - began);
    }
}
