package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One-way copy of a {@link FileProfileRepository} into an empty {@link RedisProfileRepository}.
 *
 * <p>Refused when the Redis repository already holds data: anything committed to it, or an earlier
 * finished import. Every import write checks that again atomically, so a profile write that slips in
 * stops the copy instead of being overwritten. Records are copied as they are (the same frames and
 * checksums), then read back through the Redis repository's normal read path, which verifies every
 * checksum, and compared with the file records. Only then is the import marked finished, which also
 * lifts the write refusal the Redis repository applies while unmigrated profiles exist.</p>
 *
 * <p>The file directory is opened like the file repository opens it (under its exclusive lock; an
 * unreadable journal is set aside as {@code *.corrupt} there) and is otherwise only read: nothing in
 * it is deleted or rewritten, so it stays a complete copy to go back to. A failed copy leaves partial
 * records in Redis but no finished mark: writes stay refused and the migration can run again, which
 * rewrites the same keys. Unreadable journals are not copied and are listed in the report.</p>
 *
 * <p>Blocking I/O on both stores: run it on an administrative I/O thread, with no player online.</p>
 */
final class ProfileRepositoryMigration {

    private ProfileRepositoryMigration() {}

    /** What was copied and verified; {@code skipped} names the unreadable journals left behind. */
    record Report(int accounts, int profiles, int bindings, int snapshots, int archives, int journals,
                  List<String> skipped) {

        Report {
            skipped = List.copyOf(skipped);
        }

        String summary() {
            return accounts + " account(s), " + profiles + " profile(s), " + bindings + " binding(s), " + snapshots
                + " snapshot(s), " + archives + " pre-profile archive(s) and " + journals + " journal(s) copied and verified"
                + (skipped.isEmpty() ? "" : "; " + skipped.size() + " unreadable journal(s) not copied: "
                    + String.join(", ", skipped));
        }
    }

    static Report fileToRedis(final FileProfileRepository file, final RedisProfileRepository redis) throws IOException {
        final List<UUID> accountIds = file.accountIds();
        final List<UUID> profileIds = file.profileIds();
        if (accountIds.isEmpty() && profileIds.isEmpty()) {
            throw new IOException("the file repository " + file.root() + " holds no account and no profile; nothing to migrate");
        }
        redis.beginImport();
        int archives = 0;
        for (final UUID playerId : accountIds) {
            redis.importAccount(file.loadAccount(playerId, PlayerAccount.DEFAULT_SLOTS));
            final List<ProfileSnapshot> legacy = file.legacySnapshots(playerId);
            for (int i = 0; i < legacy.size(); i++) {
                redis.importLegacy(legacy.get(i), i == legacy.size() - 1);
                archives++;
            }
        }
        int profiles = 0;
        int bindings = 0;
        int snapshots = 0;
        for (final UUID profileId : profileIds) {
            final Optional<AuroraProfile> profile = file.profile(profileId);
            if (profile.isEmpty()) continue;
            redis.importProfile(profile.get());
            profiles++;
            final Optional<ProfileWorldBinding> binding = file.binding(profileId);
            if (binding.isPresent()) {
                redis.importBinding(binding.get());
                bindings++;
            }
            // Oldest first, so the last one is the profile's latest, as latestSnapshot reads it.
            final List<ProfileSnapshot> all = file.snapshots(profileId);
            for (int i = 0; i < all.size(); i++) {
                redis.importSnapshot(all.get(i), i == all.size() - 1);
                snapshots++;
            }
        }
        final List<ProfileTransition> journals = file.transitions();
        for (final ProfileTransition journal : journals) redis.importTransition(journal);
        final List<String> skipped = new ArrayList<>(file.quarantinedJournals());
        verify(file, redis, accountIds, profileIds, journals);
        final Report report = new Report(accountIds.size(), profiles, bindings, snapshots, archives, journals.size(), skipped);
        redis.finishImport(report.summary());
        return report;
    }

    /** Reads every copied record back from Redis (checksums verified on read) and compares it with the file one. */
    private static void verify(final FileProfileRepository file, final RedisProfileRepository redis,
                               final List<UUID> accountIds, final List<UUID> profileIds,
                               final List<ProfileTransition> journals) throws IOException {
        for (final UUID playerId : accountIds) {
            same("account " + playerId, file.loadAccount(playerId, PlayerAccount.DEFAULT_SLOTS),
                redis.loadAccount(playerId, PlayerAccount.DEFAULT_SLOTS));
            final Optional<ProfileSnapshot> archived = file.legacySnapshot(playerId);
            if (archived.isPresent()) {
                sameSnapshot("the pre-profile archive of " + playerId, archived.get(),
                    redis.legacySnapshot(playerId).orElse(null));
            }
        }
        for (final UUID profileId : profileIds) {
            final Optional<AuroraProfile> profile = file.profile(profileId);
            if (profile.isEmpty()) continue;
            same("profile " + profileId, profile, redis.profile(profileId));
            same("the binding of " + profileId, file.binding(profileId), redis.binding(profileId));
            final List<ProfileSnapshot> all = file.snapshots(profileId);
            for (final ProfileSnapshot snapshot : all) {
                sameSnapshot("snapshot " + snapshot.snapshotId(), snapshot,
                    redis.snapshotById(profileId, snapshot.snapshotId()).orElse(null));
            }
            if (!all.isEmpty()) {
                sameSnapshot("the latest snapshot of " + profileId, all.getLast(),
                    redis.latestSnapshot(profileId).orElse(null));
            }
        }
        for (final ProfileTransition journal : journals) {
            same("transition journal " + journal.transitionId(), Optional.of(journal),
                redis.transition(journal.transitionId()));
        }
    }

    private static void same(final String what, final Object expected, final Object copied) throws IOException {
        if (!Objects.equals(expected, copied)) {
            throw new IOException("verification failed: " + what + " reads back differently from Redis; the import was"
                + " not marked finished and profile writes stay refused");
        }
    }

    private static void sameSnapshot(final String what, final ProfileSnapshot expected, final ProfileSnapshot copied)
        throws IOException {
        final boolean equal = copied != null && expected.profileId().equals(copied.profileId())
            && expected.snapshotId().equals(copied.snapshotId()) && expected.schemaVersion() == copied.schemaVersion()
            && expected.revision() == copied.revision() && expected.checksum().equals(copied.checksum())
            && expected.createdAtMillis() == copied.createdAtMillis() && Arrays.equals(expected.state(), copied.state());
        if (!equal) {
            throw new IOException("verification failed: " + what + " reads back differently from Redis; the import was"
                + " not marked finished and profile writes stay refused");
        }
    }
}
