package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;

/**
 * What a protected {@link ProfileRepository} mutation is made under.
 *
 * <p>{@code claim} is the ownership claim (and so the fence) the caller validated before the write,
 * or {@code null} when the write is not made on behalf of an owned profile (creating a profile,
 * account bookkeeping, administrative changes). {@code expectedRevision} is the revision of the
 * record the caller read before it derived the record it now writes: the account revision for an
 * account write, the profile revision for a profile write or a snapshot publish. {@link #ANY} skips
 * the comparison (journals, bindings and archives carry no revision); {@link #ABSENT} expects that
 * no record is stored yet.</p>
 *
 * <p>The repository is the authoritative commit point, so the comparison belongs there rather than
 * in the caller. An implementation backed by a shared store must verify that the claim still holds
 * the profile and that the stored revision equals {@code expectedRevision} in the same atomic step as
 * the write, and refuse otherwise: {@link RedisProfileRepository} checks both inside the one Lua
 * script that applies the write. {@link FileProfileRepository} compares the revision under its own
 * lock and does not check the claim; its documentation says why that is not a distributed fence.</p>
 *
 * @param claim the claim the write is made under, or {@code null}
 * @param expectedRevision the revision read before the write, {@link #ABSENT} or {@link #ANY}
 */
public record ProfileWriteGuard(OwnerClaim claim, long expectedRevision) {

    /** The stored revision is not compared. */
    public static final long ANY = Long.MIN_VALUE;
    /** No record may be stored yet. */
    public static final long ABSENT = -1L;
    /** No claim, no comparison: tests and offline tools only. */
    public static final ProfileWriteGuard UNGUARDED = new ProfileWriteGuard(null, ANY);

    public ProfileWriteGuard {
        if (expectedRevision != ANY && expectedRevision < ABSENT) {
            throw new IllegalArgumentException("expected revision " + expectedRevision);
        }
    }

    public static ProfileWriteGuard of(final OwnerClaim claim, final long expectedRevision) {
        return new ProfileWriteGuard(claim, expectedRevision);
    }

    /** A write that carries a claim but compares no revision. */
    public static ProfileWriteGuard claimed(final OwnerClaim claim) {
        return new ProfileWriteGuard(claim, ANY);
    }

    /**
     * Refuses the write with {@link ProfileErrorCode#STALE_STATE} when {@code storedRevision} (the
     * revision found under the repository's lock, {@link #ABSENT} for no record) is not the expected
     * one.
     */
    public void check(final String record, final long storedRevision) {
        if (this.expectedRevision == ANY || storedRevision == this.expectedRevision) return;
        throw new ProfileOperationException(ProfileErrorCode.STALE_STATE, record + " is at revision "
            + describe(storedRevision) + " but the write expected " + describe(this.expectedRevision)
            + "; another operation changed it and nothing was written");
    }

    private static String describe(final long revision) {
        return revision == ABSENT ? "none" : Long.toString(revision);
    }
}
