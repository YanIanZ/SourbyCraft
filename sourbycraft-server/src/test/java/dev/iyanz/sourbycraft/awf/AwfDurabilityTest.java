package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The order in which a FILE commit makes things durable, proven through the {@link Durability}
 * seam: every object and every directory entry naming it is forced before the index that
 * references it, forces come in one pass after all writes, and a crash at any force leaves the
 * previous generation intact.
 *
 * <p>A "crash" here is an exception thrown by the seam: it proves the recovery logic and the order,
 * not what the disk kept, which no in-process test can.</p>
 */
class AwfDurabilityTest {

    @TempDir Path dir;

    /** One durability call, in order. */
    record Event(boolean file, Path path) {
        boolean underObjects() {
            return pathHas("objects");
        }

        boolean underGenerations() {
            return pathHas("generations-root");
        }

        private boolean pathHas(final String segment) {
            for (final Path p : this.path) if (p.toString().equals(segment)) return true;
            return false;
        }

        String name() {
            return this.path.getFileName().toString();
        }
    }

    /** Records every force in order, forwards it to disk, and can crash at the n-th one. */
    static final class Recording implements Durability {
        final List<Event> events = new ArrayList<>();
        int crashAt = -1;
        Runnable beforeFirstForce;

        @Override
        public void forceFile(final Path file) throws IOException {
            record(new Event(true, file));
            Durability.DISK.forceFile(file);
        }

        @Override
        public void forceDirectory(final Path directory) throws IOException {
            record(new Event(false, directory));
            Durability.DISK.forceDirectory(directory);
        }

        private void record(final Event event) throws IOException {
            if (this.events.isEmpty() && this.beforeFirstForce != null) this.beforeFirstForce.run();
            this.events.add(event);
            if (this.events.size() - 1 == this.crashAt) throw new IOException("simulated crash at force #" + this.crashAt + " " + event);
        }

        void reset() {
            this.events.clear();
            this.crashAt = -1;
        }

        List<Event> files(final boolean objects) {
            return this.events.stream().filter(e -> e.file() && e.underObjects() == objects).toList();
        }

