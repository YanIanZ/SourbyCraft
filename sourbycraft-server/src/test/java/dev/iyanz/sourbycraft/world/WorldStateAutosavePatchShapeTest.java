package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.entity.teleport.OwnedTeleportPatchShapeTestAccess;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class WorldStateAutosavePatchShapeTest {
    @Test
    void worldWhitelistCallsHelperWithoutAddingUnsafeTypes() throws IOException {
        String patch = patch();
        String world = OwnedTeleportPatchShapeTestAccess.file(patch,
            "io/papermc/paper/threadedregions/RegionizedServer.java");
        assertTrue(world.contains("+                dev.iyanz.sourbycraft.world.WorldStateAutosave.save(level.getDataStorage(), sync);"));
        assertTrue(world.contains("note: the dragon fight is autosaved"));
        assertEquals(1, world.lines().filter(l -> l.startsWith("+") && l.contains("WorldStateAutosave.save(")).count());
        assertFalse(patch.contains("Raids.TYPE"));
        assertFalse(patch.contains("PaperWorldPDC.TYPE"));
    }

    @Test
    void managerDetachesSerializedTicketFieldsBeforeUnlocking() throws IOException {
        String manager = OwnedTeleportPatchShapeTestAccess.file(patch(),
            "ca/spottedleaf/moonrise/patches/chunk_system/scheduling/ChunkHolderManager.java");
        int copy = manager.indexOf("copy.add(new Ticket(ticket.getType(), ticket.getTicketLevel(), ticket.moonrise$getRemoveDelay()))");
        assertTrue(copy >= 0);
        assertTrue(manager.indexOf("this.ticketLockArea.unlock(ticketLock)", copy) > copy);
    }

    private static String patch() throws IOException {
        return OwnedTeleportPatchShapeTestAccess.patch("minecraft-patches/features",
            "0067-SourbyCraft-periodically-save-detached-persistent-tickets");
    }
}
