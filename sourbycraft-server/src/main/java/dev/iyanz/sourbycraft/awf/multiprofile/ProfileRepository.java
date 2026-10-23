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
 */
public interface ProfileRepository extends AutoCloseable {

    /** The account, or an empty account with {@code defaultSlots} when nothing was stored yet. */
    PlayerAccount loadAccount(UUID playerId, int defaultSlots) throws IOException;

    /** Writes the account atomically. The caller is responsible for revision compare-and-set. */
    void saveAccount(PlayerAccount account) throws IOException;

    Optional<AuroraProfile> profile(UUID profileId) throws IOException;

    void saveProfile(AuroraProfile profile) throws IOException;

    /** Every stored profile of an account, in account order; missing or deleted entries are skipped. */
    List<AuroraProfile> profiles(UUID accountId) throws IOException;

    Optional<ProfileWorldBinding> binding(UUID profileId) throws IOException;

    void saveBinding(ProfileWorldBinding binding) throws IOException;

    /** The snapshot with the highest revision, checksum-verified. */
    Optional<ProfileSnapshot> latestSnapshot(UUID profileId) throws IOException;

    /** Writes a snapshot durably, then prunes older snapshots beyond {@code retain} (minimum 1). */
    void saveSnapshot(ProfileSnapshot snapshot, int retain) throws IOException;

    /** The snapshot at a revision, checksum-verified; used by recovery. */
    Optional<ProfileSnapshot> snapshot(UUID profileId, long revision) throws IOException;

    Optional<ProfileTransition> transition(UUID transitionId) throws IOException;

    void saveTransition(ProfileTransition transition) throws IOException;

    void deleteTransition(UUID transitionId) throws IOException;

    /** Every journal on disk, oldest first; callers filter on {@link ProfileTransition#terminal()}. */
    List<ProfileTransition> transitions() throws IOException;

    /** Removes the profile, its binding and its snapshots. Never touches AWF world data. */
    void deleteProfile(UUID profileId) throws IOException;

    /** Removes the binding and snapshots but keeps the profile record (a tombstone). */
    void deleteProfileData(UUID profileId) throws IOException;

    @Override
    void close();
}
