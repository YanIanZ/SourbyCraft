package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Structure scans ({@code RegionFileStorage.scanChunk}: /locate, eyes of ender, dolphins, explorer
 * maps through {@code StructureCheck}) read AWF synchronously on the calling region thread. Vanilla
 * answers a scan that throws with {@code CHUNK_LOAD_NEEDED} and then loads the chunk, so a scan
 * that fails fast changes no answer, only where the wait happens. {@link AwfRegionStorage#scanRead}:
 * one attempt, no fence (a scan never makes the engine regenerate a chunk), and after a failed read
 * further scans that would have to reach the store are refused for {@link AwfRegionStorage#FAIL_FAST_NANOS}.
 * Scans answered from memory, a deletion or the region file are never refused.
 */
class AwfStructureScanTest {

    private static final ChunkKey STORED = new ChunkKey(5, 5);
    private static final ChunkKey OTHER_STORED = new ChunkKey(6, 5);
    private static final ChunkKey TOMBSTONE = new ChunkKey(7, 5);
    private static final ChunkKey ABSENT = new ChunkKey(8, 5);
    private static final ChunkKey RESIDENT = new ChunkKey(9, 5);

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A committed store whose reads fail while {@link #down} is set (Redis gone). */
    private static final class FlakyStore implements AwfStore {
        final Map<ChunkKey, byte[]> chunks = new HashMap<>();
        final Set<ChunkKey> tombstones = new HashSet<>();
        final AtomicBoolean down = new AtomicBoolean();
        final AtomicInteger readCalls = new AtomicInteger();

        @Override
        public Optional<byte[]> read(final ChunkKey key) throws IOException {
            this.readCalls.incrementAndGet();
            if (this.down.get() && this.chunks.containsKey(key)) {
                throw new java.net.SocketTimeoutException("Read timed out");
            }
            final byte[] bytes = this.chunks.get(key);
            return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
        }

        @Override
        public Set<ChunkKey> keys() {
            return new TreeSet<>(this.chunks.keySet());
        }

        @Override
        public boolean has(final ChunkKey key) {
            return this.chunks.containsKey(key) || this.tombstones.contains(key);
        }

        @Override
        public Set<ChunkKey> deleted() {
            return new TreeSet<>(this.tombstones);
        }

        @Override
        public long generation() {
            return 1;
        }

        @Override
        public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                   final Set<ChunkKey> deleted, final PersistenceMode mode) {
            return new CommitResult(2, this.chunks.size(), changed.size(), 0, 0);
        }
    }

    private final FlakyStore store = new FlakyStore();
    private AwfRegionStorage storage;

    @BeforeEach
    void open() throws IOException {
        AwfRegionStorage.resetFailFast();
        this.store.chunks.put(STORED, b("stored"));
        this.store.chunks.put(OTHER_STORED, b("other"));
        this.store.tombstones.add(TOMBSTONE);
        final AwfWorld world = new AwfWorld("world/region", WorldRole.VANILLA, null, this.store, 0);
        final AwfSettings settings = new AwfSettings(Set.of("world"), PersistenceMode.INCREMENTAL, 30, 1, 2, 2);
        this.storage = new AwfRegionStorage("world/region", world, settings, Runnable::run, 0L, () -> {});
        this.storage.write(RESIDENT.x(), RESIDENT.z(), b("in memory"));
    }

    @AfterEach
    void closeWindow() {
        AwfRegionStorage.resetFailFast();
    }

    @Test
    void aHealthyScanAnswersLikeARead() throws IOException {
        assertArrayEquals(b("stored"), this.storage.scanRead(STORED.x(), STORED.z()));
        assertSame(AwfRegionStorage.DELETED, this.storage.scanRead(TOMBSTONE.x(), TOMBSTONE.z()));
        assertNull(this.storage.scanRead(ABSENT.x(), ABSENT.z()), "not AWF's: the region file answers");
        assertArrayEquals(b("in memory"), this.storage.scanRead(RESIDENT.x(), RESIDENT.z()));
        assertEquals(0, this.storage.refusedScans());
    }

    @Test
    void aFailedScanMakesOneAttemptAndFencesNothing() {
        this.store.down.set(true);
        final IOException failed = assertThrows(IOException.class, () -> this.storage.scanRead(STORED.x(), STORED.z()));
        assertFalse(failed instanceof AwfRegionStorage.ScanRefused, "the first failure is the backend's own");
        assertEquals(1, this.store.readCalls.get(), "a scan is not retried: vanilla's fallback loads the chunk");
        assertTrue(this.storage.unreadable().isEmpty(), "a scan never causes a regeneration, so it never fences");
        assertEquals(1, this.storage.scanFailures());
    }

    @Test
    void afterAFailedScanStoredChunksAreRefusedWithoutAskingTheStore() throws IOException {
        this.store.down.set(true);
        assertThrows(IOException.class, () -> this.storage.scanRead(STORED.x(), STORED.z()));
        this.store.down.set(false); // even a store that is back is not asked inside the window
        final int calls = this.store.readCalls.get();
        assertThrows(AwfRegionStorage.ScanRefused.class, () -> this.storage.scanRead(OTHER_STORED.x(), OTHER_STORED.z()));
        assertEquals(calls, this.store.readCalls.get(), "refused before any backend call");
        assertEquals(1, this.storage.refusedScans());

        // Answers that need no backend read are the same as outside the window.
        assertSame(AwfRegionStorage.DELETED, this.storage.scanRead(TOMBSTONE.x(), TOMBSTONE.z()));
        assertNull(this.storage.scanRead(ABSENT.x(), ABSENT.z()));
        assertArrayEquals(b("in memory"), this.storage.scanRead(RESIDENT.x(), RESIDENT.z()));
        assertEquals(1, this.storage.refusedScans());
    }

    @Test
    void theRefusalWindowEnds() {
        this.store.down.set(true);
        assertThrows(IOException.class, () -> this.storage.scanRead(STORED.x(), STORED.z()));
        final long now = System.nanoTime();
        assertTrue(AwfRegionStorage.scanRefusing(now));
        assertFalse(AwfRegionStorage.scanRefusing(now + AwfRegionStorage.FAIL_FAST_NANOS + 1));
    }

    @Test
    void aChunkLoadReadIsNeverRefusedByTheScanWindow() throws IOException {
        this.store.down.set(true);
        assertThrows(IOException.class, () -> this.storage.scanRead(STORED.x(), STORED.z()));
        this.store.down.set(false);
        assertArrayEquals(b("other"), this.storage.read(OTHER_STORED.x(), OTHER_STORED.z()),
            "the load path keeps its own retry/fence policy");
    }

    @Test
    void scanChunkUsesTheScanRead() throws IOException {
        final ClassNode node = new ClassNode();
        try (InputStream in = RegionFileStorage.class.getResourceAsStream("RegionFileStorage.class")) {
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        final MethodNode scan = node.methods.stream().filter(m -> m.name.equals("scanChunk")).findFirst().orElseThrow();
        boolean scanRead = false;
        boolean read = false;
        for (final var insn : scan.instructions.toArray()) {
            if (insn instanceof MethodInsnNode m && m.owner.equals("dev/iyanz/sourbycraft/awf/AwfRegionStorage")) {
                scanRead |= m.name.equals("scanRead");
                read |= m.name.equals("read");
            }
        }
        assertTrue(scanRead, "scanChunk reads AWF through scanRead");
        assertFalse(read, "scanChunk no longer uses the load-path read");
    }
}
