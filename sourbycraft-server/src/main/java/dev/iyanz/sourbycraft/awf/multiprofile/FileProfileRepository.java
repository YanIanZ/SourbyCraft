package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A file-backed {@link ProfileRepository} for one server.
 *
 * <p>Layout under the configured directory:</p>
 * <pre>
 * repository.lock                       held by the process using the directory
 * accounts/&lt;player&gt;.awfp
 * accounts/&lt;player&gt;.legacy.awfp        the state the player had before their first profile
 * accounts/&lt;player&gt;.legacy.&lt;millis&gt;.awfp  an earlier such archive, kept when a later one is written
 * profiles/&lt;profile&gt;.awfp
 * bindings/&lt;profile&gt;.awfp
 * snapshots/&lt;profile&gt;/&lt;revision&gt;-&lt;snapshot&gt;.awfp
 * transitions/&lt;player&gt;.&lt;transition&gt;.awfp  (journals of earlier builds: &lt;transition&gt;.awfp)
 * transitions/*.corrupt                 journals that could not be read, set aside
 * </pre>
 *
 * <p>Each file is a framed record ({@link ProfileRecords}, the format {@link RedisProfileRepository}
 * stores too): magic, format, type, revision, payload length, payload and the SHA-256 of the
 * payload. Writes go to a temporary file in the same directory, are forced to disk,
 * then atomically renamed; a crash therefore leaves either the old or the new record, never a torn
 * one. An unreadable profile, account or snapshot raises {@link IOException} naming the file instead
 * of being ignored — silently dropping a profile would destroy the only copy of a player's state. An
 * unreadable journal is renamed to {@code *.corrupt} with one ERROR; until an operator deletes it,
 * {@link #transitionsFor} fails for that player only, so one bad file no longer stops every switch.</p>
 *
 * <p>Single process only. The constructor takes an exclusive OS lock on {@code repository.lock}
 * (released by {@link #close}) and fails while another process holds it; journals are indexed in
 * memory on that basis. A {@link ProfileWriteGuard}'s expected revision is compared under this
 * object's lock, which makes account and profile writes compare-and-set within this process. Its
 * ownership claim is not checked here. This repository is therefore <strong>not</strong> a
 * distributed fence: two servers must never share its directory, and network filesystems that do not
 * implement OS file locks may not enforce the lock.</p>
 *
 * <p>This implementation is {@code synchronized}: profile operations are low-frequency and the engine
 * already runs them one at a time. It must only be used from I/O workers.</p>
 */
public final class FileProfileRepository implements ProfileRepository {

    private static final byte TYPE_ACCOUNT = ProfileRecords.TYPE_ACCOUNT;
    private static final byte TYPE_PROFILE = ProfileRecords.TYPE_PROFILE;
    private static final byte TYPE_BINDING = ProfileRecords.TYPE_BINDING;
    private static final byte TYPE_SNAPSHOT = ProfileRecords.TYPE_SNAPSHOT;
    private static final byte TYPE_TRANSITION = ProfileRecords.TYPE_TRANSITION;
    private static final int MAX_PAYLOAD = ProfileRecords.MAX_PAYLOAD;
    /** Terminal journals kept per player; older ones are deleted when a newer one is written. */
    static final int TERMINAL_JOURNALS_KEPT = 5;
    private static final String LOCK_FILE = "repository.lock";
    private static final String CORRUPT = ".corrupt";

    private final Path root;
    private final Path accounts;
    private final Path profiles;
    private final Path bindings;
    private final Path snapshots;
    private final Path transitions;
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
    private boolean closed;

    /** Opens the directory for an unnamed holder; see {@link #FileProfileRepository(Path, String)}. */
    public FileProfileRepository(final Path root) throws IOException {
        this(root, "an unnamed process");
    }

    /**
     * Opens the directory and takes its exclusive lock, recording {@code holder} (the node id) in the
     * lock file so a refused second server can name who holds it.
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
            indexJournals();
        } catch (final IOException | RuntimeException failed) {
            close();
            throw failed;
        }
    }

    public Path root() {
        return this.root;
    }

    @Override
    public String describe() {
        return "file " + this.root;
    }

    @Override
    public synchronized PlayerAccount loadAccount(final UUID playerId, final int defaultSlots) throws IOException {
        final Path file = accountFile(playerId);
        if (!Files.isRegularFile(file)) return PlayerAccount.empty(playerId, defaultSlots);
        return ProfileRecords.account(read(file, TYPE_ACCOUNT), playerId, file.toString());
    }

    /** Unguarded write, for tests and offline tools; the engine always passes a guard. */
    public void saveAccount(final PlayerAccount account) throws IOException {
        saveAccount(account, ProfileWriteGuard.UNGUARDED);
    }

