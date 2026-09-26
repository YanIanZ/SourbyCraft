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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>Scope, stated plainly: this is a storage primitive with tests, not a world backend. No world,
 * chunk serializer or save path uses it yet, and it supports FULL commits only. MongoDB/MySQL/Redis backends, incremental and checkpoint modes, lazy materialisation and
 * copy-on-write templates are not implemented.</p>
 *
 * <p>Ownership: the store never sees live region-owned state, only the byte snapshot a caller
 * already took. Every blocking method refuses to run on a region tick thread; region code must
 * go through {@link #commitAsync} with a storage executor.</p>
 */
public final class GenerationStore {

    static final String CURRENT = "CURRENT";
    static final String GENERATIONS = "generations";
    static final String MANIFEST = "manifest";
    private static final Pattern BLOB_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** Points in the commit sequence, for crash-injection tests. */
    enum Stage { SNAPSHOT_WRITTEN, VERIFIED, GENERATION_PUBLISHED, COMMITTED }

    /** Test hook: throwing here simulates a crash at that stage. */
    @FunctionalInterface
    interface StageHook {
        void reached(Stage stage) throws IOException;
    }

    /** A committed generation's contents. */
    public record Generation(long number, Map<String, byte[]> blobs) {}

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
        refuseRegionThread();
        final GenerationStore store = new GenerationStore(root, role, retainedGenerations, hook);
        store.recover();
        return store;
    }

    private GenerationStore(final Path root, final WorldRole role, final int retainedGenerations,
                            final StageHook hook) {
        this.root = Objects.requireNonNull(root, "root");
        this.role = Objects.requireNonNull(role, "role");
        if (retainedGenerations < 1) {
            throw new IllegalArgumentException("retainedGenerations must be at least 1");
        }
        this.retainedGenerations = retainedGenerations;
        this.hook = hook;
    }

    /** The committed generation number, or {@code 0} when nothing has been committed. */
    public synchronized long currentGeneration() throws IOException {
        final Pointer pointer = readPointer();
        return pointer == null ? 0L : pointer.generation;
    }

    /**
     * Commits a full snapshot as the next generation.
     *
     * @param snapshot blob name to bytes; copied before anything is written
     * @return the committed generation number
     */
    public synchronized long commit(final Map<String, byte[]> snapshot) throws IOException {
        refuseRegionThread();
        if (!this.role.acceptsCommits()) {
            throw new IllegalStateException("a " + this.role + " world does not accept commits");
        }
        final Map<String, byte[]> blobs = copy(snapshot);
        final Pointer previous = readPointer();
        final long next = Math.max(previous == null ? 0L : previous.generation, highestGenerationDir()) + 1;

        final Path generations = this.root.resolve(GENERATIONS);
        Files.createDirectories(generations);
        final Path temp = generations.resolve(next + ".tmp");
        deleteTree(temp);
        Files.createDirectory(temp);

        // 1. Snapshot -> temporary generation.
        final StringBuilder manifest = new StringBuilder();
        for (final Map.Entry<String, byte[]> blob : blobs.entrySet()) {
            writeDurably(temp.resolve(blob.getKey()), blob.getValue());
            manifest.append(blob.getKey()).append('\t').append(blob.getValue().length).append('\t')
                .append(sha256(blob.getValue())).append('\n');
        }
        final byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        writeDurably(temp.resolve(MANIFEST), manifestBytes);
        this.hook.reached(Stage.SNAPSHOT_WRITTEN);

        // 2. Verify by reading back what the filesystem actually holds.
        verify(temp, manifestBytes);
        this.hook.reached(Stage.VERIFIED);

        // 3. Publish the generation directory under its final name. Still not authoritative.
        final Path published = generations.resolve(Long.toString(next));
        moveAtomically(temp, published);
        forceDirectory(generations);
        this.hook.reached(Stage.GENERATION_PUBLISHED);

        // 4. Commit: replace the pointer. Before this line the previous generation is current.
        final Path pointerTemp = this.root.resolve(CURRENT + ".tmp");
        writeDurably(pointerTemp, (next + "\t" + sha256(manifestBytes) + "\n").getBytes(StandardCharsets.UTF_8));
        moveAtomically(pointerTemp, this.root.resolve(CURRENT));
        forceDirectory(this.root);
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
     * Reads the committed generation, verifying every blob against the manifest.
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
        final Path dir = this.root.resolve(GENERATIONS).resolve(Long.toString(pointer.generation));
        final byte[] manifestBytes;
        try {
            manifestBytes = Files.readAllBytes(dir.resolve(MANIFEST));
        } catch (final NoSuchFileException missing) {
            throw new CorruptGenerationException("generation " + pointer.generation + " has no manifest");
        }
        if (!sha256(manifestBytes).equals(pointer.manifestSha)) {
            throw new CorruptGenerationException("generation " + pointer.generation + " manifest does not match CURRENT");
        }
        return new Generation(pointer.generation, verify(dir, manifestBytes));
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

    private void prune(final long current) throws IOException {
        final List<Long> committed = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.root.resolve(GENERATIONS))) {
            for (final Path dir : stream) {
                final String name = dir.getFileName().toString();
                if (isNumber(name) && Long.parseLong(name) <= current) {
                    committed.add(Long.parseLong(name));
                }
            }
        }
        committed.sort(Comparator.reverseOrder());
        for (int i = this.retainedGenerations; i < committed.size(); i++) {
            deleteTree(this.root.resolve(GENERATIONS).resolve(Long.toString(committed.get(i))));
        }
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

    /** Re-reads every blob in the manifest and checks size and hash. */
    private static Map<String, byte[]> verify(final Path dir, final byte[] manifestBytes) throws IOException {
        final Map<String, byte[]> blobs = new LinkedHashMap<>();
        for (final String line : new String(manifestBytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            final String[] parts = line.split("\t");
            if (parts.length != 3 || !BLOB_NAME.matcher(parts[0]).matches() || !isNumber(parts[1])) {
                throw new CorruptGenerationException("malformed manifest line in " + dir + ": " + line);
            }
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
        return blobs;
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

    private static void writeDurably(final Path file, final byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void moveAtomically(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException notAtomic) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Makes a rename durable where the platform allows it. Linux accepts fsync on a directory;
     * Windows refuses to open one, and there the rename's own durability is all there is.
     */
    private static void forceDirectory(final Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (final IOException | UnsupportedOperationException unsupported) {
            // Best effort by design; see above.
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
