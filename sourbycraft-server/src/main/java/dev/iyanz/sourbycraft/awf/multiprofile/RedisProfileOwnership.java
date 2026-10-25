package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import dev.iyanz.sourbycraft.awf.redis.fabric.SessionFabric;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Cross-node profile ownership on the Aurora Redis Fabric's session fabric.
 *
 * <p>The epoch is the fence: it is stored in a key that never expires, so a TTL lapse does not reset
 * it, and a takeover always increments it. {@link #validate} checks both the epoch and the remaining
 * TTL, so a node whose lease expired cannot commit even before another node takes over — the
 * distributed write path is not writable while the authoritative service says the claim is gone.</p>
 *
 * <p>Every method blocks on Redis, so calls must come from an I/O worker. The futures are completed
 * by {@link #claim} on the calling thread; the profile engine invokes them from its I/O executor
 * only.</p>
 */
public final class RedisProfileOwnership implements ProfileOwnership {

    private final SessionFabric sessions;
    private final String nodeId;
    private final int timeoutMillis;

    public RedisProfileOwnership(final SessionFabric sessions, final String nodeId, final int timeoutMillis) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        if (timeoutMillis < 100) throw new IllegalArgumentException("timeout " + timeoutMillis);
        this.timeoutMillis = timeoutMillis;
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

    @Override
    public CompletableFuture<OwnerClaim> holder(final UUID profileId) {
        try {
            return CompletableFuture.completedFuture(this.sessions.holder(profileId));
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
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
    public String describe() {
        return "redis session fabric (node " + this.nodeId + ", timeout " + this.timeoutMillis + " ms)";
    }
}
