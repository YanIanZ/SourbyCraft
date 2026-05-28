package dev.iyanz.sourbycraft.tick;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import net.minecraft.world.entity.Entity;
import static org.junit.jupiter.api.Assertions.*;

class ParallelTickTypesTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void snapshotCarriesEntityAndClass() {
        Entity e = Mockito.mock(Entity.class);
        ParallelTickSnapshot snap = new ParallelTickSnapshot(e, EntityClass.ITEM);
        assertSame(e, snap.entity());
        assertEquals(EntityClass.ITEM, snap.entityClass());
    }

    @Test
    void diffCarriesEntityAndInteractFlag() {
        Entity e = Mockito.mock(Entity.class);
        ParallelTickDiff diff = ParallelTickDiff.needsInteract(e);
        assertSame(e, diff.entity());
        assertTrue(diff.needsInteract());

        ParallelTickDiff done = ParallelTickDiff.done(e);
        assertSame(e, done.entity());
        assertFalse(done.needsInteract());
    }
}
