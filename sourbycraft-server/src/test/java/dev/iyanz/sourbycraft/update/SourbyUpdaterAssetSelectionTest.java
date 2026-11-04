package dev.iyanz.sourbycraft.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** Which release asset is the server jar: chosen by name, never a plugin API file, whatever the listing order. */
class SourbyUpdaterAssetSelectionTest {

    private static JsonArray assets(final String... names) {
        final JsonArray array = new JsonArray();
        for (final String name : names) {
            final JsonObject asset = new JsonObject();
            asset.addProperty("name", name);
            asset.addProperty("browser_download_url", "https://example.invalid/" + name);
            array.add(asset);
        }
        return array;
    }

    private static String chosen(final String... names) {
        final JsonObject jar = SourbyUpdater.selectServerJar(assets(names));
        return jar == null ? null : jar.get("name").getAsString();
    }

    @Test
    void aSingleJarIsChosenAsBefore() {
        assertEquals("SourbyCraft-26.2-REL.jar", chosen("SourbyCraft-26.2-REL.jar", "SourbyCraft-26.2-REL.jar.sha256"));
        assertEquals("SourbyCraft-slim.jar", chosen("SourbyCraft-slim.jar"));
        assertEquals("other-name.jar", chosen("other-name.jar", "notes.txt"), "an older release under another jar name");
    }

    @Test
    void aPluginApiAssetIsNeverTheServerJarWhateverTheOrder() {
        assertEquals("SourbyCraft-26.2-REL.jar", chosen("sourbyapi-55.jar", "SourbyCraft-26.2-REL.jar", "sourbyapi-55-sources.jar"));
        assertEquals("SourbyCraft-26.2-REL.jar", chosen("SourbyCraft-26.2-REL.jar", "sourbyapi-55.jar"));
        assertEquals("SourbyCraft-26.2-REL.jar", chosen("SOURBYAPI-55-javadoc.JAR", "SourbyCraft-26.2-REL.jar"));
        assertNull(chosen("sourbyapi-55.jar", "sourbyapi-55-sources.jar", "sourbyapi-55.zip"));
    }

    @Test
    void theServerNamePrefixWinsOverAnEarlierUnrelatedJar() {
        assertEquals("SourbyCraft-26.2-REL.jar", chosen("extra.jar", "SourbyCraft-26.2-REL.jar"));
    }

    @Test
    void theZipThatCarriesTheApiIsNotAJar() {
        assertEquals("SourbyCraft-26.2-REL.jar", chosen("sourbyapi-55.zip", "SourbyCraft-26.2-REL.jar"));
    }

    @Test
    void anAssetWithoutADownloadUrlIsSkipped() {
        final JsonArray array = assets("SourbyCraft-26.2-REL.jar", "SourbyCraft-26.2-DEV.jar");
        array.get(0).getAsJsonObject().remove("browser_download_url");
        assertEquals("SourbyCraft-26.2-DEV.jar", SourbyUpdater.selectServerJar(array).get("name").getAsString());
    }
}
