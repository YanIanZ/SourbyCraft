package dev.iyanz.sourbycraft.awf;

import dev.iyanz.sourbycraft.execution.ExecutionLane;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * The FILE backend's atomic persistence primitive for Aurora World Fabric.
 *
 * <p>Implements the commit sequence from {@code aurora-world-fabric.md}: an immutable snapshot is
 * written to a temporary generation, verified by reading it back, and only then published by
 * replacing the {@code CURRENT} manifest pointer. That replacement is the single commit point. A
 * crash at any earlier step leaves the previous generation authoritative, and leftovers are removed
 * the next time the store is opened.</p>
 *
 * <p>On disk: {@code CURRENT} holds {@code "<N>\t<sha256 of N's manifest>"}; {@code generations/N/}
 * holds the blobs and {@code manifest}. A manifest is UTF-8 lines. Format 1 is blob lines only,
 * one {@code "<blob>\t<length>\t<sha256>"} per blob: every store written before AWF-7, and every
 * generation this build commits with no base — byte for byte what the pre-AWF-7 writer produced,
 * so such a generation is readable by those builds. Format 2 is written only for a chained
 * generation: {@code "@format\t2"}, then {@code "@base\t<M>\t<sha256 of M's manifest>"}, then the
 * blob lines. A pre-AWF-7 build reports a format 2 manifest as corrupt (its parser accepts
 * three-field lines only), never misreads it. The base line chains a generation to an earlier
 * committed one: what the chain means is the caller's business
 * ({@link AwfWorldStore} stores a delta of its chunk index against the base); what this store
 * promises is that a generation's base is kept on disk as long as the generation is, that
 * {@link #readBase} returns the very manifest the child was chained to (hash-linked from
 * {@code CURRENT} down the chain), and that a base is always a committed generation older than its
 * child. A manifest with an {@code @} line this build does not know is reported as corrupt rather
 * than misread.</p>
 *
 * <p>Durability order within a commit: a directory chain created for the first commit is made
 * durable first (each new directory's parent forced); every blob and the manifest are written,
 * then forced in one pass, then the temporary generation directory is forced (their entries);
 * after verification the directory is renamed and {@code generations/} forced; the pointer is
 * written, forced, renamed and the root forced. Whatever a snapshot references (for
 * {@link AwfWorldStore}, its objects) must be durable before {@link #commit} is called: this store
 * forces nothing on the caller's behalf. A directory force that fails fails the commit
 * ({@link Durability#DISK}).</p>
 *
 * <p>Retention: the newest {@code retainedGenerations} committed generations are kept, plus every
 * generation one of them is chained to, transitively; everything else is deleted after a commit.
 * {@link #readRetained} returns the retained ones only, never a chain-only ancestor.</p>
 *
 * <p>Scope: this commits whole snapshots of small blobs. {@link AwfWorldStore} builds the FILE
 * backend on it: the per-generation blob is the chunk index (or a delta of it), and chunk bytes
 * live in its content-addressed object store, which is where INCREMENTAL, CHECKPOINT and FULL
 * differ.</p>
 *
 * <p>Ownership: the store never sees live region-owned state, only the byte snapshot a caller
 * already took. Every blocking method refuses to run on a region tick thread; region code must
 * go through {@link #commitAsync} with a storage executor.</p>
 */
public final class GenerationStore {

    static final String CURRENT = "CURRENT";
    static final String GENERATIONS = "generations";
    static final String MANIFEST = "manifest";
    /** Manifest header keys; a blob name can never start with {@code @}. */
    static final String FORMAT_KEY = "@format";
    static final String BASE_KEY = "@base";
    /** The manifest format this build writes; format 1 is the header-less original. */
    static final int FORMAT = 2;
    private static final Pattern BLOB_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** Points in the commit sequence, for crash-injection tests. */
    enum Stage { SNAPSHOT_WRITTEN, VERIFIED, GENERATION_PUBLISHED, COMMITTED }

    /** Test hook: throwing here simulates a crash at that stage. */
    @FunctionalInterface
    interface StageHook {
        void reached(Stage stage) throws IOException;
    }

    /**
     * A committed generation's contents.
     *
     * @param base the generation this one is chained to, {@code 0} for none
     * @param manifestSha the SHA-256 of this generation's manifest, what a child chains to
     * @param baseManifestSha the base's manifest SHA-256 as recorded at commit time, or {@code null}
     */
    public record Generation(long number, long base, String manifestSha, String baseManifestSha,
                             Map<String, byte[]> blobs) {
        public boolean hasBase() {
            return this.base > 0;
        }
    }

    /** Thrown when committed data does not match its manifest. */
    public static final class CorruptGenerationException extends IOException {
        private static final long serialVersionUID = 1L;

        CorruptGenerationException(final String message) {
            super(message);
        }
    }

    private final Path root;
    private final WorldRole role;
    private final int retainedGenerations;
    private final StageHook hook;
    private final Durability durability;

    /**
     * Opens a store, removing anything an interrupted commit left behind.
     *
     * @param root the world's storage directory
     * @param role the world's role; roles that do not accept commits make {@link #commit} refuse
     * @param retainedGenerations committed generations kept on disk, at least 1
     */
    public static GenerationStore open(final Path root, final WorldRole role, final int retainedGenerations)
        throws IOException {
        return open(root, role, retainedGenerations, stage -> {});
    }

    static GenerationStore open(final Path root, final WorldRole role, final int retainedGenerations,
                                final StageHook hook) throws IOException {
        return open(root, role, retainedGenerations, hook, Durability.DISK);
    }

    /** As {@link #open(Path, WorldRole, int)}, with a crash hook and a durability seam for tests. */
    static GenerationStore open(final Path root, final WorldRole role, final int retainedGenerations,
                                final StageHook hook, final Durability durability) throws IOException {
        refuseRegionThread();
        final GenerationStore store = new GenerationStore(root, role, retainedGenerations, hook, durability);
        store.recover();
        return store;
    }

    private GenerationStore(final Path root, final WorldRole role, final int retainedGenerations,
                            final StageHook hook, final Durability durability) {
        this.root = Objects.requireNonNull(root, "root");
        this.role = Objects.requireNonNull(role, "role");
        if (retainedGenerations < 1) {
            throw new IllegalArgumentException("retainedGenerations must be at least 1");
        }
        this.retainedGenerations = retainedGenerations;
        this.hook = hook;
        this.durability = Objects.requireNonNull(durability, "durability");
    }

    /** The committed generation number, or {@code 0} when nothing has been committed. */
    public synchronized long currentGeneration() throws IOException {
        final Pointer pointer = readPointer();
        return pointer == null ? 0L : pointer.generation;
    }

    /**
     * Commits a full snapshot as the next generation, chained to nothing.
     *
     * @param snapshot blob name to bytes; copied before anything is written
     * @return the committed generation number
     */
    public synchronized long commit(final Map<String, byte[]> snapshot) throws IOException {
        return commit(snapshot, 0L);
    }

    /**
     * Commits a snapshot as the next generation, chained to {@code base}.
     *
     * @param snapshot blob name to bytes; copied before anything is written
     * @param base a committed generation this one depends on (kept on disk as long as this one
     *             is retained), or {@code 0} for none
     * @return the committed generation number
     * @throws IllegalArgumentException when {@code base} is not a committed generation
     */
    public synchronized long commit(final Map<String, byte[]> snapshot, final long base) throws IOException {
        refuseRegionThread();
        if (!this.role.acceptsCommits()) {
            throw new IllegalStateException("a " + this.role + " world does not accept commits");
        }
        final Map<String, byte[]> blobs = copy(snapshot);
        final Pointer previous = readPointer();
        final long current = previous == null ? 0L : previous.generation;
        if (base < 0 || base > current) {
            throw new IllegalArgumentException("base generation " + base + " is not committed (current is " + current + ")");
        }
        final long next = Math.max(current, highestGenerationDir()) + 1;
        final Path generations = this.root.resolve(GENERATIONS);
        // The base's manifest hash is read before anything is written: a child never chains to a
        // base it could not read, and the link is checked again by readBase.
        final String baseSha = base == 0 ? null : sha256(manifestOf(base));

        // A fresh store: the entries naming generations/, the root and any ancestor are made
        // durable before the generation that depends on them is.
        this.durability.createDirectoriesDurably(generations);
        final Path temp = generations.resolve(next + ".tmp");
        deleteTree(temp);
        Files.createDirectory(temp);

        // 1. Snapshot -> temporary generation: write every blob and the manifest, then make them
        //    durable in one pass — the files, then the directory that holds their entries.
        final StringBuilder manifest = new StringBuilder();
        if (base > 0) {
            // Only a chained generation needs the header; an unchained one stays format 1 so that a
            // build without this parser (the auto-updater's rollback jar, say) can still read it.
            manifest.append(FORMAT_KEY).append('\t').append(FORMAT).append('\n');
            manifest.append(BASE_KEY).append('\t').append(base).append('\t').append(baseSha).append('\n');
        }
        for (final Map.Entry<String, byte[]> blob : blobs.entrySet()) {
            write(temp.resolve(blob.getKey()), blob.getValue());
            manifest.append(blob.getKey()).append('\t').append(blob.getValue().length).append('\t')
                .append(sha256(blob.getValue())).append('\n');
        }
        final byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        write(temp.resolve(MANIFEST), manifestBytes);
        for (final String name : blobs.keySet()) {
            this.durability.forceFile(temp.resolve(name));
        }
        this.durability.forceFile(temp.resolve(MANIFEST));
        this.durability.forceDirectory(temp);
        this.hook.reached(Stage.SNAPSHOT_WRITTEN);

        // 2. Verify by reading back what the filesystem actually holds.
        load(next, temp, manifestBytes);
        this.hook.reached(Stage.VERIFIED);

        // 3. Publish the generation directory under its final name. Still not authoritative.
        final Path published = generations.resolve(Long.toString(next));
        moveAtomically(temp, published);
        this.durability.forceDirectory(generations);
        this.hook.reached(Stage.GENERATION_PUBLISHED);

        // 4. Commit: replace the pointer. Before this line the previous generation is current.
        final Path pointerTemp = this.root.resolve(CURRENT + ".tmp");
        write(pointerTemp, (next + "\t" + sha256(manifestBytes) + "\n").getBytes(StandardCharsets.UTF_8));
        this.durability.forceFile(pointerTemp);
        moveAtomically(pointerTemp, this.root.resolve(CURRENT));
        this.durability.forceDirectory(this.root);
        this.hook.reached(Stage.COMMITTED);

        prune(next);
        return next;
    }

    /**
     * Commits on the given executor, for callers that must not block. The snapshot is copied on
     * the calling thread, so the caller may reuse its buffers as soon as this returns.
     */
    public CompletionStage<Long> commitAsync(final Map<String, byte[]> snapshot, final Executor storageLane) {
        final Map<String, byte[]> blobs = copy(snapshot);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.commit(blobs);
            } catch (final IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }, storageLane);
    }

    /**
     * Reads the committed generation, verifying every blob against the manifest and the manifest
     * against {@code CURRENT}.
     *
     * @return the generation, or {@code null} when nothing has been committed
     * @throws CorruptGenerationException when committed data fails verification
     */
    public synchronized Generation read() throws IOException {
        refuseRegionThread();
        final Pointer pointer = readPointer();
        if (pointer == null) {
            return null;
        }
        return read(pointer.generation, pointer.manifestSha);
    }

    /**
     * Reads a committed generation that is still on disk, verified against its own manifest (and
     * against {@code CURRENT} when it is the current one). Use {@link #readBase} to follow a
     * chain, which also checks the link.
     *
     * @throws IllegalArgumentException when {@code number} was never committed
     * @throws CorruptGenerationException when the generation is gone or fails verification
     */
    public synchronized Generation read(final long number) throws IOException {
        refuseRegionThread();
        final Pointer pointer = readPointer();
        if (pointer == null || number < 1 || number > pointer.generation) {
            throw new IllegalArgumentException("generation " + number + " is not committed");
        }
        return read(number, number == pointer.generation ? pointer.manifestSha : null);
    }

    /**
     * Reads the generation {@code child} is chained to and checks that its manifest is the one
     * the child recorded.
     *
     * @return the base, or {@code null} when the child has none
     * @throws CorruptGenerationException when the base is gone, damaged, or not the one chained to
     */
    public synchronized Generation readBase(final Generation child) throws IOException {
        if (!child.hasBase()) return null;
        final Generation base = read(child.base(), null);
        if (!base.manifestSha().equals(child.baseManifestSha())) {
            throw new CorruptGenerationException("generation " + child.number() + " is chained to a manifest of generation "
                + child.base() + " that is not the one on disk");
        }
        return base;
    }

    private Generation read(final long number, final String expectedManifestSha) throws IOException {
        final Path dir = this.root.resolve(GENERATIONS).resolve(Long.toString(number));
        final byte[] manifestBytes;
        try {
            manifestBytes = Files.readAllBytes(dir.resolve(MANIFEST));
        } catch (final NoSuchFileException missing) {
            throw new CorruptGenerationException("generation " + number + " has no manifest");
        }
        if (expectedManifestSha != null && !sha256(manifestBytes).equals(expectedManifestSha)) {
            throw new CorruptGenerationException("generation " + number + " manifest does not match CURRENT");
        }
        return load(number, dir, manifestBytes);
    }

    /**
     * Every retained committed generation that still verifies, newest first: the newest
     * {@code retainedGenerations} on disk, not the chain-only ancestors kept for them. A retained
     * generation that fails verification is skipped, not returned: callers use this to decide what
     * must be kept, and damaged data keeps nothing alive.
     */
    public synchronized List<Generation> readRetained() throws IOException {
        refuseRegionThread();
        final Pointer pointer = readPointer();
        if (pointer == null) return List.of();
        final List<Generation> out = new ArrayList<>();
        final List<Long> numbers = committedNumbersNewestFirst(pointer.generation);
        for (int i = 0; i < numbers.size() && i < this.retainedGenerations; i++) {
            final long number = numbers.get(i);
            try {
                out.add(read(number, number == pointer.generation ? pointer.manifestSha : null));
            } catch (final IOException damaged) {
                // Skipped: see above.
            }
        }
        return out;
    }

    private void recover() throws IOException {
        final Path generations = this.root.resolve(GENERATIONS);
        Files.deleteIfExists(this.root.resolve(CURRENT + ".tmp"));
        if (!Files.isDirectory(generations)) {
            return;
        }
        final Pointer pointer = readPointer();
        final long current = pointer == null ? 0L : pointer.generation;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(generations)) {
            for (final Path dir : stream) {
                final String name = dir.getFileName().toString();
                // Unfinished writes, and generations published but never committed.
                if (name.endsWith(".tmp") || (isNumber(name) && Long.parseLong(name) > current)) {
                    deleteTree(dir);
                }
            }
        }
    }

    /**
     * Deletes every committed generation that is neither one of the newest
     * {@code retainedGenerations} nor chained to, transitively, by one of them. A generation whose
     * manifest cannot be read keeps nothing alive through it.
     */
    private void prune(final long current) throws IOException {
        final List<Long> committed = committedNumbersNewestFirst(current);
        final Set<Long> keep = new HashSet<>();
        final Deque<Long> chain = new ArrayDeque<>();
        for (int i = 0; i < committed.size() && i < this.retainedGenerations; i++) {
            keep.add(committed.get(i));
            chain.add(committed.get(i));
        }
        while (!chain.isEmpty()) {
            final long base = baseOf(chain.poll());
            if (base > 0 && keep.add(base)) chain.add(base);
        }
        for (final long number : committed) {
            if (!keep.contains(number)) {
                deleteTree(this.root.resolve(GENERATIONS).resolve(Long.toString(number)));
            }
        }
    }

    /** The base recorded in a generation's manifest, {@code 0} when none or unreadable. */
    private long baseOf(final long number) {
        try {
            return parse(number, this.root.resolve(GENERATIONS).resolve(Long.toString(number)), manifestOf(number)).base;
        } catch (final IOException unreadable) {
            return 0L;
        }
    }

    private byte[] manifestOf(final long number) throws IOException {
        try {
            return Files.readAllBytes(this.root.resolve(GENERATIONS).resolve(Long.toString(number)).resolve(MANIFEST));
        } catch (final NoSuchFileException missing) {
            throw new CorruptGenerationException("generation " + number + " has no manifest");
        }
    }

    private List<Long> committedNumbersNewestFirst(final long current) throws IOException {
        final List<Long> numbers = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.root.resolve(GENERATIONS))) {
            for (final Path dir : stream) {
                final String name = dir.getFileName().toString();
                if (isNumber(name) && Long.parseLong(name) <= current) numbers.add(Long.parseLong(name));
            }
        } catch (final NoSuchFileException none) {
            return numbers;
        }
        numbers.sort(Comparator.reverseOrder());
        return numbers;
    }

    private long highestGenerationDir() throws IOException {
        final Path generations = this.root.resolve(GENERATIONS);
        if (!Files.isDirectory(generations)) return 0L;
        long highest = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(generations)) {
            for (final Path dir : stream) {
                final String name = dir.getFileName().toString();
                if (isNumber(name)) highest = Math.max(highest, Long.parseLong(name));
            }
        }
        return highest;
    }

    private record Pointer(long generation, String manifestSha) {}

    private Pointer readPointer() throws IOException {
        final String text;
        try {
            text = Files.readString(this.root.resolve(CURRENT), StandardCharsets.UTF_8).trim();
        } catch (final NoSuchFileException none) {
            return null;
        }
        final String[] parts = text.split("\t");
        if (parts.length != 2 || !isNumber(parts[0]) || parts[1].length() != 64) {
            throw new CorruptGenerationException("CURRENT is unreadable: " + text);
        }
        return new Pointer(Long.parseLong(parts[0]), parts[1]);
    }

    /** A parsed manifest: the header and the blob lines ({@code name, length, sha}). */
    private record Manifest(int format, long base, String baseManifestSha, List<String[]> blobs) {}

    /**
     * Parses a manifest's header and blob lines without touching the blobs. Header lines start
     * with {@code @} and come first; a key this build does not know is corrupt, never ignored.
     */
    private static Manifest parse(final long number, final Path dir, final byte[] manifestBytes) throws CorruptGenerationException {
        int format = 1;
        long base = 0L;
        String baseSha = null;
        boolean header = true;
        final List<String[]> blobs = new ArrayList<>();
        for (final String line : new String(manifestBytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            final String[] parts = line.split("\t");
            if (line.startsWith("@")) {
                if (!header) throw new CorruptGenerationException("header line after a blob line in " + dir + ": " + line);
                switch (parts[0]) {
                    case FORMAT_KEY -> {
                        if (parts.length != 2 || !isNumber(parts[1]) || Long.parseLong(parts[1]) > FORMAT || Long.parseLong(parts[1]) < 1) {
                            throw new CorruptGenerationException("manifest format not readable by this build in " + dir + ": " + line);
                        }
                        format = Integer.parseInt(parts[1]);
                    }
                    case BASE_KEY -> {
                        if (parts.length != 3 || !isNumber(parts[1]) || parts[2].length() != 64
                            || Long.parseLong(parts[1]) < 1 || Long.parseLong(parts[1]) >= number) {
                            throw new CorruptGenerationException("malformed base line in " + dir + ": " + line);
                        }
                        base = Long.parseLong(parts[1]);
                        baseSha = parts[2];
                    }
                    default -> throw new CorruptGenerationException("unknown manifest header in " + dir + ": " + line);
                }
                continue;
            }
            header = false;
            if (parts.length != 3 || !BLOB_NAME.matcher(parts[0]).matches() || !isNumber(parts[1]) || parts[2].length() != 64) {
                throw new CorruptGenerationException("malformed manifest line in " + dir + ": " + line);
            }
            blobs.add(parts);
        }
        if (format == 1 && base != 0) {
            throw new CorruptGenerationException("a format 1 manifest cannot have a base: " + dir);
        }
        return new Manifest(format, base, baseSha, blobs);
    }

    /** Re-reads every blob in the manifest and checks size and hash. */
    private static Generation load(final long number, final Path dir, final byte[] manifestBytes) throws IOException {
        final Manifest manifest = parse(number, dir, manifestBytes);
        final Map<String, byte[]> blobs = new LinkedHashMap<>();
        for (final String[] parts : manifest.blobs) {
            final byte[] bytes;
            try {
                bytes = Files.readAllBytes(dir.resolve(parts[0]));
            } catch (final NoSuchFileException missing) {
                throw new CorruptGenerationException("missing blob " + parts[0] + " in " + dir);
            }
            if (bytes.length != Long.parseLong(parts[1]) || !sha256(bytes).equals(parts[2])) {
                throw new CorruptGenerationException("blob " + parts[0] + " in " + dir + " fails verification");
            }
            blobs.put(parts[0], bytes);
        }
        return new Generation(number, manifest.base, sha256(manifestBytes), manifest.baseManifestSha, blobs);
    }

    private static Map<String, byte[]> copy(final Map<String, byte[]> snapshot) {
        final Map<String, byte[]> blobs = new TreeMap<>();
        for (final Map.Entry<String, byte[]> blob : snapshot.entrySet()) {
            final String name = blob.getKey();
            if (name == null || !BLOB_NAME.matcher(name).matches() || MANIFEST.equals(name)
                || name.endsWith(".tmp") || name.equals(".") || name.equals("..")) {
                throw new IllegalArgumentException("unusable blob name: " + name);
            }
            blobs.put(name, Objects.requireNonNull(blob.getValue(), name).clone());
        }
        return blobs;
    }

    /** Writes a file completely; durability comes from the force pass that follows. */
    private static void write(final Path file, final byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    /**
     * Publishes by rename. The temporary file always sits next to its target, so a filesystem
     * that refuses an atomic move here cannot rename atomically at all; a copy-and-delete would
     * void the commit-point argument, so the commit fails instead.
     */
    private void moveAtomically(final Path from, final Path to) throws IOException {
        try {
            this.durability.moveAtomically(from, to);
        } catch (final AtomicMoveNotSupportedException notAtomic) {
            throw new IOException("AWF needs an atomic rename to publish " + to + " and this filesystem refused one;"
                + " the commit is refused rather than published by copying", notAtomic);
        }
    }

    private static void deleteTree(final Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (final Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static boolean isNumber(final String text) {
        if (text.isEmpty() || text.length() > 18) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) return false;
        }
        return true;
    }

    static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Region owners must never block on file I/O. */
    private static void refuseRegionThread() {
        if (ExecutionLane.of(Thread.currentThread().getName()) == ExecutionLane.REGION_TICK) {
            throw new IllegalStateException("blocking world storage I/O on a region thread; use commitAsync");
        }
    }
}
