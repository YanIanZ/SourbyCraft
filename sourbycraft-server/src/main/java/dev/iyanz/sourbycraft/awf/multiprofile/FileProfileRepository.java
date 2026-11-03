package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Domain;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileIntent.BlobRef;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileIntent.SnapshotRef;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A file-backed {@link ProfileRepository} for one server, for both store formats.
 *
 * <p>Layout under the configured directory:</p>
 * <pre>
 * repository.lock                       held by the process using the directory
 * format.awfm                           store format marker ({@link StoreFormat}); absent = format 1
 * accounts/&lt;player&gt;.awfp
 * accounts/&lt;player&gt;.legacy.awfp        the state the player had before their first profile
 * accounts/&lt;player&gt;.legacy.&lt;millis&gt;.awfp  an earlier such archive, kept when a later one is written
 * profiles/&lt;profile&gt;.awfp
 * bindings/&lt;profile&gt;.awfp
 * snapshots/&lt;profile&gt;/&lt;revision&gt;-&lt;snapshot&gt;.awfp            format 1: profile snapshots
 * transitions/&lt;player&gt;.&lt;transition&gt;.awfp  (journals of earlier builds: &lt;transition&gt;.awfp)
 * transitions/*.corrupt                 journals that could not be read, set aside
 *
 * format 2 only:
 * snapshots/&lt;profile&gt;/&lt;member&gt;/&lt;epoch&gt;/&lt;R&gt;-&lt;snapshot&gt;.awfp  member snapshots
 * members/&lt;profile&gt;/&lt;member&gt;/&lt;epoch&gt;/head.awfp              member stream heads
 * shared/&lt;profile&gt;.awfp, invites/&lt;id&gt;.awfp, votes/&lt;id&gt;.awfp, salvage/&lt;id&gt;.awfp
 * intents/&lt;operation&gt;.awfp            intents, kept permanently (replay protection)
 * receipts/&lt;resource&gt;/&lt;operation&gt;.awfp  one receipt page per operation and resource (point lookup)
 * indexes/&lt;kind&gt;/&lt;resource&gt;[/&lt;member&gt;/&lt;epoch&gt;]/&lt;page&gt;.awfp  {@link FileProfileIndex}
 * staging/&lt;operation&gt;/                 immutable after-image blobs and the staged copy of an intent
 * </pre>
 *
 * <p>Each file is a framed record ({@link ProfileRecords}, the format {@link RedisProfileRepository}
 * stores too): magic, format, type, revision, payload length, payload and the SHA-256 of the
 * payload. An unreadable profile, account or snapshot raises {@link IOException} naming the file instead
 * of being ignored. An unreadable journal is renamed to {@code *.corrupt} with one ERROR; until an
 * operator deletes it, {@link #transitionsFor} fails for that player only.</p>
 *
 * <h2>Store format gate</h2>
 *
 * <p>The marker is read at open and re-read before every read and write. A store without a marker is
 * format 1 ({@link StoreFormat.Marker#legacy()}): the format-1 methods behave as in Build 50 (PR #62),
 * reading and writing format-1 frames only, and every format-2 method refuses with
 * {@link ProfileErrorCode#MIGRATION_REQUIRED}. A ready format-2 store reads and writes format-2 frames
 * only: the format-1 record writers ({@code saveAccount}, {@code saveProfile}, {@code saveBinding},
 * {@code publishSnapshot}, {@code deleteProfileData}) and the flat snapshot readers refuse with
 * {@link ProfileErrorCode#STORE_FORMAT_REFUSED}; {@code saveTransition} writes format-2 journals (it
 * requires the co-op fields) and {@code saveLegacySnapshot} format-2 archives. While the marker says a
 * migration is in progress, reads accept either format and only {@link CoopWriteGuard#migration} writes
 * and the {@link ProfileMigrationStore} steps are accepted. A marker of an unknown format or schema, an
 * unparseable marker, a marker that disappeared after this process saw format 2, or format-2 directories
 * without a marker refuse every read and write with {@code STORE_FORMAT_REFUSED}, and the journals are
 * then not even indexed (nothing is renamed in a store this build does not understand).</p>
 *
 * <h2>Format-2 commits</h2>
 *
 * <p>Every format-2 mutation runs under this object's lock, which is the commit lock: it re-checks the
 * marker, resolves the operation id against receipts and intents (replay: {@code REPLAYED}; same id with
 * another digest: {@code INVALID_REQUEST}), compares every revision the guard names with the stored one
 * ({@code STALE_STATE}), refuses a record reserved by another operation, checks the resource identity of
 * each required claim, then, immediately before the write that is the logical commit, asks the attached
 * {@link FileProfileClaims} whether each claim is live ({@code OWNERSHIP_CONFLICT} otherwise; with no
 * validator attached a write that carries claims is refused). Writes are temp file + force + atomic
 * rename + directory force ({@link FileProfileIo#writeDurable}); a failure after the rename started is
 * returned as {@link CommitResult.Status#UNKNOWN}, a failure before it as {@link IOException}. A
 * filesystem without atomic rename or directory force disables every format-2 write
 * ({@code STORE_FORMAT_REFUSED}); format-1 writes keep their Build 50 best-effort fallback.</p>
 *
 * <p>Intents follow COOP-DESIGN §5: PREPARE stages a copy of the intent, adds it to the global pending,
 * per-profile and pin indexes, writes the PREPARED intent, then the per-player index and the
 * reservations; COMMIT verifies the post-reservation vector and the staged after-images, writes the
 * COMMITTED intent (the logical commit), installs the after-images idempotently, clears reservations
 * without an after-image, writes receipts, writes FINALIZED and removes the index entries. Pins are
 * recomputed from the pending intents of the profile, so they are never under-counted at any crash
 * point. At open, a ready store removes the index entries of prepares that never wrote their intent and
 * of terminal intents, and finishes COMMITTED intents (roll forward: the process holds the exclusive
 * directory lock and nothing else writes yet). PREPARED intents are left for the engine to commit or
 * abort.</p>
 *
 * <p>Single process only. The constructor takes an exclusive OS lock on {@code repository.lock}
 * (released by {@link #close}) and fails while another process holds it. This repository is
 * <strong>not</strong> a distributed fence: two servers must never share its directory, and network
 * filesystems that do not implement OS file locks may not enforce the lock. The format-1 methods compare
 * the {@link ProfileWriteGuard}'s revision under this object's lock and do not check its claim.</p>
 *
 * <p>This implementation is {@code synchronized}: profile operations are low-frequency and the engine
 * already runs them one at a time. It must only be used from I/O workers.</p>
 */
public final class FileProfileRepository implements ProfileRepository, ProfileMigrationStore {

    private static final byte TYPE_ACCOUNT = ProfileRecords.TYPE_ACCOUNT;
    private static final byte TYPE_PROFILE = ProfileRecords.TYPE_PROFILE;
    private static final byte TYPE_BINDING = ProfileRecords.TYPE_BINDING;
    private static final byte TYPE_SNAPSHOT = ProfileRecords.TYPE_SNAPSHOT;
    private static final byte TYPE_TRANSITION = ProfileRecords.TYPE_TRANSITION;
    private static final int MAX_PAYLOAD = ProfileRecords.MAX_PAYLOAD;
    /** Terminal journals kept per player; older ones are deleted when a newer one is written. */
    static final int TERMINAL_JOURNALS_KEPT = 5;
    /** Pending intents per account and per profile (COOP-DESIGN §9). */
    static final int MAX_PENDING_PER_RESOURCE = 64;
    /** Pending intents in this store. */
    static final int MAX_PENDING_GLOBAL = 1024;
    /** Snapshot files one member stream directory may hold before pruning gives up on it. */
    private static final int MAX_STREAM_FILES = 4096;
    private static final int MAX_MARKER = 28 + 256 + 4; // header, writer (64 chars of UTF-8), Build 52 minimumBuild trailer
    private static final String LOCK_FILE = "repository.lock";
    private static final String CORRUPT = ".corrupt";
    /** The store clock's persisted high-water mark: {@code "AWFC"} and one big-endian epoch-millisecond long. */
    private static final String CLOCK_FILE = "clock.awfc";
    private static final int CLOCK_MAGIC = 0x41574643; // "AWFC"
    /**
     * How far ahead of the clock the persisted high-water mark runs ({@link #storeClockMillis}): the file is rewritten
     * once the clock reaches half of it, so the clock a restart resumes from is never below any value this process
     * returned, at the price of a jump of at most this much after a crash.
     */
    static final long CLOCK_LEASE_MILLIS = 30_000L;

    private final Path root;
    private final Path accounts;
    private final Path profiles;
    private final Path bindings;
    private final Path snapshots;
    private final Path transitions;
    private final Path formatFile;
    private final Path shared;
    private final Path invites;
    private final Path votes;
    private final Path salvage;
    private final Path members;
    private final Path intents;
    private final Path receipts;
    private final Path staging;
    private final FileProfileIndex indexes;
    private final FileChannel lockChannel;
    private final FileLock lock;
    /** Journal file and player of every indexed transition. */
    private final Map<UUID, Path> journalFiles = new HashMap<>();
    private final Map<UUID, UUID> journalPlayers = new HashMap<>();
    private final Map<UUID, Set<UUID>> journalsByPlayer = new HashMap<>();
    /** Quarantined journals whose player is known; each blocks that player until it is deleted. */
    private final Map<UUID, List<Path>> quarantined = new HashMap<>();
    /** Quarantined journals of earlier builds whose player could not be read. */
    private final List<Path> quarantinedUnknown = new ArrayList<>();
    /** The last marker read; {@code null} when it could not be read. */
    private StoreFormat.Marker marker = StoreFormat.Marker.legacy();
    /** Why this build may not use the store at all, or {@code null}. */
    private String markerProblem;
    /** Set at open when format-2 directories exist without a marker. */
    private String format2Residue;
    private boolean sawFormat2;
    /** {@code null} until probed; {@code false} disables every format-2 write. */
    private Boolean transactional;
    private String transactionalProblem;
    private FileProfileClaims claims;
    private int defaultSlots = PlayerAccount.DEFAULT_SLOTS;
    private boolean closed;
    /** The last value {@link #storeClockMillis} returned: the store clock never runs backwards within a process. */
    private long lastClockMillis;
    /** The high-water mark the clock file holds (a value the clock has not reached yet, or the exact value at close). */
    private long persistedClockMillis;
    private boolean clockWarned;

    /** Opens the directory for an unnamed holder; see {@link #FileProfileRepository(Path, String)}. */
    public FileProfileRepository(final Path root) throws IOException {
        this(root, "an unnamed process");
    }

