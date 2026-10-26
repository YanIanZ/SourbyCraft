package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The record format both profile repositories store: one framed, checksummed record per account,
 * profile, binding, snapshot or journal.
 *
 * <p>A frame is magic, format, type, revision, payload length, payload and the SHA-256 of the
 * payload. {@link FileProfileRepository} writes one frame per file and {@link RedisProfileRepository}
 * stores the same bytes in a hash field, so a record copied from one backend to the other is
 * byte-identical, and its checksum is verified on every read in either backend. Decoding bounds every
 * count and length before it allocates.</p>
 *
 * <p>{@code where} names the record in error messages: a file path, or a Redis key and field.</p>
 */
final class ProfileRecords {

    static final int MAGIC = 0x41574652; // "AWFR"
    static final int FORMAT = 1;
    static final byte TYPE_ACCOUNT = 1;
    static final byte TYPE_PROFILE = 2;
    static final byte TYPE_BINDING = 3;
    static final byte TYPE_SNAPSHOT = 4;
    static final byte TYPE_TRANSITION = 5;
    static final int MAX_PAYLOAD = ProfileSnapshot.MAX_STATE_BYTES + 64 * 1024;
    /** Magic, format, type, revision, payload length and the digest around a payload. */
    static final int OVERHEAD = 4 + 1 + 1 + 8 + 4 + 32;
    /** Version of the trailer Build 50 appends to a journal (from-fence, incarnation). */
    private static final int TRANSITION_TRAILER = 1;

    private ProfileRecords() {}

    record Frame(byte type, long revision, byte[] payload) {}

    // ------------------------------------------------------------------ frames

    static byte[] frame(final byte type, final long revision, final byte[] payload, final String where) throws IOException {
        if (payload.length > MAX_PAYLOAD) throw new IOException("record " + where + " is too large: " + payload.length);
        final ByteBuffer buffer = ByteBuffer.allocate(OVERHEAD + payload.length);
        buffer.putInt(MAGIC);
        buffer.put((byte) FORMAT);
        buffer.put(type);
        buffer.putLong(revision);
        buffer.putInt(payload.length);
        buffer.put(payload);
        buffer.put(sha256(payload));
        return buffer.array();
    }

