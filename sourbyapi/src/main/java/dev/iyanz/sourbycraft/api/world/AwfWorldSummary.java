package dev.iyanz.sourbycraft.api.world;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 54. One world managed through {@link AuroraWorlds}, as {@link AuroraWorlds#listWorlds()}
 * returns it: what the server knows without touching storage.
 *
 * @param name the world name
 * @param loaded whether the world was loaded when the summary was taken
 * @param autoload whether the world is loaded again when the server starts
 * @param template the template the world is a copy-on-write instance of, if any
 * @param backend the kind of backend holding the world's stores (server-wide)
 * @param backendName the configured backend's name ({@code file}, {@code redis}, or another registered one)
 * @param persistence how the server's commits are written (server-wide)
 * @param deleteOnUnload whether the world is an instance made by {@link AuroraWorlds#createInstance}, deleted
 *     once it is unloaded
 */
@NullMarked
public record AwfWorldSummary(String name, boolean loaded, boolean autoload, Optional<String> template,
                              AwfBackendKind backend, String backendName, AwfPersistenceMode persistence,
                              boolean deleteOnUnload) {
    public AwfWorldSummary {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(backendName, "backendName");
        Objects.requireNonNull(persistence, "persistence");
    }
}
