package dev.iyanz.sourbycraft;

import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Test-side door to the package-private {@code SourbyCraftConfig.installForTesting} seam, for tests
 * that live in other packages (maxplayers, lang, ...). Replaces swapping the private static
 * {@code loaded} field by reflection.
 */
public final class SourbyCraftConfigTesting {

    private SourbyCraftConfigTesting() {}

    /** A test body that may throw. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Runs {@code action} with {@code values} as the utility snapshot and a throw-away temporary
     * file as the utility TOML, so even a {@code setAndSave} cannot touch the working directory.
     */
    public static void withConfig(final Map<String, ?> values, final ThrowingRunnable action) throws Exception {
        final Path dir = Files.createTempDirectory("sourbycraft-config-test");
        try {
            withConfigFile(values, dir.resolve("sourbycraft_global_config.toml"), action);
        } finally {
            deleteTree(dir);
        }
    }

    /**
     * Runs {@code action} with {@code values} as the utility snapshot and {@code file} as the
     * utility TOML that {@code SourbyCraftConfig.setAndSave} opens and writes. The caller owns
     * {@code file} (typically under a JUnit {@code @TempDir}). The previous state is restored
     * afterwards even if {@code action} throws.
     */
    public static void withConfigFile(final Map<String, ?> values, final Path file,
                                      final ThrowingRunnable action) throws Exception {
        @SuppressWarnings("unchecked")
        final Map<String, Object> typed = (Map<String, Object>) values;
        try (AutoCloseable restore = SourbyCraftConfig.installForTesting(new ConfigSnapshot(typed), file)) {
            action.run();
        }
    }

    private static void deleteTree(final Path dir) throws java.io.IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (final Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
