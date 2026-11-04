package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A world's committed chunks on the FILE backend: a chunk index per generation in a
 * {@link GenerationStore}, chunk bytes in a content-addressed {@link ObjectStore}.
 *
 * <p>Index format. A generation holds either a full index (blob {@code index}, one
 * {@code "x z <object>"} line per chunk, {@code -} for a deleted chunk) and is chained to nothing,
 * or a delta (blob {@code delta}, chained to the previous generation through the manifest's
 * {@code @base} line): one line per chunk whose entry changed, {@code "x z <object>"} for a new
 * object, {@code "x z -"} for a deletion that shadows the base, {@code "x z drop"} for a chunk
 * that leaves the index. The committed state of a generation is its chain replayed oldest to
 * newest from the nearest full index. Stores written before AWF-7 hold full indexes only
 * (manifest format 1) and open unchanged; their current generation is the first delta's base, so
 * nothing is rewritten to migrate.</p>
 *
 * <p>Deltas are opt-in ({@code aurora.awf.delta-generations}, default off, RESTART_REQUIRED;
 * {@link Compaction#deltaGenerations()}). With them off every commit writes a full index in a
 * format 1 manifest, byte for byte what builds before AWF-7 wrote, so such a build (which is what
 * the auto-updater's rollback restores) opens the store. A build before AWF-7 cannot open a store
 * whose current generation is a delta (it reports the manifest as corrupt and the world fails to
 * load). <b>Rollback sequence once deltas have been written:</b> set
 * {@code aurora.awf.delta-generations = false}, restart on the current build, and let one commit
 * happen per AWF world (a {@code save-all flush} after a write, or the next commit interval; the
 * first commit with the flag off is always a full index — {@link #chainLength()} reads 0
 * afterwards); then the older jar opens every store. Two things make that sequence complete:
 * a writable open with the flag off compacts a chained store at once (so every store the server
 * opens — region, entities and poi of every dimension, clean or not — is converted, not only
 * those a later commit happens to touch; maintenance, flush and shutdown skip clean stores), and
 * {@code /awf compact <world|all>} forces the same on every open store and prints per store
 * whether its current generation is a full index ({@link #fullIndex()}), which is the check to
 * run before downgrading. Only stores the server has opened are converted: a world that is not
 * loaded keeps its chain until it loads on this build.</p>
 *
 * <p><b>Irreversible on downgrade:</b> a build before AWF-7 skips the retained delta generations
 * the compaction leaves on disk (its retained-generation read treats a format 2 manifest as
 * damaged) and its writable open then deletes every object only those generations reference. The
 * retained rollback points that were deltas are lost for good; the current state survives. Back
 * up the {@code <folder>.awf} directories before downgrading if those rollback points matter. This
 * build does not rewrite the retained generations as full indexes (not simple: a child's base
 * link covers its base's manifest hash, so every retained generation and link would have to be
 * replaced together). A store with a delta chain logs one line at open.</p>
 *
 * <p>Compaction: a commit writes a full index instead of a delta when it is the first, when the
 * mode is FULL, when the chain already has {@link Compaction#maxChainLength} hops, or when the
 * entries replayed on open (the chain's deltas plus this one) would reach the size of the full
 * index; the chain then ends and the old one is dropped by retention. Commit bytes and hashing
 * are proportional to the changed chunks between compactions; opening costs one full index plus
 * at most one index worth of delta entries.</p>
 *
 * <p>Commit sequence, for every mode: write objects (each to a temporary file in its shard) → read
 * back and verify the objects this commit wrote (CHECKPOINT: every object the new index
 * references) → {@link ObjectStore#sync} (one pass of forces over the new objects, then each moved
 * into place, then each touched directory forced once) → commit the index or delta as a new
 * generation, which is the single commit point → drop objects no retained generation references.
 * The steps after the commit point (this drop, and the generation store's deletion of unretained
 * generations) never fail the commit: what they could not delete is logged and retried by the
 * next commit. No index is forced before every object it references has been; a crash anywhere
 * before the generation commit leaves the previous generation authoritative, and the new objects
 * are unreferenced and are removed later. A delta's base is a committed generation that retention
 * keeps on disk as long as the delta is retained, and {@link GenerationStore#readBase} checks the
 * hash link, so a committed delta always replays against the manifest it was written against.</p>
 *
 * <p>Garbage: an object is live while the replayed state of any retained generation references
 * it. An entry of a chain ancestor that every retained generation has overwritten references
 * nothing live (it is never read: replay only dereferences the final state), so its object is
 * collected like any other unreferenced one.</p>
 *
 * <p>Blocking; must not run on a region thread ({@link GenerationStore} refuses). {@link AwfWorld}
 * calls it from a storage executor.</p>
 */
public final class AwfWorldStore implements AwfStore {

    static final String INDEX_BLOB = "index";
    static final String DELTA_BLOB = "delta";
    /**
     * Index value for a chunk this world deleted. It shadows the base (a region file or template)
     * the way a write does, so a deleted chunk does not reappear from underneath.
     */
    static final String TOMBSTONE = "-";
    /** Delta value for a chunk that leaves the index entirely ({@code removed}); never in a full index. */
    static final String DROP = "drop";

    /**
     * Whether commits may write deltas at all, and when one writes a full index instead; see the
     * class comment. {@link #FULL_ONLY} is the shipped default.
     */
    record Compaction(boolean deltaGenerations, int maxChainLength) {
        static final Compaction FULL_ONLY = new Compaction(false, 32);
        static final Compaction DELTAS = new Compaction(true, 32);

        Compaction {
            if (maxChainLength < 1) throw new IllegalArgumentException("maxChainLength must be at least 1");
        }

        /** Deltas on, with this hop limit; for tests of the chain. */
        Compaction(final int maxChainLength) {
            this(true, maxChainLength);
        }
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SourbyCraft/AWF");

    /** The store's directory, for log lines. */
    private final Path root;
    private final GenerationStore generations;
    private final ObjectStore objects;
    private final WorldRole role;
    private final int retainedGenerations;
    private final Compaction compaction;
    private volatile SortedMap<ChunkKey, String> index;
    /** The committed generation {@link #index} is the state of; 0 before the first commit. Guarded by this. */
    private long generation;
    /** Delta hops from the committed generation down to its full index, and the entries they hold. Guarded by this. */
    private int chainLength;
    private long chainEntries;
    /** Whether the last commit wrote a full index; for tests and the benchmark. Guarded by this. */
    private boolean lastCommitCompacted;
    /**
     * Object names each retained generation's replayed state references, newest first. Kept in
     * memory so a commit neither re-reads the retained indexes nor scans the object directory.
     */
    private final java.util.ArrayDeque<Set<String>> retainedNames = new java.util.ArrayDeque<>();
    /**
     * Objects written by commits that failed before their generation committed, and objects a
     * collection after a commit could not delete; the next commit's collection checks them.
     */
    private final Set<String> orphanCandidates = new HashSet<>();
    /** Failed deletions in {@link #retainAndCollect}; guarded by this. */
    private final GenerationStore.AfterCommitWarning collectWarning = new GenerationStore.AfterCommitWarning();
    /** Set by {@link #close}; guarded by this store's monitor. */
    private boolean closed;

    private AwfWorldStore(final Path root, final GenerationStore generations, final ObjectStore objects, final WorldRole role,
                          final int retainedGenerations, final Compaction compaction, final Resolved current) {
        this.root = root;
        this.generations = generations;
        this.objects = objects;
        this.role = role;
        this.retainedGenerations = retainedGenerations;
        this.compaction = compaction;
        this.index = current.index;
        this.generation = current.number;
        this.chainLength = current.hops;
        this.chainEntries = current.entries;
    }

    /**
     * Opens (or creates) a world store that writes full indexes only (deltas off).
     *
     * @param retainedGenerations committed generations kept for recovery, at least 1
     */
    public static AwfWorldStore open(final Path root, final WorldRole role, final int retainedGenerations)
        throws IOException {
        return open(root, role, retainedGenerations, false);
    }

    /**
     * Opens (or creates) a world store.
     *
     * @param retainedGenerations committed generations kept for recovery, at least 1
     * @param deltaGenerations {@code aurora.awf.delta-generations}: whether commits may write
     *                         deltas; off writes full indexes an older build can read
     */
    public static AwfWorldStore open(final Path root, final WorldRole role, final int retainedGenerations,
                                     final boolean deltaGenerations) throws IOException {
        return open(root, role, retainedGenerations, stage -> {}, Durability.DISK,
            deltaGenerations ? Compaction.DELTAS : Compaction.FULL_ONLY);
    }

    /** As {@link #open(Path, WorldRole, int)}, with a crash-injection hook, deltas ON, for tests. */
    static AwfWorldStore open(final Path root, final WorldRole role, final int retainedGenerations,
                              final GenerationStore.StageHook hook) throws IOException {
        return open(root, role, retainedGenerations, hook, Durability.DISK);
    }

    /** As above, with the durability seam both stores force through, deltas ON, for tests of the order. */
    static AwfWorldStore open(final Path root, final WorldRole role, final int retainedGenerations,
                              final GenerationStore.StageHook hook, final Durability durability) throws IOException {
        return open(root, role, retainedGenerations, hook, durability, Compaction.DELTAS);
    }

    /** As above, with the compaction policy, for tests of the chain. */
    static AwfWorldStore open(final Path root, final WorldRole role, final int retainedGenerations,
                              final GenerationStore.StageHook hook, final Durability durability,
                              final Compaction compaction) throws IOException {
        final GenerationStore generations = GenerationStore.open(root.resolve("generations-root"), role,
            retainedGenerations, hook, durability);
        final ObjectStore objects = new ObjectStore(root.resolve("objects"), durability, role);
        final Map<Long, Resolved> resolved = new HashMap<>();
        final GenerationStore.Generation current = generations.read();
        // The current generation must replay; anything else fails the open, as before.
        final Resolved state = current == null ? Resolved.EMPTY : resolve(generations, current, resolved);
        final AwfWorldStore store = new AwfWorldStore(root, generations, objects, role, retainedGenerations, compaction, state);
        for (final GenerationStore.Generation retained : generations.readRetained()) {
            try {
                store.retainedNames.addLast(names(resolve(generations, retained, resolved).index));
            } catch (final IOException damaged) {
                // A retained generation whose chain no longer replays keeps nothing alive, like one
                // whose own blobs fail verification.
            }
        }
        if (store.retainedNames.isEmpty() && current != null) store.retainedNames.add(names(state.index));
        if (state.hops > 0) {
            LOGGER.info("AWF store {} opened at generation {} with a delta chain of {} hop(s), {} entries replayed;"
                + " a build before AWF-7 cannot open it until a compaction{}", root, state.number, state.hops, state.entries,
                compaction.deltaGenerations() ? "" : role.acceptsCommits() ? " (delta-generations is off: compacting now)"
                    : " (delta-generations is off: a writable open compacts it)");
        }
        // Deltas off and a chain on disk: convert now, so that every store this server opens is
        // readable by a build without the chain parser, whether or not a commit ever touches it.
        if (role.acceptsCommits() && !compaction.deltaGenerations() && state.hops > 0) {
            final CommitResult compacted = store.compact();
            LOGGER.info("AWF store {} compacted at open to generation {} (delta-generations is off)", root,
                compacted == null ? state.number : compacted.generation());
        }
        // The one full scan: objects a crash left unreferenced. Only a writable store collects.
        if (role.acceptsCommits()) {
            final Set<String> live = new HashSet<>();
            store.retainedNames.forEach(live::addAll);
            objects.retainOnly(live);
        }
        return store;
    }

    /** A generation's replayed state and how far its chain reaches. */
    private record Resolved(long number, SortedMap<ChunkKey, String> index, int hops, long entries) {
        static final Resolved EMPTY = new Resolved(0L, Collections.unmodifiableSortedMap(new TreeMap<>()), 0, 0L);
    }

    /**
     * Replays a generation's chain: walks down through {@link GenerationStore#readBase} (which
     * checks each hash link) to a full index or an already resolved generation, then applies the
     * deltas oldest to newest. Every generation on the way is memoised in {@code resolved}.
     */
    private static Resolved resolve(final GenerationStore generations, final GenerationStore.Generation top,
                                    final Map<Long, Resolved> resolved) throws IOException {
        final List<GenerationStore.Generation> chain = new ArrayList<>();
        Resolved base = null;
        for (GenerationStore.Generation cursor = top; cursor != null; cursor = generations.readBase(cursor)) {
            final Resolved known = resolved.get(cursor.number());
            if (known != null) {
                base = known;
                break;
            }
            chain.add(cursor);
            kind(cursor); // a delta without a base, or an index with one, is corrupt before anything is replayed
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            final GenerationStore.Generation gen = chain.get(i);
            final Resolved next;
            if (kind(gen).equals(INDEX_BLOB)) {
                next = new Resolved(gen.number(), Collections.unmodifiableSortedMap(decodeIndex(gen)), 0, 0L);
            } else {
                final SortedMap<ChunkKey, String> state = new TreeMap<>(Objects.requireNonNull(base).index);
                final int applied = applyDelta(state, gen);
                next = new Resolved(gen.number(), Collections.unmodifiableSortedMap(state), base.hops + 1, base.entries + applied);
            }
            resolved.put(gen.number(), next);
            base = next;
        }
        return base;
    }

    /** Which blob a generation carries, after checking it is chained consistently. */
    private static String kind(final GenerationStore.Generation gen) throws GenerationStore.CorruptGenerationException {
        final boolean index = gen.blobs().containsKey(INDEX_BLOB);
        final boolean delta = gen.blobs().containsKey(DELTA_BLOB);
        if (index == delta) {
            throw new GenerationStore.CorruptGenerationException("generation " + gen.number() + " has "
                + (index ? "both an index and a delta" : "no index"));
        }
        if (index && gen.hasBase()) {
            throw new GenerationStore.CorruptGenerationException("generation " + gen.number() + " holds a full index but is chained to " + gen.base());
        }
        if (delta && !gen.hasBase()) {
            throw new GenerationStore.CorruptGenerationException("generation " + gen.number() + " holds a delta but is chained to nothing");
        }
        return index ? INDEX_BLOB : DELTA_BLOB;
    }

    private static Set<String> names(final SortedMap<ChunkKey, String> index) {
        final Set<String> names = new HashSet<>(index.values());
        names.remove(TOMBSTONE);
        return names;
    }

    @Override
    public long generation() throws IOException {
        return this.generations.currentGeneration();
    }

    /** Delta hops from the committed generation to its full index; 0 right after a compaction. */
    synchronized int chainLength() {
        return this.chainLength;
    }

    /** Whether the committed generation is a full index (or there is none): what a pre-AWF-7 build reads. */
    @Override
    public synchronized boolean fullIndex() {
        return this.chainLength == 0;
    }

    /**
     * Commits the current state as a full index, ending the chain. An empty store (no generation)
     * has nothing to compact and returns {@code null}; so does one whose generation is full already.
     */
    @Override
    public synchronized CommitResult compact() throws IOException {
        if (this.generation == 0 || this.chainLength == 0) return null;
        return commit(Map.of(), Set.of(), Set.of(), PersistenceMode.INCREMENTAL, true);
    }

    /** Whether the last commit wrote a full index (a compaction) rather than a delta. */
    synchronized boolean lastCommitCompacted() {
        return this.lastCommitCompacted;
    }

    /** Chunks with committed bytes; deleted chunks are not included. */
    @Override
    public Set<ChunkKey> keys() {
        final Set<ChunkKey> live = new java.util.TreeSet<>();
        this.index.forEach((key, name) -> {
            if (!TOMBSTONE.equals(name)) live.add(key);
        });
        return live;
    }

    /** Chunks this world deleted: they read as absent and shadow the base. */
    @Override
    public Set<ChunkKey> deleted() {
        final Set<ChunkKey> deleted = new java.util.TreeSet<>();
        this.index.forEach((key, name) -> {
            if (TOMBSTONE.equals(name)) deleted.add(key);
        });
        return deleted;
    }

    /** Whether the committed state says anything about a chunk: bytes or a deletion. */
    @Override
    public boolean has(final ChunkKey key) {
        return this.index.containsKey(key);
    }

    @Override
    public Optional<byte[]> read(final ChunkKey key) throws IOException {
        final String name = this.index.get(key);
        return name == null || TOMBSTONE.equals(name) ? Optional.empty() : Optional.of(this.objects.get(name));
    }

    /**
     * Commits a new generation: the current index with {@code changed} applied and {@code removed}
     * dropped.
     *
     * @param changed chunk bytes that differ from the committed state; arrays are not retained
     * @param removed chunks to drop from the world
     */
    public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                               final PersistenceMode mode) throws IOException {
        return commit(changed, removed, Set.of(), mode);
    }

    /**
     * As {@link #commit(Map, Set, PersistenceMode)}, also recording {@code deleted} chunks as
     * deletions that shadow the base.
     */
    @Override
    public synchronized CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                            final Set<ChunkKey> deleted, final PersistenceMode mode)
        throws IOException {
        return commit(changed, removed, deleted, mode, false);
    }

    /** @param forceFull write a full index whatever the policy says ({@link #compact()}) */
    private synchronized CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                             final Set<ChunkKey> deleted, final PersistenceMode mode,
                                             final boolean forceFull) throws IOException {
        if (mode == PersistenceMode.READ_ONLY || !this.role.acceptsCommits()) {
            throw new IllegalStateException("a " + this.role + " world in " + mode + " mode does not accept commits");
        }
        if (this.closed) throw new IllegalStateException("this AWF store was closed; it accepts no more commits");
        final SortedMap<ChunkKey, String> next = new TreeMap<>(this.index);
        removed.forEach(next::remove);
        deleted.forEach(key -> next.put(key, TOMBSTONE));

        int written = 0;
        long bytes = 0;
        final Set<String> toVerify = new HashSet<>();
        final long committed;
        final boolean full;
        final SortedMap<ChunkKey, String> delta;
        try {
            for (final Map.Entry<ChunkKey, byte[]> chunk : changed.entrySet()) {
                final ObjectStore.Written w = this.objects.put(chunk.getValue(), mode == PersistenceMode.FULL);
                next.put(chunk.getKey(), w.name());
                if (w.bytes() > 0) {
                    written++;
                    bytes += w.bytes();
                    toVerify.add(w.name());
                }
            }
            if (mode == PersistenceMode.FULL) {
                // FULL rewrites unchanged chunks as well, from their committed (verified) bytes.
                for (final Map.Entry<ChunkKey, String> chunk : next.entrySet()) {
                    if (changed.containsKey(chunk.getKey()) || TOMBSTONE.equals(chunk.getValue())) continue;
                    final ObjectStore.Written w = this.objects.put(this.objects.get(chunk.getValue()), true);
                    written++;
                    bytes += w.bytes();
                    toVerify.add(w.name());
                }
            }
            if (mode == PersistenceMode.CHECKPOINT) {
                toVerify.addAll(next.values());
                toVerify.remove(TOMBSTONE);
            }
            // Verify before the commit point: a generation must never reference an object that
            // cannot be read back.
            for (final String name : toVerify) {
                this.objects.get(name);
            }
            // Every object the index will reference is made durable and named here, in one pass,
            // before anything that references it is written.
            this.objects.sync();

            // The delta: only the entries this commit changes, over the keys it touched.
            delta = delta(this.index, next, changed.keySet(), removed, deleted);
            full = forceFull || !this.compaction.deltaGenerations() || this.generation == 0 || mode == PersistenceMode.FULL
                || this.chainLength >= this.compaction.maxChainLength()
                || this.chainEntries + delta.size() >= next.size();
            committed = full
                ? this.generations.commit(Map.of(INDEX_BLOB, encodeIndex(next)))
                : this.generations.commit(Map.of(DELTA_BLOB, encodeDelta(delta)), this.generation);
        } catch (final IOException | RuntimeException failed) {
            // Whatever this commit wrote may now be referenced by nothing; the next successful
            // commit checks it. A crash instead is covered by the scan when the store is opened.
            this.orphanCandidates.addAll(toVerify);
            throw failed;
        }
        this.index = Collections.unmodifiableSortedMap(next);
        this.generation = committed;
        this.lastCommitCompacted = full;
        if (full) {
            this.chainLength = 0;
            this.chainEntries = 0L;
        } else {
            this.chainLength++;
            this.chainEntries += delta.size();
        }

        final int removedObjects = retainAndCollect(next);
        return new CommitResult(committed, next.size(), written, bytes, removedObjects);
    }

    /**
     * After a commit: records the new generation's objects as retained and collects only what can
     * have become garbage, the objects of generations that just fell out of retention and those of
     * earlier failed commits, minus everything still retained. Returns how many objects it deleted.
     *
     * <p>Runs past the commit point, so it never throws: an object it cannot delete stays a
     * candidate for the next commit, and the failure is logged (at most once a minute per store).
     * Reporting the commit as failed would make the caller retry it, committing yet another
     * generation.</p>
     */
    private int retainAndCollect(final SortedMap<ChunkKey, String> committedIndex) {
        this.retainedNames.addFirst(names(committedIndex));
        final Set<String> candidates = new HashSet<>(this.orphanCandidates);
        this.orphanCandidates.clear();
        while (this.retainedNames.size() > this.retainedGenerations) {
            candidates.addAll(this.retainedNames.removeLast());
        }
        int removedObjects = 0;
        if (!candidates.isEmpty()) {
            final Set<String> live = new HashSet<>();
            this.retainedNames.forEach(live::addAll);
            IOException firstFailure = null;
            for (final String name : candidates) {
                if (live.contains(name)) continue;
                try {
                    if (this.objects.delete(name)) removedObjects++;
                } catch (final IOException failed) {
                    this.orphanCandidates.add(name);
                    if (firstFailure == null) firstFailure = failed;
                }
            }
            if (firstFailure != null) {
                this.collectWarning.failed("AWF store " + this.root + " committed generation " + this.generation + ", but "
                    + this.orphanCandidates.size() + " object(s) no retained generation references could not be deleted;"
                    + " the commit stands and the next commit tries again", firstFailure);
            }
        }
        return removedObjects;
    }

    /**
     * The generations {@link #rollbackTo} accepts, newest first: the retained committed
     * generations whose chain still replays (the current one included). A read; any role.
     */
    public synchronized List<Long> rollbackPoints() throws IOException {
        final List<Long> out = new ArrayList<>();
        final Map<Long, Resolved> resolved = new HashMap<>();
        for (final GenerationStore.Generation retained : this.generations.readRetained()) {
            try {
                resolve(this.generations, retained, resolved);
                out.add(retained.number());
            } catch (final IOException damaged) {
                // A generation that does not replay is no rollback point.
            }
        }
        return List.copyOf(out);
    }

    /**
     * Point-in-time rollback for operator recovery: commits, as a new generation on top, the
     * state retained generation {@code target} committed. Nothing is rewritten: the generations
     * after {@code target} stay on disk until retention drops them, so a rollback can itself be
     * rolled back while they are retained. The new generation is always a full index (it ends any
     * delta chain), and every object it references is read back and verified first, so a damaged
     * or missing object fails the rollback before its commit point and leaves the store as it was.
     *
     * <p>Only for a store no running world is using: a loaded world keeps its own dirty chunks and
     * would commit over the rollback. Blocking, off the region threads, like {@link #commit}.</p>
     *
     * @return the commit, or {@code null} when {@code target} is already the current generation
     * @throws IllegalArgumentException when {@code target} is not one of {@link #rollbackPoints()}
     */
    public synchronized CommitResult rollbackTo(final long target) throws IOException {
        if (!this.role.acceptsCommits()) {
            throw new IllegalStateException("a " + this.role + " world does not accept commits");
        }
        if (this.closed) throw new IllegalStateException("this AWF store was closed; it accepts no more commits");
        if (target == this.generation && this.generation != 0) return null;
        GenerationStore.Generation point = null;
        for (final GenerationStore.Generation retained : this.generations.readRetained()) {
            if (retained.number() == target) point = retained;
        }
        if (point == null) {
            throw new IllegalArgumentException("generation " + target + " is not a retained rollback point; retained: "
                + rollbackPoints());
        }
        final SortedMap<ChunkKey, String> state = resolve(this.generations, point, new HashMap<>()).index;
        for (final String name : names(state)) {
            this.objects.get(name);                    // read back and verified before the commit point
        }
        final long committed = this.generations.commit(Map.of(INDEX_BLOB, encodeIndex(state)));
        this.index = Collections.unmodifiableSortedMap(new TreeMap<>(state));
        this.generation = committed;
        this.lastCommitCompacted = true;
        this.chainLength = 0;
        this.chainEntries = 0L;
        final int removedObjects = retainAndCollect(state);
        LOGGER.warn("AWF store rolled back to the state of generation {} as generation {} ({} chunks)", target,
            committed, state.size());
        return new CommitResult(committed, state.size(), 0, 0L, removedObjects);
    }

    /**
     * Refuses every later commit; reads still work. Synchronized with {@link #commit}, so it
     * returns only after a commit that is running has finished.
     */
    @Override
    public synchronized void close() {
        this.closed = true;
    }

    /**
     * The entries that differ between two indexes over the touched keys: a new value, a
     * tombstone, or {@link #DROP} for a key that left. Keys that end up unchanged (a chunk
     * rewritten with identical bytes) are not in the delta.
     */
    static SortedMap<ChunkKey, String> delta(final SortedMap<ChunkKey, String> before, final SortedMap<ChunkKey, String> after,
                                             final Set<ChunkKey> changed, final Set<ChunkKey> removed, final Set<ChunkKey> deleted) {
        final SortedMap<ChunkKey, String> delta = new TreeMap<>();
        for (final ChunkKey key : removed) {
            if (before.containsKey(key) && !after.containsKey(key)) delta.put(key, DROP);
        }
        for (final Set<ChunkKey> keys : List.of(changed, deleted)) {
            for (final ChunkKey key : keys) {
                final String value = after.get(key);
                if (value != null && !value.equals(before.get(key))) delta.put(key, value);
            }
        }
        return delta;
    }

    static byte[] encodeIndex(final SortedMap<ChunkKey, String> index) {
        final StringBuilder out = new StringBuilder(index.size() * 80);
        for (final Map.Entry<ChunkKey, String> e : index.entrySet()) {
            out.append(e.getKey().x()).append(' ').append(e.getKey().z()).append(' ').append(e.getValue()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Same line form as the index; {@link #DROP} is the only extra value. */
    static byte[] encodeDelta(final SortedMap<ChunkKey, String> delta) {
        return encodeIndex(delta);
    }

    static SortedMap<ChunkKey, String> decodeIndex(final GenerationStore.Generation generation) throws IOException {
        final byte[] bytes = generation.blobs().get(INDEX_BLOB);
        if (bytes == null) throw new GenerationStore.CorruptGenerationException("generation " + generation.number() + " has no index");
        return decodeEntries(bytes, false);
    }

    /** Applies a generation's delta to {@code state}; returns how many entries it held. */
    static int applyDelta(final SortedMap<ChunkKey, String> state, final GenerationStore.Generation generation) throws IOException {
        final byte[] bytes = generation.blobs().get(DELTA_BLOB);
        if (bytes == null) throw new GenerationStore.CorruptGenerationException("generation " + generation.number() + " has no delta");
        final SortedMap<ChunkKey, String> delta = decodeEntries(bytes, true);
        for (final Map.Entry<ChunkKey, String> entry : delta.entrySet()) {
            if (DROP.equals(entry.getValue())) {
                state.remove(entry.getKey());
            } else {
                state.put(entry.getKey(), entry.getValue());
            }
        }
        return delta.size();
    }

    /**
     * Parses index or delta lines. The value must be an object name (lower-case SHA-256 hex), a
     * tombstone, or in a delta a drop: anything else, such as a path, makes the generation corrupt,
     * so a store copied from elsewhere can never name a file outside its object directory.
     */
    private static SortedMap<ChunkKey, String> decodeEntries(final byte[] bytes, final boolean delta) throws IOException {
        final SortedMap<ChunkKey, String> index = new TreeMap<>();
        for (final String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            final String[] parts = line.split(" ");
            try {
                if (parts.length != 3 || !(ObjectStore.NAME.matcher(parts[2]).matches() || TOMBSTONE.equals(parts[2])
                    || (delta && DROP.equals(parts[2])))) {
                    throw new NumberFormatException(line);
                }
                index.put(new ChunkKey(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])), parts[2]);
            } catch (final NumberFormatException malformed) {
                throw new GenerationStore.CorruptGenerationException("malformed " + (delta ? "delta" : "index") + " line: " + line);
            }
        }
        return index;
    }
}
