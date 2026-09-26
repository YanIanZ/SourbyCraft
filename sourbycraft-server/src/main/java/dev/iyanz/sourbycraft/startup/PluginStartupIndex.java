package dev.iyanz.sourbycraft.startup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The first Aurora Instant Startup consumer: an index of every plugin jar's descriptor, reused
 * across warm restarts when the jar's bytes have not changed.
 *
 * <p>Deliberately limited to analysis. It hashes jars and parses descriptors — work the startup
 * doc lists as safe to run concurrently — on a bounded STARTUP lane, and it never loads, enables
 * or orders plugins. {@code onLoad}/{@code onEnable}, service registration, events and world
 * mutation are untouched and stay on the server's own sequence.</p>
 *
 * <p>What it buys today is a diagnostic, not a faster plugin load: before the plugin manager
 * runs, the operator is told which jars the region-threading base will refuse for not declaring
 * {@code folia-supported}/{@code canvas-supported}. Whether the cache makes that analysis cheaper
 * is measured per boot in {@link StartupTelemetry}, cold and warm separately.</p>
 */
public final class PluginStartupIndex {

    /** Payload recorded for a jar that carries no plugin descriptor, so it is not re-parsed. */
    static final String NO_DESCRIPTOR = "none";
    /** Thread name prefix; {@code ExecutionLane.STARTUP} matches on it. */
    public static final String THREAD_PREFIX = "SourbyCraft-Startup-";

    /**
     * One jar in the plugins folder.
     *
     * @param jar the jar
     * @param descriptor its descriptor, or {@code null} when it has none or could not be read
     * @param cacheHit whether the descriptor came from the cache
     * @param failure why the jar could not be analysed, or {@code null}
     * @param fingerprint what the jar hashed to, or {@code null} when it could not be read
     */
    public record Indexed(Path jar, PluginDescriptor descriptor, boolean cacheHit, String failure,
                          SourceFingerprint fingerprint) {}

    /** The index and what building it cost. */
    public record Result(List<Indexed> plugins, StartupTelemetry.Summary telemetry,
                         StartupCache.LoadStatus cacheStatus, boolean cacheWritten) {

        /** Jars with a descriptor the base will refuse. */
        public List<Indexed> undeclared() {
            return this.plugins.stream()
                .filter(p -> p.descriptor() != null && !p.descriptor().regionSupported())
                .toList();
        }
    }

    private PluginStartupIndex() {}

    /**
     * Builds the index.
     *
     * @param pluginsDir the plugins folder; a missing folder is an empty index
     * @param cacheFile where the cache lives, or {@code null} to analyse without a cache
     * @param environment the compatibility key for this process
     * @param parallelism STARTUP lane width, at least 1
     * @param warnOnce receives one line when cached content was discarded
     */
    public static Result build(final Path pluginsDir, final Path cacheFile, final CacheEnvironment environment,
                               final int parallelism, final java.util.function.Consumer<String> warnOnce) {
        final StartupTelemetry telemetry = new StartupTelemetry();
        final long begun = System.nanoTime();

        final List<Path> jars;
        try {
            jars = listJars(pluginsDir);
        } catch (final IOException unreadable) {
            warnOnce.accept("plugins folder could not be listed: " + unreadable.getMessage());
            return new Result(List.of(), telemetry.summary(System.nanoTime() - begun),
                StartupCache.LoadStatus.MISSING, false);
        }

        final long cacheBegun = System.nanoTime();
        final StartupCache.Loaded loaded = cacheFile == null
            ? new StartupCache.Loaded(StartupCache.LoadStatus.MISSING, java.util.Map.of(), 0)
            : StartupCache.load(cacheFile, environment);
        telemetry.phase("cache-read", System.nanoTime() - cacheBegun);
        telemetry.discarded(loaded.discardedEntries());
        if (loaded.discardedAnything()) {
            warnOnce.accept("Aurora startup cache " + describe(loaded)
                + "; affected entries are rebuilt and boot continues");
        }

        final long analyseBegun = System.nanoTime();
        final List<Indexed> indexed = analyse(jars, pluginsDir, loaded, telemetry, parallelism);
        telemetry.phase("analyse", System.nanoTime() - analyseBegun);

        final boolean changed = cacheFile != null && (telemetry.misses() > 0 || loaded.discardedAnything()
            || loaded.entries().size() != cacheableCount(indexed));
        boolean written = false;
        if (changed) {
            final long writeBegun = System.nanoTime();
            try {
                StartupCache.write(cacheFile, environment, entries(indexed, pluginsDir));
                written = true;
            } catch (final IOException unwritable) {
                warnOnce.accept("Aurora startup cache could not be written: " + unwritable.getMessage());
            }
            telemetry.phase("cache-write", System.nanoTime() - writeBegun);
        }
        return new Result(List.copyOf(indexed), telemetry.summary(System.nanoTime() - begun),
            loaded.status(), written);
    }

