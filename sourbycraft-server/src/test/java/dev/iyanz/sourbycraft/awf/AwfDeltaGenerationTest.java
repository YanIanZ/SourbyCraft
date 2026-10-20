package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Delta-indexed generations (AWF-7): a commit writes only the entries it changes, chained to the
 * previous generation; replaying the chain on reopen gives exactly the committed state; a
 * compaction writes a full index and ends the chain; garbage collection sees through the chain;
 * stores written with full indexes only (format 1) open and continue as the first delta's base.
 */
class AwfDeltaGenerationTest {

    @TempDir Path dir;

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ChunkKey k(final int x, final int z) {
        return new ChunkKey(x, z);
    }

    private Path generation(final long number) {
        return this.dir.resolve("generations-root").resolve("generations").resolve(Long.toString(number));
    }

    private Set<String> generationsOnDisk() throws IOException {
        try (Stream<Path> dirs = Files.list(this.dir.resolve("generations-root").resolve("generations"))) {
            return dirs.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }

    private String manifest(final long number) throws IOException {
        return Files.readString(generation(number).resolve("manifest"), StandardCharsets.UTF_8);
    }

    private static boolean objectExists(final Path root, final byte[] raw) {
        final String name = ObjectStore.name(raw);
        return Files.isRegularFile(root.resolve("objects").resolve(name.substring(0, 2)).resolve(name));
    }

    /** Every chunk, deletion and absence in {@code expected} (null = deleted) matches the store. */
    private static void assertState(final Map<ChunkKey, byte[]> expected, final AwfStore store, final String where) throws IOException {
        final Set<ChunkKey> keys = new TreeSet<>();
        final Set<ChunkKey> deleted = new TreeSet<>();
        for (final Map.Entry<ChunkKey, byte[]> e : expected.entrySet()) {
            if (e.getValue() == null) {
                deleted.add(e.getKey());
                assertTrue(store.has(e.getKey()), where + ": " + e.getKey() + " is a recorded deletion");
                assertTrue(store.read(e.getKey()).isEmpty(), where + ": " + e.getKey() + " reads as absent");
            } else {
                keys.add(e.getKey());
                assertArrayEquals(e.getValue(), store.read(e.getKey()).orElseThrow(), where + ": " + e.getKey());
            }
        }
        assertEquals(keys, store.keys(), where);
        assertEquals(deleted, store.deleted(), where);
    }

    @Test
    void aDeltaCommitWritesOnlyTheChangedEntriesAndReopensToTheFullState() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, true);
        final Map<ChunkKey, byte[]> world = new HashMap<>();
        for (int i = 0; i < 40; i++) world.put(k(i, 0), b("chunk-" + i));
        store.commit(world, Set.of(), PersistenceMode.INCREMENTAL);
        assertTrue(store.lastCommitCompacted(), "the first commit is a full index");
        assertEquals(0, store.chainLength());

