package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * A world's committed chunks on some backend. {@link AwfWorldStore} is the FILE backend; others
 * come from an {@link AwfBackend}.
 *
 * <p>Contract every backend must keep, because {@link AwfWorld} and the engine rely on it:</p>
 * <ul>
 *   <li>{@link #commit} is atomic: afterwards either every change is visible or none is, also
 *       after a crash or a lost connection part-way.</li>
 *   <li>A deleted chunk is remembered ({@link #has} true, {@link #read} empty) until it is
 *       written again, so it shadows the base.</li>
 *   <li>Blocking methods are called only from the storage lane, flush/close paths, or a chunk
 *       read the engine would otherwise make from a region file; never while holding a region's
 *       tick for longer than such a read.</li>
 *   <li>Returned arrays belong to the caller; passed arrays are not retained.</li>
 * </ul>
 */
public interface AwfStore extends ChunkSource {

    /** What a commit did. */
    record CommitResult(long generation, int chunks, int objectsWritten, long bytesWritten, int objectsRemoved) {}

    /** Whether the committed state says anything about a chunk: bytes or a deletion. */
    boolean has(ChunkKey key);

    /** Chunks this world deleted: they read as absent and shadow the base. */
    Set<ChunkKey> deleted();

    /** The committed generation, 0 before the first commit. */
    long generation() throws IOException;

    /**
     * Commits a new generation: the current state with {@code changed} applied, {@code removed}
     * forgotten (falling through to the base again) and {@code deleted} recorded as deletions.
     */
    CommitResult commit(Map<ChunkKey, byte[]> changed, Set<ChunkKey> removed, Set<ChunkKey> deleted,
                        PersistenceMode mode) throws IOException;
}
