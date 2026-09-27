package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PerfAwfViewTest {

    @Test
    void awfIsAView() {
        assertTrue(PerfCommand.VIEWS.contains("awf"));
        assertTrue(PerfCommand.VIEWS.contains("storage"));
    }

    @Test
    void storageNamesKeepWorldDimensionAndFolder() {
        assertEquals("world/DIM-1/region", PerfCommand.shortStorageName("/srv/mc/world/DIM-1/region"));
        assertEquals("world/entities", PerfCommand.shortStorageName("world/entities"));
        assertEquals("srv/world/poi", PerfCommand.shortStorageName("C:\\srv\\world\\poi"));
    }
}
