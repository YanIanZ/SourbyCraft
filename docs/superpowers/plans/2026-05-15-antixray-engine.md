# SourbyCraft Antixray Engine — Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Extend Paper's antixray engine with fluid obscures, all-blocks mode, and entity obfuscation via line-of-sight check.

**Architecture:** Modifications directly in `ChunkPacketBlockControllerAntiXray` constructor extend `solidGlobal[]` and `obfuscateGlobal[]`. Config via `SourbyCraftWorldConfig`. Entity obfuscation via `sendChanges()` filter in `ServerEntity`.

**Tech Stack:** Java 25, PaperMC fork, paperweight build system

---

### Task 1: SourbyCraftWorldConfig — antixray config fields

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftWorldConfig.java`

- [ ] **Step 1: Add field declarations + config loading**

Append BEFORE the closing `}` of the class (after line 78):

```java
    // SourbyCraft start - antixray config
    public boolean fluidObscures = true;
    public boolean allBlocks = false;
    public boolean entityObfuscation = true;
    public int entityObfuscationRange = 64;

    private void antixray() {
        fluidObscures = getBoolean("anticheat.anti-xray.fluid-obscures", fluidObscures);
        allBlocks = getBoolean("anticheat.anti-xray.all-blocks", allBlocks);
        entityObfuscation = getBoolean("anticheat.anti-xray.entity-obfuscation", entityObfuscation);
        entityObfuscationRange = getInt("anticheat.anti-xray.entity-obfuscation-range", entityObfuscationRange);
    }
    // SourbyCraft end
```

- [ ] **Step 2: Verify compile**

```bash
cd /Users/rheninxy/Documents/Sourby/SourbyCraft && rm -rf .gradle/configuration-cache && ./gradlew :sourbycraft-server:compileJava 2>&1 | tail -3
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftWorldConfig.java
git commit -m "feat: antixray config fields in SourbyCraftWorldConfig"
```

---

### Task 2: ChunkPacketBlockControllerAntiXray — fluid obscures + all blocks

**Files:**
- Modify: `paper-server/src/main/java/io/papermc/paper/antixray/ChunkPacketBlockControllerAntiXray.java`
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/world/level/Level.java` (pass config)

- [ ] **Step 1: Add SourbyCraftWorldConfig parameter to controller constructor**

Change constructor signature (line 63) from:

```java
public ChunkPacketBlockControllerAntiXray(Level level, Executor executor) {
```
To:
```java
public ChunkPacketBlockControllerAntiXray(Level level, Executor executor, dev.iyanz.sourbycraft.SourbyCraftWorldConfig sourbyCraftWorldConfig) {
```

Add import at top:
```java
import dev.iyanz.sourbycraft.SourbyCraftWorldConfig;
```

- [ ] **Step 2: Extend obfuscateGlobal[] for all-blocks mode**

After line 128 (end of hidden blocks loop), add:

```java
        // SourbyCraft start - all-blocks mode
        if (sourbyCraftWorldConfig.allBlocks) {
            for (int i = 0; i < obfuscateGlobal.length; i++) {
                BlockState bs = GLOBAL_BLOCKSTATE_PALETTE.valueFor(i);
                if (bs != null && !bs.isAir()) {
                    obfuscateGlobal[i] = true;
                }
            }
        }
        // SourbyCraft end
```

- [ ] **Step 3: Extend solidGlobal[] for fluid obscures**

After line 142 (end of solid loop), add:

```java
        // SourbyCraft start - fluid obscures
        if (sourbyCraftWorldConfig.fluidObscures) {
            solidGlobal[GLOBAL_BLOCKSTATE_PALETTE.idFor(Blocks.WATER.defaultBlockState(), PaletteResize.noResizeExpected())] = true;
            solidGlobal[GLOBAL_BLOCKSTATE_PALETTE.idFor(Blocks.LAVA.defaultBlockState(), PaletteResize.noResizeExpected())] = true;
        }
        // SourbyCraft end
```

- [ ] **Step 4: Add entity visibility check method**

After the constructor, add:

