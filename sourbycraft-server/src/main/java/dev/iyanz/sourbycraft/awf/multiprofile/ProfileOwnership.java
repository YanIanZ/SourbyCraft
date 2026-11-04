package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 *
 * <p><b>Two claim families.</b> The {@code claim}/{@code renew}/{@code validate}/{@code release}
 * methods are the format-1 solo profile claim the switch protocol holds for the whole time a player
 * plays a profile; in the process-local provider a new token of the same process supersedes it (a
 * rejoin after a switch). The {@code *Transaction} methods are the co-op profile claim {@code P}
 * ({@link ProfileClaim}): short exclusive authority over the profile's metadata and shared state for
 * one bounded transaction, released once it is durable or recoverable, never a player's session and
 * never transferred when players change. {@code P} is strict: a live claim is renewed only by the exact
 * same owner token and refused for every other token, also on the same node, so two transactions on
 * one profile cannot both hold it; it is never issued from the solo claim's counters or key space
 * (Redis: {@code coop:profile-claim:<id>}). Ordinary member saves do not need {@code P}; they run
 * under the player's {@link PlayerSessionOwnership Q} and the member head's revision.</p>
 *
 * <p><b>Loss.</b> When a {@code P} renewal fails or is uncertain the operation stops, leaves its
 * intent and reservations for recovery and writes nothing with the stale token, not even compensation;
 * a new fence recovers the intent. A renewal timeout is not proof of expiry: the holder blocks its
 * durable mutations until a validation answers.</p>
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

    // ------------------------------------------------------------------ co-op profile claim P

    /**
     * Acquires {@code P} on {@code profileId} for {@code ownerToken}, or renews this exact token's live
     * claim. Refused with reason {@code held} while any other token holds it (the holder is reported),
     * and with {@code exhausted} when the profile's fence counter reached
     * {@link CoopWriteGuard#MAX_COUNTER}. Never waits: a caller that must wait for another claim does
     * so through bounded admission, never while holding an entity thread or an I/O queue slot.
     */
    CompletableFuture<TransactionGrant> claimTransaction(UUID profileId, UUID ownerToken);

    /** Extends a live {@code P} without changing its fence; {@code false} when it is not this exact claim any more. */
    CompletableFuture<Boolean> renewTransaction(ProfileClaim claim);

    /** Whether this exact {@code P} (session and fence) is still live; the pre-check, not the commit-time check. */
    CompletableFuture<Boolean> validateTransaction(ProfileClaim claim);

    /** Releases this exact {@code P}; {@code false} when another claim replaced it. The fence counter is kept. */
    CompletableFuture<Boolean> releaseTransaction(ProfileClaim claim);

    /** The current {@code P} holder, live or lapsed (compare its expiry), or {@code null}. */
    CompletableFuture<ProfileClaim> transactionHolder(UUID profileId);

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

    /** The node id this provider claims as. */
    String node();

    /** Short description for logs and {@code /awf multiprofile status}. Never contains credentials. */
    default String describe() {
        return "local";
    }

    @Override
    default void close() {}

    /**
     * The outcome of {@link #claimTransaction}.
     *
     * @param granted whether the caller holds {@code P} now
     * @param claim the claim when granted, else {@code null}
     * @param holder when refused as {@code held}: the live holder (its expiry is the provider's clock)
     * @param reason {@code granted}, {@code held} or {@code exhausted}
     */
    record TransactionGrant(boolean granted, ProfileClaim claim, ProfileClaim holder, String reason) {

        public TransactionGrant {
            Objects.requireNonNull(reason, "reason");
            if (granted && claim == null) throw new IllegalArgumentException("a granted claim is required");
        }

        public static TransactionGrant granted(final ProfileClaim claim) {
            return new TransactionGrant(true, claim, null, "granted");
        }

        public static TransactionGrant held(final ProfileClaim holder) {
            return new TransactionGrant(false, null, holder, "held");
        }

        public static TransactionGrant exhausted() {
            return new TransactionGrant(false, null, null, "exhausted");
        }
    }
}