        List<Event> directories(final boolean objects) {
            return this.events.stream().filter(e -> !e.file() && e.underObjects() == objects).toList();
        }
    }

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Distinct chunks with random bytes, so their objects spread over many shards. */
    private static Map<ChunkKey, byte[]> chunks(final int count, final long seed) {
        final Random random = new Random(seed);
        final Map<ChunkKey, byte[]> chunks = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            final byte[] raw = new byte[64];
            random.nextBytes(raw);
            chunks.put(new ChunkKey(i, (int) seed), raw);
        }
        return chunks;
    }

    private static long count(final Path root, final String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private static Set<String> shards(final Map<ChunkKey, byte[]> chunks) {
        final Set<String> shards = new HashSet<>();
        for (final byte[] raw : chunks.values()) shards.add(ObjectStore.name(raw).substring(0, 2));
        return shards;
    }

    @Test
    void everyObjectAndItsDirectoryIsForcedBeforeTheIndex() throws Exception {
        final Recording recording = new Recording();
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, stage -> {}, recording);
        recording.reset(); // the open forced the fresh store's ancestors; the commit is what is measured
        final Map<ChunkKey, byte[]> chunks = chunks(300, 1);
        final long[] tempsAtFirstForce = {-1};
        recording.beforeFirstForce = () -> {
            try {
                tempsAtFirstForce[0] = count(this.dir.resolve("objects"), ".tmp");
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        };
        final AwfStore.CommitResult result = store.commit(chunks, Set.of(), PersistenceMode.INCREMENTAL);

        assertEquals(300, result.objectsWritten());
        assertEquals(300, tempsAtFirstForce[0], "every object was written before the first force: one pass");

        final List<Event> events = recording.events;
        int lastObjectEvent = -1;
        int firstGenerationEvent = Integer.MAX_VALUE;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).underObjects()) lastObjectEvent = i;
            else if (events.get(i).underGenerations()) firstGenerationEvent = Math.min(firstGenerationEvent, i);
        }
        assertTrue(lastObjectEvent < firstGenerationEvent,
            "no index, manifest or pointer force before the last object or object-directory force: "
                + lastObjectEvent + " vs " + firstGenerationEvent);

        // Within the objects: every file force precedes every directory force (force, then rename, then entries).
        final List<Event> objectEvents = events.stream().filter(Event::underObjects).toList();
        int firstObjectDirectory = Integer.MAX_VALUE;
        int lastObjectFile = -1;
        for (int i = 0; i < objectEvents.size(); i++) {
            if (objectEvents.get(i).file()) lastObjectFile = i;
            else firstObjectDirectory = Math.min(firstObjectDirectory, i);
        }
        assertTrue(lastObjectFile < firstObjectDirectory, "object files are forced before their directories");

        // Within the generation: the new generations/ entry's parent (a fresh store), index and manifest,
        // their directory, generations/, pointer, root — in that order.
        final List<String> generation = events.stream().filter(Event::underGenerations)
            .map(e -> (e.file() ? "F:" : "D:") + e.name()).toList();
        assertEquals(List.of("D:generations-root", "F:index", "F:manifest", "D:1.tmp", "D:generations", "F:CURRENT.tmp", "D:generations-root"),
            generation);
    }

    @Test
    void aFreshStoresAncestorEntriesAreForcedBeforeItsFirstIndex() throws Exception {
        final Recording recording = new Recording();
        final Path store = this.dir.resolve("a").resolve("b").resolve("store");
        final AwfWorldStore opened = AwfWorldStore.open(store, WorldRole.VANILLA, 1, stage -> {}, recording);
        opened.commit(chunks(3, 1), Set.of(), PersistenceMode.INCREMENTAL);

        final List<Event> events = recording.events;
        int firstIndex = -1;
        for (int i = 0; i < events.size() && firstIndex < 0; i++) {
            if (events.get(i).file() && events.get(i).name().equals("index")) firstIndex = i;
        }
        assertTrue(firstIndex > 0, "the index was forced");
        final List<Path> ancestorsForcedBeforeIndex = events.subList(0, firstIndex).stream()
            .filter(e -> !e.file()).map(Event::path).toList();
        // Every directory whose entry names a newly created directory: tmp names a, a names b, b names
        // store, store names objects/ and generations-root/, generations-root names generations/.
        for (final Path parent : List.of(this.dir, this.dir.resolve("a"), store.getParent(), store, store.resolve("generations-root"))) {
            assertTrue(ancestorsForcedBeforeIndex.contains(parent.toAbsolutePath()) || ancestorsForcedBeforeIndex.contains(parent),
                parent + " is forced before the first index; forced: " + ancestorsForcedBeforeIndex);
        }

        // An existing store creates nothing and forces no ancestor again.
        recording.reset();
        AwfWorldStore.open(store, WorldRole.VANILLA, 1, stage -> {}, recording).commit(chunks(1, 2), Set.of(), PersistenceMode.INCREMENTAL);
        for (final Event event : recording.events) {
            assertFalse(!event.file() && (event.path().equals(store) || event.path().equals(store.toAbsolutePath())
                || event.path().equals(store.getParent()) || event.path().equals(this.dir)), "no ancestor force: " + event);
        }
    }

    @Test
    void aRetryAfterAPartialRenameFailureStillForcesTheDirectoryOfTheObjectAlreadyInPlace() throws Exception {
        final Recording recording = new Recording();
        final ObjectStore objects = new ObjectStore(this.dir.resolve("objects"), recording);
        // Two objects in two different shards; A is renamed first (insertion order).
        final byte[][] pair = twoObjectsInDifferentShards();
        final String a = objects.put(pair[0], false).name();
        final String b = objects.put(pair[1], false).name();
        // Make B's rename fail: a directory sits where B's final name goes.
        final Path bTarget = this.dir.resolve("objects").resolve(b.substring(0, 2)).resolve(b);
        Files.createDirectories(bTarget);
        recording.reset();
        assertThrows(IOException.class, objects::sync);
        assertTrue(objects.contains(a), "A was renamed into place before B failed");
        assertEquals(1, objects.pending(), "B stays pending");
        assertEquals(0, recording.directories(true).size(), "no directory was forced by the failed attempt");
        assertTrue(objects.dirtyDirectories() >= 2, "A's shard and the root are remembered as dirty");

        Files.delete(bTarget);
        recording.reset();
        assertEquals(1, objects.sync());
        final Set<String> forced = new HashSet<>(recording.directories(true).stream().map(Event::name).toList());
        assertTrue(forced.contains(a.substring(0, 2)), "the retry forces A's shard, not only B's: " + forced);
        assertTrue(forced.contains(b.substring(0, 2)), forced.toString());
        assertTrue(forced.contains("objects"), forced.toString());
        assertEquals(0, objects.dirtyDirectories());
        assertEquals(0, objects.pending());
    }

    @Test
    void aRetryAfterADirectoryForceFailureForcesThatDirectoryAgain() throws Exception {
        final Recording recording = new Recording();
        final ObjectStore objects = new ObjectStore(this.dir.resolve("objects"), recording);
        final byte[][] pair = twoObjectsInDifferentShards();
        final String a = objects.put(pair[0], false).name();
        final String b = objects.put(pair[1], false).name();
        recording.reset();
        recording.crashAt = 2; // the two file forces succeed; the first directory force throws
        assertThrows(IOException.class, objects::sync);
        assertTrue(objects.contains(a) && objects.contains(b), "both were renamed into place");
        assertEquals(0, objects.pending());
        assertEquals(3, objects.dirtyDirectories(), "both shards and the root are still dirty");

        recording.reset();
        assertEquals(0, objects.sync(), "nothing to rename, but the directories are forced now");
        final List<String> forced = recording.directories(true).stream().map(Event::name).toList();
        assertEquals(Set.of(a.substring(0, 2), b.substring(0, 2), "objects"), new HashSet<>(forced), forced.toString());
        assertEquals(3, forced.size(), "each once");
        assertEquals(0, recording.files(true).size());
        assertEquals(0, objects.dirtyDirectories());
    }

    @Test
    void aFailedDirectoryForceOnDiskIsNotSwallowed() throws Exception {
        final boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        final Path missing = this.dir.resolve("never-created");
        if (windows) {
            // The platform cannot open directories: tolerated, but counted and visible.
            final long before = Durability.degradedDirectoryForces();
            Durability.DISK.forceDirectory(this.dir);
            assertTrue(Durability.degradedDirectoryForces() > before);
            assertNotNull(Durability.degradedReason());
            return;
        }
        final long before = Durability.degradedDirectoryForces();
        assertThrows(IOException.class, () -> Durability.DISK.forceDirectory(missing),
            "a directory that cannot be forced fails the commit instead of being skipped");
        Durability.DISK.forceDirectory(this.dir); // a real directory forces fine
        assertEquals(before, Durability.degradedDirectoryForces(), "nothing is counted as degraded on a platform that can open directories");

        // And the store fails its commit on it, instead of publishing an index over an unforced directory.
        final boolean[] fail = {false};
        final Durability failingDirectories = new Durability() {
            @Override public void forceFile(final Path file) throws IOException { Durability.DISK.forceFile(file); }
            @Override public void forceDirectory(final Path dir) throws IOException {
                if (fail[0]) throw new IOException("EIO on " + dir);
                Durability.DISK.forceDirectory(dir);
            }
        };
        final AwfWorldStore store = AwfWorldStore.open(this.dir.resolve("store"), WorldRole.VANILLA, 1, stage -> {}, failingDirectories);
        fail[0] = true;
        assertThrows(IOException.class, () -> store.commit(chunks(2, 1), Set.of(), PersistenceMode.INCREMENTAL));
        assertEquals(0L, AwfWorldStore.open(this.dir.resolve("store"), WorldRole.READ_ONLY, 1).generation());
    }

    /** Two payloads whose SHA-256 names start with different shards. */
    private static byte[][] twoObjectsInDifferentShards() {
        final byte[] first = b("first");
        for (int i = 0; ; i++) {
            final byte[] candidate = b("candidate-" + i);
            if (!ObjectStore.name(candidate).substring(0, 2).equals(ObjectStore.name(first).substring(0, 2))) {
                return new byte[][] {first, candidate};
            }
        }
    }

    @Test
    void forcesAreOnePerObjectPlusOnePerTouchedDirectory() throws Exception {
        final Recording recording = new Recording();
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, stage -> {}, recording);
        final Map<ChunkKey, byte[]> first = chunks(300, 1);
        store.commit(first, Set.of(), PersistenceMode.INCREMENTAL);

        assertEquals(300, recording.files(true).size(), "one file force per object written, no more");
        final Set<String> firstShards = shards(first);
        final List<String> dirs = recording.directories(true).stream().map(Event::name).toList();
        assertEquals(new HashSet<>(dirs).size(), dirs.size(), "no directory is forced twice in one commit: " + dirs);
        assertEquals(firstShards.size() + 1, dirs.size(), "each touched shard once, plus the objects root (new shards)");
        assertTrue(dirs.contains("objects"));
        assertEquals(3, recording.files(false).size(), "index, manifest, pointer");
        // Fresh store: the store dir (names objects/, then generations-root/), generations-root (names
        // generations/), then the temporary generation, generations/ and the root.
        assertEquals(List.of(this.dir.getFileName().toString(), this.dir.getFileName().toString(), "generations-root",
            "1.tmp", "generations", "generations-root"), recording.directories(false).stream().map(Event::name).toList());

        // A second commit touches only the shards of its new objects; the objects root only if a shard is new.
        recording.reset();
        final Map<ChunkKey, byte[]> second = chunks(5, 2);
        store.commit(second, Set.of(), PersistenceMode.INCREMENTAL);
        assertEquals(5, recording.files(true).size());
        final Set<String> secondShards = shards(second);
        final List<String> secondDirs = recording.directories(true).stream().map(Event::name).toList();
        final Set<String> newShards = new HashSet<>(secondShards);
        newShards.removeAll(firstShards);
        final Set<String> expected = new HashSet<>(secondShards);
        if (!newShards.isEmpty()) expected.add("objects");
        assertEquals(expected, new HashSet<>(secondDirs));
        assertEquals(expected.size(), secondDirs.size(), "each once");

        // A commit that writes nothing new forces no object and no object directory.
        recording.reset();
        store.commit(Map.of(), Set.of(second.keySet().iterator().next()), PersistenceMode.INCREMENTAL);
        assertEquals(0, recording.files(true).size());
        assertEquals(0, recording.directories(true).size());
        assertEquals(3, recording.files(false).size());
        assertEquals(3, recording.directories(false).size(), "an existing store: temporary generation, generations/, root");
    }

    @Test
    void aCrashAtEveryForceLeavesThePreviousGenerationIntact() throws Exception {
        final Map<ChunkKey, byte[]> base = chunks(10, 1);
        final Map<ChunkKey, byte[]> change = new HashMap<>(chunks(4, 2));
        change.put(new ChunkKey(0, 1), b("modified"));
        int crashes = 0;
        for (int at = 0; ; at++) {
            final Path root = this.dir.resolve("crash-" + at);
            final Recording recording = new Recording();
            final AwfWorldStore store = AwfWorldStore.open(root, WorldRole.VANILLA, 1, stage -> {}, recording);
            store.commit(base, Set.of(), PersistenceMode.INCREMENTAL);
            final long objectsBefore = count(root.resolve("objects"), "");
            recording.reset();
            recording.crashAt = at;
            try {
                store.commit(change, Set.of(), Set.of(new ChunkKey(9, 1)), PersistenceMode.INCREMENTAL);
                break; // past the last force: the commit went through, every crash point has been tried
            } catch (final IOException crash) {
                crashes++;
            }
            final Event crashed = recording.events.get(at);
            final String where = "crash at force #" + at + " " + crashed;
            // The last force, of the root after the pointer rename, is past the commit point.
            final boolean pastCommitPoint = !crashed.file() && crashed.path().equals(root.resolve("generations-root"));

            // Reopened with deltas on, as written: the flag-off open would compact a chained store.
            final AwfWorldStore reopened = AwfWorldStore.open(root, WorldRole.VANILLA, 1, true);
            assertEquals(0, count(root, ".tmp"), where + ": no temporary file survives a reopen");
            if (pastCommitPoint) {
                assertEquals(2L, reopened.generation(), where);
                assertArrayEquals(b("modified"), reopened.read(new ChunkKey(0, 1)).orElseThrow(), where);
                assertEquals(Set.of(new ChunkKey(9, 1)), reopened.deleted(), where);
                continue;
            }
            assertEquals(1L, reopened.generation(), where);
            for (final Map.Entry<ChunkKey, byte[]> chunk : base.entrySet()) {
                assertArrayEquals(chunk.getValue(), reopened.read(chunk.getKey()).orElseThrow(), where);
            }
            for (final ChunkKey key : chunks(4, 2).keySet()) assertTrue(reopened.read(key).isEmpty(), where);
            assertTrue(reopened.deleted().isEmpty(), where);
            assertEquals(objectsBefore, count(root.resolve("objects"), ""), where + ": unreferenced objects are gone");

            // And a later commit works and is durable on its own.
            reopened.commit(change, Set.of(), PersistenceMode.INCREMENTAL);
            assertArrayEquals(b("modified"), AwfWorldStore.open(root, WorldRole.READ_ONLY, 1)
                .read(new ChunkKey(0, 1)).orElseThrow(), where);
        }
        // 5 object forces + their shards (+ root if a shard is new) + 3 files + 3 directories of the generation.
        assertTrue(crashes >= 5 + 1 + 6, "crashed at each of " + crashes + " forces");
    }

    /**
     * A crash at every force of a delta commit, and of a compaction (the chain's hop limit reached),
     * after a chain already exists: before the pointer rename the previous generation replays
     * intact through its chain; after it the new one does; every leftover is gone on reopen.
     */
    @Test
    void aCrashAtEveryForceOfADeltaCommitAndOfACompactionLeavesThePreviousGenerationIntact() throws Exception {
        for (final boolean compaction : new boolean[] {false, true}) {
            final Map<ChunkKey, byte[]> base = chunks(10, 1);
            final Map<ChunkKey, byte[]> firstDelta = Map.of(new ChunkKey(0, 1), b("first-delta"), new ChunkKey(20, 1), b("added"));
            final Map<ChunkKey, byte[]> change = new HashMap<>(chunks(4, 2));
            change.put(new ChunkKey(1, 1), b("modified"));
            // The crashing commit is the third: a delta on a delta, or the compaction a two-hop limit forces.
            final AwfWorldStore.Compaction policy = new AwfWorldStore.Compaction(compaction ? 1 : 32);
            final String kind = compaction ? "compaction" : "delta";
            int crashes = 0;
            for (int at = 0; ; at++) {
                final Path root = this.dir.resolve(kind + "-crash-" + at);
                final Recording recording = new Recording();
                final AwfWorldStore store = AwfWorldStore.open(root, WorldRole.VANILLA, 1, stage -> {}, Durability.DISK, policy);
                store.commit(base, Set.of(), PersistenceMode.INCREMENTAL);
                final AwfWorldStore chained = AwfWorldStore.open(root, WorldRole.VANILLA, 1, stage -> {}, recording, policy);
                chained.commit(firstDelta, Set.of(), Set.of(new ChunkKey(8, 1)), PersistenceMode.INCREMENTAL);
                assertFalse(chained.lastCommitCompacted(), kind + ": generation 2 is a delta");
                final long objectsBefore = count(root.resolve("objects"), "");
                recording.reset();
                recording.crashAt = at;
                try {
                    chained.commit(change, Set.of(new ChunkKey(20, 1)), Set.of(new ChunkKey(9, 1)), PersistenceMode.INCREMENTAL);
                    assertEquals(compaction, chained.lastCommitCompacted(), kind);
                    break; // past the last force: every crash point has been tried
                } catch (final IOException crash) {
                    crashes++;
                }
                final Event crashed = recording.events.get(at);
                final String where = kind + ": crash at force #" + at + " " + crashed;
                final boolean pastCommitPoint = !crashed.file() && crashed.path().equals(root.resolve("generations-root"));
                // The generation blob that was being forced is the right kind.
                if (crashed.file() && crashed.underGenerations() && !crashed.name().equals("manifest") && !crashed.name().equals("CURRENT.tmp")) {
                    assertEquals(compaction ? "index" : "delta", crashed.name(), where);
                }

                final AwfWorldStore reopened = AwfWorldStore.open(root, WorldRole.VANILLA, 1, stage -> {}, Durability.DISK, policy);
                assertEquals(0, count(root, ".tmp"), where + ": no temporary file survives a reopen");
                if (pastCommitPoint) {
                    assertEquals(3L, reopened.generation(), where);
                    assertEquals(compaction ? 0 : 2, reopened.chainLength(), where);
                    assertArrayEquals(b("modified"), reopened.read(new ChunkKey(1, 1)).orElseThrow(), where);
                    assertArrayEquals(b("first-delta"), reopened.read(new ChunkKey(0, 1)).orElseThrow(), where);
                    assertTrue(reopened.read(new ChunkKey(20, 1)).isEmpty() && !reopened.has(new ChunkKey(20, 1)), where + ": dropped");
                    assertEquals(Set.of(new ChunkKey(8, 1), new ChunkKey(9, 1)), reopened.deleted(), where);
                    continue;
                }
                assertEquals(2L, reopened.generation(), where);
                assertEquals(1, reopened.chainLength(), where + ": the previous generation's chain is intact");
                assertArrayEquals(b("first-delta"), reopened.read(new ChunkKey(0, 1)).orElseThrow(), where);
                assertArrayEquals(b("added"), reopened.read(new ChunkKey(20, 1)).orElseThrow(), where);
                for (final Map.Entry<ChunkKey, byte[]> chunk : base.entrySet()) {
                    if (chunk.getKey().equals(new ChunkKey(0, 1)) || chunk.getKey().equals(new ChunkKey(8, 1))) continue;
                    assertArrayEquals(chunk.getValue(), reopened.read(chunk.getKey()).orElseThrow(), where);
                }
                for (final ChunkKey key : chunks(4, 2).keySet()) assertTrue(reopened.read(key).isEmpty(), where);
                assertEquals(Set.of(new ChunkKey(8, 1)), reopened.deleted(), where);
                assertEquals(objectsBefore, count(root.resolve("objects"), ""), where + ": unreferenced objects are gone");
                // Only generations 1 and 2 are on disk: no half-published third.
                try (Stream<Path> dirs = Files.list(root.resolve("generations-root").resolve("generations"))) {
                    assertEquals(Set.of("1", "2"), dirs.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()), where);
                }

                // And a later commit works, chains or compacts as the policy says, and is durable on its own.
                reopened.commit(change, Set.of(), PersistenceMode.INCREMENTAL);
                assertEquals(compaction, reopened.lastCommitCompacted(), where);
                assertArrayEquals(b("modified"), AwfWorldStore.open(root, WorldRole.READ_ONLY, 1)
                    .read(new ChunkKey(1, 1)).orElseThrow(), where);
            }
            // 5 object forces + their shards (+ root if a shard is new) + 3 files + 3 directories of the generation.
            assertTrue(crashes >= 5 + 1 + 6, kind + ": crashed at each of " + crashes + " forces");
        }
    }

    /** A seam whose rename refuses to be atomic, as a filesystem without atomic rename would. */
    private static Durability refusingAtomicMoves(final boolean[] refuse) {
        return new Durability() {
            @Override public void forceFile(final Path file) throws IOException { Durability.DISK.forceFile(file); }
            @Override public void forceDirectory(final Path dir) throws IOException { Durability.DISK.forceDirectory(dir); }
            @Override public void moveAtomically(final Path from, final Path to) throws IOException {
                if (refuse[0]) throw new java.nio.file.AtomicMoveNotSupportedException(from.toString(), to.toString(), "not atomic here");
                Durability.DISK.moveAtomically(from, to);
            }
        };
    }

    @Test
    void aFilesystemThatRefusesAnAtomicRenameFailsTheCommitInsteadOfCopying() throws Exception {
        final boolean[] refuse = {true};
        // ObjectStore: the object stays pending under its temporary name; nothing appears under its name.
        final ObjectStore objects = new ObjectStore(this.dir.resolve("objects"), refusingAtomicMoves(refuse));
        final String name = objects.put(b("chunk"), false).name();
        final IOException refused = assertThrows(IOException.class, objects::sync);
        assertTrue(refused.getCause() instanceof java.nio.file.AtomicMoveNotSupportedException, String.valueOf(refused.getCause()));
        assertTrue(refused.getMessage().contains("atomic rename") && refused.getMessage().contains("refused"), refused.getMessage());
        assertEquals(1, objects.pending(), "still pending, not copied into place");
        assertFalse(objects.contains(name));
        assertEquals(1, count(this.dir.resolve("objects"), ".tmp"));
        refuse[0] = false;
        assertEquals(1, objects.sync(), "the same object publishes once renames are atomic again");
        assertTrue(objects.contains(name));

        // GenerationStore: the generation directory is never published and CURRENT never moves.
        refuse[0] = true;
        final GenerationStore generations = GenerationStore.open(this.dir.resolve("gens"), WorldRole.VANILLA, 1, stage -> {}, refusingAtomicMoves(refuse));
        final IOException refusedGeneration = assertThrows(IOException.class, () -> generations.commit(Map.of("index", b("x"))));
        assertTrue(refusedGeneration.getCause() instanceof java.nio.file.AtomicMoveNotSupportedException);
        assertEquals(0L, generations.currentGeneration());
        assertFalse(Files.exists(this.dir.resolve("gens").resolve("generations").resolve("1")), "not published by a copy");
        assertEquals(0L, GenerationStore.open(this.dir.resolve("gens"), WorldRole.VANILLA, 1).currentGeneration());
        assertEquals(0, count(this.dir.resolve("gens"), ".tmp"), "a reopen removes the unpublished directory");

        // Through the world store: the commit fails, the previous generation stays, and the chunk is not visible.
        final AwfWorldStore store = AwfWorldStore.open(this.dir.resolve("store"), WorldRole.VANILLA, 1, stage -> {}, refusingAtomicMoves(refuse));
        assertThrows(IOException.class, () -> store.commit(chunks(2, 1), Set.of(), PersistenceMode.INCREMENTAL));
        assertEquals(0L, store.generation());
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void objectsLeftPendingByAFailedCommitAreForcedBeforeTheyAreReferenced() throws Exception {
        final Recording recording = new Recording();
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, stage -> {}, recording);
        recording.reset();
        final Map<ChunkKey, byte[]> chunks = chunks(6, 1);
        recording.crashAt = 2; // the third object force fails: four objects are not durable yet
        assertThrows(IOException.class, () -> store.commit(chunks, Set.of(), PersistenceMode.INCREMENTAL));
        assertEquals(6, count(this.dir.resolve("objects"), ".tmp"), "nothing was renamed into place");

        recording.reset();
        final AwfStore.CommitResult result = store.commit(chunks, Set.of(), PersistenceMode.INCREMENTAL);
        assertEquals(0, result.objectsWritten(), "the pending objects are reused, not rewritten");
        assertEquals(6, recording.files(true).size(), "but every one of them is forced before the index");
        assertEquals(0, count(this.dir.resolve("objects"), ".tmp"));
        for (final Map.Entry<ChunkKey, byte[]> chunk : chunks.entrySet()) {
            assertArrayEquals(chunk.getValue(), AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 1)
                .read(chunk.getKey()).orElseThrow());
        }
    }

    @Test
    void generationStoreForcesBlobsThenTheirDirectoryThenPublishesThenThePointer() throws Exception {
        final Recording recording = new Recording();
        final GenerationStore store = GenerationStore.open(this.dir, WorldRole.VANILLA, 1, stage -> {}, recording);
        store.commit(Map.of("a", b("1"), "b", b("2"), "c", b("3")));
        final String root = "D:" + this.dir.getFileName();
        // The root is forced first because generations/ is created in it by this first commit.
        assertEquals(List.of(root, "F:a", "F:b", "F:c", "F:manifest", "D:1.tmp", "D:generations", "F:CURRENT.tmp", root),
            recording.events.stream().map(e -> (e.file() ? "F:" : "D:") + e.name()).toList());
        recording.reset();
        store.commit(Map.of("a", b("4")));
        assertEquals(List.of("F:a", "F:manifest", "D:2.tmp", "D:generations", "F:CURRENT.tmp", root),
            recording.events.stream().map(e -> (e.file() ? "F:" : "D:") + e.name()).toList());
    }

    @Test
    void anObjectIsReadableWhilePendingAndNamedOnlyAfterSync() throws Exception {
        final Recording recording = new Recording();
        final ObjectStore objects = new ObjectStore(this.dir.resolve("objects"), recording);
        assertEquals(List.of(false), recording.events.stream().map(Event::file).toList(), "creating objects/ forces its parent");
        recording.reset();
        final ObjectStore.Written w = objects.put(b("chunk"), false);
        assertTrue(w.bytes() > 0);
        assertFalse(objects.contains(w.name()), "not in place yet");
        assertArrayEquals(b("chunk"), objects.get(w.name()), "but readable");
        assertEquals(1, objects.pending());
        assertEquals(new ObjectStore.Written(w.name(), 0L), objects.put(b("chunk"), false), "pending counts as present");
        assertEquals(new ObjectStore.Written(w.name(), 0L), objects.put(b("chunk"), true), "even for a rewrite");

        // The force happens while the object is still a temporary file: forced, then renamed.
        assertEquals(1, objects.sync());
        assertTrue(objects.contains(w.name()));
        assertArrayEquals(b("chunk"), objects.get(w.name()));
        assertEquals(0, objects.pending());
        assertEquals(0, count(this.dir, ".tmp"));
        assertEquals(List.of(true, false, false), recording.events.stream().map(Event::file).toList(),
            "file, then its shard, then the root that gained the shard");
        assertTrue(recording.events.get(0).name().endsWith(".tmp"), "forced under its temporary name, before the rename");
        assertEquals(0, objects.sync(), "nothing pending: nothing forced");
        assertEquals(3, recording.events.size());
    }

    @Test
    void aPendingObjectCanBeDeletedAndIsSparedByRetainOnly() throws Exception {
        final ObjectStore objects = new ObjectStore(this.dir.resolve("objects"));
        final String kept = objects.put(b("kept"), false).name();
        objects.sync();
        final String pending = objects.put(b("pending"), false).name();
        assertEquals(0, objects.retainOnly(Set.of(kept)), "a pending object is this store's own batch, not garbage");
        assertEquals(1, objects.pending());
        assertTrue(objects.delete(pending));
        assertEquals(0, objects.pending());
        assertEquals(0, count(this.dir, ".tmp"));
        assertFalse(objects.delete(pending));
        assertTrue(objects.contains(kept));
    }

    @Test
    void aReopenRemovesTemporaryFilesAPendingBatchLeftBehind() throws Exception {
        final ObjectStore first = new ObjectStore(this.dir.resolve("objects"));
        final String name = first.put(b("never synced"), false).name();
        assertEquals(1, count(this.dir, ".tmp"));
        final ObjectStore reopened = new ObjectStore(this.dir.resolve("objects"));
        assertEquals(0, count(this.dir, ".tmp"));
        assertFalse(reopened.contains(name));
        assertThrows(AwfFile.CorruptAwfException.class, () -> reopened.get(name));
    }
}
