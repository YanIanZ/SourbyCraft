package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The store-wide record format and its migration state: the gate behind owner decision OD-2.
 *
 * <p>A store is either <b>format 1</b> (PR #62 solo MultiProfile: no marker, or a marker saying so)
 * or <b>format 2</b> (co-op records, every frame carries format byte 2). Once a store is format 2 a
 * binary older than Build 51 is refused twice over: at startup, because it does not understand this
 * marker ({@link #FILE_NAME} / the Redis {@code meta} fields), and per record, because the format-1
 * reader rejects format byte 2. The way back is restoring the complete pre-migration backup into an
 * isolated store; an in-place downgrade is unsupported and no exporter exists. Build 51 itself refuses
 * a marker with a format it does not know, and refuses to write while a migration is in progress.</p>
 *
 * <p>Both repositories read the marker before any other record and check it on <b>every</b> write
 * (FILE under the commit lock, Redis inside each write script) so a concurrent migration or a newer
 * binary cannot interleave with an older writer. The marker is written only by the migration tool,
 * through {@link ProfileRepository#writeStoreFormat}, under the exclusive migration lock.</p>
 */
public final class StoreFormat {

    /** PR #62 solo records. */
    public static final int LEGACY = 1;
    /** Co-op records; the format this build writes. */
    public static final int CURRENT = 2;
    /** The payload schema version this build writes inside format-2 records. */
    public static final int SCHEMA = 1;
    /** Marker file in the FILE repository root. */
    public static final String FILE_NAME = "format.awfm";
    /** Redis {@code meta} hash fields. */
    public static final String REDIS_FORMAT_FIELD = "format";
    public static final String REDIS_MIGRATION_FIELD = "migrationState";
    public static final String REDIS_SCHEMA_FIELD = "schema";
    public static final String REDIS_WRITTEN_BY_FIELD = "writtenBy";

    private static final int MAGIC = 0x41574646; // "AWFF"
    private static final int MAX_WRITTEN_BY = 64;

    private StoreFormat() {}

    /**
     * The marker.
     *
     * @param format {@link #LEGACY}, {@link #CURRENT} or a value this build does not know
     * @param migration migration state
     * @param schema payload schema the store's format-2 records were written with ({@code 0} for format 1)
     * @param writtenAtMillis when the marker was written ({@code 0} for an implicit legacy marker)
     * @param writtenBy the build that wrote it, at most 64 characters
     */
    public record Marker(int format, MigrationState migration, int schema, long writtenAtMillis, String writtenBy) {

        public Marker {
            Objects.requireNonNull(migration, "migration");
            Objects.requireNonNull(writtenBy, "writtenBy");
            if (format < 1) throw new IllegalArgumentException("format " + format);
            if (schema < 0) throw new IllegalArgumentException("schema " + schema);
            if (writtenAtMillis < 0) throw new IllegalArgumentException("writtenAtMillis");
            if (writtenBy.length() > MAX_WRITTEN_BY) throw new IllegalArgumentException("writtenBy too long");
        }

        /** A store without a marker: PR #62 format 1. */
        public static Marker legacy() {
            return new Marker(LEGACY, MigrationState.NONE, 0, 0L, "");
        }

        /** The marker the migration writes before copying; writes are refused while it stands. */
        public static Marker migrating(final String build) {
            return new Marker(CURRENT, MigrationState.IN_PROGRESS, SCHEMA, System.currentTimeMillis(), build);
        }

        /** The marker of a complete format-2 store (after a verified migration, or a store created empty by this build). */
        public static Marker ready(final String build) {
            return new Marker(CURRENT, MigrationState.READY, SCHEMA, System.currentTimeMillis(), build);
        }

        public boolean legacyFormat() {
            return this.format == LEGACY;
        }

        public boolean readyFormat2() {
            return this.format == CURRENT && this.migration == MigrationState.READY;
        }
    }

    /** Migration states with stable numeric tags. */
    public enum MigrationState {
        /** No migration recorded (format 1, or a format-2 store created empty). */
        NONE(1),
        /** A migration started and did not finish; all writes refused. */
        IN_PROGRESS(2),
        /** Migration verified and published. */
        READY(3);

        private final int tag;

        MigrationState(final int tag) {
            this.tag = tag;
        }

        public int tag() {
            return this.tag;
        }

        public static MigrationState fromTag(final int tag, final String where) throws IOException {
            for (final MigrationState state : values()) {
                if (state.tag == tag) return state;
            }
            throw new IOException("migration state tag " + tag + " in " + where + " is not known to this build");
        }
    }

    /** What this build may do with a store. */
    public enum Gate {
        /** Format 2, ready: every operation. */
        FORMAT_2,
        /** Format 1: the solo paths read and write format-1 records; every format-2 operation refuses with {@code MIGRATION_REQUIRED}. */
        LEGACY,
        /** Refused entirely: unknown format, unknown schema, or a migration in progress. Nothing is written. */
        REFUSED
    }

    /** The gate decision and the operator-facing reason. */
    public record Decision(Gate gate, String reason) {

        public boolean writable() {
            return this.gate != Gate.REFUSED;
        }
    }

    /** Decides what this build may do with a store whose marker is {@code marker}. */
    public static Decision gate(final Marker marker) {
        if (marker.format() > CURRENT) {
            return new Decision(Gate.REFUSED, "the profile store is format " + marker.format() + " (written by "
                + marker.writtenBy() + "); this build reads up to format " + CURRENT + ". Nothing was written. Use the"
                + " build that wrote it, or restore the pre-migration backup into an isolated store");
        }
        if (marker.format() == LEGACY) {
            return new Decision(Gate.LEGACY, "the profile store is format 1 (pre-Build-51 solo records); co-op operations need"
                + " format 2 and refuse with MIGRATION_REQUIRED until the offline migration ran");
        }
        if (marker.migration() == MigrationState.IN_PROGRESS) {
            return new Decision(Gate.REFUSED, "a format-2 migration started at " + marker.writtenAtMillis() + " by "
                + marker.writtenBy() + " and did not finish; nothing is written until it is re-run offline or the backup is"
                + " restored");
        }
        if (marker.schema() > SCHEMA) {
            return new Decision(Gate.REFUSED, "the profile store's format-2 records use schema " + marker.schema()
                + "; this build writes schema " + SCHEMA + " and refuses a newer one rather than round-trip it lossily");
        }
        return new Decision(Gate.FORMAT_2, "format 2, ready");
    }

    // ------------------------------------------------------------------ marker bytes (FILE)

    /** The marker file's bytes: magic, format, migration tag, schema, time and the writer. */
    public static byte[] encode(final Marker marker) {
        final byte[] writer = marker.writtenBy().getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + 4 + 8 + 4 + writer.length);
        buffer.putInt(MAGIC).putInt(marker.format()).putInt(marker.migration().tag()).putInt(marker.schema())
            .putLong(marker.writtenAtMillis()).putInt(writer.length).put(writer);
        return buffer.array();
    }

    /**
     * Decodes a marker file; a file this build cannot parse is refused (it may belong to a newer
     * build), never read as "legacy".
     */
    public static Marker decode(final byte[] bytes, final String where) throws IOException {
        if (bytes.length < 28 || bytes.length > 28 + 4 * MAX_WRITTEN_BY) throw new IOException("store format marker " + where + " is malformed");
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC) throw new IOException("store format marker " + where + " has no AWFF magic");
        final int format = buffer.getInt();
        final MigrationState migration = MigrationState.fromTag(buffer.getInt(), where);
        final int schema = buffer.getInt();
        final long writtenAt = buffer.getLong();
        final int length = buffer.getInt();
        if (length < 0 || length != buffer.remaining()) throw new IOException("store format marker " + where + " is malformed");
        final String writer = new String(bytes, 28, length, StandardCharsets.UTF_8);
        try {
            return new Marker(format, migration, schema, writtenAt, writer);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("store format marker " + where + " is invalid: " + invalid.getMessage());
        }
    }
}
