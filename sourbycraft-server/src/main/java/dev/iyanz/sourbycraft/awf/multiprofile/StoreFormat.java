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
 * binary cannot interleave with an older writer. The marker is written by the migration tool, through
 * {@link ProfileRepository#writeStoreFormat}, under the exclusive migration lock, and raised online by
 * exactly one other path: {@link ProfileRepository#requireStoreFeature}.</p>
 *
 * <p><b>Store feature level (Build 52).</b> Before the first co-op record (a co-op or labelled profile, an
 * invite, vote or salvage record, a member head of a non-owner) is prepared, the engine raises the marker to
 * payload schema {@link #COOP_SCHEMA} and {@code minimumBuild} {@link #COOP_MINIMUM_BUILD}, durably, and
 * never lowers either. A store without co-op records keeps the Build 51 marker byte for byte, so Build 51 still
 * opens it. Build 51 refuses a raised store at startup: its gate refuses a schema above 1 (FILE and Redis) and
 * its FILE decoder refuses the marker file's {@code minimumBuild} trailer as malformed (it accepts no trailing
 * bytes); in Redis the {@code minimumBuild} hash field is ignored by Build 51 and the schema refuses. The
 * auto-updater ({@code StoreDowngradeGuard}) refuses to install or restore a build below
 * {@link Marker#requiredBuild()}.</p>
 *
 * <p><b>Build 53</b> keeps {@link #COOP_MINIMUM_BUILD} at 52 and raises {@link #READS_UP_TO_BUILD} to 53 with one new
 * feature level, {@link #MEMBER_PLAY_MINIMUM_BUILD}: the first switch journal of a <em>non-owner</em> member into a co-op
 * raises the store to minimum build 53, because Build 52's recovery of such a journal would commit a co-op active
 * pointer for a non-owner and then refuse it ({@code checkCoopAccess}), leaving the account stuck (review P3-17). Every
 * other record Build 53 writes (member heads and snapshots of non-owner members, removal intents whose account
 * after-image moves the active pointer, adapter entries of the state codec including the reserved required-adapter
 * record, which Build 52 carries forward as an unknown adapter blob) has the Build 52 shape and Build 52 reads it with
 * the same meaning. Session markers ({@link SessionMarker}) are stored beside the records, under a type Build 52
 * never reads, and are not part of the level. A store without a non-owner member switch keeps the Build 52 marker.</p>
 *
 * <p><b>Build 54</b> raises {@link #READS_UP_TO_BUILD} to 54 with the feature level 54, reached two ways
 * ({@link #SALVAGE_MINIMUM_BUILD}, {@link #REGISTERED_TYPE_MINIMUM_BUILD}): the first salvage delivery intent raises the
 * store to minimum build 54. A delivery writes a salvage record in a shape
 * Build 53 refuses to decode (a DELIVERED record whose source published nothing carries no item payload, and an
 * AVAILABLE record whose payload blob is a remainder snapshot in the consumed source stream), and installs a destination
 * head whose snapshot a Build 53 node would overwrite from an older in-memory state without knowing the salvage was
 * consumed into it. The first profile record of a plugin-registered type (metadata {@code aurora:type}) or named from a
 * type's own pool raises it as well ({@link #REGISTERED_TYPE_MINIMUM_BUILD}): Build 53 refuses a name outside the server
 * pool and would read the registered type as plain classic, lifting the type's denies. Plain bank records (shared
 * state, {@code SHARED_MUTATE} and {@code BANK_TRANSFER} intents) keep the Build 51 shapes and do not raise the level,
 * but an extended shared record (archived balances of removed members or a stored interest time, review P2-4) does, and
 * so does the registration of a built-in type override that denies an action (review P3-8): Build 53 would lose the
 * archive or lift the deny. Bank receipts are permanent (kept apart from the pruned receipts, where Build 53 does not
 * look), which matters only for replaying a Build 54 bank operation, which Build 53 cannot issue.</p>
 */
public final class StoreFormat {

    /** PR #62 solo records. */
    public static final int LEGACY = 1;
    /** Co-op records; the format this build writes. */
    public static final int CURRENT = 2;
    /**
     * The marker schema the migration and a fresh format-2 store write (the Build 51 schema). Records of the
     * Build 51 shapes keep it; see {@link #COOP_SCHEMA}.
     */
    public static final int SCHEMA = 1;
    /** The marker schema of a store that holds co-op records (payload schema 2); Build 51 refuses it. */
    public static final int COOP_SCHEMA = 2;
    /** The highest marker schema this build reads and writes under. */
    public static final int MAX_SCHEMA = COOP_SCHEMA;
    /** The first build that reads a format-2 store. */
    public static final int FIRST_FORMAT_2_BUILD = 51;
    /** The {@code minimumBuild} a store gets with its first co-op record. */
    public static final int COOP_MINIMUM_BUILD = 52;
    /**
     * The highest {@code minimumBuild} this code reads; a store that requires a newer build is refused. It is the
     * store feature level of this source tree, raised with the code that needs it, not with every release.
     */
    public static final int READS_UP_TO_BUILD = 54;
    /** The {@code minimumBuild} a store gets with its first non-owner member switch journal (Build 53). */
    public static final int MEMBER_PLAY_MINIMUM_BUILD = 53;
    /** The {@code minimumBuild} a store gets with its first salvage delivery intent (Build 54, OD-3). */
    public static final int SALVAGE_MINIMUM_BUILD = 54;
    /**
     * The {@code minimumBuild} a store gets with its first profile record of a plugin-registered type or named from a type's
     * own pool (Build 54): Build 53 refuses such a name and would read a registered type as plain classic.
     */
    public static final int REGISTERED_TYPE_MINIMUM_BUILD = 54;
    /** Marker file in the FILE repository root. */
    public static final String FILE_NAME = "format.awfm";
    /** Redis {@code meta} hash fields. */
    public static final String REDIS_FORMAT_FIELD = "format";
    public static final String REDIS_MIGRATION_FIELD = "migrationState";
    public static final String REDIS_SCHEMA_FIELD = "schema";
    public static final String REDIS_WRITTEN_BY_FIELD = "writtenBy";
    /** Redis {@code meta} hash field of the store's minimum build (absent = none recorded); Build 51 ignores it. */
    public static final String REDIS_MINIMUM_BUILD_FIELD = "minimumBuild";

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
     * @param minimumBuild the oldest build that may open the store, as recorded ({@code 0} = none recorded; see
     *     {@link #requiredBuild()} for the effective value)
     */
    public record Marker(int format, MigrationState migration, int schema, long writtenAtMillis, String writtenBy,
                         int minimumBuild) {

        public Marker {
            Objects.requireNonNull(migration, "migration");
            Objects.requireNonNull(writtenBy, "writtenBy");
            if (format < 1) throw new IllegalArgumentException("format " + format);
            if (schema < 0) throw new IllegalArgumentException("schema " + schema);
            if (writtenAtMillis < 0) throw new IllegalArgumentException("writtenAtMillis");
            if (writtenBy.length() > MAX_WRITTEN_BY) throw new IllegalArgumentException("writtenBy too long");
            if (minimumBuild < 0) throw new IllegalArgumentException("minimumBuild " + minimumBuild);
        }

        /** The Build 51 shape: no minimum build recorded. */
        public Marker(final int format, final MigrationState migration, final int schema, final long writtenAtMillis,
                      final String writtenBy) {
            this(format, migration, schema, writtenAtMillis, writtenBy, 0);
        }

        /**
         * The oldest build that may open this store: {@code 0} for format 1, else the larger of the recorded
         * {@link #minimumBuild} and what the schema implies (51 for schema 1, 52 for {@link #COOP_SCHEMA}).
         */
        public int requiredBuild() {
            if (this.format == LEGACY) return 0;
            final int implied = this.schema >= COOP_SCHEMA ? COOP_MINIMUM_BUILD : FIRST_FORMAT_2_BUILD;
            return Math.max(implied, this.minimumBuild);
        }

        /** Whether this marker already records at least {@code schema} and {@code minimumBuild}. */
        public boolean covers(final int schema, final int minimumBuild) {
            return this.schema >= schema && this.minimumBuild >= minimumBuild;
        }

        /** This marker raised to at least {@code schema} and {@code minimumBuild}; never lowers either. */
        public Marker raised(final int schema, final int minimumBuild, final long writtenAtMillis, final String writtenBy) {
            final String writer = writtenBy == null ? this.writtenBy
                : writtenBy.length() <= MAX_WRITTEN_BY ? writtenBy : writtenBy.substring(0, MAX_WRITTEN_BY);
            return new Marker(this.format, this.migration, Math.max(this.schema, schema), writtenAtMillis, writer,
                Math.max(this.minimumBuild, minimumBuild));
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
        if (marker.schema() > MAX_SCHEMA) {
            return new Decision(Gate.REFUSED, "the profile store's format-2 records use schema " + marker.schema()
                + "; this build reads up to schema " + MAX_SCHEMA + " and refuses a newer one rather than round-trip it lossily");
        }
        if (marker.minimumBuild() > READS_UP_TO_BUILD) {
            return new Decision(Gate.REFUSED, "the profile store requires Build " + marker.minimumBuild() + " or newer (written by "
                + marker.writtenBy() + "); this build reads stores up to Build " + READS_UP_TO_BUILD + ". Nothing was written");
        }
        return new Decision(Gate.FORMAT_2, "format 2, ready");
    }

    // ------------------------------------------------------------------ marker bytes (FILE)

    /**
     * The marker file's bytes: magic, format, migration tag, schema, time and the writer, then, only when a
     * {@code minimumBuild} is recorded, a 4-byte trailer holding it. Without one the bytes are exactly the
     * Build 51 layout. Build 51's decoder accepts no trailer and refuses such a file as malformed, which is the
     * intended fail-closed outcome for a store holding co-op records.
     */
    public static byte[] encode(final Marker marker) {
        final byte[] writer = marker.writtenBy().getBytes(StandardCharsets.UTF_8);
        final int trailer = marker.minimumBuild() > 0 ? 4 : 0;
        final ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + 4 + 8 + 4 + writer.length + trailer);
        buffer.putInt(MAGIC).putInt(marker.format()).putInt(marker.migration().tag()).putInt(marker.schema())
            .putLong(marker.writtenAtMillis()).putInt(writer.length).put(writer);
        if (trailer > 0) buffer.putInt(marker.minimumBuild());
        return buffer.array();
    }

    /**
     * Decodes a marker file; a file this build cannot parse is refused (it may belong to a newer
     * build), never read as "legacy".
     */
    public static Marker decode(final byte[] bytes, final String where) throws IOException {
        if (bytes.length < 28 || bytes.length > 28 + 4 * MAX_WRITTEN_BY + 4) throw new IOException("store format marker " + where + " is malformed");
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC) throw new IOException("store format marker " + where + " has no AWFF magic");
        final int format = buffer.getInt();
        final MigrationState migration = MigrationState.fromTag(buffer.getInt(), where);
        final int schema = buffer.getInt();
        final long writtenAt = buffer.getLong();
        final int length = buffer.getInt();
        if (length < 0 || (length != buffer.remaining() && length + 4 != buffer.remaining())) {
            throw new IOException("store format marker " + where + " is malformed");
        }
        final String writer = new String(bytes, 28, length, StandardCharsets.UTF_8);
        int minimumBuild = 0;
        if (buffer.remaining() == length + 4) {
            buffer.position(buffer.position() + length);
            minimumBuild = buffer.getInt();
            if (minimumBuild <= 0) throw new IOException("store format marker " + where + " has an invalid minimum build " + minimumBuild);
        }
        try {
            return new Marker(format, migration, schema, writtenAt, writer, minimumBuild);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("store format marker " + where + " is invalid: " + invalid.getMessage());
        }
    }
}
