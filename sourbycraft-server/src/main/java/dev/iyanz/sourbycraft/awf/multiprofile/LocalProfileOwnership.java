package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
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
 */
public final class LocalProfileOwnership implements ProfileOwnership {

    private record Lease(OwnerClaim claim, long expiresAtNanos) {}

    private final ConcurrentHashMap<UUID, Lease> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> fences = new ConcurrentHashMap<>();
    private final long leaseNanos;
    private final String nodeId;
    private final String incarnation = "local:" + UUID.randomUUID();

    public LocalProfileOwnership(final String nodeId, final int leaseSeconds) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        if (leaseSeconds < 1) throw new IllegalArgumentException("leaseSeconds " + leaseSeconds);
        this.leaseNanos = leaseSeconds * 1_000_000_000L;
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

    /** Every live lease, for admin diagnostics. */
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
    public String describe() {
        return "local (node " + this.nodeId + ", " + this.leases.size() + " claims)";
    }
}
