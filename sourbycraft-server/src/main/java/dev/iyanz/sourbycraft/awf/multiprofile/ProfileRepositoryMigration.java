package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The two offline profile-store migrations: the one-way copy of a format-1 {@link FileProfileRepository}
 * into an empty {@link RedisProfileRepository} ({@link #fileToRedis}), and the in-place format 1 to 2
 * conversion of a store ({@link #toFormat2}, owner decision OD-2).
 *
 * <h2>File to Redis</h2>
 *
 * <p>One-way copy of a {@link FileProfileRepository} into an empty {@link RedisProfileRepository}.
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
 *
 * <h2>Format 1 to format 2</h2>
 *
 * <p>{@link #toFormat2} converts the solo records of a PR #62 store into format-2 records in place,
 * offline, through the repository itself ({@link ProfileRepository} reads, {@code commitAccount},
 * {@code commitProfile} and {@code commitBinding} under {@link CoopWriteGuard#migration} guards) plus the
 * backend steps of {@link ProfileMigrationStore}; a backend that does not implement that interface is
 * refused, never half-migrated. The mode comes from {@code migration.mode}
 * ({@link ProfileSettings.MigrationMode}):</p>
 * <ul>
 *   <li>{@code off}: nothing is read or written;</li>
 *   <li>{@code dry-run}: every record is read (snapshots checksum-verified one at a time) and the result
 *       reports what {@code apply} would convert, the warnings and the refused items; nothing is written;</li>
 *   <li>{@code apply}: refused, writing nothing, while any item is refused (an unfinished or unreadable
 *       journal, an unreadable record). Otherwise it writes and verifies the backup into
 *       {@code migration.backup-dir} first, then marks the store {@code IN_PROGRESS}, converts every
 *       record, verifies the result and only then marks it {@code READY}. A store found
 *       {@code IN_PROGRESS} (a crash mid-migration) is resumed when a complete verified backup of it is in
 *       the backup directory, and refused otherwise. Every step is idempotent, and a failure leaves the
 *       store {@code IN_PROGRESS} (all ordinary writes refused) for a re-run; it is never marked ready
 *       while a format-1 record remains.</li>
 * </ul>
 *
 * <p>Conversion: each profile gets the name {@link CuteNames#fromId} derived for it (the same name the
 * format-1 reader shows), with the smallest free numeric suffix when an older profile of the same account
 * (by creation time, then id) already has it; policy type {@code CLASSIC}; one OWNER member row, the
 * account, with membership epoch {@link #migrationEpoch} (derived from the profile id, so a re-run
 * allocates the same one); metadata revision {@code M} 0. Its format-1 snapshots become member snapshots
 * of that stream with the same ids, revisions and states; the head names the latest exactly
 * ({@code R} = its revision), or is UNINITIALIZED when there was none (no state is synthesised). The
 * binding keeps its revision. Each account keeps its revision {@code A}, gets one alias per profile
 * ({@link CuteNames#alias} of the name, made unique), the epoch of its active profile, and
 * {@code legacyAdoptionClosed}. Pre-profile archives are rewritten as format-2 archives; terminal journals
 * are deleted (they are in the backup); the format-1 profile snapshots are deleted after their profile
 * record is converted. There is no way back except restoring the backup into an isolated store.</p>
 *
 * <p>Both repositories implement {@link ProfileMigrationStore}. The migration is offline on both: every
 * writer stopped. The FILE store enforces that with its exclusive directory lock. The Redis store has no
 * migration lock: {@link ProfileMigrationStore#requireNoOtherWriter} refuses while another process holds
 * a live session or co-op claim in the fabric, its backup is a verified export of every key of the namespace
 * into {@code migration.backup-dir} ({@link RedisProfileBackup}), and each migration write re-checks the
 * {@code meta} marker atomically; a node that holds no claim at that moment is not detected, so stopping
 * every node first is part of the procedure, not optional.</p>
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
        if (!file.storeFormat().legacyFormat()) {
            throw new IOException("the file repository " + file.root() + " is not a format-1 store; the file-to-Redis import copies"
                + " format-1 records only and was not started");
        }
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

    // ================================================================== format 1 -> 2 (OD-2)

    /** The actor recorded on migration operations: no player. */
    static final UUID MIGRATION_ACTOR = new UUID(0L, 0L);
    /** Most warnings or refused items a result lists; the rest are counted. */
    private static final int MAX_LISTED = 64;
    private static final int MAX_WRITTEN_BY = 64;

    /**
     * What the format-2 migration did, or (dry-run) would do.
     *
     * @param mode the configured mode
     * @param outcome see {@link Outcome}
     * @param storeBefore the marker found, as text
     * @param storeAfter the marker left, as text
     * @param accounts accounts converted (dry-run: to convert)
     * @param profiles profiles converted
     * @param bindings bindings converted
     * @param snapshots format-1 snapshots copied into member streams
     * @param heads member stream heads written
     * @param archives pre-profile archives rewritten
     * @param journalsRetired terminal format-1 journals deleted (they are in the backup)
     * @param backup the backup directory written or reused, or {@code null}
     * @param warnings what was converted with a change the operator should know about
     * @param refused what stops the migration; non-empty means nothing was written (or, after the
     *     marker, the store stays {@code IN_PROGRESS} for a re-run)
     */
    record Format2Result(ProfileSettings.MigrationMode mode, Outcome outcome, String storeBefore, String storeAfter,
                         int accounts, int profiles, int bindings, int snapshots, int heads, int archives,
                         int journalsRetired, String backup, List<String> warnings, List<String> refused) {

        Format2Result {
            warnings = List.copyOf(warnings);
            refused = List.copyOf(refused);
        }

        /** How the migration ended. */
        enum Outcome {
            /** {@code migration.mode: off}: nothing read. */
            OFF,
            /** The store is format 2 already; nothing written. */
            ALREADY_FORMAT_2,
            /** Read everything, wrote nothing. */
            DRY_RUN,
            /** Converted, verified and marked READY. */
            MIGRATED,
            /** Refused; see {@link #refused()}. */
            REFUSED
        }

        /** Whether the store is a ready format-2 store now. */
        boolean ready() {
            return this.outcome == Outcome.MIGRATED || this.outcome == Outcome.ALREADY_FORMAT_2;
        }

        /** One operator-facing line for the log and {@code /awf multiprofile status}. */
        String summary() {
            final StringBuilder text = new StringBuilder("format-2 migration (").append(this.mode.id()).append("): ")
                .append(this.outcome);
            if (this.outcome != Outcome.OFF) {
                text.append("; store ").append(this.storeBefore).append(" -> ").append(this.storeAfter);
                text.append("; ").append(this.accounts).append(" account(s), ").append(this.profiles).append(" profile(s), ")
                    .append(this.bindings).append(" binding(s), ").append(this.snapshots).append(" snapshot(s), ")
                    .append(this.heads).append(" stream head(s), ").append(this.archives).append(" archive(s), ")
                    .append(this.journalsRetired).append(" terminal journal(s)")
                    .append(this.outcome == Outcome.DRY_RUN ? " would be converted" : " converted");
            }
            if (this.backup != null) text.append("; backup ").append(this.backup);
            if (!this.warnings.isEmpty()) text.append("; warnings: ").append(String.join(" | ", this.warnings));
            if (!this.refused.isEmpty()) text.append("; refused: ").append(String.join(" | ", this.refused));
            return text.toString();
        }
    }

    /**
     * The membership epoch the migration gives a format-1 profile's owner: a name-based UUID of the
     * profile id, so every run (and a resumed one) allocates the same epoch for the same profile.
     */
    static UUID migrationEpoch(final UUID profileId) {
        return UUID.nameUUIDFromBytes(("sourbycraft:multiprofile:format-2-migration-epoch:" + profileId)
            .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Runs the format 1 → 2 migration in {@code settings.mode()} (see the class comment). Call it at
     * startup, after the repository is open and before any admission; blocking I/O.
     *
     * @param build the build that writes the marker ({@code writtenBy}, cut to 64 characters)
     * @throws IOException when the store's marker cannot be read at all
     */
    static Format2Result toFormat2(final ProfileRepository repository, final ProfileSettings.Migration settings,
                                   final String build) throws IOException {
        final ProfileSettings.MigrationMode mode = settings.mode();
        final Problems problems = new Problems();
        final Counts counts = new Counts();
        if (mode == ProfileSettings.MigrationMode.OFF) {
            return counts.result(mode, Format2Result.Outcome.OFF, "not read", "not read", null, problems);
        }
        final StoreFormat.Marker before = repository.storeFormat();
        final String found = describe(before);
        final StoreFormat.Decision gate = StoreFormat.gate(before);
        if (gate.gate() == StoreFormat.Gate.FORMAT_2) {
            return counts.result(mode, Format2Result.Outcome.ALREADY_FORMAT_2, found, found, null, problems);
        }
        final boolean resuming = before.format() == StoreFormat.CURRENT && before.migration() == StoreFormat.MigrationState.IN_PROGRESS;
        if (gate.gate() == StoreFormat.Gate.REFUSED && !resuming) {
            problems.refuse(gate.reason());
            return counts.result(mode, Format2Result.Outcome.REFUSED, found, found, null, problems);
        }
        if (!(repository instanceof ProfileMigrationStore store)) {
            problems.refuse("the " + repository.describe() + " repository has no format-2 migration in this build; nothing was"
                + " read or written");
            return counts.result(mode, Format2Result.Outcome.REFUSED, found, found, null, problems);
        }
        final Plan plan;
        try {
            plan = plan(repository, store, problems, counts);
        } catch (final IOException | RuntimeException failed) {
            problems.refuse("reading the store failed: " + failed.getMessage());
            return counts.result(mode, Format2Result.Outcome.REFUSED, found, found, null, problems);
        }
        if (mode == ProfileSettings.MigrationMode.DRY_RUN) {
            if (resuming) problems.warn("a migration of this store started earlier and did not finish; apply resumes it");
            return counts.result(mode, Format2Result.Outcome.DRY_RUN, found, found, null, problems);
        }
        if (!problems.refused().isEmpty()) {
            return counts.result(mode, Format2Result.Outcome.REFUSED, found, found, null, problems);
        }
        final String writer = build.length() > MAX_WRITTEN_BY ? build.substring(0, MAX_WRITTEN_BY) : build;
        final String backup;
        try {
            // Offline only: the FILE store holds its directory lock; Redis has no lock and refuses what it can observe.
            store.requireNoOtherWriter();
            if (resuming) {
                backup = store.verifiedBackup(settings.backupDir()).orElse(null);
                if (backup == null) {
                    problems.refuse("a migration of this store is in progress but " + settings.backupDir() + " holds no complete"
                        + " verified backup of it; nothing was written. Restore the store from a backup by hand");
                    return counts.result(mode, Format2Result.Outcome.REFUSED, found, found, null, problems);
                }
            } else {
                backup = store.backup(settings.backupDir());
                repository.writeStoreFormat(StoreFormat.Marker.migrating(writer));
            }
        } catch (final IOException | RuntimeException failed) {
            problems.refuse("the writer check, the backup or the migration marker failed (" + failed.getMessage()
                + "); the store records were not changed");
            return counts.result(mode, Format2Result.Outcome.REFUSED, found, describe(repository.storeFormat()), null, problems);
        }
        counts.reset();
        final CoopOperation op = new CoopOperation(UUID.randomUUID(), CoopOperation.Kind.MIGRATION, MIGRATION_ACTOR,
            CoopOperation.digest("format-2 migration", writer, repository.describe()), System.currentTimeMillis());
        try {
            convert(repository, store, plan, op, counts);
            verify(repository, store, plan, problems);
            if (problems.refused().isEmpty()) repository.writeStoreFormat(StoreFormat.Marker.ready(writer));
        } catch (final IOException | RuntimeException failed) {
            problems.refuse("converting the store stopped (" + failed.getMessage() + "); it stays marked IN_PROGRESS with every"
                + " ordinary write refused. Fix the cause and run apply again (it resumes), or restore the backup " + backup);
        }
        final String after = describe(repository.storeFormat());
        if (!problems.refused().isEmpty()) {
            return counts.result(mode, Format2Result.Outcome.REFUSED, found, after, backup, problems);
        }
        return counts.result(mode, Format2Result.Outcome.MIGRATED, found, after, backup, problems);
    }

    /** One profile of the plan, in conversion order. */
    private record Planned(UUID id, UUID owner, String name, UUID epoch, boolean converted) {}

    private record Plan(List<Planned> profiles, Map<UUID, Planned> byId, List<UUID> accounts) {}

    /** Reads every record once; records refused items and warnings; fills the dry-run counts. */
    private static Plan plan(final ProfileRepository repository, final ProfileMigrationStore store, final Problems problems,
                             final Counts counts) throws IOException {
        for (final String journal : repository.quarantinedJournals()) {
            problems.refuse("the journal " + journal + " is unreadable and set aside; inspect and delete it first");
        }
        for (final String record : store.unconvertibleRecords(16)) {
            problems.refuse("the record " + record + " has no conversion and would fail the final verification; move it out of the"
                + " store (or delete it) before migrating");
        }
        for (final ProfileTransition journal : repository.transitions()) {
            if (journal.terminal()) {
                counts.journals++;
            } else {
                problems.refuse("the switch journal " + journal.transitionId() + " of " + journal.playerId() + " is "
                    + journal.phase() + "; let the player rejoin on the old build (or resolve it) before migrating");
            }
        }
        final Map<UUID, List<AuroraProfile>> byOwner = new TreeMap<>();
        for (final UUID id : store.migrationProfileIds()) {
            final AuroraProfile profile;
            try {
                profile = repository.profile(id).orElse(null);
            } catch (final IOException unreadable) {
                problems.refuse("profile " + id + " cannot be read: " + unreadable.getMessage());
                continue;
            }
            if (profile == null) continue;
            byOwner.computeIfAbsent(profile.accountId(), ignored -> new ArrayList<>()).add(profile);
            if (!profile.legacy()) continue;
            counts.profiles++;
            counts.heads++;
            try {
                for (final UUID snapshotId : store.legacyProfileSnapshotIds(id)) {
                    if (repository.snapshotById(id, snapshotId).isEmpty()) {
                        problems.refuse("snapshot " + snapshotId + " of profile " + id + " disappeared while it was read");
                    }
                    counts.snapshots++;
                }
                if (repository.binding(id).isPresent()) counts.bindings++;
            } catch (final IOException unreadable) {
                problems.refuse("a snapshot or the binding of profile " + id + " cannot be read: " + unreadable.getMessage());
            }
        }
        final List<Planned> order = new ArrayList<>();
        final Map<UUID, Planned> byId = new HashMap<>();
        for (final Map.Entry<UUID, List<AuroraProfile>> owner : byOwner.entrySet()) {
            final List<AuroraProfile> owned = owner.getValue();
            final Map<UUID, String> names = legacyNames(owned);
            owned.sort(Comparator.comparingLong(AuroraProfile::createdAtMillis).thenComparing(AuroraProfile::id));
            for (final AuroraProfile profile : owned) {
                final Planned planned;
                if (!profile.legacy()) {
                    final ProfileMember row = profile.owner();
                    planned = new Planned(profile.id(), profile.accountId(), profile.name(),
                        row == null ? migrationEpoch(profile.id()) : row.epoch(), true);
                } else {
                    final String base = CuteNames.fromId(profile.id());
                    final String name = names.get(profile.id());
                    if (!name.equals(base)) {
                        problems.warn("profile " + profile.id() + " of " + owner.getKey() + " is named " + name + " (" + base
                            + " is taken by an older profile of that account)");
                    }
                    planned = new Planned(profile.id(), profile.accountId(), name, migrationEpoch(profile.id()), false);
                }
                order.add(planned);
                byId.put(planned.id(), planned);
            }
        }
        final List<UUID> accounts = new ArrayList<>();
        for (final UUID id : store.migrationAccountIds()) {
            final PlayerAccount account;
            try {
                account = repository.loadAccount(id, PlayerAccount.DEFAULT_SLOTS);
            } catch (final IOException unreadable) {
                problems.refuse("account " + id + " cannot be read: " + unreadable.getMessage());
                continue;
            }
            accounts.add(id);
            if (account.legacyAdoptionClosed()) continue;
            counts.accounts++;
            for (final UUID profileId : account.profileIds()) {
                final Planned planned = byId.get(profileId);
                if (planned == null) {
                    problems.warn("account " + id + " lists profile " + profileId + ", which has no record; it is kept without"
                        + " an alias");
                } else if (!planned.owner().equals(id)) {
                    problems.warn("account " + id + " lists profile " + profileId + " owned by " + planned.owner());
                }
            }
            if (account.activeProfile() != null && !byId.containsKey(account.activeProfile())) {
                problems.warn("the active profile " + account.activeProfile() + " of " + id + " has no record; no epoch is set");
            }
        }
        counts.archives = store.legacyArchiveCount();
        return new Plan(List.copyOf(order), Map.copyOf(byId), List.copyOf(accounts));
    }

    /**
     * The names the migration gives the format-1 (legacy) profiles of one account, by profile id: each gets
     * {@link CuteNames#fromId}, and when that name is taken (by a format-2 profile, or by an older legacy profile
     * of the account: creation time, then id) the smallest free numeric suffix. Deterministic, so the engine
     * shows a legacy profile under the same name before and after the migration.
     */
    static Map<UUID, String> legacyNames(final List<AuroraProfile> ownedByOneAccount) {
        final List<AuroraProfile> owned = new ArrayList<>(ownedByOneAccount);
        owned.sort(Comparator.comparingLong(AuroraProfile::createdAtMillis).thenComparing(AuroraProfile::id));
        final Set<String> taken = new HashSet<>();
        for (final AuroraProfile profile : owned) {
            if (!profile.legacy()) taken.add(CuteNames.key(profile.name()));
        }
        final Map<UUID, String> names = new HashMap<>();
        for (final AuroraProfile profile : owned) {
            if (!profile.legacy()) continue;
            final String name = freeName(CuteNames.fromId(profile.id()), taken);
            taken.add(CuteNames.key(name));
            names.put(profile.id(), name);
        }
        return names;
    }

    /** {@code base}, or {@code base N} with the smallest free {@code N} from 2. */
    private static String freeName(final String base, final Set<String> taken) {
        if (!taken.contains(CuteNames.key(base))) return base;
        for (int suffix = 2; suffix <= CuteNames.MAX_SUFFIX; suffix++) {
            final String candidate = base + " " + suffix;
            if (CuteNames.valid(candidate) && !taken.contains(CuteNames.key(candidate))) return candidate;
        }
        throw new IllegalStateException("no free name for " + base + " among " + taken.size() + " names");
    }

    private static void convert(final ProfileRepository repository, final ProfileMigrationStore store, final Plan plan,
                                final CoopOperation op, final Counts counts) throws IOException {
        for (final Planned planned : plan.profiles()) {
            final UUID id = planned.id();
            final AuroraProfile current = repository.profile(id)
                .orElseThrow(() -> new IOException("profile " + id + " disappeared during the migration"));
            if (current.legacy()) {
                final UUID owner = current.accountId();
                final UUID epoch = planned.epoch();
                ProfileSnapshot latest = null;
                for (final UUID snapshotId : store.legacyProfileSnapshotIds(id)) {
                    final ProfileSnapshot flat = repository.snapshotById(id, snapshotId)
                        .orElseThrow(() -> new IOException("snapshot " + snapshotId + " of " + id + " disappeared"));
                    final ProfileSnapshot member = new ProfileSnapshot(id, owner, epoch, flat.snapshotId(), flat.schemaVersion(),
                        flat.revision(), flat.state(), flat.checksum(), flat.createdAtMillis(), null);
                    store.writeMigratedMemberSnapshot(member);
                    counts.snapshots++;
                    latest = member;
                }
                store.writeMigratedHead(latest == null ? MemberStateHead.uninitialized(id, owner, epoch)
                    : new MemberStateHead(id, owner, epoch, latest.revision(), MemberStateHead.State.READY, latest.snapshotId(),
                        latest.checksum(), null, null));
                counts.heads++;
                long bindingRevision = 0L;
                final Optional<ProfileWorldBinding> binding = repository.binding(id);
                if (binding.isPresent()) {
                    final OptionalLong stored = repository.bindingRevision(id);
                    bindingRevision = stored.isPresent() ? stored.getAsLong() : 1L;
                    committed(repository.commitBinding(binding.get(), bindingRevision,
                        CoopWriteGuard.migration(op, List.of(Expected.record(id, bindingRevision)))), "the binding of " + id);
                    counts.bindings++;
                }
                final AuroraProfile converted = new AuroraProfile(id, owner, current.type(),
                    dev.iyanz.sourbycraft.api.profile.ProfileType.CLASSIC, planned.name(), current.metadata(),
                    current.createdAtMillis(), 0L, current.status(),
                    List.of(new ProfileMember(owner, epoch, ProfileRole.OWNER, current.createdAtMillis(), current.createdAtMillis())),
                    bindingRevision, null);
                committed(repository.commitProfile(converted,
                    CoopWriteGuard.migration(op, List.of(Expected.metadata(id, current.revision())))), "profile " + id);
                counts.profiles++;
            }
            store.dropLegacyProfileSnapshots(id);
        }
        for (final UUID id : plan.accounts()) {
            final PlayerAccount account = repository.loadAccount(id, PlayerAccount.DEFAULT_SLOTS);
            if (account.legacyAdoptionClosed()) continue;
            final Map<UUID, String> aliases = new LinkedHashMap<>();
            final Set<String> taken = new HashSet<>();
            for (final UUID profileId : account.profileIds()) {
                final Planned planned = plan.byId().get(profileId);
                if (planned == null) continue;
                final String alias = CuteNames.allocateAlias(CuteNames.alias(planned.name()), taken);
                taken.add(alias);
                aliases.put(profileId, alias);
            }
            UUID activeEpoch = null;
            if (account.activeProfile() != null) {
                final Planned active = plan.byId().get(account.activeProfile());
                if (active != null && active.owner().equals(id)) activeEpoch = active.epoch();
            }
            final PlayerAccount converted = new PlayerAccount(id, account.profileIds(), aliases, account.activeProfile(), activeEpoch,
                account.slotLimit(), account.revision(), account.sessionGeneration(), null, List.of(), true);
            committed(repository.commitAccount(converted,
                CoopWriteGuard.migration(op, List.of(Expected.account(id, account.revision())))), "account " + id);
            counts.accounts++;
        }
        counts.archives = store.convertLegacyArchives();
        counts.journals = store.retireTerminalJournals();
    }

    /** Reads the converted store back: nothing format 1 left, every stream head exact, every account consistent. */
    private static void verify(final ProfileRepository repository, final ProfileMigrationStore store, final Plan plan,
                               final Problems problems) throws IOException {
        for (final String record : store.legacyRecordsRemaining(16)) {
            problems.refuse("the record " + record + " is still format 1");
        }
        for (final Planned planned : plan.profiles()) {
            final AuroraProfile profile = repository.profile(planned.id()).orElse(null);
            if (profile == null || profile.legacy()) {
                problems.refuse("profile " + planned.id() + " was not converted");
                continue;
            }
            final ProfileMember owner = profile.owner();
            if (owner == null || !owner.epoch().equals(planned.epoch()) || !profile.name().equals(planned.name())) {
                problems.refuse("profile " + planned.id() + " does not read back with its planned owner epoch and name");
                continue;
            }
            final MemberStateHead head = repository.memberHead(planned.id(), owner.playerId(), owner.epoch()).orElse(null);
            if (head == null) {
                problems.refuse("profile " + planned.id() + " has no member stream head");
            } else if (head.state() == MemberStateHead.State.READY) {
                final ProfileSnapshot snapshot = repository.memberSnapshot(planned.id(), owner.playerId(), owner.epoch(),
                    head.snapshotId()).orElse(null);
                if (!head.matches(snapshot)) {
                    problems.refuse("the head of profile " + planned.id() + " does not name a snapshot that reads back exactly");
                }
            }
        }
        for (final UUID id : plan.accounts()) {
            final PlayerAccount account = repository.loadAccount(id, PlayerAccount.DEFAULT_SLOTS);
            if (!account.legacyAdoptionClosed()) {
                problems.refuse("account " + id + " was not converted");
                continue;
            }
            if (account.activeProfile() == null) continue;
            final Planned active = plan.byId().get(account.activeProfile());
            if (active != null && active.owner().equals(id) && !Objects.equals(account.activeEpoch(), active.epoch())) {
                problems.refuse("account " + id + " does not name the epoch of its active profile");
            }
        }
    }

    private static void committed(final ProfileRepository.CommitResult result, final String what) throws IOException {
        if (result.unknown()) {
            throw new IOException(what + " may or may not have been converted (" + result.detail() + ")");
        }
    }

    private static String describe(final StoreFormat.Marker marker) {
        return "format " + marker.format() + " (" + marker.migration().name().toLowerCase(java.util.Locale.ROOT) + ")";
    }

    /** Mutable counters of one run. */
    private static final class Counts {

        int accounts;
        int profiles;
        int bindings;
        int snapshots;
        int heads;
        int archives;
        int journals;

        void reset() {
            this.accounts = 0;
            this.profiles = 0;
            this.bindings = 0;
            this.snapshots = 0;
            this.heads = 0;
            this.archives = 0;
            this.journals = 0;
        }

        Format2Result result(final ProfileSettings.MigrationMode mode, final Format2Result.Outcome outcome, final String before,
                             final String after, final String backup, final Problems problems) {
            return new Format2Result(mode, outcome, before, after, this.accounts, this.profiles, this.bindings, this.snapshots,
                this.heads, this.archives, this.journals, backup, problems.warnings(), problems.refused());
        }
    }

    /** Bounded lists of warnings and refused items. */
    private static final class Problems {

        private final List<String> warnings = new ArrayList<>();
        private final List<String> refused = new ArrayList<>();
        private int moreWarnings;
        private int moreRefused;

        void warn(final String warning) {
            if (this.warnings.size() < MAX_LISTED) this.warnings.add(warning); else this.moreWarnings++;
        }

        void refuse(final String item) {
            if (this.refused.size() < MAX_LISTED) this.refused.add(item); else this.moreRefused++;
        }

        List<String> warnings() {
            return with(this.warnings, this.moreWarnings);
        }

        List<String> refused() {
            return with(this.refused, this.moreRefused);
        }

        private static List<String> with(final List<String> listed, final int more) {
            if (more == 0) return List.copyOf(listed);
            final List<String> all = new ArrayList<>(listed);
            all.add("... and " + more + " more");
            return List.copyOf(all);
        }
    }
}
