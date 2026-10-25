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
            final OwnerClaim holder = this.ownership.holder(profileId).join();
            if (holder != null && !holder.node().equals(this.nodeId)) {
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profileId + " is owned by " + holder.node());
            }
            final PlayerAccount account = this.repository.loadAccount(profile.accountId(), this.settings.defaultSlots());
            if (profileId.equals(account.activeProfile())) {
                throw error(ProfileErrorCode.PROFILE_LOCKED, "the active profile cannot be deleted; switch away first");
            }
            this.repository.saveProfile(profile.withStatus(ProfileStatus.DELETED),
                ProfileWriteGuard.of(null, profile.revision()));
            this.repository.saveAccount(account.withoutProfile(profileId), ProfileWriteGuard.of(null, account.revision()));
            this.repository.deleteProfileData(profileId, ProfileWriteGuard.UNGUARDED);
            return true;
        });
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
                this.repository.saveProfile(profile.withHold(next), ProfileWriteGuard.of(null, profile.revision()));
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
        return run.execute().whenComplete((result, failed) -> transitionEnded(playerId, journal.transitionId()));
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
        final OwnerClaim holder = this.ownership.holder(profile.id()).join();
        if (holder != null && !holder.node().equals(this.nodeId)) {
            throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profile.id() + " is owned by " + holder.node());
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
            this.repository.saveBinding(new ProfileWorldBinding(profile.id(), roles, request.template()),
                ProfileWriteGuard.UNGUARDED);
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

    /** Renews every live session claim; returns how many are still held. */
    public CompletableFuture<Integer> renewSessions() {
        if (this.sessionClaims.isEmpty()) return CompletableFuture.completedFuture(0);
        final List<Map.Entry<UUID, OwnerClaim>> claims = List.copyOf(this.sessionClaims.entrySet());
        return io(() -> {
            int renewed = 0;
            for (final Map.Entry<UUID, OwnerClaim> entry : claims) {
                try {
                    final OwnerClaim claim = entry.getValue();
                    if (this.ownership.renew(claim).join()) {
                        renewed++;
                    } else if (!this.switches.containsKey(entry.getKey())
                        && this.sessionClaims.remove(entry.getKey(), claim)) {
                        // While a switch or recovery runs, it may have superseded this claim; a switch
                        // installs its own claim when it ends, and one still invalid afterwards is
                        // dropped on a later heartbeat. Otherwise the claim lapsed or was taken over;
                        // kept, it would fail every save and repeat this warning on every heartbeat.
                        SourbyLogger.warn("AWF MultiProfile: the session claim of " + entry.getKey()
                            + " is no longer valid and was dropped; the player's next join claims the profile again");
                    }
                } catch (final RuntimeException failed) {
                    // One provider serves every claim; if it is unreachable, stop instead of paying
                    // one timeout per player on the heartbeat thread.
                    SourbyLogger.warn("AWF MultiProfile: session renewal failed for " + entry.getKey()
                        + "; the remaining claims are retried on the next heartbeat: " + messageOf(failed));
                    break;
                }
            }
            return renewed;
        });
    }

    /** Whether a switch is running for the player. */
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

    /** Records that a bound world was used, for idle unloading. */
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

    /** Unloads bound worlds that have been idle beyond the configured time; completes with how many unloaded. */
    public CompletableFuture<Integer> sweepIdleWorlds() {
        if (!this.settings.enabled() || this.settings.worlds().idleUnloadSeconds() <= 0) {
            return CompletableFuture.completedFuture(0);
        }
        final long idleNanos = this.settings.worlds().idleUnloadSeconds() * 1_000_000_000L;
        final long now = System.nanoTime();
        final List<String> idle = new ArrayList<>();
        this.worldUse.forEach((world, used) -> {
            if (now - used >= idleNanos && this.worlds.loaded(world)) idle.add(world);
        });
        if (idle.isEmpty()) return CompletableFuture.completedFuture(0);
        final AtomicInteger unloaded = new AtomicInteger();
        final AtomicInteger refused = new AtomicInteger();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (final String world : idle) {
            final long usedAt = this.worldUse.getOrDefault(world, 0L);
            chain = chain.thenCompose(ignored -> {
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
            // old snapshot would revert whatever happened since, so nothing is applied.
            return io(() -> {
                this.repository.deleteTransition(transitionId, ProfileWriteGuard.UNGUARDED);
                return null;
            }).thenApply(ignored -> failure(ProfileErrorCode.RECOVERED, "transition " + transitionId + " stopped at "
                + journal.phase() + ", before the target state was applied; journal removed, nothing changed",
                null, began));
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
            return outcome.handle((result, failed) -> releaseRecoveryClaim(claim).thenApply(released -> {
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
     */
    private CompletableFuture<OwnerClaim> claimForRecovery(final UUID profileId, final long recordedFence,
                                                           final ProfileTransition journal) {
        if (profileId == null || !this.settings.coordination().fencingEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        final boolean comparable = journal.incarnation() == null
            ? this.ownership.distributed() : journal.incarnation().equals(this.ownership.incarnation());
        return io(() -> {
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

    private CompletableFuture<Void> releaseRecoveryClaim(final OwnerClaim claim) {
        if (claim == null) return CompletableFuture.completedFuture(null);
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
            // After the commit point a failure is not a rollback: the account already names the target.
            chain = chain.thenCompose(ignored -> releasePrevious().handle((released, failed) -> {
                if (failed != null) {
                    SourbyLogger.warn("AWF MultiProfile: releasing the previous profile failed after commit: "
                        + messageOf(failed));
                }
                return (Void) null;
            }));
            chain = chain.thenCompose(ignored -> ProfileEngine.this.<Void>io(() -> {
                ProfileEngine.this.repository.deleteTransition(this.journal.get().transitionId(), journalGuard());
                return null;
            }).handle((deleted, failed) -> {
                if (failed != null) {
                    // The journal still says COMMITTING, so the next join completes it forward.
                    SourbyLogger.warn("AWF MultiProfile: could not remove a completed journal: " + messageOf(failed));
                }
                return (Void) null;
            }));
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
                    if (state.spawn() != null) ProfileEngine.this.touch(state.spawn().world());
                    this.targetApplyStarted.set(true);
                    return bounded(ProfileEngine.this.players.apply(playerId(), state,
                            ProfileEngine.this.settings.isolation(), this.abandoned::get), "applying the target state")
                        .thenCompose(ignored -> {
                            final ProfileState.Location destination = state.spawn() != null ? state.spawn()
                                : state.returnPoint();
                            if (destination == null) return CompletableFuture.completedFuture(null);
                            return phase(TransitionPhase.TELEPORTING).thenCompose(teleporting ->
                                bounded(ProfileEngine.this.players.teleport(playerId(), destination),
                                    "teleporting the player"));
                        });
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
                ProfileEngine.this.repository.saveAccount(current.withActive(this.target.id()),
                    ProfileWriteGuard.of(held, current.revision()));
                this.commitPoint.set(true);
                // The switch owns the target claim from here on; quit and shutdown must release
                // this one, not the claim the previous profile had at join time.
                if (held != null) ProfileEngine.this.sessionClaims.put(playerId(), held);
                return null;
            }));
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
     * the profile on in between.
     */
    private ProfileSnapshot writeSnapshotFor(final UUID profileId, final ProfileState state, final OwnerClaim claim)
        throws IOException {
        final AuroraProfile profile = this.repository.profile(profileId)
            .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + profileId));
        final byte[] encoded = encode(profileId, state);
        final ProfileSnapshot snapshot = new ProfileSnapshot(profileId, UUID.randomUUID(), ProfileState.SCHEMA,
            profile.revision() + 1, encoded, ProfileStateCodec.checksum(encoded), System.currentTimeMillis());
        this.repository.publishSnapshot(profile.nextRevision(), snapshot, SNAPSHOTS_KEPT,
            ProfileWriteGuard.of(claim, profile.revision()));
        return snapshot;
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

    /** Encodes a state; one too large to store is the request's problem, reported with its size. */
    private static byte[] encode(final UUID owner, final ProfileState state) {
        try {
            return ProfileStateCodec.encode(state);
        } catch (final ProfileStateCodec.ProfileStateFormatException tooLarge) {
            SourbyLogger.warn("AWF MultiProfile: the state of " + owner + " cannot be stored: " + tooLarge.getMessage());
            throw error(ProfileErrorCode.INVALID_REQUEST, "the profile state cannot be stored: " + tooLarge.getMessage());
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
            case STALE_STATE, OWNERSHIP_CONFLICT, WORLD_UNAVAILABLE, PLAYER_OFFLINE, INVALID_REQUEST,
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
