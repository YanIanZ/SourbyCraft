# Item Stacking & Drop Security — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Max stack size 64→99, unlimited drop merging, anti-snatch owner-only pickup timer.

**Architecture:** One Minecraft patch file (`patches/minecraft/0032`) modifies Item.java, ItemEntity.java, ServerPlayer.java. SourbyCraftConfig.java gets direct source edits for config fields. Generated files also written directly for immediate compile testing.

**Tech Stack:** Java 21, PaperMC fork (paperweight build system), git-diff patch format

---

### Task 1: Add config fields to SourbyCraftConfig.java

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`

- [ ] **Step 1: Add config field declarations**

At line 61 (after `itemMergeRadius`), add:

```java
public static int itemMaxStackSize = 99;
public static boolean unlimitedDropStack = true;
public static int dropStackCap = Integer.MAX_VALUE;
public static boolean ownerProtectionEnabled = true;
public static int ownerProtectionTime = 10;
```

- [ ] **Step 2: Add config loading lines**

At line 124 (after `itemMergeRadius` loading), add:

```java
itemMaxStackSize = getInt("item.max-stack-size", itemMaxStackSize);
unlimitedDropStack = getBoolean("item.unlimited-drop-stack", unlimitedDropStack);
dropStackCap = getInt("item.drop-stack-cap", dropStackCap);
ownerProtectionEnabled = getBoolean("item.owner-protection-enabled", ownerProtectionEnabled);
ownerProtectionTime = getInt("item.owner-protection-time", ownerProtectionTime);
net.minecraft.world.item.Item.sourbycraftMaxStackSize = itemMaxStackSize;
```

- [ ] **Step 3: Verify**

```bash
./gradlew :sourbycraft-server:compileJava 2>&1 | grep -E "SourbyCraftConfig|error:|BUILD"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java
git commit -m "feat: item stack & drop security config fields"
```

---

### Task 2: Create Minecraft patch for Item.java max stack size

**Files:**
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/world/item/Item.java` (generated)
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`

- [ ] **Step 1: Add dynamic max stack field to Item.java**

After line 110 (`public static final int ABSOLUTE_MAX_STACK_SIZE = 99;`), add:

```java
public static int sourbycraftMaxStackSize = 99; // SourbyCraft - dynamic max stack
```

- [ ] **Step 2: Override getDefaultMaxStackSize()**

At line 155, change:

```java
public int getDefaultMaxStackSize() {
    return this.components.getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
}
```
To:
```java
public int getDefaultMaxStackSize() {
    int max = this.components.getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
    if (max == DEFAULT_MAX_STACK_SIZE && sourbycraftMaxStackSize != DEFAULT_MAX_STACK_SIZE) {
        return sourbycraftMaxStackSize;
    }
    return max;
}
```

- [ ] **Step 3: Apply config to Item field from SourbyCraftConfig**

In `SourbyCraftConfig.java`, in the `init()` method after the `itemMergeRadius` loading (after line 124), add:

```java
net.minecraft.world.item.Item.sourbycraftMaxStackSize = itemMaxStackSize;
```

- [ ] **Step 4: Verify compile**

```bash
./gradlew :sourbycraft-server:compileJava 2>&1 | grep -E "Item.java|error:|BUILD"
```
Expected: BUILD SUCCESSFUL

---

### Task 3: Modify ItemEntity.java — drop stack unlimited

**Files:**
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/item/ItemEntity.java` (generated)

- [ ] **Step 1: Change `isMergable()` — remove maxStackSize constraint**

At lines 283-285, change:

```java
return this.isAlive() && this.pickupDelay != 32767 && this.age != -32768 && this.age < this.despawnRate && item.getCount() < item.getMaxStackSize(); // Paper - Alternative item-despawn-rate
```
To:
```java
return this.isAlive() && this.pickupDelay != 32767 && this.age != -32768 && this.age < this.despawnRate; // Paper - Alternative item-despawn-rate // SourbyCraft - unlimited drop stack
```

- [ ] **Step 2: Change `areMergable()` — skip maxStackSize check when unlimited**

