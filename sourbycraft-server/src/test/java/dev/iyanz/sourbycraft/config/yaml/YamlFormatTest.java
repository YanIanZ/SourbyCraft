package dev.iyanz.sourbycraft.config.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.ParsingException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The YAML format used for SourbyCraft's own config files: values, comments and quoting. */
public class YamlFormatTest {

    private static CommentedConfig parse(final String text) {
        return YamlFormat.DEFAULT.createParser().parse(new StringReader(text));
    }

    private static String write(final Config config) {
        final StringWriter out = new StringWriter();
        YamlFormat.DEFAULT.createWriter().write(config, out);
        return out.toString();
    }

    @Test
    void readsTablesListsAndComments() {
        final CommentedConfig config = parse("""
            # Aurora services (test)
            aurora:
              # Bridge mode: off or safe.
              bridge:
                mode: "safe"
                quarantine-after: 7
                limits: [1, 2, 3]
            """);
        final CommentedConfig bridge = config.get("aurora.bridge");
        assertEquals("safe", bridge.get("mode"));
        assertEquals(Integer.valueOf(7), bridge.get("quarantine-after"));
        assertEquals(List.of(1, 2, 3), bridge.get("limits"));
        assertEquals("Aurora services (test)", config.getComment("aurora"));
        assertEquals("Bridge mode: off or safe.", ((CommentedConfig) config.get("aurora")).getComment("bridge"));
    }

    @Test
    void yaml11WordBooleansStayStrings() {
        final CommentedConfig config = parse("mode: off\nsecond: yes\nthird: no\nfourth: on\n");
        assertEquals("off", config.get("mode"));
        assertEquals("yes", config.get("second"));
        assertEquals("no", config.get("third"));
        assertEquals("on", config.get("fourth"));
    }

    @Test
    void realBooleansAndNumbersKeepTheirTypes() {
        final CommentedConfig config = parse("enabled: true\nlimit: 12\nratio: 0.5\nnothing: null\n");
        assertEquals(Boolean.TRUE, config.get("enabled"));
        assertEquals(Integer.valueOf(12), config.get("limit"));
        assertEquals(Double.valueOf(0.5), config.get("ratio"));
        assertNull(config.get("nothing"));
    }

    @Test
    void roundTripKeepsCommentsAndQuotesStrings() {
        final CommentedConfig config = YamlFormat.DEFAULT.createConfig();
        final CommentedConfig bridge = config.createSubConfig();
        bridge.set("mode", "off");
        bridge.set("note", "a \"quoted\" value\nwith a newline");
        bridge.setComment("mode", "Mode: off or safe.");
        config.set("bridge", bridge);
        config.setComment("bridge", "Aurora Bridge.\n\nSecond paragraph.");
        final String text = write(config);
        assertTrue(text.contains("# Aurora Bridge."), text);
        assertTrue(text.contains("# Second paragraph."), text);
        assertTrue(text.contains("# Mode: off or safe."), text);
        assertTrue(text.contains("mode: \"off\""), text);
        assertTrue(text.contains("note: \"a \\\"quoted\\\" value\\nwith a newline\""), text);

        final CommentedConfig again = parse(text);
        assertEquals("off", again.get("bridge.mode"));
        assertEquals("a \"quoted\" value\nwith a newline", again.get("bridge.note"));
        assertEquals("Aurora Bridge.\n\nSecond paragraph.", again.getComment("bridge"));
        assertEquals("Mode: off or safe.", ((CommentedConfig) again.get("bridge")).getComment("mode"));
    }

    @Test
    void emptyTablesAndListsRoundTrip() {
        final CommentedConfig config = YamlFormat.DEFAULT.createConfig();
        config.set("empty", config.createSubConfig());
        config.set("none", List.of());
        final String text = write(config);
        final CommentedConfig again = parse(text);
        assertInstanceOf(Config.class, again.get("empty"));
        assertTrue(((Config) again.get("empty")).isEmpty());
        assertEquals(List.of(), again.get("none"));
    }

    @Test
    void aMalformedFileReportsItsPosition() {
        final ParsingException failure = assertThrows(ParsingException.class, () -> parse("a: [1, 2\n"));
        assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank(), "message");
    }

    @Test
    void aNonMappingTopLevelIsRefused() {
        final ParsingException failure = assertThrows(ParsingException.class, () -> parse("- just\n- a list\n"));
        assertTrue(failure.getMessage().contains("mapping"), failure.getMessage());
    }

    @Test
    void duplicateKeysAreRefused() {
        final ParsingException failure = assertThrows(ParsingException.class, () -> parse("a: 1\na: 2\n"));
        assertTrue(failure.getMessage().contains("Duplicate"), failure.getMessage());
    }
}
