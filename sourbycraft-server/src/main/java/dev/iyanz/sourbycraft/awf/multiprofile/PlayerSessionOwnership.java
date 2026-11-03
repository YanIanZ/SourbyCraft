package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.CoopClaimFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.NodeIdentity;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The player session claim {@code Q}: one online session of a player across every profile and node.
 *
 * <p>{@code Q} is acquired at join, before any capture or restore is admitted, renewed with every
 * other operation-held claim, and released at quit; a token is never reused. It is strict: a live
 * claim is renewed only by the exact same {@code node/incarnation/owner} session and refused for every
 * other token, also one of the same node and process, so two sessions of one player (a duplicate
 * login, a rejoin before the previous quit finished) cannot both write. This differs from the solo
 * {@link ProfileOwnership} claim on purpose and must not be emulated with it: that claim is keyed by
 * profile, this one by player, and the two live in disjoint namespaces ({@code coop:player-claim:<id>}).</p>
 *
 * <p><b>Loss</b> (COOP-DESIGN §3): on a confirmed loss (a renewal or validation answers "not held")
 * the engine freezes the player, refuses captures and mutations under the old claim, cancels queued
 * applies through its generation token, and disconnects the player through its entity scheduler when
 * authority cannot be regained. It never re-acquires and then publishes a capture taken under the lost
 * claim. On an <em>uncertain</em> renewal (timeout, lost reply) it blocks durable mutations and new
 * gameplay admission until a validation answers; a timeout is never treated as proof of expiry.</p>
 *
 * <p>Every method is asynchronous in signature only: the Redis provider blocks on the calling thread
 * and must be used from an I/O worker. Build 51 wires {@code Q} behind disabled co-op admission.</p>
 */
public interface PlayerSessionOwnership extends AutoCloseable {

    /**
     * Acquires {@code Q} for {@code playerId} with {@code ownerToken}, or renews this exact token's
     * live claim. {@code held} while another token holds it (holder reported); {@code exhausted} when
     * the player's fence counter reached {@link CoopWriteGuard#MAX_COUNTER}.
     */
    CompletableFuture<Grant> claim(UUID playerId, UUID ownerToken);

    /** Extends a live claim without changing its fence; {@code false} when it is not this exact claim any more. */
    CompletableFuture<Boolean> renew(PlayerClaim claim);