At lines 300-303, change:

```java
public static boolean areMergable(ItemStack destinationStack, ItemStack originStack) {
    return originStack.getCount() + destinationStack.getCount() <= originStack.getMaxStackSize()
        && ItemStack.isSameItemSameComponents(destinationStack, originStack);
}
```
To:
```java
public static boolean areMergable(ItemStack destinationStack, ItemStack originStack) {
    if (!dev.iyanz.sourbycraft.SourbyCraftConfig.unlimitedDropStack) {
        if (originStack.getCount() + destinationStack.getCount() > originStack.getMaxStackSize()) return false;
    } else if (dev.iyanz.sourbycraft.SourbyCraftConfig.dropStackCap != Integer.MAX_VALUE) {
        if (originStack.getCount() + destinationStack.getCount() > dev.iyanz.sourbycraft.SourbyCraftConfig.dropStackCap) return false;
    }
    return ItemStack.isSameItemSameComponents(destinationStack, originStack);
}
```

- [ ] **Step 3: Fix `merge()` — replace hardcoded 64**

At lines 312-313, change:

```java
ItemStack itemStack = merge(destinationStack, originStack, 64);
```
To:
```java
int cap = dev.iyanz.sourbycraft.SourbyCraftConfig.unlimitedDropStack ? dev.iyanz.sourbycraft.SourbyCraftConfig.dropStackCap : originStack.getMaxStackSize();
ItemStack itemStack = merge(destinationStack, originStack, cap);
```

- [ ] **Step 4: Verify compile**

```bash
./gradlew :sourbycraft-server:compileJava 2>&1 | grep -E "ItemEntity|error:|BUILD"
```
Expected: BUILD SUCCESSFUL

---

### Task 4: Modify ItemEntity.java — owner UUID for anti-snatch

