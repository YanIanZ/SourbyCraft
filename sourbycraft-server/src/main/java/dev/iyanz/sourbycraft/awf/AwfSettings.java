package dev.iyanz.sourbycraft.awf;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which worlds Aurora World Fabric stores, and how. Every key is RESTART_REQUIRED: a world's
 * storage is chosen when the engine opens it, and a reload cannot move a loaded world.
 *
 * <p>Read straight from the files, like the bridge's keys, because worlds are opened by the engine
 * and nothing guarantees SourbyCraft's configuration has loaded first. The empty world list is the
 * default: no world is touched unless an operator names it.</p>
 *
 * @param worlds world folder names whose chunk, entity and POI storage goes through AWF
 * @param export world folder names whose AWF stores are written back to region files at load
 * @param backend the {@link AwfBackend} stores live on; {@code file} unless another is registered
 * @param persistence how each commit is written ({@link PersistenceMode#READ_ONLY} is refused)
 * @param commitIntervalSeconds how long a written chunk may wait in memory before a commit is started
 * @param residentChunks clean committed chunks kept in memory per storage after a commit
 * @param retainedGenerations committed generations kept for recovery
 * @param commitAttempts attempts per commit before it fails and the chunks stay dirty
 * @param deltaGenerations FILE backend: write each commit as a delta of the chunk index chained to
 *                         the previous generation (compacted periodically) instead of a full index.
 *                         Off by default: a full index is what builds before AWF-7 read, and the
 *                         auto-updater's rollback restores such a build. Once deltas are written, a
 *                         rollback needs this off plus one commit per world first (see
 *                         {@link AwfWorldStore}).
 * @param commitDirtyBytes start a commit once the unsaved chunks hold at least this many bytes, without
 *                         waiting for {@code commitIntervalSeconds}; {@code 0} (the default) means only the
 *                         interval applies. Bounds how much a crash can lose by size, not only by time. At
 *                         most one commit per storage runs at a time and the check runs once a second, so
 *                         this is a trigger threshold, not a hard cap on unsaved bytes.
 * @param shutdownDeadlineMillis one budget, in milliseconds, for every AWF wait at shutdown (flushes, final
 *                         commits, lock waits, backend calls, lease release), measured from the start of
 *                         shutdown. When it passes no further commit is attempted and the shutdown is reported
 *                         as incomplete, never as saved. Default 30000. See {@link AwfShutdownBudget}.
 */
public record AwfSettings(Set<String> worlds, Set<String> export, String backend, PersistenceMode persistence, int commitIntervalSeconds,
                          int residentChunks, int retainedGenerations, int commitAttempts, boolean deltaGenerations,
                          int commitDirtyBytes, int shutdownDeadlineMillis) {

    public static final String WORLDS_KEY = "aurora.awf.worlds";
    public static final String EXPORT_KEY = "aurora.awf.export";
    public static final String BACKEND_KEY = "aurora.awf.backend";
    public static final String PERSISTENCE_KEY = "aurora.awf.persistence";
    public static final String COMMIT_INTERVAL_KEY = "aurora.awf.commit-interval-seconds";
    public static final String RESIDENT_CHUNKS_KEY = "aurora.awf.resident-chunks";
    public static final String RETAINED_GENERATIONS_KEY = "aurora.awf.retained-generations";
    public static final String COMMIT_ATTEMPTS_KEY = "aurora.awf.commit-attempts";
    public static final String DELTA_GENERATIONS_KEY = "aurora.awf.delta-generations";
    public static final String COMMIT_DIRTY_BYTES_KEY = "aurora.awf.commit-dirty-bytes";
    public static final String SHUTDOWN_DEADLINE_KEY = "aurora.awf.shutdown-deadline-ms";
    /** {@link #shutdownDeadlineMillis()} unless configured. */
    public static final int DEFAULT_SHUTDOWN_DEADLINE_MILLIS = 30_000;
    static final List<String> KEYS = List.of(WORLDS_KEY, EXPORT_KEY, BACKEND_KEY, PERSISTENCE_KEY, COMMIT_INTERVAL_KEY,
        RESIDENT_CHUNKS_KEY, RETAINED_GENERATIONS_KEY, COMMIT_ATTEMPTS_KEY, DELTA_GENERATIONS_KEY,
        COMMIT_DIRTY_BYTES_KEY, SHUTDOWN_DEADLINE_KEY);

    public static final AwfSettings DEFAULT =
        new AwfSettings(Set.of(), Set.of(), "file", PersistenceMode.INCREMENTAL, 30, 1024, 3, 3, false, 0,
            DEFAULT_SHUTDOWN_DEADLINE_MILLIS);

    /** Settings with the default shutdown deadline. */
    public AwfSettings(final Set<String> worlds, final Set<String> export, final String backend,
                       final PersistenceMode persistence, final int commitIntervalSeconds, final int residentChunks,
                       final int retainedGenerations, final int commitAttempts, final boolean deltaGenerations,
                       final int commitDirtyBytes) {
        this(worlds, export, backend, persistence, commitIntervalSeconds, residentChunks, retainedGenerations,
            commitAttempts, deltaGenerations, commitDirtyBytes, DEFAULT_SHUTDOWN_DEADLINE_MILLIS);
    }

    /** These settings with another shutdown deadline. */
    public AwfSettings withShutdownDeadlineMillis(final int millis) {
        return new AwfSettings(this.worlds, this.export, this.backend, this.persistence, this.commitIntervalSeconds,
            this.residentChunks, this.retainedGenerations, this.commitAttempts, this.deltaGenerations,
            this.commitDirtyBytes, millis);
    }

    /** Settings without an export list. */
    public AwfSettings(final Set<String> worlds, final PersistenceMode persistence, final int commitIntervalSeconds,
                       final int residentChunks, final int retainedGenerations, final int commitAttempts) {
        this(worlds, Set.of(), "file", persistence, commitIntervalSeconds, residentChunks, retainedGenerations,
            commitAttempts);
    }

    /** Settings on the FILE backend. */
    public AwfSettings(final Set<String> worlds, final Set<String> export, final PersistenceMode persistence,
                       final int commitIntervalSeconds, final int residentChunks, final int retainedGenerations,
                       final int commitAttempts) {
        this(worlds, export, "file", persistence, commitIntervalSeconds, residentChunks, retainedGenerations,
            commitAttempts);
    }

    /** Settings with full-index generations (the default). */
    public AwfSettings(final Set<String> worlds, final Set<String> export, final String backend,
                       final PersistenceMode persistence, final int commitIntervalSeconds, final int residentChunks,
                       final int retainedGenerations, final int commitAttempts) {
        this(worlds, export, backend, persistence, commitIntervalSeconds, residentChunks, retainedGenerations,
            commitAttempts, false);
    }

    /** Settings without a byte trigger (the default): commits start on the interval only. */
    public AwfSettings(final Set<String> worlds, final Set<String> export, final String backend,
                       final PersistenceMode persistence, final int commitIntervalSeconds, final int residentChunks,
                       final int retainedGenerations, final int commitAttempts, final boolean deltaGenerations) {
        this(worlds, export, backend, persistence, commitIntervalSeconds, residentChunks, retainedGenerations,
            commitAttempts, deltaGenerations, 0);
    }

    public AwfSettings {
        worlds = Set.copyOf(new LinkedHashSet<>(worlds));
        export = Set.copyOf(new LinkedHashSet<>(export));
        Objects.requireNonNull(backend, "backend");
        if (!backend.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("backend name: " + backend);
        Objects.requireNonNull(persistence, "persistence");
        if (persistence == PersistenceMode.READ_ONLY) {
            throw new IllegalArgumentException("a live world cannot be stored READ_ONLY");
        }
        if (commitIntervalSeconds < 1 || residentChunks < 1 || retainedGenerations < 1 || commitAttempts < 1) {
            throw new IllegalArgumentException("AWF numbers must be at least 1");
        }
        if (commitDirtyBytes < 0) throw new IllegalArgumentException("commitDirtyBytes must not be negative");
        if (shutdownDeadlineMillis < 1) throw new IllegalArgumentException("shutdownDeadlineMillis must be at least 1");
    }

    /** Settings and the keys that were present but unusable. */
    public record Parsed(AwfSettings settings, List<String> invalidKeys) {
        public Parsed {
            invalidKeys = List.copyOf(invalidKeys);
        }
    }

    /**
     * Parses flattened keys. An unusable key falls back to its default on its own. An unusable
     * world list means no world at all: guessing which worlds to move into a new storage format
     * is the unsafe direction.
     */
    public static Parsed parse(final Map<String, Object> values) {
        final List<String> invalid = new ArrayList<>();
        final Set<String> worlds = names(values, WORLDS_KEY, invalid);
        final Set<String> export = names(values, EXPORT_KEY, invalid);
        String backend = DEFAULT.backend();
        final Object backendValue = values.get(BACKEND_KEY);
        if (backendValue != null) {
            if (backendValue instanceof String text && text.toLowerCase(Locale.ROOT).matches("[a-z0-9_-]{1,32}")) {
                backend = text.toLowerCase(Locale.ROOT);
            } else {
                invalid.add(BACKEND_KEY);
            }
        }
        PersistenceMode persistence = DEFAULT.persistence();
        final Object modeValue = values.get(PERSISTENCE_KEY);
        if (modeValue != null) {
            final String text = modeValue instanceof String s ? s.toUpperCase(Locale.ROOT) : "";
            if (text.equals("INCREMENTAL") || text.equals("CHECKPOINT") || text.equals("FULL")) {
                persistence = PersistenceMode.valueOf(text);
            } else {
                invalid.add(PERSISTENCE_KEY);
            }
        }
        final int interval = whole(values, COMMIT_INTERVAL_KEY, DEFAULT.commitIntervalSeconds(), 1, 86_400, invalid);
        final int resident = whole(values, RESIDENT_CHUNKS_KEY, DEFAULT.residentChunks(), 1, 1_000_000, invalid);
        final int retained = whole(values, RETAINED_GENERATIONS_KEY, DEFAULT.retainedGenerations(), 1, 100, invalid);
        final int attempts = whole(values, COMMIT_ATTEMPTS_KEY, DEFAULT.commitAttempts(), 1, 100, invalid);
        final boolean deltas = flag(values, DELTA_GENERATIONS_KEY, DEFAULT.deltaGenerations(), invalid);
        final int dirtyBytes = whole(values, COMMIT_DIRTY_BYTES_KEY, DEFAULT.commitDirtyBytes(), 0, Integer.MAX_VALUE,
            invalid);
        final int shutdownDeadline = whole(values, SHUTDOWN_DEADLINE_KEY, DEFAULT.shutdownDeadlineMillis(), 1,
            Integer.MAX_VALUE, invalid);
        return new Parsed(new AwfSettings(worlds, export, backend, persistence, interval, resident, retained, attempts,
            deltas, dirtyBytes, shutdownDeadline), invalid);
    }

    /** A list of plain folder names, or none at all when any entry is unusable. */
    private static Set<String> names(final Map<String, Object> values, final String key, final List<String> invalid) {
        final Object value = values.get(key);
        if (value == null) return Set.of();
        if (value instanceof List<?> list && list.stream().allMatch(AwfSettings::validWorldName)) {
            final Set<String> names = new LinkedHashSet<>();
            list.forEach(name -> names.add((String) name));
            return names;
        }
        invalid.add(key);
        return Set.of();
    }

    /** A plain folder name: no separators, no parent references, nothing empty. */
    static boolean validWorldName(final Object value) {
        return value instanceof String name && !name.isBlank() && !name.equals(".") && !name.equals("..")
            && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    private static int whole(final Map<String, Object> values, final String key, final int fallback, final int min,
                             final int max, final List<String> invalid) {
        final Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number && number.doubleValue() == Math.floor(number.doubleValue())
            && number.longValue() >= min && number.longValue() <= max) {
            return number.intValue();
        }
        invalid.add(key);
        return fallback;
    }

    /** A TOML boolean; anything else (a string "true", a number) is invalid and falls back. */
    private static boolean flag(final Map<String, Object> values, final String key, final boolean fallback,
                                final List<String> invalid) {
        final Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        invalid.add(key);
        return fallback;
    }

    /**
     * Reads the AWF keys from Aurora's file over the unified file, the precedence the config system
     * applies. A file that cannot be read means no world goes through AWF this run.
     */
    public static AwfSettings readEarly(final Path auroraFile, final Path unifiedFile) {
        final Map<String, Object> values = new HashMap<>();
        for (final Path file : List.of(unifiedFile, auroraFile)) {
            final Path source = dev.iyanz.sourbycraft.config.ConfigSources.existing(file);
            if (source == null) continue;
            try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                final var config = dev.iyanz.sourbycraft.config.ConfigSources.parse(source, reader);
                for (final String key : KEYS) {
                    final Object value = config.get(key);
                    if (value != null) values.put(key, value);
                }
            } catch (final Exception unreadable) {
                SourbyLogger.warn("Aurora World Fabric could not read "
                    + dev.iyanz.sourbycraft.config.ConfigParseErrors.describe(source, unreadable)
                    + "; no world is opened through AWF this run");
                return DEFAULT;
            }
        }
        final Parsed parsed = parse(values);
        for (final String key : parsed.invalidKeys()) {
            SourbyLogger.warn("Aurora config key '" + key + "' is invalid; AWF uses its default for it");
        }
        return parsed.settings();
    }
}
