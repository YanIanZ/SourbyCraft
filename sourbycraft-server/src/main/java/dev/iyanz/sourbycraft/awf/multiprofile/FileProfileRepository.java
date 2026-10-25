package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * <p>Each file is a framed record: magic, format, type, revision, payload length, payload and the
 * SHA-256 of the payload. Writes go to a temporary file in the same directory, are forced to disk,
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

    private static final int MAGIC = 0x41574652; // "AWFR"
    private static final int FORMAT = 1;
    private static final byte TYPE_ACCOUNT = 1;
    private static final byte TYPE_PROFILE = 2;
    private static final byte TYPE_BINDING = 3;
    private static final byte TYPE_SNAPSHOT = 4;
    private static final byte TYPE_TRANSITION = 5;
    private static final int MAX_PAYLOAD = ProfileSnapshot.MAX_STATE_BYTES + 64 * 1024;
    /** Version of the trailer Build 50 appends to a journal (from-fence, incarnation). */
    private static final int TRANSITION_TRAILER = 1;
    /** Terminal journals kept per player; older ones are deleted when a newer one is written. */
    private static final int TERMINAL_JOURNALS_KEPT = 5;
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
    public synchronized PlayerAccount loadAccount(final UUID playerId, final int defaultSlots) throws IOException {
        final Path file = accountFile(playerId);
        if (!Files.isRegularFile(file)) return PlayerAccount.empty(playerId, defaultSlots);
        final Frame frame = read(file, TYPE_ACCOUNT);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload))) {
            final UUID stored = uuid(in);
            final int slots = in.readInt();
            final boolean hasActive = in.readBoolean();
            final UUID active = hasActive ? uuid(in) : null;
            final int count = bounded(in.readInt(), PlayerAccount.MAX_SLOTS, "account profiles");
            final List<UUID> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) ids.add(uuid(in));
            if (!stored.equals(playerId)) throw new IOException("account file " + file + " holds " + stored);
            return new PlayerAccount(playerId, ids, active, slots, frame.revision);
        }
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
            guard.check("account " + account.playerId(), Files.isRegularFile(file) ? read(file, TYPE_ACCOUNT).revision : 0L);
        }
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            uuid(out, account.playerId());
            out.writeInt(account.slotLimit());
            out.writeBoolean(account.activeProfile() != null);
            if (account.activeProfile() != null) uuid(out, account.activeProfile());
            out.writeInt(account.profileIds().size());
            for (final UUID id : account.profileIds()) uuid(out, id);
        }
        write(file, TYPE_ACCOUNT, account.revision(), buffer.toByteArray());
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
        final Frame frame = read(file, TYPE_BINDING);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload))) {
            final UUID stored = uuid(in);
            final int count = bounded(in.readInt(), ProfileWorldBinding.MAX_WORLDS, "binding worlds");
            final Map<String, ProfileWorldRole> worlds = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                final String world = text(in);
                final int role = in.readUnsignedByte();
                if (role >= ProfileWorldRole.values().length) throw new IOException("binding role " + role + " in " + file);
                worlds.put(world, ProfileWorldRole.values()[role]);
            }
            final String template = text(in);
            return Optional.of(new ProfileWorldBinding(stored, worlds, template.isEmpty() ? null : template));
        }
    }

    /** Unguarded write, for tests and offline tools; the engine always passes a guard. */
    public void saveBinding(final ProfileWorldBinding binding) throws IOException {
        saveBinding(binding, ProfileWriteGuard.UNGUARDED);
    }

    @Override
    public synchronized void saveBinding(final ProfileWorldBinding binding, final ProfileWriteGuard guard) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            uuid(out, binding.profileId());
            out.writeInt(binding.worlds().size());
            for (final Map.Entry<String, ProfileWorldRole> entry : binding.worlds().entrySet()) {
                text(out, entry.getKey());
                out.writeByte(entry.getValue().ordinal());
            }
            text(out, binding.template() == null ? "" : binding.template());
        }
        write(this.bindings.resolve(binding.profileId() + ".awfp"), TYPE_BINDING, 1L, buffer.toByteArray());
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
        write(file, TYPE_SNAPSHOT, snapshot.revision(), snapshotPayload(snapshot));
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
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            uuid(out, transition.transitionId());
            uuid(out, transition.playerId());
            out.writeBoolean(transition.fromProfile() != null);
            if (transition.fromProfile() != null) uuid(out, transition.fromProfile());
            uuid(out, transition.toProfile());
            out.writeByte(transition.phase().ordinal());
            out.writeLong(transition.deadlineMillis());
            out.writeBoolean(transition.fromSnapshotId() != null);
            if (transition.fromSnapshotId() != null) uuid(out, transition.fromSnapshotId());
            out.writeBoolean(transition.toSnapshotId() != null);
            if (transition.toSnapshotId() != null) uuid(out, transition.toSnapshotId());
            // Earlier builds recorded one fence here: the target claim's.
            out.writeLong(transition.toFence());
            text(out, transition.failure() == null ? "" : transition.failure());
            out.writeLong(transition.updatedAtMillis());
            // Trailer: earlier builds stop reading before it, so they still read this journal.
            out.writeByte(TRANSITION_TRAILER);
            out.writeLong(transition.fromFence());
            text(out, transition.incarnation() == null ? "" : transition.incarnation());
        }
        final UUID transitionId = transition.transitionId();
        final Path file = this.journalFiles.getOrDefault(transitionId,
            this.transitions.resolve(transition.playerId() + "." + transitionId + ".awfp"));
        write(file, TYPE_TRANSITION, 1L, buffer.toByteArray());
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
            snapshot.revision(), snapshotPayload(snapshot));
    }

    private static byte[] snapshotPayload(final ProfileSnapshot snapshot) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(snapshot.size() + 128);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            uuid(out, snapshot.profileId());
            uuid(out, snapshot.snapshotId());
            out.writeInt(snapshot.schemaVersion());
            out.writeLong(snapshot.revision());
            text(out, snapshot.checksum());
            out.writeLong(snapshot.createdAtMillis());
            final byte[] state = snapshot.state();
            out.writeInt(state.length);
            out.write(state);
        }
        return buffer.toByteArray();
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
            Files.isRegularFile(file) ? read(file, TYPE_PROFILE).revision : ProfileWriteGuard.ABSENT);
    }

    private void writeProfile(final AuroraProfile profile) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            uuid(out, profile.id());
            uuid(out, profile.accountId());
            text(out, profile.type());
            out.writeLong(profile.createdAtMillis());
            out.writeLong(profile.revision());
            out.writeByte(profile.status().ordinal());
            out.writeInt(profile.metadata().size());
            for (final Map.Entry<String, String> entry : profile.metadata().entrySet()) {
                text(out, entry.getKey());
                text(out, entry.getValue());
            }
        }
        write(this.profiles.resolve(profile.id() + ".awfp"), TYPE_PROFILE, profile.revision(), buffer.toByteArray());
    }

    private AuroraProfile readProfile(final Path file, final UUID expected) throws IOException {
        final Frame frame = read(file, TYPE_PROFILE);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload))) {
            final UUID id = uuid(in);
            final UUID account = uuid(in);
            final String type = text(in);
            final long created = in.readLong();
            final long revision = in.readLong();
            final int status = in.readUnsignedByte();
            if (status >= ProfileStatus.values().length) throw new IOException("profile status " + status + " in " + file);
            final int metadata = bounded(in.readInt(), AuroraProfile.MAX_METADATA, "profile metadata");
            final Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < metadata; i++) values.put(text(in), text(in));
            if (!id.equals(expected)) throw new IOException("profile file " + file + " holds " + id);
            return new AuroraProfile(id, account, type, values, created, revision, ProfileStatus.values()[status]);
        }
    }

    private ProfileSnapshot readSnapshot(final Path file, final UUID profileId) throws IOException {
        final Frame frame = read(file, TYPE_SNAPSHOT);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload))) {
            final UUID storedProfile = uuid(in);
            final UUID snapshotId = uuid(in);
            final int schema = in.readInt();
            final long revision = in.readLong();
            final String checksum = text(in);
            final long createdAt = in.readLong();
            final int length = in.readInt();
            if (length < 1 || length > ProfileSnapshot.MAX_STATE_BYTES) throw new IOException("snapshot length " + length);
            final byte[] state = in.readNBytes(length);
            if (state.length != length) throw new EOFException("snapshot truncated: " + file);
            if (!storedProfile.equals(profileId)) throw new IOException("snapshot " + file + " belongs to " + storedProfile);
            final String actual = ProfileStateCodec.checksum(state);
            if (!actual.equals(checksum)) throw new IOException("snapshot checksum mismatch in " + file);
            return new ProfileSnapshot(profileId, snapshotId, schema, revision, state, checksum, createdAt);
        }
    }

    private ProfileTransition readTransition(final Path file) throws IOException {
        final Frame frame = read(file, TYPE_TRANSITION);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload))) {
            final UUID id = uuid(in);
            final UUID player = uuid(in);
            final UUID from = in.readBoolean() ? uuid(in) : null;
            final UUID to = uuid(in);
            final int phase = in.readUnsignedByte();
            if (phase >= TransitionPhase.values().length) throw new IOException("transition phase " + phase + " in " + file);
            final long deadline = in.readLong();
            final UUID fromSnapshot = in.readBoolean() ? uuid(in) : null;
            final UUID toSnapshot = in.readBoolean() ? uuid(in) : null;
            final long toFence = in.readLong();
            final String failure = text(in);
            final long updated = in.readLong();
            long fromFence = 0L;
            String incarnation = null;
            // A journal of an earlier build ends here; its one fence is the target fence.
            if (in.available() > 0 && in.readUnsignedByte() >= TRANSITION_TRAILER) {
                fromFence = in.readLong();
                final String issuedBy = text(in);
                incarnation = issuedBy.isEmpty() ? null : issuedBy;
            }
            return new ProfileTransition(id, player, from, to, TransitionPhase.values()[phase], deadline, fromSnapshot,
                toSnapshot, fromFence, toFence, incarnation, failure.isEmpty() ? null : failure, updated);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("transition journal " + file + " is invalid: " + invalid.getMessage(), invalid);
        }
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

    private record Frame(byte type, long revision, byte[] payload) {}

    private void write(final Path file, final byte type, final long revision, final byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) throw new IOException("record " + file.getFileName() + " is too large: " + payload.length);
        final byte[] digest = sha256(payload);
        final ByteBuffer buffer = ByteBuffer.allocate(4 + 1 + 1 + 8 + 4 + payload.length + 32);
        buffer.putInt(MAGIC);
        buffer.put((byte) FORMAT);
        buffer.put(type);
        buffer.putLong(revision);
        buffer.putInt(payload.length);
        buffer.put(payload);
        buffer.put(digest);
        buffer.flip();
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

    private Frame read(final Path file, final byte expectedType) throws IOException {
        final long size = Files.size(file);
        if (size > MAX_PAYLOAD + 64L) {
            throw new IOException("record " + file + " is " + size + " bytes; the limit is " + MAX_PAYLOAD);
        }
        final byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_PAYLOAD + 65);
        }
        if (bytes.length > MAX_PAYLOAD + 64) throw new IOException("record " + file + " exceeds the size limit");
        if (bytes.length < 4 + 1 + 1 + 8 + 4 + 32) throw new IOException("record " + file + " is truncated");
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC) throw new IOException("record " + file + " has no AWF profile magic");
        final int format = buffer.get() & 0xFF;
        if (format != FORMAT) throw new IOException("record " + file + " has format " + format);
        final byte type = buffer.get();
        if (type != expectedType) throw new IOException("record " + file + " has type " + type + ", expected " + expectedType);
        final long revision = buffer.getLong();
        final int length = buffer.getInt();
        if (length < 0 || length > MAX_PAYLOAD || buffer.remaining() != length + 32) {
            throw new IOException("record " + file + " has an invalid length");
        }
        final byte[] payload = new byte[length];
        buffer.get(payload);
        final byte[] digest = new byte[32];
        buffer.get(digest);
        if (!MessageDigest.isEqual(digest, sha256(payload))) {
            throw new IOException("record " + file + " fails its checksum");
        }
        return new Frame(type, revision, payload);
    }

    private static void forceDirectory(final Path directory) {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (final IOException | RuntimeException notSupported) {
            // Best effort: the rename itself is already durable on the filesystems SourbyCraft targets.
        }
    }

    private static byte[] sha256(final byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("the JDK has no SHA-256", impossible);
        }
    }

    private static void uuid(final DataOutputStream out, final UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID uuid(final DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void text(final DataOutputStream out, final String text) throws IOException {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > 4096) throw new IOException("text length " + length);
        final byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("text truncated");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int bounded(final int value, final int max, final String what) throws IOException {
        if (value < 0 || value > max) throw new IOException(what + " count " + value);
        return value;
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
