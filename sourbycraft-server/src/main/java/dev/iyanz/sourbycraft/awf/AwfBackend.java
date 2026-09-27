package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where AWF stores live. {@link #FILE} (a directory beside each region folder) is built in and the
 * default; another backend is selected with {@code aurora.awf.backend}.
 *
 * <p>This is a server-internal SPI, not part of {@code sourbyapi}: a backend is code compiled
 * against the server jar and registered with {@link #register} before any listed world loads (a
 * plugin's {@code onLoad} with {@code load: STARTUP}). SourbyCraft ships no database backend and no
 * database driver. If the configured backend is not registered when a listed world loads, the
 * world fails to load; it never falls back to another backend.</p>
 *
 * <p>Storage ids are region folders relative to the server directory with {@code /} separators,
 * for example {@code world/region} or {@code world/entities}.</p>
 */
public interface AwfBackend {

    /** The name {@code aurora.awf.backend} selects, lower case. */
    String name();

    /** Whether a store exists for this id. Once one does, it is authoritative for its folder. */
    boolean exists(String storageId) throws IOException;

    /** Opens or creates the store; {@link WorldRole#READ_ONLY} must never create or delete anything. */
    AwfStore open(String storageId, WorldRole role, int retainedGenerations) throws IOException;

    /**
     * Takes a store out of use after an export, keeping its data for the operator.
     *
     * @return where or how it was kept, for the log
     */
    String retire(String storageId) throws IOException;

    /** The built-in FILE backend. */
    AwfBackend FILE = new FileBackend(java.nio.file.Path.of(""));

    /** Makes a backend selectable. A second backend with the same name is refused. */
    static void register(final AwfBackend backend) {
        Objects.requireNonNull(backend, "backend");
        if (Registry.BACKENDS.putIfAbsent(backend.name(), backend) != null) {
            throw new IllegalStateException("an AWF backend named " + backend.name() + " is already registered");
        }
    }

    /** The backend of this name, or {@code null}. */
    static AwfBackend named(final String name) {
        return Registry.BACKENDS.get(name);
    }

    /** Holder so the map is created with the interface and FILE is always present. */
    final class Registry {
        static final Map<String, AwfBackend> BACKENDS = new ConcurrentHashMap<>(Map.of("file", FILE));

        private Registry() {}
    }

    /** Stores as directories: {@code <root>/<id>.awf}. */
    final class FileBackend implements AwfBackend {
        private final java.nio.file.Path root;

        public FileBackend(final java.nio.file.Path root) {
            this.root = Objects.requireNonNull(root, "root");
        }

        /**
         * The store directory. With the empty root (the built-in backend) an id is a path as
         * given, so an absolute id stays absolute. Under any other root, an absolute id is
         * re-rooted there instead of escaping it.
         */
        java.nio.file.Path directory(final String storageId) {
            final java.nio.file.Path id = java.nio.file.Path.of(storageId + AwfRegionStorage.STORE_SUFFIX);
            if (this.root.toString().isEmpty() || !id.isAbsolute()) return this.root.resolve(id);
            return this.root.resolve(id.getRoot().relativize(id));
        }

        @Override
        public String name() {
            return "file";
        }

        @Override
        public boolean exists(final String storageId) {
            return java.nio.file.Files.isDirectory(directory(storageId));
        }

        @Override
        public AwfStore open(final String storageId, final WorldRole role, final int retainedGenerations)
            throws IOException {
            return AwfWorldStore.open(directory(storageId), role, retainedGenerations);
        }

        @Override
        public String retire(final String storageId) throws IOException {
            final java.nio.file.Path dir = directory(storageId);
            final java.nio.file.Path backup = dir.resolveSibling(dir.getFileName() + ".exported-" + System.currentTimeMillis());
            java.nio.file.Files.move(dir, backup, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return backup.toString();
        }
    }
}
