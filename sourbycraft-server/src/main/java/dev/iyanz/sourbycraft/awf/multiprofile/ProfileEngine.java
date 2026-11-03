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
 * that plays the profile's worlds (owner decision OD-1). Co-op membership operations (invites, votes,
 * salvage) are not reachable from this engine.</p>
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
                if (actor == null) actor = claim;
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
            this.repository.resolveIntent(operationId, CoopWriteGuard.intent(op, transaction, actor, intent.revision(),
                intent.expectedAfter(), Set.of(operationId)));
            ProfileIntent after = this.repository.intent(operationId).orElse(intent);
            if (after.pending() && !after.committed() && after.phase() == ProfileIntent.Phase.PREPARED
                && (!this.ownership.distributed() || after.pastDeadline(this.repository.storeClockMillis()))) {
                // Never committed, and no other process can still be running it: abort clears its own
                // reservations only. A committed intent always rolls forward instead.
                this.repository.abortIntent(operationId, CoopWriteGuard.intent(op, transaction, actor, after.revision(),
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
                throw error(ProfileErrorCode.NOT_OWNED, "profile " + targetId + " belongs to another account");
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
        return io(() -> {
            final AuroraProfile profile = this.repository.profile(profileId)
                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + profileId));
            if (this.switches.containsKey(profile.accountId())) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch is running for "
                    + profile.accountId() + "; its capture is the authoritative save");
            }
            final PlayerAccount account = this.repository.loadAccount(profile.accountId(), this.settings.defaultSlots());
            if (!profile.id().equals(account.activeProfile()) || profile.status() != ProfileStatus.ACTIVE) {
                throw error(ProfileErrorCode.PROFILE_LOCKED, "only the active profile can be saved");
            }
            requireNoPendingJournal(profile.accountId());
            if (format2()) {
                requireNoUnresolvedIntent(profile.accountId());
                final PlayerSession session = this.sessions.get(profile.accountId());
                if (session == null) {
                    throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "the player has no admitted session on this node;"
                        + " the snapshot was not written");
                }
                requireLive(profile.accountId(), session);
                if (account.activeEpoch() == null || !profile.hasMember(profile.accountId(), account.activeEpoch())) {
                    throw error(ProfileErrorCode.STALE_STATE, "the account's membership epoch is not a current membership"
                        + " of " + profileId + "; the snapshot was not written");
                }
                return new SaveStart(profile, account.revision(), null, new CaptureTicket(profile.accountId(),
                    session.claim(), account.sessionGeneration(), account.revision(), profileId, account.activeEpoch()));
            }
            return new SaveStart(profile, account.revision(), writeAuthority(profile), null);
        }).thenCompose(start -> this.players.capture(start.profile().accountId(), this.settings.isolation())
            .thenCompose(state -> io(() -> {
                final AuroraProfile profile = start.profile();
                // The switch may have started while the player thread was capturing. Writing now
                // would store the target's state under the profile being left, so it is refused.
                if (this.switches.containsKey(profile.accountId())) {
                    throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch started while the state"
                        + " was captured; the snapshot was not written");
                }
                if (start.ticket() != null) {
                    final long began = System.nanoTime();
                    publishCaptured(start.ticket(), state);
                    if (this.metrics != null) this.metrics.snapshot(System.nanoTime() - began);
                    publish(FabricEventType.PROFILE_SAVE_COMPLETED, Map.of("profile", profileId.toString()));
                    return null;
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
                writeSnapshot(profile, state, start.claim());
                publish(FabricEventType.PROFILE_SAVE_COMPLETED, Map.of("profile", profileId.toString()));
                return null;
            })));
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
    private ProfileSnapshot publishCaptured(final CaptureTicket ticket, final ProfileState state) throws IOException {
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
        return publishMemberState(profile, accountId, ticket.epoch(), state, ticket.claim(), ticket.accountRevision());
    }

    /**
     * Publishes {@code state} as the next snapshot of the stream {@code (profile, member, epoch)} under
     * {@link CoopWriteGuard#memberSave}: {@code Q(member)}, expected {@code A}, {@code M} (compared, not advanced)
     * and the head's {@code R} ({@link CoopWriteGuard#ABSENT} initialises the stream). The state keeps the
     * adapter entries of the head's snapshot no adapter of this boot accounts for. Runs inside an I/O step.
     */
    private ProfileSnapshot publishMemberState(final AuroraProfile profile, final UUID member, final UUID epoch,
                                               final ProfileState state, final PlayerClaim q, final long expectedAccount)
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
        final ProfileState carried = withCarriedAdapters(state, () -> head.isPresent() ? exactHeadSnapshot(head.get())
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
        Objects.requireNonNull(state, "state");
        if (!this.settings.enabled() || this.closed) return CompletableFuture.completedFuture(false);
        return io(() -> {
            if (this.switches.containsKey(accountId)) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch is running for " + accountId
                    + "; its capture is the authoritative save");
            }
            if (format2()) {
                if (ticket == null || !ticket.playerId().equals(accountId)) {
                    throw error(ProfileErrorCode.CLAIM_LOST, "no capture ticket of an admitted session of " + accountId
                        + "; the captured state cannot be attributed and was not written");
                }
                if (ticket.profileId() == null) return false;
                requireNoPendingJournal(accountId);
                final long began = System.nanoTime();
                final ProfileSnapshot written = publishCaptured(ticket, state);
                if (this.metrics != null) this.metrics.snapshot(System.nanoTime() - began);
                publish(FabricEventType.PROFILE_SAVE_COMPLETED, Map.of("profile", written.profileId().toString()));
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
            writeSnapshot(profile, state, writeAuthority(profile));
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
        return io(() -> this.ownership.claim(profileId, this.nodeId, UUID.randomUUID()).join())
            .thenApply(claim -> {
                if (claim.granted() && this.switches.containsKey(playerId)) {
                    // A switch started while this join was claiming the old profile; the switch's
                    // adopted claim is authoritative. Release this one, but do not turn a normal
                    // join into a duplicate-session kick.
                    this.release(claim.claim());
                    return claim;
                }
                if (claim.granted()) this.sessionClaims.put(playerId, claim.claim());
                return claim;
            });
    }

    /** Releases the session claim of a player; {@code false} when none was recorded. */
    public CompletableFuture<Boolean> releaseSession(final UUID playerId) {
        final OwnerClaim claim = this.sessionClaims.remove(playerId);
        if (claim == null) return CompletableFuture.completedFuture(false);
        return release(claim);
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
            final ClaimResult claimed = this.ownership.claim(UUID.fromString(lapsed.id()), this.nodeId,
                UUID.randomUUID()).join();
            if (claimed.granted()) {
                if (this.sessionClaims.replace(playerId, lapsed, claimed.claim())) {
                    SourbyLogger.warn("AWF MultiProfile: the session claim of " + playerId + " on " + lapsed.id()
                        + " lapsed and was claimed again (fence " + lapsed.fence() + " -> " + claimed.claim().fence() + ")");
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
        return this.switches.containsKey(playerId);
    }

    /**
     * Whether the player is frozen: a running switch captured their state and has neither committed
     * nor rolled back. Cheap enough for an event handler on the player's thread.
     */
    public boolean frozen(final UUID playerId) {
        return this.frozen.contains(playerId) || this.lostSessions.contains(playerId);
    }

    /** Releases every claim on the calling thread; shutdown only, after the I/O drain. */
    void releaseSessionsNow() {
        RuntimeException firstFailure = null;
        for (final Map.Entry<UUID, OwnerClaim> entry : this.sessionClaims.entrySet()) {
            try {
                this.ownership.release(entry.getValue()).join();
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
            final ClaimResult claimed = this.ownership.claim(profileId, this.nodeId, UUID.randomUUID()).join();
            if (!claimed.granted()) {
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profileId + " is held by "
                    + (claimed.claim() == null ? "another node" : claimed.claim().node())
                    + "; recovery did not write");
            }
            if (comparable && recordedFence > 0L && claimed.claim().fence() < recordedFence) {
                this.ownership.release(claimed.claim()).join();
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "transition " + journal.transitionId()
                    + " was recorded under fence " + recordedFence + " on " + profileId + " but this claim is fence "
                    + claimed.claim().fence() + "; refusing to write with a stale fence");
            }
            return claimed.claim();
        });
    }

    /** Releases a claim {@link #claimForRecovery} took; the player's session claim it borrowed is kept. */
    private CompletableFuture<Void> releaseRecoveryClaim(final OwnerClaim claim, final UUID playerId) {
        if (claim == null || claim.equals(this.sessionClaims.get(playerId))) return CompletableFuture.completedFuture(null);
        return this.<Void>io(() -> {
            this.ownership.release(claim).join();
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
            return ProfileStateCodec.decode(snapshot.get().state());
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
        this.switches.clear();
        this.runs.clear();
        this.frozen.clear();
        this.sessionClaims.clear();
        this.releaseOnSwitchEnd.clear();
        this.worldUse.clear();
        this.sessions.clear();
        this.lostSessions.clear();
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
                final ClaimResult claimed = ProfileEngine.this.ownership
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
                final ClaimResult claimed = ProfileEngine.this.ownership
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
            return bounded(ProfileEngine.this.players.capture(playerId(), ProfileEngine.this.settings.isolation()),
                    "capturing the player's state")
                .thenCompose(state -> io(() -> {
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
                            snapshot = publishMemberState(source, playerId(), this.coop.fromEpoch(), state,
                                this.coop.claim(), this.coop.accountRevision());
                            this.fromHead = snapshot.revision();
                        } else {
                            snapshot = writeSnapshotFor(previous, state, held);
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
            }).thenCompose(state -> phase(TransitionPhase.APPLYING_TARGET).thenApply(written -> state))
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
                    ProfileEngine.this.ownership.release(held).join();
                    ProfileEngine.this.sessionClaims.remove(playerId(), held);
                    return null;
                }
                final OwnerClaim holder = ProfileEngine.this.ownership.holder(previous).join();
                if (holder != null && holder.node().equals(ProfileEngine.this.nodeId)) {
                    ProfileEngine.this.ownership.release(holder).join();
                    ProfileEngine.this.sessionClaims.remove(playerId(), holder);
                }
                return null;
            });
        }

        /** Releases the claim a failed switch took on the target; the player never committed to it. */
        private CompletableFuture<Void> releaseTargetClaim() {
            final OwnerClaim held = this.claim.getAndSet(null);
            if (held == null) return CompletableFuture.completedFuture(null);
            return ProfileEngine.this.<Void>io(() -> {
                ProfileEngine.this.ownership.release(held).join();
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

    private void writeSnapshot(final AuroraProfile profile, final ProfileState state, final OwnerClaim claim)
        throws IOException {
        final long began = System.nanoTime();
        writeSnapshotFor(profile.id(), state, claim);
        if (this.metrics != null) this.metrics.snapshot(System.nanoTime() - began);
    }

    /**
     * Publishes a snapshot at {@code revision + 1} and the profile record bumped to it. The guard
     * carries the revision read here, so the repository refuses the publish if another writer moved
     * the profile on in between. The state keeps the adapter entries of the profile's latest snapshot
     * that the capture does not account for ({@link #withCarriedAdapters}).
     */
    private ProfileSnapshot writeSnapshotFor(final UUID profileId, final ProfileState state, final OwnerClaim claim)
        throws IOException {
        final AuroraProfile profile = this.repository.profile(profileId)
            .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + profileId));
        final byte[] encoded = encode(profileId, withCarriedAdapters(profileId, state));
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
    private ProfileState withCarriedAdapters(final UUID profileId, final ProfileState state) {
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
        return previous.adapters().isEmpty() ? state : this.players.carryAdapters(state, previous);
    }

    /** {@link #withCarriedAdapters(UUID, ProfileState)} for a format-2 stream, from the head's exact snapshot. */
    private ProfileState withCarriedAdapters(final ProfileState state, final SnapshotRead source, final UUID owner) {
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
        return previous.adapters().isEmpty() ? state : this.players.carryAdapters(state, previous);
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
            this.repository.resolveIntent(id, intentGuard(op, p, q, stored));
            stored = this.repository.intent(id).orElse(stored);
        }
        if (!stored.pending()) {
            if (stored.committed()) return;
            throw error(ProfileErrorCode.IO_FAILURE, op.kind() + " " + id + " was aborted before it committed (" + stored.outcome()
                + "); nothing was applied");
        }
        final CommitResult committed;
        try {
            committed = this.repository.commitIntent(id, intentGuard(op, p, q, stored));
        } catch (final IOException | RuntimeException refused) {
            abortUncommitted(op, p, q, refused);
            throw refused;
        }
        if (!committed.unknown()) return;
        final ProfileIntent outcome = this.repository.intent(id).orElse(null);
        if (outcome != null && outcome.committed()) {
            acceptCommitted(op, p, q, outcome, committed.detail());
            return;
        }
        if (outcome != null && outcome.phase() == ProfileIntent.Phase.PREPARED) {
            // The abort races a commit request that may still be in flight: the intent read after the abort
            // attempt decides. Committed: it landed first and counts as applied. Aborted: nothing was applied.
            // Anything else: unknown, and the operation stays unresolved for recovery.
            final ProfileIntent decided = abortUncommitted(op, p, q, null);
            if (decided != null && decided.committed()) {
                acceptCommitted(op, p, q, decided, committed.detail() + "; a delayed commit landed before the abort");
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
    private void acceptCommitted(final CoopOperation op, final ProfileClaim p, final PlayerClaim q, final ProfileIntent outcome,
                                 final String detail) {
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
                final CommitResult finished = this.repository.resolveIntent(id, intentGuard(op, p, q, outcome));
                if (finished.unknown()) markUnresolved(outcome, finished.detail());
            } catch (final IOException | RuntimeException later) {
                markUnresolved(outcome, "finishing it failed: " + messageOf(later));
            }
        }
    }

    /** The guard of a commit, abort or resolve of {@code intent}: its revision, its post-reservation vector, its own reservation. */
    private static CoopWriteGuard intentGuard(final CoopOperation op, final ProfileClaim p, final PlayerClaim q, final ProfileIntent intent) {
        return CoopWriteGuard.intent(op, p, q, intent.revision(), intent.expectedAfter(), Set.of(op.operationId()));
    }

    /**
     * Aborts the operation's intent while it is still PREPARED (clearing only its own reservations); a committed
     * intent is left to roll forward and logged. Failures are logged: the intent then stays for recovery.
     */
    private ProfileIntent abortUncommitted(final CoopOperation op, final ProfileClaim p, final PlayerClaim q, final Throwable cause) {
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
            this.repository.abortIntent(id, intentGuard(op, p, q, intent));
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
                 COUNTER_EXHAUSTED, CLAIM_LOST, UNKNOWN_OUTCOME, STORE_FORMAT_REFUSED, MIGRATION_REQUIRED -> code;
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
