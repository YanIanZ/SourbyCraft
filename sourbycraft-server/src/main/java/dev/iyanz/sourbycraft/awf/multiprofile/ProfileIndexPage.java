package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One page of a durable index (format-2 record type 13, subtype index): ordered ids under one scope.
 *
 * <p>Listings never scan all records; they page through these indexes, at most {@value #MAX_ENTRIES}
 * entries and {@value #MAX_BYTES} encoded bytes per page. Order is {@code (createdAtMillis, id)}. A
 * {@link Cursor} carries the index generation, the last key and the scope; changing the generation
 * (a rebuild) invalidates every cursor, and a listing then answers "restart", never an incomplete
 * authoritative result. Index writes are part of an intent's after-images, so a page is never ahead
 * of or behind the records it indexes. A missing or corrupt page locks the affected listings until an
 * offline rebuild from the canonical records; a corrupt read is never reported as "empty".</p>
 *
 * <p>Pin pages ({@link Kind#PINS_BY_STREAM}) carry a reference count per snapshot id in
 * {@link Entry#count()}: an exact snapshot stays unprunable while any pending intent of any member
 * names it. Stream snapshot pages ({@link Kind#SNAPSHOTS_BY_STREAM}) list the snapshot ids of one
 * member stream in {@code R} order, with the head revision each was published at in the time field;
 * both repositories serve them from the stream itself (Redis: the stream's sorted set; FILE: the
 * bounded stream directory), not from a page written by an intent.</p>
 *
 * @param kind which index
 * @param resource the scope id (profile, player or invitee; {@link #GLOBAL} for the global pending set)
 * @param member stream member for a {@link Kind#streamScoped()} kind, else {@code null}
 * @param epoch stream epoch for a {@link Kind#streamScoped()} kind, else {@code null}
 * @param generation index generation; a rebuild increments it
 * @param page page number from 0
 * @param revision the page's own revision
 * @param entries ordered entries
 */
public record ProfileIndexPage(Kind kind, UUID resource, UUID member, UUID epoch, long generation, int page, long revision,
                               List<Entry> entries) {

    public static final int MAX_ENTRIES = 128;
    public static final int MAX_BYTES = 64 * 1024;
    /** Listing page size handed to callers (§9: 32 records). */
    public static final int LISTING_PAGE = 32;
    /** Scope of the global pending-intent index. */
    public static final UUID GLOBAL = new UUID(0L, 0L);

    public ProfileIndexPage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(resource, "resource");
        if (kind.streamScoped()) {
            Objects.requireNonNull(member, "member");
            Objects.requireNonNull(epoch, "epoch");
        } else if (member != null || epoch != null) {
            throw new IllegalArgumentException(kind + " has no member or epoch scope");
        }
        CoopWriteGuard.counter(generation, "index generation");
        if (page < 0) throw new IllegalArgumentException("page " + page);
        CoopWriteGuard.counter(revision, "index page revision");
        entries = List.copyOf(entries == null ? List.of() : entries);
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("index page too long");
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i - 1).compareTo(entries.get(i)) >= 0) {
                throw new IllegalArgumentException("index page entries must be strictly ordered by (createdAt, id)");
            }
        }
    }

    /** An empty page of a kind that is not {@link Kind#streamScoped()}. */
    public static ProfileIndexPage empty(final Kind kind, final UUID resource, final long generation, final int page) {
        return new ProfileIndexPage(kind, resource, null, null, generation, page, 0L, List.of());
    }

    public boolean full() {
        return this.entries.size() >= MAX_ENTRIES;
    }

    /** The page with {@code entry} inserted in order (or its count replaced), at revision + 1. */
    public ProfileIndexPage with(final Entry entry) {
        final List<Entry> next = new java.util.ArrayList<>(this.entries);
        next.removeIf(existing -> existing.id().equals(entry.id()));
        int at = 0;
        while (at < next.size() && next.get(at).compareTo(entry) < 0) at++;
        next.add(at, entry);
        return new ProfileIndexPage(this.kind, this.resource, this.member, this.epoch, this.generation, this.page,
            CoopWriteGuard.next(this.revision, "index page"), next);
    }

    /** The page without {@code id}, at revision + 1 (unchanged instance when absent). */
    public ProfileIndexPage without(final UUID id) {
        final List<Entry> next = new java.util.ArrayList<>(this.entries);
        if (!next.removeIf(existing -> existing.id().equals(id))) return this;
        return new ProfileIndexPage(this.kind, this.resource, this.member, this.epoch, this.generation, this.page,
            CoopWriteGuard.next(this.revision, "index page"), next);
    }

    /** The cursor after the last entry of this page, or {@code null} when the page is empty. */
    public Cursor cursorAfterLast() {
        if (this.entries.isEmpty()) return null;
        final Entry last = this.entries.getLast();
        return new Cursor(this.kind, this.resource, this.generation, last.createdAtMillis(), last.id());
    }

    /**
     * One indexed id.
     *
     * @param createdAtMillis the indexed record's creation time (primary order)
     * @param id the record id (secondary order)
     * @param count reference count for pin pages, {@code 0} otherwise
     */
    public record Entry(long createdAtMillis, UUID id, long count) implements Comparable<Entry> {

        public Entry {
            Objects.requireNonNull(id, "id");
            if (createdAtMillis < 0 || count < 0) throw new IllegalArgumentException("index entry");
        }

        public Entry(final long createdAtMillis, final UUID id) {
            this(createdAtMillis, id, 0L);
        }

        @Override
        public int compareTo(final Entry other) {
            final int byTime = Long.compare(this.createdAtMillis, other.createdAtMillis);
            return byTime != 0 ? byTime : this.id.compareTo(other.id);
        }
    }

    /**
     * An opaque listing position: kind, scope, generation and the last key returned. A cursor whose
     * generation is not the index's current one is refused ({@code INVALID_REQUEST}, "restart the
     * listing"), never answered with a partial page.
     */
    public record Cursor(Kind kind, UUID resource, long generation, long lastCreatedAtMillis, UUID lastId) {

        public Cursor {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(lastId, "lastId");
            CoopWriteGuard.counter(generation, "cursor generation");
            if (lastCreatedAtMillis < 0) throw new IllegalArgumentException("cursor time");
        }

        /** URL-safe base64 of the fixed 52-byte form (kind tag, resource, generation, last time, last id). */
        public String encode() {
            final ByteBuffer buffer = ByteBuffer.allocate(4 + 16 + 8 + 8 + 16);
            buffer.putInt(this.kind.tag());
            buffer.putLong(this.resource.getMostSignificantBits()).putLong(this.resource.getLeastSignificantBits());
            buffer.putLong(this.generation);
            buffer.putLong(this.lastCreatedAtMillis);
            buffer.putLong(this.lastId.getMostSignificantBits()).putLong(this.lastId.getLeastSignificantBits());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        }

        /** Decodes {@link #encode}; a malformed cursor is refused as invalid, never treated as "from the start". */
        public static Cursor decode(final String text) throws IOException {
            final byte[] bytes;
            try {
                bytes = Base64.getUrlDecoder().decode(Objects.requireNonNull(text, "cursor"));
            } catch (final IllegalArgumentException invalid) {
                throw new IOException("malformed listing cursor");
            }
            if (bytes.length != 52) throw new IOException("malformed listing cursor");
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            try {
                final Kind kind = Kind.fromTag(buffer.getInt(), "cursor");
                final UUID resource = new UUID(buffer.getLong(), buffer.getLong());
                final long generation = buffer.getLong();
                final long createdAt = buffer.getLong();
                final UUID lastId = new UUID(buffer.getLong(), buffer.getLong());
                return new Cursor(kind, resource, generation, createdAt, lastId);
            } catch (final IllegalArgumentException invalid) {
                throw new IOException("malformed listing cursor: " + invalid.getMessage());
            }
        }
    }

    /** Index kinds with stable numeric tags; each names what {@code resource} is. */
    public enum Kind {
        /** Invites of a profile; resource = profile. */
        INVITES_BY_PROFILE(1),
        /** Invites to an account; resource = invitee. */
        INVITES_BY_INVITEE(2),
        /** Votes of a profile; resource = profile. */
        VOTES_BY_PROFILE(3),
        /** Votes against a member; resource = target player. */
        VOTES_BY_TARGET(4),
        /** All salvage of a profile; resource = profile. */
        SALVAGE_BY_PROFILE(5),
        /** AVAILABLE salvage of a profile; resource = profile. */
        SALVAGE_AVAILABLE_BY_PROFILE(6),
        /** Pending intents touching a player; resource = player. */
        INTENTS_BY_PLAYER(7),
        /** Pending intents touching a profile; resource = profile. */
        INTENTS_BY_PROFILE(8),
        /** Every pending intent; resource = {@link #GLOBAL}. */
        PENDING_INTENTS(9),
        /** Pinned snapshot ids of one stream with reference counts; resource = profile, plus member and epoch. */
        PINS_BY_STREAM(10),
        /** Snapshot ids of one stream (for bounded pruning); resource = profile, plus member and epoch. */
        SNAPSHOTS_BY_STREAM(11);

        private final int tag;

        Kind(final int tag) {
            this.tag = tag;
        }

        public int tag() {
            return this.tag;
        }

        public static Kind fromTag(final int tag, final String where) throws IOException {
            for (final Kind kind : values()) {
                if (kind.tag == tag) return kind;
            }
            throw new IOException("index kind tag " + tag + " in " + where + " is not known to this build");
        }

        /** Whether pages of this kind are scoped by member and epoch as well. */
        public boolean streamScoped() {
            return this == PINS_BY_STREAM || this == SNAPSHOTS_BY_STREAM;
        }
    }
}
