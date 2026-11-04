package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.CoopClaimFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import dev.iyanz.sourbycraft.awf.redis.fabric.SessionFabric;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Cross-node profile ownership on the Aurora Redis Fabric.
 *
 * <p>The solo claim lives on the {@link SessionFabric}: the epoch is the fence, stored in a key that
 * never expires, so a TTL lapse does not reset it, and a takeover always increments it.
 * {@link #validate} checks both the epoch and the remaining TTL, so a node whose lease expired cannot
 * commit even before another node takes over.</p>
 *
 * <p>The co-op transaction claim {@code P} lives on the {@link CoopClaimFabric}
 * ({@code coop:profile-claim:<id>}), strict and with its own fence counter; the repository's format-2
 * write scripts read that hash, never the session hash, when a guard carries a {@code P}.</p>
 *
 * <p>Every method blocks on Redis, so calls must come from an I/O worker. The futures are completed
 * on the calling thread; the profile engine invokes them from its I/O executor only.</p>
 */
public final class RedisProfileOwnership implements ProfileOwnership {

    private final SessionFabric sessions;
    private final CoopClaimFabric coop;
    private final String nodeId;
    private final int timeoutMillis;

    public RedisProfileOwnership(final SessionFabric sessions, final String nodeId, final int timeoutMillis) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.coop = sessions.coopClaims();
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        if (timeoutMillis < 100) throw new IllegalArgumentException("timeout " + timeoutMillis);
        this.timeoutMillis = timeoutMillis;
    }

    /** The strict claim fabric this provider issues {@code P} on; the Redis repository compares against its keys. */
    public CoopClaimFabric coopClaims() {
        return this.coop;
    }

    @Override
    public CompletableFuture<ClaimResult> claim(final UUID profileId, final String node, final UUID ownerToken) {
        try {
            return CompletableFuture.completedFuture(this.sessions.claim(profileId, node, ownerToken));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    @Override
    public CompletableFuture<Boolean> renew(final OwnerClaim claim) {
        try {
            return CompletableFuture.completedFuture(this.sessions.refresh(UUID.fromString(claim.id()),
                claim.node(), UUID.fromString(claim.owner()), claim.fence()));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    @Override
    public CompletableFuture<Boolean> validate(final OwnerClaim claim) {
        try {
            return CompletableFuture.completedFuture(this.sessions.valid(UUID.fromString(claim.id()),
                claim.node(), UUID.fromString(claim.owner()), claim.fence()));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    @Override
    public CompletableFuture<Boolean> release(final OwnerClaim claim) {
        try {
            return CompletableFuture.completedFuture(this.sessions.release(UUID.fromString(claim.id()),
                claim.node(), UUID.fromString(claim.owner()), claim.fence()));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    /** One pipelined {@code REFRESH} per claim in a single round trip ({@link SessionFabric#refreshAll}). */
    @Override
    public CompletableFuture<List<Boolean>> renewAll(final List<OwnerClaim> claims) {
        try {
            return CompletableFuture.completedFuture(this.sessions.refreshAll(claims));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    @Override
    public CompletableFuture<OwnerClaim> holder(final UUID profileId) {
        try {
            return CompletableFuture.completedFuture(this.sessions.holder(profileId));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    /** Compares the holder's incarnation with this process's, not only its node id. */
    @Override
    public CompletableFuture<OwnerClaim> foreignHolder(final UUID profileId) {
        try {
            return CompletableFuture.completedFuture(this.sessions.foreignHolder(profileId));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    /** {@link SessionFabric#reclaim}: the session epoch is the fence. */
    @Override
    public CompletableFuture<ClaimResult> reclaim(final UUID profileId, final long expectedFence, final String node,
                                                  final UUID ownerToken) {
        try {
            return CompletableFuture.completedFuture(this.sessions.reclaim(profileId, expectedFence, node, ownerToken));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    // ------------------------------------------------------------------ co-op P

    @Override
    public CompletableFuture<TransactionGrant> claimTransaction(final UUID profileId, final UUID ownerToken) {
        try {
            final ClaimResult result = this.coop.claim(CoopClaimFabric.Kind.PROFILE, profileId, this.nodeId, ownerToken);
            if (result.granted()) {
                return CompletableFuture.completedFuture(TransactionGrant.granted(ProfileClaim.of(result.claim(),
                    this.coop.incarnation())));
            }
            if ("exhausted".equals(result.reason())) return CompletableFuture.completedFuture(TransactionGrant.exhausted());
            return CompletableFuture.completedFuture(TransactionGrant.held(holderOf(profileId, result.claim())));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    @Override
    public CompletableFuture<Boolean> renewTransaction(final ProfileClaim claim) {
        try {
            return CompletableFuture.completedFuture(this.coop.renew(CoopClaimFabric.Kind.PROFILE, claim.profileId(),
                claim.node(), claim.ownerToken(), claim.fence()));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    @Override
    public CompletableFuture<Boolean> validateTransaction(final ProfileClaim claim) {
        try {
            return CompletableFuture.completedFuture(this.coop.valid(CoopClaimFabric.Kind.PROFILE, claim.profileId(),
                claim.node(), claim.ownerToken(), claim.fence()));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    @Override
    public CompletableFuture<Boolean> releaseTransaction(final ProfileClaim claim) {
        try {
            return CompletableFuture.completedFuture(this.coop.release(CoopClaimFabric.Kind.PROFILE, claim.profileId(),
                claim.node(), claim.ownerToken(), claim.fence()));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    @Override
    public CompletableFuture<ProfileClaim> transactionHolder(final UUID profileId) {
        try {
            return CompletableFuture.completedFuture(holderOf(profileId, this.coop.holder(CoopClaimFabric.Kind.PROFILE, profileId)));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    private ProfileClaim holderOf(final UUID profileId, final OwnerClaim holder) throws IOException {
        if (holder == null) return null;
        final String incarnation = this.coop.holderIncarnation(CoopClaimFabric.Kind.PROFILE, profileId);
        return new ProfileClaim(profileId, holder.node(), incarnation == null ? "" : incarnation,
            UUID.fromString(holder.owner()), holder.fence(), holder.expiresAtMillis());
    }

    @Override
    public boolean distributed() {
        return true;
    }

    /** The epoch keys never expire and every node shares them, so fences compare across processes. */
    @Override
    public String incarnation() {
        return "redis-session-epoch";
    }

    @Override
    public String node() {
        return this.nodeId;
    }

    @Override
    public String describe() {
        return "redis session fabric (node " + this.nodeId + ", timeout " + this.timeoutMillis + " ms)";
    }
}