    /**
     * Opens the directory and takes its exclusive lock, recording {@code holder} (the node id) in the
     * lock file so a refused second server can name who holds it. Reads the store format marker; for a
     * ready format-2 store runs the open-time intent recovery described in the class comment.
     *
     * @throws IOException when the directory cannot be created or another process holds the lock
     */
    public FileProfileRepository(final Path root, final String holder) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.accounts = this.root.resolve("accounts");
        this.profiles = this.root.resolve("profiles");
        this.bindings = this.root.resolve("bindings");
        this.snapshots = this.root.resolve("snapshots");
        this.transitions = this.root.resolve("transitions");
        this.formatFile = this.root.resolve(StoreFormat.FILE_NAME);
        this.shared = this.root.resolve("shared");
        this.invites = this.root.resolve("invites");
        this.votes = this.root.resolve("votes");
        this.salvage = this.root.resolve("salvage");
        this.members = this.root.resolve("members");
        this.intents = this.root.resolve("intents");
        this.receipts = this.root.resolve("receipts");
        this.staging = this.root.resolve("staging");
        this.indexes = new FileProfileIndex(this.root.resolve("indexes"), this.root);
        for (final Path directory : List.of(this.accounts, this.profiles, this.bindings, this.snapshots, this.transitions)) {
            Files.createDirectories(directory);
        }
        final FileChannel channel = FileChannel.open(this.root.resolve(LOCK_FILE), StandardOpenOption.CREATE,
            StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
        } catch (final OverlappingFileLockException sameProcess) {
            acquired = null;
        } catch (final IOException | RuntimeException failed) {
            channel.close();
            throw failed;
        }
        if (acquired == null) {
            final String owner = lockHolder(channel);
            channel.close();
            throw new IOException("the profile directory " + this.root + " is in use by " + owner
                + "; MultiProfile needs a directory exclusive to one server");
        }
        this.lockChannel = channel;
        this.lock = acquired;
        try {
            final byte[] record = (holder + " (pid " + ProcessHandle.current().pid() + ")\n")
                .getBytes(StandardCharsets.UTF_8);
            channel.truncate(0L);
            final ByteBuffer buffer = ByteBuffer.wrap(record);
            while (buffer.hasRemaining()) channel.write(buffer, buffer.position());
            channel.force(true);
            if (!Files.exists(this.formatFile)) {
                for (final Path directory : List.of(this.members, this.intents, this.shared, this.receipts, this.staging, this.invites,
                    this.votes, this.salvage, this.root.resolve("indexes"))) {
                    if (Files.exists(directory)) {
                        this.format2Residue = "the profile directory " + this.root + " holds format-2 data (" + directory.getFileName()
                            + "/) but no " + StoreFormat.FILE_NAME + " marker; this build refuses to read or write it. Restore the"
                            + " marker or the pre-migration backup";
                        break;
                    }
                }
            }
            readClock();
            refreshMarker();
            if (this.markerProblem != null) {
                SourbyLogger.error("AWF MultiProfile: " + this.markerProblem);
            } else {
                indexJournals();
                if (access() == Access.READY) {
                    recoverAtOpen();
                    sweepStaleStaging();
                }
            }
        } catch (final IOException | RuntimeException failed) {
            close();
            throw failed;
        }
    }

    public Path root() {
        return this.root;
    }

    /** Does no I/O: the format is the one read last. */
    @Override
    public String describe() {
        final StoreFormat.Marker known = this.marker;
        return "file " + this.root + (known == null || this.markerProblem != null ? " (format refused)"
            : " (format " + known.format() + (known.migration() == StoreFormat.MigrationState.IN_PROGRESS ? ", migrating" : "") + ")");
    }

    /**
     * Attaches the commit-time claim validator ({@link FileProfileClaims#of}). Until one is attached,
     * every format-2 write that carries a claim is refused with {@code OWNERSHIP_CONFLICT}.
     */
    public synchronized void attachClaims(final FileProfileClaims validator) {
        this.claims = Objects.requireNonNull(validator, "validator");
    }

    /**
     * The slot limit a reservation gives an account that has no record yet ({@code aurora.awf.multiprofile.default-slots});
     * without it the reservation would create the account with the built-in {@value PlayerAccount#DEFAULT_SLOTS} slots,
     * which an aborted intent would then leave behind.
     */
    public synchronized void attachDefaultSlots(final int defaultSlots) {
        if (defaultSlots < 1) throw new IllegalArgumentException("default slots " + defaultSlots);
        this.defaultSlots = defaultSlots;
    }

    // ================================================================== format 1 (PR #62 behaviour)

    @Override
    public synchronized PlayerAccount loadAccount(final UUID playerId, final int defaultSlots) throws IOException {
        final Access access = access();
        final Path file = accountFile(playerId);
        if (!Files.isRegularFile(file)) return PlayerAccount.empty(playerId, defaultSlots);
        return decodeAccount(read(file, TYPE_ACCOUNT, access), playerId, file);
    }

    /** Unguarded write, for tests and offline tools; the engine always passes a guard. */
    public void saveAccount(final PlayerAccount account) throws IOException {
        saveAccount(account, ProfileWriteGuard.UNGUARDED);
    }

    @Override
    public synchronized void saveAccount(final PlayerAccount account, final ProfileWriteGuard guard) throws IOException {
        requireFormat1Write("saveAccount");
        final Path file = accountFile(account.playerId());
        if (guard.expectedRevision() != ProfileWriteGuard.ANY) {
            // An account that was never stored is what loadAccount returns: revision 0.
            guard.check("account " + account.playerId(), Files.isRegularFile(file) ? read(file, TYPE_ACCOUNT).revision() : 0L);
        }
        write(file, TYPE_ACCOUNT, account.revision(), ProfileRecords.accountPayload(account));
    }

    @Override
    public synchronized Optional<AuroraProfile> profile(final UUID profileId) throws IOException {
        return Optional.ofNullable(profileOrNull(profileId));
    }

    /** Unguarded write, for tests and offline tools; the engine always passes a guard. */
    public void saveProfile(final AuroraProfile profile) throws IOException {
        saveProfile(profile, ProfileWriteGuard.UNGUARDED);
    }

    @Override
    public synchronized void saveProfile(final AuroraProfile profile, final ProfileWriteGuard guard) throws IOException {
        requireFormat1Write("saveProfile");
        checkProfileRevision(profile.id(), guard);
        writeProfile(profile);
    }

    @Override
    public synchronized List<AuroraProfile> profiles(final UUID accountId) throws IOException {
        final PlayerAccount account = loadAccount(accountId, PlayerAccount.DEFAULT_SLOTS);
        final List<AuroraProfile> found = new ArrayList<>(account.profileIds().size());
        for (final UUID id : account.profileIds()) {
            final Optional<AuroraProfile> profile = profile(id);
            profile.ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    @Override
    public synchronized Optional<ProfileWorldBinding> binding(final UUID profileId) throws IOException {
        final ProfileRecords.Binding2 binding = bindingOrNull(profileId);
        return binding == null ? Optional.empty() : Optional.of(binding.binding());
    }

    /** Unguarded write, for tests and offline tools; the engine always passes a guard. */
    public void saveBinding(final ProfileWorldBinding binding) throws IOException {
        saveBinding(binding, ProfileWriteGuard.UNGUARDED);
    }

    @Override
    public synchronized void saveBinding(final ProfileWorldBinding binding, final ProfileWriteGuard guard) throws IOException {
        requireFormat1Write("saveBinding");
        write(this.bindings.resolve(binding.profileId() + ".awfp"), TYPE_BINDING, 1L, ProfileRecords.bindingPayload(binding));
    }

    @Override
    public synchronized Optional<ProfileSnapshot> latestSnapshot(final UUID profileId) throws IOException {
        requireFlatSnapshots("latestSnapshot");
        final List<Path> files = snapshotFiles(profileId);
        for (int i = files.size() - 1; i >= 0; i--) {
            final ProfileSnapshot snapshot = readSnapshot(files.get(i), profileId);
            if (snapshot != null) return Optional.of(snapshot);
        }
        return Optional.empty();
    }

    @Override
    public synchronized Optional<ProfileSnapshot> snapshotById(final UUID profileId, final UUID snapshotId) throws IOException {
        requireFlatSnapshots("snapshotById");
        for (final Path file : snapshotFiles(profileId)) {
            if (!snapshotId.equals(snapshotIdOf(file))) continue;
            final ProfileSnapshot snapshot = readSnapshot(file, profileId);
            if (!snapshot.snapshotId().equals(snapshotId)) {
                throw new IOException("snapshot file " + file + " holds " + snapshot.snapshotId());
            }
            return Optional.of(snapshot);
        }
        return Optional.empty();
    }

    /** The newest snapshot at one revision, checksum-verified; for tests and offline tools. */
    public synchronized Optional<ProfileSnapshot> snapshot(final UUID profileId, final long revision) throws IOException {
        requireFlatSnapshots("snapshot");
        final List<Path> files = snapshotFiles(profileId);
        for (int i = files.size() - 1; i >= 0; i--) {
            final Path file = files.get(i);
            if (revisionOf(file.getFileName().toString()) != revision) continue;
            final ProfileSnapshot snapshot = readSnapshot(file, profileId);
            if (snapshot != null) return Optional.of(snapshot);
        }
        return Optional.empty();
    }

    @Override
    public synchronized void publishSnapshot(final AuroraProfile next, final ProfileSnapshot snapshot, final int retain,
                                             final ProfileWriteGuard guard) throws IOException {
        requireFormat1Write("publishSnapshot");
        if (!next.id().equals(snapshot.profileId())) {
            throw new IllegalArgumentException("snapshot of " + snapshot.profileId() + " published for " + next.id());
        }
        checkProfileRevision(next.id(), guard);
        final Set<UUID> pinned = journalPins(next.accountId());
        writeSnapshotFile(snapshot);
        pruneSnapshots(snapshot.profileId(), retain, pinned);
        writeProfile(next);
    }

    /**
     * Writes a snapshot without touching the profile record, then prunes like
     * {@link #publishSnapshot}; for tests and offline tools.
     */
    public synchronized void saveSnapshot(final ProfileSnapshot snapshot, final int retain) throws IOException {
        requireFormat1Write("saveSnapshot");
        final Optional<AuroraProfile> profile = profile(snapshot.profileId());
        final Set<UUID> pinned = profile.isPresent() ? journalPins(profile.get().accountId()) : Set.of();
        writeSnapshotFile(snapshot);
        pruneSnapshots(snapshot.profileId(), retain, pinned);
    }

    @Override
    public synchronized void saveLegacySnapshot(final UUID playerId, final ProfileSnapshot snapshot,
                                                final ProfileWriteGuard guard) throws IOException {
        final Access access = access();
        if (access == Access.MIGRATING) throw refused("a format-2 migration is in progress; saveLegacySnapshot is refused");
        if (!snapshot.profileId().equals(playerId)) {
            throw new IllegalArgumentException("legacy snapshot of " + snapshot.profileId() + " saved for " + playerId);
        }
        final Path file = legacyFile(playerId);
        if (Files.isRegularFile(file)) {
            // An earlier first switch archived a state already (it was rolled back, or the account was
            // reset). That archive may be the only copy of what the player had, so it is kept aside.
            Files.move(file, this.accounts.resolve(playerId + ".legacy." + System.currentTimeMillis() + ".awfp"));
        }
        if (access == Access.LEGACY) {
            write(file, TYPE_SNAPSHOT, snapshot.revision(), ProfileRecords.snapshotPayload(snapshot));
        } else {
            durable(file, ProfileRecords.frame2(TYPE_SNAPSHOT, snapshot.revision(), ProfileRecords.legacyArchivePayload2(snapshot),
                file.toString()));
        }
    }

    @Override
    public synchronized Optional<ProfileSnapshot> legacySnapshot(final UUID playerId) throws IOException {
        final Access access = access();
        final Path file = legacyFile(playerId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(decodeArchive(read(file, TYPE_SNAPSHOT, access), playerId, file));
    }

    @Override
    public synchronized Optional<ProfileTransition> transition(final UUID transitionId) throws IOException {
        access();
        return Optional.ofNullable(readIndexed(transitionId));
    }

    @Override
    public synchronized List<ProfileTransition> transitionsFor(final UUID playerId) throws IOException {
        access();
        final List<Path> blocked = quarantinedFor(playerId);
        if (!blocked.isEmpty()) {
            throw new IOException("the transition journal " + blocked.getFirst().getFileName() + " of " + playerId
                + " could not be read and was set aside; inspect it, then delete it to unblock this player");
        }
        final List<ProfileTransition> found = new ArrayList<>();
        for (final UUID transitionId : List.copyOf(this.journalsByPlayer.getOrDefault(playerId, Set.of()))) {
            final ProfileTransition journal = readIndexed(transitionId);
            if (journal != null) found.add(journal);
        }
        found.sort(Comparator.comparingLong(ProfileTransition::updatedAtMillis));
        return List.copyOf(found);
    }

    @Override
    public synchronized List<ProfileTransition> transitions() {
        access();
        final List<ProfileTransition> found = new ArrayList<>();
        for (final UUID transitionId : List.copyOf(this.journalFiles.keySet())) {
            try {
                final ProfileTransition journal = readIndexed(transitionId);
                if (journal != null) found.add(journal);
            } catch (final IOException setAside) {
                // Already logged and quarantined; the status listing shows the file.
            }
        }
        found.sort(Comparator.comparingLong(ProfileTransition::updatedAtMillis));
        return List.copyOf(found);
    }

    @Override
    public synchronized List<String> quarantinedJournals() {
        final List<String> names = new ArrayList<>();
        for (final UUID player : List.copyOf(this.quarantined.keySet())) {
            for (final Path file : quarantinedFor(player)) names.add(file.getFileName().toString());
        }
        this.quarantinedUnknown.removeIf(file -> !Files.exists(file));
        for (final Path file : this.quarantinedUnknown) names.add(file.getFileName().toString());
        return List.copyOf(names);
    }

    /** Unguarded write, for tests and offline tools; the engine always passes a guard. */
    public void saveTransition(final ProfileTransition transition) throws IOException {
        saveTransition(transition, ProfileWriteGuard.UNGUARDED);
    }

    /**
     * Format 1: as in Build 50. Format 2: the journal must carry its co-op fields and is written as a
     * format-2 record with the strict durable write. Refused during a migration.
     */
    @Override
    public synchronized void saveTransition(final ProfileTransition transition, final ProfileWriteGuard guard)
        throws IOException {
        final Access access = access();
        if (access == Access.MIGRATING) throw refused("a format-2 migration is in progress; saveTransition is refused");
        final UUID transitionId = transition.transitionId();
        final Path file = this.journalFiles.getOrDefault(transitionId,
            this.transitions.resolve(transition.playerId() + "." + transitionId + ".awfp"));
        if (access == Access.LEGACY) {
            write(file, TYPE_TRANSITION, 1L, ProfileRecords.transitionPayload(transition));
        } else {
            if (transition.coop() == null) {
                throw refused("a format-2 store stores switch journals with their co-op fields only; journal " + transitionId
                    + " has none and was not written");
            }
            durable(file, ProfileRecords.frame2(TYPE_TRANSITION, 1L, ProfileRecords.transitionPayload2(transition), file.toString()));
        }
        index(transitionId, transition.playerId(), file);
        if (transition.terminal()) pruneTerminalJournals(transition.playerId());
    }

    /** Unguarded delete, for tests and offline tools; the engine always passes a guard. */
    public void deleteTransition(final UUID transitionId) throws IOException {
        deleteTransition(transitionId, ProfileWriteGuard.UNGUARDED);
    }

    @Override
    public synchronized void deleteTransition(final UUID transitionId, final ProfileWriteGuard guard) throws IOException {
        if (access() == Access.MIGRATING) throw refused("a format-2 migration is in progress; deleteTransition is refused");
        final Path file = this.journalFiles.get(transitionId);
        if (file != null) Files.deleteIfExists(file);
        unindex(transitionId);
    }

    /** Removes the profile record, its binding and its snapshots; for tests and offline tools. Format 1 only. */
    public synchronized void deleteProfile(final UUID profileId) throws IOException {
        deleteProfileData(profileId, ProfileWriteGuard.UNGUARDED);
        Files.deleteIfExists(this.profiles.resolve(profileId + ".awfp"));
    }

    /** Unguarded delete, for tests and offline tools; the engine always passes a guard. */
    public void deleteProfileData(final UUID profileId) throws IOException {
        deleteProfileData(profileId, ProfileWriteGuard.UNGUARDED);
    }

    /** Format 1 only: in a format-2 store the snapshot directory also holds the member streams. */
    @Override
    public synchronized void deleteProfileData(final UUID profileId, final ProfileWriteGuard guard) throws IOException {
        requireFormat1Write("deleteProfileData");
        Files.deleteIfExists(this.bindings.resolve(profileId + ".awfp"));
        deleteRecursively(this.snapshots.resolve(profileId.toString()));
    }

    // ------------------------------------------------------------------ listing, for the repository migrations

    /** Every stored account, by player id; files that are not {@code <uuid>.awfp} are skipped. */
    public synchronized List<UUID> accountIds() throws IOException {
        return idsIn(this.accounts);
    }

    /** Every stored profile record, tombstones included. */
    public synchronized List<UUID> profileIds() throws IOException {
        return idsIn(this.profiles);
    }

    /** Every format-1 snapshot of a profile, oldest first in {@link #latestSnapshot}'s order, checksum-verified. */
    public synchronized List<ProfileSnapshot> snapshots(final UUID profileId) throws IOException {
        requireFlatSnapshots("snapshots");
        final List<ProfileSnapshot> found = new ArrayList<>();
        for (final Path file : snapshotFiles(profileId)) found.add(readSnapshot(file, profileId));
        return List.copyOf(found);
    }

    /**
     * Every archived pre-profile state of a player, oldest first; the last one is
     * {@link #legacySnapshot}. Checksum-verified.
     */
    public synchronized List<ProfileSnapshot> legacySnapshots(final UUID playerId) throws IOException {
        final Access access = access();
        final Map<Long, Path> earlier = new TreeMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(this.accounts, playerId + ".legacy.*.awfp")) {
            for (final Path file : files) {
                final String name = file.getFileName().toString();
                final String millis = name.substring((playerId + ".legacy.").length(), name.length() - ".awfp".length());
                try {
                    earlier.put(Long.parseLong(millis), file);
                } catch (final NumberFormatException notAnArchive) {
                    // Not written by this repository; left alone.
                }
            }
        }
        final List<ProfileSnapshot> found = new ArrayList<>();
        for (final Path file : earlier.values()) found.add(decodeArchive(read(file, TYPE_SNAPSHOT, access), playerId, file));
        legacySnapshot(playerId).ifPresent(found::add);
        return List.copyOf(found);
    }

    private static List<UUID> idsIn(final Path directory) throws IOException {
        final List<UUID> ids = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.awfp")) {
            for (final Path file : files) {
                final String name = file.getFileName().toString();
                final UUID id = uuidOrNull(name.substring(0, name.length() - ".awfp".length()));
                if (id != null) ids.add(id);
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    /**
     * Releases the directory lock. Every write already closed its channel before the rename, so no
     * record is left open; writes made after this are no longer protected from a second process.
     */
    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        if (this.lastClockMillis > 0L && this.lastClockMillis != this.persistedClockMillis) {
            // The exact last value: a clean restart resumes from it without the lease's jump.
            try {
                persistClock(this.lastClockMillis);
            } catch (final IOException | RuntimeException failed) {
                SourbyLogger.warn("AWF MultiProfile: the store clock mark " + this.root.resolve(CLOCK_FILE) + " was not written at close ("
                    + failed.getMessage() + "); the last mark, written ahead of the clock, stands");
            }
        }
        try {
            this.lock.release();
        } catch (final IOException | RuntimeException ignored) {
            // Closing the channel below releases it as well.
        }
        try {
            this.lockChannel.close();
        } catch (final IOException failed) {
            SourbyLogger.warn("AWF MultiProfile: closing the profile directory lock failed: " + failed.getMessage());
        }
    }

    // ================================================================== store format

    @Override
    public synchronized StoreFormat.Marker storeFormat() throws IOException {
        refreshMarker();
        final StoreFormat.Marker known = this.marker;
        if (this.markerProblem != null && (known == null || known.legacyFormat())) throw new IOException(this.markerProblem);
        return known;
    }

    /**
     * Writes the marker durably. Only format-2 schema-1 markers are written (never a downgrade);
     * {@code IN_PROGRESS} is refused on a ready store, and {@code READY} (or a format-2 {@code NONE}) is
     * refused while any record of the store is not format 2.
     */
    @Override
    public synchronized void writeStoreFormat(final StoreFormat.Marker next) throws IOException {
        refreshMarker();
        if (this.markerProblem != null) throw refused(this.markerProblem);
        final StoreFormat.Marker current = this.marker;
        if (next.format() != StoreFormat.CURRENT || next.schema() != StoreFormat.SCHEMA) {
            throw invalid("this build writes only format-" + StoreFormat.CURRENT + " schema-" + StoreFormat.SCHEMA
                + " markers; a downgrade marker is never written");
        }
        if (!current.legacyFormat() && (current.schema() > next.schema() || current.minimumBuild() > next.minimumBuild())) {
            throw refused("the store was raised to schema " + current.schema() + " / Build " + current.minimumBuild() + "; a marker below"
                + " that (schema " + next.schema() + ", Build " + next.minimumBuild() + ") is never written. Nothing was written");
        }
        if (next.migration() == StoreFormat.MigrationState.IN_PROGRESS) {
            if (!current.legacyFormat() && current.migration() != StoreFormat.MigrationState.IN_PROGRESS) {
                throw invalid("the store is already format 2; a migration marker is not written over it");
            }
        } else {
            final List<String> remaining = legacyRecordsRemainingUnchecked(1);
            if (!remaining.isEmpty()) {
                throw refused("the store still holds a record that is not format 2 (" + remaining.getFirst()
                    + "); it is not marked " + next.migration());
            }
        }
        requireTransactional();
        // The global pending-intent index exists from the first format-2 marker on; a format-2 store without it is corrupt.
        this.indexes.ensurePresent(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL);
        durable(this.formatFile, StoreFormat.encode(next));
        this.marker = next;
        this.sawFormat2 = true;
        this.indexes.strict(true);
        this.format2Residue = null;
    }

    /**
     * {@link ProfileRepository#requireStoreFeature}: under the commit lock, so no format-2 write interleaves; the
     * marker file is replaced durably before this returns.
     */
    @Override
    public synchronized StoreFormat.Marker requireStoreFeature(final int schema, final int minimumBuild, final String writtenBy)
        throws IOException {
        refreshMarker();
        if (this.markerProblem != null) throw refused(this.markerProblem);
        final StoreFormat.Marker current = this.marker;
        if (current.legacyFormat() || current.migration() == StoreFormat.MigrationState.IN_PROGRESS) {
            throw refused("the store is " + (current.legacyFormat() ? "format 1" : "being migrated") + "; its feature level is"
                + " not raised and no co-op record is written");
        }
        if (schema > StoreFormat.MAX_SCHEMA || minimumBuild > StoreFormat.READS_UP_TO_BUILD) {
            throw invalid("this build raises a store to at most schema " + StoreFormat.MAX_SCHEMA + " and Build "
                + StoreFormat.READS_UP_TO_BUILD);
        }
        if (current.covers(schema, minimumBuild)) return current;
        requireTransactional();
        final StoreFormat.Marker next = current.raised(schema, minimumBuild, System.currentTimeMillis(), writtenBy);
        durable(this.formatFile, StoreFormat.encode(next));
        this.marker = next;
        SourbyLogger.info("AWF MultiProfile: the profile store now requires Build " + next.requiredBuild() + " or newer (schema "
            + next.schema() + "); builds below it refuse it");
        return next;
    }

    // ================================================================== format 2: guarded record writes

    @Override
    public synchronized CommitResult commitAccount(final PlayerAccount after, final CoopWriteGuard guard) throws IOException {
        final boolean migration = format2Write(guard);
        final UUID player = after.playerId();
        final String what = "account " + player;
        if (!migration) {
            final CommitResult replay = replayDirect(player, guard);
            if (replay != null) return replay;
        }
        final Path file = accountFile(player);
        final PlayerAccount stored = accountOrNull(player);
        final byte[] payload = ProfileRecords.accountPayload2(after);
        final Expected expected = guard.expected(Domain.ACCOUNT, player, null, null);
        if (expected.revision() == CoopWriteGuard.ABSENT) {
            throw invalid(what + ": accounts use revision 0 for an unstored account, never ABSENT; nothing was written");
        }
        compareVector(guard);
        guard.checkActive(stored == null ? null : stored.activeProfile());
        final UUID pending = stored == null ? null : stored.pendingIntent();
        checkReservation(pending, guard, what);
        if (!Objects.equals(after.pendingIntent(), pending)) {
            throw invalid("a direct commit of " + what + " cannot install or clear a reservation; intents do that");
        }
        if (!migration) {
            requireNext(after.revision(), expected.revision(), what);
            requirePlayerClaim(guard, player);
            checkCoopAccess(stored == null ? PlayerAccount.empty(player, this.defaultSlots) : stored, after, this::profileOrNull, what);
        }
        final Set<String> aliases = new HashSet<>();
        for (final String alias : after.aliases().values()) {
            if (!aliases.add(alias.toLowerCase(java.util.Locale.ROOT))) {
                throw new ProfileOperationException(ProfileErrorCode.NAME_TAKEN, "alias " + alias + " is used twice in " + what
                    + "; nothing was written");
            }
        }
        validateLive(guard);
        return publish(file, TYPE_ACCOUNT, after.revision(), payload, player, guard, migration, what);
    }

    /** Whether the marker read last covers co-op records (schema 2, Build 52). */
    private boolean coopStoreLevel() {
        return this.marker != null && this.marker.covers(StoreFormat.COOP_SCHEMA, StoreFormat.COOP_MINIMUM_BUILD);
    }

    private String describeLevel() {
        return this.marker == null ? "unreadable" : "schema " + this.marker.schema() + ", minimum build " + this.marker.minimumBuild();
    }

    /** Reads a stored profile for {@link #checkCoopAccess}; {@code null} when none. */
    interface ProfileLookup {
        AuroraProfile profile(UUID profileId) throws IOException;
    }

    /**
     * The account side of "membership changes are intents" (the counterpart of the member-row check of
     * {@code commitProfile}), shared with the Redis repository: a direct account commit may not add or drop access to
     * a co-op profile (an accept, leave or kick does that in one intent with the member row), and may make a co-op
     * profile the active one only for a current member, which in Build 52 is its OWNER only
     * ({@code COOP_SWITCH_UNAVAILABLE} for every other member: member switching arrives in Build 53), at that
     * member's epoch. Solo profiles, account-local aliases, slot limits, sessions and cleanups are unaffected (Build 51
     * behaviour), and so is a profile the store does not hold (a creation in the same operation).
     */
    static void checkCoopAccess(final PlayerAccount stored, final PlayerAccount after, final ProfileLookup profiles, final String what)
        throws IOException {
        final Set<UUID> touched = new LinkedHashSet<>(stored.profileIds());
        touched.addAll(after.profileIds());
        for (final UUID profileId : touched) {
            if (stored.owns(profileId) == after.owns(profileId)) continue;
            final AuroraProfile profile = profiles.profile(profileId);
            if (profile != null && profile.coop()) {
                throw invalid("a direct commit of " + what + (after.owns(profileId) ? " adds" : " drops") + " access to co-op profile "
                    + profileId + "; co-op membership changes are intents (accept, leave, kick). Nothing was written");
            }
        }
        final UUID active = after.activeProfile();
        if (active == null || active.equals(stored.activeProfile())) return;
        final AuroraProfile target = profiles.profile(active);
        if (target == null || !target.coop()) return;
        final ProfileMember row = target.member(after.playerId());
        if (row == null) {
            throw new ProfileOperationException(ProfileErrorCode.NOT_A_MEMBER, what + " would make co-op profile " + active + " active but "
                + after.playerId() + " is not one of its members. Nothing was written");
        }
        if (row.role() != ProfileRole.OWNER) {
            throw new ProfileOperationException(ProfileErrorCode.COOP_SWITCH_UNAVAILABLE, what + " would make co-op profile " + active
                + " active for its " + row.role() + " " + after.playerId() + "; in Build 52 only the OWNER plays a co-op profile (member"
                + " switching arrives in Build 53). Nothing was written");
        }
        if (after.activeEpoch() != null && !after.activeEpoch().equals(row.epoch())) {
            throw invalid(what + " names active epoch " + after.activeEpoch() + " but the owner's membership epoch in " + active + " is "
                + row.epoch() + ". Nothing was written");
        }
    }

    @Override
    public synchronized CommitResult commitProfile(final AuroraProfile after, final CoopWriteGuard guard) throws IOException {
        final boolean migration = format2Write(guard);
        final UUID id = after.id();
        final String what = "profile " + id;
        if (!migration) {
            final CommitResult replay = replayDirect(id, guard);
            if (replay != null) return replay;
        }
        if (after.legacy()) throw invalid("a format-2 " + what + " has member rows; nothing was written");
        final Path file = profileFile(id);
        final byte[] payload = ProfileRecords.profilePayload2(after);
        final AuroraProfile stored = profileOrNull(id);
        final Expected expected = guard.expected(Domain.METADATA, id, null, null);
        compareVector(guard);
        if (migration) {
            if (after.pendingIntent() != null) throw invalid("a migrated " + what + " carries no reservation");
        } else if (expected.revision() == CoopWriteGuard.ABSENT) {
            if (after.revision() != 0L || after.pendingIntent() != null) {
                throw invalid("a new " + what + " starts at revision 0 with no reservation");
            }
            requirePlayerClaim(guard, after.accountId());
            if (guard.profile() != null) requireProfileClaim(guard, id);
            requireUniqueName(after);
        } else {
            requireProfileClaim(guard, id);
            requireActor(guard);
            final UUID actor = guard.actor().playerId();
            requireOwner(stored, actor, what);
            if (!stored.accountId().equals(after.accountId()) || !stored.name().equals(after.name())
                || stored.createdAtMillis() != after.createdAtMillis() || !sameMemberships(stored.members(), after.members())) {
                throw invalid("a direct commit of " + what + " cannot change its owner, name, creation time or memberships;"
                    + " membership changes are intents");
            }
            if (stored.kind() != after.kind()) {
                throw invalid("a direct commit of " + what + " cannot change its kind (" + stored.kind() + " to " + after.kind()
                    + "); the conversion to co-op is an intent and one way");
            }
            checkReservation(stored.pendingIntent(), guard, what);
            if (!Objects.equals(after.pendingIntent(), stored.pendingIntent())) {
                throw invalid("a direct commit of " + what + " cannot install or clear a reservation; intents do that");
            }
            requireNext(after.revision(), expected.revision(), what);
        }
        if (!migration && ProfileRecords.needsProfileSchema2(after) && !coopStoreLevel()) {
            throw refused(what + " is a co-op or labelled profile (payload schema 2) but the store marker " + this.formatFile + " is "
                + describeLevel() + "; raise the store's feature level first (requireStoreFeature). Nothing was written");
        }
        validateLive(guard);
        return publish(file, TYPE_PROFILE, after.revision(), payload, id, guard, migration, what);
    }

    @Override
    public synchronized OptionalLong bindingRevision(final UUID profileId) throws IOException {
        format2Read();
        final ProfileRecords.Binding2 binding = bindingOrNull(profileId);
        return binding == null ? OptionalLong.empty() : OptionalLong.of(binding.revision());
    }

    @Override
    public synchronized CommitResult commitBinding(final ProfileWorldBinding binding, final long nextRevision,
                                                   final CoopWriteGuard guard) throws IOException {
        final boolean migration = format2Write(guard);
        final UUID id = binding.profileId();
        final String what = "the binding of " + id;
        if (!migration) {
            final CommitResult replay = replayDirect(id, guard);
            if (replay != null) return replay;
        }
        CoopWriteGuard.counter(nextRevision, "binding revision");
        final ProfileRecords.Binding2 stored = bindingOrNull(id);
        final UUID pending = stored == null ? null : stored.pendingIntent();
        final Path file = this.bindings.resolve(id + ".awfp");
        final byte[] payload = ProfileRecords.bindingPayload2(binding, nextRevision, pending);
        final Expected expected = guard.expected(Domain.RECORD, id, null, null);
        compareVector(guard);
        if (!migration) {
            requireNext(nextRevision, expected.revision(), what);
            requireProfileClaim(guard, id);
            requireActor(guard);
            final AuroraProfile profile = profileOrNull(id);
            if (profile == null) throw new ProfileOperationException(ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + id);
            requireOwner(profile, guard.actor().playerId(), what);
            checkReservation(pending, guard, what);
        }
        validateLive(guard);
        return publish(file, TYPE_BINDING, nextRevision, payload, id, guard, migration, what);
    }

    // ------------------------------------------------------------------ member streams

    @Override
    public synchronized Optional<MemberStateHead> memberHead(final UUID profileId, final UUID memberId, final UUID epoch)
        throws IOException {
        format2Read();
        return Optional.ofNullable(headOrNull(profileId, memberId, epoch));
    }

    @Override
    public synchronized List<MemberStateHead> memberHeads(final UUID profileId) throws IOException {
        format2Read();
        final AuroraProfile profile = profileOrNull(profileId);
        if (profile == null) return List.of();
        final List<MemberStateHead> heads = new ArrayList<>(profile.members().size());
        for (final ProfileMember member : profile.members()) {
            final MemberStateHead head = headOrNull(profileId, member.playerId(), member.epoch());
            if (head != null) heads.add(head);
        }
        return List.copyOf(heads);
    }

    @Override
    public synchronized Optional<ProfileSnapshot> memberSnapshot(final UUID profileId, final UUID memberId, final UUID epoch,
                                                                 final UUID snapshotId) throws IOException {
        final Access access = format2Read();
        final Path directory = streamDirectory(profileId, memberId, epoch);
        if (!Files.isDirectory(directory)) return Optional.empty();
        final List<Path> found = new ArrayList<>(2);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*-" + snapshotId + ".awfp")) {
            for (final Path file : files) {
                found.add(file);
                if (found.size() > 1) throw new IOException("member stream " + directory + " holds snapshot " + snapshotId + " twice");
            }
        }
        if (found.isEmpty()) return Optional.empty();
        final Path file = found.getFirst();
        final ProfileSnapshot snapshot = ProfileRecords.memberSnapshot2(read(file, ProfileRecords.TYPE_MEMBER_SNAPSHOT, access),
            profileId, memberId, epoch, file.toString());
        if (!snapshot.snapshotId().equals(snapshotId) || snapshot.revision() != revisionOf(file.getFileName().toString())) {
            throw new IOException("member snapshot file " + file + " holds " + snapshot.snapshotId() + " at revision "
                + snapshot.revision());
        }
        return Optional.of(snapshot);
    }

    @Override
    public synchronized CommitResult publishMemberSnapshot(final MemberStateHead next, final ProfileSnapshot snapshot,
                                                           final int retain, final CoopWriteGuard guard) throws IOException {
        if (format2Write(guard)) throw invalid("the migration installs member streams through ProfileMigrationStore");
        final UUID profileId = next.profileId();
        final UUID memberId = next.memberId();
        final UUID epoch = next.epoch();
        final String what = "member stream " + next.describe();
        final CommitResult replay = replayDirect(profileId, guard);
        if (replay != null) return replay;
        if (!snapshot.member() || !snapshot.inStream(profileId, memberId, epoch)) {
            throw invalid("snapshot " + snapshot.snapshotId() + " does not belong to " + what);
        }
        final Path headFile = headFile(profileId, memberId, epoch);
        final byte[] headPayload = ProfileRecords.memberHeadPayload2(next);
        final Path snapshotFile = streamDirectory(profileId, memberId, epoch)
            .resolve(snapshot.revision() + "-" + snapshot.snapshotId() + ".awfp");
        final MemberStateHead stored = headOrNull(profileId, memberId, epoch);
        final Expected expected = guard.expected(Domain.HEAD, profileId, memberId, epoch);
        compareVector(guard);
        final MemberStateHead base = stored != null ? stored : MemberStateHead.uninitialized(profileId, memberId, epoch);
        if (stored == null && expected.revision() != CoopWriteGuard.ABSENT) {
            throw stale(what + " has no head but operation " + guard.operation().operationId() + " expected one");
        }
        final MemberStateHead published;
        try {
            published = base.published(snapshot);
        } catch (final IllegalStateException | IllegalArgumentException refused) {
            throw stale(what + " cannot publish snapshot " + snapshot.snapshotId() + ": " + refused.getMessage());
        }
        if (!published.equals(next)) {
            throw invalid("the head written for " + what + " must be exactly the stored head published with snapshot "
                + snapshot.snapshotId());
        }
        checkReservation(base.pendingIntent(), guard, what);
        final AuroraProfile profile = profileOrNull(profileId);
        if (profile == null || !profile.hasMember(memberId, epoch)) {
            throw stale(memberId + "/" + epoch + " is not a current membership of profile " + profileId + "; nothing was written");
        }
        requirePlayerClaim(guard, memberId);
        final PlayerAccount account = accountOrNull(memberId);
        final UUID active = account == null ? null : account.activeProfile();
        guard.checkActive(active);
        if (!profileId.equals(active)) {
            throw stale("the active profile of " + memberId + " is " + active + ", not " + profileId + "; nothing was written");
        }
        // Claims are validated before the first write: a fenced save leaves no orphan snapshot in the stream.
        validateLive(guard);
        // The snapshot is an immutable blob: durable before the head that names it.
        FileProfileIo.ensureDirectory(snapshotFile.getParent(), this.root);
        blob(snapshotFile, ProfileRecords.frame2(ProfileRecords.TYPE_MEMBER_SNAPSHOT, snapshot.revision(),
            ProfileRecords.memberSnapshotPayload2(snapshot), snapshotFile.toString()));
        final CommitResult result = publish(headFile, ProfileRecords.TYPE_MEMBER_HEAD, next.revision(), headPayload, profileId,
            guard, false, what);
        if (result.status() == CommitResult.Status.COMMITTED) pruneStream(profileId, memberId, epoch, retain, next);
        return result;
    }

    @Override
    public synchronized List<SnapshotRef> pinnedSnapshots(final UUID profileId, final UUID memberId, final UUID epoch)
        throws IOException {
        format2Read();
        final List<SnapshotRef> pins = new ArrayList<>();
        for (final ProfileIndexPage.Entry entry : this.indexes.entries(ProfileIndexPage.Kind.PINS_BY_STREAM, profileId, memberId, epoch)) {
            pins.add(new SnapshotRef(profileId, memberId, epoch, entry.id()));
        }
        return List.copyOf(pins);
    }

    // ------------------------------------------------------------------ shared state

    @Override
    public synchronized Optional<ProfileSharedState> shared(final UUID profileId) throws IOException {
        format2Read();
        return Optional.ofNullable(sharedOrNull(profileId));
    }

    @Override
    public synchronized CommitResult mutateShared(final ProfileSharedState after, final CoopWriteGuard guard) throws IOException {
        if (format2Write(guard)) throw invalid("the migration writes no shared state");
        final UUID id = after.profileId();
        final String what = "the shared state of " + id;
        final CommitResult replay = replayDirect(id, guard);
        if (replay != null) return replay;
        final Path file = sharedFile(id);
        final byte[] payload = ProfileRecords.sharedPayload2(after);
        final ProfileSharedState stored = sharedOrNull(id);
        final Expected expected = guard.expected(Domain.SHARED, id, null, null);
        compareVector(guard);
        requireNext(after.revision(), expected.revision(), what);
        requireProfileClaim(guard, id);
        requireActor(guard);
        final UUID actor = guard.actor().playerId();
        final AuroraProfile profile = profileOrNull(id);
        if (profile == null || profile.member(actor) == null) {
            throw new ProfileOperationException(ProfileErrorCode.NOT_OWNED, actor + " is not a member of profile " + id
                + "; nothing was written");
        }
        final PlayerAccount account = accountOrNull(actor);
        final UUID active = account == null ? null : account.activeProfile();
        guard.checkActive(active);
        if (!id.equals(active)) throw stale(actor + " is not active on profile " + id + "; nothing was written");
        final UUID pending = stored == null ? null : stored.pendingIntent();
        checkReservation(pending, guard, what);
        if (!Objects.equals(after.pendingIntent(), pending)) {
            throw invalid("a direct commit of " + what + " cannot install or clear a reservation; intents do that");
        }
        validateLive(guard);
        return publish(file, ProfileRecords.TYPE_SHARED, after.revision(), payload, id, guard, false, what);
    }

    // ------------------------------------------------------------------ intents

    /**
     * Stages one immutable after-image blob for operation {@code operationId} under
     * {@code staging/<operation>/}, after decoding it with its type's codec (identity, schema, bounds). The
     * returned reference goes into the intent's after-images. Staging writes outside the commit decision
     * and only ever creates new blobs (the same bytes again are a no-op); a ready format-2 store is required.
     */
    @Override
    public synchronized BlobRef stageAfterImage(final UUID operationId, final byte recordType, final UUID id, final UUID member,
                                                final UUID epoch, final long revision, final byte[] payload) throws IOException {
        if (access() != Access.READY) throw refused("after-images are staged in a ready format-2 store only");
        requireTransactional();
        final BlobRef ref = new BlobRef(recordType, id, member, epoch, revision, payload.length, ProfileRecords.sha256(payload));
        final Path file = blobFile(operationId, ref);
        final byte[] frame = ProfileRecords.frame2(recordType, revision, payload, file.toString());
        decodeStaged(ref, ProfileRecords.unframe(frame, ProfileRecords.FORMAT_2, recordType, file.toString()), file.toString());
        if (Files.isRegularFile(file) && Arrays.equals(FileProfileIo.readBounded(file, frame.length), frame)) return ref;
        FileProfileIo.ensureDirectory(file.getParent(), this.root);
        blob(file, frame);
        return ref;
    }

    @Override
    public synchronized CommitResult prepareIntent(final ProfileIntent intent, final CoopWriteGuard guard) throws IOException {
        if (format2Write(guard)) throw invalid("the migration does not use intents");
        final CoopOperation op = guard.operation();
        final UUID id = op.operationId();
        if (!intent.operationId().equals(id) || intent.kind() != op.kind() || !intent.actor().equals(op.actor())
            || !intent.sameRequest(op.requestDigest())) {
            throw invalid("intent " + intent.operationId() + " does not match the operation of its guard");
        }
        final ProfileIntent stored = intentOrNull(id);
        if (stored != null) {
            if (!stored.sameRequest(op.requestDigest())) throw invalid("operation id " + id + " was already used for another request");
            return CommitResult.replayed(id, receiptOf(stored), "operation " + id + " is already prepared (" + stored.phase() + ")");
        }
        if (intent.phase() != ProfileIntent.Phase.PREPARED || intent.revision() != 0L) {
            throw invalid("a new intent is PREPARED at revision 0");
        }
        final List<Step> steps;
        try {
            final OptionalLong namedIntent = named(guard, Domain.RECORD, id, null, null);
            if (namedIntent.isPresent() && namedIntent.getAsLong() != CoopWriteGuard.ABSENT) {
                throw stale("intent " + id + " does not exist but operation " + id + " expected revision " + namedIntent.getAsLong());
            }
            requireIntentClaims(intent, guard);
            steps = plan(intent);
            for (final Step step : steps) {
                final Expected before = step.before();
                if (guard.expected(before.domain(), before.resource(), before.member(), before.epoch()).revision() != before.revision()) {
                    throw invalid("intent " + id + " and its guard expect different revisions of " + before.describe());
                }
                guard.check(before.domain(), before.resource(), before.member(), before.epoch(), storedRevision(before, id));
                final UUID pending = pendingOf(before);
                if (step.reserve()) {
                    if (pending != null) throw stale(before.describe() + " is reserved by operation " + pending + "; nothing was written");
                    requireReservable(before);
                } else {
                    checkReservation(pending, guard, before.describe());
                }
            }
            verifyAfterImages(intent, steps);
            checkCapacity(intent);
            validateLive(guard);
        } catch (final IOException | RuntimeException refused) {
            // Nothing durable names this operation yet (no intent, no staged intent): its staged after-images
            // would otherwise stay under staging/ forever, since recovery sweeps indexed operations only.
            if (stagedIntentOrNull(id) == null) discardStaging(id);
            throw refused;
        }
        final ProfileIndexPage.Entry entry = new ProfileIndexPage.Entry(intent.createdAtMillis(), id);
        final Path intentFile = intentFile(id);
        final byte[] frame = ProfileRecords.frame2(ProfileRecords.TYPE_INTENT, intent.revision(), ProfileRecords.intentPayload2(intent),
            intentFile.toString());
        // A staged copy first: an interrupted prepare's index entries and pins are found through it at open.
        final Path stagedIntent = stagedIntentFile(id);
        FileProfileIo.ensureDirectory(stagedIntent.getParent(), this.root);
        blob(stagedIntent, frame);
        this.indexes.add(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null, entry);
        for (final UUID profile : profilesOf(intent)) {
            this.indexes.add(ProfileIndexPage.Kind.INTENTS_BY_PROFILE, profile, null, null, entry);
        }
        for (final Stream stream : streamsOf(intent)) recomputePins(stream, intent);
        FileProfileIo.ensureDirectory(this.intents, this.root);
        try {
            durable(intentFile, frame);
        } catch (final FileProfileIo.OutcomeUnknown unknown) {
            return CommitResult.unknown(id, "the PREPARED intent " + id + " may or may not be durable (" + unknown.getMessage()
                + "); read intent() before acting");
        }
        int reserved = 0;
        try {
            for (final UUID player : playersOf(intent)) {
                this.indexes.add(ProfileIndexPage.Kind.INTENTS_BY_PLAYER, player, null, null, entry);
            }
            for (final Step step : steps) {
                if (!step.reserve()) continue;
                setReservation(step.before(), id);
                reserved++;
            }
        } catch (final IOException | RuntimeException failed) {
            return CommitResult.unknown(id, "intent " + id + " is PREPARED but installing its reservations stopped after " + reserved
                + " (" + failed.getMessage() + "); resolveIntent completes them, abortIntent clears them");
        }
        return CommitResult.committed(id, null, "intent " + id + " PREPARED with " + reserved + " reservation(s)");
    }

    @Override
    public synchronized CommitResult commitIntent(final UUID operationId, final CoopWriteGuard guard) throws IOException {
        if (format2Write(guard)) throw invalid("the migration does not use intents");
        final ProfileIntent intent = requireIntent(operationId, guard);
        switch (intent.phase()) {
            case FINALIZED -> {
                return CommitResult.replayed(operationId, receiptOf(intent), "operation " + operationId + " is already committed");
            }
            case ABORTED -> throw stale("operation " + operationId + " was aborted; it cannot commit");
            case COMMITTED -> {
                requireIntentClaims(intent, guard);
                checkIntentRevision(guard, intent);
                validateLive(guard);
                return finish(intent, true);
            }
            case RECOVERY_REQUIRED -> {
                if (!committedRecovery(intent)) throw stale("operation " + operationId + " needs recovery: " + intent.outcome());
                requireIntentClaims(intent, guard);
                checkIntentRevision(guard, intent);
                validateLive(guard);
                return finish(intent, true);
            }
            case PREPARED -> {
                // decided below
            }
        }
        requireIntentClaims(intent, guard);
        checkIntentRevision(guard, intent);
        // The deadline is compared against the store clock under the commit lock, before the marker: an invite
        // accept carries the invite's expiry here, so an expired invitation cannot be consumed by a late commit.
        final long storeNow = storeClockMillis();
        if (intent.pastDeadline(storeNow)) {
            throw new ProfileOperationException(ProfileErrorCode.TIMEOUT, "intent " + operationId + " (" + intent.kind()
                + ") passed its deadline " + intent.deadlineMillis() + " (store clock " + storeNow + "); it can only be aborted."
                + " Nothing was committed");
        }
        final List<Step> steps = plan(intent);
        for (final Step step : steps) {
            final Expected after = step.after();
            final long stored = storedRevision(after, operationId);
            if (stored != after.revision()) {
                throw stale(after.describe() + " is at revision " + stored + " but intent " + operationId + " expects "
                    + after.revision() + " after its reservations; nothing was committed");
            }
            guard.check(after.domain(), after.resource(), after.member(), after.epoch(), stored);
            final UUID pending = pendingOf(after);
            if (step.reserve() && !operationId.equals(pending)) {
                throw stale(after.describe() + " does not carry the reservation of " + operationId + "; nothing was committed");
            }
            if (!step.reserve() && pending != null && !guard.reservations().contains(pending)) {
                throw stale(after.describe() + " is reserved by operation " + pending + "; nothing was committed");
            }
        }
        verifyAfterImages(intent, steps);
        validateLive(guard);
        final ProfileIntent committed = intent.at(ProfileIntent.Phase.COMMITTED, "committed");
        try {
            writeIntent(committed);
        } catch (final FileProfileIo.OutcomeUnknown unknown) {
            return CommitResult.unknown(operationId, "the COMMITTED marker of " + operationId + " may or may not be durable ("
                + unknown.getMessage() + "); read intent() and resolve, never compensate");
        }
        return finish(committed, false);
    }

    @Override
    public synchronized CommitResult abortIntent(final UUID operationId, final CoopWriteGuard guard) throws IOException {
        if (format2Write(guard)) throw invalid("the migration does not use intents");
        final ProfileIntent intent = requireIntent(operationId, guard);
        switch (intent.phase()) {
            case ABORTED -> {
                cleanupAfterTerminal(intent);
                return CommitResult.replayed(operationId, receiptOf(intent), "operation " + operationId + " is already aborted");
            }
            case COMMITTED, FINALIZED -> throw stale("operation " + operationId + " is committed; it rolls forward and cannot abort");
            case RECOVERY_REQUIRED -> {
                if (committedRecovery(intent)) throw stale("operation " + operationId + " is committed; it rolls forward and cannot abort");
            }
            case PREPARED -> {
                // aborted below
            }
        }
        requireIntentClaims(intent, guard);
        checkIntentRevision(guard, intent);
        final List<Step> steps = plan(intent);
        for (final Step step : steps) guard.expected(step.after().domain(), step.after().resource(), step.after().member(), step.after().epoch());
        validateLive(guard);
        int cleared = 0;
        for (final Step step : steps) {
            if (step.reserve() && clearReservation(step, operationId)) cleared++;
        }
        final ProfileIntent aborted = intent.at(ProfileIntent.Phase.ABORTED, "aborted");
        try {
            writeIntent(aborted);
        } catch (final FileProfileIo.OutcomeUnknown unknown) {
            return CommitResult.unknown(operationId, "the ABORTED marker of " + operationId + " may or may not be durable ("
                + unknown.getMessage() + "); its reservations are cleared; abort again");
        }
        ProfileReceipt receipt = null;
        try {
            receipt = writeReceipts(aborted, ProfileReceipt.Outcome.ABORTED);
        } catch (final IOException failed) {
            SourbyLogger.warn("AWF MultiProfile: the ABORTED receipts of " + operationId + " were not written: " + failed.getMessage()
                + " (the ABORTED intent answers a replay)");
        }
        cleanupAfterTerminal(aborted);
        return CommitResult.committed(operationId, receipt, "operation " + operationId + " ABORTED; " + cleared
            + " reservation(s) cleared");
    }

    @Override
    public synchronized CommitResult resolveIntent(final UUID operationId, final CoopWriteGuard guard) throws IOException {
        if (format2Write(guard)) throw invalid("the migration does not use intents");
        final ProfileIntent intent = requireIntent(operationId, guard);
        if (!intent.pending()) {
            cleanupAfterTerminal(intent);
            return CommitResult.replayed(operationId, receiptOf(intent), "operation " + operationId + " is " + intent.phase());
        }
        requireIntentClaims(intent, guard);
        checkIntentRevision(guard, intent);
        validateLive(guard);
        if (intent.committed() || committedRecovery(intent)) return finish(intent, true);
        final boolean complete = completePrepare(intent);
        return CommitResult.unknown(operationId, "operation " + operationId + " is PREPARED and undecided"
            + (complete ? "; its reservations are installed: commit or abort it" : "; a reserved record moved, so it can only abort"));
    }

    @Override
    public synchronized Optional<ProfileIntent> intent(final UUID operationId) throws IOException {
        format2Read();
        return Optional.ofNullable(intentOrNull(operationId));
    }

    @Override
    public synchronized Optional<ProfileReceipt> receipt(final UUID resource, final UUID operationId) throws IOException {
        format2Read();
        return Optional.ofNullable(receiptOrNull(resource, operationId));
    }

    /**
     * {@link ProfileIndexPage.Kind#SNAPSHOTS_BY_STREAM} is served from the stream's bounded directory (at
     * most {@value #MAX_STREAM_FILES} files, ordered by the publishing revision {@code R}, which the entry's
     * time field carries); the other kinds from {@link FileProfileIndex}.
     */
    @Override
    public synchronized ProfileIndexPage index(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member,
                                               final UUID epoch, final ProfileIndexPage.Cursor cursor) throws IOException {
        format2Read();
        if (kind.streamScoped() != (member != null) || kind.streamScoped() != (epoch != null)) {
            throw invalid(kind + (kind.streamScoped() ? " needs" : " takes no") + " member and epoch");
        }
        if (kind == ProfileIndexPage.Kind.SNAPSHOTS_BY_STREAM) return streamSnapshotPage(resource, member, epoch, cursor);
        return this.indexes.list(kind, resource, member, epoch, cursor);
    }

    /** One page of a member stream's snapshot ids from its directory; generation 0 (the directory is never rebuilt). */
    private ProfileIndexPage streamSnapshotPage(final UUID profileId, final UUID member, final UUID epoch,
                                                final ProfileIndexPage.Cursor cursor) throws IOException {
        final ProfileIndexPage.Kind kind = ProfileIndexPage.Kind.SNAPSHOTS_BY_STREAM;
        if (cursor != null && (cursor.kind() != kind || !cursor.resource().equals(profileId) || cursor.generation() != 0L)) {
            throw invalid("the listing cursor belongs to another index or generation; restart the listing");
        }
        final Path directory = streamDirectory(profileId, member, epoch);
        final List<ProfileIndexPage.Entry> all = new ArrayList<>();
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> found = Files.newDirectoryStream(directory, "*.awfp")) {
                for (final Path file : found) {
                    if (all.size() >= MAX_STREAM_FILES) {
                        throw new IOException("member stream " + directory + " holds more than " + MAX_STREAM_FILES
                            + " snapshot files; the listing is refused until it is pruned offline");
                    }
                    final UUID id = snapshotIdOf(file);
                    final long revision = revisionOf(file.getFileName().toString());
                    if (id == null || revision < 0L) throw new IOException("member stream " + directory + " holds a file with an unreadable name: " + file.getFileName());
                    all.add(new ProfileIndexPage.Entry(revision, id, 0L));
                }
            }
        }
        all.sort(null);
        final List<ProfileIndexPage.Entry> page = new ArrayList<>(ProfileIndexPage.LISTING_PAGE);
        for (final ProfileIndexPage.Entry entry : all) {
            if (cursor != null) {
                final int byRevision = Long.compare(entry.createdAtMillis(), cursor.lastCreatedAtMillis());
                if (byRevision < 0 || (byRevision == 0 && entry.id().compareTo(cursor.lastId()) <= 0)) continue;
            }
            page.add(entry);
            if (page.size() >= ProfileIndexPage.LISTING_PAGE) break;
        }
        try {
            return new ProfileIndexPage(kind, profileId, member, epoch, 0L, 0, 0L, page);
        } catch (final IllegalArgumentException corrupt) {
            throw new IOException("member stream " + directory + " lists inconsistently: " + corrupt.getMessage());
        }
    }

    @Override
    public synchronized Optional<ProfileInvite> invite(final UUID inviteId) throws IOException {
        final Access access = format2Read();
        final Path file = this.invites.resolve(inviteId + ".awfp");
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(ProfileRecords.invite2(read(file, ProfileRecords.TYPE_INVITE, access), inviteId, file.toString()));
    }

    @Override
    public synchronized Optional<KickVote> vote(final UUID voteId) throws IOException {
        final Access access = format2Read();
        final Path file = this.votes.resolve(voteId + ".awfp");
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(ProfileRecords.vote2(read(file, ProfileRecords.TYPE_VOTE, access), voteId, file.toString()));
    }

    @Override
    public synchronized Optional<ProfileSalvage> salvage(final UUID salvageId) throws IOException {
        final Access access = format2Read();
        final Path file = this.salvage.resolve(salvageId + ".awfp");
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(ProfileRecords.salvage2(read(file, ProfileRecords.TYPE_SALVAGE, access), salvageId, file.toString()));
    }

    // ================================================================== ProfileMigrationStore

    /** This process holds the directory's exclusive OS lock (constructor); no other writer can be open on it. */
    @Override
    public synchronized void requireNoOtherWriter() throws IOException {
        if (this.closed || this.lock == null || !this.lock.isValid()) {
            throw new IOException("the exclusive lock on " + this.root + " is not held; the migration does not start");
        }
    }

    @Override
    public synchronized List<UUID> migrationAccountIds() throws IOException {
        access();
        return idsIn(this.accounts);
    }

    @Override
    public synchronized List<UUID> migrationProfileIds() throws IOException {
        access();
        return idsIn(this.profiles);
    }

    @Override
    public synchronized List<UUID> legacyProfileSnapshotIds(final UUID profileId) throws IOException {
        access();
        final List<UUID> ids = new ArrayList<>();
        for (final Path file : snapshotFiles(profileId)) {
            final UUID id = snapshotIdOf(file);
            if (id != null && formatOf(file) == ProfileRecords.FORMAT) ids.add(id);
        }
        return List.copyOf(ids);
    }

    @Override
    public synchronized int legacyArchiveCount() throws IOException {
        access();
        int count = 0;
        for (final Path file : archiveFiles()) {
            if (formatOf(file) == ProfileRecords.FORMAT) count++;
        }
        return count;
    }

    @Override
    public synchronized int convertLegacyArchives() throws IOException {
        requireMigrating("convertLegacyArchives");
        int converted = 0;
        for (final Path file : archiveFiles()) {
            if (formatOf(file) != ProfileRecords.FORMAT) continue;
            final String name = file.getFileName().toString();
            final UUID player = uuidOrNull(name.substring(0, name.indexOf('.')));
            if (player == null) continue;
            final ProfileSnapshot archive = ProfileRecords.snapshot(read(file, TYPE_SNAPSHOT, Access.LEGACY), player, file.toString());
            durable(file, ProfileRecords.frame2(TYPE_SNAPSHOT, archive.revision(), ProfileRecords.legacyArchivePayload2(archive),
                file.toString()));
            converted++;
        }
        return converted;
    }

    @Override
    public synchronized void writeMigratedMemberSnapshot(final ProfileSnapshot snapshot) throws IOException {
        requireMigrating("writeMigratedMemberSnapshot");
        if (!snapshot.member()) throw new IllegalArgumentException("a migrated member snapshot names member and epoch");
        final Path file = streamDirectory(snapshot.profileId(), snapshot.memberId(), snapshot.membershipEpoch())
            .resolve(snapshot.revision() + "-" + snapshot.snapshotId() + ".awfp");
        final byte[] frame = ProfileRecords.frame2(ProfileRecords.TYPE_MEMBER_SNAPSHOT, snapshot.revision(),
            ProfileRecords.memberSnapshotPayload2(snapshot), file.toString());
        if (Files.isRegularFile(file)) {
            if (Arrays.equals(FileProfileIo.readBounded(file, frame.length), frame)) return;
            throw new IOException("member snapshot " + file + " already exists with other content; the migration stops");
        }
        FileProfileIo.ensureDirectory(file.getParent(), this.root);
        blob(file, frame);
    }

    @Override
    public synchronized void writeMigratedHead(final MemberStateHead head) throws IOException {
        requireMigrating("writeMigratedHead");
        final MemberStateHead existing = headOrNull(head.profileId(), head.memberId(), head.epoch());
        if (existing != null) {
            if (existing.equals(head)) return;
            throw new IOException("member stream " + head.describe() + " already has another head; the migration stops");
        }
        final Path file = headFile(head.profileId(), head.memberId(), head.epoch());
        FileProfileIo.ensureDirectory(file.getParent(), this.root);
        blob(file, ProfileRecords.frame2(ProfileRecords.TYPE_MEMBER_HEAD, head.revision(), ProfileRecords.memberHeadPayload2(head),
            file.toString()));
    }

    @Override
    public synchronized int dropLegacyProfileSnapshots(final UUID profileId) throws IOException {
        requireMigrating("dropLegacyProfileSnapshots");
        final Path profileFile = profileFile(profileId);
        if (!Files.isRegularFile(profileFile) || formatOf(profileFile) != ProfileRecords.FORMAT_2) {
            throw new IOException("profile " + profileId + " is not converted yet; its format-1 snapshots are kept");
        }
        int dropped = 0;
        for (final Path file : snapshotFiles(profileId)) {
            if (formatOf(file) != ProfileRecords.FORMAT) continue;
            Files.deleteIfExists(file);
            dropped++;
        }
        if (dropped > 0) FileProfileIo.forceDirectory(this.snapshots.resolve(profileId.toString()));
        return dropped;
    }

    @Override
    public synchronized int retireTerminalJournals() throws IOException {
        requireMigrating("retireTerminalJournals");
        if (!quarantinedJournals().isEmpty()) {
            throw new IOException("unreadable journals are set aside (" + String.join(", ", quarantinedJournals())
                + "); they are not retired");
        }
        int retired = 0;
        for (final UUID transitionId : List.copyOf(this.journalFiles.keySet())) {
            final Path file = this.journalFiles.get(transitionId);
            if (file == null || !Files.isRegularFile(file) || formatOf(file) != ProfileRecords.FORMAT) continue;
            final ProfileTransition journal = readIndexed(transitionId);
            if (journal == null) continue;
            if (!journal.terminal()) {
                throw new IOException("journal " + transitionId + " of " + journal.playerId() + " is " + journal.phase()
                    + "; the migration does not retire an unresolved switch");
            }
            Files.deleteIfExists(file);
            unindex(transitionId);
            retired++;
        }
        if (retired > 0) FileProfileIo.forceDirectory(this.transitions);
        return retired;
    }

    @Override
    public synchronized String backup(final String backupDir) throws IOException {
        if (access() == Access.READY) throw refused("the store is already format 2; there is no format-1 store to back up");
        return FileProfileBackup.create(this.root, backupDir, LOCK_FILE).toString();
    }

    @Override
    public synchronized Optional<String> verifiedBackup(final String backupDir) throws IOException {
        return FileProfileBackup.find(this.root, backupDir).map(Path::toString);
    }

    @Override
    public synchronized List<String> legacyRecordsRemaining(final int limit) throws IOException {
        access();
        return legacyRecordsRemainingUnchecked(limit);
    }

    /**
     * Format-1 records the migration cannot convert and the final verification would therefore refuse: a
     * binding or a snapshot directory whose profile has no record, a format-1 file in a format-2-only
     * directory, and files whose names fit no record of the layout. Checked by the plan, so a dry run
     * reports them and an apply refuses before the backup and the IN_PROGRESS marker.
     */
    @Override
    public synchronized List<String> unconvertibleRecords(final int limit) throws IOException {
        access();
        final List<String> found = new ArrayList<>();
        final Path indexes = this.root.resolve("indexes");
        for (final Path directory : List.of(this.accounts, this.profiles, this.bindings, this.snapshots, this.transitions,
            this.shared, this.invites, this.votes, this.salvage, this.members, this.intents, this.receipts, indexes)) {
            if (!Files.isDirectory(directory)) continue;
            try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
                for (final Path file : (Iterable<Path>) walk::iterator) {
                    if (found.size() >= limit) return List.copyOf(found);
                    if (!file.getFileName().toString().endsWith(".awfp") || !Files.isRegularFile(file)) continue;
                    if (formatOf(file) == ProfileRecords.FORMAT_2) continue;
                    final String why = unconvertible(directory, file);
                    if (why != null) found.add(this.root.relativize(file) + " (" + why + ")");
                }
            }
        }
        return List.copyOf(found);
    }

    /** Why a format-1 file under {@code directory} has no conversion, or {@code null} when the migration converts it. */
    private String unconvertible(final Path directory, final Path file) {
        final String name = file.getFileName().toString();
        final String stem = name.substring(0, name.length() - ".awfp".length());
        if (directory.equals(this.accounts)) {
            if (!file.getParent().equals(directory)) return "unexpected file below accounts/";
            final int dot = stem.indexOf('.');
            final UUID player = uuidOrNull(dot < 0 ? stem : stem.substring(0, dot));
            if (player == null) return "its name is not a player id";
            if (dot < 0 || stem.substring(dot).matches("\\.legacy(\\.\\d+)?")) return null;
            return "its name fits no account or archive record";
        }
        if (directory.equals(this.profiles)) {
            return file.getParent().equals(directory) && uuidOrNull(stem) != null ? null : "its name is not a profile id";
        }
        if (directory.equals(this.bindings)) {
            final UUID profile = file.getParent().equals(directory) ? uuidOrNull(stem) : null;
            if (profile == null) return "its name is not a profile id";
            return Files.isRegularFile(this.profiles.resolve(profile + ".awfp")) ? null : "profile " + profile + " has no record";
        }
        if (directory.equals(this.snapshots)) {
            final Path relative = directory.relativize(file);
            if (relative.getNameCount() != 2) return "a format-1 snapshot lives in snapshots/<profile>/";
            final UUID profile = uuidOrNull(relative.getName(0).toString());
            if (profile == null) return "its directory is not a profile id";
            return Files.isRegularFile(this.profiles.resolve(profile + ".awfp")) ? null : "profile " + profile + " has no record";
        }
        if (directory.equals(this.transitions)) {
            return file.getParent().equals(directory) ? null : "unexpected file below transitions/";
        }
        return "a format-1 record in the format-2-only directory " + this.root.relativize(directory) + "/";
    }

    private List<String> legacyRecordsRemainingUnchecked(final int limit) throws IOException {
        final List<String> found = new ArrayList<>();
        for (final Path directory : List.of(this.accounts, this.profiles, this.bindings, this.snapshots, this.transitions,
            this.shared, this.invites, this.votes, this.salvage, this.members, this.intents, this.receipts,
            this.root.resolve("indexes"))) {
            if (!Files.isDirectory(directory)) continue;
            try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
                for (final Path file : (Iterable<Path>) walk::iterator) {
                    if (found.size() >= limit) return List.copyOf(found);
                    if (!file.getFileName().toString().endsWith(".awfp") || !Files.isRegularFile(file)) continue;
                    if (formatOf(file) != ProfileRecords.FORMAT_2) found.add(this.root.relativize(file).toString());
                }
            }
        }
        return List.copyOf(found);
    }

    // ================================================================== gate

    private enum Access { LEGACY, MIGRATING, READY }

    /** Re-reads the marker file and sets {@link #markerProblem} when this build may not use the store. */
    private void refreshMarker() {
        if (!Files.exists(this.formatFile)) {
            if (this.sawFormat2) {
                this.marker = null;
                this.markerProblem = "the store format marker " + this.formatFile + " disappeared after this process read a"
                    + " format-2 marker; nothing is read or written until it is restored";
                return;
            }
            this.marker = StoreFormat.Marker.legacy();
            this.markerProblem = this.format2Residue;
            return;
        }
        try {
            final StoreFormat.Marker read = StoreFormat.decode(FileProfileIo.readBounded(this.formatFile, MAX_MARKER),
                this.formatFile.toString());
            this.marker = read;
            if (read.format() >= StoreFormat.CURRENT) this.sawFormat2 = true;
            this.indexes.strict(!read.legacyFormat());
            final boolean unknown = read.format() > StoreFormat.CURRENT
                || (read.format() == StoreFormat.CURRENT && (read.schema() > StoreFormat.MAX_SCHEMA
                    || read.minimumBuild() > StoreFormat.READS_UP_TO_BUILD));
            this.markerProblem = unknown ? StoreFormat.gate(read).reason() : null;
        } catch (final IOException unreadable) {
            this.marker = null;
            this.markerProblem = "the store format marker " + this.formatFile + " cannot be read (" + unreadable.getMessage()
                + "); this build refuses to read or write the store (it may belong to a newer build)";
        }
    }

    private Access access() {
        refreshMarker();
        if (this.markerProblem != null) throw refused(this.markerProblem);
        if (this.marker.legacyFormat()) return Access.LEGACY;
        return this.marker.migration() == StoreFormat.MigrationState.IN_PROGRESS ? Access.MIGRATING : Access.READY;
    }

    private void requireFormat1Write(final String method) {
        switch (access()) {
            case LEGACY -> {
                // Build 50 behaviour.
            }
            case MIGRATING -> throw refused("a format-2 migration is in progress; " + method + " is refused");
            case READY -> throw refused("the store is format 2; the format-1 writer " + method + " is refused, use the format-2"
                + " operations");
        }
    }

    private void requireFlatSnapshots(final String method) {
        if (access() == Access.READY) {
            throw refused("the store is format 2; " + method + " reads format-1 profile snapshots, which a format-2 store does not"
                + " use (member streams: memberHead + memberSnapshot)");
        }
    }

    private void requireMigrating(final String method) {
        if (access() != Access.MIGRATING) {
            throw refused(method + " runs only while the store marker says a migration is in progress");
        }
    }

    /** The gate of a format-2 mutation; {@code true} for an accepted migration write. */
    private boolean format2Write(final CoopWriteGuard guard) {
        final boolean migration = guard.operation().kind() == CoopOperation.Kind.MIGRATION;
        switch (access()) {
            case LEGACY -> throw new ProfileOperationException(ProfileErrorCode.MIGRATION_REQUIRED, "the profile store "
                + this.root + " is format 1; format-2 operations need the offline migration first. Nothing was written");
            case MIGRATING -> {
                if (!migration) throw refused("a format-2 migration of " + this.root + " is in progress; nothing is written until"
                    + " it finishes or the backup is restored");
            }
            case READY -> {
                if (migration) throw invalid("migration writes are accepted only while the store marker says IN_PROGRESS");
            }
        }
        requireTransactional();
        return migration;
    }

    private Access format2Read() {
        final Access access = access();
        if (access == Access.LEGACY) {
            throw new ProfileOperationException(ProfileErrorCode.MIGRATION_REQUIRED, "the profile store " + this.root
                + " is format 1; it holds no format-2 records until the offline migration ran");
        }
        return access;
    }

    private void requireTransactional() {
        if (this.transactional == null) {
            try {
                FileProfileIo.probe(this.root);
                this.transactional = Boolean.TRUE;
            } catch (final FileProfileIo.DurabilityUnsupported unsupported) {
                this.transactional = Boolean.FALSE;
                this.transactionalProblem = unsupported.getMessage();
                SourbyLogger.error("AWF MultiProfile: " + unsupported.getMessage() + "; co-op transactional writes are disabled");
            }
        }
        if (!this.transactional) {
            throw refused("co-op transactional writes are disabled for " + this.root + ": " + this.transactionalProblem);
        }
    }

    // ================================================================== format-2 internals

    private record Step(Expected before, Expected after, boolean reserve) {}

    private record Stream(UUID profile, UUID member, UUID epoch) {}

    /** A staged after-image: its frame bytes, the decoded record's reservation and its canonical path. */
    private record Staged(byte[] bytes, UUID pendingIntent, Path target, Object record) {

        Staged withBytes(final byte[] frame) {
            return new Staged(frame, this.pendingIntent, this.target, this.record);
        }
    }

    private CommitResult publish(final Path file, final byte type, final long revision, final byte[] payload,
                                 final UUID receiptResource, final CoopWriteGuard guard, final boolean migration,
                                 final String what) throws IOException {
        final UUID op = guard.operation().operationId();
        final byte[] frame = ProfileRecords.frame2(type, revision, payload, file.toString());
        FileProfileIo.ensureDirectory(file.getParent(), this.root);
        try {
            durable(file, frame);
        } catch (final FileProfileIo.OutcomeUnknown unknown) {
            return CommitResult.unknown(op, what + ": " + unknown.getMessage() + "; read it back before acting");
        }
        if (migration) return CommitResult.committed(op, null, what + " converted to format 2 at revision " + revision);
        if (!guard.operation().kind().permanentReceipt()) {
            // SAVE_MEMBER and LOGIN leave no permanent receipt (COOP-DESIGN §9): the head revision and the
            // session generation answer a replay, and one file per save would grow the receipts directory forever.
            return CommitResult.committed(op, null, what + " committed at revision " + revision);
        }
        final ProfileReceipt receipt;
        try {
            // Receipt results are empty in both backends: callers rely on presence, id, digest, kind and outcome only.
            receipt = writeReceipt(receiptResource, guard.operation(), ProfileReceipt.Outcome.COMMITTED, new byte[0]);
        } catch (final IOException failed) {
            // The record is durable (renamed and its directory forced): the outcome is known, only the receipt is missing.
            SourbyLogger.warn("AWF MultiProfile: " + what + " committed at revision " + revision + " but its receipt was not written ("
                + failed.getMessage() + "); a retry with the same operation is answered from the record");
            return CommitResult.committed(op, null, what + " committed at revision " + revision + "; its receipt was not written");
        }
        return CommitResult.committed(op, receipt, what + " committed at revision " + revision);
    }

    /** Replay of a direct commit: the stored receipt, or a refusal when the id belongs to an intent or another request. */
    private CommitResult replayDirect(final UUID resource, final CoopWriteGuard guard) throws IOException {
        final CoopOperation op = guard.operation();
        final ProfileReceipt receipt = receiptOrNull(resource, op.operationId());
        if (receipt != null) {
            if (!receipt.sameRequest(op.requestDigest())) {
                throw invalid("operation id " + op.operationId() + " was already used for another request");
            }
            return CommitResult.replayed(op.operationId(), receipt, "operation " + op.operationId() + " was already "
                + receipt.outcome());
        }
        if (Files.exists(intentFile(op.operationId()))) {
            throw invalid("operation id " + op.operationId() + " belongs to an intent; commit it with commitIntent");
        }
        return null;
    }

    /**
     * A direct commit is replayed only through its stored receipt ({@link #replayDirect}): a record that happens
     * to hold the same bytes is not evidence that <em>this</em> operation wrote them (two same-base writes of
     * identical content, or a stale claim holder, would otherwise be answered REPLAYED before any guard ran). A
     * commit whose receipt was not written is settled by its caller from the record itself; a retry is refused
     * as stale by the vector.
     */
    private ProfileReceipt writeReceipt(final UUID resource, final CoopOperation op, final ProfileReceipt.Outcome outcome,
                                        final byte[] result) throws IOException {
        final ProfileReceipt existing = receiptOrNull(resource, op.operationId());
        if (existing != null) return existing;
        final ProfileReceipt receipt = new ProfileReceipt(op.operationId(), op.requestDigest(), op.kind(), outcome, result,
            System.currentTimeMillis());
        final ProfileReceipt.Page page = new ProfileReceipt.Page(resource, 0, 0L, List.of(receipt));
        final Path file = receiptFile(resource, op.operationId());
        FileProfileIo.ensureDirectory(file.getParent(), this.root);
        durable(file, ProfileRecords.frame2(ProfileRecords.TYPE_PAGE, 0L, ProfileRecords.receiptPagePayload2(page), file.toString()));
        pruneReceipts(file.getParent(), file);
        return receipt;
    }

    /**
     * Keeps at most {@link ProfileReceipt#RETAINED_PER_RESOURCE} receipt files per resource (COOP-DESIGN §9 bounds):
     * the oldest by modification time are deleted, never the one just written. A dropped receipt can no longer
     * replay or refuse its operation id. Best effort: a failure is logged and retried at the next receipt.
     */
    private void pruneReceipts(final Path directory, final Path keep) {
        try {
            final List<Path> files = new ArrayList<>();
            try (java.nio.file.DirectoryStream<Path> found = Files.newDirectoryStream(directory, "*.awfp")) {
                for (final Path file : found) files.add(file);
            }
            final int excess = files.size() - ProfileReceipt.RETAINED_PER_RESOURCE;
            if (excess <= 0) return;
            final Map<Path, Long> modified = new HashMap<>();
            for (final Path file : files) modified.put(file, Files.getLastModifiedTime(file).toMillis());
            files.sort(Comparator.comparing((Path file) -> modified.get(file)).thenComparing(file -> file.getFileName().toString()));
            int deleted = 0;
            for (final Path file : files) {
                if (deleted >= excess) break;
                if (file.equals(keep)) continue;
                Files.deleteIfExists(file);
                deleted++;
            }
            FileProfileIo.forceDirectory(directory);
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: pruning the receipts in " + directory + " failed (" + failed.getMessage()
                + "); they are pruned after the next receipt");
        }
    }

    /** Receipts of an intent on its actor, accounts and profiles; returns the actor's. */
    private ProfileReceipt writeReceipts(final ProfileIntent intent, final ProfileReceipt.Outcome outcome) throws IOException {
        final CoopOperation op = operationOf(intent);
        final Set<UUID> resources = new LinkedHashSet<>();
        resources.add(intent.actor());
        for (final Expected entry : intent.expectedAfter()) {
            if (entry.domain() == Domain.ACCOUNT || entry.domain() == Domain.METADATA) resources.add(entry.resource());
        }
        ProfileReceipt actor = null;
        for (final UUID resource : resources) {
            final ProfileReceipt receipt = writeReceipt(resource, op, outcome, new byte[0]);
            if (actor == null) actor = receipt;
        }
        return actor;
    }

    private ProfileReceipt receiptOf(final ProfileIntent intent) throws IOException {
        return receiptOrNull(intent.actor(), intent.operationId());
    }

    private static CoopOperation operationOf(final ProfileIntent intent) {
        return new CoopOperation(intent.operationId(), intent.kind(), intent.actor(), intent.requestDigest(), intent.createdAtMillis());
    }

    // ------------------------------------------------------------------ guard checks

    private void compareVector(final CoopWriteGuard guard) throws IOException {
        final UUID op = guard.operation().operationId();
        for (final Expected entry : guard.vector()) {
            guard.check(entry.domain(), entry.resource(), entry.member(), entry.epoch(), storedRevision(entry, op));
        }
    }

    /** The stored revision of the record an entry names: missing accounts are 0, other missing records {@code ABSENT}. */
    private long storedRevision(final Expected entry, final UUID op) throws IOException {
        return switch (entry.domain()) {
            case ACCOUNT -> {
                final PlayerAccount account = accountOrNull(entry.resource());
                yield account == null ? 0L : account.revision();
            }
            case METADATA -> {
                final AuroraProfile profile = profileOrNull(entry.resource());
                yield profile == null ? CoopWriteGuard.ABSENT : profile.revision();
            }
            case SHARED -> {
                final ProfileSharedState state = sharedOrNull(entry.resource());
                yield state == null ? CoopWriteGuard.ABSENT : state.revision();
            }
            case HEAD -> {
                final MemberStateHead head = headOrNull(entry.resource(), entry.member(), entry.epoch());
                yield head == null ? CoopWriteGuard.ABSENT : head.revision();
            }
            case RECORD -> recordRevision(entry.resource(), op);
        };
    }

    private long recordRevision(final UUID id, final UUID op) throws IOException {
        if (id.equals(op)) {
            final ProfileIntent intent = intentOrNull(id);
            return intent == null ? CoopWriteGuard.ABSENT : intent.revision();
        }
        final ProfileRecords.Binding2 binding = bindingOrNull(id);
        if (binding != null) return binding.revision();
        final Access access = access();
        final Path invite = this.invites.resolve(id + ".awfp");
        if (Files.isRegularFile(invite)) return ProfileRecords.invite2(read(invite, ProfileRecords.TYPE_INVITE, access), id, invite.toString()).revision();
        final Path vote = this.votes.resolve(id + ".awfp");
        if (Files.isRegularFile(vote)) return ProfileRecords.vote2(read(vote, ProfileRecords.TYPE_VOTE, access), id, vote.toString()).revision();
        final Path salvaged = this.salvage.resolve(id + ".awfp");
        if (Files.isRegularFile(salvaged)) {
            return ProfileRecords.salvage2(read(salvaged, ProfileRecords.TYPE_SALVAGE, access), id, salvaged.toString()).revision();
        }
        return CoopWriteGuard.ABSENT;
    }

    private static OptionalLong named(final CoopWriteGuard guard, final Domain domain, final UUID resource, final UUID member,
                                      final UUID epoch) {
        for (final Expected entry : guard.vector()) {
            if (entry.domain() == domain && entry.resource().equals(resource) && Objects.equals(entry.member(), member)
                && Objects.equals(entry.epoch(), epoch)) {
                return OptionalLong.of(entry.revision());
            }
        }
        return OptionalLong.empty();
    }

    private static void checkReservation(final UUID pending, final CoopWriteGuard guard, final String what) {
        if (pending != null && !guard.reservations().contains(pending)) {
            throw stale(what + " is reserved by operation " + pending + "; resolve that operation first. Nothing was written");
        }
    }

    private static void requireNext(final long after, final long expected, final String what) {
        final long next = expected == CoopWriteGuard.ABSENT ? 0L : CoopWriteGuard.next(expected, what);
        if (after != next) {
            throw invalid(what + " must be written at revision " + next + " (expected " + expected + " + 1), not " + after);
        }
    }

    private static void requireProfileClaim(final CoopWriteGuard guard, final UUID profileId) {
        if (guard.profile() == null || !guard.profile().profileId().equals(profileId)) {
            throw guard.fenced("the profile claim P", "the write changes profile " + profileId + " but carries no profile claim on it");
        }
    }

    private static void requirePlayerClaim(final CoopWriteGuard guard, final UUID playerId) {
        final boolean actor = guard.actor() != null && guard.actor().playerId().equals(playerId);
        final boolean affected = guard.affected() != null && guard.affected().playerId().equals(playerId);
        if (!actor && !affected) {
            throw guard.fenced("the player claim Q", "the write changes account " + playerId + " but carries no player claim on it");
        }
    }

    private static void requireActor(final CoopWriteGuard guard) {
        if (guard.actor() == null) throw guard.fenced("the player claim Q", "the write names no acting player");
    }

    /** Build 51 metadata and binding changes are the OWNER's. */
    private static void requireOwner(final AuroraProfile profile, final UUID actor, final String what) {
        final ProfileMember row = profile == null ? null : profile.member(actor);
        if (row == null || row.role() != ProfileRole.OWNER) {
            throw new ProfileOperationException(ProfileErrorCode.NOT_OWNED, actor + " is not the OWNER of the profile; " + what
                + " was not written");
        }
    }

    static boolean sameMemberships(final List<ProfileMember> left, final List<ProfileMember> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            final ProfileMember a = left.get(i);
            final ProfileMember b = right.get(i);
            if (!a.playerId().equals(b.playerId()) || !a.epoch().equals(b.epoch()) || a.role() != b.role()) return false;
        }
        return true;
    }

    /** A new profile's name must differ by {@link CuteNames#key} from every other profile of its owner's account. */
    private void requireUniqueName(final AuroraProfile created) throws IOException {
        final PlayerAccount account = accountOrNull(created.accountId());
        if (account == null) return;
        final String key = CuteNames.key(created.name());
        for (final UUID other : account.profileIds()) {
            if (other.equals(created.id())) continue;
            final AuroraProfile profile = profileOrNull(other);
            if (profile != null && CuteNames.key(profile.name()).equals(key)) {
                throw new ProfileOperationException(ProfileErrorCode.NAME_TAKEN, "the account " + created.accountId()
                    + " already has a profile named " + profile.name() + "; nothing was written");
            }
        }
    }

    private void validateLive(final CoopWriteGuard guard) throws IOException {
        if (guard.profile() == null && guard.actor() == null && guard.affected() == null) return;
        final FileProfileClaims validator = this.claims;
        if (validator == null) {
            throw guard.fenced("the claims", "no claim validator is attached to the FILE repository, so no claim can be checked");
        }
        if (guard.profile() != null && !validator.profileClaimLive(guard.profile())) {
            throw guard.fenced("the profile claim P on " + guard.profile().profileId(), "not live, or not this session and fence");
        }
        if (guard.actor() != null && !validator.playerClaimLive(guard.actor())) {
            throw guard.fenced("the player claim Q on " + guard.actor().playerId(), "not live, or not this session and fence");
        }
        if (guard.affected() != null && !validator.playerClaimLive(guard.affected())) {
            throw guard.fenced("the player claim Q on " + guard.affected().playerId(), "not live, or not this session and fence");
        }
    }

    // ------------------------------------------------------------------ intent internals

    private ProfileIntent requireIntent(final UUID operationId, final CoopWriteGuard guard) throws IOException {
        if (!guard.operation().operationId().equals(operationId)) {
            throw invalid("the guard belongs to operation " + guard.operation().operationId() + ", not " + operationId);
        }
        final ProfileIntent intent = intentOrNull(operationId);
        if (intent == null) throw invalid("there is no intent " + operationId);
        if (!intent.sameRequest(guard.operation().requestDigest())) {
            throw invalid("operation id " + operationId + " was prepared for another request");
        }
        return intent;
    }

    private static void checkIntentRevision(final CoopWriteGuard guard, final ProfileIntent intent) {
        guard.check(Domain.RECORD, intent.operationId(), null, null, intent.revision());
    }

    /** A RECOVERY_REQUIRED intent this repository marked after its COMMITTED marker (the only kind it writes). */
    private static boolean committedRecovery(final ProfileIntent intent) {
        return intent.phase() == ProfileIntent.Phase.RECOVERY_REQUIRED && intent.outcome() != null
            && intent.outcome().startsWith("committed");
    }

    /** The claims an intent was admitted under must be carried again, on the same resources (any live fence). */
    private static void requireIntentClaims(final ProfileIntent intent, final CoopWriteGuard guard) {
        if (intent.profileClaim() != null) requireProfileClaim(guard, intent.profileClaim().id());
        for (final ProfileIntent.ClaimRef claim : intent.playerClaims()) requirePlayerClaim(guard, claim.id());
    }

    private static List<Step> plan(final ProfileIntent intent) {
        final List<Step> steps = new ArrayList<>(intent.expectedAfter().size());
        for (final Expected after : intent.expectedAfter()) {
            if (after.domain() == Domain.RECORD && after.resource().equals(intent.operationId())) continue;
            Expected before = null;
            for (final Expected candidate : intent.expectedBefore()) {
                if (candidate.sameRecord(after)) before = candidate;
            }
            if (before == null) throw invalid("intent " + intent.operationId() + " names " + after.describe() + " after but not before");
            final boolean reserve;
            if (after.revision() == before.revision()) {
                reserve = false;
            } else if (before.revision() != CoopWriteGuard.ABSENT && after.revision() == before.revision() + 1L) {
                reserve = true;
            } else {
                throw invalid("intent " + intent.operationId() + " moves " + after.describe() + " from " + before.revision() + " to "
                    + after.revision() + "; a reservation advances a record exactly once");
            }
            steps.add(new Step(before, after, reserve));
        }
        for (final Expected before : intent.expectedBefore()) {
            if (before.domain() == Domain.RECORD && before.resource().equals(intent.operationId())) continue;
            boolean found = false;
            for (final Step step : steps) found |= step.before().sameRecord(before);
            if (!found) throw invalid("intent " + intent.operationId() + " names " + before.describe() + " before but not after");
        }
        return List.copyOf(steps);
    }

    private void requireReservable(final Expected entry) throws IOException {
        final boolean reservable = switch (entry.domain()) {
            case ACCOUNT -> true;
            case METADATA -> profileOrNull(entry.resource()) != null;
            case SHARED -> sharedOrNull(entry.resource()) != null;
            case HEAD -> headOrNull(entry.resource(), entry.member(), entry.epoch()) != null;
            case RECORD -> bindingOrNull(entry.resource()) != null;
        };
        if (!reservable) throw invalid(entry.describe() + " does not exist or carries no reservation field");
    }

    private UUID pendingOf(final Expected entry) throws IOException {
        return switch (entry.domain()) {
            case ACCOUNT -> {
                final PlayerAccount account = accountOrNull(entry.resource());
                yield account == null ? null : account.pendingIntent();
            }
            case METADATA -> {
                final AuroraProfile profile = profileOrNull(entry.resource());
                yield profile == null ? null : profile.pendingIntent();
            }
            case SHARED -> {
                final ProfileSharedState state = sharedOrNull(entry.resource());
                yield state == null ? null : state.pendingIntent();
            }
            case HEAD -> {
                final MemberStateHead head = headOrNull(entry.resource(), entry.member(), entry.epoch());
                yield head == null ? null : head.pendingIntent();
            }
            case RECORD -> {
                final ProfileRecords.Binding2 binding = bindingOrNull(entry.resource());
                yield binding == null ? null : binding.pendingIntent();
            }
        };
    }

    /** Writes the record {@code entry} names with {@code pending} as its reservation, advancing its revision once. */
    private void setReservation(final Expected entry, final UUID pending) throws IOException {
        final UUID id = entry.resource();
        switch (entry.domain()) {
            case ACCOUNT -> {
                final PlayerAccount stored = accountOrNull(id);
                final PlayerAccount next = (stored == null ? PlayerAccount.empty(id, this.defaultSlots) : stored)
                    .withPendingIntent(pending);
                writeFormat2(accountFile(id), TYPE_ACCOUNT, next.revision(), ProfileRecords.accountPayload2(next));
            }
            case METADATA -> {
                final AuroraProfile stored = profileOrNull(id);
                if (stored == null || stored.legacy()) throw invalid(entry.describe() + " cannot carry a reservation");
                final AuroraProfile next = stored.withPendingIntent(pending);
                writeFormat2(profileFile(id), TYPE_PROFILE, next.revision(), ProfileRecords.profilePayload2(next));
            }
            case SHARED -> {
                final ProfileSharedState stored = sharedOrNull(id);
                if (stored == null) throw invalid(entry.describe() + " does not exist");
                final ProfileSharedState next = stored.withPendingIntent(pending);
                writeFormat2(sharedFile(id), ProfileRecords.TYPE_SHARED, next.revision(), ProfileRecords.sharedPayload2(next));
            }
            case HEAD -> {
                final MemberStateHead stored = headOrNull(id, entry.member(), entry.epoch());
                if (stored == null) throw invalid(entry.describe() + " does not exist");
                final MemberStateHead next = stored.withPendingIntent(pending);
                writeFormat2(headFile(id, entry.member(), entry.epoch()), ProfileRecords.TYPE_MEMBER_HEAD, next.revision(),
                    ProfileRecords.memberHeadPayload2(next));
            }
            case RECORD -> {
                final ProfileRecords.Binding2 stored = bindingOrNull(id);
                if (stored == null) throw invalid(entry.describe() + " is not a binding; only bindings carry a reservation");
                final long next = CoopWriteGuard.next(stored.revision(), "binding of " + id);
                writeFormat2(this.bindings.resolve(id + ".awfp"), TYPE_BINDING, next,
                    ProfileRecords.bindingPayload2(stored.binding(), next, pending));
            }
        }
    }

    /** Clears this operation's reservation when it is still installed at the post-reservation revision. */
    private boolean clearReservation(final Step step, final UUID operationId) throws IOException {
        if (!operationId.equals(pendingOf(step.after()))) return false;
        if (storedRevision(step.after(), operationId) != step.after().revision()) return false;
        setReservation(step.after(), null);
        return true;
    }

    private void writeFormat2(final Path file, final byte type, final long revision, final byte[] payload) throws IOException {
        FileProfileIo.ensureDirectory(file.getParent(), this.root);
        durable(file, ProfileRecords.frame2(type, revision, payload, file.toString()));
    }

    private void writeIntent(final ProfileIntent intent) throws IOException {
        final Path file = intentFile(intent.operationId());
        durable(file, ProfileRecords.frame2(ProfileRecords.TYPE_INTENT, intent.revision(), ProfileRecords.intentPayload2(intent),
            file.toString()));
    }

    /** Checks every after-image is staged intact, belongs to a record of the vector and carries its final revision. */
    private void verifyAfterImages(final ProfileIntent intent, final List<Step> steps) throws IOException {
        final Set<String> seen = new HashSet<>();
        for (final BlobRef ref : intent.afterImages()) {
            final Staged staged = staged(intent.operationId(), ref);
            if (ref.recordType() == ProfileRecords.TYPE_MEMBER_SNAPSHOT) continue;
            final Expected target = targetOf(ref);
            final Step step = stepFor(steps, target);
            if (step == null) {
                throw invalid("intent " + intent.operationId() + " carries an after-image of " + target.describe()
                    + ", which its version vector does not name");
            }
            if (!seen.add(target.describe())) throw invalid("intent " + intent.operationId() + " carries two after-images of " + target.describe());
            final long expected = step.after().revision() == CoopWriteGuard.ABSENT ? 0L
                : CoopWriteGuard.next(step.after().revision(), target.describe());
            if (ref.revision() != expected) {
                throw invalid("the after-image of " + target.describe() + " is revision " + ref.revision() + "; the commit writes "
                    + expected);
            }
            if (staged.pendingIntent() != null) throw invalid("the after-image of " + target.describe() + " carries a reservation");
            if (ProfileRecords.needsCoopStore(ref.recordType(), staged.record()) && !coopStoreLevel()) {
                throw refused("the after-image of " + target.describe() + " is a co-op record but the store marker " + this.formatFile
                    + " is " + describeLevel() + "; the store's feature level must be raised to schema " + StoreFormat.COOP_SCHEMA
                    + " and Build " + StoreFormat.COOP_MINIMUM_BUILD + " (requireStoreFeature) before the first co-op record."
                    + " Nothing was written");
            }
            if (staged.record() instanceof AuroraProfile profile) {
                if (profile.legacy()) throw invalid("the after-image of " + target.describe() + " has no member rows");
                // A created profile's name must be free in its owner's account; compared at prepare and again at commit.
                if (step.before().revision() == CoopWriteGuard.ABSENT) requireUniqueName(profile);
                else checkProfileImage(profileOrNull(profile.id()), profile, intent);
            } else {
                checkRecordCapacity(staged.record());
            }
        }
    }

    /**
     * Identity and kind checks of a profile after-image against the stored record (COOP-DESIGN §2: the repository
     * checks identity and status, not only the vector): owner, name, namespace, policy type and creation time are
     * immutable (owner transfer and renames are not operations of this port); the kind is one way; a membership
     * change requires the operation's profile claim {@code P} on this profile.
     */
    static void checkProfileImage(final AuroraProfile stored, final AuroraProfile after, final ProfileIntent intent) {
        if (stored == null) return;   // the vector reports the missing record as stale
        if (!stored.sameIdentity(after)) {
            throw invalid("the after-image of profile " + after.id() + " changes its owner, name, namespace, policy type or creation time;"
                + " none of these can change (owner transfer is refused). Nothing was written");
        }
        if (stored.kind() == ProfileKind.COOP && after.kind() != ProfileKind.COOP) {
            throw invalid("the after-image of profile " + after.id() + " turns a co-op profile back into a solo one; the conversion is"
                + " one way. Nothing was written");
        }
        if (!sameMemberships(stored.members(), after.members())
            && (intent.profileClaim() == null || !intent.profileClaim().id().equals(after.id()))) {
            throw invalid("the after-image of profile " + after.id() + " changes its members but operation " + intent.operationId()
                + " holds no profile claim on it. Nothing was written");
        }
    }

    /**
     * COOP-DESIGN §9 admission bounds of the membership records, checked at prepare and again at commit before the
     * marker: at most {@value ProfileInvite#MAX_OPEN} open invites per profile and per invitee, one open vote per
     * profile, {@value ProfileSalvage#MAX_OUTSTANDING_PER_PROFILE} outstanding salvage records per profile. A record
     * already in the index (a replayed install) never counts twice.
     */
    private void checkRecordCapacity(final Object record) throws IOException {
        if (record instanceof ProfileInvite invite && invite.open()) {
            requireRoom(ProfileIndexPage.Kind.INVITES_BY_PROFILE, invite.profileId(), invite.id(), ProfileInvite.MAX_OPEN,
                "open invites of profile " + invite.profileId());
            requireRoom(ProfileIndexPage.Kind.INVITES_BY_INVITEE, invite.invitee(), invite.id(), ProfileInvite.MAX_OPEN,
                "open invites to account " + invite.invitee());
        } else if (record instanceof KickVote vote && vote.status().occupiesSlot()) {
            requireRoom(ProfileIndexPage.Kind.VOTES_BY_PROFILE, vote.profileId(), vote.id(), KickVote.MAX_OPEN_PER_PROFILE,
                "open kick votes of profile " + vote.profileId());
        } else if (record instanceof ProfileSalvage salvaged && salvaged.outstanding()) {
            requireRoom(ProfileIndexPage.Kind.SALVAGE_BY_PROFILE, salvaged.profileId(), salvaged.id(),
                ProfileSalvage.MAX_OUTSTANDING_PER_PROFILE, "outstanding salvage of profile " + salvaged.profileId());
        }
    }

    private void requireRoom(final ProfileIndexPage.Kind kind, final UUID resource, final UUID id, final int cap, final String what)
        throws IOException {
        final List<ProfileIndexPage.Entry> entries = this.indexes.entries(kind, resource, null, null);
        if (entries.size() < cap) return;
        for (final ProfileIndexPage.Entry entry : entries) {
            if (entry.id().equals(id)) return;
        }
        throw busy("the bound of " + cap + " " + what + " is reached");
    }

    private static Step stepFor(final List<Step> steps, final Expected target) {
        for (final Step step : steps) {
            if (step.after().sameRecord(target)) return step;
        }
        return null;
    }

    private static Expected targetOf(final BlobRef ref) {
        return switch (ref.recordType()) {
            case ProfileRecords.TYPE_ACCOUNT -> Expected.account(ref.id(), 0L);
            case ProfileRecords.TYPE_PROFILE -> Expected.metadata(ref.id(), 0L);
            case ProfileRecords.TYPE_SHARED -> Expected.shared(ref.id(), 0L);
            case ProfileRecords.TYPE_MEMBER_HEAD -> Expected.head(ref.id(), ref.member(), ref.epoch(), 0L);
            case ProfileRecords.TYPE_BINDING, ProfileRecords.TYPE_INVITE, ProfileRecords.TYPE_VOTE, ProfileRecords.TYPE_SALVAGE ->
                Expected.record(ref.id(), 0L);
            default -> throw invalid("a " + ProfileRecords.typeName(ref.recordType()) + " record is not an intent after-image");
        };
    }

    /** Reads and verifies one staged blob: frame, revision, length, digest and the type's own decoding. */
    private Staged staged(final UUID operationId, final BlobRef ref) throws IOException {
        final Path file = blobFile(operationId, ref);
        if (!Files.isRegularFile(file)) throw new IOException("the after-image " + file.getFileName() + " of " + operationId + " is not staged");
        final byte[] bytes = FileProfileIo.readBounded(file, ProfileRecords.maxPayload(ref.recordType()) + ProfileRecords.OVERHEAD);
        final ProfileRecords.Frame frame = ProfileRecords.unframe(bytes, ProfileRecords.FORMAT_2, ref.recordType(), file.toString());
        if (frame.revision() != ref.revision() || frame.payload().length != ref.length()
            || !MessageDigest.isEqual(ProfileRecords.sha256(frame.payload()), ref.sha256())) {
            throw new IOException("the staged after-image " + file + " does not match its reference");
        }
        return decodeStaged(ref, frame, file.toString()).withBytes(bytes);
    }

    private Staged decodeStaged(final BlobRef ref, final ProfileRecords.Frame frame, final String where) throws IOException {
        final UUID id = ref.id();
        return switch (ref.recordType()) {
            case ProfileRecords.TYPE_ACCOUNT -> {
                final PlayerAccount value = ProfileRecords.account2(frame, id, where);
                yield new Staged(null, value.pendingIntent(), accountFile(id), value);
            }
            case ProfileRecords.TYPE_PROFILE -> {
                final AuroraProfile value = ProfileRecords.profile2(frame, id, where);
                yield new Staged(null, value.pendingIntent(), profileFile(id), value);
            }
            case ProfileRecords.TYPE_BINDING -> {
                final ProfileRecords.Binding2 value = ProfileRecords.binding2(frame, id, where);
                yield new Staged(null, value.pendingIntent(), this.bindings.resolve(id + ".awfp"), value);
            }
            case ProfileRecords.TYPE_SHARED -> {
                final ProfileSharedState value = ProfileRecords.shared2(frame, id, where);
                yield new Staged(null, value.pendingIntent(), sharedFile(id), value);
            }
            case ProfileRecords.TYPE_INVITE -> new Staged(null, null, this.invites.resolve(id + ".awfp"),
                ProfileRecords.invite2(frame, id, where));
            case ProfileRecords.TYPE_VOTE -> new Staged(null, null, this.votes.resolve(id + ".awfp"), ProfileRecords.vote2(frame, id, where));
            case ProfileRecords.TYPE_SALVAGE -> new Staged(null, null, this.salvage.resolve(id + ".awfp"),
                ProfileRecords.salvage2(frame, id, where));
            case ProfileRecords.TYPE_MEMBER_HEAD -> {
                requireStream(ref);
                final MemberStateHead value = ProfileRecords.memberHead2(frame, id, ref.member(), ref.epoch(), where);
                yield new Staged(null, value.pendingIntent(), headFile(id, ref.member(), ref.epoch()), value);
            }
            case ProfileRecords.TYPE_MEMBER_SNAPSHOT -> {
                requireStream(ref);
                final ProfileSnapshot value = ProfileRecords.memberSnapshot2(frame, id, ref.member(), ref.epoch(), where);
                yield new Staged(null, null, streamDirectory(id, ref.member(), ref.epoch())
                    .resolve(value.revision() + "-" + value.snapshotId() + ".awfp"), value);
            }
            default -> throw invalid("a " + ProfileRecords.typeName(ref.recordType()) + " record is not an intent after-image");
        };
    }

    private static void requireStream(final BlobRef ref) {
        if (ref.member() == null || ref.epoch() == null) throw invalid("a stream after-image names member and epoch");
    }

    /**
     * Installs the after-images of a COMMITTED intent; idempotent. Returns {@code null}, or why a record
     * is neither at its post-reservation revision nor already holding its after-image.
     */
    private String installAfterImages(final ProfileIntent intent, final List<Step> steps) throws IOException {
        final UUID id = intent.operationId();
        final List<BlobRef> ordered = new ArrayList<>(intent.afterImages());
        ordered.sort(Comparator.comparingInt(ref -> ref.recordType() == ProfileRecords.TYPE_MEMBER_SNAPSHOT ? 0 : 1));
        for (final BlobRef ref : ordered) {
            final Staged staged;
            try {
                staged = staged(id, ref);
            } catch (final IOException missing) {
                return "its after-image of " + ProfileRecords.typeName(ref.recordType()) + " " + ref.id() + " cannot be read ("
                    + missing.getMessage() + ")";
            }
            final Path target = staged.target();
            ProfileRecords.Frame current = null;
            if (Files.isRegularFile(target)) {
                current = ProfileRecords.unframe(FileProfileIo.readBounded(target,
                    ProfileRecords.maxPayload(ref.recordType()) + ProfileRecords.OVERHEAD), ref.recordType(), target.toString());
                if (current.format() == ProfileRecords.FORMAT_2 && current.revision() == ref.revision()
                    && MessageDigest.isEqual(ProfileRecords.sha256(current.payload()), ref.sha256())) {
                    indexAfterImage(staged.record());
                    continue;
                }
            }
            if (ref.recordType() == ProfileRecords.TYPE_MEMBER_SNAPSHOT) {
                if (current != null) return "member snapshot " + target + " exists with other content";
                FileProfileIo.ensureDirectory(target.getParent(), this.root);
                durable(target, staged.bytes());
                continue;
            }
            final Step step = stepFor(steps, targetOf(ref));
            final long stored = current == null
                ? (ref.recordType() == ProfileRecords.TYPE_ACCOUNT ? 0L : CoopWriteGuard.ABSENT) : current.revision();
            final UUID pending = current == null ? null : pendingOf(step.after());
            if (stored != step.after().revision() || (step.reserve() && !id.equals(pending))) {
                return step.after().describe() + " is at revision " + stored + (pending == null ? "" : " reserved by " + pending)
                    + " instead of revision " + step.after().revision() + " reserved by " + id;
            }
            FileProfileIo.ensureDirectory(target.getParent(), this.root);
            durable(target, staged.bytes());
            indexAfterImage(staged.record());
        }
        return null;
    }

    /**
     * Derived index entries of installed membership records; idempotent, and the same as the Redis commit script
     * keeps: the invite indexes hold <em>open</em> invites, the vote indexes undecided (OPEN or PASSED) votes, the
     * salvage index every record that is not DELIVERED and the available index the AVAILABLE ones. A record that
     * leaves a state leaves the index in the same install.
     */
    private void indexAfterImage(final Object record) throws IOException {
        if (record instanceof ProfileInvite invite) {
            final ProfileIndexPage.Entry entry = new ProfileIndexPage.Entry(invite.createdAtMillis(), invite.id());
            if (invite.open()) {
                this.indexes.add(ProfileIndexPage.Kind.INVITES_BY_PROFILE, invite.profileId(), null, null, entry);
                this.indexes.add(ProfileIndexPage.Kind.INVITES_BY_INVITEE, invite.invitee(), null, null, entry);
            } else {
                this.indexes.remove(ProfileIndexPage.Kind.INVITES_BY_PROFILE, invite.profileId(), null, null, invite.id());
                this.indexes.remove(ProfileIndexPage.Kind.INVITES_BY_INVITEE, invite.invitee(), null, null, invite.id());
            }
        } else if (record instanceof KickVote vote) {
            final ProfileIndexPage.Entry entry = new ProfileIndexPage.Entry(vote.createdAtMillis(), vote.id());
            if (vote.status().occupiesSlot()) {
                this.indexes.add(ProfileIndexPage.Kind.VOTES_BY_PROFILE, vote.profileId(), null, null, entry);
                this.indexes.add(ProfileIndexPage.Kind.VOTES_BY_TARGET, vote.target(), null, null, entry);
            } else {
                this.indexes.remove(ProfileIndexPage.Kind.VOTES_BY_PROFILE, vote.profileId(), null, null, vote.id());
                this.indexes.remove(ProfileIndexPage.Kind.VOTES_BY_TARGET, vote.target(), null, null, vote.id());
            }
        } else if (record instanceof ProfileSalvage salvaged) {
            final ProfileIndexPage.Entry entry = new ProfileIndexPage.Entry(salvaged.createdAtMillis(), salvaged.id());
            if (salvaged.outstanding()) {
                this.indexes.add(ProfileIndexPage.Kind.SALVAGE_BY_PROFILE, salvaged.profileId(), null, null, entry);
            } else {
                this.indexes.remove(ProfileIndexPage.Kind.SALVAGE_BY_PROFILE, salvaged.profileId(), null, null, salvaged.id());
            }
            if (salvaged.state() == ProfileSalvage.State.AVAILABLE) {
                this.indexes.add(ProfileIndexPage.Kind.SALVAGE_AVAILABLE_BY_PROFILE, salvaged.profileId(), null, null, entry);
            } else {
                this.indexes.remove(ProfileIndexPage.Kind.SALVAGE_AVAILABLE_BY_PROFILE, salvaged.profileId(), null, null, salvaged.id());
            }
        }
    }

    /** Rolls a COMMITTED intent forward; never throws for a post-commit failure (it is UNKNOWN). */
    private CommitResult finish(final ProfileIntent intent, final boolean resumed) {
        final UUID id = intent.operationId();
        try {
            final List<Step> steps = plan(intent);
            final String conflict = installAfterImages(intent, steps);
            if (conflict != null) {
                if (intent.phase() != ProfileIntent.Phase.RECOVERY_REQUIRED) {
                    writeIntent(intent.at(ProfileIntent.Phase.RECOVERY_REQUIRED, "committed; " + conflict));
                }
                SourbyLogger.error("AWF MultiProfile: operation " + id + " is committed but " + conflict
                    + "; its intent is RECOVERY_REQUIRED for an operator");
                return CommitResult.unknown(id, "operation " + id + " is committed but " + conflict + "; RECOVERY_REQUIRED");
            }
            for (final Step step : steps) {
                if (!step.reserve()) continue;
                boolean imaged = false;
                for (final BlobRef ref : intent.afterImages()) {
                    if (ref.recordType() != ProfileRecords.TYPE_MEMBER_SNAPSHOT && targetOf(ref).sameRecord(step.after())) imaged = true;
                }
                if (!imaged) clearReservation(step, id);
            }
            final ProfileReceipt receipt = writeReceipts(intent, ProfileReceipt.Outcome.COMMITTED);
            final ProfileIntent finalized = intent.at(ProfileIntent.Phase.FINALIZED, "finalized");
            writeIntent(finalized);
            cleanupAfterTerminal(finalized);
            return resumed ? CommitResult.replayed(id, receipt, "operation " + id + " was committed earlier and is now finalized")
                : CommitResult.committed(id, receipt, "operation " + id + " committed and finalized");
        } catch (final IOException | RuntimeException failed) {
            return CommitResult.unknown(id, "operation " + id + " is committed; finishing it failed (" + failed.getMessage()
                + "); resolveIntent finishes it, never compensate");
        }
    }

    /** Completes an interrupted prepare where nothing moved; {@code false} when a reserved record changed meanwhile. */
    private boolean completePrepare(final ProfileIntent intent) throws IOException {
        final UUID id = intent.operationId();
        final ProfileIndexPage.Entry entry = new ProfileIndexPage.Entry(intent.createdAtMillis(), id);
        this.indexes.add(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null, entry);
        for (final UUID profile : profilesOf(intent)) this.indexes.add(ProfileIndexPage.Kind.INTENTS_BY_PROFILE, profile, null, null, entry);
        for (final Stream stream : streamsOf(intent)) recomputePins(stream, null);
        for (final UUID player : playersOf(intent)) this.indexes.add(ProfileIndexPage.Kind.INTENTS_BY_PLAYER, player, null, null, entry);
        boolean complete = true;
        for (final Step step : plan(intent)) {
            if (!step.reserve()) continue;
            final UUID pending = pendingOf(step.before());
            final long stored = storedRevision(step.before(), id);
            if (id.equals(pending) && stored == step.after().revision()) continue;
            if (pending == null && stored == step.before().revision()) {
                setReservation(step.before(), id);
            } else {
                complete = false;
            }
        }
        return complete;
    }

    /** Removes a terminal intent's index entries and staging; failures are logged (the next open retries). */
    private void cleanupAfterTerminal(final ProfileIntent intent) {
        final UUID id = intent.operationId();
        try {
            for (final UUID player : playersOf(intent)) this.indexes.remove(ProfileIndexPage.Kind.INTENTS_BY_PLAYER, player, null, null, id);
            for (final UUID profile : profilesOf(intent)) this.indexes.remove(ProfileIndexPage.Kind.INTENTS_BY_PROFILE, profile, null, null, id);
            for (final Stream stream : streamsOf(intent)) recomputePins(stream, null);
            this.indexes.remove(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null, id);
            deleteRecursively(stagingDirectory(id));
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: cleaning up the indexes of operation " + id + " failed (" + failed.getMessage()
                + "); its pins stay counted until the next open");
        }
    }

    /**
     * Rewrites the pin page of one stream from the pending intents of its profile (plus {@code extra},
     * an intent being prepared). Prepares not yet written are found through their staged copy.
     */
    private void recomputePins(final Stream stream, final ProfileIntent extra) throws IOException {
        final TreeMap<UUID, Long> counts = new TreeMap<>();
        boolean extraSeen = false;
        for (final ProfileIndexPage.Entry entry : this.indexes.entries(ProfileIndexPage.Kind.INTENTS_BY_PROFILE, stream.profile(), null, null)) {
            final ProfileIntent pending;
            if (extra != null && entry.id().equals(extra.operationId())) {
                pending = extra;
                extraSeen = true;
            } else {
                final ProfileIntent written = intentOrNull(entry.id());
                pending = written != null ? written : stagedIntentOrNull(entry.id());
            }
            if (pending != null && pending.pending()) countPins(pending, stream, counts);
        }
        if (extra != null && !extraSeen && extra.pending()) countPins(extra, stream, counts);
        final List<ProfileIndexPage.Entry> entries = new ArrayList<>(counts.size());
        counts.forEach((snapshot, count) -> entries.add(new ProfileIndexPage.Entry(0L, snapshot, count)));
        this.indexes.replace(ProfileIndexPage.Kind.PINS_BY_STREAM, stream.profile(), stream.member(), stream.epoch(), entries);
    }

    private static void countPins(final ProfileIntent intent, final Stream stream, final Map<UUID, Long> counts) {
        for (final SnapshotRef pin : intent.pins()) {
            if (pin.profileId().equals(stream.profile()) && stream.member().equals(pin.member()) && stream.epoch().equals(pin.epoch())) {
                counts.merge(pin.snapshotId(), 1L, Long::sum);
            }
        }
    }

    private static Set<UUID> playersOf(final ProfileIntent intent) {
        final Set<UUID> players = new LinkedHashSet<>();
        players.add(intent.actor());
        for (final Expected entry : intent.expectedAfter()) {
            if (entry.domain() == Domain.ACCOUNT) players.add(entry.resource());
        }
        for (final ProfileIntent.ClaimRef claim : intent.playerClaims()) players.add(claim.id());
        return players;
    }

    private static Set<UUID> profilesOf(final ProfileIntent intent) {
        final Set<UUID> found = new LinkedHashSet<>();
        for (final Expected entry : intent.expectedAfter()) {
            if (entry.domain() == Domain.METADATA || entry.domain() == Domain.SHARED || entry.domain() == Domain.HEAD) {
                found.add(entry.resource());
            }
        }
        if (intent.profileClaim() != null) found.add(intent.profileClaim().id());
        for (final SnapshotRef pin : intent.pins()) found.add(pin.profileId());
        return found;
    }

    private static Set<Stream> streamsOf(final ProfileIntent intent) {
        final Set<Stream> streams = new LinkedHashSet<>();
        for (final SnapshotRef pin : intent.pins()) {
            if (pin.member() != null) streams.add(new Stream(pin.profileId(), pin.member(), pin.epoch()));
        }
        return streams;
    }

    private void checkCapacity(final ProfileIntent intent) throws IOException {
        if (this.indexes.entries(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null).size() >= MAX_PENDING_GLOBAL) {
            throw busy(MAX_PENDING_GLOBAL + " operations are pending in this store");
        }
        for (final UUID player : playersOf(intent)) {
            if (this.indexes.entries(ProfileIndexPage.Kind.INTENTS_BY_PLAYER, player, null, null).size() >= MAX_PENDING_PER_RESOURCE) {
                throw busy(MAX_PENDING_PER_RESOURCE + " operations are pending for account " + player);
            }
        }
        for (final UUID profile : profilesOf(intent)) {
            if (this.indexes.entries(ProfileIndexPage.Kind.INTENTS_BY_PROFILE, profile, null, null).size() >= MAX_PENDING_PER_RESOURCE) {
                throw busy(MAX_PENDING_PER_RESOURCE + " operations are pending for profile " + profile);
            }
        }
    }

    /**
     * Open-time recovery of a ready store: removes the entries of prepares that never wrote their intent
     * and of terminal intents, and rolls COMMITTED intents forward. Failures are logged; PREPARED intents
     * are left for the engine.
     */
    /** Removes the staged after-images of an operation that was refused before anything durable named it; failures are logged. */
    private void discardStaging(final UUID operationId) {
        try {
            deleteRecursively(stagingDirectory(operationId));
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: the staged after-images of the refused operation " + operationId + " could not be"
                + " removed (" + failed.getMessage() + "); they are harmless and may be deleted by hand");
        }
    }

    private void recoverAtOpen() {
        final List<ProfileIndexPage.Entry> pending;
        try {
            pending = this.indexes.entries(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null);
        } catch (final IOException corrupt) {
            SourbyLogger.error("AWF MultiProfile: the pending-intent index cannot be read (" + corrupt.getMessage()
                + "); no intent was recovered and co-op operations that need it stay refused");
            return;
        }
        if (pending.isEmpty()) return;
        try {
            requireTransactional();
        } catch (final RuntimeException disabled) {
            SourbyLogger.error("AWF MultiProfile: " + pending.size() + " pending intent(s) are not recovered: " + disabled.getMessage());
            return;
        }
        int interrupted = 0;
        int finished = 0;
        int undecided = 0;
        int failed = 0;
        for (final ProfileIndexPage.Entry entry : pending) {
            final UUID id = entry.id();
            try {
                final ProfileIntent intent = intentOrNull(id);
                if (intent == null) {
                    final ProfileIntent staged = stagedIntentOrNull(id);
                    if (staged != null) {
                        for (final UUID profile : profilesOf(staged)) {
                            this.indexes.remove(ProfileIndexPage.Kind.INTENTS_BY_PROFILE, profile, null, null, id);
                        }
                        for (final Stream stream : streamsOf(staged)) recomputePins(stream, null);
                    }
                    this.indexes.remove(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null, id);
                    deleteRecursively(stagingDirectory(id));
                    interrupted++;
                } else if (!intent.pending()) {
                    cleanupAfterTerminal(intent);
                } else if (intent.committed() || committedRecovery(intent)) {
                    if (finish(intent, true).unknown()) failed++; else finished++;
                } else {
                    undecided++;
                }
            } catch (final IOException | RuntimeException problem) {
                failed++;
                SourbyLogger.error("AWF MultiProfile: recovering operation " + id + " at open failed: " + problem.getMessage());
            }
        }
        SourbyLogger.info("AWF MultiProfile: open-time intent recovery of " + this.root + ": " + interrupted
            + " interrupted prepare(s) cleaned, " + finished + " committed operation(s) finalized, " + undecided
            + " PREPARED operation(s) left for the engine, " + failed + " unresolved");
    }

    /** Staging directories older than this with no durable or staged intent are removed at open. */
    private static final long STALE_STAGING_MILLIS = TimeUnit.HOURS.toMillis(1);

    /**
     * Removes {@code staging/<operation>/} directories of operations that never reached {@link #prepareIntent} (no
     * intent, no staged intent, no pending-index entry) and are older than {@value #STALE_STAGING_MILLIS} ms: their
     * blobs can never be installed. Age-bounded so a prepare in flight on this process is never swept.
     */
    private void sweepStaleStaging() {
        if (!Files.isDirectory(this.staging)) return;
        int swept = 0;
        try (java.nio.file.DirectoryStream<Path> found = Files.newDirectoryStream(this.staging)) {
            for (final Path directory : found) {
                if (!Files.isDirectory(directory)) continue;
                final UUID id = uuidOrNull(directory.getFileName().toString());
                try {
                    if (id == null) continue;
                    if (System.currentTimeMillis() - Files.getLastModifiedTime(directory).toMillis() < STALE_STAGING_MILLIS) continue;
                    if (intentOrNull(id) != null || stagedIntentOrNull(id) != null) continue;
                    deleteRecursively(directory);
                    swept++;
                } catch (final IOException | RuntimeException problem) {
                    SourbyLogger.warn("AWF MultiProfile: the staging directory " + directory + " could not be checked or removed ("
                        + problem.getMessage() + ")");
                }
            }
        } catch (final IOException unreadable) {
            SourbyLogger.warn("AWF MultiProfile: the staging directory " + this.staging + " could not be listed (" + unreadable.getMessage() + ")");
        }
        if (swept > 0) SourbyLogger.info("AWF MultiProfile: removed " + swept + " stale staging directories of operations that were never prepared");
    }

    // ------------------------------------------------------------------ format-2 member stream pruning

    private void pruneStream(final UUID profileId, final UUID memberId, final UUID epoch, final int retain,
                             final MemberStateHead head) {
        final Set<UUID> keep = new HashSet<>();
        try {
            for (final ProfileIndexPage.Entry entry : this.indexes.entries(ProfileIndexPage.Kind.PINS_BY_STREAM, profileId, memberId, epoch)) {
                keep.add(entry.id());
            }
            for (final ProfileTransition journal : transitionsFor(memberId)) {
                if (journal.terminal()) continue;
                if (journal.fromSnapshotId() != null) keep.add(journal.fromSnapshotId());
                if (journal.toSnapshotId() != null) keep.add(journal.toSnapshotId());
            }
        } catch (final IOException | RuntimeException unknown) {
            SourbyLogger.warn("AWF MultiProfile: the pins of stream " + head.describe() + " are unknown (" + unknown.getMessage()
                + "); nothing was pruned");
            return;
        }
        if (head.snapshotId() != null) keep.add(head.snapshotId());
        final Path directory = streamDirectory(profileId, memberId, epoch);
        try {
            final Map<Path, FileTime> modified = new HashMap<>();
            try (DirectoryStream<Path> found = Files.newDirectoryStream(directory, "*.awfp")) {
                for (final Path file : found) {
                    if (modified.size() >= MAX_STREAM_FILES) throw new IOException("more than " + MAX_STREAM_FILES + " snapshot files");
                    modified.put(file, modifiedAt(file));
                }
            }
            final List<Path> files = new ArrayList<>(modified.keySet());
            files.sort(Comparator.comparingLong((Path path) -> revisionOf(path.getFileName().toString()))
                .thenComparing(modified::get).thenComparing(path -> path.getFileName().toString()));
            final int older = files.size() - Math.max(1, retain);
            for (int i = 0; i < older; i++) {
                final UUID id = snapshotIdOf(files.get(i));
                if (id != null && !keep.contains(id)) Files.deleteIfExists(files.get(i));
            }
        } catch (final IOException failed) {
            SourbyLogger.warn("AWF MultiProfile: pruning stream " + head.describe() + " failed: " + failed.getMessage());
        }
    }

    // ------------------------------------------------------------------ record readers (format aware)

    private PlayerAccount accountOrNull(final UUID playerId) throws IOException {
        final Path file = accountFile(playerId);
        if (!Files.isRegularFile(file)) return null;
        return decodeAccount(read(file, TYPE_ACCOUNT), playerId, file);
    }

    private AuroraProfile profileOrNull(final UUID profileId) throws IOException {
        final Access access = access();
        final Path file = profileFile(profileId);
        if (!Files.isRegularFile(file)) return null;
        final ProfileRecords.Frame frame = read(file, TYPE_PROFILE, access);
        return frame.format() == ProfileRecords.FORMAT_2 ? ProfileRecords.profile2(frame, profileId, file.toString())
            : ProfileRecords.profile(frame, profileId, file.toString());
    }

    private ProfileRecords.Binding2 bindingOrNull(final UUID profileId) throws IOException {
        final Access access = access();
        final Path file = this.bindings.resolve(profileId + ".awfp");
        if (!Files.isRegularFile(file)) return null;
        final ProfileRecords.Frame frame = read(file, TYPE_BINDING, access);
        if (frame.format() == ProfileRecords.FORMAT_2) return ProfileRecords.binding2(frame, profileId, file.toString());
        return new ProfileRecords.Binding2(ProfileRecords.binding(frame, file.toString()), frame.revision(), null);
    }

    private ProfileSharedState sharedOrNull(final UUID profileId) throws IOException {
        final Access access = access();
        final Path file = sharedFile(profileId);
        if (!Files.isRegularFile(file)) return null;
        return ProfileRecords.shared2(read(file, ProfileRecords.TYPE_SHARED, access), profileId, file.toString());
    }

    private MemberStateHead headOrNull(final UUID profileId, final UUID memberId, final UUID epoch) throws IOException {
        final Access access = access();
        final Path file = headFile(profileId, memberId, epoch);
        if (!Files.isRegularFile(file)) return null;
        return ProfileRecords.memberHead2(read(file, ProfileRecords.TYPE_MEMBER_HEAD, access), profileId, memberId, epoch,
            file.toString());
    }

    private ProfileIntent intentOrNull(final UUID operationId) throws IOException {
        final Access access = access();
        final Path file = intentFile(operationId);
        if (!Files.isRegularFile(file)) return null;
        return ProfileRecords.intent2(read(file, ProfileRecords.TYPE_INTENT, access), operationId, file.toString());
    }

    private ProfileIntent stagedIntentOrNull(final UUID operationId) throws IOException {
        final Path file = stagedIntentFile(operationId);
        if (!Files.isRegularFile(file)) return null;
        return ProfileRecords.intent2(read(file, ProfileRecords.TYPE_INTENT, Access.READY), operationId, file.toString());
    }

    private ProfileReceipt receiptOrNull(final UUID resource, final UUID operationId) throws IOException {
        final Access access = access();
        final Path file = receiptFile(resource, operationId);
        if (!Files.isRegularFile(file)) return null;
        final ProfileReceipt.Page page = ProfileRecords.receiptPage2(read(file, ProfileRecords.TYPE_PAGE, access), resource,
            file.toString());
        final ProfileReceipt receipt = page.find(operationId);
        if (receipt == null) throw new IOException("receipt file " + file + " does not hold operation " + operationId);
        return receipt;
    }

    private PlayerAccount decodeAccount(final ProfileRecords.Frame frame, final UUID playerId, final Path file) throws IOException {
        return frame.format() == ProfileRecords.FORMAT_2 ? ProfileRecords.account2(frame, playerId, file.toString())
            : ProfileRecords.account(frame, playerId, file.toString());
    }

    private static ProfileSnapshot decodeArchive(final ProfileRecords.Frame frame, final UUID playerId, final Path file)
        throws IOException {
        return frame.format() == ProfileRecords.FORMAT_2 ? ProfileRecords.legacyArchive2(frame, playerId, file.toString())
            : ProfileRecords.snapshot(frame, playerId, file.toString());
    }

    /** The format byte of a record file (0 when it is not a profile record). */
    private static int formatOf(final Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            final byte[] header = input.readNBytes(5);
            if (header.length < 5 || ByteBuffer.wrap(header).getInt() != ProfileRecords.MAGIC) return 0;
            return header[4] & 0xFF;
        }
    }

    private List<Path> archiveFiles() throws IOException {
        final List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> found = Files.newDirectoryStream(this.accounts, "*.legacy*.awfp")) {
            for (final Path file : found) files.add(file);
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    // ------------------------------------------------------------------ paths

    private Path profileFile(final UUID profileId) {
        return this.profiles.resolve(profileId + ".awfp");
    }

    private Path sharedFile(final UUID profileId) {
        return this.shared.resolve(profileId + ".awfp");
    }

    private Path headFile(final UUID profileId, final UUID memberId, final UUID epoch) {
        return this.members.resolve(profileId.toString()).resolve(memberId.toString()).resolve(epoch.toString()).resolve("head.awfp");
    }

    private Path streamDirectory(final UUID profileId, final UUID memberId, final UUID epoch) {
        return this.snapshots.resolve(profileId.toString()).resolve(memberId.toString()).resolve(epoch.toString());
    }

    private Path intentFile(final UUID operationId) {
        return this.intents.resolve(operationId + ".awfp");
    }

    private Path receiptFile(final UUID resource, final UUID operationId) {
        return this.receipts.resolve(resource.toString()).resolve(operationId + ".awfp");
    }

    private Path stagingDirectory(final UUID operationId) {
        return this.staging.resolve(operationId.toString());
    }

    private Path stagedIntentFile(final UUID operationId) {
        return stagingDirectory(operationId).resolve("intent.awfp");
    }

    private Path blobFile(final UUID operationId, final BlobRef ref) {
        final StringBuilder name = new StringBuilder().append(ref.recordType() & 0xFF).append('-').append(ref.id());
        if (ref.member() != null) name.append('-').append(ref.member()).append('-').append(ref.epoch());
        name.append('-').append(ref.revision()).append('-').append(FileProfileIo.hex(ref.sha256())).append(".blob");
        return stagingDirectory(operationId).resolve(name.toString());
    }

    // ------------------------------------------------------------------ writes

    /** {@link FileProfileIo#writeDurable}, recording a filesystem without atomic rename. */
    private void durable(final Path file, final byte[] bytes) throws IOException {
        try {
            FileProfileIo.writeDurable(file, bytes);
        } catch (final FileProfileIo.DurabilityUnsupported unsupported) {
            this.transactional = Boolean.FALSE;
            this.transactionalProblem = unsupported.getMessage();
            throw unsupported;
        }
    }

    /** A durable write of an immutable blob: an unknown outcome is just a failure (nothing names the blob yet). */
    private void blob(final Path file, final byte[] bytes) throws IOException {
        try {
            durable(file, bytes);
        } catch (final FileProfileIo.OutcomeUnknown unknown) {
            throw new IOException("staging " + file + " failed: " + unknown.getMessage(), unknown);
        }
    }

    /**
     * The store clock intents' deadlines are compared against: the wall clock, never running backwards, also across
     * restarts. The highest value returned is persisted as a high-water mark in {@value #CLOCK_FILE} ahead of the
     * clock by {@link #CLOCK_LEASE_MILLIS} (rewritten when the clock reaches half the lease; the exact value at a clean
     * close), and the clock resumes from that mark at open: a clock set back between two runs, or during one, does not
     * revive an expired invitation or vote (COOP-DESIGN §9). After a crash the clock may therefore run up to the lease
     * ahead of the wall clock until the wall clock catches up. A mark that cannot be written keeps the clock monotonic
     * within the process and is reported once; it never fails the caller.
     */
    @Override
    public synchronized long storeClockMillis() {
        final long now = System.currentTimeMillis();
        if (now > this.lastClockMillis) this.lastClockMillis = now;
        if (!this.closed && this.lastClockMillis + CLOCK_LEASE_MILLIS / 2L >= this.persistedClockMillis) {
            try {
                persistClock(this.lastClockMillis + CLOCK_LEASE_MILLIS);
            } catch (final IOException | RuntimeException failed) {
                if (!this.clockWarned) {
                    this.clockWarned = true;
                    SourbyLogger.warn("AWF MultiProfile: the store clock mark " + this.root.resolve(CLOCK_FILE) + " cannot be written ("
                        + failed.getMessage() + "); the clock stays monotonic within this process only until the mark is writable again");
                }
            }
        }
        return this.lastClockMillis;
    }

    /** A mark further ahead of the wall clock than the lease plus this is not trusted (a clock that jumped and came back). */
    static final long CLOCK_TOLERANCE_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /**
     * Reads the persisted clock mark at open; the clock never returns less than a valid mark. The open never fails on
     * it: a missing, truncated or malformed file (WARN) starts from the wall clock and is rewritten by the first read;
     * a mark beyond {@code now + lease + tolerance} (WARN) is capped to {@code now + lease}, so a mark written while
     * the wall clock was far ahead cannot freeze every expiry for that long.
     */
    private void readClock() {
        final Path file = this.root.resolve(CLOCK_FILE);
        if (!Files.isRegularFile(file)) return;
        final long now = System.currentTimeMillis();
        long mark;
        try {
            final byte[] bytes = FileProfileIo.readBounded(file, 12);
            if (bytes.length != 12 || ByteBuffer.wrap(bytes).getInt() != CLOCK_MAGIC) {
                throw new IOException(bytes.length + " bytes" + (bytes.length >= 4 ? ", magic " + Integer.toHexString(ByteBuffer.wrap(bytes).getInt()) : ""));
            }
            mark = ByteBuffer.wrap(bytes, 4, 8).getLong();
            if (mark < 0L) throw new IOException("negative time " + mark);
        } catch (final IOException | RuntimeException malformed) {
            SourbyLogger.warn("AWF MultiProfile: the store clock mark " + file + " is unreadable (" + malformed.getMessage() + "); the store"
                + " clock resumes from the wall clock and the mark is rewritten. An invitation that expired while the wall clock was"
                + " set back may be judged open until the wall clock passes its expiry");
            this.persistedClockMillis = 0L;
            return;
        }
        if (mark > now + CLOCK_LEASE_MILLIS + CLOCK_TOLERANCE_MILLIS) {
            SourbyLogger.warn("AWF MultiProfile: the store clock mark " + file + " (" + mark + ") is " + (mark - now) + " ms ahead of the"
                + " wall clock (" + now + "), beyond the lease and tolerance; it is capped to the wall clock plus the lease");
            mark = now + CLOCK_LEASE_MILLIS;
        }
        this.persistedClockMillis = mark;
        if (mark > this.lastClockMillis) this.lastClockMillis = mark;
    }

    private void persistClock(final long mark) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(12);
        buffer.putInt(CLOCK_MAGIC);
        buffer.putLong(mark);
        durable(this.root.resolve(CLOCK_FILE), buffer.array());
        this.persistedClockMillis = mark;
        this.clockWarned = false;
    }

    private static ProfileOperationException refused(final String message) {
        return new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, message);
    }

    private static ProfileOperationException invalid(final String message) {
        return new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, message);
    }

    private static ProfileOperationException stale(final String message) {
        return new ProfileOperationException(ProfileErrorCode.STALE_STATE, message);
    }

    /** A COOP-DESIGN §9 bound is reached; the retryable refusal. */
    private static ProfileOperationException busy(final String message) {
        return new ProfileOperationException(ProfileErrorCode.CAPACITY_EXCEEDED, message + "; retry later. Nothing was written");
    }

    // ------------------------------------------------------------------ journal index

    /** Indexes every journal once; files of earlier builds are read to learn their player. */
    private void indexJournals() throws IOException {
        int setAside = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(this.transitions)) {
            for (final Path file : files) {
                final String name = file.getFileName().toString();
                if (name.endsWith(CORRUPT)) {
                    rememberQuarantined(file);
                    setAside++;
                    continue;
                }
                if (!name.endsWith(".awfp")) continue;   // the temporary file of an interrupted write
                final String[] parts = name.split("\\.");
                if (parts.length == 3) {
                    final UUID player = uuidOrNull(parts[0]);
                    final UUID transition = uuidOrNull(parts[1]);
                    if (player != null && transition != null) {
                        index(transition, player, file);
                        continue;
                    }
                }
                try {
                    final ProfileTransition journal = readTransition(file);
                    index(journal.transitionId(), journal.playerId(), file);
                } catch (final IOException unreadable) {
                    quarantine(file, null, null, unreadable);
                }
            }
        }
        if (setAside > 0) {
            SourbyLogger.warn("AWF MultiProfile: " + setAside + " unreadable transition journal(s) are set aside in "
                + this.transitions + " (*.corrupt); /awf multiprofile status lists them");
        }
    }

    private void index(final UUID transitionId, final UUID playerId, final Path file) {
        this.journalFiles.put(transitionId, file);
        this.journalPlayers.put(transitionId, playerId);
        this.journalsByPlayer.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(transitionId);
    }

    private void unindex(final UUID transitionId) {
        this.journalFiles.remove(transitionId);
        final UUID player = this.journalPlayers.remove(transitionId);
        if (player == null) return;
        final Set<UUID> ids = this.journalsByPlayer.get(player);
        if (ids == null) return;
        ids.remove(transitionId);
        if (ids.isEmpty()) this.journalsByPlayer.remove(player);
    }

    /** Reads an indexed journal; {@code null} when it is gone, quarantined and rethrown when unreadable. */
    private ProfileTransition readIndexed(final UUID transitionId) throws IOException {
        final Path file = this.journalFiles.get(transitionId);
        if (file == null) return null;
        if (!Files.isRegularFile(file)) {
            unindex(transitionId);   // removed by an operator
            return null;
        }
        try {
            return readTransition(file);
        } catch (final IOException unreadable) {
            final Path kept = quarantine(file, this.journalPlayers.get(transitionId), transitionId, unreadable);
            throw new IOException("the transition journal " + file.getFileName() + " could not be read ("
                + unreadable.getMessage() + ") and was set aside as " + kept.getFileName(), unreadable);
        }
    }

    /**
     * Renames an unreadable journal to {@code *.corrupt}, logs one ERROR and blocks its player. The
     * new name starts with the player id, so the block survives a restart.
     */
    private Path quarantine(final Path file, final UUID playerId, final UUID transitionId, final IOException cause) {
        if (transitionId != null) unindex(transitionId);
        final String base = playerId != null && transitionId != null
            ? playerId + "." + transitionId + ".awfp" : file.getFileName().toString();
        Path target = this.transitions.resolve(base + CORRUPT);
        if (Files.exists(target)) target = this.transitions.resolve(base + "." + System.currentTimeMillis() + CORRUPT);
        Path kept = target;
        try {
            Files.move(file, target);
        } catch (final IOException moveFailed) {
            kept = file;
        }
        if (playerId != null) {
            this.quarantined.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(kept);
        } else {
            this.quarantinedUnknown.add(kept);
        }
        SourbyLogger.error("AWF MultiProfile: transition journal " + file + " cannot be read (" + cause.getMessage()
            + ")" + (kept == file ? " and could not be renamed" : " and was moved to " + kept.getFileName())
            + (playerId == null
                ? "; its player is unknown (a journal of an earlier build), so no player is blocked"
                : "; player " + playerId + " cannot switch or save until an operator inspects and deletes it"));
        return kept;
    }

    private void rememberQuarantined(final Path file) {
        final String[] parts = file.getFileName().toString().split("\\.");
        final UUID player = parts.length >= 4 && uuidOrNull(parts[1]) != null ? uuidOrNull(parts[0]) : null;
        if (player != null) {
            this.quarantined.computeIfAbsent(player, ignored -> new ArrayList<>()).add(file);
        } else {
            this.quarantinedUnknown.add(file);
        }
    }

    /** The quarantined journals of a player that still exist; an operator unblocks by deleting them. */
    private List<Path> quarantinedFor(final UUID playerId) {
        final List<Path> files = this.quarantined.get(playerId);
        if (files == null) return List.of();
        files.removeIf(file -> !Files.exists(file));
        if (files.isEmpty()) {
            this.quarantined.remove(playerId);
            return List.of();
        }
        return List.copyOf(files);
    }

    /** Deletes a player's oldest terminal journals beyond {@link #TERMINAL_JOURNALS_KEPT}; best effort. */
    private void pruneTerminalJournals(final UUID playerId) {
        final List<ProfileTransition> terminal = new ArrayList<>();
        for (final UUID transitionId : List.copyOf(this.journalsByPlayer.getOrDefault(playerId, Set.of()))) {
            try {
                final ProfileTransition journal = readIndexed(transitionId);
                if (journal != null && journal.terminal()) terminal.add(journal);
            } catch (final IOException setAside) {
                // Logged and quarantined by readIndexed; pruning the rest still helps.
            }
        }
        if (terminal.size() <= TERMINAL_JOURNALS_KEPT) return;
        terminal.sort(Comparator.comparingLong(ProfileTransition::updatedAtMillis).reversed());
        for (int i = TERMINAL_JOURNALS_KEPT; i < terminal.size(); i++) {
            final UUID transitionId = terminal.get(i).transitionId();
            try {
                deleteTransition(transitionId, ProfileWriteGuard.UNGUARDED);
            } catch (final IOException failed) {
                // The journal that was just written is durable; an old record staying is harmless.
                SourbyLogger.warn("AWF MultiProfile: could not prune transition journal " + transitionId + ": "
                    + failed.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ format-1 snapshots

    /** Snapshot ids a non-terminal journal of the account names; {@code null} when unknown (keep all). */
    private Set<UUID> journalPins(final UUID accountId) {
        final Set<UUID> pinned = new HashSet<>();
        try {
            for (final ProfileTransition journal : transitionsFor(accountId)) {
                if (journal.terminal()) continue;
                if (journal.fromSnapshotId() != null) pinned.add(journal.fromSnapshotId());
                if (journal.toSnapshotId() != null) pinned.add(journal.toSnapshotId());
            }
        } catch (final IOException unknown) {
            return null;
        }
        return pinned;
    }

    /** Keeps the {@code retain} newest snapshots plus every pinned one; {@code pinned == null} keeps all. */
    private void pruneSnapshots(final UUID profileId, final int retain, final Set<UUID> pinned) throws IOException {
        if (pinned == null) return;
        final List<Path> files = snapshotFiles(profileId);
        final int older = files.size() - Math.max(1, retain);
        for (int i = 0; i < older; i++) {
            final Path file = files.get(i);
            if (!pinned.contains(snapshotIdOf(file))) Files.deleteIfExists(file);
        }
    }

    private void writeSnapshotFile(final ProfileSnapshot snapshot) throws IOException {
        final Path directory = this.snapshots.resolve(snapshot.profileId().toString());
        Files.createDirectories(directory);
        write(directory.resolve(snapshot.revision() + "-" + snapshot.snapshotId() + ".awfp"), TYPE_SNAPSHOT,
            snapshot.revision(), ProfileRecords.snapshotPayload(snapshot));
    }

    // ------------------------------------------------------------------ format-1 records

    private Path accountFile(final UUID playerId) {
        return this.accounts.resolve(playerId + ".awfp");
    }

    private Path legacyFile(final UUID playerId) {
        return this.accounts.resolve(playerId + ".legacy.awfp");
    }

    private void checkProfileRevision(final UUID profileId, final ProfileWriteGuard guard) throws IOException {
        if (guard.expectedRevision() == ProfileWriteGuard.ANY) return;
        final Path file = this.profiles.resolve(profileId + ".awfp");
        guard.check("profile " + profileId,
            Files.isRegularFile(file) ? read(file, TYPE_PROFILE).revision() : ProfileWriteGuard.ABSENT);
    }

    private void writeProfile(final AuroraProfile profile) throws IOException {
        write(this.profiles.resolve(profile.id() + ".awfp"), TYPE_PROFILE, profile.revision(),
            ProfileRecords.profilePayload(profile));
    }

    /** A flat (format-1) profile snapshot; a format-2 frame there is refused. */
    private ProfileSnapshot readSnapshot(final Path file, final UUID profileId) throws IOException {
        final ProfileRecords.Frame frame = read(file, TYPE_SNAPSHOT);
        if (frame.format() != ProfileRecords.FORMAT) throw new IOException("snapshot " + file + " is not a format-1 profile snapshot");
        return ProfileRecords.snapshot(frame, profileId, file.toString());
    }

    private ProfileTransition readTransition(final Path file) throws IOException {
        final ProfileRecords.Frame frame = read(file, TYPE_TRANSITION);
        return frame.format() == ProfileRecords.FORMAT_2 ? ProfileRecords.transition2(frame, file.toString())
            : ProfileRecords.transition(frame, file.toString());
    }

    /**
     * Format-1 snapshot files oldest first: by revision, then by modification time, so of two snapshots
     * that share a revision (a crash between a snapshot and its profile record) the later one is newest.
     * Member stream subdirectories are not listed.
     */
    private List<Path> snapshotFiles(final UUID profileId) throws IOException {
        final Path directory = this.snapshots.resolve(profileId.toString());
        if (!Files.isDirectory(directory)) return List.of();
        final Map<Path, FileTime> modified = new HashMap<>();
        try (DirectoryStream<Path> found = Files.newDirectoryStream(directory, "*.awfp")) {
            for (final Path file : found) {
                if (Files.isRegularFile(file)) modified.put(file, modifiedAt(file));
            }
        }
        final List<Path> files = new ArrayList<>(modified.keySet());
        files.sort(Comparator.comparingLong((Path path) -> revisionOf(path.getFileName().toString()))
            .thenComparing(modified::get)
            .thenComparing(path -> path.getFileName().toString()));
        return files;
    }

    private static FileTime modifiedAt(final Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (final IOException unknown) {
            return FileTime.fromMillis(0L);
        }
    }

    private static long revisionOf(final String name) {
        final int dash = name.indexOf('-');
        if (dash <= 0) return -1L;
        try {
            return Long.parseLong(name.substring(0, dash));
        } catch (final NumberFormatException malformed) {
            return -1L;
        }
    }

    /** The snapshot id in {@code <revision>-<snapshot>.awfp}, or {@code null}. */
    private static UUID snapshotIdOf(final Path file) {
        final String name = file.getFileName().toString();
        final int dash = name.indexOf('-');
        if (dash <= 0 || !name.endsWith(".awfp")) return null;
        return uuidOrNull(name.substring(dash + 1, name.length() - ".awfp".length()));
    }

    private static UUID uuidOrNull(final String text) {
        try {
            return UUID.fromString(text);
        } catch (final IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static String lockHolder(final FileChannel channel) {
        try {
            final ByteBuffer buffer = ByteBuffer.allocate(256);
            channel.read(buffer, 0L);
            final String holder = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).trim();
            return holder.isEmpty() ? "another process" : holder;
        } catch (final IOException unreadable) {
            return "another process";
        }
    }

    /** The format-1 writer (Build 50): temp file, force, atomic rename or plain rename, best-effort directory force. */
    private void write(final Path file, final byte type, final long revision, final byte[] payload) throws IOException {
        final ByteBuffer buffer = ByteBuffer.wrap(ProfileRecords.frame(type, revision, payload,
            file.getFileName().toString()));
        // Unique per write, so an interrupted write never collides with the next one.
        final Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException noAtomicRename) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        forceDirectoryBestEffort(file.getParent());
    }

    /** A record frame of the store's current format(s), read with the type's bound from the actual stream. */
    private ProfileRecords.Frame read(final Path file, final byte expectedType) throws IOException {
        return read(file, expectedType, access());
    }

    private ProfileRecords.Frame read(final Path file, final byte expectedType, final Access access) throws IOException {
        final int expected = switch (access) {
            case LEGACY -> ProfileRecords.FORMAT;
            case READY -> ProfileRecords.FORMAT_2;
            case MIGRATING -> 0;
        };
        final int limit = (expected == ProfileRecords.FORMAT_2 ? ProfileRecords.maxPayload(expectedType) : MAX_PAYLOAD)
            + ProfileRecords.OVERHEAD;
        final ProfileRecords.Frame frame = ProfileRecords.unframe(FileProfileIo.readBounded(file, limit), expectedType,
            file.toString());
        if (expected != 0 && frame.format() != expected) {
            throw new IOException("record " + file + " is format " + frame.format() + " in a format-" + expected + " store; refused");
        }
        return frame;
    }

    private static void forceDirectoryBestEffort(final Path directory) {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (final IOException | RuntimeException notSupported) {
            // Best effort: the rename itself is already durable on the filesystems SourbyCraft targets.
        }
    }

    private static void deleteRecursively(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var walk = Files.walk(directory)) {
            for (final Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (final IOException failed) {
            SourbyLogger.warn("AWF MultiProfile: could not fully delete " + directory + ": " + failed.getMessage());
            throw failed;
        }
    }
}
