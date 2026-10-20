package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PerfAwfViewTest {

    @Test
    void awfIsAView() {
        assertTrue(PerfCommand.VIEWS.contains("awf"));
        assertTrue(PerfCommand.VIEWS.contains("storage"));
        for (final String view : new String[] {"plugins", "history", "player", "chunks", "entities"}) {
            assertTrue(PerfCommand.VIEWS.contains(view), view);
        }
    }

    @Test
    void degradedDirectoryForcesAreShownOnlyWhileNonZero() {
        final java.util.List<net.kyori.adventure.text.Component> normal = new java.util.ArrayList<>();
        PerfCommand.renderAwf(normal, 0L, null);
        final String normalText = CommandTestSupport.plain(normal);
        assertTrue(!normalText.contains("fsyncs skipped"), normalText);
        assertTrue(normalText.contains("Aurora World Fabric"), normalText);

        final java.util.List<net.kyori.adventure.text.Component> degraded = new java.util.ArrayList<>();
        PerfCommand.renderAwf(degraded, 7L, "UnsupportedOperationException: directories cannot be opened");
        final String text = CommandTestSupport.plain(degraded);
        assertTrue(text.contains("AWF directory fsyncs skipped: 7 since start (UnsupportedOperationException: directories cannot be opened)"), text);
        assertTrue(text.contains("not fully durable"), text);
        assertTrue(text.indexOf("fsyncs skipped") < text.indexOf("Aurora World Fabric"), "the warning leads: " + text);
    }

    @Test
    void storageNamesKeepWorldDimensionAndFolder() {
        assertEquals("world/DIM-1/region", PerfCommand.shortStorageName("/srv/mc/world/DIM-1/region"));
        assertEquals("world/entities", PerfCommand.shortStorageName("world/entities"));
        assertEquals("srv/world/poi", PerfCommand.shortStorageName("C:\\srv\\world\\poi"));
    }
}
