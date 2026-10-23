package dev.iyanz.sourbycraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The documented examples in {@code docs/config-examples/} are golden copies of the Build 49
 * layouts: every key of the tables, nothing else, each at its default. A key added to the code
 * without the example fails here; so does an example that drifts from a default.
 */
class ConfigExamplesTest {

    private static Path examples() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("docs/config-examples"))) root = root.getParent();
        assertNotNull(root, "docs/config-examples is checked in");
        return root.resolve("docs/config-examples");
    }

    private static Map<String, Object> parse(final Path file) throws Exception {
        return ConfigSnapshot.copyOf(ConfigSources.read(file)).values();
    }

    @Test
    void theAuroraExampleIsEveryAuroraKeyAtItsDefault() throws Exception {
        final Map<String, Object> example = parse(examples().resolve("aurora.yml"));
        final Map<String, Object> expected = new LinkedHashMap<>();
        for (final ConfigKey key : AuroraKeys.KEYS) expected.put(key.path(), key.defaultValue());
        assertEquals(expected, example);
        final String text = Files.readString(examples().resolve("aurora.yml"));
        for (final UtilityKeys.Section section : AuroraKeys.SECTIONS) {
            if (!section.path().equals("aurora")) assertSectionDocumented(example, text, section.path());
        }
    }

    @Test
    void theUtilityExampleIsEveryUtilityKeyAtItsDefault() throws Exception {
        final Map<String, Object> example = parse(examples().resolve("sourbycraft_global_config.yml"));
        final Map<String, Object> expected = new LinkedHashMap<>();
        for (final ConfigKey key : UtilityKeys.KEYS) expected.put(key.path(), key.defaultValue());
        // the [messages] lists are built-in variants seeded at boot; the example documents them as comments
        assertEquals(expected, example);
        final String text = Files.readString(examples().resolve("sourbycraft_global_config.yml"));
        for (final UtilityKeys.Section section : UtilityKeys.SECTIONS) {
            if (section.path().equals("plugins")) continue;
            assertSectionDocumented(example, text, section.path());
        }
        assertSectionDocumented(example, text, "plugins.viaversion");
        assertSectionDocumented(example, text, "plugins.protocollib");
        for (final ConfigKey key : UtilityKeys.KEYS) {
            assertTrue(text.contains(key.lifecycle().name()), key.path() + ": lifecycle named in the example");
        }
    }

    private static void assertSectionDocumented(final Map<String, Object> example, final String text,
                                                final String section) {
        final String leaf = section.substring(section.lastIndexOf('.') + 1);
        assertTrue(example.containsKey(section)
                || example.keySet().stream().anyMatch(key -> key.startsWith(section + "."))
                || text.contains(leaf + ":"),
            section + " is documented in the example");
    }

    @Test
    void theConsoleSnippetMatchesTheConsoleSection() throws Exception {
        final Map<String, Object> snippet = parse(examples().resolve("utility-ui.yml"));
        final Map<String, Object> expected = new LinkedHashMap<>();
        for (final ConfigKey key : UtilityKeys.KEYS) if (key.table().equals("console")) expected.put(key.path(), key.defaultValue());
        assertEquals(expected, snippet);
    }
}
