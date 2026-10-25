package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
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

    /** The write-path check: may this exact owner still commit a protected mutation? */
    CompletableFuture<Boolean> validate(OwnerClaim claim);

    CompletableFuture<Boolean> release(OwnerClaim claim);

    /** The current holder, or {@code null}. */
    CompletableFuture<OwnerClaim> holder(UUID profileId);

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
