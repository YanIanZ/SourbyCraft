# Item Entity Pooling — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Rewrite item drop system with ItemEntity pooling — pre-allocate, reuse, zero GC pressure.

**Architecture:** `ItemEntityPool` singleton manages per-world `ConcurrentLinkedDeque<ItemEntity>` free lists. All `new ItemEntity()` in Minecraft source replaced with `pool.acquire()`. All `discard()` replaced with `pool.release()`. Pool state machine: FREE → ACTIVE → EXPIRED → FREE.

**Tech Stack:** Java 25, ConcurrentLinkedDeque, AtomicInteger, Minecraft NMS (ItemEntity, ServerLevel)

---

## File Structure

```
sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/
├── item/ItemEntityPool.java              (NEW — core pool implementation)
├── SourbyCraftConfig.java                (MODIFIED — 6 new config fields)

patches/minecraft/
└── 0027-item-entity-pooling.patch        (NEW — replaces new ItemEntity + discard)
```

---

### Task 1: ItemEntityPool Core Implementation

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/item/ItemEntityPool.java`

- [ ] **Step 1: Create directory and write ItemEntityPool.java**

```bash
mkdir -p sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/item
```

Write at `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/item/ItemEntityPool.java`:

```java
package dev.iyanz.sourbycraft.item;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
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

    /** Initialize pool. Idempotent. Called from SourbyCraftConfig.init(). */
    public void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("ItemEntity pool initialized (size={}, maxGrowth={})",
                SourbyCraftConfig.itemPoolSize, SourbyCraftConfig.itemPoolMaxGrowth);
    }

    /** Pre-allocate pool for a level. Called from ServerLevel constructor. */
    public void preallocate(ServerLevel level, int count) {
        if (!SourbyCraftConfig.itemPoolEnabled) return;
        ResourceKey<Level> key = level.dimension();
        ConcurrentLinkedDeque<ItemEntity> free = freeLists.computeIfAbsent(key,
                k -> new ConcurrentLinkedDeque<>());
        for (int i = 0; i < count; i++) {
            ItemEntity entity = new ItemEntity(level, 0, 0, 0, ItemStack.EMPTY);
            entity.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            entity.discard();
            free.offer(entity);
        }
        LOGGER.debug("Pre-allocated {} ItemEntity for {}", count, key.location());
    }

    /**
     * Acquire an ItemEntity from the pool or create new.
     * Sets position, item stack, owner, and pickup delay.
     */
    public ItemEntity acquire(ServerLevel level, Vec3 pos, ItemStack stack, @Nullable UUID owner) {
        if (!SourbyCraftConfig.itemPoolEnabled) {
            return createNew(level, pos, stack, owner);
        }

        ResourceKey<Level> key = level.dimension();
        ConcurrentLinkedDeque<ItemEntity> free = freeLists.get(key);
        ItemEntity entity;

        if (free != null && !free.isEmpty()) {
            entity = free.poll();
            if (entity == null) {
                entity = createNew(level, pos, stack, owner);
            } else {
                configureEntity(entity, level, pos, stack, owner);
            }
        } else {
            entity = createNew(level, pos, stack, owner);
        }

        AtomicInteger count = activeCounts.computeIfAbsent(key, k -> new AtomicInteger());
        count.incrementAndGet();
        return entity;
    }

    /** Release entity back to pool. Called from ItemEntity tick on despawn. */
    public void release(ItemEntity entity) {
        if (!SourbyCraftConfig.itemPoolEnabled || entity.level().isClientSide) return;

        ResourceKey<Level> key = entity.level().dimension();
        ConcurrentLinkedDeque<ItemEntity> free = freeLists.get(key);

        // Reset entity state before returning to pool
        entity.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        entity.setItem(ItemStack.EMPTY);
        entity.setPosRaw(0, -128, 0); // far from world
        entity.setDeltaMovement(Vec3.ZERO);
        entity.clearFire();
        entity.setSharedFlag(0, false);
        entity.age = 0;
        entity.tickCount = 0;
        entity.unRide();
        entity.removeTag("SourbycraftOwner");

        if (free != null) {
            int maxSize = SourbyCraftConfig.itemPoolMaxGrowth;
            if (free.size() < maxSize / 2) {
                free.offer(entity);
            }
        }

        AtomicInteger count = activeCounts.get(key);
        if (count != null) count.decrementAndGet();
    }

    /** Shrink idle pool entries above threshold. */
    public void shrink(ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        ConcurrentLinkedDeque<ItemEntity> free = freeLists.get(key);
        if (free == null) return;

        int target = (int) (SourbyCraftConfig.itemPoolSize * SourbyCraftConfig.itemPoolShrinkThreshold);
        while (free.size() > target) {
            free.poll(); // discard, let GC collect
        }
    }

    /** Per-chunk item limit enforcement. Despawns oldest items if exceeded. */
    public void enforceChunkLimit(ServerLevel level, int chunkX, int chunkZ) {
        if (!SourbyCraftConfig.itemPoolEnabled) return;
        int max = SourbyCraftConfig.itemMaxPerChunk;
        if (max <= 0) return;

        var entities = level.getEntities().getAll();
        int count = 0;
        for (var e : entities) {
            if (e instanceof ItemEntity && e.chunkPosition().x == chunkX && e.chunkPosition().z == chunkZ) {
                count++;
            }
        }
        if (count > max) {
            // Despawn oldest items
            entities.stream()
                .filter(e -> e instanceof ItemEntity)
                .filter(e -> e.chunkPosition().x == chunkX && e.chunkPosition().z == chunkZ)
                .sorted((a, b) -> Integer.compare(b.tickCount, a.tickCount))
                .skip(max)
                .forEach(e -> release((ItemEntity) e));
        }
    }

    private ItemEntity createNew(ServerLevel level, Vec3 pos, ItemStack stack, UUID owner) {
        ItemEntity entity = new ItemEntity(level, pos.x, pos.y, pos.z, stack);
        if (owner != null) {
            entity.getBukkitEntity().getHandle().getPersistentDataContainer()
                .set(new org.bukkit.NamespacedKey("sourbycraft", "owner"),
                     org.bukkit.persistence.PersistentDataType.STRING, owner.toString());
        }
        return entity;
    }

    private void configureEntity(ItemEntity entity, ServerLevel level, Vec3 pos, ItemStack stack, UUID owner) {
        entity.setLevel(level);
        entity.setPos(pos);
        entity.setItem(stack);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.age = 0;
        entity.tickCount = 0;
        entity.setRemoved(null);
        entity.unsetRemoved();
        entity.setSharedFlag(0, false);
        if (owner != null) {
            entity.getPersistentData().putUUID("SourbycraftOwner", owner);
        }
        // Anti-snatch delay from config
        if (SourbyCraftConfig.ownerProtectionEnabled && owner != null) {
            entity.pickupDelay = SourbyCraftConfig.ownerProtectionTime * 20;
        }
        level.addFreshEntity(entity);
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :sourbycraft-server:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/item/
git commit -m "feat(v6): ItemEntityPool — GC-free item entity reuse"
```

---

### Task 2: Config Integration

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`

- [ ] **Step 1: Add config fields**

Add after line 77 (after `dropStackCap`):

```java
    public static boolean itemPoolEnabled = true;
    public static int itemPoolSize = 256;
    public static int itemPoolMaxGrowth = 1024;
    public static double itemPoolShrinkThreshold = 0.5;
    public static int itemMaxPerChunk = 50;
    public static int itemMaxPerPlayer = 100;
```

- [ ] **Step 2: Add config reads in init()**

Add after line 177 (`asyncSaveBatch` read):

```java
        itemPoolEnabled = getBoolean("item.pool-enabled", itemPoolEnabled);
        itemPoolSize = getInt("item.pool-size", itemPoolSize);
        itemPoolMaxGrowth = getInt("item.pool-max-growth", itemPoolMaxGrowth);
        itemPoolShrinkThreshold = getDouble("item.pool-shrink-threshold", itemPoolShrinkThreshold);
        itemMaxPerChunk = getInt("item.max-per-chunk", itemMaxPerChunk);
        itemMaxPerPlayer = getInt("item.max-per-player", itemMaxPerPlayer);
```

- [ ] **Step 3: Call pool init at end of init()**

After the config reads, add:

```java
        if (itemPoolEnabled) {
            dev.iyanz.sourbycraft.item.ItemEntityPool.instance().init();
        }
```

- [ ] **Step 4: Compile and commit**

```bash
./gradlew :sourbycraft-server:compileJava
git add sourbycraft-server/
git commit -m "feat(v6): ItemEntityPool config — pool-size, limits, shrink"
```

---

### Task 3: Minecraft Integration Patch

**Files:**
- Create: `patches/minecraft/0027-item-entity-pooling.patch`

- [ ] **Step 1: Create the patch file**

Write at `patches/minecraft/0027-item-entity-pooling.patch`:

```patch
From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001
From: SourbyCraft <dev@iyanz.dev>
Date: Tue, 19 May 2026 00:00:00 +0700
Subject: [PATCH] feat(v6): ItemEntity pooling — acquire/release pattern


diff --git a/net/minecraft/server/level/ServerLevel.java b/net/minecraft/server/level/ServerLevel.java
--- a/net/minecraft/server/level/ServerLevel.java
+++ b/net/minecraft/server/level/ServerLevel.java
@@ -0,0 +0,0 @@
     // pre-allocate ItemEntity pool for this level if enabled
     if (dev.iyanz.sourbycraft.SourbyCraftConfig.itemPoolEnabled) {
         dev.iyanz.sourbycraft.item.ItemEntityPool.instance().preallocate(
             this, dev.iyanz.sourbycraft.SourbyCraftConfig.itemPoolSize);
     }
diff --git a/net/minecraft/server/level/ServerPlayer.java b/net/minecraft/server/level/ServerPlayer.java
--- a/net/minecraft/server/level/ServerPlayer.java
+++ b/net/minecraft/server/level/ServerPlayer.java
@@ -0,0 +0,0 @@
     // player drop (Q key)
-    new ItemEntity(level, x, y, z, stack);
+    dev.iyanz.sourbycraft.item.ItemEntityPool.instance()
+        .acquire(level, new Vec3(x, y, z), stack, this.getUUID());
@@ -0,0 +0,0 @@
     // death drops
-    new ItemEntity(level, x, y, z, stack);
+    dev.iyanz.sourbycraft.item.ItemEntityPool.instance()
+        .acquire(level, new Vec3(x, y, z), stack, this.getUUID());
diff --git a/net/minecraft/server/level/ServerPlayerGameMode.java b/net/minecraft/server/level/ServerPlayerGameMode.java
--- a/net/minecraft/server/level/ServerPlayerGameMode.java
+++ b/net/minecraft/server/level/ServerPlayerGameMode.java
@@ -0,0 +0,0 @@
     // block break drops
-    new ItemEntity(level, x, y, z, stack);
+    dev.iyanz.sourbycraft.item.ItemEntityPool.instance()
+        .acquire(level, new Vec3(x, y, z), stack, this.player.getUUID());
diff --git a/net/minecraft/world/entity/LivingEntity.java b/net/minecraft/world/entity/LivingEntity.java
--- a/net/minecraft/world/entity/LivingEntity.java
+++ b/net/minecraft/world/entity/LivingEntity.java
@@ -0,0 +0,0 @@
     // entity death equipment drops
-    new ItemEntity(level, x, y, z, stack);
+    dev.iyanz.sourbycraft.item.ItemEntityPool.instance()
+        .acquire((ServerLevel) level, new Vec3(x, y, z), stack, null);
diff --git a/net/minecraft/world/inventory/AbstractContainerMenu.java b/net/minecraft/world/inventory/AbstractContainerMenu.java
--- a/net/minecraft/world/inventory/AbstractContainerMenu.java
+++ b/net/minecraft/world/inventory/AbstractContainerMenu.java
@@ -0,0 +0,0 @@
     // container close drops
-    new ItemEntity(level, x, y, z, stack);
+    dev.iyanz.sourbycraft.item.ItemEntityPool.instance()
+        .acquire((ServerLevel) level, new Vec3(x, y, z), stack, null);
diff --git a/net/minecraft/world/entity/projectile/FishingHook.java b/net/minecraft/world/entity/projectile/FishingHook.java
--- a/net/minecraft/world/entity/projectile/FishingHook.java
+++ b/net/minecraft/world/entity/projectile/FishingHook.java
@@ -0,0 +0,0 @@
     // fishing loot drop
-    new ItemEntity(level, x, y, z, stack);
+    dev.iyanz.sourbycraft.item.ItemEntityPool.instance()
+        .acquire((ServerLevel) level, new Vec3(x, y, z), stack, null);
diff --git a/net/minecraft/world/entity/item/ItemEntity.java b/net/minecraft/world/entity/item/ItemEntity.java
--- a/net/minecraft/world/entity/item/ItemEntity.java
+++ b/net/minecraft/world/entity/item/ItemEntity.java
@@ -0,0 +0,0 @@
-    this.discard();
+    dev.iyanz.sourbycraft.item.ItemEntityPool.instance().release(this);
```

- [ ] **Step 2: Re-apply patches and compile**

```bash
./gradlew cleanCache :sourbycraft-server:compileJava
```

Expected: patches apply, BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add patches/minecraft/
git commit -m "feat(v6): integrate ItemEntityPool into Minecraft — 7 acquire + 1 release"
```

---

### Task 4: Build Verification

- [ ] **Step 1: Full clean build**

```bash
./gradlew :sourbycraft-server:jar
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Verify pool class exists in JAR**

```bash
jar tf sourbycraft-server/build/libs/sourbycraft-server-v6-REL.jar | grep ItemEntityPool
```

Expected: `dev/iyanz/sourbycraft/item/ItemEntityPool.class`

- [ ] **Step 3: Final commit if needed**

---

## Implementation Order

1. **Task 1** — ItemEntityPool.java (no dependencies)
2. **Task 2** — Config (depends on Task 1 for field names)
3. **Task 3** — Minecraft patch (depends on Tasks 1+2 for API surface)
4. **Task 4** — Verification
