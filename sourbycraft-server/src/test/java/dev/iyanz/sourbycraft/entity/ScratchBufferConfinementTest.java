package dev.iyanz.sourbycraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;

/**
 * Confinement rules for the reused scratch buffers (PRD section 108).
 *
 * <p>A scratch buffer replaces a per-call allocation with a field that is cleared and refilled.
 * That is only correct while the field is reached by exactly one region thread and its contents
 * never outlive the call. Two rules follow, and both were broken at least once:
 *
 * <ul>
 *   <li>A scratch may not live on {@link ServerLevel}. One level is subdivided into many regions
 *       by {@code ServerLevel.regioniser}, and every region thread calls {@code level.tick} on the
 *       same instance, so a level-held buffer is shared mutable state across threads.
 *   <li>A scratch may not be handed to something that stores the reference.
 *       {@code SynchedEntityData.DataItem#setValue} keeps what it is given and {@code set} only
 *       marks the entry dirty when the candidate is {@code notEqual} to the stored value, so
 *       publishing the scratch makes every later comparison a self-comparison that never fires.
 * </ul>
 */
public class ScratchBufferConfinementTest {

    private static final String SYNCHED_DATA = "net/minecraft/network/syncher/SynchedEntityData";

    @Test
    void serverLevelHoldsNoScratchBuffer() {
        // A level spans many concurrently ticking regions; per-region state belongs in
        // RegionizedWorldData, reached through ServerLevel#getCurrentWorldData.
        assertNoScratchFields(ServerLevel.class);
    }

    @Test
    void entityScratchBuffersAreInstanceFieldsNotStatics() throws Exception {
        // An entity is owned by one region at a time, so a per-entity buffer is confined. A static
        // one would be shared by every region thread on the server.
        for (final String owner : List.of("collisionScratchVoxels", "collisionScratchAabbs",
                                          "collisionScratchEntityAabbs")) {
            assertConfinedInstanceField(Entity.class, owner);
        }
        assertConfinedInstanceField(LivingEntity.class, "effectParticlesScratch");
        assertConfinedInstanceField(Mob.class, "itemEntitiesScratch");
    }

    @Test
    void effectParticlesAreNotPublishedFromTheScratchBuffer() throws IOException {
        // The guard is a copy before the store. Losing it silently stops effect-particle updates
        // reaching clients, because the dirty flag never fires again.
        final MethodModel method = methodOf(LivingEntity.class, "updateSynchronizedMobEffectParticles");
        assertTrue(invokes(method, "java/util/List", "copyOf"),
            "must publish a copy, never the scratch buffer itself");
        assertTrue(invokes(method, SYNCHED_DATA, "get"),
            "must compare against the stored value so the dirty flag still fires on a real change");
    }

    private static void assertNoScratchFields(final Class<?> type) {
        final List<String> offenders = new ArrayList<>();
        for (final Field field : type.getDeclaredFields()) {
            if (field.getName().toLowerCase(java.util.Locale.ROOT).contains("scratch")) {
                offenders.add(field.getName());
            }
        }
        assertEquals(List.of(), offenders,
            type.getSimpleName() + " is shared by every region thread ticking that level; "
                + "put per-region state in RegionizedWorldData instead");
    }

    private static void assertConfinedInstanceField(final Class<?> type, final String name) throws Exception {
        final Field field = type.getDeclaredField(name);
        final int modifiers = field.getModifiers();
        assertFalse(Modifier.isStatic(modifiers),
            name + " must not be static; a static scratch is shared by every region thread");
        assertTrue(Modifier.isFinal(modifiers), name + " should be final");
        assertTrue(Modifier.isPrivate(modifiers),
            name + " must stay private so nothing outside the owning entity can retain it");
    }

    private static MethodModel methodOf(final Class<?> type, final String name) throws IOException {
        final String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertNotNull(input, "missing class file for " + type.getName());
            final ClassModel model = ClassFile.of().parse(input.readAllBytes());
            for (final MethodModel method : model.methods()) {
                if (method.methodName().stringValue().equals(name)) {
                    return method;
                }
            }
        }
        throw new AssertionError("no method " + name + " on " + type.getName());
    }

    private static boolean invokes(final MethodModel method, final String owner, final String name) {
        for (final CodeElement element : method.code().orElseThrow()) {
            if (element instanceof InvokeInstruction invoke
                && invoke.owner().asInternalName().equals(owner)
                && invoke.name().stringValue().equals(name)) {
                return true;
            }
        }
        return false;
    }

}
