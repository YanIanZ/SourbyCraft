package dev.iyanz.sourbycraft.testplugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * How many files under a directory this JVM holds open, for the unload fixture's "world folder
 * released" check. Linux reads {@code /proc/self/fd}; elsewhere {@code lsof -p <pid>} is used when
 * it exists. When neither can answer the result is empty and the fixture says so (it never reports
 * {@code 0} for a check that did not run).
 */
final class OpenFiles {

    private OpenFiles() {}

    /** The count of open files whose path is {@code dir} or below it, or empty when unknowable. */
    static OptionalInt under(final Path dir) {
        final Path root = dir.toAbsolutePath().normalize();
        final Path proc = Path.of("/proc/self/fd");
        if (Files.isDirectory(proc)) {
            try (Stream<Path> fds = Files.list(proc)) {
                int open = 0;
                for (final Path fd : (Iterable<Path>)fds::iterator) {
                    try {
                        if (Files.readSymbolicLink(fd).normalize().startsWith(root)) open++;
                    } catch (final IOException closedMeanwhile) {
                        // The listing's own descriptor, or one closed since: not a leak.
                    }
                }
                return OptionalInt.of(open);
            } catch (final IOException unreadable) {
                return OptionalInt.empty();
            }
        }
        return lsof(root);
    }

    private static OptionalInt lsof(final Path root) {
        try {
            final Process process = new ProcessBuilder("lsof", "-w", "-p", Long.toString(ProcessHandle.current().pid()), "-Fn")
                .redirectErrorStream(true).start();
            final String out;
            try (var in = process.getInputStream()) {
                out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return OptionalInt.empty();
            }
            return OptionalInt.of(countLsofNames(out, root));
        } catch (final IOException | RuntimeException unavailable) {
            return OptionalInt.empty();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return OptionalInt.empty();
        }
    }

    /** {@code lsof -Fn} prints one {@code n<path>} line per open file; counts those under {@code root}. */
    static int countLsofNames(final String output, final Path root) {
        final String prefix = root.toString();
        int open = 0;
        for (final String line : (List<String>)output.lines().toList()) {
            if (!line.startsWith("n")) continue;
            final String name = line.substring(1);
            if (name.equals(prefix) || name.startsWith(prefix + "/")) open++;
        }
        return open;
    }
}
