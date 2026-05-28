# SourbyCraft v7 — DAB Tuning & Mob AI Fix

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Upgrade v6→v7: per-entity DAB config system, wire dead config fields (mobTickDistance, mobPathfindInterval) ke game loop.

**Architecture:** Add `dab.entity-overrides` config section in SourbyCraftConfig. Wire overrides into `ActivationRange.java` (Pufferfish DAB calculation). Wire `mobTickDistance` into entity tick radius, `mobPathfindInterval` into GoalSelector throttle. Version bump v6→v7.

**Tech Stack:** Java 25, Paper 1.21.11, Pufferfish DAB, Minecraft NMS (Mob, ActivationRange, GoalSelector, ServerChunkCache)

---

## File Structure

```
sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/
├── SourbyCraftConfig.java         (MODIFIED — DAB entity overrides + version bump)
└── (no new files — reuse existing PufferfishConfig for globals)

sourbycraft-server/src/minecraft/java/net/minecraft/
├── world/entity/Mob.java          (MODIFIED via patch — DAB priority from config)
├── world/entity/ActivationRange.java (MODIFIED via patch — per-entity overrides)
├── world/entity/ai/goal/GoalSelector.java (MODIFIED via patch — mobPathfindInterval)
└── server/level/ServerChunkCache.java (MODIFIED via patch — mobTickDistance)

gradle.properties                   (MODIFIED — v6→v7)
sourbycraft-swm-api/build.gradle.kts (MODIFIED — jar path)
```

---

### Task 1: DAB Config + Version Bump

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
- Modify: `gradle.properties`
- Modify: `sourbycraft-swm-api/build.gradle.kts`

- [ ] **Step 1: Add DAB entity override config fields**

Add after existing entity config fields in SourbyCraftConfig.java:

```java
    // DAB entity overrides: key = "minecraft:zombie", value = [maxTickFreq, activationDistMod]
    public static final java.util.Map<String, int[]> dabEntityOverrides = new java.util.concurrent.ConcurrentHashMap<>();
```

- [ ] **Step 2: Add config reading in init()**

Add after entity config reads (after line ~185):

```java
        // DAB entity overrides
        org.bukkit.configuration.ConfigurationSection dabSec = config.getConfigurationSection("dab.entity-overrides");
        if (dabSec != null) {
            for (String key : dabSec.getKeys(false)) {
                int freq = dabSec.getInt(key + ".max-tick-freq", 20);
                int mod = dabSec.getInt(key + ".activation-dist-mod", 8);
                dabEntityOverrides.put(key, new int[]{freq, mod});
            }
        }
```

- [ ] **Step 3: Version bump v6→v7**

In `gradle.properties`:
```properties
version = v7-REL
releaseVersion = 7
```

In `SourbyCraftConfig.java` line 24:
```java
    static int version, currentVersion = 7;
```

Line 39: change `"v6-REL"` → `"v7-REL"`.

Add migration block after existing v4/v5→v6 migration (line ~140):
```java
            if ("v6-REL".equals(swmVersion)) {
                swmVersion = "v7-REL";
                set("swm.version", swmVersion);
            }
```

In `sourbycraft-swm-api/build.gradle.kts` line 30: `v6-REL.jar` → `v7-REL.jar`.

- [ ] **Step 4: Compile and commit**

```bash
./gradlew :sourbycraft-server:compileJava
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java gradle.properties sourbycraft-swm-api/build.gradle.kts
git commit -m "feat(v7): DAB entity override config + version bump v6→v7"
```

---

### Task 2: Wire DAB Overrides into ActivationRange

**Files:**
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/ActivationRange.java`

- [ ] **Step 1: Find DAB priority calculation**

Search for `activatedPriority` assignment in ActivationRange.java:
```bash
grep -n "activatedPriority\|activationDistanceMod\|maximumActivationPrio" sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/ActivationRange.java
```

- [ ] **Step 2: Modify priority calculation to check overrides**

Replace the hardcoded `activationDistanceMod` and `maximumActivationPrio` usage with override-aware version:

```java
// Before (simplified):
int priority = Math.max(1, Math.min(sqDist >> PufferfishConfig.activationDistanceMod,
    PufferfishConfig.maximumActivationPrio));

