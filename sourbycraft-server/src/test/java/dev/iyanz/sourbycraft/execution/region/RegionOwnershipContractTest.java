package dev.iyanz.sourbycraft.execution.region;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.guards.EndPlatformEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RegionOwnershipContractTest {

    @Test
    void replacementIsVisibleAndRestorableWithoutAServer() {
        final RegionOwnership original = RegionOwnership.current();
        final FakeOwnership fake = new FakeOwnership();
        assertSame(original, RegionOwnership.replace(fake));
        try {
            assertSame(fake, RegionOwnership.current());
            assertFalse(RegionOwnership.current().isTickThreadFor((Entity) null));
            // No entity: the actual event glue must not dereference the absent level.
            assertFalse(EndPlatformEvents.shouldFire(null, null, null));
            assertFalse(EndPlatformEvents.shouldFire(true,
                () -> RegionOwnership.current().isTickThreadFor((Level) null, (BlockPos) null)));
            fake.owned = true;
            assertTrue(EndPlatformEvents.shouldFire(true,
                () -> RegionOwnership.current().isTickThreadFor((Level) null, (BlockPos) null)));
            assertThrows(NullPointerException.class, () -> RegionOwnership.replace(null));
            assertSame(fake, RegionOwnership.current());
        } finally {
            RegionOwnership.replace(original);
        }
        assertSame(original, RegionOwnership.current());
    }

    @Test
    void scheduledFunctionsReachTheGlobalTickOnlyThroughTheContract() throws Exception {
        final RegionOwnership original = RegionOwnership.current();
        final FakeOwnership fake = new FakeOwnership();
        RegionOwnership.replace(fake);
        try {
            final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> fake.ensureGlobalTickThread("Scheduled functions must tick on the global thread"));
            assertTrue(refused.getMessage().contains("global thread"));
            // Completions are handed to the global tick, never computed on the calling thread.
            final var future = dev.iyanz.sourbycraft.world.ScheduledFunctionTicker.suggest(null,
                new com.mojang.brigadier.suggestion.SuggestionsBuilder("", 0));
            assertFalse(future.isDone());
            org.junit.jupiter.api.Assertions.assertEquals(1, fake.queued.size());
            // Run by the "global tick": the null level fails inside the task and fails the future.
            fake.queued.get(0).run();
            assertTrue(future.isCompletedExceptionally());
            // /schedule mutations: accepted at once, run only when the global tick runs them.
            final int[] ran = {0};
            org.junit.jupiter.api.Assertions.assertEquals(1,
                dev.iyanz.sourbycraft.world.ScheduledFunctionTicker.onGlobal(() -> ++ran[0], null));
            org.junit.jupiter.api.Assertions.assertEquals(0, ran[0]);
            org.junit.jupiter.api.Assertions.assertEquals(2, fake.queued.size());
            fake.queued.get(1).run();
            org.junit.jupiter.api.Assertions.assertEquals(1, ran[0]);
        } finally {
            RegionOwnership.replace(original);
        }
    }

    private static final class FakeOwnership implements RegionOwnership {
        boolean owned;

        @Override public boolean isTickThreadFor(Entity entity) { return owned; }
        @Override public boolean isTickThreadFor(Level level, BlockPos pos) { return owned; }
        @Override public boolean isTickThreadFor(Level level, Vec3 pos) { return owned; }
        @Override public boolean isTickThreadFor(Level level, int chunkX, int chunkZ) { return owned; }
        @Override public boolean isTickThreadFor(Level level, int minX, int minZ, int maxX, int maxZ) { return owned; }
        @Override public boolean isTickThreadFor(Level level, AABB box) { return owned; }
        @Override public void ensureTickThread(Entity entity, String reason) {
            if (!owned) throw new IllegalStateException(reason);
        }
        @Override public void ensureGlobalTickThread(String reason) {
            if (!owned) throw new IllegalStateException(reason);
        }
        @Override public boolean isGlobalTickThread() { return owned; }
        @Override public void executeOnGlobalTick(Runnable task) {
            queued.add(task);
        }
        final java.util.List<Runnable> queued = new java.util.ArrayList<>();
    }
}
