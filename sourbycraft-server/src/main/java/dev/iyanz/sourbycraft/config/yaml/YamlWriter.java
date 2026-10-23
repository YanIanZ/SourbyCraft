package dev.iyanz.sourbycraft.config.yaml;

import com.electronwill.nightconfig.core.UnmodifiableCommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ConfigWriter;
import com.electronwill.nightconfig.core.io.WritingException;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

/**
 * Writes a commented nightconfig tree as YAML, comment above each key.
 *
 * <p>Strings are always double-quoted so a value such as {@code off} survives a round trip (YAML
 * 1.1 would otherwise read it as a boolean). Keys are written as-is: every SourbyCraft key is a
 * plain lower-case name. Lists are block sequences of scalars; tables nest by indentation.</p>
 */
final class YamlWriter implements ConfigWriter {

    private static final int INDENT = 2;

    @Override
    public void write(final UnmodifiableConfig config, final Writer writer) {
        try {
            writeLevel(config, writer, 0);
            writer.flush();
        } catch (final IOException failed) {
            throw new WritingException("Could not write YAML config", failed);
        }
    }

    private void writeLevel(final UnmodifiableConfig config, final Writer out, final int indent) throws IOException {
        for (final UnmodifiableConfig.Entry entry : config.entrySet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();
            comment(config, key, out, indent);
            if (value instanceof UnmodifiableConfig table) {
                if (table.isEmpty()) {
                    line(out, indent, key + ": {}");
                } else {
                    line(out, indent, key + ":");
                    writeLevel(table, out, indent + INDENT);
                }
            } else if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    line(out, indent, key + ": []");
                } else {
                    line(out, indent, key + ":");
                    for (final Object item : list) {
                        line(out, indent + INDENT, "- " + scalar(item));
                    }
                }
            } else {
                line(out, indent, key + ": " + scalar(value));
            }
        }
    }

    private static void comment(final UnmodifiableConfig config, final String key, final Writer out, final int indent)
        throws IOException {
        if (!(config instanceof UnmodifiableCommentedConfig commented)) return;
        final String comment = commented.commentMap().get(key);
        if (comment == null || comment.isEmpty()) return;
        for (final String line : comment.split("\n", -1)) {
            final String text = line.isBlank() ? "#" : "# " + line;
            line(out, indent, text);
        }
    }

    /** A scalar as YAML: strings quoted, numbers and booleans plain, null as {@code null}. */
    static String scalar(final Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        return quote(String.valueOf(value));
    }

    static String quote(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            final char c = value.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static void line(final Writer out, final int indent, final String text) throws IOException {
        out.write(" ".repeat(indent));
        out.write(text);
        out.write('\n');
    }
}
