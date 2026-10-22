package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Somewhere serialized chunks can be read from: a template file, a committed world store, or a
 * world's resident state layered over either.
 *
 * <p>Returned arrays are the caller's to keep; implementations never hand out an array they will
 * later mutate, and never retain one a caller passed in.</p>
 */
public interface ChunkSource {

    /** The chunk's serialized bytes, or empty when this source has no such chunk. */
    Optional<byte[]> read(ChunkKey key) throws IOException;

    /** Every chunk this source can return. */
    Set<ChunkKey> keys();
}
