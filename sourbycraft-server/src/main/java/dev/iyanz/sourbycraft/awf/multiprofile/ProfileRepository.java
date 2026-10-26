package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable storage of profiles, accounts, bindings, snapshots and recovery journals.
 *
 * <p>Every method is a synchronous I/O call and must be used on an I/O worker, never on a region,
 * entity, global-tick or Netty thread. Implementations must be safe for concurrent calls.
 * The interface deliberately has no transaction type: the switch protocol is recoverable, not
 * atomic, and says so in its documentation.</p>
 *
 * <p>Every mutation takes a {@link ProfileWriteGuard}: the claim the caller validated and the
 * revision it read. The repository is the authoritative commit, so an implementation shared between
 * servers must verify both atomically with the write and refuse otherwise. The file implementation
 * compares revisions locally and is not a distributed fence; {@link RedisProfileRepository} checks
 * claim and revision inside the one Lua script that applies each write.</p>
 */
public interface ProfileRepository extends AutoCloseable {

    /** Short description for logs and {@code /awf multiprofile status}; does no I/O and holds no credentials. */
    default String describe() {
        return getClass().getSimpleName();
    }

    /**
     * Whether a mutation that failed with an {@link IOException} may still have been applied: a
     * remote store can run a write and lose the reply. A caller that must know the outcome (the
     * switch commit) has to establish it before it compensates. A local store's failed write wrote
     * nothing.
     */
    default boolean remote() {
        return false;
    }

    /** The account, or an empty account (revision 0) with {@code defaultSlots} when none is stored. */
    PlayerAccount loadAccount(UUID playerId, int defaultSlots) throws IOException;

    /**
     * Writes the account atomically. The guard's expected revision is the revision of the account the
     * caller read ({@code 0} for one that was never stored).
     */
    void saveAccount(PlayerAccount account, ProfileWriteGuard guard) throws IOException;

    Optional<AuroraProfile> profile(UUID profileId) throws IOException;

    /** Writes the profile record; the guard expects the stored profile revision, or {@link ProfileWriteGuard#ABSENT}. */
    void saveProfile(AuroraProfile profile, ProfileWriteGuard guard) throws IOException;

    /** Every stored profile of an account, in account order; missing entries are skipped. */
    List<AuroraProfile> profiles(UUID accountId) throws IOException;

    Optional<ProfileWorldBinding> binding(UUID profileId) throws IOException;

    void saveBinding(ProfileWorldBinding binding, ProfileWriteGuard guard) throws IOException;

    /** The snapshot with the highest revision (the newest of equal revisions), checksum-verified. */
    Optional<ProfileSnapshot> latestSnapshot(UUID profileId) throws IOException;

    /** One exact snapshot, checksum-verified; the rollback point a journal names. */
    Optional<ProfileSnapshot> snapshotById(UUID profileId, UUID snapshotId) throws IOException;

    /**
     * Publishes a snapshot and the profile record that advances to its revision: the snapshot is
     * durable before the record is written. The guard expects the profile revision the snapshot was
     * derived from. Older snapshots beyond {@code retain} (minimum 1) are pruned, except those a
     * pending journal of the account still names.
     */
    void publishSnapshot(AuroraProfile next, ProfileSnapshot snapshot, int retain, ProfileWriteGuard guard)
        throws IOException;

    /**
     * Archives the state a player had before any profile was applied (the snapshot's profile id is the
     * player id). Written by the player's first switch; an earlier archive is kept, never overwritten,
     * and {@link #legacySnapshot} returns the newest.
     */
    void saveLegacySnapshot(UUID playerId, ProfileSnapshot snapshot, ProfileWriteGuard guard) throws IOException;

    /** The archived pre-profile state of a player, checksum-verified. */
    Optional<ProfileSnapshot> legacySnapshot(UUID playerId) throws IOException;

    Optional<ProfileTransition> transition(UUID transitionId) throws IOException;

    /**
     * The journals of one player, oldest first. Fails when one of them cannot be read: that player's
     * journal state is unknown, so callers refuse to act for them, while other players are unaffected.
     */
    List<ProfileTransition> transitionsFor(UUID playerId) throws IOException;

    /** Every readable journal, oldest first, for status output; unreadable ones are skipped. */
    List<ProfileTransition> transitions() throws IOException;

    /** Journal files set aside as unreadable, for status output. */
    default List<String> quarantinedJournals() {
        return List.of();
    }

    void saveTransition(ProfileTransition transition, ProfileWriteGuard guard) throws IOException;

    void deleteTransition(UUID transitionId, ProfileWriteGuard guard) throws IOException;

    /** Removes the binding and snapshots but keeps the profile record (a tombstone). Never touches AWF world data. */
    void deleteProfileData(UUID profileId, ProfileWriteGuard guard) throws IOException;

    @Override
    void close();
}
