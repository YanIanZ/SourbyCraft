package dev.iyanz.sourbycraft.item;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-world ItemEntity pool — eliminates GC pressure from constant item creation/destruction.
 * Items are pre-allocated and reused via acquire()/release() lifecycle.
 */
public final class ItemEntityPool {

    private static final Logger LOGGER = LoggerFactory.getLogger("SourbyCraft:ItemPool");
    private static final ItemEntityPool INSTANCE = new ItemEntityPool();

    private final Map<ResourceKey<Level>, ConcurrentLinkedDeque<ItemEntity>> freeLists = new ConcurrentHashMap<>();
    private final Map<ResourceKey<Level>, AtomicInteger> activeCounts = new ConcurrentHashMap<>();
    private volatile boolean initialized;

    private ItemEntityPool() {}

    public static ItemEntityPool instance() { return INSTANCE; }

    public void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("ItemEntity pool initialized (size={}, maxGrowth={})",
                SourbyCraftConfig.itemPoolSize, SourbyCraftConfig.itemPoolMaxGrowth);
    }

    public void preallocate(ServerLevel level, int count) {
        if (!SourbyCraftConfig.itemPoolEnabled) return;
        ResourceKey<Level> key = level.dimension();
        ConcurrentLinkedDeque<ItemEntity> free = freeLists.computeIfAbsent(key,
                k -> new ConcurrentLinkedDeque<>());
        for (int i = 0; i < count; i++) {
            ItemEntity entity = new ItemEntity(level, 0, 0, 0, ItemStack.EMPTY);
            entity.setRemoved(Entity.RemovalReason.DISCARDED);
            entity.discard();
            free.offer(entity);
        }
        LOGGER.debug("Pre-allocated {} ItemEntity for {}", count, key);
    }

    public ItemEntity acquire(ServerLevel level, Vec3 pos, ItemStack stack, @Nullable UUID owner, @Nullable Vec3 velocity) {
        if (!SourbyCraftConfig.itemPoolEnabled) {
            return createNew(level, pos, stack, velocity);
        }

        ResourceKey<Level> key = level.dimension();
        ConcurrentLinkedDeque<ItemEntity> free = freeLists.get(key);
        ItemEntity entity;

        if (free != null && !free.isEmpty()) {
            entity = free.poll();
            if (entity == null) {
                entity = createNew(level, pos, stack, velocity);
            } else {
                configureEntity(entity, level, pos, stack, velocity);
            }
        } else {
            entity = createNew(level, pos, stack, velocity);
        }

        AtomicInteger count = activeCounts.computeIfAbsent(key, k -> new AtomicInteger());
        count.incrementAndGet();
        return entity;
    }

    public void release(ItemEntity entity) {
        if (!SourbyCraftConfig.itemPoolEnabled) return;
        if (!(entity.level() instanceof ServerLevel)) return;

        ResourceKey<Level> key = entity.level().dimension();
        ConcurrentLinkedDeque<ItemEntity> free = freeLists.get(key);

        entity.setRemoved(Entity.RemovalReason.DISCARDED);
        entity.setItem(ItemStack.EMPTY);
        entity.setPosRaw(0, -128, 0);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.clearFire();
        entity.setSharedFlag(0, false);
        entity.age = 0;
        entity.tickCount = 0;
        entity.unRide();

        if (free != null) {
            int maxSize = SourbyCraftConfig.itemPoolMaxGrowth;
            if (free.size() < maxSize / 2) {
                free.offer(entity);
            }
        }

        AtomicInteger count = activeCounts.get(key);
        if (count != null) count.decrementAndGet();
    }

    public void shrink(ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        ConcurrentLinkedDeque<ItemEntity> free = freeLists.get(key);
        if (free == null) return;

        int target = (int) (SourbyCraftConfig.itemPoolSize * SourbyCraftConfig.itemPoolShrinkThreshold);
        while (free.size() > target) {
            free.poll();
        }
    }

    public void enforceChunkLimit(ServerLevel level, int chunkX, int chunkZ) {
        if (!SourbyCraftConfig.itemPoolEnabled) return;
        int max = SourbyCraftConfig.itemMaxPerChunk;
        if (max <= 0) return;

        int count = 0;
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof ItemEntity
                    && e.chunkPosition().x == chunkX
                    && e.chunkPosition().z == chunkZ) {
                count++;
            }
        }
        if (count > max) {
            int toRemove = count - max;
            for (Entity e : level.getEntities().getAll()) {
                if (toRemove <= 0) break;
                if (e instanceof ItemEntity ie
                        && ie.chunkPosition().x == chunkX
                        && ie.chunkPosition().z == chunkZ) {
                    release(ie);
                    toRemove--;
                }
            }
        }
    }

    private ItemEntity createNew(ServerLevel level, Vec3 pos, ItemStack stack, @Nullable Vec3 velocity) {
        ItemEntity entity = new ItemEntity(level, pos.x, pos.y, pos.z, stack);
        if (velocity != null) {
            entity.setDeltaMovement(velocity);
        }
        return entity;
    }

    // SourbyCraft v9: ownerUUID anti-snatch removed (patch 0028-0034 series dropped pre-v9).
    private void configureEntity(ItemEntity entity, ServerLevel level, Vec3 pos, ItemStack stack, @Nullable Vec3 velocity) {
        entity.setLevel(level);
        entity.setPos(pos);
        entity.setItem(stack);
        if (velocity != null) {
            entity.setDeltaMovement(velocity);
        }
        entity.age = 0;
        entity.tickCount = 0;
        entity.setRemoved(null);
        entity.unsetRemoved();
        entity.setSharedFlag(0, false);
        level.addFreshEntity(entity);
    }
}
