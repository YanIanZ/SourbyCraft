package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileOwner;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import dev.iyanz.sourbycraft.api.profile.ProfileWorldRef;
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

    public ProfileEngine(final ProfileSettings settings, final ProfileRepository repository,
                         final ProfileOwnership ownership, final PlayerStatePort players,
                         final ProfileWorldPort worlds, final String nodeId, final Executor io,
                         final FabricMetrics metrics, final EventFabric events) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
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

    public String nodeId() {
        return this.nodeId;
    }

    public boolean switching() {
        return this.settings.enabled() && this.settings.switching().enabled();
    }

    /** Profiles of an account, in account order. */
    public CompletableFuture<List<ProfileInfo>> list(final UUID accountId) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            final PlayerAccount account = this.repository.loadAccount(accountId, this.settings.defaultSlots());
            final List<ProfileInfo> infos = new ArrayList<>();
            for (final AuroraProfile profile : this.repository.profiles(accountId)) {
                if (profile.status() == ProfileStatus.DELETED) continue;
                infos.add(info(profile, account.activeProfile()));
            }
            return List.copyOf(infos);
        });
    }

    public CompletableFuture<Optional<ProfileInfo>> get(final UUID profileId) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> describe(profileId));
    }

    public CompletableFuture<Optional<ProfileInfo>> active(final UUID accountId) {
        return list(accountId).thenApply(infos -> infos.stream().filter(ProfileInfo::active).findFirst());
    }

    /** Creates a profile. Slot limit and account revision are checked in one serialised I/O step. */
    public CompletableFuture<ProfileInfo> create(final ProfileRequest request) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            if (this.switches.containsKey(request.accountId())) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "account transition in progress");
            }
            final AuroraProfile profile;
            try {
                profile = new AuroraProfile(UUID.randomUUID(), request.accountId(), request.type(),
                    request.metadata(), System.currentTimeMillis(), 0L, ProfileStatus.ACTIVE);
            } catch (final IllegalArgumentException invalid) {
                // A bad type or metadata is the caller's mistake, not a server fault.
                throw error(ProfileErrorCode.INVALID_REQUEST, invalid.getMessage());
            }
            final PlayerAccount account = this.repository.loadAccount(request.accountId(), this.settings.defaultSlots());
            if (account.full() || account.profileIds().size() >= this.settings.maxSlots()) {
                throw error(ProfileErrorCode.SLOT_LIMIT, "the account already owns " + account.profileIds().size()
                    + " of " + account.slotLimit() + " profiles");
            }
            this.repository.saveProfile(profile, ProfileWriteGuard.of(null, ProfileWriteGuard.ABSENT));
            this.repository.saveAccount(account.withProfile(profile.id()),
                ProfileWriteGuard.of(null, account.revision()));
            // Published inside the step: a callback attached after the future completed would run the
            // blocking publish on the caller's thread, which may be a region thread.
            publish(FabricEventType.PROFILE_CREATED, Map.of("profile", profile.id().toString(),
                "account", profile.accountId().toString(), "type", profile.type()));
            return info(profile, null);
        });
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
            if (profile.status() != next) {
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
            this.repository.saveAccount(account.withSlotLimit(slots), ProfileWriteGuard.of(null, account.revision()));
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
            return begin(new SwitchStart(account, target.get()), targetId, began);
        }).thenCompose(result -> result)
            .exceptionally(failed -> failure(codeOf(failed), messageOf(failed), null, began));
    }

    private CompletableFuture<ProfileSwitchResult> begin(final SwitchStart start, final UUID targetId, final long began) {
        final UUID playerId = start.account().playerId();
        final long deadline = System.currentTimeMillis() + this.settings.switching().timeoutMillis();
        final ProfileTransition journal = ProfileTransition.started(playerId, start.account().activeProfile(),
            targetId, deadline, 0L);
        if (this.switches.putIfAbsent(playerId, journal.transitionId()) != null) {
            if (this.metrics != null) this.metrics.profileFailure();
            return CompletableFuture.completedFuture(failure(ProfileErrorCode.SWITCH_IN_PROGRESS,
                "another profile switch is already running for this player", null, began));
        }
        final UUID ownerToken = UUID.randomUUID();
        final SwitchRun run = new SwitchRun(journal, start.account(), start.target(), ownerToken, began);
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
        }
    }

    /** Captures and saves the current state of a profile whose owner is online on this node. */
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
            return new SaveStart(profile, account.revision(), writeAuthority(profile));
        }).thenCompose(start -> this.players.capture(start.profile().accountId(), this.settings.isolation())
            .thenCompose(state -> io(() -> {
                final AuroraProfile profile = start.profile();
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
                writeSnapshot(profile, state, start.claim());
                publish(FabricEventType.PROFILE_SAVE_COMPLETED, Map.of("profile", profileId.toString()));
                return null;
            })));
    }

    private record SaveStart(AuroraProfile profile, long accountRevision, OwnerClaim claim) {}

    /**
     * Writes a state the caller captured on the player's owning thread as a snapshot of the account's
     * active profile. The quit listener uses it: it captures inside {@code PlayerQuitEvent}, while the
     * player still exists. Completes {@code false} when nothing was written because the account has no
     * active profile or the engine was already closed when called (shutdown closes it before the server
     * removes players); an engine closing while the step waits fails it instead. Otherwise the checks
     * of {@link #save} apply: no running switch, no pending journal, and the session claim must still
     * be valid.
     */
    public CompletableFuture<Boolean> saveCaptured(final UUID accountId, final ProfileState state) {
        Objects.requireNonNull(state, "state");
        if (!this.settings.enabled() || this.closed) return CompletableFuture.completedFuture(false);
        return io(() -> {
            if (this.switches.containsKey(accountId)) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch is running for " + accountId
                    + "; its capture is the authoritative save");
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
        }
    }

    /** Cancels a pending {@link #releaseSessionAfterSwitch}: the player joined again before the switch ended. */
    public void keepSessionAfterSwitch(final UUID playerId) {
        this.releaseOnSwitchEnd.remove(playerId);
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
        final ProfileState.Location last = this.repository.latestSnapshot(profile)
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
        return this.frozen.contains(playerId);
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
                    this.repository.saveAccount(account.withActive(previous), ProfileWriteGuard.of(claim, account.revision()));
                }
                this.repository.deleteTransition(journal.transitionId(), journalGuard);
                return null;
            }))
            .thenApply(ignored -> failure(ProfileErrorCode.RECOVERED,
                "transition rolled back to the previous state", null, began));
    }

    /** The exact rollback point a journal names; a first switch names the archived pre-profile state. */
    private Optional<ProfileSnapshot> rollbackSnapshot(final ProfileTransition journal) throws IOException {
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
        this.ownership.close();
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

        private SwitchRun(final ProfileTransition journal, final PlayerAccount account, final AuroraProfile target,
                          final UUID ownerToken, final long began) {
            this.journal = new AtomicReference<>(journal);
            this.account = account;
            this.target = target;
            this.ownerToken = ownerToken;
            this.began = began;
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
                        final ProfileSnapshot snapshot = previous == null
                            ? archivePreProfileState(playerId(), state, this.claim.get())
                            : writeSnapshotFor(previous, state, held);
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

    private record SwitchStart(PlayerAccount account, AuroraProfile target) {}

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
        final Optional<AuroraProfile> profile = this.repository.profile(profileId);
        if (profile.isEmpty() || profile.get().status() == ProfileStatus.DELETED) return Optional.empty();
        final PlayerAccount account = this.repository.loadAccount(profile.get().accountId(),
            this.settings.defaultSlots());
        return Optional.of(info(profile.get(), account.activeProfile()));
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
                 PROFILE_NOT_FOUND, NOT_OWNED, PROFILE_LOCKED, SWITCHING_DISABLED, DISABLED, TIMEOUT -> code;
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
