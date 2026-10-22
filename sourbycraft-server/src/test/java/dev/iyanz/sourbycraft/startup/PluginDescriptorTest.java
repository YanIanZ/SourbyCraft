package dev.iyanz.sourbycraft.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

/** The flags are evaluated exactly as the region-threading base evaluates them. */
class PluginDescriptorTest {

    private static PluginDescriptor legacy(final String yaml) throws IOException {
        return PluginDescriptor.parse(new StringReader(yaml), false);
    }

    private static PluginDescriptor paper(final String yaml) throws IOException {
        return PluginDescriptor.parse(new StringReader(yaml), true);
    }

    @Test
    void legacyFoliaFlagMustBeTrue() throws IOException {
        assertTrue(legacy("name: A\nmain: a.A\nfolia-supported: true\n").regionSupported());
        assertTrue(legacy("name: A\nfolia-supported: 'TRUE'\n").regionSupported());
        assertFalse(legacy("name: A\nfolia-supported: yes-ish\n").regionSupported());
        assertFalse(legacy("name: A\n").regionSupported());
    }

    @Test
    void legacyCanvasFlagIsConsultedOnlyWhenTheFoliaFlagIsAbsentOrFalse() throws IOException {
        assertTrue(legacy("name: A\ncanvas-supported: true\n").regionSupported());
        assertTrue(legacy("name: A\nfolia-supported: false\ncanvas-supported: true\n").regionSupported());
        // A non-false, non-true Folia value shadows the Canvas flag in PluginDescriptionFile.
        assertFalse(legacy("name: A\nfolia-supported: maybe\ncanvas-supported: true\n").regionSupported());
    }

    @Test
    void paperDescriptorsAcceptEitherFlag() throws IOException {
        assertTrue(paper("name: P\nfolia-supported: true\n").regionSupported());
        assertTrue(paper("name: P\ncanvas-supported: true\n").regionSupported());
        assertFalse(paper("name: P\n").regionSupported());
    }

    @Test
    void aDescriptorWithoutANameIsNotAPlugin() throws IOException {
        assertNull(legacy("main: a.A\n"));
        assertNull(legacy("- just\n- a list\n"));
    }

    @Test
    void malformedYamlIsAnIoFailureNotACrash() {
        assertThrows(IOException.class, () -> legacy("name: [unterminated\n"));
    }

    @Test
    void legacyDependenciesAreRead() throws IOException {
        final PluginDescriptor d = legacy("name: A\ndepend: [Vault]\nsoftdepend: [PlaceholderAPI, x]\nloadbefore: [Z]\n");
        assertEquals(java.util.List.of("Vault"), d.depend());
        assertEquals(java.util.List.of("PlaceholderAPI", "x"), d.softDepend());
        assertEquals(java.util.List.of("Z"), d.loadBefore());
    }

    @Test
    void paperDependenciesFollowPaperDefaults() throws IOException {
        final PluginDescriptor d = paper("name: P\ndependencies:\n  server:\n"
            + "    Hard: {load: BEFORE}\n    Soft: {load: BEFORE, required: false}\n    Later: {load: AFTER, required: false}\n");
        assertEquals(java.util.List.of("Hard"), d.depend());
        assertEquals(java.util.List.of("Soft"), d.softDepend());
        assertEquals(java.util.List.of("Later"), d.loadBefore());
    }

    @Test
    void thePayloadRoundTrips() throws IOException {
        final PluginDescriptor d = legacy("name: A\nversion: 1.2\nmain: a.A\nfolia-supported: true\ndepend: [B]\n");
        assertEquals(d, PluginDescriptor.decode(d.encode()));
        assertNull(PluginDescriptor.decode("garbage"));
    }
}
