package dev.iyanz.sourbycraft.config.yaml;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.ConfigFormat;
import com.electronwill.nightconfig.core.io.ConfigParser;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.core.io.ParsingMode;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

/** Reads a YAML mapping into a commented nightconfig tree, keeping the comments above each key. */
final class YamlParser implements ConfigParser<CommentedConfig> {

    private final YamlFormat format;

    YamlParser(final YamlFormat format) {
        this.format = format;
    }

    @Override
    public ConfigFormat<CommentedConfig> getFormat() {
        return this.format;
    }

    @Override
    public CommentedConfig parse(final Reader reader) {
        final CommentedConfig config = this.format.createConfig();
        parse(reader, config, ParsingMode.MERGE);
        return config;
    }

    @Override
    public void parse(final Reader reader, final Config destination, final ParsingMode mode) {
        final Node root;
        try {
            root = yaml().compose(reader);
        } catch (final YAMLException malformed) {
            throw new ParsingException(malformed.getMessage(), malformed);
        }
        if (root == null) return; // an empty (or comment-only) file is an empty config
        if (!(root instanceof MappingNode mapping)) {
            throw new ParsingException("The top level of a SourbyCraft config file must be a YAML mapping");
        }
        fill(mapping, destination, mode);
    }

    private void fill(final MappingNode mapping, final Config destination, final ParsingMode mode) {
        for (final NodeTuple tuple : mapping.getValue()) {
            final String key = keyOf(tuple.getKeyNode());
            final Object value = valueOf(tuple.getValueNode());
            if (mode == ParsingMode.MERGE && destination.contains(key)) {
                throw new ParsingException("Duplicate config key '" + key + "'");
            }
            destination.set(key, value);
            if (destination instanceof CommentedConfig commented) {
                final String comment = commentOf(tuple.getKeyNode());
                if (comment != null) commented.setComment(Collections.singletonList(key), comment);
            }
        }
    }

    private String keyOf(final Node node) {
        if (!(node instanceof ScalarNode scalar)) {
            throw new ParsingException("A config key must be a scalar");
        }
        return scalar.getValue();
    }

    private Object valueOf(final Node node) {
        if (node instanceof MappingNode mapping) {
            final CommentedConfig sub = this.format.createConfig();
            fill(mapping, sub, ParsingMode.MERGE);
            return sub;
        }
        if (node instanceof SequenceNode sequence) {
            final List<Object> values = new ArrayList<>(sequence.getValue().size());
            for (final Node item : sequence.getValue()) {
                values.add(scalarOf(item));
            }
            return values;
        }
        return scalarOf(node);
    }

    /**
     * A YAML scalar as a config value. Only {@code true}/{@code false} become booleans: the YAML
     * 1.1 resolver also tags {@code off}, {@code on}, {@code yes} and {@code no}, and a config that
     * means the string {@code "off"} must not turn into {@code false} (the bridge mode key already
     * lost that fight once).
     */
    private Object scalarOf(final Node node) {
        if (!(node instanceof ScalarNode scalar)) {
            throw new ParsingException("Unsupported YAML node: " + node.getNodeId());
        }
        final String value = scalar.getValue();
        final Tag tag = scalar.getTag();
        if (Tag.NULL.equals(tag)) return null;
        if (Tag.BOOL.equals(tag)) {
            if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
            return value;
        }
        if (Tag.INT.equals(tag)) {
            final String digits = value.replace("_", "");
            try {
                return Integer.parseInt(digits);
            } catch (final NumberFormatException notAnInt) {
                try {
                    return Long.parseLong(digits);
                } catch (final NumberFormatException notALong) {
                    return value;
                }
            }
        }
        if (Tag.FLOAT.equals(tag)) {
            try {
                return Double.parseDouble(value.replace("_", ""));
            } catch (final NumberFormatException notANumber) {
                return value;
            }
        }
        return value;
    }

    /** The block comment above a key, as nightconfig stores it (one line per element). */
    private static String commentOf(final Node keyNode) {
        final List<CommentLine> lines = keyNode.getBlockComments();
        if (lines == null || lines.isEmpty()) return null;
        final StringBuilder text = new StringBuilder();
        for (final CommentLine line : lines) {
            String value = line.getValue();
            if (value.startsWith("#")) value = value.substring(1);
            if (value.startsWith(" ")) value = value.substring(1);
            if (text.length() > 0) text.append('\n');
            text.append(value);
        }
        return text.toString();
    }

    private static Yaml yaml() {
        final LoaderOptions loader = new LoaderOptions();
        loader.setProcessComments(true);
        final DumperOptions dumper = new DumperOptions();
        dumper.setProcessComments(true);
        return new Yaml(new SafeConstructor(loader), new Representer(dumper), dumper, loader);
    }
}