        // Three incremental commits: a few changes, a removal, a deletion, an unchanged rewrite.
        final Map<ChunkKey, byte[]> expected = new HashMap<>(world);
        for (int round = 1; round <= 3; round++) {
            final Map<ChunkKey, byte[]> changed = new HashMap<>();
            changed.put(k(round, 0), b("changed-" + round));
            changed.put(k(100 + round, 0), b("new-" + round));
            changed.put(k(39, 0), b("chunk-39")); // identical bytes: not a change
            final Set<ChunkKey> removed = Set.of(k(10 + round, 0));
            final Set<ChunkKey> deleted = Set.of(k(20 + round, 0));
            final var result = store.commit(changed, removed, deleted, PersistenceMode.INCREMENTAL);
            changed.forEach(expected::put);
            removed.forEach(expected::remove);
            deleted.forEach(key -> expected.put(key, null));
            assertFalse(store.lastCommitCompacted(), "round " + round + " is a delta");
            assertEquals(round, store.chainLength());
            assertEquals(expected.size(), result.chunks());

            final long number = result.generation();
            assertTrue(Files.isRegularFile(generation(number).resolve("delta")), "a delta blob");
            assertFalse(Files.exists(generation(number).resolve("index")), "no full index");
            final String manifest = manifest(number);
            assertTrue(manifest.startsWith("@format\t2\n@base\t" + (number - 1) + "\t"), manifest);
            final List<String> lines = Files.readAllLines(generation(number).resolve("delta"));
            assertEquals(4, lines.size(), "two changes, one drop, one tombstone; the identical rewrite is absent: " + lines);
            assertTrue(lines.contains((10 + round) + " 0 drop"), lines.toString());
            assertTrue(lines.contains((20 + round) + " 0 -"), lines.toString());
            assertTrue(lines.contains(round + " 0 " + ObjectStore.name(b("changed-" + round))), lines.toString());
        }
        assertState(expected, store, "in memory");
        assertState(expected, AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 1), "reopened read-only");
        final AwfWorldStore writable = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, true);
        assertState(expected, writable, "reopened writable (after the open scan)");
        assertEquals(3, writable.chainLength(), "the chain is what was committed");
        assertEquals(4L, writable.generation());
        // The retained generation (4) keeps its whole chain on disk, back to the full index.
        assertEquals(Set.of("1", "2", "3", "4"), generationsOnDisk());
    }

    @Test
    void tombstonesAndDropsReplayThroughTheChainInOrder() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, true);
        // Filler chunks keep the world larger than the deltas, so the size criterion does not compact.
        final Map<ChunkKey, byte[]> initial = new HashMap<>(Map.of(k(0, 0), b("a"), k(1, 0), b("b"), k(2, 0), b("c")));
        for (int i = 0; i < 20; i++) initial.put(k(i, 7), b("filler-" + i));
        store.commit(initial, Set.of(), PersistenceMode.INCREMENTAL);
        // gen 2: delete (0,0) (a tombstone that shadows the base), drop (1,0).
        store.commit(Map.of(), Set.of(k(1, 0)), Set.of(k(0, 0)), PersistenceMode.INCREMENTAL);
        // gen 3: a deleted chunk written again, a dropped chunk deleted (tombstone for a key the index lost).
        store.commit(Map.of(k(0, 0), b("a-again")), Set.of(), Set.of(k(1, 0)), PersistenceMode.INCREMENTAL);
        // gen 4: drop the tombstone itself; delete (2,0).
        store.commit(Map.of(), Set.of(k(1, 0)), Set.of(k(2, 0)), PersistenceMode.INCREMENTAL);
        assertEquals(3, store.chainLength());

        final Map<ChunkKey, byte[]> expected = new HashMap<>(initial);
        expected.remove(k(1, 0));
        expected.put(k(0, 0), b("a-again"));
        expected.put(k(2, 0), null);
        assertState(expected, store, "in memory");
        final AwfWorldStore reopened = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, true);
        assertState(expected, reopened, "reopened");
        assertFalse(reopened.has(k(1, 0)), "dropped: the index says nothing about it");
        // The deleted chunk's bytes are gone once no retained generation references them; the rewritten one's are there.
        assertFalse(objectExists(this.dir, b("c")));
        assertFalse(objectExists(this.dir, b("a")));
        assertTrue(objectExists(this.dir, b("a-again")));
        // The tombstone survives a compaction into the full index, the drop simply is not there.
        reopened.commit(Map.of(), Set.of(), PersistenceMode.FULL);
        assertTrue(reopened.lastCommitCompacted());
        final List<String> index = Files.readAllLines(generation(5).resolve("index"));
        assertTrue(index.contains("0 0 " + ObjectStore.name(b("a-again"))), index.toString());
        assertTrue(index.contains("2 0 -"), index.toString());
        assertFalse(index.stream().anyMatch(l -> l.startsWith("1 0 ")), index.toString());
        assertEquals(initial.size() - 1, index.size(), index.toString());
        assertState(expected, AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 1), "after compaction");
    }

    @Test
    void aCompactionEndsTheChainAndRetentionDropsTheOldOne() throws Exception {
        final AwfWorldStore.Compaction twoHops = new AwfWorldStore.Compaction(2);
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, stage -> {}, Durability.DISK, twoHops);
        final Map<ChunkKey, byte[]> expected = new HashMap<>();
        for (int i = 0; i < 20; i++) expected.put(k(i, 0), b("base-" + i));
        store.commit(expected, Set.of(), PersistenceMode.INCREMENTAL);            // 1: full
        for (int round = 2; round <= 7; round++) {
            final Map<ChunkKey, byte[]> changed = Map.of(k(round, 0), b("round-" + round));
            store.commit(changed, Set.of(), PersistenceMode.INCREMENTAL);
            expected.putAll(changed);
            // 2: delta(1), 3: delta(2), 4: full (two hops reached), 5: delta(4), 6: delta(5), 7: full.
            final boolean full = round == 4 || round == 7;
            assertEquals(full, store.lastCommitCompacted(), "generation " + round);
            assertEquals(full ? 0 : (round == 2 || round == 5 ? 1 : 2), store.chainLength(), "generation " + round);
            if (full) {
                assertFalse(manifest(round).contains("@base"), "a full index is chained to nothing");
                assertTrue(Files.isRegularFile(generation(round).resolve("index")));
            }
            assertState(expected, AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 2), "reopened at " + round);
        }
        // Retention keeps 7 and 6; 6 is chained to 5 and 5 to 4, so the old chain 1..3 is gone.
        assertEquals(Set.of("4", "5", "6", "7"), generationsOnDisk());
        // One more delta on 7: 6 falls out of retention and takes 5 and 4 with it.
        store.commit(Map.of(k(0, 0), b("last")), Set.of(), PersistenceMode.INCREMENTAL);
        expected.put(k(0, 0), b("last"));
        assertEquals(Set.of("7", "8"), generationsOnDisk());
        assertState(expected, AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, true), "after the old chain is pruned");
    }

    @Test
    void aCommitWhoseReplayWouldReachTheFullIndexSizeCompacts() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, true);
        final Map<ChunkKey, byte[]> world = new HashMap<>();
        for (int i = 0; i < 10; i++) world.put(k(i, 0), b("w" + i));
        store.commit(world, Set.of(), PersistenceMode.INCREMENTAL);
        // 4 + 4 = 8 entries replayed < 10: deltas. 4 + 4 + 4 = 12 >= 10: a full index.
        for (int round = 1; round <= 3; round++) {
            final Map<ChunkKey, byte[]> changed = new HashMap<>();
            for (int i = 0; i < 4; i++) changed.put(k(i, 0), b("r" + round + "-" + i));
            store.commit(changed, Set.of(), PersistenceMode.INCREMENTAL);
            assertEquals(round == 3, store.lastCommitCompacted(), "round " + round);
        }
        // An empty world compacts every time (nothing to replay is cheaper as a full index).
        final AwfWorldStore empty = AwfWorldStore.open(this.dir.resolve("empty"), WorldRole.VANILLA, 1, true);
        empty.commit(Map.of(), Set.of(), PersistenceMode.INCREMENTAL);
        empty.commit(Map.of(), Set.of(), PersistenceMode.INCREMENTAL);
        assertTrue(empty.lastCommitCompacted());
        // CHECKPOINT mode chains like INCREMENTAL (it differs in what it verifies, not in the index).
        final AwfWorldStore cp = AwfWorldStore.open(this.dir.resolve("cp"), WorldRole.VANILLA, 1, true);
        cp.commit(world, Set.of(), PersistenceMode.CHECKPOINT);
        cp.commit(Map.of(k(0, 0), b("x")), Set.of(), PersistenceMode.CHECKPOINT);
        assertFalse(cp.lastCommitCompacted());
        assertEquals(1, cp.chainLength());
    }

    @Test
    void garbageCollectionKeepsEveryObjectAReplayedRetainedGenerationReferences() throws Exception {
        final byte[] a = b("object-a");
        final byte[] bb = b("object-b");
        final byte[] c = b("object-c");
        final byte[] c2 = b("object-c2");
        AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, true);
        final Map<ChunkKey, byte[]> initial = new HashMap<>(Map.of(k(0, 0), a, k(1, 0), bb));
        for (int i = 0; i < 20; i++) initial.put(k(i, 7), b("filler-" + i));
        store.commit(initial, Set.of(), PersistenceMode.INCREMENTAL);                           // 1: full {a, b, filler}
        store.commit(Map.of(k(2, 0), c), Set.of(), PersistenceMode.INCREMENTAL);                // 2: delta {c}; a, b inherited
        assertEquals(1, store.chainLength());
        assertEquals(Set.of("1", "2"), generationsOnDisk());

        // A writable reopen scans the object directory: the inherited objects are live through the chain.
        store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, true);
        assertTrue(objectExists(this.dir, a) && objectExists(this.dir, bb) && objectExists(this.dir, c), "nothing inherited was collected");
        assertArrayEquals(a, store.read(k(0, 0)).orElseThrow());
        assertArrayEquals(bb, store.read(k(1, 0)).orElseThrow());

        // 3: delta replacing c. Generation 2 leaves retention; c is referenced by no replayed retained state.
        final var third = store.commit(Map.of(k(2, 0), c2), Set.of(), PersistenceMode.INCREMENTAL);
        assertEquals(1, third.objectsRemoved());
        assertFalse(objectExists(this.dir, c));
        assertTrue(objectExists(this.dir, a) && objectExists(this.dir, bb) && objectExists(this.dir, c2));
        assertEquals(Set.of("1", "2", "3"), generationsOnDisk(), "2 left retention but stays on disk as 3's base, 1 as 2's");

        // 4: drop a. Its entry in generation 1's full index is shadowed by the drop, so a is garbage
        // even though the ancestor's blob still names it; replay never dereferences a shadowed entry.
        final var fourth = store.commit(Map.of(), Set.of(k(0, 0)), PersistenceMode.INCREMENTAL);
        assertEquals(1, fourth.objectsRemoved());
        assertFalse(objectExists(this.dir, a));
        assertTrue(Files.readString(generation(1).resolve("index")).contains(ObjectStore.name(a)), "the ancestor still names it");
        final AwfWorldStore reopened = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, true);
        final Set<ChunkKey> keys = new TreeSet<>(initial.keySet());
        keys.remove(k(0, 0));
        keys.add(k(2, 0));
        assertEquals(keys, reopened.keys());
        assertArrayEquals(bb, reopened.read(k(1, 0)).orElseThrow());
        assertArrayEquals(c2, reopened.read(k(2, 0)).orElseThrow());
        assertTrue(objectExists(this.dir, bb) && objectExists(this.dir, c2), "the open scan keeps what the chain references");
        // CHECKPOINT verifies every object the replayed index references, through the chain.
        reopened.commit(Map.of(k(5, 0), b("e")), Set.of(), PersistenceMode.CHECKPOINT);
        assertArrayEquals(bb, AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 1).read(k(1, 0)).orElseThrow());
    }

    @Test
    void withTwoRetainedGenerationsAnObjectLivesWhileEitherReplayedStateNeedsIt() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, true);
        final Map<ChunkKey, byte[]> initial = new HashMap<>(Map.of(k(0, 0), b("v1")));
        for (int i = 0; i < 20; i++) initial.put(k(i, 7), b("filler-" + i));
        store.commit(initial, Set.of(), PersistenceMode.INCREMENTAL);                      // 1 full
        store.commit(Map.of(k(0, 0), b("v2")), Set.of(), PersistenceMode.INCREMENTAL);   // 2 delta(1)
        store.commit(Map.of(k(0, 0), b("v3")), Set.of(), PersistenceMode.INCREMENTAL);   // 3 delta(2); 1 falls out
        assertFalse(objectExists(this.dir, b("v1")), "only generation 1's state used v1");
        assertTrue(objectExists(this.dir, b("v2")), "generation 2 is retained and its state is v2");
        assertEquals(Set.of("1", "2", "3"), generationsOnDisk(), "1 stays on disk as the chain's root");
        // Reopen: both retained states replay (2 -> v2, 3 -> v3) and the scan keeps both objects.
        AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, true);
        assertTrue(objectExists(this.dir, b("v2")) && objectExists(this.dir, b("v3")));
    }

    /** A store exactly as builds before AWF-7 wrote it: full index, header-less manifest. */
    private void writeFormat1Store(final Map<ChunkKey, byte[]> chunks) throws Exception {
        final ObjectStore objects = new ObjectStore(this.dir.resolve("objects"));
        final TreeMap<ChunkKey, String> index = new TreeMap<>();
        for (final Map.Entry<ChunkKey, byte[]> e : chunks.entrySet()) index.put(e.getKey(), objects.put(e.getValue(), false).name());
        objects.sync();
        final byte[] indexBytes = AwfWorldStore.encodeIndex(index);
        Files.createDirectories(generation(1));
        Files.write(generation(1).resolve("index"), indexBytes);
        final byte[] manifest = ("index\t" + indexBytes.length + "\t" + GenerationStore.sha256(indexBytes) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(generation(1).resolve("manifest"), manifest);
        Files.writeString(this.dir.resolve("generations-root").resolve("CURRENT"), "1\t" + GenerationStore.sha256(manifest) + "\n");
    }

    @Test
    void aStoreWrittenWithFullIndexesOnlyOpensAndItsGenerationBecomesTheFirstDeltasBase() throws Exception {
        final Map<ChunkKey, byte[]> old = new HashMap<>();
        for (int i = 0; i < 5; i++) old.put(k(i, i), b("old-" + i));
        writeFormat1Store(old);
        final String oldManifest = manifest(1);
        assertFalse(oldManifest.contains("@"), "the fixture is the header-less format");

        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, true);
        assertEquals(1L, store.generation());
        assertEquals(0, store.chainLength());
        assertState(old, store, "format 1 opened");

        // The first commit is a delta chained to the old generation, whose manifest is not rewritten.
        final Map<ChunkKey, byte[]> expected = new HashMap<>(old);
        store.commit(Map.of(k(0, 0), b("new-0")), Set.of(k(4, 4)), PersistenceMode.INCREMENTAL);
        expected.put(k(0, 0), b("new-0"));
        expected.remove(k(4, 4));
        assertFalse(store.lastCommitCompacted());
        assertEquals(oldManifest, manifest(1), "nothing is rewritten to migrate");
        assertEquals("@format\t2\n@base\t1\t" + GenerationStore.sha256(oldManifest.getBytes(StandardCharsets.UTF_8)) + "\n",
            manifest(2).substring(0, manifest(2).indexOf("delta\t")));
        assertState(expected, AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 2), "reopened after the first delta");
        assertState(expected, AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, true), "reopened writable");
        assertTrue(objectExists(this.dir, b("old-1")), "inherited objects survive the open scan");
        assertTrue(objectExists(this.dir, b("old-4")), "generation 1 is still retained, so its state keeps the dropped chunk's object alive");

        // Both retained states replay: the old full index on its own, and the delta over it.
        final GenerationStore generations = GenerationStore.open(this.dir.resolve("generations-root"), WorldRole.READ_ONLY, 2);
        final List<GenerationStore.Generation> retained = generations.readRetained();
        assertEquals(List.of(2L, 1L), retained.stream().map(GenerationStore.Generation::number).toList());
        assertEquals(0L, retained.get(1).base());
        assertEquals(1L, retained.get(0).base());
        assertEquals(retained.get(1).manifestSha(), retained.get(0).baseManifestSha());

        // A compaction then writes a format 2 full index and the old generation is pruned once unretained.
        final AwfWorldStore again = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, true);
        again.commit(Map.of(k(1, 1), b("new-1")), Set.of(), PersistenceMode.FULL);
        expected.put(k(1, 1), b("new-1"));
        assertTrue(again.lastCommitCompacted());
        assertEquals(Set.of("1", "2", "3"), generationsOnDisk(), "2 is retained and chained to 1");
        again.commit(Map.of(k(2, 2), b("new-2")), Set.of(), PersistenceMode.INCREMENTAL);
        expected.put(k(2, 2), b("new-2"));
        assertEquals(Set.of("3", "4"), generationsOnDisk());
        assertState(expected, AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 2), "migrated");
    }

    @Test
    void aChainWhoseLinkOrDeltaIsDamagedIsReportedNotReplayed() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1, true);
        store.commit(Map.of(k(0, 0), b("a")), Set.of(), PersistenceMode.INCREMENTAL);
        store.commit(Map.of(k(1, 0), b("b")), Set.of(), PersistenceMode.INCREMENTAL);
        store.commit(Map.of(k(2, 0), b("c")), Set.of(), PersistenceMode.INCREMENTAL);

        // The base's manifest is replaced by a well-formed one that is not the one generation 3 chained to.
        final Path baseManifest = generation(2).resolve("manifest");
        final byte[] original = Files.readAllBytes(baseManifest);
        Files.write(baseManifest, (new String(original, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(GenerationStore.CorruptGenerationException.class, () -> AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 1));
        Files.write(baseManifest, original);
        AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 1);

        // A delta blob of an ancestor that no longer matches its manifest.
        final Path delta = generation(2).resolve("delta");
        final byte[] deltaBytes = Files.readAllBytes(delta);
        Files.write(delta, "1 0 drop\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(GenerationStore.CorruptGenerationException.class, () -> AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 1));
        Files.write(delta, deltaBytes);

        // An ancestor that is gone.
        final Path moved = this.dir.resolve("gone");
        Files.move(generation(1), moved);
        assertThrows(GenerationStore.CorruptGenerationException.class, () -> AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 1));
        Files.move(moved, generation(1));
        assertArrayEquals(b("a"), AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 1).read(k(0, 0)).orElseThrow());
    }

    @Test
    void theGenerationStoreKeepsABaseWhileAChildIsRetainedAndChecksTheLink() throws Exception {
        final GenerationStore store = GenerationStore.open(this.dir, WorldRole.VANILLA, 1);
        assertEquals(1L, store.commit(Map.of("x", b("one"))));
        assertThrows(IllegalArgumentException.class, () -> store.commit(Map.of("x", b("bad")), 2L), "a base must be committed");
        assertEquals(2L, store.commit(Map.of("x", b("two")), 1L));
        assertEquals(3L, store.commit(Map.of("x", b("three")), 2L));
        try (Stream<Path> dirs = Files.list(this.dir.resolve("generations"))) {
            assertEquals(Set.of("1", "2", "3"), dirs.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()),
                "retention 1 keeps 3 and, through the chain, 2 and 1");
        }
        final GenerationStore.Generation three = store.read();
        assertEquals(2L, three.base());
        final GenerationStore.Generation two = store.readBase(three);
        assertEquals(2L, two.number());
        assertEquals("two", new String(two.blobs().get("x"), StandardCharsets.UTF_8));
        final GenerationStore.Generation one = store.readBase(two);
        assertEquals("one", new String(one.blobs().get("x"), StandardCharsets.UTF_8));
        assertEquals(null, store.readBase(one));
        assertEquals(List.of(3L), store.readRetained().stream().map(GenerationStore.Generation::number).toList(),
            "retained means the newest ones, not the chain-only ancestors");

        // A full commit on top: nothing chains to the old ones any more, so they go.
        assertEquals(4L, store.commit(Map.of("x", b("four"))));
        try (Stream<Path> dirs = Files.list(this.dir.resolve("generations"))) {
            assertEquals(Set.of("4"), dirs.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        assertThrows(IllegalArgumentException.class, () -> store.read(5L));
        assertThrows(GenerationStore.CorruptGenerationException.class, () -> store.read(3L), "committed once, pruned now");
    }

    @Test
    void aManifestHeaderThisBuildDoesNotKnowIsCorruptNotIgnored() throws Exception {
        final GenerationStore store = GenerationStore.open(this.dir, WorldRole.VANILLA, 1);
        store.commit(Map.of("x", b("one")));
        assertFalse(Files.readString(this.dir.resolve("generations").resolve("1").resolve("manifest")).contains("@"),
            "an unchained generation is format 1: no header at all");
        store.commit(Map.of("x", b("two")), 1L);
        final Path manifest = this.dir.resolve("generations").resolve("2").resolve("manifest");
        final byte[] original = Files.readAllBytes(manifest);
        assertTrue(new String(original, StandardCharsets.UTF_8).startsWith("@format\t2\n@base\t1\t"));
        for (final String header : new String[] {"@format\t3\n", "@compression\tzstd\n", "@format\t2\n@base\t2\t" + "0".repeat(64) + "\n"}) {
            final byte[] tampered = (header + new String(original, StandardCharsets.UTF_8).substring("@format\t2\n".length()))
                .getBytes(StandardCharsets.UTF_8);
            Files.write(manifest, tampered);
            Files.writeString(this.dir.resolve("CURRENT"), "2\t" + GenerationStore.sha256(tampered) + "\n");
            assertThrows(GenerationStore.CorruptGenerationException.class, store::read, header);
        }
    }

    /** What the pre-AWF-7 writer produced for one index blob: the manifest bytes and the pointer line. */
    private static byte[] oldWriterManifest(final byte[] indexBytes) {
        return ("index\t" + indexBytes.length + "\t" + GenerationStore.sha256(indexBytes) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void withDeltasOffEveryCommitIsAFullIndexInAManifestThePreviousBuildParses() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, false);
        final Map<ChunkKey, byte[]> expected = new HashMap<>();
        for (int i = 0; i < 30; i++) expected.put(k(i, 0), b("w" + i));
        store.commit(expected, Set.of(), PersistenceMode.INCREMENTAL);
        for (int round = 1; round <= 4; round++) {
            final Map<ChunkKey, byte[]> changed = Map.of(k(round, 0), b("r" + round));
            final Set<ChunkKey> removed = Set.of(k(10 + round, 0));
            final Set<ChunkKey> deleted = Set.of(k(20 + round, 0));
            store.commit(changed, removed, deleted, PersistenceMode.INCREMENTAL);
            expected.putAll(changed);
            removed.forEach(expected::remove);
            deleted.forEach(key -> expected.put(key, null));
            final long number = round + 1;
            assertTrue(store.lastCommitCompacted(), "generation " + number);
            assertEquals(0, store.chainLength());
            assertTrue(Files.isRegularFile(generation(number).resolve("index")) && !Files.exists(generation(number).resolve("delta")),
                "generation " + number + " is a full index");
            final byte[] index = Files.readAllBytes(generation(number).resolve("index"));
            assertArrayEquals(oldWriterManifest(index), Files.readAllBytes(generation(number).resolve("manifest")),
                "byte for byte the manifest the pre-AWF-7 writer produced");
            // The pre-AWF-7 reader (its rules copied, HeadBuildReader) reads it, and the index it
            // decodes with its own decoder is the committed state.
            final Map<String, byte[]> blobs = HeadBuildReader.read(this.dir.resolve("generations-root"));
            assertEquals(Set.of("index"), blobs.keySet());
            final SortedMap<ChunkKey, String> parsed = HeadBuildReader.decodeIndex(blobs);
            assertEquals(expected.size(), parsed.size());
            for (final Map.Entry<ChunkKey, byte[]> e : expected.entrySet()) {
                assertEquals(e.getValue() == null ? AwfWorldStore.TOMBSTONE : ObjectStore.name(e.getValue()), parsed.get(e.getKey()));
            }
        }
        // No generation on disk carries a header or a delta blob, and retention is the old two.
        assertEquals(Set.of("4", "5"), generationsOnDisk());
        for (final String number : generationsOnDisk()) {
            assertFalse(manifest(Long.parseLong(number)).contains("@"), number);
        }
        assertState(expected, AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 2), "reopened");
        // HEAD's writable open, retained scan and object collection included, deletes nothing and
        // accepts every retained generation: no delta was ever written.
        final long objectsBefore = objectFiles();
        assertEquals(List.of(5L, 4L), HeadBuildReader.retainedNumbersRead(this.dir.resolve("generations-root")));
        final SortedMap<ChunkKey, String> served = HeadBuildReader.openWritable(this.dir);
        assertEquals(expected.size(), served.size());
        assertEquals(objectsBefore, objectFiles(), "HEAD's open-time collection deletes no object of a full-index-only store");
        assertState(expected, AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 2), "after HEAD's open");
        // The public 3-argument open (templates, older callers) is the flag-off one.
        AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2).commit(Map.of(k(0, 0), b("x")), Set.of(), PersistenceMode.INCREMENTAL);
        assertFalse(manifest(6).contains("@"));
        assertTrue(Files.isRegularFile(generation(6).resolve("index")));
    }

    @Test
    void aStoreWithDeltasOpensWithDeltasOffAndItsNextCommitCompactsForTheOldParser() throws Exception {
        final AwfWorldStore on = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, true);
        final Map<ChunkKey, byte[]> expected = new HashMap<>();
        for (int i = 0; i < 30; i++) expected.put(k(i, 0), b("w" + i));
        on.commit(expected, Set.of(), PersistenceMode.INCREMENTAL);
        on.commit(Map.of(k(1, 0), b("d1")), Set.of(), Set.of(k(2, 0)), PersistenceMode.INCREMENTAL);
        on.commit(Map.of(k(3, 0), b("d2")), Set.of(k(4, 0)), PersistenceMode.INCREMENTAL);
        expected.put(k(1, 0), b("d1"));
        expected.put(k(2, 0), null);
        expected.put(k(3, 0), b("d2"));
        expected.remove(k(4, 0));
        assertEquals(2, on.chainLength());
        assertFalse(on.fullIndex());
        assertThrows(IOException.class, () -> HeadBuildReader.read(this.dir.resolve("generations-root")),
            "the pre-AWF-7 reader cannot read a store whose current generation is a delta");

        // A read-only open with the flag off changes nothing: it cannot commit.
        final AwfWorldStore readOnly = AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 2, false);
        assertState(expected, readOnly, "read-only open with deltas off");
        assertEquals(2, readOnly.chainLength());
        assertEquals(3L, readOnly.generation());

        // A writable open with the flag off compacts at once, before anything else touches the store:
        // a clean store (nothing to commit) is converted too, which is what a downgrade needs.
        final AwfWorldStore off = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, false);
        assertState(expected, off, "opened with deltas off");
        assertEquals(0, off.chainLength(), "compacted at open");
        assertTrue(off.fullIndex());
        assertEquals(4L, off.generation(), "the compaction is generation 4");
        assertFalse(manifest(4).contains("@"));
        assertTrue(Files.isRegularFile(generation(4).resolve("index")));
        final Map<String, byte[]> blobs = HeadBuildReader.read(this.dir.resolve("generations-root"));
        assertEquals(expected.size(), HeadBuildReader.decodeIndex(blobs).size());
        // A second writable open has nothing to compact and commits nothing.
        assertEquals(4L, AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, false).generation());
        // Retention keeps 3 (a delta) and its chain for this build; HEAD's scan accepts 4 and the
        // chain root 1 (a full index on disk), and skips 3 and 2 as damaged.
        assertEquals(Set.of("1", "2", "3", "4"), generationsOnDisk());
        assertEquals(List.of(4L, 1L), HeadBuildReader.retainedNumbersRead(this.dir.resolve("generations-root")));
        // compact() on a full store is a no-op; an ordinary commit stays a full index.
        assertEquals(null, off.compact());
        off.commit(Map.of(k(5, 0), b("after")), Set.of(), PersistenceMode.INCREMENTAL);
        expected.put(k(5, 0), b("after"));
        assertTrue(off.lastCommitCompacted());
        assertEquals(Set.of("4", "5"), generationsOnDisk(), "the old chain is gone once nothing retained is chained to it");
        assertState(expected, AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 2), "reopened");
    }

    private long objectFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(this.dir.resolve("objects"))) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void compactForcesAFullIndexWithDeltasOnWithoutChangingTheState() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, true);
        final Map<ChunkKey, byte[]> expected = new HashMap<>();
        for (int i = 0; i < 30; i++) expected.put(k(i, 0), b("w" + i));
        store.commit(expected, Set.of(), PersistenceMode.INCREMENTAL);
        store.commit(Map.of(k(1, 0), b("d1")), Set.of(), Set.of(k(2, 0)), PersistenceMode.INCREMENTAL);
        expected.put(k(1, 0), b("d1"));
        expected.put(k(2, 0), null);
        assertFalse(store.fullIndex());
        final AwfStore.CommitResult compacted = store.compact();
        assertEquals(3L, compacted.generation());
        assertEquals(0, compacted.objectsWritten(), "a compaction writes no object");
        assertTrue(store.fullIndex() && store.lastCommitCompacted());
        assertState(expected, store, "after compact");
        assertEquals(expected.size(), HeadBuildReader.decodeIndex(HeadBuildReader.read(this.dir.resolve("generations-root"))).size());
        assertEquals(null, store.compact(), "nothing to compact twice");
        // With the flag still on, the next commit chains again.
        store.commit(Map.of(k(3, 0), b("d2")), Set.of(), PersistenceMode.INCREMENTAL);
        assertFalse(store.fullIndex());
        // An empty store has nothing to compact.
        assertEquals(null, AwfWorldStore.open(this.dir.resolve("empty"), WorldRole.VANILLA, 1, true).compact());
    }

    /**
     * Models the Codex P2: after a compaction, a build before AWF-7 opens the store, skips the
     * retained delta generations as damaged, and its writable-open collection deletes the objects
     * only those generations reference. The current state survives; those rollback points do not.
     */
    @Test
    void aPreAwf7WritableOpenDeletesTheObjectsOnlyRetainedDeltasReferenceIrreversibly() throws Exception {
        final byte[] a = b("value-a");
        final byte[] bb = b("value-b");
        final byte[] c = b("value-c");
        final AwfWorldStore on = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 3, true);
        final Map<ChunkKey, byte[]> expected = new HashMap<>();
        for (int i = 0; i < 20; i++) expected.put(k(i, 7), b("filler-" + i));
        expected.put(k(0, 0), a);
        on.commit(expected, Set.of(), PersistenceMode.INCREMENTAL);                       // 1: full, X = a
        on.commit(Map.of(k(0, 0), bb), Set.of(), PersistenceMode.INCREMENTAL);           // 2: delta, X = b
        on.commit(Map.of(k(0, 0), c), Set.of(), PersistenceMode.INCREMENTAL);            // 3: delta, X = c
        expected.put(k(0, 0), c);
        // The rollback preparation: deltas off, writable open compacts -> 4: full, X = c.
        final AwfWorldStore off = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 3, false);
        assertEquals(4L, off.generation());
        assertTrue(off.fullIndex());
        assertTrue(objectExists(this.dir, bb), "generation 2 is retained by this build, so b is live");
        assertFalse(objectExists(this.dir, a), "generation 1 left retention at the compaction: this build collected a"
            + " (the chain root's entry is shadowed by every retained state)");
        assertEquals(Set.of("1", "2", "3", "4"), generationsOnDisk());

        // The pre-AWF-7 build: reads 4 and the chain root 1, skips 3 and 2 as damaged, and deletes b.
        assertEquals(List.of(4L, 1L), HeadBuildReader.retainedNumbersRead(this.dir.resolve("generations-root")));
        final SortedMap<ChunkKey, String> served = HeadBuildReader.openWritable(this.dir);
        assertEquals(ObjectStore.name(c), served.get(k(0, 0)), "the current state is intact");
        assertEquals(expected.size(), served.size());
        assertFalse(objectExists(this.dir, bb), "irreversible: the object only the retained delta referenced is gone");
        assertTrue(objectExists(this.dir, c));

        // Back on this build: the current state is still served, generation 2 still replays as an
        // index, but its chunk bytes cannot be read any more.
        final AwfWorldStore again = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 3, false);
        assertState(expected, again, "after the old build's open");
        final GenerationStore generations = GenerationStore.open(this.dir.resolve("generations-root"), WorldRole.READ_ONLY, 3);
        final GenerationStore.Generation two = generations.read(2L);
        final SortedMap<ChunkKey, String> stateTwo = new TreeMap<>(AwfWorldStore.decodeIndex(generations.readBase(two)));
        AwfWorldStore.applyDelta(stateTwo, two);
        assertEquals(ObjectStore.name(bb), stateTwo.get(k(0, 0)));
        assertThrows(AwfFile.CorruptAwfException.class, () -> new ObjectStore(this.dir.resolve("objects")).get(ObjectStore.name(bb)));
    }

    @Test
    void aDeltaEntrySetIsTheDifferenceOverTheTouchedKeys() {
        final TreeMap<ChunkKey, String> before = new TreeMap<>(Map.of(k(0, 0), "A", k(1, 0), "B", k(2, 0), "C", k(3, 0), AwfWorldStore.TOMBSTONE));
        final TreeMap<ChunkKey, String> after = new TreeMap<>(before);
        after.put(k(0, 0), "A2");            // changed
        after.remove(k(1, 0));               // removed
        after.put(k(3, 0), "D");             // a tombstone written again
        after.put(k(4, 0), AwfWorldStore.TOMBSTONE); // deleted, never in the index before
        final Set<ChunkKey> changed = Set.of(k(0, 0), k(2, 0), k(3, 0)); // (2,0) unchanged bytes
        final Set<ChunkKey> removed = new HashSet<>(Set.of(k(1, 0), k(9, 9)));   // (9,9) never existed
        final Set<ChunkKey> deleted = Set.of(k(4, 0));
        final var delta = AwfWorldStore.delta(before, after, changed, removed, deleted);
        assertEquals(Map.of(k(0, 0), "A2", k(1, 0), AwfWorldStore.DROP, k(3, 0), "D", k(4, 0), AwfWorldStore.TOMBSTONE), delta);
    }
}
