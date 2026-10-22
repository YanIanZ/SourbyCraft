package dev.iyanz.sourbycraft.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** The report metadata is present, grouped under one key, and carries no configuration values. */
class SourbyMetadataProviderTest {

    @Test
    void everySectionIsPresentOutsideARunningServer() {
        final JsonObject root = new SourbyMetadataProvider().get().get("sourbycraft").getAsJsonObject();
        for (final String section : new String[] {"build", "runtime", "bridge", "governor", "switches", "startup"}) {
            assertTrue(root.has(section), section);
        }
        assertEquals("Aurora", root.getAsJsonObject("build").get("engine").getAsString());
        assertEquals("not-ready", root.getAsJsonObject("startup").get("state").getAsString());
        assertEquals("not-started", root.getAsJsonObject("bridge").get("mode").getAsString());
    }

    @Test
    void noConfigurationStringsLeakIn() {
        final String json = SourbyMetadataProvider.build().toString().toLowerCase(java.util.Locale.ROOT);
        for (final String forbidden : new String[] {"password", "secret", "token", "webhook", "motd", "seed"}) {
            assertFalse(json.contains(forbidden), forbidden);
        }
    }
}