```java
    // SourbyCraft start - entity obfuscation
    private dev.iyanz.sourbycraft.SourbyCraftWorldConfig sourbycraftWorldConfig;

    public boolean isEntityVisibleTo(net.minecraft.world.entity.Entity entity, net.minecraft.server.level.ServerPlayer player) {
        if (!sourbycraftWorldConfig.entityObfuscation) return true;
        if (player.isSpectator()) return true;
        if (player.getBukkitEntity().hasPermission("paper.antixray.bypass")) return true;
        if (entity instanceof net.minecraft.world.entity.TamableAnimal tamable && player.getUUID().equals(tamable.getOwnerUUID())) return true;
        if (player.distanceToSqr(entity) > sourbycraftWorldConfig.entityObfuscationRange * sourbycraftWorldConfig.entityObfuscationRange) return true;

        net.minecraft.world.level.ClipContext ctx = new net.minecraft.world.level.ClipContext(
            player.getEyePosition(),
            entity.getEyePosition(),
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            player
        );
        net.minecraft.world.phys.BlockHitResult hit = player.level().clip(ctx);
        return hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK;
    }
    // SourbyCraft end
```

And store the config reference. Change the constructor to save it:
After line 67 (the maxBlockHeight line), add:
```java
        this.sourbycraftWorldConfig = sourbyCraftWorldConfig;
```

- [ ] **Step 5: Update Level.java to pass SourbyCraftWorldConfig**

In `sourbycraft-server/src/minecraft/java/net/minecraft/world/level/Level.java` (the field setup), find where `chunkPacketBlockController` is assigned. Change from:

```java
this.chunkPacketBlockController = paperWorldConfig.antiXray.enabled ? new ChunkPacketBlockControllerAntiXray(this, executor) : ChunkPacketBlockController.NO_OPERATION_INSTANCE;
```

To:
```java
// SourbyCraft start - pass world config
dev.iyanz.sourbycraft.SourbyCraftWorldConfig scWorldConfig = new dev.iyanz.sourbycraft.SourbyCraftWorldConfig(
    this.getWorld().getName(), this.getWorld().getEnvironment()
);
this.chunkPacketBlockController = paperWorldConfig.antiXray.enabled ? new ChunkPacketBlockControllerAntiXray(this, executor, scWorldConfig) : ChunkPacketBlockController.NO_OPERATION_INSTANCE;
// SourbyCraft end
```

NOTE: Find the exact line in Level.java by searching for `ChunkPacketBlockControllerAntiXray`.

- [ ] **Step 6: Verify compile**

```bash
./gradlew :sourbycraft-server:compileJava 2>&1 | tail -3
```
Expected: BUILD SUCCESSFUL

---

### Task 3: ServerEntity — entity obfuscation filter

**Files:**
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/server/level/ServerEntity.java`

- [ ] **Step 1: Add visibility filter in sendChanges()**

At the very beginning of `sendChanges()` (after line 97, before any packet sends), add:

```java
        // SourbyCraft start - entity obfuscation
        if (this.level.chunkPacketBlockController instanceof io.papermc.paper.antixray.ChunkPacketBlockControllerAntiXray controller) {
            this.trackedPlayers.removeIf(conn ->
                !controller.isEntityVisibleTo(this.entity, conn.getPlayer())
            );
        }
        // SourbyCraft end
```

This removes players who can't see the entity from the tracked players set for this tick. They'll be re-added later when the entity moves into visibility range.

- [ ] **Step 2: Verify compile**

```bash
./gradlew :sourbycraft-server:compileJava 2>&1 | tail -3
```
Expected: BUILD SUCCESSFUL

---

### Task 4: Patch file + full build

**Files:**
- Create: `patches/minecraft/0024-antixray-fluid-obscures-allblocks.patch`

- [ ] **Step 1: Create the patch**

The patch should contain diffs for: `ChunkPacketBlockControllerAntiXray.java`, `Level.java`, `ServerEntity.java` (all in `paper-server/src/main/java/` relative paths). Use git diff format matching line numbers from the actual generated files.

Write it to `patches/minecraft/0024-antixray-fluid-obscures-allblocks.patch`.

- [ ] **Step 2: Clean build paperclip**

```bash
rm -rf .gradle/configuration-cache && ./gradlew :sourbycraft-server:createMojmapPaperclipJar 2>&1 | tail -3
```
Expected: BUILD SUCCESSFUL

---

### Task 5: Commit + push

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftWorldConfig.java
git add patches/minecraft/0024-antixray-fluid-obscures-allblocks.patch
git commit -m "feat: antixray fluid obscures, all-blocks, entity obfuscation"
git push origin HEAD && git tag -f v3 && git push origin v3 --force
```
