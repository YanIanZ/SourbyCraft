package dev.iyanz.aurora.engine.configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * The two engine configuration files and their one-time rename from the Canvas-era names.
 *
 * <p>Build 49 renamed {@code config/canvas-server.yml} to {@code config/aurora-server.yml} and
 * {@code config/canvas-worlds.yml} to {@code config/aurora-worlds.yml} (owner decision 2026-10-22,
 * {@code docs/development/CONFIG-FILE-RENAME-PLAN.md}). The per-world {@code canvas-patch.yml}
 * keeps its name: it lives in each dimension's storage directory and is out of this scope.</p>
 *
 * <p>{@link #migrate} runs before every load of a file (boot and {@code /sourbycraft reload}):</p>
 * <ul>
 *   <li>neither name exists: nothing happens, the loader writes the defaults under the new name;</li>
 *   <li>only the old name exists: its document is copied under the new name with the new header
 *       (values and per-key comments kept; the old header dropped), published atomically through a
 *       {@code .migrating} sibling, and the old file is renamed to {@code <old>.migrated}. One INFO
 *       line. The copy is never merged with anything, and nothing is deleted;</li>
 *   <li>both exist: the new file is read, the old one is ignored, and one WARN names the stale file;</li>
 *   <li>the old file cannot be parsed: the load fails with a message naming both paths; nothing
 *       is written or renamed, so the operator's file is intact for repair.</li>
 * </ul>
 */
public final class EngineConfigFiles {

    /** Directory of both files, relative to the server's working directory. */
    public static final String DIRECTORY = "config";
    public static final String SERVER = "aurora-server.yml";
    public static final String WORLDS = "aurora-worlds.yml";
    public static final String LEGACY_SERVER = "canvas-server.yml";
    public static final String LEGACY_WORLDS = "canvas-worlds.yml";
    /** Appended to an old file's name once its content has been copied under the new name. */
    public static final String MIGRATED_SUFFIX = ".migrated";
    /** Sibling the new document is written to before the atomic publish; never a valid config name. */
    static final String MIGRATING_SUFFIX = ".migrating";

    private EngineConfigFiles() {}

    public static Path directory() {
        return Path.of(DIRECTORY).toAbsolutePath().normalize();
    }

    /** {@code config/aurora-server.yml}, resolved against the working directory. */
    public static Path serverFile() {
        return directory().resolve(SERVER);
    }

    /** {@code config/aurora-worlds.yml}, resolved against the working directory. */
    public static Path worldsFile() {
        return directory().resolve(WORLDS);
    }

    public static Path legacyServerFile() {
        return directory().resolve(LEGACY_SERVER);
    }

    public static Path legacyWorldsFile() {
        return directory().resolve(LEGACY_WORLDS);
    }

    /** How a file is named in messages: {@code config/<name>}, whatever the working directory is. */
    public static String display(final Path file) {
        return DIRECTORY + "/" + file.getFileName();
    }

    public enum Outcome {
        /** Nothing to do: the new file exists alone, or neither file exists. */
        NOTHING,
        /** The old file's content now lives under the new name; the old file was renamed. */
        MIGRATED,
        /** Both names exist: the new one is read, the old one is stale. */
        STALE_LEGACY
    }

    /**
     * What {@link #migrate} did.
     *
     * @param outcome       see {@link Outcome}
     * @param current       the file the loader reads
     * @param legacy        the old name
     * @param renamedLegacy where the old file went ({@link Outcome#MIGRATED} only; {@code null} when
     *                      the content was copied but the old file could not be renamed)
     * @param message       the one line to log, empty for {@link Outcome#NOTHING}
     */
    public record Result(Outcome outcome, Path current, Path legacy, @Nullable Path renamedLegacy, String message) {

        /** INFO for a migration, WARN for a stale old file, silence otherwise. */
        public void log(final Logger logger) {
            switch (this.outcome) {
                case MIGRATED -> logger.info(this.message);
                case STALE_LEGACY -> logger.warn(this.message);
                case NOTHING -> { }
            }
        }
    }

    /**
     * Copies {@code legacy} under {@code current} once, as described in the class comment.
     *
     * @param current the new file name
     * @param legacy  the old file name
     * @param header  the header comment the new file gets (the old file's header is dropped)
     * @return what happened; the caller logs it
     * @throws IllegalStateException when the old file exists but cannot be parsed or copied; nothing
     *                               has been written or renamed in that case
     */
    public static Result migrate(final Path current, final Path legacy, final String... header) {
        final String currentName = display(current);
        final String legacyName = display(legacy);
        if (Files.exists(current)) {
            if (Files.exists(legacy)) {
                return new Result(Outcome.STALE_LEGACY, current, legacy, null,
                    "Both " + currentName + " and " + legacyName + " exist; " + currentName + " is read and "
                        + legacyName + " is ignored. Rename or remove the old file (its settings were not merged).");
            }
            return new Result(Outcome.NOTHING, current, legacy, null, "");
        }
        if (!Files.exists(legacy)) {
            return new Result(Outcome.NOTHING, current, legacy, null, "");
        }

        final Node document;
        try {
            document = ConfigurationProvider.composeFromFile(legacy);
        } catch (final IOException | RuntimeException unreadable) {
            throw new IllegalStateException(legacyName + " could not be migrated to " + currentName + ": "
                + unreadable.getMessage() + ". Fix the old file (or move it away to start from defaults); "
                + "nothing was written or renamed.", unreadable);
        }

        final Path staging = current.resolveSibling(current.getFileName() + MIGRATING_SUFFIX);
        final boolean empty = document == null;
        try {
            if (!empty) {
                // The old header ("the historical ... path is retained") composes as the leading block
                // comments of the first key, up to the blank line write() put after it; per-key comments
                // follow that blank line and survive.
                document.setBlockComments(null);
                dropOldHeader(document);
                document.setTag(Tag.MAP);
                ConfigurationProvider.write(staging, document, false, header);
                Files.move(staging, current, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (final IOException failure) {
            try {
                Files.deleteIfExists(staging);
            } catch (final IOException ignored) {
                // the staging name is not a config name; a leftover is overwritten next time
            }
            throw new UncheckedIOException(legacyName + " could not be migrated to " + currentName + ": "
                + failure.getMessage() + ". The old file was not changed.", failure);
        }

        Path renamed;
        String note;
        try {
            renamed = Files.move(legacy, freeMigratedName(legacy));
            note = "old file renamed to " + display(renamed) + ", not read again";
        } catch (final IOException notRenamed) {
            renamed = null;
            note = "old file could NOT be renamed (" + notRenamed.getMessage() + "); it is ignored and will be "
                + "reported as stale on every load until it is moved away";
        }
        final String message = empty
            ? legacyName + " was empty; " + currentName + " is created with the defaults (" + note + ")"
            : "Migrated " + legacyName + " -> " + currentName + " (values and comments kept; " + note + ")";
        return new Result(Outcome.MIGRATED, current, legacy, renamed, message);
    }

    /**
     * Removes the file header from a composed document: the comment lines before the first truly blank
     * line above the first key, when they look like one of the engine's headers. An operator who
     * deleted the header keeps the first key's own comments.
     */
    static void dropOldHeader(final Node document) {
        if (!(document instanceof MappingNode mapping) || mapping.getValue().isEmpty()) return;
        final Node firstKey = mapping.getValue().get(0).getKeyNode();
        final List<CommentLine> comments = firstKey.getBlockComments();
        if (comments == null) return;
        int blank = -1;
        for (int i = 0; i < comments.size(); i++) {
            if (comments.get(i).getCommentType() == CommentType.BLANK_LINE) {
                blank = i;
                break;
            }
        }
        if (blank < 0) return;
        boolean header = false;
        for (int i = 0; i < blank; i++) {
            final String text = comments.get(i).getValue();
            if (text != null && (text.contains("Aurora /") || text.contains("historical") || text.contains("config/"))) header = true;
        }
        if (header) firstKey.setBlockComments(new ArrayList<>(comments.subList(blank + 1, comments.size())));
    }

    /** {@code <legacy>.migrated}, or {@code .migrated.1}, {@code .migrated.2} ... when that is taken. */
    static Path freeMigratedName(final Path legacy) {
        final Path first = legacy.resolveSibling(legacy.getFileName() + MIGRATED_SUFFIX);
        if (!Files.exists(first)) {
            return first;
        }
        for (int n = 1; ; n++) {
            final Path candidate = legacy.resolveSibling(legacy.getFileName() + MIGRATED_SUFFIX + "." + n);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }
}
