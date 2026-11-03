package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local ownership: one SourbyCraft server, no Redis.
 *
 * <p>The fence is a per-profile counter that only increases. Expiry uses {@link System#nanoTime}, so
 * it is immune to wall-clock jumps; a claim that expired is reclaimed with a higher fence and the
 * previous holder's {@link #validate} fails.</p>
 *
 * <p>The counters live in memory and start at 1 in every process, so each instance has a random
 * {@link #incarnation()}: recovery does not compare a fence a journal recorded before a restart with
 * one issued after it.</p>
 *
 * <p>The solo claim ({@link #claim}) keeps its same-process supersession: a new token of this node
 * replaces a live claim, which the solo switch protocol relies on. The co-op transaction claim
 * {@code P} ({@link #claimTransaction}) is a separate {@link StrictLeases} table with its own fence
 * counters: a live {@code P} is renewed only by the exact same token and refused for every other, also
 * of this process. The two tables never see each other's ids as the same lease.</p>
 */
public final class LocalProfileOwnership implements ProfileOwnership {

    private record Lease(OwnerClaim claim, long expiresAtNanos) {}

    private final ConcurrentHashMap<UUID, Lease> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> fences = new ConcurrentHashMap<>();
    private final StrictLeases transactions;
    private final long leaseNanos;
    private final String nodeId;
    private final String incarnation = "local:" + UUID.randomUUID();

    public LocalProfileOwnership(final String nodeId, final int leaseSeconds) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        if (leaseSeconds < 1) throw new IllegalArgumentException("leaseSeconds " + leaseSeconds);
        this.leaseNanos = leaseSeconds * 1_000_000_000L;
        this.transactions = new StrictLeases(leaseSeconds, this.incarnation);
    }

    @Override
    public CompletableFuture<ClaimResult> claim(final UUID profileId, final String node, final UUID ownerToken) {
        return CompletableFuture.completedFuture(claimNow(profileId, node, ownerToken));
    }

    private synchronized ClaimResult claimNow(final UUID profileId, final String node, final UUID ownerToken) {
        final long now = System.nanoTime();
        final Lease current = this.leases.get(profileId);
        if (current != null) {
            final boolean expired = current.expiresAtNanos() - now <= 0L;
            final boolean same = current.claim().owner().equals(ownerToken.toString())
                && current.claim().node().equals(node);
            if (!expired) {
                if (same) {
                    final OwnerClaim renewed = new OwnerClaim("profile", profileId.toString(), node,
                        ownerToken.toString(), current.claim().epoch(), current.claim().fence(),
                        System.currentTimeMillis() + this.leaseNanos / 1_000_000L);
                    this.leases.put(profileId, new Lease(renewed, now + this.leaseNanos));
                    return ClaimResult.granted(renewed);
                }
                if (current.claim().node().equals(node)) {
                    // Same process, new owner token: a rejoin after a switch, or the switch's own
                    // claim of the profile it is moving to. The node id names exactly one process,
                    // so this is not a cross-node conflict and must not reject the player.
                    final long fence = this.fences.merge(profileId, 1L, Long::sum);
                    final OwnerClaim claim = new OwnerClaim("profile", profileId.toString(), node,
                        ownerToken.toString(), fence, fence,
                        System.currentTimeMillis() + this.leaseNanos / 1_000_000L);
                    this.leases.put(profileId, new Lease(claim, now + this.leaseNanos));
                    return ClaimResult.granted(claim);
                }
                return ClaimResult.refused("held", current.claim());
            }
        }
        final long fence = this.fences.merge(profileId, 1L, Long::sum);
        final OwnerClaim claim = new OwnerClaim("profile", profileId.toString(), node, ownerToken.toString(), fence,
            fence, System.currentTimeMillis() + this.leaseNanos / 1_000_000L);
        this.leases.put(profileId, new Lease(claim, now + this.leaseNanos));
        return ClaimResult.granted(claim);
    }

    @Override
    public CompletableFuture<Boolean> renew(final OwnerClaim claim) {
        return CompletableFuture.completedFuture(renewNow(claim));
    }

    private synchronized boolean renewNow(final OwnerClaim claim) {
        final long now = System.nanoTime();
        final Lease current = this.leases.get(UUID.fromString(claim.id()));
        if (current == null || !current.claim().sameIncarnation(claim) || current.expiresAtNanos() - now <= 0L) {
            return false;
        }
        this.leases.put(UUID.fromString(claim.id()), new Lease(current.claim(), now + this.leaseNanos));
        return true;
    }

    @Override
    public CompletableFuture<Boolean> validate(final OwnerClaim claim) {
        return CompletableFuture.completedFuture(validateNow(claim));
    }

    private synchronized boolean validateNow(final OwnerClaim claim) {
        final Lease current = this.leases.get(UUID.fromString(claim.id()));
        return current != null && current.claim().sameIncarnation(claim)
            && current.expiresAtNanos() - System.nanoTime() > 0L;
    }

    @Override
    public CompletableFuture<Boolean> release(final OwnerClaim claim) {
        return CompletableFuture.completedFuture(releaseNow(claim));
    }

    private synchronized boolean releaseNow(final OwnerClaim claim) {
        final Lease current = this.leases.get(UUID.fromString(claim.id()));
        if (current == null || !current.claim().sameIncarnation(claim)) return false;
        this.leases.remove(UUID.fromString(claim.id()), current);
        return true;
    }

    @Override
    public CompletableFuture<OwnerClaim> holder(final UUID profileId) {
        return CompletableFuture.completedFuture(holderNow(profileId));
    }

    private synchronized OwnerClaim holderNow(final UUID profileId) {
        final Lease lease = this.leases.get(profileId);
        if (lease == null || lease.expiresAtNanos() - System.nanoTime() <= 0L) return null;
        return lease.claim();
    }

    /** Claims live in this process only, so a holder under another node id is the only foreign one. */
    @Override
    public CompletableFuture<OwnerClaim> foreignHolder(final UUID profileId) {
        final OwnerClaim holder = holderNow(profileId);
        return CompletableFuture.completedFuture(holder == null || holder.node().equals(this.nodeId) ? null : holder);
    }

    // ------------------------------------------------------------------ co-op P

    @Override
    public CompletableFuture<TransactionGrant> claimTransaction(final UUID profileId, final UUID ownerToken) {
        final StrictLeases.Outcome outcome = this.transactions.claim(profileId, this.nodeId, ownerToken);
        if (outcome.exhausted()) return CompletableFuture.completedFuture(TransactionGrant.exhausted());
        final ProfileClaim claim = toClaim(profileId, outcome.lease());
        return CompletableFuture.completedFuture(outcome.granted() ? TransactionGrant.granted(claim)
            : TransactionGrant.held(claim));
    }

    @Override
    public CompletableFuture<Boolean> renewTransaction(final ProfileClaim claim) {
        return CompletableFuture.completedFuture(this.transactions.renew(claim.profileId(), claim.session(), claim.fence()));
    }

    @Override
    public CompletableFuture<Boolean> validateTransaction(final ProfileClaim claim) {
        return CompletableFuture.completedFuture(this.transactions.valid(claim.profileId(), claim.session(), claim.fence()));
    }

    @Override
    public CompletableFuture<Boolean> releaseTransaction(final ProfileClaim claim) {
        return CompletableFuture.completedFuture(this.transactions.release(claim.profileId(), claim.session(), claim.fence()));
    }

    @Override
    public CompletableFuture<ProfileClaim> transactionHolder(final UUID profileId) {
        final StrictLeases.Lease lease = this.transactions.holder(profileId);
        return CompletableFuture.completedFuture(lease == null ? null : toClaim(profileId, lease));
    }

    private static ProfileClaim toClaim(final UUID profileId, final StrictLeases.Lease lease) {
        return new ProfileClaim(profileId, lease.node(), lease.incarnation(), lease.owner(), lease.fence(),
            lease.expiresAtMillis());
    }

    /** Every live solo lease, for admin diagnostics. */
    public Map<UUID, OwnerClaim> live() {
        final Map<UUID, OwnerClaim> live = new java.util.LinkedHashMap<>();
        final long now = System.nanoTime();
        this.leases.forEach((id, lease) -> {
            if (lease.expiresAtNanos() - now > 0L) live.put(id, lease.claim());
        });
        return live;
    }

    @Override
    public String incarnation() {
        return this.incarnation;
    }

    @Override
    public String node() {
        return this.nodeId;
    }

    @Override
    public String describe() {
        return "local (node " + this.nodeId + ", " + this.leases.size() + " claims, " + this.transactions.size()
            + " transaction claims)";
    }

    /**
     * A process-local strict lease table: per id, one live lease renewed only by the exact same
     * session and fence, refused for any other token (also of this process), with a per-id fence
     * counter that never resets within the process and refuses to pass {@link CoopWriteGuard#MAX_COUNTER}.
     * Expiry uses {@link System#nanoTime}. Shared by the local {@code P} and {@code Q} providers, each
     * with its own instance, so their ids never alias. Every lease carries the owning provider's
     * {@link ProfileOwnership#incarnation() incarnation} (one per instance, so a restarted provider is a new
     * one): the claims it mints and the provider's own {@code incarnation()} agree, which is what the engine's
     * recovery compares a journal's recorded incarnation against.
     */
    static final class StrictLeases {

        /** A live or lapsed lease; {@code session} is {@code node/incarnation/owner}. */
        record Lease(String session, String node, String incarnation, UUID owner, long fence, long expiresAtNanos,
                     long expiresAtMillis) {}

        /** {@code granted} with the new or renewed lease, or refused with the live holder; {@code exhausted} refuses with no lease. */
        record Outcome(boolean granted, boolean exhausted, Lease lease) {}

        private final Map<UUID, Lease> leases = new HashMap<>();
        private final Map<UUID, Long> fences = new HashMap<>();
        private final long leaseNanos;
        /** The owning provider's incarnation, written into every lease (see the class comment). */
        private final String incarnation;

        StrictLeases(final int leaseSeconds, final String incarnation) {
            if (leaseSeconds < 1) throw new IllegalArgumentException("leaseSeconds " + leaseSeconds);
            this.leaseNanos = leaseSeconds * 1_000_000_000L;
            this.incarnation = Objects.requireNonNull(incarnation, "incarnation");
        }

        synchronized Outcome claim(final UUID id, final String node, final UUID owner) {
            final long now = System.nanoTime();
            final String session = node + "/" + this.incarnation + "/" + owner;
            final Lease current = this.leases.get(id);
            if (current != null && current.expiresAtNanos() - now > 0L) {
                if (!current.session().equals(session)) return new Outcome(false, false, current);
                final Lease renewed = new Lease(session, node, this.incarnation, owner, current.fence(), now + this.leaseNanos,
                    System.currentTimeMillis() + this.leaseNanos / 1_000_000L);
                this.leases.put(id, renewed);
                return new Outcome(true, false, renewed);
            }
            final long previous = this.fences.getOrDefault(id, 0L);
            if (previous >= CoopWriteGuard.MAX_COUNTER) return new Outcome(false, true, null);
            final long fence = previous + 1L;
            this.fences.put(id, fence);
            final Lease lease = new Lease(session, node, this.incarnation, owner, fence, now + this.leaseNanos,
                System.currentTimeMillis() + this.leaseNanos / 1_000_000L);
            this.leases.put(id, lease);
            return new Outcome(true, false, lease);
        }

        synchronized boolean renew(final UUID id, final String session, final long fence) {
            final long now = System.nanoTime();
            final Lease current = this.leases.get(id);
            if (current == null || !current.session().equals(session) || current.fence() != fence
                || current.expiresAtNanos() - now <= 0L) {
                return false;
            }
            this.leases.put(id, new Lease(current.session(), current.node(), current.incarnation(), current.owner(),
                current.fence(), now + this.leaseNanos, System.currentTimeMillis() + this.leaseNanos / 1_000_000L));
            return true;
        }

        synchronized boolean valid(final UUID id, final String session, final long fence) {
            final Lease current = this.leases.get(id);
            return current != null && current.session().equals(session) && current.fence() == fence
                && current.expiresAtNanos() - System.nanoTime() > 0L;
        }

        synchronized boolean release(final UUID id, final String session, final long fence) {
            final Lease current = this.leases.get(id);
            if (current == null || !current.session().equals(session) || current.fence() != fence) return false;
            this.leases.remove(id);
            return true;
        }

        /** The stored lease, live or lapsed (callers compare {@code expiresAtMillis}), or {@code null}. */
        synchronized Lease holder(final UUID id) {
            return this.leases.get(id);
        }

        synchronized int size() {
            return this.leases.size();
        }
    }
}
