package dev.iyanz.sourbycraft.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableCommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.iyanz.sourbycraft.config.yaml.YamlFormat;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One-time conversion of an operator's TOML file to the YAML file SourbyCraft reads now.
 *
 * <p>The old file is parsed with nightconfig's TOML reader and written through
 * {@link YamlFormat} to {@code <name>.yml}, keeping every value and every seeded comment. Only then
 * is the old file renamed to {@code <name>.toml.migrated} (numbered when a backup already exists),
 * so a failed conversion never loses the operator's file. An unparseable TOML file is left exactly
 * as it is and named with file + near-line; the caller then keeps reading it, which fails the same
 * way and falls back to defaults.</p>
 */
public final class ConfigFormatMigration {

    /** Backup suffix; a second conversion of the same file gets {@code .migrated.2}. */
    static final String MIGRATED_SUFFIX = ".migrated";

    private ConfigFormatMigration() {
    }

    /**
     * The file to open for a logical YAML path: the YAML file when it exists, otherwise a converted
     * legacy TOML file, otherwise the YAML path itself (a new install; seeding creates it).
     */
    public static Path choose(final Path yaml, final Path legacy) {
        if (Files.isRegularFile(yaml)) return yaml;
        if (!Files.isRegularFile(legacy)) return yaml;
        return convert(yaml, legacy) ? yaml : legacy;
    }

    /** Converts {@code legacy} to {@code yaml} and renames the old file; {@code false} when it cannot. */
    public static boolean convert(final Path yaml, final Path legacy) {
        final CommentedFileConfig source;
        try {
            source = CommentedFileConfig.builder(legacy, TomlFormat.instance())
                .sync()
                .preserveInsertionOrder()
                .build();
            source.load();
        } catch (final Exception malformed) {
            SourbyLogger.error("could not convert " + legacy + " to " + yaml + " ("
                + ConfigParseErrors.describe(legacy, malformed) + "); the file is left unchanged, so the "
                + "old values are still read and the defaults apply where it cannot parse");
            return false;
        }
        try {
            final CommentedFileConfig target = CommentedFileConfig.builder(yaml, YamlFormat.DEFAULT)
                .sync()
                .preserveInsertionOrder()
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .build();
            copy(source, target);
            try {
                target.save();
            } catch (final Exception failed) {
                Files.deleteIfExists(yaml); // never leave a half-written file behind
                throw failed;
            }
        } catch (final Exception failed) {
            SourbyLogger.error("could not write " + yaml + " (" + failed.getMessage()
                + "); " + legacy + " stays in use and is not renamed");
            return false;
        }
        try {
            final Path backup = backupPath(legacy);
            Files.move(legacy, backup);
            SourbyLogger.info("converted " + legacy + " to " + yaml + "; the old file is kept as " + backup.getFileName());
        } catch (final IOException failed) {
            SourbyLogger.warn("converted " + legacy + " to " + yaml + ", but could not rename the old file ("
                + failed.getMessage() + "); " + yaml + " wins from now on");
        }
        return true;
    }

    static Path backupPath(final Path legacy) {
        Path candidate = legacy.resolveSibling(legacy.getFileName() + MIGRATED_SUFFIX);
        for (int number = 2; Files.exists(candidate); number++) {
            candidate = legacy.resolveSibling(legacy.getFileName() + MIGRATED_SUFFIX + "." + number);
        }
        return candidate;
    }

    /** TOML keeps the space after {@code #}; the YAML writer adds its own, so drop exactly one. */
    static String normalise(final String comment) {
        final StringBuilder out = new StringBuilder(comment.length());
        for (final String line : comment.split("\n", -1)) {
            if (out.length() > 0) out.append('\n');
            out.append(line.startsWith(" ") ? line.substring(1) : line);
        }
        return out.toString();
    }

    /** Copies values, tables and per-key comments, keeping the operator's order. */
    static void copy(final UnmodifiableConfig from, final CommentedConfig to) {
        for (final UnmodifiableConfig.Entry entry : from.entrySet()) {
            final Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig table && !(value instanceof List<?>)) {
                final CommentedConfig sub = to.createSubConfig();
                copy(table, sub);
                to.set(entry.getKey(), sub);
            } else if (value instanceof List<?> list) {
                to.set(entry.getKey(), new ArrayList<>(list));
            } else {
                to.set(entry.getKey(), value);
            }
            if (from instanceof UnmodifiableCommentedConfig commented) {
                final String comment = commented.commentMap().get(entry.getKey());
                if (comment != null) to.setComment(Collections.singletonList(entry.getKey()), normalise(comment));
            }
        }
    }
}