    @Override
    public synchronized void saveAccount(final PlayerAccount account, final ProfileWriteGuard guard) throws IOException {
        final Path file = accountFile(account.playerId());
        if (guard.expectedRevision() != ProfileWriteGuard.ANY) {
            // An account that was never stored is what loadAccount returns: revision 0.
            guard.check("account " + account.playerId(), Files.isRegularFile(file) ? read(file, TYPE_ACCOUNT).revision() : 0L);
        }
        write(file, TYPE_ACCOUNT, account.revision(), ProfileRecords.accountPayload(account));
    }

    @Override
    public synchronized Optional<AuroraProfile> profile(final UUID profileId) throws IOException {
        final Path file = this.profiles.resolve(profileId + ".awfp");
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(readProfile(file, profileId));
    }

    /** Unguarded write, for tests and offline tools; the engine always passes a guard. */
    public void saveProfile(final AuroraProfile profile) throws IOException {
        saveProfile(profile, ProfileWriteGuard.UNGUARDED);
    }

    @Override
    public synchronized void saveProfile(final AuroraProfile profile, final ProfileWriteGuard guard) throws IOException {
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
        final Path file = this.bindings.resolve(profileId + ".awfp");
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(ProfileRecords.binding(read(file, TYPE_BINDING), file.toString()));
    }

    /** Unguarded write, for tests and offline tools; the engine always passes a guard. */
    public void saveBinding(final ProfileWorldBinding binding) throws IOException {
        saveBinding(binding, ProfileWriteGuard.UNGUARDED);
    }

    @Override
    public synchronized void saveBinding(final ProfileWorldBinding binding, final ProfileWriteGuard guard) throws IOException {
        write(this.bindings.resolve(binding.profileId() + ".awfp"), TYPE_BINDING, 1L, ProfileRecords.bindingPayload(binding));
    }

    @Override
    public synchronized Optional<ProfileSnapshot> latestSnapshot(final UUID profileId) throws IOException {
        final List<Path> files = snapshotFiles(profileId);
        for (int i = files.size() - 1; i >= 0; i--) {
            final ProfileSnapshot snapshot = readSnapshot(files.get(i), profileId);
            if (snapshot != null) return Optional.of(snapshot);
        }
        return Optional.empty();
    }

    @Override
    public synchronized Optional<ProfileSnapshot> snapshotById(final UUID profileId, final UUID snapshotId) throws IOException {
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
        if (!next.id().equals(snapshot.profileId())) {
            throw new IllegalArgumentException("snapshot of " + snapshot.profileId() + " published for " + next.id());
        }
        checkProfileRevision(next.id(), guard);
        final Set<UUID> pinned = pinnedSnapshots(next.accountId());
        writeSnapshotFile(snapshot);
        pruneSnapshots(snapshot.profileId(), retain, pinned);
        writeProfile(next);
    }

    /**
     * Writes a snapshot without touching the profile record, then prunes like
     * {@link #publishSnapshot}; for tests and offline tools.
     */
    public synchronized void saveSnapshot(final ProfileSnapshot snapshot, final int retain) throws IOException {
        final Optional<AuroraProfile> profile = profile(snapshot.profileId());
        final Set<UUID> pinned = profile.isPresent() ? pinnedSnapshots(profile.get().accountId()) : Set.of();
        writeSnapshotFile(snapshot);
        pruneSnapshots(snapshot.profileId(), retain, pinned);
    }

    @Override
    public synchronized void saveLegacySnapshot(final UUID playerId, final ProfileSnapshot snapshot,
                                                final ProfileWriteGuard guard) throws IOException {
        if (!snapshot.profileId().equals(playerId)) {
            throw new IllegalArgumentException("legacy snapshot of " + snapshot.profileId() + " saved for " + playerId);
        }
        final Path file = legacyFile(playerId);
        if (Files.isRegularFile(file)) {
            // An earlier first switch archived a state already (it was rolled back, or the account was
            // reset). That archive may be the only copy of what the player had, so it is kept aside.
            Files.move(file, this.accounts.resolve(playerId + ".legacy." + System.currentTimeMillis() + ".awfp"));
        }
        write(file, TYPE_SNAPSHOT, snapshot.revision(), ProfileRecords.snapshotPayload(snapshot));
    }

