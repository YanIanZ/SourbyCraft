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
    void collapsedChunkSystemReadErrorsAreShownOnlyWhileNonZero() {
        final java.util.List<net.kyori.adventure.text.Component> none = new java.util.ArrayList<>();
        PerfCommand.renderAwf(none, 0L, null, 0L);
        assertTrue(!CommandTestSupport.plain(none).contains("read errors collapsed"), CommandTestSupport.plain(none));

        final java.util.List<net.kyori.adventure.text.Component> some = new java.util.ArrayList<>();
        PerfCommand.renderAwf(some, 0L, null, 1458L);
        final String text = CommandTestSupport.plain(some);
        assertTrue(text.contains("Chunk-system read errors collapsed: 1458 since start"), text);
        assertTrue(text.indexOf("read errors collapsed") < text.indexOf("Aurora World Fabric"), text);
    }

    @Test
    void theViewShowsTheFiltersCurrentDroppedCount() throws Exception {
        final java.lang.reflect.Constructor<dev.iyanz.sourbycraft.awf.AwfRegionStorage.RepeatedReadFailure> c =
            dev.iyanz.sourbycraft.awf.AwfRegionStorage.RepeatedReadFailure.class.getDeclaredConstructor(String.class);
        c.setAccessible(true);
        final var filter = dev.iyanz.sourbycraft.awf.RepeatedReadFailureLogFilter.instance();
        filter.filter(org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder()
            .setLoggerName("some.chunk.system.Logger").setThrown(c.newInstance("repeat")).build());

        final java.util.List<net.kyori.adventure.text.Component> lines = new java.util.ArrayList<>();
        new PerfAwfView().render(null, lines);
        final String text = CommandTestSupport.plain(lines);
        assertTrue(filter.dropped() > 0);
        assertTrue(text.contains("Chunk-system read errors collapsed: " + filter.dropped() + " since start"), text);
    }

    @Test
    void storageNamesKeepWorldDimensionAndFolder() {
        assertEquals("world/DIM-1/region", PerfCommand.shortStorageName("/srv/mc/world/DIM-1/region"));
        assertEquals("world/entities", PerfCommand.shortStorageName("world/entities"));
        assertEquals("srv/world/poi", PerfCommand.shortStorageName("C:\\srv\\world\\poi"));
    }
}
