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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A file-backed {@link ProfileRepository}.
 *
 * <p>Layout under the configured directory:</p>
 * <pre>
 * accounts/&lt;player&gt;.awfp
 * profiles/&lt;profile&gt;.awfp
 * bindings/&lt;profile&gt;.awfp
 * snapshots/&lt;profile&gt;/&lt;revision&gt;-&lt;snapshot&gt;.awfp
 * transitions/&lt;transition&gt;.awfp
 * </pre>
 *
 * <p>Each file is a framed record: magic, format, type, revision, payload length, payload and the
 * SHA-256 of the payload. Writes go to a temporary file in the same directory, are forced to disk,
 * then atomically renamed; a crash therefore leaves either the old or the new record, never a torn
 * one. An unreadable record raises {@link IOException} naming the file instead of being ignored —
 * silently dropping a profile would destroy the only copy of a player's state.</p>
 *
 * <p>This implementation is {@code synchronized}: profile operations are low-frequency and already
 * serialised per player by ownership, and a lock here is cheaper than a per-path lock table whose
 * lifecycle would need its own test. It must only be used from I/O workers.</p>
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

    private final Path root;
    private final Path accounts;
    private final Path profiles;
    private final Path bindings;
    private final Path snapshots;
    private final Path transitions;

    public FileProfileRepository(final Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.accounts = this.root.resolve("accounts");
        this.profiles = this.root.resolve("profiles");
        this.bindings = this.root.resolve("bindings");
        this.snapshots = this.root.resolve("snapshots");
        this.transitions = this.root.resolve("transitions");
        for (final Path directory : List.of(this.accounts, this.profiles, this.bindings, this.snapshots, this.transitions)) {
            Files.createDirectories(directory);
        }
    }

    public Path root() {
        return this.root;
    }

    @Override
    public synchronized PlayerAccount loadAccount(final UUID playerId, final int defaultSlots) throws IOException {
        final Path file = this.accounts.resolve(playerId + ".awfp");
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

    @Override
    public synchronized void saveAccount(final PlayerAccount account) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            uuid(out, account.playerId());
            out.writeInt(account.slotLimit());
            out.writeBoolean(account.activeProfile() != null);
            if (account.activeProfile() != null) uuid(out, account.activeProfile());
            out.writeInt(account.profileIds().size());
            for (final UUID id : account.profileIds()) uuid(out, id);
        }
        write(this.accounts.resolve(account.playerId() + ".awfp"), TYPE_ACCOUNT, account.revision(), buffer.toByteArray());
    }

    @Override
    public synchronized Optional<AuroraProfile> profile(final UUID profileId) throws IOException {
        final Path file = this.profiles.resolve(profileId + ".awfp");
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(readProfile(file, profileId));
    }

    @Override
    public synchronized void saveProfile(final AuroraProfile profile) throws IOException {
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

    @Override
    public synchronized void saveBinding(final ProfileWorldBinding binding) throws IOException {
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
    public synchronized void saveSnapshot(final ProfileSnapshot snapshot, final int retain) throws IOException {
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
        final Path directory = this.snapshots.resolve(snapshot.profileId().toString());
        Files.createDirectories(directory);
        write(directory.resolve(snapshot.revision() + "-" + snapshot.snapshotId() + ".awfp"), TYPE_SNAPSHOT,
            snapshot.revision(), buffer.toByteArray());
        final List<Path> files = snapshotFiles(snapshot.profileId());
        final int keep = Math.max(1, retain);
        for (int i = 0; i < files.size() - keep; i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    @Override
    public synchronized Optional<ProfileTransition> transition(final UUID transitionId) throws IOException {
        final Path file = this.transitions.resolve(transitionId + ".awfp");
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(readTransition(file));
    }

    @Override
    public synchronized void saveTransition(final ProfileTransition transition) throws IOException {
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
            out.writeLong(transition.fence());
            text(out, transition.failure() == null ? "" : transition.failure());
            out.writeLong(transition.updatedAtMillis());
        }
        write(this.transitions.resolve(transition.transitionId() + ".awfp"), TYPE_TRANSITION, 1L, buffer.toByteArray());
    }

    @Override
    public synchronized void deleteTransition(final UUID transitionId) throws IOException {
        Files.deleteIfExists(this.transitions.resolve(transitionId + ".awfp"));
    }

    @Override
    public synchronized List<ProfileTransition> transitions() throws IOException {
        final List<ProfileTransition> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(this.transitions, "*.awfp")) {
            for (final Path file : files) found.add(readTransition(file));
        }
        found.sort(Comparator.comparingLong(ProfileTransition::updatedAtMillis));
        return List.copyOf(found);
    }

    @Override
    public synchronized void deleteProfile(final UUID profileId) throws IOException {
        deleteProfileData(profileId);
        Files.deleteIfExists(this.profiles.resolve(profileId + ".awfp"));
    }

    @Override
    public synchronized void deleteProfileData(final UUID profileId) throws IOException {
        Files.deleteIfExists(this.bindings.resolve(profileId + ".awfp"));
        deleteRecursively(this.snapshots.resolve(profileId.toString()));
    }

    @Override
    public void close() {
        // No open handles: every write closes its channel before the rename.
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
            final long fence = in.readLong();
            final String failure = text(in);
            final long updated = in.readLong();
            return new ProfileTransition(id, player, from, to, TransitionPhase.values()[phase], deadline, fromSnapshot,
                toSnapshot, fence, failure.isEmpty() ? null : failure, updated);
        }
    }

    private List<Path> snapshotFiles(final UUID profileId) throws IOException {
        final Path directory = this.snapshots.resolve(profileId.toString());
        if (!Files.isDirectory(directory)) return List.of();
        final List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> found = Files.newDirectoryStream(directory, "*.awfp")) {
            for (final Path file : found) files.add(file);
        }
        files.sort(Comparator.comparingLong(path -> revisionOf(path.getFileName().toString())));
        return files;
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
        // Unique per write: two nodes on a shared directory must never share a temp file.
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
        final byte[] bytes = Files.readAllBytes(file);
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
