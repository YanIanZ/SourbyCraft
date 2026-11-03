package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ClaimKind;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Domain;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileIntent.BlobRef;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileIntent.ClaimRef;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileIntent.SnapshotRef;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileSharedState.MemberKey;
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
 * profile, binding, snapshot, journal, shared state, invite, vote, salvage, member head, intent or page.
 *
 * <p>A frame is magic, format byte, type byte, revision, payload length, payload and the SHA-256 of
 * the payload. {@link FileProfileRepository} writes one frame per file and {@link RedisProfileRepository}
 * stores the same bytes in a hash field, so a record copied from one backend to the other is
 * byte-identical, and its checksum is verified on every read in either backend. Decoding bounds every
 * count and length before it allocates, per record type ({@link #maxPayload}).</p>
 *
 * <p><b>Format 1</b> ({@link StoreFormat#LEGACY}) is PR #62's layout, types 1–5, kept so a format-1
 * store can be read for offline migration and so the solo paths keep working until they are rewired.
 * Its profile reader ignores trailing bytes, as it always did; nothing is ever appended to a format-1
 * profile.</p>
 *
 * <p><b>Format 2</b> ({@link StoreFormat#CURRENT}) is the co-op layout, types 1–13. Every payload starts
 * with an unsigned-short schema version ({@link StoreFormat#SCHEMA}); a schema this build does not know
 * refuses the read (and so every write that would follow) instead of round-tripping it lossily. Numeric
 * fields are big-endian, UUIDs two longs, enums their stable numeric tags (never ordinals), optional
 * fields a presence byte, lists an int count checked against the type's bound, texts a bounded UTF-8
 * length. Duplicate map keys, unknown tags and trailing bytes are refused. The frame header's revision
 * must equal the revision repeated in the payload. The {@code *2} methods are the format-2 codecs; the
 * unsuffixed ones are format 1.</p>
 *
 * <p>{@code where} names the record in error messages: a file path, or a Redis key and field.</p>
 */
final class ProfileRecords {

    static final int MAGIC = 0x41574652; // "AWFR"
    /** The format the unsuffixed (format-1) writers emit. */
    static final int FORMAT = StoreFormat.LEGACY;
    static final int FORMAT_2 = StoreFormat.CURRENT;
    static final int SCHEMA = StoreFormat.SCHEMA;

    static final byte TYPE_ACCOUNT = 1;
    static final byte TYPE_PROFILE = 2;
    static final byte TYPE_BINDING = 3;
    /** Format 1: a profile snapshot. Format 2: the pre-profile legacy archive of a player. */
    static final byte TYPE_SNAPSHOT = 4;
    static final byte TYPE_TRANSITION = 5;
    static final byte TYPE_SHARED = 6;
    static final byte TYPE_INVITE = 7;
    static final byte TYPE_VOTE = 8;
    static final byte TYPE_SALVAGE = 9;
    static final byte TYPE_MEMBER_SNAPSHOT = 10;
    static final byte TYPE_MEMBER_HEAD = 11;
    static final byte TYPE_INTENT = 12;
    /** Index pages and receipt pages; the payload's subtype byte tells them apart. */
    static final byte TYPE_PAGE = 13;
    static final byte PAGE_INDEX = 1;
    static final byte PAGE_RECEIPTS = 2;

    /** Largest payload of any type: a snapshot plus framing allowance. */
    static final int MAX_PAYLOAD = ProfileSnapshot.MAX_STATE_BYTES + 64 * 1024;
    static final int MAX_CONTROL_PAYLOAD = 64 * 1024;
    static final int MAX_SHARED_PAYLOAD = 256 * 1024;
    /** Magic, format, type, revision, payload length and the digest around a payload. */
    static final int OVERHEAD = 4 + 1 + 1 + 8 + 4 + 32;
    /** Version of the trailer Build 50 appends to a format-1 journal (from-fence, incarnation). */
    private static final int TRANSITION_TRAILER = 1;
    private static final int MAX_TEXT = 4096;
    private static final int MAX_NAME = 128;

    private ProfileRecords() {}

    /** A decoded frame; {@code format} is 1 or 2. */
    record Frame(int format, byte type, long revision, byte[] payload) {}

    // ------------------------------------------------------------------ frames

    /** The payload bound of a record type. */
    static int maxPayload(final byte type) {
        return switch (type) {
            case TYPE_SNAPSHOT, TYPE_MEMBER_SNAPSHOT -> MAX_PAYLOAD;
            case TYPE_SHARED, TYPE_INTENT -> MAX_SHARED_PAYLOAD;
            default -> MAX_CONTROL_PAYLOAD;
        };
    }

    static String typeName(final byte type) {
        return switch (type) {
            case TYPE_ACCOUNT -> "account";
            case TYPE_PROFILE -> "profile";
            case TYPE_BINDING -> "binding";
            case TYPE_SNAPSHOT -> "snapshot";
            case TYPE_TRANSITION -> "transition";
            case TYPE_SHARED -> "shared";
            case TYPE_INVITE -> "invite";
            case TYPE_VOTE -> "vote";
            case TYPE_SALVAGE -> "salvage";
            case TYPE_MEMBER_SNAPSHOT -> "member-snapshot";
            case TYPE_MEMBER_HEAD -> "member-head";
            case TYPE_INTENT -> "intent";
            case TYPE_PAGE -> "page";
            default -> "type " + type;
        };
    }

    /** A format-1 frame. */
    static byte[] frame(final byte type, final long revision, final byte[] payload, final String where) throws IOException {
        return frame(FORMAT, type, revision, payload, where);
    }

    /** A format-2 frame. */
    static byte[] frame2(final byte type, final long revision, final byte[] payload, final String where) throws IOException {
        return frame(FORMAT_2, type, revision, payload, where);
    }

    static byte[] frame(final int format, final byte type, final long revision, final byte[] payload, final String where)
        throws IOException {
        if (format != FORMAT && format != FORMAT_2) throw new IOException("record " + where + ": unknown format " + format);
        final int limit = format == FORMAT ? MAX_PAYLOAD : maxPayload(type);
        if (payload.length > limit) {
            throw new IOException("record " + where + " (" + typeName(type) + ") is too large: " + payload.length + " > " + limit);
        }
        if (revision < 0) throw new IOException("record " + where + " has a negative revision");
        final ByteBuffer buffer = ByteBuffer.allocate(OVERHEAD + payload.length);
        buffer.putInt(MAGIC);
        buffer.put((byte) format);
        buffer.put(type);
        buffer.putLong(revision);
        buffer.putInt(payload.length);
        buffer.put(payload);
        buffer.put(sha256(payload));
        return buffer.array();
    }

    /**
     * Decodes a frame of either format; the caller checks {@link Frame#format()} against the store's
     * {@link StoreFormat}. A format this build does not know is refused.
     */
    static Frame unframe(final byte[] bytes, final byte expectedType, final String where) throws IOException {
        if (bytes.length > MAX_PAYLOAD + 64) throw new IOException("record " + where + " exceeds the size limit");
        if (bytes.length < OVERHEAD) throw new IOException("record " + where + " is truncated");
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC) throw new IOException("record " + where + " has no AWF profile magic");
        final int format = buffer.get() & 0xFF;
        if (format != FORMAT && format != FORMAT_2) {
            throw new IOException("record " + where + " has format " + format + "; this build reads formats 1 and 2 and refuses"
                + " a newer store (restore the pre-migration backup or use the build that wrote it)");
        }
        final byte type = buffer.get();
        if (type != expectedType) throw new IOException("record " + where + " has type " + type + ", expected " + expectedType);
        final long revision = buffer.getLong();
        if (revision < 0 || revision > CoopWriteGuard.MAX_COUNTER) throw new IOException("record " + where + " has revision " + revision);
        final int length = buffer.getInt();
        final int limit = format == FORMAT ? MAX_PAYLOAD : maxPayload(type);
        if (length < 0 || length > limit || buffer.remaining() != length + 32) {
            throw new IOException("record " + where + " has an invalid length");
        }
        final byte[] payload = new byte[length];
        buffer.get(payload);
        final byte[] digest = new byte[32];
        buffer.get(digest);
        if (!MessageDigest.isEqual(digest, sha256(payload))) {
            throw new IOException("record " + where + " fails its checksum");
        }
        return new Frame(format, type, revision, payload);
    }

    /** {@link #unframe} requiring exactly {@code expectedFormat}; a format-1 frame in a format-2 store (or the reverse) is refused. */
    static Frame unframe(final byte[] bytes, final int expectedFormat, final byte expectedType, final String where) throws IOException {
        final Frame frame = unframe(bytes, expectedType, where);
        if (frame.format() != expectedFormat) {
            throw new IOException("record " + where + " is format " + frame.format() + " in a format-" + expectedFormat + " store");
        }
        return frame;
    }

    // ================================================================== format 1 (PR #62; read for migration, written by solo paths)

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

    /** Format-1 profile; trailing bytes are ignored, as PR #62 did. Name, type and members take their format-1 defaults. */
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

    /** A format-1 snapshot of {@code profileId}; the state's own checksum is verified as well as the frame's. */
    static ProfileSnapshot snapshot(final Frame frame, final UUID profileId, final String where) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload()))) {
            final UUID storedProfile = uuid(in);
            final UUID snapshotId = uuid(in);
            final int schema = in.readInt();
            final long revision = in.readLong();
            final String checksum = text(in);
            final long createdAt = in.readLong();
            final byte[] state = stateBytes(in, where);
            if (!storedProfile.equals(profileId)) throw new IOException("snapshot " + where + " belongs to " + storedProfile);
            if (!ProfileStateCodec.checksum(state).equals(checksum)) throw new IOException("snapshot checksum mismatch in " + where);
            return new ProfileSnapshot(profileId, snapshotId, schema, revision, state, checksum, createdAt);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("snapshot " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

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

    // ================================================================== format 2

    // ------------------------------------------------------------------ type 1: account

    static byte[] accountPayload2(final PlayerAccount account) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            uuid(out, account.playerId());
            out.writeLong(account.revision());
            out.writeInt(account.slotLimit());
            out.writeInt(account.profileIds().size());
            for (final UUID id : account.profileIds()) {
                uuid(out, id);
                optionalText(out, account.alias(id), CuteNames.MAX_ALIAS_LENGTH);
            }
            optionalUuid(out, account.activeProfile());
            optionalUuid(out, account.activeEpoch());
            out.writeLong(account.sessionGeneration());
            optionalUuid(out, account.pendingIntent());
            out.writeInt(account.cleanups().size());
            for (final PlayerAccount.Cleanup cleanup : account.cleanups()) {
                uuid(out, cleanup.profileId());
                uuid(out, cleanup.epoch());
                uuid(out, cleanup.removalOperationId());
                out.writeLong(cleanup.categoryMask());
                uuid(out, cleanup.cleanupSnapshotId());
            }
            out.writeBoolean(account.legacyAdoptionClosed());
        }
        return buffer.toByteArray();
    }

    static PlayerAccount account2(final Frame frame, final UUID playerId, final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final UUID stored = uuid(in);
            final long revision = revision(in, frame, where);
            final int slots = in.readInt();
            final int count = bounded(in.readInt(), PlayerAccount.MAX_SLOTS, "account profiles");
            final List<UUID> ids = new ArrayList<>(count);
            final Map<UUID, String> aliases = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                final UUID id = uuid(in);
                if (ids.contains(id)) throw new IOException("account record " + where + " lists profile " + id + " twice");
                ids.add(id);
                final String alias = optionalText(in, CuteNames.MAX_ALIAS_LENGTH);
                if (alias != null) aliases.put(id, alias);
            }
            final UUID active = optionalUuid(in);
            final UUID activeEpoch = optionalUuid(in);
            final long generation = in.readLong();
            final UUID pending = optionalUuid(in);
            final int cleanupCount = bounded(in.readInt(), PlayerAccount.MAX_CLEANUPS, "account cleanups");
            final List<PlayerAccount.Cleanup> cleanups = new ArrayList<>(cleanupCount);
            for (int i = 0; i < cleanupCount; i++) {
                cleanups.add(new PlayerAccount.Cleanup(uuid(in), uuid(in), uuid(in), in.readLong(), uuid(in)));
            }
            final boolean closed = in.readBoolean();
            end(in, where);
            if (!stored.equals(playerId)) throw new IOException("account record " + where + " holds " + stored);
            return new PlayerAccount(playerId, ids, aliases, active, activeEpoch, slots, revision, generation, pending, cleanups,
                closed);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("account record " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ type 2: profile metadata

    static byte[] profilePayload2(final AuroraProfile profile) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            uuid(out, profile.id());
            out.writeLong(profile.revision());
            uuid(out, profile.accountId());
            text(out, profile.type());
            out.writeByte(ProfileType.tag(profile.profileType()));
            text(out, profile.name());
            out.writeLong(profile.createdAtMillis());
            out.writeByte(profile.status().tag());
            out.writeInt(profile.metadata().size());
            for (final Map.Entry<String, String> entry : profile.metadata().entrySet()) {
                text(out, entry.getKey());
                text(out, entry.getValue());
            }
            out.writeInt(profile.members().size());
            for (final ProfileMember member : profile.members()) {
                uuid(out, member.playerId());
                uuid(out, member.epoch());
                out.writeByte(member.role().tag());
                out.writeLong(member.joinedAtMillis());
                out.writeLong(member.lastSeenAtMillis());
            }
            out.writeLong(profile.bindingRevision());
            optionalUuid(out, profile.pendingIntent());
        }
        return buffer.toByteArray();
    }

    static AuroraProfile profile2(final Frame frame, final UUID expected, final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final UUID id = uuid(in);
            final long revision = revision(in, frame, where);
            final UUID account = uuid(in);
            final String type = text(in, 32);
            final dev.iyanz.sourbycraft.api.profile.ProfileType policy = ProfileType.fromTag(in.readUnsignedByte(), where);
            final String name = text(in, CuteNames.MAX_LENGTH);
            final long created = in.readLong();
            final ProfileStatus status = ProfileStatus.fromTag(in.readUnsignedByte(), where);
            final int metadata = bounded(in.readInt(), AuroraProfile.MAX_METADATA, "profile metadata");
            final Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < metadata; i++) {
                final String key = text(in, AuroraProfile.MAX_METADATA_KEY);
                if (values.put(key, text(in, AuroraProfile.MAX_METADATA_VALUE)) != null) {
                    throw new IOException("profile record " + where + " repeats metadata key " + key);
                }
            }
            final int memberCount = bounded(in.readInt(), AuroraProfile.MAX_MEMBERS, "profile members");
            final List<ProfileMember> members = new ArrayList<>(memberCount);
            for (int i = 0; i < memberCount; i++) {
                members.add(new ProfileMember(uuid(in), uuid(in), ProfileRole.fromTag(in.readUnsignedByte(), where), in.readLong(),
                    in.readLong()));
            }
            final long bindingRevision = in.readLong();
            final UUID pending = optionalUuid(in);
            end(in, where);
            if (!id.equals(expected)) throw new IOException("profile record " + where + " holds " + id);
            return new AuroraProfile(id, account, type, policy, name, values, created, revision, status, members, bindingRevision,
                pending);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("profile record " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ type 3: binding

    /** A format-2 binding with its own revision and reservation (the binding value type carries neither). */
    record Binding2(ProfileWorldBinding binding, long revision, UUID pendingIntent) {}

    static byte[] bindingPayload2(final ProfileWorldBinding binding, final long revision, final UUID pendingIntent)
        throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            uuid(out, binding.profileId());
            out.writeLong(CoopWriteGuard.counter(revision, "binding revision"));
            out.writeInt(binding.worlds().size());
            for (final Map.Entry<String, ProfileWorldRole> entry : binding.worlds().entrySet()) {
                text(out, entry.getKey());
                // Role names are stable identifiers of the API enum (not ordinals).
                text(out, entry.getValue().name());
            }
            optionalText(out, binding.template(), 48);
            optionalUuid(out, pendingIntent);
        }
        return buffer.toByteArray();
    }

    static Binding2 binding2(final Frame frame, final UUID expected, final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final UUID stored = uuid(in);
            final long revision = revision(in, frame, where);
            final int count = bounded(in.readInt(), ProfileWorldBinding.MAX_WORLDS, "binding worlds");
            final Map<String, ProfileWorldRole> worlds = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                final String world = text(in, 48);
                final String role = text(in, 32);
                final ProfileWorldRole parsed;
                try {
                    parsed = ProfileWorldRole.valueOf(role);
                } catch (final IllegalArgumentException unknown) {
                    throw new IOException("binding role " + role + " in " + where + " is not known to this build");
                }
                if (worlds.put(world, parsed) != null) throw new IOException("binding " + where + " repeats world " + world);
            }
            final String template = optionalText(in, 48);
            final UUID pending = optionalUuid(in);
            end(in, where);
            if (!stored.equals(expected)) throw new IOException("binding record " + where + " holds " + stored);
            return new Binding2(new ProfileWorldBinding(stored, worlds, template), revision, pending);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("binding record " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ type 4: legacy archive, type 10: member snapshot

    /** Format-2 pre-profile archive of a player (the snapshot's profile id is the player id). */
    static byte[] legacyArchivePayload2(final ProfileSnapshot snapshot) throws IOException {
        if (snapshot.member()) throw new IOException("a legacy archive carries no member");
        return snapshotPayload2(snapshot);
    }

    static ProfileSnapshot legacyArchive2(final Frame frame, final UUID playerId, final String where) throws IOException {
        final ProfileSnapshot snapshot = snapshot2(frame, where);
        if (snapshot.member()) throw new IOException("legacy archive " + where + " names a member");
        if (!snapshot.profileId().equals(playerId)) throw new IOException("legacy archive " + where + " belongs to " + snapshot.profileId());
        return snapshot;
    }

    /** Format-2 member snapshot ({@code (profile, member, epoch)} required). */
    static byte[] memberSnapshotPayload2(final ProfileSnapshot snapshot) throws IOException {
        if (!snapshot.member()) throw new IOException("a member snapshot names member and epoch");
        return snapshotPayload2(snapshot);
    }

    /** A member snapshot of exactly the stream {@code (profileId, memberId, epoch)}; both checksums verified. */
    static ProfileSnapshot memberSnapshot2(final Frame frame, final UUID profileId, final UUID memberId, final UUID epoch,
                                           final String where) throws IOException {
        final ProfileSnapshot snapshot = snapshot2(frame, where);
        if (!snapshot.inStream(profileId, memberId, epoch)) {
            throw new IOException("member snapshot " + where + " belongs to stream " + snapshot.profileId() + "/"
                + snapshot.memberId() + "/" + snapshot.membershipEpoch());
        }
        return snapshot;
    }

    private static byte[] snapshotPayload2(final ProfileSnapshot snapshot) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(snapshot.size() + 192);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            uuid(out, snapshot.profileId());
            out.writeLong(snapshot.revision());
            optionalUuid(out, snapshot.memberId());
            optionalUuid(out, snapshot.membershipEpoch());
            uuid(out, snapshot.snapshotId());
            out.writeInt(snapshot.schemaVersion());
            out.writeLong(snapshot.createdAtMillis());
            text(out, snapshot.checksum());
            optionalUuid(out, snapshot.operationId());
            final byte[] state = snapshot.state();
            out.writeInt(state.length);
            out.write(state);
        }
        return buffer.toByteArray();
    }

    private static ProfileSnapshot snapshot2(final Frame frame, final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final UUID profile = uuid(in);
            final long revision = revision(in, frame, where);
            final UUID member = optionalUuid(in);
            final UUID epoch = optionalUuid(in);
            final UUID snapshotId = uuid(in);
            final int schema = in.readInt();
            final long createdAt = in.readLong();
            final String checksum = text(in, 64);
            final UUID operation = optionalUuid(in);
            final byte[] state = stateBytes(in, where);
            end(in, where);
            if (!ProfileStateCodec.checksum(state).equals(checksum)) throw new IOException("snapshot checksum mismatch in " + where);
            return new ProfileSnapshot(profile, member, epoch, snapshotId, schema, revision, state, checksum, createdAt, operation);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("snapshot " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ type 5: switch journal

    static byte[] transitionPayload2(final ProfileTransition transition) throws IOException {
        final ProfileTransition.Coop coop = transition.coop();
        if (coop == null) throw new IOException("a format-2 journal requires the co-op fields");
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            uuid(out, transition.transitionId());
            uuid(out, transition.playerId());
            optionalUuid(out, transition.fromProfile());
            uuid(out, transition.toProfile());
            out.writeByte(transition.phase().tag());
            out.writeLong(transition.deadlineMillis());
            optionalUuid(out, transition.fromSnapshotId());
            optionalUuid(out, transition.toSnapshotId());
            out.writeLong(transition.fromFence());
            out.writeLong(transition.toFence());
            optionalText(out, transition.incarnation(), ProfileTransition.MAX_INCARNATION_CHARS);
            optionalText(out, transition.failure(), ProfileTransition.MAX_FAILURE_CHARS);
            out.writeLong(transition.updatedAtMillis());
            uuid(out, coop.operationId());
            out.write(coop.requestDigest());
            out.writeLong(coop.intentRevision());
            out.writeLong(coop.playerFence());
            text(out, coop.playerIncarnation());
            out.writeLong(coop.sessionGeneration());
            optionalUuid(out, coop.fromEpoch());
            uuid(out, coop.toEpoch());
            out.writeLong(coop.expectedAccount());
            out.writeLong(coop.expectedFromMetadata());
            out.writeLong(coop.expectedToMetadata());
            out.writeLong(coop.expectedFromHead());
            out.writeLong(coop.expectedToHead());
            out.writeLong(coop.committedAccount());
            optionalText(out, coop.outcome(), ProfileTransition.Coop.MAX_OUTCOME_CHARS);
        }
        return buffer.toByteArray();
    }

    static ProfileTransition transition2(final Frame frame, final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final UUID id = uuid(in);
            final UUID player = uuid(in);
            final UUID from = optionalUuid(in);
            final UUID to = uuid(in);
            final TransitionPhase phase = TransitionPhase.fromTag(in.readUnsignedByte(), where);
            final long deadline = in.readLong();
            final UUID fromSnapshot = optionalUuid(in);
            final UUID toSnapshot = optionalUuid(in);
            final long fromFence = in.readLong();
            final long toFence = in.readLong();
            final String incarnation = optionalText(in, ProfileTransition.MAX_INCARNATION_CHARS);
            final String failure = optionalText(in, ProfileTransition.MAX_FAILURE_CHARS);
            final long updated = in.readLong();
            final UUID operation = uuid(in);
            final byte[] digest = bytes(in, CoopOperation.DIGEST_BYTES, where);
            final long intentRevision = in.readLong();
            final long playerFence = in.readLong();
            final String playerIncarnation = text(in, ProfileTransition.MAX_INCARNATION_CHARS);
            final long generation = in.readLong();
            final UUID fromEpoch = optionalUuid(in);
            final UUID toEpoch = uuid(in);
            final ProfileTransition.Coop coop = new ProfileTransition.Coop(operation, digest, intentRevision, playerFence,
                playerIncarnation, generation, fromEpoch, toEpoch, in.readLong(), in.readLong(), in.readLong(), in.readLong(),
                in.readLong(), in.readLong(), optionalText(in, ProfileTransition.Coop.MAX_OUTCOME_CHARS));
            end(in, where);
            return new ProfileTransition(id, player, from, to, phase, deadline, fromSnapshot, toSnapshot, fromFence, toFence,
                incarnation, failure, updated, coop);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("transition journal " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ type 6: shared state

    static byte[] sharedPayload2(final ProfileSharedState shared) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            uuid(out, shared.profileId());
            out.writeLong(shared.revision());
            text(out, shared.currency().providerId());
            text(out, shared.currency().currencyId());
            out.writeByte(shared.currency().scale());
            out.writeLong(shared.sharedBalance());
            out.writeLong(shared.sharedCapacity());
            out.writeInt(shared.personalBalances().size());
            for (final Map.Entry<MemberKey, Long> entry : shared.personalBalances().entrySet()) {
                memberKey(out, entry.getKey());
                out.writeLong(entry.getValue());
            }
            out.writeInt(shared.collections().size());
            for (final Map.Entry<String, Long> entry : shared.collections().entrySet()) {
                text(out, entry.getKey());
                out.writeLong(entry.getValue());
            }
            out.writeInt(shared.contributions().size());
            for (final Map.Entry<String, Map<MemberKey, Long>> entry : shared.contributions().entrySet()) {
                text(out, entry.getKey());
                out.writeInt(entry.getValue().size());
                for (final Map.Entry<MemberKey, Long> contribution : entry.getValue().entrySet()) {
                    memberKey(out, contribution.getKey());
                    out.writeLong(contribution.getValue());
                }
            }
            out.writeInt(shared.reserved().size());
            for (final Map.Entry<UUID, Long> entry : shared.reserved().entrySet()) {
                uuid(out, entry.getKey());
                out.writeLong(entry.getValue());
            }
            optionalUuid(out, shared.pendingIntent());
        }
        return buffer.toByteArray();
    }

    static ProfileSharedState shared2(final Frame frame, final UUID expected, final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final UUID profile = uuid(in);
            final long revision = revision(in, frame, where);
            final ProfileSharedState.Currency currency = new ProfileSharedState.Currency(text(in, 64), text(in, 64),
                in.readUnsignedByte());
            final long sharedBalance = in.readLong();
            final long capacity = in.readLong();
            final int balances = bounded(in.readInt(), AuroraProfile.MAX_MEMBERS, "personal balances");
            final Map<MemberKey, Long> personal = new LinkedHashMap<>();
            for (int i = 0; i < balances; i++) {
                final MemberKey key = memberKey(in);
                if (personal.put(key, in.readLong()) != null) throw new IOException("shared record " + where + " repeats member " + key);
            }
            final int collectionCount = bounded(in.readInt(), ProfileSharedState.MAX_COLLECTIONS, "collections");
            final Map<String, Long> collections = new LinkedHashMap<>();
            for (int i = 0; i < collectionCount; i++) {
                final String collection = text(in, ProfileSharedState.MAX_COLLECTION_ID);
                if (collections.put(collection, in.readLong()) != null) {
                    throw new IOException("shared record " + where + " repeats collection " + collection);
                }
            }
            final int ledgerCount = bounded(in.readInt(), ProfileSharedState.MAX_COLLECTIONS, "contribution ledgers");
            final Map<String, Map<MemberKey, Long>> contributions = new LinkedHashMap<>();
            for (int i = 0; i < ledgerCount; i++) {
                final String collection = text(in, ProfileSharedState.MAX_COLLECTION_ID);
                final int contributors = bounded(in.readInt(), AuroraProfile.MAX_MEMBERS, "contributors");
                final Map<MemberKey, Long> perMember = new LinkedHashMap<>();
                for (int j = 0; j < contributors; j++) {
                    final MemberKey key = memberKey(in);
                    if (perMember.put(key, in.readLong()) != null) throw new IOException("shared record " + where + " repeats contributor " + key);
                }
                if (contributions.put(collection, perMember) != null) {
                    throw new IOException("shared record " + where + " repeats ledger " + collection);
                }
            }
            final int reservations = bounded(in.readInt(), ProfileSharedState.MAX_RESERVATIONS, "reservations");
            final Map<UUID, Long> reserved = new LinkedHashMap<>();
            for (int i = 0; i < reservations; i++) {
                final UUID operation = uuid(in);
                if (reserved.put(operation, in.readLong()) != null) throw new IOException("shared record " + where + " repeats reservation " + operation);
            }
            final UUID pending = optionalUuid(in);
            end(in, where);
            if (!profile.equals(expected)) throw new IOException("shared record " + where + " holds " + profile);
            return new ProfileSharedState(profile, revision, currency, sharedBalance, capacity, personal, collections, contributions,
                reserved, pending);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("shared record " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ type 7: invite

    static byte[] invitePayload2(final ProfileInvite invite) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(192);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            uuid(out, invite.id());
            out.writeLong(invite.revision());
            uuid(out, invite.profileId());
            uuid(out, invite.inviter());
            uuid(out, invite.inviterEpoch());
            uuid(out, invite.invitee());
            out.writeLong(invite.createdAtMillis());
            out.writeLong(invite.expiresAtMillis());
            out.writeByte(invite.status().tag());
            optionalUuid(out, invite.acceptedEpoch());
            uuid(out, invite.operationId());
        }
        return buffer.toByteArray();
    }

    static ProfileInvite invite2(final Frame frame, final UUID expected, final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final UUID id = uuid(in);
            final long revision = revision(in, frame, where);
            final ProfileInvite invite = new ProfileInvite(id, revision, uuid(in), uuid(in), uuid(in), uuid(in), in.readLong(),
                in.readLong(), ProfileInvite.Status.fromTag(in.readUnsignedByte(), where), optionalUuid(in), uuid(in));
            end(in, where);
            if (!id.equals(expected)) throw new IOException("invite record " + where + " holds " + id);
            return invite;
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("invite record " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ type 8: kick vote

    static byte[] votePayload2(final KickVote vote) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            uuid(out, vote.id());
            out.writeLong(vote.revision());
            uuid(out, vote.profileId());
            uuid(out, vote.target());
            uuid(out, vote.targetEpoch());
            out.writeInt(vote.electorate().size());
            for (final Map.Entry<UUID, UUID> voter : vote.electorate().entrySet()) {
                uuid(out, voter.getKey());
                uuid(out, voter.getValue());
            }
            out.writeInt(vote.approvals().size());
            for (final Map.Entry<UUID, Long> approval : vote.approvals().entrySet()) {
                uuid(out, approval.getKey());
                out.writeLong(approval.getValue());
            }
            out.writeLong(vote.createdAtMillis());
            out.writeLong(vote.expiresAtMillis());
            out.writeByte(vote.status().tag());
            optionalUuid(out, vote.removalIntentId());
        }
        return buffer.toByteArray();
    }

    static KickVote vote2(final Frame frame, final UUID expected, final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final UUID id = uuid(in);
            final long revision = revision(in, frame, where);
            final UUID profile = uuid(in);
            final UUID target = uuid(in);
            final UUID targetEpoch = uuid(in);
            final int voters = bounded(in.readInt(), KickVote.MAX_ELECTORATE, "vote electorate");
            final Map<UUID, UUID> electorate = new LinkedHashMap<>();
            for (int i = 0; i < voters; i++) {
                final UUID voter = uuid(in);
                if (electorate.put(voter, uuid(in)) != null) throw new IOException("vote record " + where + " repeats voter " + voter);
            }
            final int approvalCount = bounded(in.readInt(), KickVote.MAX_ELECTORATE, "vote approvals");
            final Map<UUID, Long> approvals = new LinkedHashMap<>();
            for (int i = 0; i < approvalCount; i++) {
                final UUID voter = uuid(in);
                if (approvals.put(voter, in.readLong()) != null) throw new IOException("vote record " + where + " repeats approval " + voter);
            }
            final KickVote vote = new KickVote(id, revision, profile, target, targetEpoch, electorate, approvals, in.readLong(),
                in.readLong(), KickVote.Status.fromTag(in.readUnsignedByte(), where), optionalUuid(in));
            end(in, where);
            if (!id.equals(expected)) throw new IOException("vote record " + where + " holds " + id);
            return vote;
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("vote record " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ type 9: salvage

    static byte[] salvagePayload2(final ProfileSalvage salvage) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(384);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            uuid(out, salvage.id());
            out.writeLong(salvage.revision());
            uuid(out, salvage.profileId());
            uuid(out, salvage.removedMember());
            uuid(out, salvage.removedEpoch());
            uuid(out, salvage.removalIntentId());
            uuid(out, salvage.sourceSnapshotId());
            out.writeLong(salvage.sourceRevision());
            text(out, salvage.sourceChecksum());
            out.writeInt(salvage.extractionSchema());
            out.writeInt(salvage.payloadLength());
            text(out, salvage.payloadChecksum());
            uuid(out, salvage.payloadBlobId());
            out.writeByte(salvage.state().tag());
            optionalUuid(out, salvage.claimant());
            optionalUuid(out, salvage.claimantEpoch());
            optionalUuid(out, salvage.destinationSnapshotId());
            optionalUuid(out, salvage.deliveryOperationId());
            out.writeLong(salvage.createdAtMillis());
        }
        return buffer.toByteArray();
    }

    static ProfileSalvage salvage2(final Frame frame, final UUID expected, final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final UUID id = uuid(in);
            final long revision = revision(in, frame, where);
            final ProfileSalvage salvage = new ProfileSalvage(id, revision, uuid(in), uuid(in), uuid(in), uuid(in), uuid(in),
                in.readLong(), text(in, 64), in.readInt(), in.readInt(), text(in, 64), uuid(in),
                ProfileSalvage.State.fromTag(in.readUnsignedByte(), where), optionalUuid(in), optionalUuid(in), optionalUuid(in),
                optionalUuid(in), in.readLong());
            end(in, where);
            if (!id.equals(expected)) throw new IOException("salvage record " + where + " holds " + id);
            return salvage;
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("salvage record " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ type 11: member head

    static byte[] memberHeadPayload2(final MemberStateHead head) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(192);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            uuid(out, head.profileId());
            out.writeLong(head.revision());
            uuid(out, head.memberId());
            uuid(out, head.epoch());
            out.writeByte(head.state().tag());
            optionalUuid(out, head.snapshotId());
            optionalText(out, head.snapshotChecksum(), 64);
            optionalUuid(out, head.consumingOperationId());
            optionalUuid(out, head.pendingIntent());
        }
        return buffer.toByteArray();
    }

    static MemberStateHead memberHead2(final Frame frame, final UUID profileId, final UUID memberId, final UUID epoch,
                                       final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final UUID profile = uuid(in);
            final long revision = revision(in, frame, where);
            final MemberStateHead head = new MemberStateHead(profile, uuid(in), uuid(in), revision,
                MemberStateHead.State.fromTag(in.readUnsignedByte(), where), optionalUuid(in), optionalText(in, 64),
                optionalUuid(in), optionalUuid(in));
            end(in, where);
            if (!head.profileId().equals(profileId) || !head.memberId().equals(memberId) || !head.epoch().equals(epoch)) {
                throw new IOException("member head " + where + " belongs to stream " + head.describe());
            }
            return head;
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("member head " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    // ------------------------------------------------------------------ type 12: intent

    static byte[] intentPayload2(final ProfileIntent intent) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(2048);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            uuid(out, intent.operationId());
            out.writeLong(intent.revision());
            out.writeInt(intent.kind().tag());
            uuid(out, intent.actor());
            out.write(intent.requestDigest());
            expectedList(out, intent.expectedBefore());
            expectedList(out, intent.expectedAfter());
            out.writeBoolean(intent.profileClaim() != null);
            if (intent.profileClaim() != null) claimRef(out, intent.profileClaim());
            out.writeInt(intent.playerClaims().size());
            for (final ClaimRef claim : intent.playerClaims()) claimRef(out, claim);
            out.writeByte(intent.phase().tag());
            blobList(out, intent.beforeImages());
            blobList(out, intent.afterImages());
            out.writeInt(intent.pins().size());
            for (final SnapshotRef pin : intent.pins()) {
                uuid(out, pin.profileId());
                optionalUuid(out, pin.member());
                optionalUuid(out, pin.epoch());
                uuid(out, pin.snapshotId());
            }
            out.writeLong(intent.createdAtMillis());
            out.writeLong(intent.deadlineMillis());
            out.writeLong(intent.updatedAtMillis());
            optionalText(out, intent.outcome(), ProfileIntent.MAX_OUTCOME_CHARS);
        }
        final byte[] payload = buffer.toByteArray();
        if (payload.length > ProfileIntent.MAX_BYTES) throw new IOException("intent " + intent.operationId() + " exceeds " + ProfileIntent.MAX_BYTES + " bytes");
        return payload;
    }

    static ProfileIntent intent2(final Frame frame, final UUID expected, final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final UUID id = uuid(in);
            final long revision = revision(in, frame, where);
            final CoopOperation.Kind kind = CoopOperation.Kind.fromTag(in.readInt(), where);
            final UUID actor = uuid(in);
            final byte[] digest = bytes(in, CoopOperation.DIGEST_BYTES, where);
            final List<Expected> before = expectedList(in, where);
            final List<Expected> after = expectedList(in, where);
            final ClaimRef profileClaim = in.readBoolean() ? claimRef(in, where) : null;
            final int claimCount = bounded(in.readInt(), ProfileIntent.MAX_CLAIMS, "intent player claims");
            final List<ClaimRef> players = new ArrayList<>(claimCount);
            for (int i = 0; i < claimCount; i++) players.add(claimRef(in, where));
            final ProfileIntent.Phase phase = ProfileIntent.Phase.fromTag(in.readUnsignedByte(), where);
            final List<BlobRef> beforeImages = blobList(in, where);
            final List<BlobRef> afterImages = blobList(in, where);
            final int pinCount = bounded(in.readInt(), ProfileIntent.MAX_REFS, "intent pins");
            final List<SnapshotRef> pins = new ArrayList<>(pinCount);
            for (int i = 0; i < pinCount; i++) pins.add(new SnapshotRef(uuid(in), optionalUuid(in), optionalUuid(in), uuid(in)));
            final long created = in.readLong();
            final long deadline = in.readLong();
            final long updated = in.readLong();
            final String outcome = optionalText(in, ProfileIntent.MAX_OUTCOME_CHARS);
            end(in, where);
            if (!id.equals(expected)) throw new IOException("intent record " + where + " holds " + id);
            return new ProfileIntent(id, revision, kind, actor, digest, before, after, profileClaim, players, phase, beforeImages,
                afterImages, pins, created, deadline, updated, outcome);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("intent record " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    private static void expectedList(final DataOutputStream out, final List<Expected> list) throws IOException {
        out.writeInt(list.size());
        for (final Expected entry : list) {
            out.writeByte(entry.domain().tag());
            uuid(out, entry.resource());
            optionalUuid(out, entry.member());
            optionalUuid(out, entry.epoch());
            out.writeLong(entry.revision());
        }
    }

    private static List<Expected> expectedList(final DataInputStream in, final String where) throws IOException {
        final int count = bounded(in.readInt(), CoopWriteGuard.MAX_VECTOR, "intent version vector");
        final List<Expected> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final Domain domain = Domain.fromTag(in.readUnsignedByte(), where);
            final UUID resource = uuid(in);
            final UUID member = optionalUuid(in);
            final UUID epoch = optionalUuid(in);
            final long revision = in.readLong();
            if (revision != CoopWriteGuard.ABSENT && (revision < 0 || revision > CoopWriteGuard.MAX_COUNTER)) {
                throw new IOException("intent " + where + " expects revision " + revision);
            }
            list.add(new Expected(domain, resource, member, epoch, revision));
        }
        return list;
    }

    private static void claimRef(final DataOutputStream out, final ClaimRef claim) throws IOException {
        out.writeByte(claim.kind() == ClaimKind.PROFILE ? 1 : 2);
        uuid(out, claim.id());
        text(out, claim.node());
        text(out, claim.incarnation());
        uuid(out, claim.ownerToken());
        out.writeLong(claim.fence());
    }

    private static ClaimRef claimRef(final DataInputStream in, final String where) throws IOException {
        final int tag = in.readUnsignedByte();
        final ClaimKind kind = switch (tag) {
            case 1 -> ClaimKind.PROFILE;
            case 2 -> ClaimKind.PLAYER;
            default -> throw new IOException("claim kind tag " + tag + " in " + where + " is not known to this build");
        };
        return new ClaimRef(kind, uuid(in), text(in, MAX_NAME), text(in, MAX_NAME), uuid(in), in.readLong());
    }

    private static void blobList(final DataOutputStream out, final List<BlobRef> list) throws IOException {
        out.writeInt(list.size());
        for (final BlobRef blob : list) {
            out.writeByte(blob.recordType());
            uuid(out, blob.id());
            optionalUuid(out, blob.member());
            optionalUuid(out, blob.epoch());
            out.writeLong(blob.revision());
            out.writeInt(blob.length());
            out.write(blob.sha256());
        }
    }

    private static List<BlobRef> blobList(final DataInputStream in, final String where) throws IOException {
        final int count = bounded(in.readInt(), ProfileIntent.MAX_REFS, "intent blob references");
        final List<BlobRef> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new BlobRef(in.readByte(), uuid(in), optionalUuid(in), optionalUuid(in), in.readLong(), in.readInt(),
                bytes(in, 32, where)));
        }
        return list;
    }

    // ------------------------------------------------------------------ type 13: index pages and receipt pages

    static byte[] indexPagePayload2(final ProfileIndexPage page) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 + page.entries().size() * 32);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            out.writeByte(PAGE_INDEX);
            out.writeInt(page.kind().tag());
            uuid(out, page.resource());
            optionalUuid(out, page.member());
            optionalUuid(out, page.epoch());
            out.writeLong(page.generation());
            out.writeInt(page.page());
            out.writeLong(page.revision());
            out.writeInt(page.entries().size());
            for (final ProfileIndexPage.Entry entry : page.entries()) {
                out.writeLong(entry.createdAtMillis());
                uuid(out, entry.id());
                out.writeLong(entry.count());
            }
        }
        final byte[] payload = buffer.toByteArray();
        if (payload.length > ProfileIndexPage.MAX_BYTES) throw new IOException("index page exceeds " + ProfileIndexPage.MAX_BYTES + " bytes");
        return payload;
    }

    static ProfileIndexPage indexPage2(final Frame frame, final ProfileIndexPage.Kind kind, final UUID resource, final String where)
        throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final int subtype = in.readUnsignedByte();
            if (subtype != PAGE_INDEX) throw new IOException("page " + where + " is subtype " + subtype + ", not an index page");
            final ProfileIndexPage.Kind storedKind = ProfileIndexPage.Kind.fromTag(in.readInt(), where);
            final UUID storedResource = uuid(in);
            final UUID member = optionalUuid(in);
            final UUID epoch = optionalUuid(in);
            final long generation = in.readLong();
            final int pageNumber = in.readInt();
            final long revision = in.readLong();
            if (revision != frame.revision()) throw new IOException("index page " + where + " header revision " + frame.revision() + " != payload " + revision);
            final int count = bounded(in.readInt(), ProfileIndexPage.MAX_ENTRIES, "index page entries");
            final List<ProfileIndexPage.Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) entries.add(new ProfileIndexPage.Entry(in.readLong(), uuid(in), in.readLong()));
            end(in, where);
            if (storedKind != kind || !storedResource.equals(resource)) {
                throw new IOException("index page " + where + " is " + storedKind + " of " + storedResource + ", expected " + kind + " of " + resource);
            }
            return new ProfileIndexPage(kind, resource, member, epoch, generation, pageNumber, revision, entries);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("index page " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    static byte[] receiptPagePayload2(final ProfileReceipt.Page page) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 + page.receipts().size() * 96);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(SCHEMA);
            out.writeByte(PAGE_RECEIPTS);
            uuid(out, page.resource());
            out.writeInt(page.page());
            out.writeLong(page.revision());
            out.writeInt(page.receipts().size());
            for (final ProfileReceipt receipt : page.receipts()) {
                uuid(out, receipt.operationId());
                out.write(receipt.requestDigest());
                out.writeInt(receipt.kind().tag());
                out.writeByte(receipt.outcome().tag());
                final byte[] result = receipt.result();
                out.writeInt(result.length);
                out.write(result);
                out.writeLong(receipt.decidedAtMillis());
            }
        }
        return buffer.toByteArray();
    }

    static ProfileReceipt.Page receiptPage2(final Frame frame, final UUID resource, final String where) throws IOException {
        require2(frame, where);
        try (DataInputStream in = open(frame, where)) {
            final int subtype = in.readUnsignedByte();
            if (subtype != PAGE_RECEIPTS) throw new IOException("page " + where + " is subtype " + subtype + ", not a receipt page");
            final UUID storedResource = uuid(in);
            final int pageNumber = in.readInt();
            final long revision = in.readLong();
            if (revision != frame.revision()) throw new IOException("receipt page " + where + " header revision " + frame.revision() + " != payload " + revision);
            final int count = bounded(in.readInt(), ProfileReceipt.Page.MAX_PER_PAGE, "receipt page entries");
            final List<ProfileReceipt> receipts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final UUID operation = uuid(in);
                final byte[] digest = bytes(in, CoopOperation.DIGEST_BYTES, where);
                final CoopOperation.Kind kind = CoopOperation.Kind.fromTag(in.readInt(), where);
                final ProfileReceipt.Outcome outcome = ProfileReceipt.Outcome.fromTag(in.readUnsignedByte(), where);
                final int length = in.readInt();
                if (length < 0 || length > ProfileReceipt.MAX_RESULT_BYTES) throw new IOException("receipt result length " + length + " in " + where);
                final byte[] result = bytes(in, length, where);
                receipts.add(new ProfileReceipt(operation, digest, kind, outcome, result, in.readLong()));
            }
            end(in, where);
            if (!storedResource.equals(resource)) throw new IOException("receipt page " + where + " belongs to " + storedResource);
            return new ProfileReceipt.Page(resource, pageNumber, revision, receipts);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("receipt page " + where + " is invalid: " + invalid.getMessage(), invalid);
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

    private static void require2(final Frame frame, final String where) throws IOException {
        if (frame.format() != FORMAT_2) throw new IOException("record " + where + " is format " + frame.format() + ", a format-2 codec was asked");
    }

    /** Opens a format-2 payload and checks its schema; a newer schema is refused, not skipped. */
    private static DataInputStream open(final Frame frame, final String where) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload()));
        final int schema = in.readUnsignedShort();
        if (schema != SCHEMA) {
            throw new IOException("record " + where + " uses payload schema " + schema + "; this build reads schema " + SCHEMA
                + " and refuses a newer one rather than round-trip it lossily");
        }
        return in;
    }

    /** Reads the payload's revision and checks it against the frame header. */
    private static long revision(final DataInputStream in, final Frame frame, final String where) throws IOException {
        final long revision = in.readLong();
        if (revision != frame.revision()) {
            throw new IOException("record " + where + " header revision " + frame.revision() + " != payload revision " + revision);
        }
        return revision;
    }

    private static void end(final DataInputStream in, final String where) throws IOException {
        if (in.read() != -1) throw new IOException("record " + where + " has trailing bytes");
    }

    private static byte[] stateBytes(final DataInputStream in, final String where) throws IOException {
        final int length = in.readInt();
        if (length < 1 || length > ProfileSnapshot.MAX_STATE_BYTES) throw new IOException("snapshot length " + length + " in " + where);
        return bytes(in, length, where);
    }

    private static byte[] bytes(final DataInputStream in, final int length, final String where) throws IOException {
        final byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("record " + where + " is truncated");
        return bytes;
    }

    private static void uuid(final DataOutputStream out, final UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID uuid(final DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void optionalUuid(final DataOutputStream out, final UUID value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) uuid(out, value);
    }

    private static UUID optionalUuid(final DataInputStream in) throws IOException {
        final int present = in.readUnsignedByte();
        if (present == 0) return null;
        if (present != 1) throw new IOException("presence byte " + present);
        return uuid(in);
    }

    private static void memberKey(final DataOutputStream out, final MemberKey key) throws IOException {
        uuid(out, key.playerId());
        uuid(out, key.epoch());
    }

    private static MemberKey memberKey(final DataInputStream in) throws IOException {
        return new MemberKey(uuid(in), uuid(in));
    }

    private static void text(final DataOutputStream out, final String text) throws IOException {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) throw new IOException("text of " + bytes.length + " bytes exceeds " + MAX_TEXT);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(final DataInputStream in) throws IOException {
        return text(in, MAX_TEXT);
    }

    /** A text of at most {@code maxChars} characters (UTF-8 length bounded by four bytes per character). */
    private static String text(final DataInputStream in, final int maxChars) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > Math.min(MAX_TEXT, maxChars * 4)) throw new IOException("text length " + length);
        final byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("text truncated");
        final String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() > maxChars) throw new IOException("text of " + text.length() + " characters exceeds " + maxChars);
        return text;
    }

    private static void optionalText(final DataOutputStream out, final String text, final int maxChars) throws IOException {
        out.writeBoolean(text != null);
        if (text == null) return;
        if (text.length() > maxChars) throw new IOException("text of " + text.length() + " characters exceeds " + maxChars);
        text(out, text);
    }

    private static String optionalText(final DataInputStream in, final int maxChars) throws IOException {
        final int present = in.readUnsignedByte();
        if (present == 0) return null;
        if (present != 1) throw new IOException("presence byte " + present);
        return text(in, maxChars);
    }

    private static int bounded(final int value, final int max, final String what) throws IOException {
        if (value < 0 || value > max) throw new IOException(what + " count " + value);
        return value;
    }
}
