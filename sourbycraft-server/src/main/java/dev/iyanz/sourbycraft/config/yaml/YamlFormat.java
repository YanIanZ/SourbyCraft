package dev.iyanz.sourbycraft.config.yaml;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.ConfigFormat;
import com.electronwill.nightconfig.core.InMemoryCommentedFormat;
import com.electronwill.nightconfig.core.io.ConfigParser;
import com.electronwill.nightconfig.core.io.ConfigWriter;
import java.util.Map;
import java.util.function.Supplier;

/**
 * YAML for SourbyCraft's own config files, read and written through nightconfig so the existing
 * {@code CommentedFileConfig} callers keep working.
 *
 * <p>nightconfig's bundled YAML module parses YAML but its writer drops every comment, and the
 * seeded documentation is most of what an operator reads. This format is a thin SnakeYAML-backed
 * pair instead: the parser reads comments attached to keys, the writer emits them again, and
 * strings are always double-quoted so YAML 1.1 scalars such as {@code off}/{@code on}/{@code yes}
 * cannot silently become booleans. Stored values stay nightconfig values ({@code String},
 * {@code Long}, {@code Double}, {@code Boolean}, lists, nested configs); nothing about the typed
 * accessors changes.</p>
 */
public final class YamlFormat implements ConfigFormat<CommentedConfig> {

    public static final YamlFormat DEFAULT = new YamlFormat();

    private static final InMemoryCommentedFormat MEMORY = InMemoryCommentedFormat.defaultInstance();

    private YamlFormat() {
    }

    @Override
    public ConfigWriter createWriter() {
        return new YamlWriter();
    }

    @Override
    public ConfigParser<CommentedConfig> createParser() {
        return new YamlParser(this);
    }

    @Override
    public CommentedConfig createConfig(final Supplier<Map<String, Object>> mapCreator) {
        return MEMORY.createConfig(mapCreator);
    }

    @Override
    public CommentedConfig createConcurrentConfig() {
        return MEMORY.createConcurrentConfig();
    }

    @Override
    public boolean supportsComments() {
        return true;
    }
}
