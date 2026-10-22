package dev.iyanz.sourbycraft.entity;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;

/** Regression guards for V9/V11: entity-owned lists must not retain the last query's objects. */
public class ScratchBufferConfinementTest {
    @Test
    void serverLevelHoldsNoScratchBuffer() {
        final List<String> offenders = new ArrayList<>();
        for (final Field field : ServerLevel.class.getDeclaredFields()) {
            if (field.getName().toLowerCase(java.util.Locale.ROOT).contains("scratch")) {
                offenders.add(field.getName());
            }
        }
        assertEquals(List.of(), offenders, "ServerLevel spans concurrently ticking regions");
    }

    @Test
    void collisionQueriesDoNotRetainTheirLastResultsOnTheEntity() {
        for (final String name : List.of("collisionScratchVoxels", "collisionScratchAabbs", "collisionScratchEntityAabbs")) {
            assertThrows(NoSuchFieldException.class, () -> Entity.class.getDeclaredField(name));
        }
    }

    @Test
    void pickupsDoNotRetainRemovedItemsWhenThePickupGateCloses() {
        assertThrows(NoSuchFieldException.class, () -> Mob.class.getDeclaredField("itemEntitiesScratch"));
    }

    @Test
    void effectPublicationCannotAliasAnEntityScratchList() {
        assertThrows(NoSuchFieldException.class, () -> LivingEntity.class.getDeclaredField("effectParticlesScratch"));
    }

    @Test
    void remainingPositionScratchIsAnOwnerConfinedInstanceField() throws Exception {
        final Field field = Entity.class.getDeclaredField("isInWallScratch");
        assertFalse(Modifier.isStatic(field.getModifiers()));
        assertTrue(Modifier.isPrivate(field.getModifiers()));
        assertTrue(Modifier.isFinal(field.getModifiers()));
    }

    @Test
    void publishedCallerOwnedQueryOverloadRemainsBinaryCompatible() throws Exception {
        final var method = net.minecraft.world.level.Level.class.getDeclaredMethod("getEntitiesOfClass",
            Class.class, net.minecraft.world.phys.AABB.class, java.util.function.Predicate.class, List.class);
        assertEquals(void.class, method.getReturnType());
        assertTrue(Modifier.isPublic(method.getModifiers()));
    }
}