// After:
int distMod = PufferfishConfig.activationDistanceMod;
int maxPrio = PufferfishConfig.maximumActivationPrio;
String typeKey = entity.getType().getDescriptionId(); // "entity.minecraft.zombie"
int[] override = SourbyCraftConfig.dabEntityOverrides.get(typeKey);
if (override != null) {
    maxPrio = override[0];
    distMod = override[1];
}
int priority = Math.max(1, Math.min(sqDist >> distMod, maxPrio));
```

- [ ] **Step 3: Compile and commit**

```bash
./gradlew :sourbycraft-server:compileJava
git add sourbycraft-server/src/minecraft/
git commit -m "feat(v7): wire DAB entity overrides to ActivationRange priority calc"
```

---

### Task 3: Wire mobTickDistance + mobPathfindInterval

**Files:**
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/server/level/ServerChunkCache.java`
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/ai/goal/GoalSelector.java`

- [ ] **Step 1: Wire mobTickDistance to entity tick radius**

In ServerChunkCache.java, find the entity ticking section (likely `tickChunks()` or similar). Add radius check:

```java
// Limit entity ticking to mobTickDistance from nearest player
int tickDist = SourbyCraftConfig.mobTickDistance;
if (tickDist > 0) {
    // Only tick entities within tickDist blocks of a player
    // Skip entities beyond this distance
}
```

- [ ] **Step 2: Wire mobPathfindInterval to GoalSelector**

In GoalSelector.java, find the `inactiveTick()` method throttle:

```java
// Before:
public boolean inactiveTick(int tickRate, boolean inactive) {
    // uses hardcoded logic
}

// After: use config value
public boolean inactiveTick(int tickRate, boolean inactive) {
    int interval = dev.iyanz.sourbycraft.SourbyCraftConfig.mobPathfindInterval;
    // use interval for throttle calculation
}
```

- [ ] **Step 3: Compile and commit**

```bash
./gradlew :sourbycraft-server:compileJava
git add sourbycraft-server/src/minecraft/
git commit -m "feat(v7): wire mobTickDistance and mobPathfindInterval to game loop"
```

---

### Task 4: Fixup Minecraft Patches + Build

- [ ] **Step 1: Rebuild minecraft source patches from changes**

```bash
# Instead of using destructive rebuild, manually update the patch files
# OR: directly edit source files and skip patch regeneration
```

⚠️ **NOTE:** `rebuildMinecraftFeaturePatches` deletes all patches. Safer approach: edit source files directly and let them persist. They're in `src/minecraft/` and compiled as-is.

- [ ] **Step 2: Full build**

```bash
./gradlew :sourbycraft-server:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit remaining patch changes**

```bash
git add sourbycraft-server/src/minecraft/
git commit -m "feat(v7): mob AI patches — DAB overrides, wired config fields"
```

---

### Task 5: Final Verification

- [ ] **Step 1: Full build**

```bash
./gradlew :sourbycraft-server:jar :sourbycraft-swm-api:extractApi
```

Expected: BUILD SUCCESSFUL, manifest shows `v7-REL`.

- [ ] **Step 2: Verify JAR**

```bash
jar xf sourbycraft-server/build/libs/sourbycraft-server-v7-REL.jar META-INF/MANIFEST.MF
grep "Implementation-Version" META-INF/MANIFEST.MF
```

Expected: `1.21.11-v7-REL`

---

## Implementation Order

1. **Task 1** — Config + version bump (foundation, no dependencies)
2. **Task 2** — DAB wire (depends on Task 1 for config fields)
3. **Task 3** — Dead config wire (independent, can parallel with Task 2)
4. **Task 4** — Fixup patches (after Tasks 2+3 source changes)
5. **Task 5** — Final verification
