package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Durable paged indexes of the format-2 {@link FileProfileRepository}: {@code indexes/<kind tag>/<resource>/<page>.awfp},
 * stream-scoped kinds one level deeper ({@code .../<member>/<epoch>/<page>.awfp}). Each page is a
 * type-13 index page ({@link ProfileIndexPage}) of at most {@value ProfileIndexPage#MAX_ENTRIES} entries.
 *
 * <p>An index is a contiguous run of pages {@code 0..n-1} whose concatenation is ordered by
 * {@code (createdAtMillis, id)}. A rewrite that grows the index writes pages last to first and one that
 * shrinks it first to last, deleting trailing pages last, so a crash in the middle can leave an entry in
 * two pages but never lose one; a load merges and de-duplicates (keeping the larger reference count) and
 * the next rewrite normalises. A gap in the page numbers, a page of another kind, scope or generation,
 * an unparseable page or more than {@value #MAX_PAGES} pages fails the load: a corrupt index is never
 * reported as empty. A missing directory is an index that was never written.</p>
 *
 * <p>Not thread-safe; {@link FileProfileRepository} calls it under its commit lock.</p>
 */
final class FileProfileIndex {

    /** The generation of every index this build writes; an offline rebuild would advance it. */
    static final long GENERATION = 1L;
    /** Pages per index ({@code MAX_PAGES * 128} entries); a larger index is refused, not truncated. */
    static final int MAX_PAGES = 64;

    private final Path base;
    private final Path storeRoot;
    /** Format-2 store: the global pending-intent index must exist; a missing one is corrupt, never empty. */
    private volatile boolean strict;

    FileProfileIndex(final Path base, final Path storeRoot) {
        this.base = base;
        this.storeRoot = storeRoot;
    }

    /**
     * Strict mode, on once the store is format 2: the global {@link ProfileIndexPage.Kind#PENDING_INTENTS} index
     * is created with the marker ({@link #ensurePresent}), so reading it while its directory (or the whole index
     * root) is missing is a corrupt store and fails closed instead of answering "no pending intents". Per-resource
     * indexes may legitimately never have been written and still read as empty.
     */
    void strict(final boolean strict) {
        this.strict = strict;
    }

    /** Creates the directory of one index (forced), so a format-2 store always has its pending-intent index on disk. */
    void ensurePresent(final ProfileIndexPage.Kind kind, final UUID resource) throws IOException {
        final Path directory = directory(kind, resource, null, null);
        FileProfileIo.ensureDirectory(directory, this.storeRoot);
        FileProfileIo.forceDirectory(directory);
    }

    /** The current entries of one index, merged and ordered; empty for an index never written. */
    List<ProfileIndexPage.Entry> entries(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member,
                                         final UUID epoch) throws IOException {
        return load(kind, resource, member, epoch).entries();
    }

    /** Adds or replaces (by id) one entry. Idempotent. */
    void add(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member, final UUID epoch,
             final ProfileIndexPage.Entry entry) throws IOException {
        final Loaded loaded = load(kind, resource, member, epoch);
        final List<ProfileIndexPage.Entry> next = new ArrayList<>(loaded.entries());
        next.removeIf(existing -> existing.id().equals(entry.id()));
        next.add(entry);
        next.sort(null);
        if (next.equals(loaded.entries())) return;
        store(kind, resource, member, epoch, loaded, next);
    }

    /** Removes one entry by id. Idempotent. */
    void remove(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member, final UUID epoch,
                final UUID id) throws IOException {
        final Loaded loaded = load(kind, resource, member, epoch);
        final List<ProfileIndexPage.Entry> next = new ArrayList<>(loaded.entries());
        if (!next.removeIf(existing -> existing.id().equals(id))) return;
        store(kind, resource, member, epoch, loaded, next);
    }

    /** Replaces the whole index with {@code entries} (pin pages, recomputed from the pending intents). */
    void replace(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member, final UUID epoch,
                 final List<ProfileIndexPage.Entry> entries) throws IOException {
        final Loaded loaded = load(kind, resource, member, epoch);
        final List<ProfileIndexPage.Entry> next = new ArrayList<>(entries);
        next.sort(null);
        if (next.equals(loaded.entries())) return;
        store(kind, resource, member, epoch, loaded, next);
    }

    /**
     * One listing page: at most {@link ProfileIndexPage#LISTING_PAGE} entries after {@code cursor}.
     *
     * @throws ProfileOperationException {@code INVALID_REQUEST} for a cursor of another index or generation
     */
    ProfileIndexPage list(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member, final UUID epoch,
                          final ProfileIndexPage.Cursor cursor) throws IOException {
        if (cursor != null) {
            if (cursor.kind() != kind || !cursor.resource().equals(resource)) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the listing cursor belongs to "
                    + cursor.kind() + " of " + cursor.resource() + ", not " + kind + " of " + resource);
            }
            if (cursor.generation() != GENERATION) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the listing cursor is from index"
                    + " generation " + cursor.generation() + "; the index is generation " + GENERATION + ". Restart the listing");
            }
        }
        final List<ProfileIndexPage.Entry> page = new ArrayList<>(ProfileIndexPage.LISTING_PAGE);
        for (final ProfileIndexPage.Entry entry : load(kind, resource, member, epoch).entries()) {
            if (cursor != null && compare(entry, cursor) <= 0) continue;
            page.add(entry);
            if (page.size() == ProfileIndexPage.LISTING_PAGE) break;
        }
        return new ProfileIndexPage(kind, resource, kind.streamScoped() ? member : null,
            kind.streamScoped() ? epoch : null, GENERATION, 0, 0L, page);
    }

    private static int compare(final ProfileIndexPage.Entry entry, final ProfileIndexPage.Cursor cursor) {
        final int byTime = Long.compare(entry.createdAtMillis(), cursor.lastCreatedAtMillis());
        return byTime != 0 ? byTime : entry.id().compareTo(cursor.lastId());
    }

    // ------------------------------------------------------------------ pages

    private record Loaded(List<ProfileIndexPage> pages, List<ProfileIndexPage.Entry> entries) {}

    Path directory(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member, final UUID epoch) {
        Path directory = this.base.resolve(Integer.toString(kind.tag())).resolve(resource.toString());
        if (kind.streamScoped()) {
            Objects.requireNonNull(member, "member");
            Objects.requireNonNull(epoch, "epoch");
            directory = directory.resolve(member.toString()).resolve(epoch.toString());
        }
        return directory;
    }

    private Loaded load(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member, final UUID epoch)
        throws IOException {
        final Path directory = directory(kind, resource, member, epoch);
        if (!Files.isDirectory(directory)) {
            if (this.strict && (kind == ProfileIndexPage.Kind.PENDING_INTENTS || !Files.isDirectory(this.base))) {
                throw corrupt(directory, "is missing although the store is format 2 (the pending-intent index is created with the"
                    + " store marker and only ever emptied, never removed)");
            }
            return new Loaded(List.of(), List.of());
        }
        final TreeMap<Integer, Path> files = new TreeMap<>();
        try (DirectoryStream<Path> found = Files.newDirectoryStream(directory)) {
            for (final Path file : found) {
                final String name = file.getFileName().toString();
                if (name.endsWith(".tmp")) continue;   // an interrupted write
                if (Files.isDirectory(file)) continue;   // never written by this class; pages are files
                if (!name.endsWith(".awfp")) throw corrupt(directory, "holds the unexpected file " + name);
                final int number;
                try {
                    number = Integer.parseInt(name.substring(0, name.length() - ".awfp".length()));
                } catch (final NumberFormatException notAPage) {
                    throw corrupt(directory, "holds the unexpected file " + name);
                }
                if (number < 0 || number >= MAX_PAGES) throw corrupt(directory, "has page " + number + " outside 0.." + (MAX_PAGES - 1));
                files.put(number, file);
            }
        }
        final List<ProfileIndexPage> pages = new ArrayList<>(files.size());
        final Map<UUID, ProfileIndexPage.Entry> merged = new LinkedHashMap<>();
        int expected = 0;
        for (final Map.Entry<Integer, Path> file : files.entrySet()) {
            if (file.getKey() != expected) throw corrupt(directory, "is missing page " + expected);
            expected++;
            final String where = file.getValue().toString();
            final ProfileIndexPage page;
            try {
                final byte[] bytes = FileProfileIo.readBounded(file.getValue(), ProfileRecords.MAX_CONTROL_PAYLOAD + ProfileRecords.OVERHEAD);
                page = ProfileRecords.indexPage2(ProfileRecords.unframe(bytes, ProfileRecords.FORMAT_2, ProfileRecords.TYPE_PAGE,
                    where), kind, resource, where);
            } catch (final IOException unreadable) {
                throw corrupt(directory, "page " + file.getKey() + " cannot be read (" + unreadable.getMessage() + ")");
            }
            if (page.page() != file.getKey() || page.generation() != GENERATION || !Objects.equals(page.member(), kind.streamScoped() ? member : null)
                || !Objects.equals(page.epoch(), kind.streamScoped() ? epoch : null)) {
                throw corrupt(directory, "page " + file.getKey() + " has another number, scope or generation");
            }
            pages.add(page);
            for (final ProfileIndexPage.Entry entry : page.entries()) {
                merged.merge(entry.id(), entry, (left, right) -> left.count() >= right.count() ? left : right);
            }
        }
        final List<ProfileIndexPage.Entry> entries = new ArrayList<>(merged.values());
        entries.sort(null);
        return new Loaded(List.copyOf(pages), List.copyOf(entries));
    }

    private void store(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member, final UUID epoch,
                       final Loaded old, final List<ProfileIndexPage.Entry> next) throws IOException {
        if (next.size() > MAX_PAGES * ProfileIndexPage.MAX_ENTRIES) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, kind + " index of " + resource
                + " would exceed " + (MAX_PAGES * ProfileIndexPage.MAX_ENTRIES) + " entries; nothing was written");
        }
        final Path directory = directory(kind, resource, member, epoch);
        FileProfileIo.ensureDirectory(directory, this.storeRoot);
        final List<List<ProfileIndexPage.Entry>> chunks = new ArrayList<>();
        for (int from = 0; from < next.size(); from += ProfileIndexPage.MAX_ENTRIES) {
            chunks.add(next.subList(from, Math.min(next.size(), from + ProfileIndexPage.MAX_ENTRIES)));
        }
        final int oldPages = old.pages().size();
        final boolean grows = next.size() >= old.entries().size();
        final List<Integer> order = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) order.add(grows ? chunks.size() - 1 - i : i);
        for (final int number : order) {
            final List<ProfileIndexPage.Entry> chunk = chunks.get(number);
            final ProfileIndexPage previous = number < oldPages ? old.pages().get(number) : null;
            if (previous != null && previous.entries().equals(chunk)) continue;
            final long revision = previous == null ? 0L : CoopWriteGuard.next(previous.revision(), "index page");
            final ProfileIndexPage page = new ProfileIndexPage(kind, resource, kind.streamScoped() ? member : null,
                kind.streamScoped() ? epoch : null, GENERATION, number, revision, chunk);
            final Path file = directory.resolve(number + ".awfp");
            FileProfileIo.writeDurable(file, ProfileRecords.frame2(ProfileRecords.TYPE_PAGE, revision,
                ProfileRecords.indexPagePayload2(page), file.toString()));
        }
        for (int number = oldPages - 1; number >= chunks.size(); number--) {
            Files.deleteIfExists(directory.resolve(number + ".awfp"));
        }
        if (oldPages > chunks.size()) FileProfileIo.forceDirectory(directory);
    }

    private static IOException corrupt(final Path directory, final String detail) {
        return new IOException("the profile index " + directory + " " + detail + "; it is treated as corrupt (never as empty)"
            + " and the operations that need it are refused until an offline rebuild");
    }
}