**Files:**
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/item/ItemEntity.java` (generated)

- [ ] **Step 1: Add ownerUUID field**

At line 49 (after `public int pickupDelay = 0;`), add:

```java
@Nullable
public java.util.UUID ownerUUID = null; // SourbyCraft - anti-snatch
```

Add the import at the top of the file with other imports. Find the import section and add:

```java
import javax.annotation.Nullable;
```

(If `@Nullable` is already imported, skip this import line.)

- [ ] **Step 2: Save ownerUUID to NBT**

In `addAdditionalSaveData()` (around line 381 where pickupDelay is saved), after the pickupDelay write, add:

```java
if (this.ownerUUID != null) {
    output.putUUID("SourbycraftOwner", this.ownerUUID);
}
```

- [ ] **Step 3: Load ownerUUID from NBT**

In `readAdditionalSaveData()` (around line 398 where pickupDelay is read), after the pickupDelay read, add:

```java
if (input.hasUUID("SourbycraftOwner")) {
    this.ownerUUID = input.getUUID("SourbycraftOwner");
}
```

- [ ] **Step 4: Add owner-only pickup gate in `playerTouch()`**

In `playerTouch(Player entity)` at line 427, right BEFORE `if (this.pickupDelay <= 0) {`, add:

```java
// SourbyCraft start - anti-snatch owner protection
if (dev.iyanz.sourbycraft.SourbyCraftConfig.ownerProtectionEnabled && this.ownerUUID != null && this.pickupDelay > 0) {
    if (!entity.getUUID().equals(this.ownerUUID)) {
        return; // block non-owner during protection
    }
    this.pickupDelay = 0; // owner bypass — allow immediate pickup
}
// SourbyCraft end
```

This goes right before:
```java
if (this.pickupDelay <= 0) {
```
(above the `PlayerAttemptPickupItemEvent` block, so owner bypass sets pickupDelay=0 then falls through to normal pickup)

- [ ] **Step 5: Pass ownerUUID during merge**

In the 4-arg `merge()` method at line 317, add after `destinationEntity.age = Math.min(...)`:

```java
if (destinationEntity.ownerUUID == null) {
    destinationEntity.ownerUUID = originEntity.ownerUUID;
    destinationEntity.pickupDelay = Math.max(destinationEntity.pickupDelay, originEntity.pickupDelay);
} else if (originEntity.ownerUUID != null && !originEntity.ownerUUID.equals(destinationEntity.ownerUUID)) {
    if (originStack.getCount() > destinationStack.getCount()) {
        destinationEntity.ownerUUID = originEntity.ownerUUID;
        destinationEntity.pickupDelay = Math.max(destinationEntity.pickupDelay, originEntity.pickupDelay);
    }
}
```

- [ ] **Step 6: Verify compile**

```bash
./gradlew :sourbycraft-server:compileJava 2>&1 | grep -E "ItemEntity|error:|BUILD"
```
Expected: BUILD SUCCESSFUL

---

### Task 5: Modify ServerPlayer.java — set ownerUUID on drop

**Files:**
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/server/level/ServerPlayer.java` (generated)

- [ ] **Step 1: Set ownerUUID in drop() method**

In `drop()` at line 2709 (after `ItemEntity itemEntity = super.drop(...)` and before `ItemStack itemStack = ...`), add:

```java
// SourbyCraft start - anti-snatch
if (itemEntity != null && dev.iyanz.sourbycraft.SourbyCraftConfig.ownerProtectionEnabled) {
    itemEntity.ownerUUID = this.getUUID();
    itemEntity.pickupDelay = dev.iyanz.sourbycraft.SourbyCraftConfig.ownerProtectionTime * 20;
}
// SourbyCraft end
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :sourbycraft-server:compileJava 2>&1 | grep -E "ServerPlayer|error:|BUILD"
```
Expected: BUILD SUCCESSFUL

---

### Task 6: Create git-diff patch file for all Minecraft changes

**Files:**
- Create: `patches/minecraft/0032-item-stacking-and-drop-security.patch`

- [ ] **Step 1: Generate the patch**

```bash
cd sourbycraft-server/src/minecraft/java
git diff --no-index --relative /dev/null . > /dev/null 2>&1
```

Instead, manually compose the patch file. Use the following exact content:

Run:
```bash
cat > /Users/rheninxy/Documents/Sourby/SourbyCraft/patches/minecraft/0032-item-stacking-and-drop-security.patch << 'PATCHEOF'
From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001
From: SourbyCraft <dev@iyanz.dev>
Date: Thu, 15 May 2026 00:00:00 +0700
Subject: [PATCH] Item stacking 64->99, unlimited drop merge, anti-snatch owner protection


diff --git a/net/minecraft/server/level/ServerPlayer.java b/net/minecraft/server/level/ServerPlayer.java
--- a/net/minecraft/server/level/ServerPlayer.java
+++ b/net/minecraft/server/level/ServerPlayer.java
@@ -2708,6 +2708,11 @@
     public ItemEntity drop(ItemStack droppedItem, boolean dropAround, boolean traceItem, boolean callEvent, java.util.function.@Nullable Consumer<org.bukkit.entity.Item> entityOperation) { // Paper - Extend dropItem API
         ItemEntity itemEntity = super.drop(droppedItem, dropAround, traceItem, callEvent, entityOperation); // Paper - Extend dropItem API
         ItemStack itemStack = itemEntity != null ? itemEntity.getItem() : ItemStack.EMPTY; // Paper - move up
+        // SourbyCraft start - anti-snatch
+        if (itemEntity != null && dev.iyanz.sourbycraft.SourbyCraftConfig.ownerProtectionEnabled) {
+            itemEntity.ownerUUID = this.getUUID();
+            itemEntity.pickupDelay = dev.iyanz.sourbycraft.SourbyCraftConfig.ownerProtectionTime * 20;
+        }
+        // SourbyCraft end
         if (traceItem) {
             if (!itemStack.isEmpty()) {
                 this.awardStat(Stats.ITEM_DROPPED.get(itemStack.getItem()), itemStack.getCount()); // Paper - use size from dropped item
diff --git a/net/minecraft/world/entity/item/ItemEntity.java b/net/minecraft/world/entity/item/ItemEntity.java
--- a/net/minecraft/world/entity/item/ItemEntity.java
+++ b/net/minecraft/world/entity/item/ItemEntity.java
@@ -46,6 +46,8 @@
     private static final int DEFAULT_HEALTH = 5;
 
     public int age = 0;
+    @Nullable
+    public java.util.UUID ownerUUID = null; // SourbyCraft - anti-snatch
     public int pickupDelay = 0;
     public int health = 5;
     private int despawnRate = 6000; // Paper - Alternative item-despawn-rate
@@ -283,7 +285,7 @@
     private boolean isMergable() {
         ItemStack item = this.getItem();
-        return this.isAlive() && this.pickupDelay != 32767 && this.age != -32768 && this.age < this.despawnRate && item.getCount() < item.getMaxStackSize(); // Paper - Alternative item-despawn-rate
+        return this.isAlive() && this.pickupDelay != 32767 && this.age != -32768 && this.age < this.despawnRate; // Paper - Alternative item-despawn-rate // SourbyCraft - unlimited drop stack
     }
 
     private void tryToMerge(ItemEntity itemEntity) {
@@ -300,8 +302,13 @@
     public static boolean areMergable(ItemStack destinationStack, ItemStack originStack) {
-        return originStack.getCount() + destinationStack.getCount() <= originStack.getMaxStackSize()
-            && ItemStack.isSameItemSameComponents(destinationStack, originStack);
+        if (!dev.iyanz.sourbycraft.SourbyCraftConfig.unlimitedDropStack) {
+            if (originStack.getCount() + destinationStack.getCount() > originStack.getMaxStackSize()) return false;
+        } else if (dev.iyanz.sourbycraft.SourbyCraftConfig.dropStackCap != Integer.MAX_VALUE) {
+            if (originStack.getCount() + destinationStack.getCount() > dev.iyanz.sourbycraft.SourbyCraftConfig.dropStackCap) return false;
+        }
+        return ItemStack.isSameItemSameComponents(destinationStack, originStack);
     }
 
     public static ItemStack merge(ItemStack destinationStack, ItemStack originStack, int amount) {
@@ -312,7 +319,8 @@
     private static void merge(ItemEntity destinationEntity, ItemStack destinationStack, ItemStack originStack) {
-        ItemStack itemStack = merge(destinationStack, originStack, 64);
+        int cap = dev.iyanz.sourbycraft.SourbyCraftConfig.unlimitedDropStack ? dev.iyanz.sourbycraft.SourbyCraftConfig.dropStackCap : originStack.getMaxStackSize();
+        ItemStack itemStack = merge(destinationStack, originStack, cap);
         destinationEntity.setItem(itemStack);
     }
 
@@ -324,6 +332,17 @@
         merge(destinationEntity, destinationStack, originStack);
         destinationEntity.pickupDelay = Math.max(destinationEntity.pickupDelay, originEntity.pickupDelay);
         destinationEntity.age = Math.min(destinationEntity.age, originEntity.age);
+        // SourbyCraft start - anti-snatch owner merge
+        if (destinationEntity.ownerUUID == null) {
+            destinationEntity.ownerUUID = originEntity.ownerUUID;
+            destinationEntity.pickupDelay = Math.max(destinationEntity.pickupDelay, originEntity.pickupDelay);
+        } else if (originEntity.ownerUUID != null && !originEntity.ownerUUID.equals(destinationEntity.ownerUUID)) {
+            if (originStack.getCount() > destinationStack.getCount()) {
+                destinationEntity.ownerUUID = originEntity.ownerUUID;
+                destinationEntity.pickupDelay = Math.max(destinationEntity.pickupDelay, originEntity.pickupDelay);
+            }
+        }
+        // SourbyCraft end
         if (originStack.isEmpty()) {
             originEntity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.MERGE); // CraftBukkit - add Bukkit remove cause
         }
@@ -382,6 +401,9 @@
             output.put("Health", (short)this.health); // Paper start
         }
         output.putShort("PickupDelay", (short)this.pickupDelay);
+        if (this.ownerUUID != null) {
+            output.putUUID("SourbycraftOwner", this.ownerUUID);
+        }
         // Paper start
         if (this.age != null) {
             output.putInt("Age", this.age);
@@ -398,6 +420,9 @@
         }
         this.health = input.getShortOr("Health", (short)5); // Paper start
         this.pickupDelay = input.getShortOr("PickupDelay", (short)0);
+        if (input.hasUUID("SourbycraftOwner")) {
+            this.ownerUUID = input.getUUID("SourbycraftOwner");
+        }
         // Paper start
         if (input.hasUUID("Thrower")) {
             this.target = input.getUUID("Thrower");
@@ -425,6 +450,15 @@
             int remaining = count - canHold;
             boolean flyAtPlayer = false; // Paper
 
+            // SourbyCraft start - anti-snatch owner protection
+            if (dev.iyanz.sourbycraft.SourbyCraftConfig.ownerProtectionEnabled && this.ownerUUID != null && this.pickupDelay > 0) {
+                if (!entity.getUUID().equals(this.ownerUUID)) {
+                    return; // block non-owner during protection
+                }
+                this.pickupDelay = 0; // owner bypass
+            }
+            // SourbyCraft end
+
             // Paper start - PlayerAttemptPickupItemEvent
             if (this.pickupDelay <= 0) {
                 org.bukkit.event.player.PlayerAttemptPickupItemEvent attemptEvent = new org.bukkit.event.player.PlayerAttemptPickupItemEvent((org.bukkit.entity.Player) entity.getBukkitEntity(), (org.bukkit.entity.Item) this.getBukkitEntity(), remaining);
                 this.level().getCraftServer().getPluginManager().callEvent(attemptEvent);
 
diff --git a/net/minecraft/world/item/Item.java b/net/minecraft/world/item/Item.java
--- a/net/minecraft/world/item/Item.java
+++ b/net/minecraft/world/item/Item.java
@@ -109,6 +109,7 @@
     public static final int DEFAULT_MAX_STACK_SIZE = 64;
     public static final int ABSOLUTE_MAX_STACK_SIZE = 99;
+    public static int sourbycraftMaxStackSize = 99; // SourbyCraft - dynamic max stack
 
     public static final MapCodec<ItemStack> CODEC = ExtraCodecs.validate(
         ItemStack.MAP_CODEC, ItemStack::validateStrict
@@ -153,7 +154,10 @@
     public int getDefaultMaxStackSize() {
-        return this.components.getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
+        int max = this.components.getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
+        if (max == DEFAULT_MAX_STACK_SIZE && sourbycraftMaxStackSize != DEFAULT_MAX_STACK_SIZE) {
+            return sourbycraftMaxStackSize;
+        }
+        return max;
     }
PATCHEOF
```

- [ ] **Step 2: Verify patch format**

```bash
cd /Users/rheninxy/Documents/Sourby/SourbyCraft/sourbycraft-server/src/minecraft/java && git apply --check /Users/rheninxy/Documents/Sourby/SourbyCraft/patches/minecraft/0032-item-stacking-and-drop-security.patch 2>&1
```

---

### Task 7: Full build and smoke test

- [ ] **Step 1: Clean rebuild**

```bash
cd /Users/rheninxy/Documents/Sourby/SourbyCraft && ./gradlew :sourbycraft-server:compileJava 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify all three features compile correctly**

```bash
cd /Users/rheninxy/Documents/Sourby/SourbyCraft
grep -r "DEFAULT_MAX_STACK_SIZE" sourbycraft-server/src/minecraft/java/net/minecraft/world/item/Item.java | head -3
grep -r "ownerUUID" sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/item/ItemEntity.java | head -5
grep -r "ownerProtectionEnabled" sourbycraft-server/src/minecraft/java/net/minecraft/server/level/ServerPlayer.java
```
Expected: All return matching lines

- [ ] **Step 3: Commit everything**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java
git add patches/minecraft/0032-item-stacking-and-drop-security.patch
git commit -m "feat: item max stack 99, unlimited drop merge, anti-snatch"
```

---

### Task 8: Push to release tag

- [ ] **Step 1: Push and update tag**

```bash
git push origin HEAD
git tag -f v3
git push origin v3 --force
```
