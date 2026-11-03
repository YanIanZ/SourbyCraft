package dev.iyanz.sourbycraft.api.world;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 54. One managed world's storage state, as {@link AuroraWorlds#info(String)} returns it.
 *
 * @param world the summary {@link AuroraWorlds#listWorlds()} gives for the world
 * @param storeFormat how the stores are kept: {@code awf-generations} on the FILE backend (a generation store
 *     with a content-addressed object store per storage folder), otherwise the backend's name
 * @param storedBytes FILE backend only: the bytes the world's own stores occupy on disk (its template's are not
 *     counted), summed over at most {@value #MAX_COUNTED_FILES} files; empty on other backends, or when the stores
 *     hold more files than that or could not be read
 * @param dirtyChunks chunks written or deleted and not yet committed, over the world's open storages; 0 while the
 *     world is unloaded
 * @param openStorages the world's AWF storages that are open (region, entities and poi: 3 while it is loaded)
 * @param lastCommit FILE backend: when the newest committed generation of any of the world's stores was written
 *     (the modification time of its {@code CURRENT} pointer, so periodic commits count). Other backends: when the
 *     last {@link AuroraWorlds#save} (or saving {@link AuroraWorlds#unload}) through this service on this server
 *     completed durably since startup. Empty when neither is known
 */
@NullMarked
public record AwfWorldInfo(AwfWorldSummary world, String storeFormat, OptionalLong storedBytes, int dirtyChunks,
                           int openStorages, Optional<Instant> lastCommit) {

    /** The most store files {@link #storedBytes} adds up before it reports the size as unknown. */
    public static final int MAX_COUNTED_FILES = 200_000;

    public AwfWorldInfo {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(storeFormat, "storeFormat");
        Objects.requireNonNull(storedBytes, "storedBytes");
        Objects.requireNonNull(lastCommit, "lastCommit");
    }

    /** The world name. */
    public String name() {
        return this.world.name();
    }

    /** Whether the world was loaded when the information was gathered. */
    public boolean loaded() {
        return this.world.loaded();
    }

    /** The kind of backend holding the world's stores. */
    public AwfBackendKind backend() {
        return this.world.backend();
    }
}