    private static String describe(final StartupCache.Loaded loaded) {
        return switch (loaded.status()) {
            case STALE_ENVIRONMENT -> "was written by a different Minecraft/ABI/format/Java and was discarded";
            case CORRUPT -> "was not a valid startup cache and was discarded";
            case IO_ERROR -> "could not be read and was ignored";
            default -> "had " + loaded.discardedEntries() + " corrupt entr"
                + (loaded.discardedEntries() == 1 ? "y" : "ies") + " discarded";
        };
    }

    private static List<Path> listJars(final Path pluginsDir) throws IOException {
        if (!Files.isDirectory(pluginsDir)) {
            return List.of();
        }
        final List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (final Path jar : stream) {
                if (Files.isRegularFile(jar)) jars.add(jar);
            }
        }
        jars.sort(Comparator.comparing(Path::toString));
        return jars;
    }

    private static List<Indexed> analyse(final List<Path> jars, final Path pluginsDir,
                                         final StartupCache.Loaded loaded, final StartupTelemetry telemetry,
                                         final int parallelism) {
        if (jars.isEmpty()) {
            return List.of();
        }
        final int width = Math.max(1, Math.min(parallelism, jars.size()));
        // Bounded on both axes: a fixed number of threads and a queue sized to the work, so
        // nothing here can grow without limit or fall back to running on the caller.
        final ThreadPoolExecutor lane = new ThreadPoolExecutor(width, width, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(jars.size()), startupThreads(), new ThreadPoolExecutor.AbortPolicy());
        try {
            final List<Future<Indexed>> futures = new ArrayList<>(jars.size());
            for (final Path jar : jars) {
                futures.add(lane.submit(() -> analyseOne(jar, key(pluginsDir, jar), loaded, telemetry)));
            }
            final List<Indexed> out = new ArrayList<>(jars.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    out.add(futures.get(i).get());
                } catch (final ExecutionException failure) {
                    out.add(new Indexed(jars.get(i), null, false, String.valueOf(failure.getCause()), null));
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    out.add(new Indexed(jars.get(i), null, false, "interrupted", null));
                }
            }
            return out;
        } finally {
            lane.shutdownNow();
        }
    }

    private static Indexed analyseOne(final Path jar, final String key, final StartupCache.Loaded loaded,
                                      final StartupTelemetry telemetry) {
        final SourceFingerprint fingerprint;
        try {
            fingerprint = SourceFingerprint.of(jar);
        } catch (final IOException unreadable) {
            telemetry.miss();
            return new Indexed(jar, null, false, "unreadable: " + unreadable.getMessage(), null);
        }
        final StartupCache.Entry cached = loaded.entries().get(key);
        // Size/mtime can only rule reuse out early; reuse itself requires the SHA-256 to match.
        if (cached != null && !cached.source().cheaplyDiffersFrom(fingerprint.size(), fingerprint.modifiedMillis())
            && cached.source().sha256().equals(fingerprint.sha256())) {
            if (NO_DESCRIPTOR.equals(cached.payload())) {
                telemetry.hit();
                return new Indexed(jar, null, true, null, fingerprint);
            }
            final PluginDescriptor descriptor = PluginDescriptor.decode(cached.payload());
            if (descriptor != null) {
                telemetry.hit();
                return new Indexed(jar, descriptor, true, null, fingerprint);
            }
            telemetry.discarded(1);
        }
        telemetry.miss();
        try {
            final PluginDescriptor descriptor = PluginDescriptor.read(jar);
            return new Indexed(jar, descriptor, false, null, fingerprint);
        } catch (final IOException unreadable) {
            return new Indexed(jar, null, false, "unreadable: " + unreadable.getMessage(), fingerprint);
        }
    }

    private static int cacheableCount(final List<Indexed> indexed) {
        return (int)indexed.stream().filter(i -> i.failure() == null).count();
    }

    private static List<StartupCache.Entry> entries(final List<Indexed> indexed, final Path pluginsDir) {
        final List<StartupCache.Entry> out = new ArrayList<>(indexed.size());
        for (final Indexed item : indexed) {
            // Unreadable jars are not cached: the next boot should look again.
            if (item.failure() != null || item.fingerprint() == null) continue;
            out.add(new StartupCache.Entry(key(pluginsDir, item.jar()), item.fingerprint(),
                item.descriptor() == null ? NO_DESCRIPTOR : item.descriptor().encode()));
        }
        return out;
    }

    private static String key(final Path pluginsDir, final Path jar) {
        return pluginsDir.relativize(jar).toString().replace('\\', '/');
    }

    private static ThreadFactory startupThreads() {
        final AtomicInteger next = new AtomicInteger(1);
        return task -> {
            final Thread thread = new Thread(task, THREAD_PREFIX + next.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
