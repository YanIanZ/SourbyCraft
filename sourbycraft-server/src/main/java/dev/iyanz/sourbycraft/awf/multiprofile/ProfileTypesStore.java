package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileNaming;
import dev.iyanz.sourbycraft.api.profile.ProfileRule;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeDefinition;
import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Durable copies of plugin-registered profile type definitions ({@link ProfileTypes}).
 *
 * <p>One record per type id: magic {@code AWPT}, version 1, the owner plugin name, the definition, and a CRC-32 of
 * everything before it. A record is at most {@value #MAX_RECORD_BYTES} bytes. FILE: {@code <id>.awpt} in the
 * {@code types/} directory below the profile store, written with {@link FileProfileIo#writeDurable} (temporary file,
 * atomic rename, forced directory). Redis: one field per id of a hash (one {@code HSET}, atomic per field). Neither is
 * read by builds before 54 nor counted by the store's migration, backup verification or minimum build.</p>
 *
 * <p>Reads are bounded: at most {@code limit} records, each bounded by its size before it is decoded. A record that
 * is unreadable, fails its checksum or does not decode is skipped with a WARN, so its type becomes unknown (denied
 * under the default {@code types.unknown-type-policy}) rather than silently allowed.</p>
 */
public abstract class ProfileTypesStore {

    /** Largest encoded definition. */
    public static final int MAX_RECORD_BYTES = 256 * 1024;
    private static final int MAGIC = 0x41575054; // "AWPT"
    private static final int VERSION = 1;
    private static final String SUFFIX = ".awpt";

    /** A stored definition and the plugin name that registered it. */
    /**
     * One stored record. A record the store holds but this build cannot read is returned as a tombstone ({@code definition
     * == null}, {@code unreadable} the reason; review P2-3): the registry then denies the type instead of forgetting that
     * a definition (possibly a built-in override with denies) existed.
     */
    public record Stored(ProfileTypeDefinition definition, String owner, String unreadable) {

        public Stored(final ProfileTypeDefinition definition, final String owner) {
            this(definition, owner, null);
        }

        public static Stored unreadable(final String reason) {
            return new Stored(null, null, reason == null ? "unreadable" : reason);
        }
    }

    /** Every stored definition by id, at most {@code limit}; unreadable records are skipped with a WARN. */
    public abstract Map<String, Stored> load(int limit) throws IOException;

    /** Replaces the stored record of one id. */
    public abstract void save(String id, byte[] record) throws IOException;

    /** Where the records live, for messages. */
    public abstract String describe();

    /** The FILE store in {@code directory} (created on the first write). */
    public static ProfileTypesStore file(final Path directory) {
        return new FileStore(directory);
    }

    /** The Redis store in hash {@code key}. */
    public static ProfileTypesStore redis(final RedisClient client, final String key) {
        return new RedisStore(client, key);
    }

    // ------------------------------------------------------------------ codec

    /** The record bytes of a definition. */
    public static byte[] encode(final ProfileTypeDefinition definition, final String owner) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            final DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeShort(VERSION);
            out.writeUTF(owner);
            out.writeUTF(definition.id());
            out.writeUTF(definition.displayName());
            out.writeUTF(definition.description());
            out.writeBoolean(definition.coopAllowed());
            out.writeBoolean(definition.creatable());
            out.writeByte(definition.maxMembers() == null ? 0 : definition.maxMembers());
            out.writeByte(definition.naming().policy().ordinal());
            out.writeShort(definition.naming().pool().size());
            for (final String entry : definition.naming().pool()) out.writeUTF(entry);
            writeRule(out, definition.defaultRule());
            out.writeShort(definition.rules().size());
            for (final Map.Entry<String, ProfileRule> rule : definition.rules().entrySet()) {
                out.writeUTF(rule.getKey());
                writeRule(out, rule.getValue());
            }
            out.flush();
            final CRC32 crc = new CRC32();
            crc.update(bytes.toByteArray());
            out.writeLong(crc.getValue());
            out.flush();
            final byte[] result = bytes.toByteArray();
            if (result.length > MAX_RECORD_BYTES) {
                throw new IllegalArgumentException("profile type " + definition.id() + " encodes to " + result.length
                    + " bytes, more than " + MAX_RECORD_BYTES);
            }
            return result;
        } catch (final IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Decodes a record; {@code where} names it in the error. */
    public static Stored decode(final byte[] record, final String where) throws IOException {
        if (record.length < 14 || record.length > MAX_RECORD_BYTES) throw new IOException(where + ": bad record size " + record.length);
        final CRC32 crc = new CRC32();
        crc.update(record, 0, record.length - 8);
        final long stored = new DataInputStream(new ByteArrayInputStream(record, record.length - 8, 8)).readLong();
        if (stored != crc.getValue()) throw new IOException(where + ": checksum mismatch");
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(record, 0, record.length - 8));
        if (in.readInt() != MAGIC) throw new IOException(where + ": not a profile type record");
        final int version = in.readUnsignedShort();
        if (version != VERSION) throw new IOException(where + ": record version " + version + " is not known to this build");
        try {
            final String owner = in.readUTF();
            final ProfileTypeDefinition.Builder builder = ProfileTypeDefinition.builder(in.readUTF())
                .displayName(in.readUTF()).description(in.readUTF()).coopAllowed(in.readBoolean()).creatable(in.readBoolean());
            final int members = in.readUnsignedByte();
            builder.maxMembers(members == 0 ? null : members);
            final int policy = in.readUnsignedByte();
            if (policy >= ProfileNaming.Policy.values().length) throw new IOException(where + ": naming policy " + policy);
            final int poolSize = in.readUnsignedShort();
            if (poolSize > ProfileNaming.MAX_POOL) throw new IOException(where + ": pool of " + poolSize);
            final List<String> pool = new ArrayList<>(poolSize);
            for (int i = 0; i < poolSize; i++) pool.add(in.readUTF());
            builder.naming(new ProfileNaming(ProfileNaming.Policy.values()[policy], pool));
            builder.defaultRule(readRule(in, where));
            final int rules = in.readUnsignedShort();
            if (rules > ProfileTypeDefinition.MAX_RULES) throw new IOException(where + ": " + rules + " rules");
            for (int i = 0; i < rules; i++) builder.rule(in.readUTF(), readRule(in, where));
            if (in.available() != 0) throw new IOException(where + ": trailing bytes");
            return new Stored(builder.build(), owner);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException(where + ": " + invalid.getMessage(), invalid);
        }
    }

    private static void writeRule(final DataOutputStream out, final ProfileRule rule) throws IOException {
        out.writeBoolean(rule.allowed());
        out.writeByte(rule.params().size());
        for (final Map.Entry<String, String> param : rule.params().entrySet()) {
            out.writeUTF(param.getKey());
            out.writeUTF(param.getValue());
        }
    }

    private static ProfileRule readRule(final DataInputStream in, final String where) throws IOException {
        final boolean allowed = in.readBoolean();
        final int count = in.readUnsignedByte();
        if (count > ProfileRule.MAX_PARAMS) throw new IOException(where + ": " + count + " rule parameters");
        final Map<String, String> params = new TreeMap<>();
        for (int i = 0; i < count; i++) params.put(in.readUTF(), in.readUTF());
        return new ProfileRule(allowed, params);
    }

    // ------------------------------------------------------------------ FILE

    private static final class FileStore extends ProfileTypesStore {

        private final Path directory;

        FileStore(final Path directory) {
            this.directory = directory;
        }

        @Override
        public Map<String, Stored> load(final int limit) throws IOException {
            final Map<String, Stored> result = new LinkedHashMap<>();
            if (!Files.isDirectory(this.directory)) return result;
            final List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> found = Files.newDirectoryStream(this.directory, "*" + SUFFIX)) {
                for (final Path file : found) {
                    if (files.size() >= limit) {
                        SourbyLogger.warn("AWF MultiProfile: " + this.directory + " holds more than " + limit
                            + " profile type records; the rest are not read");
                        break;
                    }
                    files.add(file);
                }
            }
            files.sort(null);
            for (final Path file : files) {
                final String name = file.getFileName().toString();
                final String id = name.substring(0, name.length() - SUFFIX.length());
                if (!ProfileTypeDefinition.validId(id) || !Files.isRegularFile(file)) continue;
                try {
                    result.put(id, decode(FileProfileIo.readBounded(file, MAX_RECORD_BYTES), file.toString()));
                } catch (final IOException unreadable) {
                    SourbyLogger.warn("AWF MultiProfile: stored profile type " + file + " is unreadable ("
                        + unreadable.getMessage() + "); every action of its profiles is refused until its plugin registers it again");
                    result.put(id, Stored.unreadable(unreadable.getMessage()));
                }
            }
            return result;
        }

        @Override
        public void save(final String id, final byte[] record) throws IOException {
            if (!ProfileTypeDefinition.validId(id)) throw new IllegalArgumentException("type id " + id);
            Files.createDirectories(this.directory);
            FileProfileIo.writeDurable(this.directory.resolve(id + SUFFIX), record);
        }

        @Override
        public String describe() {
            return this.directory.toString();
        }
    }

    // ------------------------------------------------------------------ Redis

    private static final class RedisStore extends ProfileTypesStore {

        private final RedisClient client;
        private final String key;

        RedisStore(final RedisClient client, final String key) {
            this.client = client;
            this.key = key;
        }

        @Override
        public Map<String, Stored> load(final int limit) throws IOException {
            // Field names first, then one bounded HGET per field (review P3-13): a hash with more fields than the limit
            // leaves the surplus unread with a WARN instead of refusing startup, and one oversized or unreadable value
            // becomes a deny-all tombstone (like a corrupt file) instead of failing every other definition with it.
            final Object names = this.client.callBounded(MAX_RECORD_BYTES, "HKEYS", this.key);
            if (!(names instanceof List<?> fields)) throw new IOException("HKEYS " + this.key + " returned a malformed reply");
            final TreeMap<String, Boolean> ids = new TreeMap<>();
            for (final Object field : fields) {
                final String id = RedisClient.text(field);
                if (id != null && ProfileTypeDefinition.validId(id)) ids.put(id, Boolean.TRUE);
            }
            if (ids.size() > limit) {
                SourbyLogger.warn("AWF MultiProfile: Redis hash " + this.key + " holds " + ids.size() + " profile type records, more than "
                    + limit + "; only the first " + limit + " (by id) are read, the rest are unknown until their plugins register them");
            }
            final Map<String, Stored> result = new LinkedHashMap<>();
            int read = 0;
            for (final String id : ids.keySet()) {
                if (read++ >= limit) break;
                final String where = this.key + "[" + id + "]";
                try {
                    final Object value = this.client.callBounded(MAX_RECORD_BYTES, "HGET", this.key, id);
                    if (value == null) continue;
                    final byte[] bytes = value instanceof byte[] raw ? Arrays.copyOf(raw, raw.length)
                        : String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                    result.put(id, decode(bytes, where));
                } catch (final IOException | RuntimeException unreadable) {
                    SourbyLogger.warn("AWF MultiProfile: stored profile type " + where + " is unreadable (" + unreadable.getMessage()
                        + "); every action of its profiles is refused until its plugin registers it again");
                    result.put(id, Stored.unreadable(unreadable.getMessage()));
                }
            }
            return result;
        }

        @Override
        public void save(final String id, final byte[] record) throws IOException {
            if (!ProfileTypeDefinition.validId(id)) throw new IllegalArgumentException("type id " + id);
            this.client.call("HSET", this.key, id, record);
        }

        @Override
        public String describe() {
            return "Redis hash " + this.key;
        }
    }
}
