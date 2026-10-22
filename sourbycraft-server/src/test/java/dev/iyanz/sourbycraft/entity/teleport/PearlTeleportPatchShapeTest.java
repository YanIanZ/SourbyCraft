package dev.iyanz.sourbycraft.entity.teleport;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class PearlTeleportPatchShapeTest {
    @Test
    void impactDelegatesOnThePearlThreadAndDoesNotRestoreTheSynchronousBody() throws IOException {
        final String patch = OwnedTeleportPatchShapeTest.patch("minecraft-patches/features", "-SourbyCraft-ender-pearl-teleport-event");
        assertTrue(patch.contains("+                    dev.iyanz.sourbycraft.entity.teleport.PearlTeleport.hit(this, entity, level, this.position());"));
        assertTrue(patch.contains("-    private void attemptTeleport("));
        assertFalse(patch.lines().anyMatch(line -> line.startsWith("+") && line.contains("player.teleport(")));
    }
}
