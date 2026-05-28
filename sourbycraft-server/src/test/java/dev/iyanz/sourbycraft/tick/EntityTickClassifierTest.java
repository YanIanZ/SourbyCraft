package dev.iyanz.sourbycraft.tick;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import static org.junit.jupiter.api.Assertions.*;

class EntityTickClassifierTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void classifyItemEntity() {
        ItemEntity e = Mockito.mock(ItemEntity.class);
        assertEquals(EntityClass.ITEM, EntityTickClassifier.classify(e));
    }

    @Test
    void classifyExperienceOrb() {
        ExperienceOrb e = Mockito.mock(ExperienceOrb.class);
        assertEquals(EntityClass.EXP_ORB, EntityTickClassifier.classify(e));
    }

    @Test
    void classifyArrow() {
        AbstractArrow e = Mockito.mock(AbstractArrow.class);
        assertEquals(EntityClass.ARROW, EntityTickClassifier.classify(e));
    }

    @Test
    void classifyLivingEntity() {
        LivingEntity e = Mockito.mock(LivingEntity.class);
        assertEquals(EntityClass.LIVING, EntityTickClassifier.classify(e));
    }

    @Test
    void classifyOther() {
        Entity e = Mockito.mock(Entity.class);
        assertEquals(EntityClass.OTHER, EntityTickClassifier.classify(e));
    }

    @Test
    void classifyNullReturnsOther() {
        assertEquals(EntityClass.OTHER, EntityTickClassifier.classify(null));
    }
}