    @Override
    public synchronized Optional<ProfileSnapshot> legacySnapshot(final UUID playerId) throws IOException {
        final Path file = legacyFile(playerId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(readSnapshot(file, playerId));
    }

    @Override
    public synchronized Optional<ProfileTransition> transition(final UUID transitionId) throws IOException {
        return Optional.ofNullable(readIndexed(transitionId));
    }

    @Override
    public synchronized List<ProfileTransition> transitionsFor(final UUID playerId) throws IOException {
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

    @Override
    public synchronized void saveTransition(final ProfileTransition transition, final ProfileWriteGuard guard)
        throws IOException {
        final UUID transitionId = transition.transitionId();
        final Path file = this.journalFiles.getOrDefault(transitionId,
            this.transitions.resolve(transition.playerId() + "." + transitionId + ".awfp"));
        write(file, TYPE_TRANSITION, 1L, ProfileRecords.transitionPayload(transition));
        index(transitionId, transition.playerId(), file);
        if (transition.terminal()) pruneTerminalJournals(transition.playerId());
    }

    /** Unguarded delete, for tests and offline tools; the engine always passes a guard. */
    public void deleteTransition(final UUID transitionId) throws IOException {
        deleteTransition(transitionId, ProfileWriteGuard.UNGUARDED);
    }

    @Override
    public synchronized void deleteTransition(final UUID transitionId, final ProfileWriteGuard guard) throws IOException {
        final Path file = this.journalFiles.get(transitionId);
        if (file != null) Files.deleteIfExists(file);
        unindex(transitionId);
    }

    /** Removes the profile record, its binding and its snapshots; for tests and offline tools. */
    public synchronized void deleteProfile(final UUID profileId) throws IOException {
        deleteProfileData(profileId, ProfileWriteGuard.UNGUARDED);
        Files.deleteIfExists(this.profiles.resolve(profileId + ".awfp"));
    }

    /** Unguarded delete, for tests and offline tools; the engine always passes a guard. */
    public void deleteProfileData(final UUID profileId) throws IOException {
        deleteProfileData(profileId, ProfileWriteGuard.UNGUARDED);
    }

    @Override
    public synchronized void deleteProfileData(final UUID profileId, final ProfileWriteGuard guard) throws IOException {
        Files.deleteIfExists(this.bindings.resolve(profileId + ".awfp"));
        deleteRecursively(this.snapshots.resolve(profileId.toString()));
    }

    // ------------------------------------------------------------------ listing, for the repository migration

    /** Every stored account, by player id; files that are not {@code <uuid>.awfp} are skipped. */
    public synchronized List<UUID> accountIds() throws IOException {
        return idsIn(this.accounts);
    }

    /** Every stored profile record, tombstones included. */
    public synchronized List<UUID> profileIds() throws IOException {
        return idsIn(this.profiles);
    }

    /** Every snapshot of a profile, oldest first in {@link #latestSnapshot}'s order, checksum-verified. */
    public synchronized List<ProfileSnapshot> snapshots(final UUID profileId) throws IOException {
        final List<ProfileSnapshot> found = new ArrayList<>();
        for (final Path file : snapshotFiles(profileId)) found.add(readSnapshot(file, profileId));
        return List.copyOf(found);
    }

    /**
     * Every archived pre-profile state of a player, oldest first; the last one is
     * {@link #legacySnapshot}. Checksum-verified.
     */
    public synchronized List<ProfileSnapshot> legacySnapshots(final UUID playerId) throws IOException {
        final Map<Long, Path> earlier = new java.util.TreeMap<>();
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
        for (final Path file : earlier.values()) found.add(readSnapshot(file, playerId));
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

    // ------------------------------------------------------------------ snapshots

    /** Snapshot ids a non-terminal journal of the account names; {@code null} when unknown (keep all). */
    private Set<UUID> pinnedSnapshots(final UUID accountId) {
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

    // ------------------------------------------------------------------ records

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

    private AuroraProfile readProfile(final Path file, final UUID expected) throws IOException {
        return ProfileRecords.profile(read(file, TYPE_PROFILE), expected, file.toString());
    }

    private ProfileSnapshot readSnapshot(final Path file, final UUID profileId) throws IOException {
        return ProfileRecords.snapshot(read(file, TYPE_SNAPSHOT), profileId, file.toString());
    }

    private ProfileTransition readTransition(final Path file) throws IOException {
        return ProfileRecords.transition(read(file, TYPE_TRANSITION), file.toString());
    }

    /**
     * Snapshot files oldest first: by revision, then by modification time, so of two snapshots that
     * share a revision (a crash between a snapshot and its profile record) the later one is newest.
     */
    private List<Path> snapshotFiles(final UUID profileId) throws IOException {
        final Path directory = this.snapshots.resolve(profileId.toString());
        if (!Files.isDirectory(directory)) return List.of();
        final Map<Path, FileTime> modified = new HashMap<>();
        try (DirectoryStream<Path> found = Files.newDirectoryStream(directory, "*.awfp")) {
            for (final Path file : found) modified.put(file, modifiedAt(file));
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
        forceDirectory(file.getParent());
    }

    private ProfileRecords.Frame read(final Path file, final byte expectedType) throws IOException {
        final long size = Files.size(file);
        if (size > MAX_PAYLOAD + 64L) {
            throw new IOException("record " + file + " is " + size + " bytes; the limit is " + MAX_PAYLOAD);
        }
        final byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_PAYLOAD + 65);
        }
        return ProfileRecords.unframe(bytes, expectedType, file.toString());
    }

    private static void forceDirectory(final Path directory) {
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
