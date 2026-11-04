package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The backend-specific steps of the offline format 1 → 2 migration ({@link ProfileRepositoryMigration#toFormat2}).
 *
 * <p>Everything a {@link ProfileRepository} method can express goes through the repository: records are
 * read with the format-1 readers and written with {@code commitAccount}, {@code commitProfile} and
 * {@code commitBinding} under {@link CoopWriteGuard#migration} guards, which a store accepts only while its
 * marker says {@link StoreFormat.MigrationState#IN_PROGRESS}. What has no repository method is here:
 * listing the stored ids, the verified backup, installing a migrated member stream (snapshots plus head
 * without the save protocol), rewriting the pre-profile archives, dropping the converted format-1
 * snapshots and journals, and the final scan for records that are still format 1.</p>
 *
 * <p>Every write method is refused unless the store's marker is {@code IN_PROGRESS}, and every one is
 * idempotent, so a migration interrupted at any point can run again. {@link FileProfileRepository} and
 * {@link RedisProfileRepository} implement it.</p>
 *
 * <p>The migration is offline: every writer of the store must be stopped. The FILE store enforces this
 * with its exclusive directory lock. Redis has no migration lock, so {@link #requireNoOtherWriter}
 * refuses what it can observe (a live session or co-op claim of another process) and the rest is the
 * operator's procedure (stop every node first).</p>
 */
interface ProfileMigrationStore {

    /**
     * Refuses, naming the holder, when another process observably still writes this store (a live
     * session, profile or player claim of another node or incarnation). Called before the backup and
     * again before a resumed conversion. A store with its own exclusive lock returns at once.
     */
    void requireNoOtherWriter() throws IOException;

    /** Every stored account id (no pre-profile archives), ascending. */
    List<UUID> migrationAccountIds() throws IOException;

    /** Every stored profile record id, tombstones included, ascending. */
    List<UUID> migrationProfileIds() throws IOException;

    /**
     * The ids of a profile's format-1 snapshots, oldest first in {@link ProfileRepository#latestSnapshot}'s
     * order (the last is the latest); read each with {@link ProfileRepository#snapshotById}.
     */
    List<UUID> legacyProfileSnapshotIds(UUID profileId) throws IOException;

    /** How many pre-profile archives are still format 1. */
    int legacyArchiveCount() throws IOException;

    /** Rewrites every format-1 pre-profile archive as a format-2 archive (same snapshot); returns how many. */
    int convertLegacyArchives() throws IOException;

    /** Writes one migrated member snapshot (an immutable blob; the same bytes again are a no-op). */
    void writeMigratedMemberSnapshot(ProfileSnapshot snapshot) throws IOException;

    /** Writes a migrated stream head; refused when a different head is already stored for that stream. */
    void writeMigratedHead(MemberStateHead head) throws IOException;

    /** Deletes a converted profile's format-1 snapshots; refused while its profile record is still format 1. */
    int dropLegacyProfileSnapshots(UUID profileId) throws IOException;

    /** Deletes every terminal format-1 journal (kept in the backup); refused while a non-terminal one exists. */
    int retireTerminalJournals() throws IOException;

    /** Writes and verifies the pre-migration backup into {@code backupDir}; returns where it is. */
    String backup(String backupDir) throws IOException;

    /** The newest complete verified backup of this store in {@code backupDir}, if any. */
    Optional<String> verifiedBackup(String backupDir) throws IOException;

    /** Up to {@code limit} records that are not format 2 (empty when the store holds format-2 records only). */
    List<String> legacyRecordsRemaining(int limit) throws IOException;

    /**
     * Format-1 records the conversion has no step for, so {@link #legacyRecordsRemaining} would still list
     * them after an apply (at most {@code limit}); the plan refuses them before the backup. The Redis store
     * converts every key it enumerates and reports none here.
     */
    default List<String> unconvertibleRecords(int limit) throws IOException {
        return List.of();
    }
}
