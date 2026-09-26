package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * An upper source over a lower one: a committed instance store over its template, for example.
 * The upper source wins for any chunk it has; the lower is only read for the rest.
 */
public record LayeredSource(ChunkSource upper, ChunkSource lower) implements ChunkSource {

    public LayeredSource {
        Objects.requireNonNull(upper, "upper");
        Objects.requireNonNull(lower, "lower");
    }

    @Override
    public Optional<byte[]> read(final ChunkKey key) throws IOException {
        final Optional<byte[]> top = this.upper.read(key);
        return top.isPresent() ? top : this.lower.read(key);
    }

    @Override
    public Set<ChunkKey> keys() {
        final Set<ChunkKey> keys = new TreeSet<>(this.upper.keys());
        keys.addAll(this.lower.keys());
        return keys;
    }
}
