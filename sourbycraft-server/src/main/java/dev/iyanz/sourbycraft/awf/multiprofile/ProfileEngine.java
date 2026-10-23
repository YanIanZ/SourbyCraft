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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The AWF MultiProfile engine: durable profiles, fenced ownership, a recoverable switch protocol and
 * AWF world bindings.
 *
 * <p>Threading: every method returns immediately; all repository work runs on the supplied bounded
 * I/O executor, and player reads/writes are delegated to {@link PlayerStatePort}, which hops to the
 * owning entity thread. No future is joined here, so the engine is safe to call from a region thread
 * as long as the caller only attaches a callback.</p>
 *
 * <p>Recoverable, not atomic: the account's active-profile reference is the commit point. Before it
 * is written, a failure rolls the player back to the old snapshot; after it is written, a failure is
 * completed forward by {@link #recover}. A crash can therefore leave a journal entry, never two
 * authoritative profiles.</p>
 */
public final class ProfileEngine {

    private final ProfileSettings settings;
    private final ProfileRepository repository;
    private final ProfileOwnership ownership;
    private final PlayerStatePort players;
    private final ProfileWorldPort worlds;
    private final String nodeId;
    private final Executor io;
    private final FabricMetrics metrics;
    private final EventFabric events;
    private final ConcurrentHashMap<UUID, UUID> switches = new ConcurrentHashMap<>();
    /** The claim that keeps a player's active profile owned on this node, by player id. */
    private final ConcurrentHashMap<UUID, OwnerClaim> sessionClaims = new ConcurrentHashMap<>();
    /** Players who quit mid-switch; their adopted claim is released when the switch ends. */
    private final java.util.Set<UUID> releaseOnSwitchEnd = ConcurrentHashMap.newKeySet();
    /** Serialises read-modify-write of a profile revision; two writers must never share one. */
    private final Object revisionLock = new Object();
    private final ConcurrentHashMap<String, Long> worldUse = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger pendingIo = new java.util.concurrent.atomic.AtomicInteger();
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
        this.io = Objects.requireNonNull(io, "io");
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
        return io(() -> {
            final Optional<AuroraProfile> profile = this.repository.profile(profileId);
            if (profile.isEmpty() || profile.get().status() == ProfileStatus.DELETED) return Optional.empty();
            final PlayerAccount account = this.repository.loadAccount(profile.get().accountId(),
                this.settings.defaultSlots());
            return Optional.of(info(profile.get(), account.activeProfile()));
        });
    }

    public CompletableFuture<Optional<ProfileInfo>> active(final UUID accountId) {
        return list(accountId).thenApply(infos -> infos.stream().filter(ProfileInfo::active).findFirst());
    }

    /** Creates a profile. Slot limit and account revision are checked in one serialised I/O step. */
    public CompletableFuture<ProfileInfo> create(final ProfileRequest request) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            final PlayerAccount account = this.repository.loadAccount(request.accountId(), this.settings.defaultSlots());
            if (account.full() || account.profileIds().size() >= this.settings.maxSlots()) {
                throw error(ProfileErrorCode.SLOT_LIMIT, "the account already owns " + account.profileIds().size()
                    + " of " + account.slotLimit() + " profiles");
            }
            final AuroraProfile profile = new AuroraProfile(UUID.randomUUID(), request.accountId(), request.type(),
                request.metadata(), System.currentTimeMillis(), 0L, ProfileStatus.ACTIVE);
            this.repository.saveProfile(profile);
            this.repository.saveAccount(account.withProfile(profile.id()));
            return info(profile, null);
        }).thenApply(profile -> {
            publish(FabricEventType.PROFILE_CREATED, Map.of("profile", profile.id().toString(),
                "account", profile.accountId().toString(), "type", profile.type()));
            return profile;
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
            final OwnerClaim holder = this.ownership.holder(profileId).join();
            if (holder != null && !holder.node().equals(this.nodeId)) {
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profileId + " is owned by " + holder.node());
            }
            final PlayerAccount account = this.repository.loadAccount(profile.accountId(), this.settings.defaultSlots());
            if (profileId.equals(account.activeProfile())) {
                throw error(ProfileErrorCode.PROFILE_LOCKED, "the active profile cannot be deleted; switch away first");
            }
            this.repository.saveProfile(profile.withStatus(ProfileStatus.DELETED));
            this.repository.saveAccount(account.withoutProfile(profileId));
            this.repository.deleteProfileData(profileId);
            return true;
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
            final UUID running = this.switches.get(playerId);
            final Optional<ProfileTransition> pending = this.repository.transitions().stream()
                .filter(transition -> !transition.terminal() && transition.playerId().equals(playerId)
                    && !transition.transitionId().equals(running))
                .findFirst();
            if (pending.isPresent()) {
                throw error(ProfileErrorCode.RECOVERY_REQUIRED, "transition " + pending.get().transitionId()
                    + " is incomplete; run /awf multiprofile recover " + pending.get().transitionId());
            }
            return new SwitchStart(account, target.get());
        }).thenCompose(start -> begin(start, targetId, began))
            .exceptionally(failed -> failure(codeOf(failed), messageOf(failed), null, began));
    }

    private CompletableFuture<ProfileSwitchResult> begin(final SwitchStart start, final UUID targetId, final long began) {
        final long deadline = System.currentTimeMillis() + this.settings.switching().timeoutMillis();
        final ProfileTransition journal = ProfileTransition.started(start.account().playerId(),
            start.account().activeProfile(), targetId, deadline, 0L);
        if (this.switches.putIfAbsent(start.account().playerId(), journal.transitionId()) != null) {
            if (this.metrics != null) this.metrics.profileFailure();
            return CompletableFuture.completedFuture(failure(ProfileErrorCode.SWITCH_IN_PROGRESS,
                "another profile switch is already running for this player", null, began));
        }
        final UUID ownerToken = UUID.randomUUID();
        final SwitchRun run = new SwitchRun(journal, start.account(), start.target(), ownerToken, began);
        return run.execute().whenComplete((result, failed) -> {
            this.switches.remove(start.account().playerId(), journal.transitionId());
            if (this.releaseOnSwitchEnd.remove(start.account().playerId())) {
                // The player quit while this switch was running; the claim it adopted on commit
                // belongs to nobody now.
                this.releaseSession(start.account().playerId());
            }
        });
    }

    /** Saves the current state of a profile whose owner is online on this node. */
    public CompletableFuture<Void> save(final UUID profileId) {
        if (!this.settings.enabled()) return failed(ProfileErrorCode.DISABLED, "AWF MultiProfile is disabled");
        return io(() -> {
            final AuroraProfile profile = this.repository.profile(profileId)
                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + profileId));
            if (this.switches.containsKey(profile.accountId())) {
                throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch is running for "
                    + profile.accountId() + "; its capture is the authoritative save");
            }
            final OwnerClaim holder = this.ownership.holder(profileId).join();
            if (holder != null) {
                if (!holder.node().equals(this.nodeId)) {
                    throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profileId
                        + " is owned by " + holder.node());
                }
                if (this.settings.coordination().fencingEnabled()
                    && !this.ownership.validate(holder).join()) {
                    throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "the ownership claim on " + profileId
                        + " is no longer valid; the snapshot was not written");
                }
            } else if (this.ownership.distributed() && this.settings.coordination().fencingEnabled()) {
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profileId
                    + " has no ownership claim; distributed mode refuses to write without one");
            }
            return profile;
        }).thenCompose(profile -> this.players.capture(profile.accountId(), this.settings.isolation())
            .thenCompose(state -> io(() -> {
                // The switch may have started while the player thread was capturing. Writing now
                // would store the target's state under the profile being left, so it is refused.
                if (this.switches.containsKey(profile.accountId())) {
                    throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "a profile switch started while the state"
                        + " was captured; the snapshot was not written");
                }
                final PlayerAccount current = this.repository.loadAccount(profile.accountId(),
                    this.settings.defaultSlots());
                if (!profile.id().equals(current.activeProfile())) {
                    throw error(ProfileErrorCode.SWITCH_IN_PROGRESS, "the active profile changed while the state"
                        + " was captured; the snapshot was not written");
                }
                writeSnapshot(profile, state);
                return null;
            })))
            .thenApply(ignored -> {
                publish(FabricEventType.PROFILE_SAVE_COMPLETED, Map.of("profile", profileId.toString()));
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

    /**
     * Asks for the session claim to be released when the player's running switch ends. A quit in
     * the middle of a switch cannot release the target claim yet: the switch installs it on commit.
     */
    public void releaseSessionAfterSwitch(final UUID playerId) {
        this.releaseOnSwitchEnd.add(playerId);
    }

    /** Renews every live session claim; returns how many are still held. */
    public CompletableFuture<Integer> renewSessions() {
        if (this.sessionClaims.isEmpty()) return CompletableFuture.completedFuture(0);
        final List<Map.Entry<UUID, OwnerClaim>> claims = List.copyOf(this.sessionClaims.entrySet());
        return io(() -> {
            int renewed = 0;
            for (final Map.Entry<UUID, OwnerClaim> entry : claims) {
                try {
                    if (this.ownership.renew(entry.getValue()).join()) renewed++;
                    else SourbyLogger.warn("AWF MultiProfile: the session claim of " + entry.getKey()
                        + " is no longer valid; the next operation re-claims or refuses");
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

    /** Unloads bound worlds that have been idle beyond the configured time. */
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
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (final String world : idle) {
            final long usedAt = this.worldUse.getOrDefault(world, 0L);
            chain = chain.thenCompose(ignored -> {
                // A switch may have touched the world since the scan; a conditional remove keeps
                // that touch, so the world is not unloaded under the teleport and is swept later.
                if (!this.worldUse.remove(world, usedAt)) return CompletableFuture.completedFuture(null);
                return this.worlds.saveAndUnload(world).exceptionally(failed -> {
                    SourbyLogger.warn("AWF MultiProfile: idle unload of '" + world + "' failed: " + messageOf(failed));
                    return null;
                });
            });
        }
        return chain.thenApply(ignored -> idle.size());
    }

    /**
     * Finishes or rolls back an incomplete transition.
     *
     * <p>Roll forward when the account already committed the target; roll back otherwise. A
     * rollback needs the old snapshot and an online player. When that is impossible the journal is
     * marked {@code RECOVERY_REQUIRED} and stays for an operator.</p>
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
            final PlayerAccount account = this.repository.loadAccount(journal.playerId(), this.settings.defaultSlots());
            return new Recovery(journal, account);
        }).thenCompose(recovery -> {
            final ProfileTransition journal = recovery.journal();
            if (journal.terminal()) {
                if (journal.phase() == TransitionPhase.FAILED) {
                    // A failed journal is an operator note, not a lock: forget it on request.
                    return io(() -> {
                        this.repository.deleteTransition(transitionId);
                        return null;
                    }).thenApply(ignored -> failure(ProfileErrorCode.RECOVERED,
                        "failed journal " + transitionId + " removed; it recorded: " + journal.failure(), null, began));
                }
                return CompletableFuture.completedFuture(failure(ProfileErrorCode.INVALID_REQUEST,
                    "transition " + transitionId + " is already " + journal.phase(), null, began));
            }
            final boolean forward = journal.toProfile().equals(recovery.account().activeProfile());
            final UUID contact = forward ? journal.toProfile() : journal.fromProfile();
            return claimForRecovery(contact, journal).thenCompose(claim -> {
                final CompletableFuture<ProfileSwitchResult> outcome = forward
                    ? io(() -> {
                        this.repository.saveTransition(journal.at(TransitionPhase.ACTIVE));
                        this.repository.deleteTransition(transitionId);
                        return (ProfileInfo) null;
                    }).thenCompose(ignored -> get(journal.toProfile()))
                        .thenApply(profile -> ProfileSwitchResult.success(profile.orElse(null), transitionId,
                            System.currentTimeMillis() - began))
                    : rollbackTo(recovery, began, claim);
                return outcome.handleAsync((result, failed) -> {
                    if (claim != null) {
                        try {
                            this.ownership.release(claim).join();
                        } catch (final RuntimeException releaseFailed) {
                            SourbyLogger.warn("AWF MultiProfile: releasing a recovery claim failed: "
                                + releaseFailed.getMessage());
                        }
                    }
                    if (failed != null) {
                        throw failed instanceof CompletionException completion ? completion
                            : new CompletionException(failed);
                    }
                    return result;
                }, this.io);
            });
        }).exceptionally(failed -> {
            if (this.metrics != null) this.metrics.profileFailure();
            return failure(codeOf(failed), messageOf(failed), null, began);
        });
    }

    /**
     * Recovery writes are fenced like a switch: take the profile being made authoritative and refuse a
     * journal whose recorded fence is ahead of it (a takeover happened after the journal was written).
     */
    private CompletableFuture<OwnerClaim> claimForRecovery(final UUID profileId, final ProfileTransition journal) {
        if (profileId == null || !this.settings.coordination().fencingEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return io(() -> {
            final ClaimResult claimed = this.ownership.claim(profileId, this.nodeId, UUID.randomUUID()).join();
            if (!claimed.granted()) {
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + profileId + " is held by "
                    + (claimed.claim() == null ? "another node" : claimed.claim().node())
                    + "; recovery did not write");
            }
            if (journal.fence() > 0L && claimed.claim().fence() < journal.fence()) {
                this.ownership.release(claimed.claim()).join();
                throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "transition " + journal.transitionId()
                    + " was recorded under fence " + journal.fence() + " but this claim is fence "
                    + claimed.claim().fence() + "; refusing to write with a stale fence");
            }
            return claimed.claim();
        });
    }

    private CompletableFuture<ProfileSwitchResult> rollbackTo(final Recovery recovery, final long began,
                                                              final OwnerClaim claim) {
        final ProfileTransition journal = recovery.journal();
        if (journal.fromProfile() == null) {
            // A first join never applied anything; the journal is simply abandoned.
            return io(() -> {
                this.repository.saveTransition(journal.at(TransitionPhase.RECOVERING));
                this.repository.deleteTransition(journal.transitionId());
                return null;
            }).thenApply(ignored -> failure(ProfileErrorCode.RECOVERED,
                "transition cleared; no previous profile existed", null, began));
        }
        if (journal.fromSnapshotId() == null) {
            return io(() -> {
                this.repository.saveTransition(journal.at(TransitionPhase.RECOVERING));
                this.repository.saveTransition(journal.failed("no snapshot of the previous profile"));
                return null;
            }).thenApply(ignored -> failure(ProfileErrorCode.RECOVERY_REQUIRED,
                "the previous profile has no durable snapshot; refusing to guess", null, began));
        }
        if (!this.players.online(journal.playerId())) {
            // Leave the journal non-terminal: the next join retries the rollback.
            return io(() -> {
                this.repository.saveTransition(journal.at(TransitionPhase.RECOVERING));
                return null;
            }).thenApply(ignored -> failure(ProfileErrorCode.PLAYER_OFFLINE,
                "the player must be online to recover this transition; the journal stays pending", null, began));
        }
        return io(() -> {
            this.repository.saveTransition(journal.at(TransitionPhase.RECOVERING));
            return null;
        }).thenCompose(ignored -> previousState(journal.fromProfile()))
            .thenCompose(state -> this.players.apply(journal.playerId(), state, this.settings.isolation()))
            .thenCompose(ignored -> io(() -> {
                if (claim != null && this.settings.coordination().fencingEnabled()
                    && !this.ownership.validate(claim).join()) {
                    throw error(ProfileErrorCode.OWNERSHIP_CONFLICT,
                        "the recovery claim lapsed before the account was rewritten");
                }
                final PlayerAccount account = this.repository.loadAccount(journal.playerId(),
                    this.settings.defaultSlots());
                if (account.owns(journal.fromProfile())) {
                    this.repository.saveAccount(account.withActive(journal.fromProfile()));
                }
                this.repository.saveTransition(journal.failed("rolled back after an incomplete switch"));
                this.repository.deleteTransition(journal.transitionId());
                return null;
            }))
            .thenApply(ignored -> failure(ProfileErrorCode.RECOVERED,
                "transition rolled back to the previous profile", null, began));
    }

    /** Recovers every incomplete transition of one player; called on join. */
    public CompletableFuture<Integer> recoverPending(final UUID playerId) {
        if (!this.settings.enabled()) return CompletableFuture.completedFuture(0);
        return io(() -> this.repository.transitions().stream()
            .filter(transition -> !transition.terminal() && transition.playerId().equals(playerId)
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

    /** Ids of journals that are not terminal, for status output. */
    public CompletableFuture<List<ProfileTransition>> pendingTransitions() {
        if (!this.settings.enabled()) return CompletableFuture.completedFuture(List.of());
        return io(() -> this.repository.transitions().stream().filter(transition -> !transition.terminal()).toList());
    }

    public void close() {
        this.closed = true;
        this.switches.clear();
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
        private volatile Throwable failure;
        private volatile ProfileSnapshot previousSnapshot;
        private volatile ProfileState previousState;

        private SwitchRun(final ProfileTransition journal, final PlayerAccount account, final AuroraProfile target,
                          final UUID ownerToken, final long began) {
            this.journal = new AtomicReference<>(journal);
            this.account = account;
            this.target = target;
            this.ownerToken = ownerToken;
            this.began = began;
        }

        private CompletableFuture<ProfileSwitchResult> execute() {
            CompletableFuture<Void> chain = phase(TransitionPhase.VALIDATING);
            chain = chain.thenCompose(ignored -> {
                if (!ProfileEngine.this.players.online(this.account.playerId())) {
                    throw error(ProfileErrorCode.PLAYER_OFFLINE, "the player is not online");
                }
                return phase(TransitionPhase.FREEZING);
            });
            chain = chain.thenCompose(ignored -> claimPrevious());
            chain = chain.thenCompose(ignored -> capturePrevious());
            chain = chain.thenCompose(ignored -> io(() -> {
                final ClaimResult claimed = ProfileEngine.this.ownership
                    .claim(this.target.id(), ProfileEngine.this.nodeId, this.ownerToken).join();
                if (!claimed.granted()) {
                    throw error(ProfileErrorCode.OWNERSHIP_CONFLICT, "profile " + this.target.id()
                        + " is held by " + (claimed.claim() == null ? "another node" : claimed.claim().node()));
                }
                this.claim.set(claimed.claim());
                this.journal.set(this.journal.get().withFence(claimed.claim().fence()));
                return null;
            }));
            chain = chain.thenCompose(ignored -> phase(TransitionPhase.PREPARING_TARGET));
            chain = chain.thenCompose(ignored -> worldReady());
            chain = chain.thenCompose(ignored -> applyTarget());
            chain = chain.thenCompose(ignored -> phase(TransitionPhase.COMMITTING)
                .thenCompose(also -> io(() -> {
                    if (ProfileEngine.this.settings.coordination().fencingEnabled()
                        && this.claim.get() != null
                        && !ProfileEngine.this.ownership.validate(this.claim.get()).join()) {
                        throw error(ProfileErrorCode.OWNERSHIP_CONFLICT,
                            "the profile ownership fence is no longer valid; nothing was committed");
                    }
                    final PlayerAccount current = ProfileEngine.this.repository.loadAccount(this.account.playerId(),
                        ProfileEngine.this.settings.defaultSlots());
                    ProfileEngine.this.repository.saveAccount(current.withActive(this.target.id()));
                    this.commitPoint.set(true);
                    // The switch owns the target claim from here on; quit and shutdown must release
                    // this one, not the claim the previous profile had at join time.
                    if (this.claim.get() != null) {
                        ProfileEngine.this.sessionClaims.put(this.account.playerId(), this.claim.get());
                    }
                    ProfileEngine.this.repository.saveTransition(this.journal.get().at(TransitionPhase.ACTIVE));
                    return null;
                })));
            // After the commit point a failure is not a rollback: the account already names the target.
            chain = chain.thenCompose(ignored -> releasePrevious().handle((released, failed) -> {
                if (failed != null) {
                    SourbyLogger.warn("AWF MultiProfile: releasing the previous profile failed after commit: "
                        + messageOf(failed));
                }
                return (Void) null;
            }));
            chain = chain.thenCompose(ignored -> ProfileEngine.this.<Void>io(() -> {
                ProfileEngine.this.repository.deleteTransition(this.journal.get().transitionId());
                return null;
            }).handle((deleted, failed) -> {
                if (failed != null) {
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
                .thenCompose(ignored -> get(this.target.id()))
                .thenApply(found -> {
                    final ProfileTransition current = this.journal.get();
                    if (this.commitPoint.get()) {
                        publish(FabricEventType.PROFILE_SWITCH_COMPLETED, Map.of(
                            "player", this.account.playerId().toString(),
                            "profile", this.target.id().toString(),
                            "from", String.valueOf(current.fromProfile())));
                        return ProfileSwitchResult.success(found.orElse(null), current.transitionId(),
                            System.currentTimeMillis() - this.began);
                    }
                    return failure(switchFailureCode(this.failure),
                        current.failure() == null
                            ? "the switch did not commit: " + messageOf(this.failure) : current.failure(),
                        found.orElse(null), current.transitionId(), this.began);
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
                return null;
            });
        }

        private CompletableFuture<Void> capturePrevious() {
            checkDeadline();
            if (this.account.activeProfile() == null) return CompletableFuture.completedFuture(null);
            return ProfileEngine.this.players.capture(this.account.playerId(), ProfileEngine.this.settings.isolation())
                .thenCompose(state -> io(() -> {
                    this.previousState = state;
                    if (ProfileEngine.this.settings.coordination().fencingEnabled()
                        && this.previousClaim.get() != null
                        && !ProfileEngine.this.ownership.validate(this.previousClaim.get()).join()) {
                        throw error(ProfileErrorCode.OWNERSHIP_CONFLICT,
                            "the claim on the previous profile lapsed; its snapshot was not written");
                    }
                    try {
                        final ProfileSnapshot snapshot = writeSnapshotFor(this.account.activeProfile(), state);
                        this.previousSnapshot = snapshot;
                        if (snapshot != null) {
                            this.journal.set(this.journal.get().withFromSnapshot(snapshot.snapshotId()));
                        }
                    } catch (final ProfileOperationException failed) {
                        if (ProfileEngine.this.settings.switching().requireDurableSave()) throw failed;
                        SourbyLogger.warn("AWF MultiProfile: saving the previous profile failed and"
                            + " require-durable-save is false; continuing without a rollback point: "
                            + failed.getMessage());
                    }
                    return null;
                }))
                .thenCompose(ignored -> phase(TransitionPhase.SAVING));
        }

        private CompletableFuture<Void> worldReady() {
            checkDeadline();
            final long remaining = Math.max(1L, this.journal.get().deadlineMillis() - System.currentTimeMillis());
            return io(() -> ProfileEngine.this.repository.binding(this.target.id()).orElse(null))
                .thenCompose(binding -> {
                    if (binding == null || binding.isEmpty()) return CompletableFuture.completedFuture(null);
                    binding.worlds().keySet().forEach(ProfileEngine.this::touch);
                    return ProfileEngine.this.worlds.ensureReady(binding).exceptionallyCompose(failed ->
                        CompletableFuture.failedFuture(error(ProfileErrorCode.WORLD_UNAVAILABLE,
                            "profile world is not available: " + messageOf(failed))));
                })
                // A world creation that waits on the global tick must not keep a switch (and its
                // journal) alive forever; the timeout completes the chain, which rolls back.
                .orTimeout(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                .exceptionallyCompose(failed -> CompletableFuture.failedFuture(
                    failed instanceof java.util.concurrent.TimeoutException
                        ? error(ProfileErrorCode.TIMEOUT, "the profile world did not become ready within"
                            + " switching.timeout-ms; the switch was rolled back")
                        : failed));
        }

        private CompletableFuture<Void> applyTarget() {
            checkDeadline();
            return phase(TransitionPhase.APPLYING_TARGET).thenCompose(phaseIgnored -> io(() -> {
                final AuroraProfile current = ProfileEngine.this.repository.profile(this.target.id())
                    .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "the target disappeared"));
                final Optional<ProfileSnapshot> snapshot = ProfileEngine.this.repository.latestSnapshot(this.target.id());
                if (snapshot.isPresent() && snapshot.get().revision() < current.revision()) {
                    throw error(ProfileErrorCode.STALE_STATE, "the stored state of " + this.target.id()
                        + " is revision " + snapshot.get().revision() + ", the profile is " + current.revision());
                }
                return snapshot.map(ProfileSnapshot::state).map(ProfileStateCodec::decode).orElseGet(ProfileState::empty);
            })).thenCompose(state -> {
                if (state.spawn() != null) ProfileEngine.this.touch(state.spawn().world());
                return ProfileEngine.this.players.apply(this.account.playerId(), state,
                        ProfileEngine.this.settings.isolation()).thenCompose(ignored -> {
                    final ProfileState.Location destination = state.spawn() != null ? state.spawn()
                        : state.returnPoint();
                    if (destination == null) return CompletableFuture.completedFuture(null);
                    return phase(TransitionPhase.TELEPORTING).thenCompose(teleporting ->
                        ProfileEngine.this.players.teleport(this.account.playerId(), destination));
                });
            });
        }

        private CompletableFuture<Void> releasePrevious() {
            final UUID previous = this.journal.get().fromProfile();
            if (previous == null || previous.equals(this.target.id())) {
                return CompletableFuture.completedFuture(null);
            }
            final OwnerClaim held = this.previousClaim.getAndSet(null);
            if (held != null) {
                return ProfileEngine.this.io(() -> {
                    ProfileEngine.this.ownership.release(held).join();
                    ProfileEngine.this.sessionClaims.remove(this.account.playerId(), held);
                    return null;
                });
            }
            return ProfileEngine.this.ownership.holder(previous).thenCompose(holder -> {
                if (holder == null || !holder.node().equals(ProfileEngine.this.nodeId)) {
                    return CompletableFuture.completedFuture(null);
                }
                return ProfileEngine.this.ownership.release(holder).thenApply(released -> {
                    ProfileEngine.this.sessionClaims.remove(this.account.playerId(), holder);
                    return null;
                });
            });
        }

        /** Releases the claim a failed switch took on the target; the player never committed to it. */
        private CompletableFuture<Void> releaseTargetClaim() {
            final OwnerClaim held = this.claim.getAndSet(null);
            if (held == null) return CompletableFuture.completedFuture(null);
            return ProfileEngine.this.io(() -> {
                try {
                    ProfileEngine.this.ownership.release(held).join();
                } catch (final RuntimeException failed) {
                    SourbyLogger.warn("AWF MultiProfile: releasing the claim of a failed switch failed: "
                        + messageOf(failed));
                }
                return null;
            });
        }

        private CompletableFuture<Void> compensate(final Throwable failed) {
            final String reason = messageOf(failed);
            this.journal.updateAndGet(journal -> journal.at(TransitionPhase.RECOVERING));
            this.journal.set(this.journal.get().failed(reason));
            if (this.commitPoint.get()) {
                // The account already points at the target; finish forward instead of rolling back.
                return io(() -> {
                    ProfileEngine.this.repository.saveTransition(this.journal.get());
                    return null;
                });
            }
            if (ProfileEngine.this.metrics != null) ProfileEngine.this.metrics.profileFailure();
            final Map<String, String> recovery = Map.of("player", this.account.playerId().toString(),
                "transition", this.journal.get().transitionId().toString(), "reason", truncate(reason));
            final CompletableFuture<Void> released = releaseTargetClaim();
            if (this.previousSnapshot == null || this.previousState == null
                || !ProfileEngine.this.players.online(this.account.playerId())) {
                return released.thenCompose(ignored -> io(() -> {
                    publish(FabricEventType.PROFILE_RECOVERY_REQUIRED, recovery);
                    ProfileEngine.this.repository.saveTransition(this.journal.get());
                    return null;
                }));
            }
            // The Redis publish and the journal fsync are blocking I/O; they must not run on the
            // entity thread that completed the failed apply.
            return released
                .thenCompose(ignored -> io(() -> {
                    publish(FabricEventType.PROFILE_RECOVERY_REQUIRED, recovery);
                    return null;
                }))
                .thenCompose(ignored -> ProfileEngine.this.players.apply(this.account.playerId(), this.previousState,
                        ProfileEngine.this.settings.isolation()))
                .handleAsync((ignored, rollbackFailed) -> {
                    try {
                        ProfileEngine.this.repository.saveTransition(this.journal.get());
                    } catch (final IOException journalFailed) {
                        SourbyLogger.error("AWF MultiProfile: could not record a failed transition: "
                            + journalFailed.getMessage());
                    }
                    if (rollbackFailed != null) {
                        SourbyLogger.warn("AWF MultiProfile: rollback of a failed switch did not complete: "
                            + messageOf(rollbackFailed));
                    }
                    return (Void) null;
                }, ProfileEngine.this.io);
        }

        private CompletableFuture<Void> phase(final TransitionPhase next) {
            return io(() -> {
                checkDeadline();
                final ProfileTransition updated = this.journal.get().at(next);
                ProfileEngine.this.repository.saveTransition(updated);
                this.journal.set(updated);
                return null;
            });
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

    private void writeSnapshot(final AuroraProfile profile, final ProfileState state) throws IOException {
        final long began = System.nanoTime();
        writeSnapshotFor(profile.id(), state);
        if (this.metrics != null) this.metrics.snapshot(System.nanoTime() - began);
    }

    /** Writes a snapshot at {@code revision + 1} and bumps the profile revision. */
    private ProfileSnapshot writeSnapshotFor(final UUID profileId, final ProfileState state) throws IOException {
        // Two writers that both read revision N would otherwise publish two snapshots at N+1, and
        // "latest" would depend on directory order. The lock covers the read, the snapshot and the
        // revision bump; a save and a switch can still race on *which* state they captured, which is
        // refused by the caller's active-profile check.
        synchronized (this.revisionLock) {
            final AuroraProfile profile = this.repository.profile(profileId)
                .orElseThrow(() -> error(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + profileId));
            final byte[] encoded = ProfileStateCodec.encode(state);
            final ProfileSnapshot snapshot = new ProfileSnapshot(profileId, UUID.randomUUID(), ProfileState.SCHEMA,
                profile.revision() + 1, encoded, ProfileStateCodec.checksum(encoded), System.currentTimeMillis());
            this.repository.saveSnapshot(snapshot, 3);
            this.repository.saveProfile(profile.nextRevision());
            return snapshot;
        }
    }

    private CompletableFuture<ProfileState> previousState(final UUID profileId) {
        return io(() -> this.repository.latestSnapshot(profileId)
            .map(ProfileSnapshot::state).map(ProfileStateCodec::decode).orElseGet(ProfileState::empty));
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

    private void publish(final FabricEventType type, final Map<String, String> payload) {
        if (this.events == null) return;
        try {
            this.events.publish(FabricEvent.of(type, this.nodeId, payload));
        } catch (final IOException notPublished) {
            SourbyLogger.warn("AWF MultiProfile: could not publish " + type + ": " + notPublished.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private <T> CompletableFuture<T> io(final IoSupplier<T> work) {
        this.pendingIo.incrementAndGet();
        final CompletableFuture<T> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
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
            }, this.io);
        } catch (final java.util.concurrent.RejectedExecutionException rejected) {
            // The storage lane is bounded and may be saturated or already shut down. The API says
            // these futures never throw, and a counter that never returns would block the drain.
            this.pendingIo.decrementAndGet();
            return CompletableFuture.failedFuture(error(ProfileErrorCode.INTERNAL_ERROR,
                "the profile I/O lane refused the task: " + rejected.getMessage()));
        }
        return future.whenComplete((ignored, failed) -> this.pendingIo.decrementAndGet());
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

    static ProfileOperationException unwrap(final Throwable failed) {
        Throwable current = failed;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
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