    static Frame unframe(final byte[] bytes, final byte expectedType, final String where) throws IOException {
        if (bytes.length > MAX_PAYLOAD + 64) throw new IOException("record " + where + " exceeds the size limit");
        if (bytes.length < OVERHEAD) throw new IOException("record " + where + " is truncated");
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC) throw new IOException("record " + where + " has no AWF profile magic");
        final int format = buffer.get() & 0xFF;
        if (format != FORMAT) throw new IOException("record " + where + " has format " + format);
        final byte type = buffer.get();
        if (type != expectedType) throw new IOException("record " + where + " has type " + type + ", expected " + expectedType);
        final long revision = buffer.getLong();
        final int length = buffer.getInt();
        if (length < 0 || length > MAX_PAYLOAD || buffer.remaining() != length + 32) {
            throw new IOException("record " + where + " has an invalid length");
        }
        final byte[] payload = new byte[length];
        buffer.get(payload);
        final byte[] digest = new byte[32];
        buffer.get(digest);
        if (!MessageDigest.isEqual(digest, sha256(payload))) {
            throw new IOException("record " + where + " fails its checksum");
        }
        return new Frame(type, revision, payload);
    }

    // ------------------------------------------------------------------ accounts

    static byte[] accountPayload(final PlayerAccount account) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            uuid(out, account.playerId());
            out.writeInt(account.slotLimit());
            out.writeBoolean(account.activeProfile() != null);
            if (account.activeProfile() != null) uuid(out, account.activeProfile());
            out.writeInt(account.profileIds().size());
            for (final UUID id : account.profileIds()) uuid(out, id);
        }
        return buffer.toByteArray();
    }

    static PlayerAccount account(final Frame frame, final UUID playerId, final String where) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload()))) {
            final UUID stored = uuid(in);
            final int slots = in.readInt();
            final boolean hasActive = in.readBoolean();
            final UUID active = hasActive ? uuid(in) : null;
            final int count = bounded(in.readInt(), PlayerAccount.MAX_SLOTS, "account profiles");
            final List<UUID> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) ids.add(uuid(in));
            if (!stored.equals(playerId)) throw new IOException("account record " + where + " holds " + stored);
            return new PlayerAccount(playerId, ids, active, slots, frame.revision());
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("account record " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ profiles

    static byte[] profilePayload(final AuroraProfile profile) throws IOException {
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
        return buffer.toByteArray();
    }

    static AuroraProfile profile(final Frame frame, final UUID expected, final String where) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload()))) {
            final UUID id = uuid(in);
            final UUID account = uuid(in);
            final String type = text(in);
            final long created = in.readLong();
            final long revision = in.readLong();
            final int status = in.readUnsignedByte();
            if (status >= ProfileStatus.values().length) throw new IOException("profile status " + status + " in " + where);
            final int metadata = bounded(in.readInt(), AuroraProfile.MAX_METADATA, "profile metadata");
            final Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < metadata; i++) values.put(text(in), text(in));
            if (!id.equals(expected)) throw new IOException("profile record " + where + " holds " + id);
            return new AuroraProfile(id, account, type, values, created, revision, ProfileStatus.values()[status]);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("profile record " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ bindings

    static byte[] bindingPayload(final ProfileWorldBinding binding) throws IOException {
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
        return buffer.toByteArray();
    }

    static ProfileWorldBinding binding(final Frame frame, final String where) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload()))) {
            final UUID stored = uuid(in);
            final int count = bounded(in.readInt(), ProfileWorldBinding.MAX_WORLDS, "binding worlds");
            final Map<String, ProfileWorldRole> worlds = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                final String world = text(in);
                final int role = in.readUnsignedByte();
                if (role >= ProfileWorldRole.values().length) throw new IOException("binding role " + role + " in " + where);
                worlds.put(world, ProfileWorldRole.values()[role]);
            }
            final String template = text(in);
            return new ProfileWorldBinding(stored, worlds, template.isEmpty() ? null : template);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("binding record " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ snapshots

    static byte[] snapshotPayload(final ProfileSnapshot snapshot) throws IOException {
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

    /** A snapshot of {@code profileId}; the state's own checksum is verified as well as the frame's. */
    static ProfileSnapshot snapshot(final Frame frame, final UUID profileId, final String where) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload()))) {
            final UUID storedProfile = uuid(in);
            final UUID snapshotId = uuid(in);
            final int schema = in.readInt();
            final long revision = in.readLong();
            final String checksum = text(in);
            final long createdAt = in.readLong();
            final int length = in.readInt();
            if (length < 1 || length > ProfileSnapshot.MAX_STATE_BYTES) throw new IOException("snapshot length " + length);
            final byte[] state = in.readNBytes(length);
            if (state.length != length) throw new EOFException("snapshot truncated: " + where);
            if (!storedProfile.equals(profileId)) throw new IOException("snapshot " + where + " belongs to " + storedProfile);
            final String actual = ProfileStateCodec.checksum(state);
            if (!actual.equals(checksum)) throw new IOException("snapshot checksum mismatch in " + where);
            return new ProfileSnapshot(profileId, snapshotId, schema, revision, state, checksum, createdAt);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("snapshot " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ journals

    static byte[] transitionPayload(final ProfileTransition transition) throws IOException {
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
        return buffer.toByteArray();
    }

    static ProfileTransition transition(final Frame frame, final String where) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload()))) {
            final UUID id = uuid(in);
            final UUID player = uuid(in);
            final UUID from = in.readBoolean() ? uuid(in) : null;
            final UUID to = uuid(in);
            final int phase = in.readUnsignedByte();
            if (phase >= TransitionPhase.values().length) throw new IOException("transition phase " + phase + " in " + where);
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
            throw new IOException("transition journal " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ primitives

    static byte[] sha256(final byte[] data) {
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
}
