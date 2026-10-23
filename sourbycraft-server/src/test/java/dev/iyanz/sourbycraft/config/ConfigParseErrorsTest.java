package dev.iyanz.sourbycraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The operator-facing text of a config parse failure: file, near-line and the parser's own message. */
class ConfigParseErrorsTest {

    @TempDir Path dir;

    @Test
    void aRealTomlFailureNamesTheLineWithTheOffendingValue() throws Exception {
        final Path file = Files.writeString(this.dir.resolve("aurora.toml"),
            "[aurora.bridge]\nmode = \"safe\"\n\n[aurora.awf]\nworlds = sourby\n");
        final ParsingException failure = assertThrows(ParsingException.class,
            () -> new TomlParser().parse(Files.newBufferedReader(file)));
        final String described = ConfigParseErrors.describe(file, failure);
        assertTrue(described.startsWith(file + " (near line 5: `worlds = sourby`): "), described);
        assertTrue(described.endsWith(failure.getMessage()), described);
    }

    @Test
    void aMessageWithoutAValueTokenStillNamesTheFile() {
        final String described = ConfigParseErrors.describe(this.dir.resolve("aurora.toml"),
            new ParsingException("Invalid separator '\\n' in table name."));
        assertEquals(this.dir.resolve("aurora.toml") + " (Invalid separator '\\n' in table name.)", described);
    }

    @Test
    void aTokenThatDoesNotAppearInTheFileIsNotGuessed() {
        final String described = ConfigParseErrors.describe(this.dir.resolve("aurora.toml"),
            new ParsingException("Invalid integer value: absent"));
        assertEquals(this.dir.resolve("aurora.toml") + " (Invalid integer value: absent)", described);
    }

    @Test
    void onlyAWholeTokenCounts() throws Exception {
        final Path file = Files.writeString(this.dir.resolve("aurora.toml"), "worlds = sourby_old\n");
        final String described = ConfigParseErrors.describe(file,
            new ParsingException("Invalid integer value: sourby"));
        assertEquals(file + " (Invalid integer value: sourby)", described);
    }

    @Test
    void theSnippetIsBounded() throws Exception {
        final String longLine = "worlds = " + "a".repeat(300) + " sourby";
        final Path file = Files.writeString(this.dir.resolve("aurora.toml"), longLine + "\n");
        final String described = ConfigParseErrors.describe(file,
            new ParsingException("Invalid integer value: sourby"));
        assertTrue(described.contains("near line 1"), described);
        assertTrue(described.length() < longLine.length() + 120, described);
    }

    @Test
    void tokenExtractionFollowsTheNightconfigShapes() {
        assertEquals("sourby", ConfigParseErrors.offendingToken("Invalid integer value: sourby"));
        assertEquals("sourby", ConfigParseErrors.offendingToken("Invalid integer: sourby"));
        assertNull(ConfigParseErrors.offendingToken("Invalid separator '\\n' in table name."));
        assertNull(ConfigParseErrors.offendingToken("Invalid value: " + "x".repeat(80)));
    }
}