    /**
     * {@link #renew} for several claims, results in the order of {@code claims}. A failure fails the
     * whole call, and then no claim may be treated as lapsed.
     */
    default CompletableFuture<List<Boolean>> renewAll(final List<PlayerClaim> claims) {
        final List<Boolean> renewed = new ArrayList<>(claims.size());
        try {
            for (final PlayerClaim claim : claims) renewed.add(renew(claim).join());
        } catch (final RuntimeException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        return CompletableFuture.completedFuture(List.copyOf(renewed));
    }

    /** Whether this exact claim (session and fence) is still live; the pre-check, not the commit-time check. */
    CompletableFuture<Boolean> validate(PlayerClaim claim);

    /** Releases this exact claim; {@code false} when another claim replaced it. The fence counter is kept. */
    CompletableFuture<Boolean> release(PlayerClaim claim);

    /** The current holder, live or lapsed (compare its expiry), or {@code null}. */
    CompletableFuture<PlayerClaim> holder(UUID playerId);

    /** Whether this provider coordinates more than one process. */
    boolean distributed();

    /** Lifetime of the fence counters, as {@link ProfileOwnership#incarnation()}. */
    String incarnation();

    /** The node id this provider claims as. */
    String node();

    /** Short description for logs and status; never contains credentials. */
    String describe();

    @Override
    default void close() {}

    /** Process-local {@code Q}: one server, no Redis. Fences restart at 1 in every process. */
    static PlayerSessionOwnership local(final String nodeId, final int leaseSeconds) {
        return new Local(nodeId, leaseSeconds);
    }

    /** Cross-node {@code Q} on the {@link CoopClaimFabric}; fences are durable in Redis. */
    static PlayerSessionOwnership redis(final CoopClaimFabric fabric, final String nodeId) {
        return new Redis(fabric, nodeId);
    }

    /**
     * The outcome of {@link #claim}.
     *
     * @param granted whether the caller holds {@code Q} now
     * @param claim the claim when granted, else {@code null}
     * @param holder when refused as {@code held}: the live holder
     * @param reason {@code granted}, {@code held} or {@code exhausted}
     */
    record Grant(boolean granted, PlayerClaim claim, PlayerClaim holder, String reason) {

        public Grant {
            Objects.requireNonNull(reason, "reason");
            if (granted && claim == null) throw new IllegalArgumentException("a granted claim is required");
        }

        public static Grant granted(final PlayerClaim claim) {
            return new Grant(true, claim, null, "granted");
        }

        public static Grant held(final PlayerClaim holder) {
            return new Grant(false, null, holder, "held");
        }

        public static Grant exhausted() {
            return new Grant(false, null, null, "exhausted");
        }
    }

    // ------------------------------------------------------------------ process-local

    /** One process; the strict lease table is {@link LocalProfileOwnership.StrictLeases}. */
    final class Local implements PlayerSessionOwnership {

        private final LocalProfileOwnership.StrictLeases leases;
        private final String nodeId;
        private final String incarnation = "local:" + UUID.randomUUID();

        private Local(final String nodeId, final int leaseSeconds) {
            this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
            // The leases carry this instance's incarnation, so a claim's incarnation() and this provider's agree.
            this.leases = new LocalProfileOwnership.StrictLeases(leaseSeconds, this.incarnation);
        }

        @Override
        public CompletableFuture<Grant> claim(final UUID playerId, final UUID ownerToken) {
            final LocalProfileOwnership.StrictLeases.Outcome outcome = this.leases.claim(playerId, this.nodeId, ownerToken);
            if (outcome.exhausted()) return CompletableFuture.completedFuture(Grant.exhausted());
            final PlayerClaim claim = toClaim(playerId, outcome.lease());
            return CompletableFuture.completedFuture(outcome.granted() ? Grant.granted(claim) : Grant.held(claim));
        }

        @Override
        public CompletableFuture<Boolean> renew(final PlayerClaim claim) {
            return CompletableFuture.completedFuture(this.leases.renew(claim.playerId(), claim.session(), claim.fence()));
        }

        @Override
        public CompletableFuture<Boolean> validate(final PlayerClaim claim) {
            return CompletableFuture.completedFuture(this.leases.valid(claim.playerId(), claim.session(), claim.fence()));
        }

        @Override
        public CompletableFuture<Boolean> release(final PlayerClaim claim) {
            return CompletableFuture.completedFuture(this.leases.release(claim.playerId(), claim.session(), claim.fence()));
        }

        @Override
        public CompletableFuture<PlayerClaim> holder(final UUID playerId) {
            final LocalProfileOwnership.StrictLeases.Lease lease = this.leases.holder(playerId);
            return CompletableFuture.completedFuture(lease == null ? null : toClaim(playerId, lease));
        }

        private static PlayerClaim toClaim(final UUID playerId, final LocalProfileOwnership.StrictLeases.Lease lease) {
            return new PlayerClaim(playerId, lease.node(), lease.incarnation(), lease.owner(), lease.fence(),
                lease.expiresAtMillis());
        }

        @Override
        public boolean distributed() {
            return false;
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
            return "local player sessions (node " + this.nodeId + ", " + this.leases.size() + " claims)";
        }
    }

    // ------------------------------------------------------------------ Redis

    /** Cross-node {@code Q} on {@link CoopClaimFabric.Kind#PLAYER}; every call blocks on Redis. */
    final class Redis implements PlayerSessionOwnership {

        private final CoopClaimFabric fabric;
        private final String nodeId;

        private Redis(final CoopClaimFabric fabric, final String nodeId) {
            this.fabric = Objects.requireNonNull(fabric, "fabric");
            this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        }

        @Override
        public CompletableFuture<Grant> claim(final UUID playerId, final UUID ownerToken) {
            try {
                final ClaimResult result = this.fabric.claim(CoopClaimFabric.Kind.PLAYER, playerId, this.nodeId, ownerToken);
                if (result.granted()) {
                    return CompletableFuture.completedFuture(Grant.granted(PlayerClaim.of(result.claim(), this.fabric.incarnation())));
                }
                if ("exhausted".equals(result.reason())) return CompletableFuture.completedFuture(Grant.exhausted());
                return CompletableFuture.completedFuture(Grant.held(holderOf(playerId, result.claim())));
            } catch (final IOException failed) {
                return CompletableFuture.failedFuture(failed);
            }
        }

        @Override
        public CompletableFuture<Boolean> renew(final PlayerClaim claim) {
            try {
                return CompletableFuture.completedFuture(this.fabric.renew(CoopClaimFabric.Kind.PLAYER, claim.playerId(),
                    claim.node(), claim.ownerToken(), claim.fence()));
            } catch (final IOException failed) {
                return CompletableFuture.failedFuture(failed);
            }
        }

        /** One pipelined {@code RENEW} per claim in a single round trip ({@link CoopClaimFabric#renewAll}). */
        @Override
        public CompletableFuture<List<Boolean>> renewAll(final List<PlayerClaim> claims) {
            try {
                final List<OwnerClaim> raw = new ArrayList<>(claims.size());
                for (final PlayerClaim claim : claims) raw.add(claim.toOwnerClaim());
                return CompletableFuture.completedFuture(this.fabric.renewAll(raw));
            } catch (final IOException failed) {
                return CompletableFuture.failedFuture(failed);
            }
        }

        @Override
        public CompletableFuture<Boolean> validate(final PlayerClaim claim) {
            try {
                return CompletableFuture.completedFuture(this.fabric.valid(CoopClaimFabric.Kind.PLAYER, claim.playerId(),
                    claim.node(), claim.ownerToken(), claim.fence()));
            } catch (final IOException failed) {
                return CompletableFuture.failedFuture(failed);
            }
        }

        @Override
        public CompletableFuture<Boolean> release(final PlayerClaim claim) {
            try {
                return CompletableFuture.completedFuture(this.fabric.release(CoopClaimFabric.Kind.PLAYER, claim.playerId(),
                    claim.node(), claim.ownerToken(), claim.fence()));
            } catch (final IOException failed) {
                return CompletableFuture.failedFuture(failed);
            }
        }

        @Override
        public CompletableFuture<PlayerClaim> holder(final UUID playerId) {
            try {
                return CompletableFuture.completedFuture(holderOf(playerId,
                    this.fabric.holder(CoopClaimFabric.Kind.PLAYER, playerId)));
            } catch (final IOException failed) {
                return CompletableFuture.failedFuture(failed);
            }
        }

        /** A holder read from Redis: its incarnation is in the hash, read separately (or unknown). */
        private PlayerClaim holderOf(final UUID playerId, final OwnerClaim holder) throws IOException {
            if (holder == null) return null;
            final String incarnation = this.fabric.holderIncarnation(CoopClaimFabric.Kind.PLAYER, playerId);
            return new PlayerClaim(playerId, holder.node(), incarnation == null ? "" : incarnation,
                UUID.fromString(holder.owner()), holder.fence(), holder.expiresAtMillis());
        }

        @Override
        public boolean distributed() {
            return true;
        }

        /** The fence keys never expire and every node shares them, so fences compare across processes. */
        @Override
        public String incarnation() {
            return "redis-coop-fence";
        }

        @Override
        public String node() {
            return this.nodeId;
        }

        @Override
        public String describe() {
            return "redis co-op player sessions (node " + this.nodeId + ", process " + NodeIdentity.processIncarnation() + ")";
        }
    }
}
