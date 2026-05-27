package dev.iyanz.sourbycraft.tick;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class EntityTickRouterTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void itemEntityRoutedToTickerWhenFlagsOn() {
        BatchPhysicsTicker ticker = Mockito.mock(BatchPhysicsTicker.class);
        Mockito.when(ticker.submit(Mockito.any())).thenReturn(true);

        AtomicBoolean inlineRan = new AtomicBoolean();
        ItemEntity item = Mockito.mock(ItemEntity.class);
        boolean routed = EntityTickRouter.route(item, ticker,
            true, true, true, () -> inlineRan.set(true));

        assertTrue(routed);
        assertFalse(inlineRan.get());
        Mockito.verify(ticker).submit(Mockito.argThat(s -> s.entityClass() == EntityClass.ITEM));
    }

    @Test
    void livingEntityAlwaysRunsInline() {
        BatchPhysicsTicker ticker = Mockito.mock(BatchPhysicsTicker.class);
        AtomicBoolean inlineRan = new AtomicBoolean();
        LivingEntity living = Mockito.mock(LivingEntity.class);
        boolean routed = EntityTickRouter.route(living, ticker,
            true, true, true, () -> inlineRan.set(true));
        assertFalse(routed);
        assertTrue(inlineRan.get());
        Mockito.verify(ticker, Mockito.never()).submit(Mockito.any());
    }

    @Test
    void itemEntityFallsBackToInlineWhenSubmitFails() {
        BatchPhysicsTicker ticker = Mockito.mock(BatchPhysicsTicker.class);
        Mockito.when(ticker.submit(Mockito.any())).thenReturn(false);
        AtomicBoolean inlineRan = new AtomicBoolean();
        ItemEntity item = Mockito.mock(ItemEntity.class);
        boolean routed = EntityTickRouter.route(item, ticker,
            true, true, true, () -> inlineRan.set(true));
        assertFalse(routed);
        assertTrue(inlineRan.get());
    }

    @Test
    void itemEntityRunsInlineWhenPerFeatureFlagOff() {
        BatchPhysicsTicker ticker = Mockito.mock(BatchPhysicsTicker.class);
        AtomicBoolean inlineRan = new AtomicBoolean();
        ItemEntity item = Mockito.mock(ItemEntity.class);
        boolean routed = EntityTickRouter.route(item, ticker,
            false, true, true, () -> inlineRan.set(true));
        assertFalse(routed);
        assertTrue(inlineRan.get());
        Mockito.verify(ticker, Mockito.never()).submit(Mockito.any());
    }
}
