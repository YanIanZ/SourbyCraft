package dev.iyanz.sourbycraft.config;

import java.util.List;
import java.util.Objects;

/**
 * One documented configuration key: its path, default, lifecycle, the explanation written beside it in
 * the file, and the paths it had before (rewritten to the current path once at boot by
 * {@link ConfigMigration}; not read since Build 50).
 *
 * @param path         dotted path in the current layout
 * @param defaultValue the value a fresh file gets and a missing key resolves to
 * @param lifecycle    {@link AuroraConfig.Lifecycle#LIVE} when {@code /sourbycraft reload} applies it,
 *                     {@link AuroraConfig.Lifecycle#RESTART_REQUIRED} when only a new process reads it
 * @param comment      the explanation; {@link #documentation()} prefixes lifecycle and default
 * @param legacyPaths  the paths this key had before, oldest last; empty when it was never renamed
 */
public record ConfigKey(String path, Object defaultValue, AuroraConfig.Lifecycle lifecycle, String comment,
                        List<String> legacyPaths) {

    public ConfigKey {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(comment, "comment");
        legacyPaths = List.copyOf(legacyPaths);
    }

    public static ConfigKey of(final String path, final Object defaultValue, final AuroraConfig.Lifecycle lifecycle,
                               final String comment, final String... legacyPaths) {
        return new ConfigKey(path, defaultValue, lifecycle, comment, List.of(legacyPaths));
    }

    /** The comment as written to the file: lifecycle and default first, then the explanation. */
    public String documentation() {
        return this.lifecycle + ". Default " + render(this.defaultValue) + ". " + this.comment;
    }

    /** The last path segment: the key as it appears inside its table. */
    public String name() {
        return this.path.substring(this.path.lastIndexOf('.') + 1);
    }

    /** The table the key sits in ({@code ""} at the root). */
    public String table() {
        final int dot = this.path.lastIndexOf('.');
        return dot < 0 ? "" : this.path.substring(0, dot);
    }

    static String render(final Object value) {
        if (value instanceof String text) return "\"" + text + "\"";
        if (value instanceof List<?> list) {
            final StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(render(list.get(i)));
            }
            return out.append("]").toString();
        }
        return String.valueOf(value);
    }
}
