package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.CoopPage;
import dev.iyanz.sourbycraft.api.profile.ProfileAuditEntry;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The MultiProfile audit log (Build 55, COOP-DESIGN slice 7): immutable entries recorded <em>after</em> the durable
 * outcome of an operation, written on the bounded I/O lane, never changing the outcome they describe.
 *
 * <p><b>Where.</b> Two places. A bounded in-memory ring (the newest {@value #MEMORY_ENTRIES} entries over all
 * profiles) answers {@link #page} for {@code /awf multiprofile audit} and {@code AuroraProfiles.audit}; and
 * append-only daily files {@code <directory>/audit/<yyyy-MM-dd>.log} (one tab-separated line per entry) hold the
 * retained log, kept {@code audit.retention-days} and then deleted at the next day change. Both are node-local:
 * each node records what it committed. The files live outside the profile store format (no record type, no
 * store-level change); the Redis repository writes them below the configured directory as well.</p>
 *
 * <p><b>How.</b> {@link #record} is non-blocking on every thread: it appends to the ring and offers one step to the
 * audit's own single-slot {@link ProfileIoQueue} on the storage lane (its own FIFO, so it never delays or reorders
 * profile steps and holds at most one lane slot). A full queue drops the entry and counts it ({@link #dropped});
 * a write that fails is counted ({@link #writeFailures}) and logged at most once a minute. A daily file is capped at
 * {@value #MAX_SEGMENT_BYTES} bytes: an entry that would take it over is dropped and counted (the ring keeps it), with
 * one WARN per minute. {@link #close} stops accepting entries; the entries already handed to the lane are still
 * written. Nothing here throws to the caller, and nothing here is consulted by recovery: the store's intents and
 * transitions are the journal.</p>
 */
public final class ProfileAudit {

    /** Entries kept in memory for paging. */
    public static final int MEMORY_ENTRIES = 4096;
    /** Audit writes waiting for the lane; beyond it an entry is dropped and counted. */
    static final int QUEUE_CAPACITY = 1024;
    /** Most bytes one daily file grows to; entries beyond it are dropped and counted (review P3-11). */
    static final long MAX_SEGMENT_BYTES = 256L * 1024L * 1024L;
    /** One write-failure WARN per this many milliseconds. */
    private static final long WARN_INTERVAL_MILLIS = 60_000L;
    private static final String CURSOR_PREFIX = "audit:";

    private final Path directory;
    private final int retentionDays;
    private final ProfileIoQueue queue;
    private final Object ringLock = new Object();
    private final ProfileAuditEntry[] ring = new ProfileAuditEntry[MEMORY_ENTRIES];
    private int ringNext;
    private int ringSize;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private volatile long lastWarnMillis;
    /** The day the current segment belongs to (UTC); a change runs retention. Written on the lane only. */
    private LocalDate segmentDay;
    /** Bytes of the current segment as last seen by this node; written on the lane only. */
    private long segmentBytes;
    private volatile boolean closed;

    /**
     * @param directory the profile store directory; the log goes to {@code directory/audit}
     * @param retentionDays days a daily file is kept (at least 1)
     * @param lane the storage lane executor
     */
    public ProfileAudit(final Path directory, final int retentionDays, final Executor lane) {
        this.directory = Objects.requireNonNull(directory, "directory").resolve("audit");
        if (retentionDays < 1) throw new IllegalArgumentException("retentionDays " + retentionDays);
        this.retentionDays = retentionDays;
        this.queue = new ProfileIoQueue(Objects.requireNonNull(lane, "lane"), QUEUE_CAPACITY);
    }

    public Path directory() {
        return this.directory;
    }

    public long recorded() {
        return this.recorded.get();
    }

    public long dropped() {
        return this.dropped.get();
    }

    public long writeFailures() {
        return this.writeFailures.get();
    }

    /**
     * Records an entry: ring now, file on the lane. Never blocks, never throws.
     *
     * @return the entry as recorded (its sequence assigned), for callers that log it
     */
    public ProfileAuditEntry record(final String kind, final UUID profileId, final UUID actorId, final UUID operationId,
                                    final boolean success, final ProfileErrorCode errorCode, final String detail) {
        final ProfileAuditEntry entry;
        try {
            entry = new ProfileAuditEntry(this.sequence.incrementAndGet(), System.currentTimeMillis(), kind, profileId, actorId,
                operationId, success, success ? null : errorCode, detail == null ? "" : sanitize(detail));
        } catch (final IllegalArgumentException invalid) {
            this.dropped.incrementAndGet();
            return null;
        }
        if (this.closed) {
            this.dropped.incrementAndGet();
            return entry;
        }
        synchronized (this.ringLock) {
            this.ring[this.ringNext] = entry;
            this.ringNext = (this.ringNext + 1) % MEMORY_ENTRIES;
            if (this.ringSize < MEMORY_ENTRIES) this.ringSize++;
        }
        this.recorded.incrementAndGet();
        final boolean queued = this.queue.offer(new ProfileIoQueue.Step() {
            @Override
            public Runnable run() {
                append(entry);
                return null;
            }

            @Override
            public void rejected(final RejectedExecutionException rejected) {
                ProfileAudit.this.dropped.incrementAndGet();
            }
        });
        if (!queued) this.dropped.incrementAndGet();
        return entry;
    }

    /** Control characters, tabs and line breaks become spaces: one entry is one line. */
    static String sanitize(final String text) {
        final StringBuilder out = new StringBuilder(Math.min(text.length(), ProfileAuditEntry.MAX_DETAIL));
        for (int i = 0; i < text.length() && out.length() < ProfileAuditEntry.MAX_DETAIL; i++) {
            final char c = text.charAt(i);
            out.append(c < ' ' || c == '\u007f' ? ' ' : c);
        }
        return out.toString();
    }

    /**
     * The lane step: opens the day's segment (running retention on a day change) and appends one line. Runs for an
     * entry queued before {@link #close} even after it: a closed log drops new entries, not the ones it accepted.
     */
    private void append(final ProfileAuditEntry entry) {
        try {
            final LocalDate day = Instant.ofEpochMilli(entry.atMillis()).atZone(ZoneOffset.UTC).toLocalDate();
            final Path segment = this.directory.resolve(day + ".log");
            if (this.segmentDay == null || !this.segmentDay.equals(day)) {
                Files.createDirectories(this.directory);
                this.segmentDay = day;
                this.segmentBytes = Files.exists(segment) ? Files.size(segment) : 0L;
                retain(day);
            }
            final byte[] line = line(entry).getBytes(StandardCharsets.UTF_8);
            if (this.segmentBytes + line.length > MAX_SEGMENT_BYTES) {
                this.dropped.incrementAndGet();
                final long now = System.currentTimeMillis();
                if (now - this.lastWarnMillis >= WARN_INTERVAL_MILLIS) {
                    this.lastWarnMillis = now;
                    SourbyLogger.warn("AWF MultiProfile: the audit file " + segment + " reached its " + MAX_SEGMENT_BYTES
                        + "-byte cap; further entries of this day are kept in memory only (/awf multiprofile audit) and counted as dropped");
                }
                return;
            }
            Files.write(segment, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            this.segmentBytes += line.length;
        } catch (final IOException | RuntimeException failed) {
            this.writeFailures.incrementAndGet();
            final long now = System.currentTimeMillis();
            if (now - this.lastWarnMillis >= WARN_INTERVAL_MILLIS) {
                this.lastWarnMillis = now;
                SourbyLogger.warn("AWF MultiProfile: the audit log could not be written to " + this.directory + " (" + failed.getMessage()
                    + "); " + this.writeFailures.get() + " write(s) failed so far. The operations themselves are unaffected; the"
                    + " entries stay in memory (/awf multiprofile audit)");
            }
        }
    }

    /** One line: sequence, ISO time, kind, profile, actor, operation, OK or the error code, detail. */
    static String line(final ProfileAuditEntry entry) {
        return entry.sequence() + "\t" + Instant.ofEpochMilli(entry.atMillis()) + "\t" + entry.kind() + "\t"
            + (entry.profileId() == null ? "-" : entry.profileId()) + "\t" + (entry.actorId() == null ? "-" : entry.actorId()) + "\t"
            + (entry.operationId() == null ? "-" : entry.operationId()) + "\t" + (entry.success() ? "OK" : String.valueOf(entry.errorCode()))
            + "\t" + entry.detail() + "\n";
    }

    /** Deletes daily files older than the retention window; a file that cannot be deleted is logged and left. */
    private void retain(final LocalDate today) {
        final LocalDate oldestKept = today.minusDays(this.retentionDays - 1L);
        try (Stream<Path> files = Files.list(this.directory)) {
            for (final Path file : (Iterable<Path>) files::iterator) {
                final String name = file.getFileName().toString();
                if (!name.endsWith(".log") || name.length() != 14) continue;
                final LocalDate day;
                try {
                    day = LocalDate.parse(name.substring(0, 10));
                } catch (final RuntimeException notADay) {
                    continue;
                }
                if (day.isBefore(oldestKept)) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (final IOException kept) {
                        SourbyLogger.warn("AWF MultiProfile: could not delete the expired audit file " + file + ": " + kept.getMessage());
                    }
                }
            }
        } catch (final IOException unlisted) {
            SourbyLogger.warn("AWF MultiProfile: could not list " + this.directory + " for audit retention: " + unlisted.getMessage());
        }
    }

    /**
     * One page of the in-memory entries about {@code profileId} (also entries whose actor is that id, for an
     * account), newest first. {@code cursor} is {@code null} for the first page.
     *
     * @throws dev.iyanz.sourbycraft.api.profile.ProfileOperationException {@code INVALID_REQUEST} for a malformed cursor
     */
    public CoopPage<ProfileAuditEntry> page(final UUID profileId, final String cursor) {
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            if (!cursor.startsWith(CURSOR_PREFIX)) throw badCursor(cursor);
            try {
                before = Long.parseLong(cursor.substring(CURSOR_PREFIX.length()));
            } catch (final NumberFormatException bad) {
                throw badCursor(cursor);
            }
        }
        final ProfileAuditEntry[] copy;
        final int size;
        final int next;
        synchronized (this.ringLock) {
            copy = this.ring.clone();
            size = this.ringSize;
            next = this.ringNext;
        }
        final List<ProfileAuditEntry> items = new ArrayList<>(CoopPage.MAX_PAGE_SIZE);
        String nextCursor = null;
        for (int i = 1; i <= size; i++) {
            final ProfileAuditEntry entry = copy[Math.floorMod(next - i, MEMORY_ENTRIES)];
            if (entry == null || entry.sequence() >= before) continue;
            if (!profileId.equals(entry.profileId()) && !profileId.equals(entry.actorId())) continue;
            if (items.size() == CoopPage.MAX_PAGE_SIZE) {
                nextCursor = CURSOR_PREFIX + items.get(items.size() - 1).sequence();
                break;
            }
            items.add(entry);
        }
        return new CoopPage<>(items, nextCursor);
    }

    private static dev.iyanz.sourbycraft.api.profile.ProfileOperationException badCursor(final String cursor) {
        return new dev.iyanz.sourbycraft.api.profile.ProfileOperationException(ProfileErrorCode.INVALID_REQUEST,
            "not an audit cursor: " + cursor);
    }

    /** Stops accepting entries; writes already handed to the lane finish on their own (none of them is dropped). */
    public void close() {
        this.closed = true;
    }
}
