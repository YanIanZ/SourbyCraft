package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Exclusive ownership of a profile.
 *
 * <p>Two implementations ship: {@link LocalProfileOwnership} (one process, no Redis) and
 * {@link RedisProfileOwnership} (fenced across nodes). Both expose the same claim and validate
 * operations, so the switch protocol is identical; only the failure modes differ.</p>
 *
 * <p>A claim is a lease, but a lease expiring is not permission to write: the replacement gets a
 * higher fence and {@link #validate} rejects the old one. Implementations must never validate a
 * claim they did not issue.</p>
 */
public interface ProfileOwnership extends AutoCloseable {

    CompletableFuture<ClaimResult> claim(UUID profileId, String node, UUID ownerToken);

    /** Extends the lease without changing the fence. */
    CompletableFuture<Boolean> renew(OwnerClaim claim);

    /**
     * {@link #renew} for several claims; the results are in the order of {@code claims}. A failure
     * fails the whole call, and then no claim may be treated as lapsed. This default renews one at a
     * time; a provider that can batch overrides it.
     */
    default CompletableFuture<List<Boolean>> renewAll(final List<OwnerClaim> claims) {
        final List<Boolean> renewed = new ArrayList<>(claims.size());
        try {
            for (final OwnerClaim claim : claims) renewed.add(renew(claim).join());
        } catch (final RuntimeException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        return CompletableFuture.completedFuture(List.copyOf(renewed));
    }

    /** The write-path check: may this exact owner still commit a protected mutation? */
    CompletableFuture<Boolean> validate(OwnerClaim claim);

    CompletableFuture<Boolean> release(OwnerClaim claim);

    /** The current holder, or {@code null}. */
    CompletableFuture<OwnerClaim> holder(UUID profileId);

    /**
     * The current holder when it is another process, or {@code null} when the profile is unowned or
     * this process holds it. Comparing node ids is not enough where one node id can name two processes
     * (a restarted server, or two servers that share the id): the provider compares the process.
     */
    CompletableFuture<OwnerClaim> foreignHolder(UUID profileId);

    /**
     * Operator recovery: takes the profile over with a new fence, only when its current fence is
     * exactly {@code expectedFence}, whoever holds it. A provider without cross-process claims has
     * nothing to take over and refuses.
     */
    default CompletableFuture<ClaimResult> reclaim(final UUID profileId, final long expectedFence, final String node,
                                                   final UUID ownerToken) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
            "process-local ownership has no claim of another process to take over; its claims end with the process"));
    }

    /** Whether this provider coordinates more than one process. */
    default boolean distributed() {
        return false;
    }

    /**
     * Names the lifetime of the fence counters this provider issues. Two fences are comparable only
     * when they carry the same incarnation: a process-local provider counts from 1 again after a
     * restart, so a fence a journal recorded in an earlier process says nothing about a fresh claim.
     * A provider whose counters are stored durably returns the same value in every process.
     */
    String incarnation();

    /** Short description for logs and {@code /awf multiprofile status}. Never contains credentials. */
    default String describe() {
        return "local";
    }

    @Override
    default void close() {}
}
