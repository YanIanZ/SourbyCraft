# Perf-engine P2 — Lag-Machine Protection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship 5 NMS lag-machine protection patches wired to 8 new Knobs (snowball/firework save fixes, projectile chunk-load throttle, excess minecart/boat removal on collision).

**Architecture:** Adds `LAG_MACHINE_*` static-final knobs to existing `Knobs.java`. Wires operator-yml bridge in `SourbyCraftConfig.init()`. Adds 5 separate NMS patches under `patches/minecraft/` (one per protection). Adds 1 helper class `LagMachineCounters` for the per-tick projectile counter. Static yml config only; tier-driven escalation deferred to P7 controller.

**Tech Stack:** Java (Paper fork, mojmap), paperweight v2.0-beta.19, Brigadier (no new cmds; uses existing `Knobs.logLoaded("boot")` for verification).

**Spec:** `docs/superpowers/specs/2026-06-06-perf-engine-P2-lag-machine-design.md` (committed `8fd262a`).

---

## Deviations / Adaptations From Spec (documented inline)

1. **Patch numbers:** spec said "`00NN` and up" — next free under `patches/minecraft/` is `0046` (last used: `0045-...sensor-tick-hook.patch` for P1). P2 uses `0046`-`0050`.
2. **NMS class paths confirmed** (verified by grep):
   - `net/minecraft/world/entity/projectile/throwableitemprojectile/Snowball.java`
   - `net/minecraft/world/entity/projectile/FireworkRocketEntity.java`
   - `net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java` (push at line 540)
   - `net/minecraft/world/entity/vehicle/boat/AbstractBoat.java` (push at line 186)
3. **Snowball + Firework do not have `shouldBeSaved()` override in vanilla** — patches ADD the override.
4. **Projectile chunk-load site exact location TBD at impl time** — spec defers this. Likely in `Projectile.java` move/tick path; alternatively wrap `ServerLevel.getChunk(...)` calls invoked from `Projectile` contexts. Plan Task 4 includes a grep helper to locate.

---

## File Structure

**Created:**
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/LagMachineCounters.java` — per-tick projectile-load counter
- `patches/minecraft/0046-perf-engine-P2-disable-saving-snowballs.patch` — Snowball NBT save skip
- `patches/minecraft/0047-perf-engine-P2-disable-saving-fireworks.patch` — Firework NBT save skip
- `patches/minecraft/0048-perf-engine-P2-projectile-chunkload-limiter.patch` — projectile chunk-load throttle + tick-reset hook + per-projectile field
- `patches/minecraft/0049-perf-engine-P2-remove-excess-minecarts.patch` — minecart collision cleanup
- `patches/minecraft/0050-perf-engine-P2-remove-excess-boats.patch` — boat collision cleanup

**Modified:**
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java` — add 8 `LAG_MACHINE_*` knobs
- `sourbycraft-server/src/main/resources/sourbycraft.yml` — append `perf.lag-machine:` block
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` — add operator-yml bridge

---

## TDD adaptation

Per `feedback-no-smoke-harness` memory: NO automated test surface added. NO JUnit. NO bash smoke. Verification per task = operator boots `test-harness/TestServer-mojmap/` manually, observes log lines (`Knobs.logLoaded()` boot summary) + behavior. Cheap compile gate (`./gradlew :sourbycraft-server:classes`) between code edits.

---

## Task 1: Knobs.java additions + yml schema growth

**Goal:** Land 8 new `LAG_MACHINE_*` static-final knobs in `Knobs.java`. Append `perf.lag-machine:` block to `sourbycraft.yml`. Compile-only gate; no NMS patch yet, no NMS call site touches the new knobs.

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java`
- Modify: `sourbycraft-server/src/main/resources/sourbycraft.yml`

- [ ] **Step 1: Add 8 knob declarations to `Knobs.java`**

Open `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java`. Locate the existing `ENTITY_TICK_RATE` declaration. Add immediately AFTER it:

```java
    // === P2 Lag-Machine Protection knobs ===

    /** Disables NBT saving for Snowball entities. Saved snowballs are a known lag-machine vector
     *  (despawn-on-load thousands per chunk → slow chunk load). Default true per UniverseSpigot rec. */
    public static final BoolKnob LAG_MACHINE_DISABLE_SAVING_SNOWBALLS =
        new BoolKnob("perf.lag-machine.disable-saving-snowballs", true);

    /** Disables NBT saving for FireworkRocket entities. Same vector as snowballs. */
    public static final BoolKnob LAG_MACHINE_DISABLE_SAVING_FIREWORKS =
        new BoolKnob("perf.lag-machine.disable-saving-fireworks", true);

    /** Max total projectile-triggered chunk loads per server tick. 0 = unlimited. */
    public static final IntKnob LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK =
        new IntKnob("perf.lag-machine.max-projectile-loads-per-tick", 10, 0, 1000);

    /** Max chunk loads a single projectile can trigger before being discarded. 0 = unlimited. */
    public static final IntKnob LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE =
        new IntKnob("perf.lag-machine.max-projectile-loads-per-projectile", 10, 0, 100);

    /** Enables removing excess minecarts on collision (vehicle cap). */
    public static final BoolKnob LAG_MACHINE_REMOVE_EXCESS_MINECARTS =
        new BoolKnob("perf.lag-machine.remove-excess-minecarts", false);

    /** Threshold for "excess" minecarts at a collision point. */
    public static final IntKnob LAG_MACHINE_EXCESS_MINECARTS_LIMIT =
        new IntKnob("perf.lag-machine.excess-minecarts-limit", 10, 1, 1000);

    /** Enables removing excess boats on collision (vehicle cap). */
    public static final BoolKnob LAG_MACHINE_REMOVE_EXCESS_BOATS =
        new BoolKnob("perf.lag-machine.remove-excess-boats", false);

    /** Threshold for "excess" boats at a collision point. */
    public static final IntKnob LAG_MACHINE_EXCESS_BOATS_LIMIT =
        new IntKnob("perf.lag-machine.excess-boats-limit", 10, 1, 1000);
```

- [ ] **Step 2: Append `perf.lag-machine:` block to `sourbycraft.yml`**

Open `sourbycraft-server/src/main/resources/sourbycraft.yml`. Locate the existing `perf:` section. After the `sensor:` sub-section, append (2-space indent under `perf:`):

```yaml
  lag-machine:
    # P2 lag-machine protection toggles. Each wraps a single NMS call site.
    # Operator sourbycraft.yml `perf.lag-machine.*` overrides these at boot.
    # Save-fixes (snowballs, fireworks) ON by default — saved entities of these types
    # are a known lag-machine vector and the gameplay cost of dropping their NBT save is zero.
    # Excess-vehicle removal OFF by default — could surprise minecart-art servers.
    disable-saving-snowballs: true
    disable-saving-fireworks: true
    max-projectile-loads-per-tick: 10           # 0 = unlimited
    max-projectile-loads-per-projectile: 10     # 0 = unlimited
    remove-excess-minecarts: false
    excess-minecarts-limit: 10
    remove-excess-boats: false
    excess-boats-limit: 10
```

- [ ] **Step 3: Compile**

```bash
./gradlew :sourbycraft-server:classes
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify knob count**

```bash
grep -c "new BoolKnob(\"perf\\.lag-machine" sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java
grep -c "new IntKnob(\"perf\\.lag-machine" sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java
```

Expected: `4` (BoolKnobs) and `4` (IntKnobs).

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java \
        sourbycraft-server/src/main/resources/sourbycraft.yml
git commit -m "feat: perf-engine P2 — Knobs + yml schema for 8 lag-machine toggles"
```

---

## Task 2: SourbyCraftConfig operator-yml bridge

**Goal:** Wire operator-yml override path for the 8 new P2 knobs in `SourbyCraftConfig.init()`. Mirrors P1's `applyOperatorConfig` pattern. Boot test: verify `Knobs.logLoaded()` line lists all 8 P2 entries with default values.

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`

- [ ] **Step 1: Locate the P1 sensor bridge block**

```bash
grep -n "PerfSensor.applyOperatorConfig" sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java
```

Note the line range. The P2 bridge goes IMMEDIATELY AFTER the closing `}` of P1's `try { ... } catch (Throwable t) { ... }` block.

- [ ] **Step 2: Insert P2 bridge block**

Add this block immediately after the P1 `applyOperatorConfig` try/catch:

```java
        // SourbyCraft - perf-engine P2: operator sourbycraft.yml bridge for lag-machine knobs.
        try {
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_DISABLE_SAVING_SNOWBALLS.set(
                cfgBool("perf.lag-machine.disable-saving-snowballs", true));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_DISABLE_SAVING_FIREWORKS.set(
                cfgBool("perf.lag-machine.disable-saving-fireworks", true));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.set(
                cfgInt("perf.lag-machine.max-projectile-loads-per-tick", 10));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.set(
                cfgInt("perf.lag-machine.max-projectile-loads-per-projectile", 10));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.set(
                cfgBool("perf.lag-machine.remove-excess-minecarts", false));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.set(
                cfgInt("perf.lag-machine.excess-minecarts-limit", 10));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.set(
                cfgBool("perf.lag-machine.remove-excess-boats", false));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.set(
                cfgInt("perf.lag-machine.excess-boats-limit", 10));
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("P2 lag-machine operator-config bridge failed; using yml defaults", t);
        }
```

- [ ] **Step 3: Compile**

```bash
./gradlew :sourbycraft-server:classes
```

Expected: BUILD SUCCESSFUL. If FAIL with "cannot find symbol cfgBool/cfgInt", the helpers don't exist in this branch — search for them:

```bash
grep -n "static.*cfgBool\|static.*cfgInt" sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java
```

If they're absent, the P1 commit landed them. Verify by checking commit history; the methods were added during P1 Task 3. If truly missing, report BLOCKED.

- [ ] **Step 4: Rebuild release jar + boot test**

```bash
./gradlew assembleReleaseArtifacts --no-configuration-cache
cp release/SourbyCraft-12-EXP.jar test-harness/TestServer-mojmap/server.jar
rm -f test-harness/TestServer-mojmap/boot.log
cd test-harness/TestServer-mojmap && java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
PID=$!
cd - >/dev/null
```

Wait for `Done (`:

```bash
until grep -q "Done (" test-harness/TestServer-mojmap/boot.log 2>/dev/null; do
    if ! kill -0 $PID 2>/dev/null; then echo DIED; tail -30 test-harness/TestServer-mojmap/boot.log; break; fi
    sleep 5
done
```

- [ ] **Step 5: Verify `Knobs.logLoaded()` lists all 8 P2 entries**

```bash
grep "perf knobs loaded" test-harness/TestServer-mojmap/boot.log
```

Expected: one line containing all 8 `perf.lag-machine.*=*` entries with default values (`disable-saving-snowballs=true`, `disable-saving-fireworks=true`, `max-projectile-loads-per-tick=10`, `max-projectile-loads-per-projectile=10`, `remove-excess-minecarts=false`, `excess-minecarts-limit=10`, `remove-excess-boats=false`, `excess-boats-limit=10`).

- [ ] **Step 6: Shutdown + commit**

```bash
pkill -f "server.jar nogui" 2>/dev/null; sleep 3
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java
git commit -m "feat: perf-engine P2 — operator sourbycraft.yml bridge for lag-machine knobs"
```

---

## Task 3: Snowball save-skip patch (0046)

**Goal:** Patch `Snowball.java` to override `shouldBeSaved()` and return `false` when `LAG_MACHINE_DISABLE_SAVING_SNOWBALLS` is true.

**Files:**
- Modify (via paperweight patch regen): `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/projectile/throwableitemprojectile/Snowball.java`
- Create: `patches/minecraft/0046-perf-engine-P2-disable-saving-snowballs.patch`

- [ ] **Step 1: Apply existing patches**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Inspect vanilla `Snowball.java`**

```bash
cat sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/projectile/throwableitemprojectile/Snowball.java | head -40
```

Note the class declaration `public class Snowball extends ThrowableItemProjectile { ... }`. Confirm there's no existing `shouldBeSaved()` override (we ADD it).

- [ ] **Step 3: Add `shouldBeSaved()` override**

Open `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/projectile/throwableitemprojectile/Snowball.java`. Find the closing `}` of the class. Insert immediately BEFORE the closing `}`:

```java

    // SourbyCraft start - perf-engine P2 lag-machine: skip NBT save for snowballs
    @Override
    public boolean shouldBeSaved() {
        if (dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_DISABLE_SAVING_SNOWBALLS.get()) {
            return false;
        }
        return super.shouldBeSaved();
    }
    // SourbyCraft end - perf-engine P2 lag-machine
```

- [ ] **Step 4: Compile**

```bash
./gradlew :sourbycraft-server:classes 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL. If FAIL with "method shouldBeSaved() not found in superclass" or similar, the override target is wrong — verify by:

```bash
grep -rn "public boolean shouldBeSaved\|public boolean isPersisted\|protected boolean shouldBeSaved" sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/ | grep -i "entity\\|projectile" | head
```

The Entity base method is on `net.minecraft.world.entity.Entity`. Use its exact signature. If it's `boolean shouldBeSaved()` no override needed for `@Override`; if it's something else, adjust the override.

- [ ] **Step 5: Regenerate paperweight patches**

```bash
./gradlew tasks --group paperweight 2>&1 | grep -iE "rebuild|patches" | head
```

Find the rebuild task name (likely `rebuildPaperServerPatches` or `rebuildPaperPatches`). Run it:

```bash
./gradlew rebuildPaperServerPatches 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. New patch file appears at `patches/minecraft/0046-...patch`. Paperweight names patches from the most recent staged change — if it picks a different name, rename the file to `patches/minecraft/0046-perf-engine-P2-disable-saving-snowballs.patch`.

If paperweight doesn't auto-stage, you may need to:
```bash
cd $(./gradlew :sourbycraft-server:paperweightPaths 2>/dev/null | grep -i "minecraft source" | awk '{print $NF}')  # find paper source dir
git add -A; git commit -m "SourbyCraft: perf-engine P2 disable saving snowballs"
cd - >/dev/null
./gradlew rebuildPaperServerPatches
```

- [ ] **Step 6: Verify patch applies cleanly + boot test**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -3
./gradlew assembleReleaseArtifacts --no-configuration-cache 2>&1 | tail -3
cp release/SourbyCraft-12-EXP.jar test-harness/TestServer-mojmap/server.jar
rm -f test-harness/TestServer-mojmap/boot.log
cd test-harness/TestServer-mojmap && java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
PID=$!
cd - >/dev/null
until grep -q "Done (" test-harness/TestServer-mojmap/boot.log 2>/dev/null; do sleep 5; done
pkill -f "server.jar nogui"; sleep 3
```

Expected: `Done (` reached. Operator can manually test snowball save skip by spawning snowballs and reloading chunks.

- [ ] **Step 7: Commit**

```bash
git add patches/minecraft/0046-*.patch
git commit -m "feat: perf-engine P2 — disable saving snowballs (lag-machine save vector)"
```

---

## Task 4: Firework save-skip patch (0047)

**Goal:** Patch `FireworkRocketEntity.java` to override `shouldBeSaved()` and return `false` when `LAG_MACHINE_DISABLE_SAVING_FIREWORKS` is true. Same shape as Task 3.

**Files:**
- Modify (via patch regen): `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/projectile/FireworkRocketEntity.java`
- Create: `patches/minecraft/0047-perf-engine-P2-disable-saving-fireworks.patch`

- [ ] **Step 1: Apply existing patches**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -3
```

- [ ] **Step 2: Add `shouldBeSaved()` override to `FireworkRocketEntity.java`**

Open `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/projectile/FireworkRocketEntity.java`. Find the closing `}` of the class. Insert immediately BEFORE it:

```java

    // SourbyCraft start - perf-engine P2 lag-machine: skip NBT save for fireworks
    @Override
    public boolean shouldBeSaved() {
        if (dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_DISABLE_SAVING_FIREWORKS.get()) {
            return false;
        }
        return super.shouldBeSaved();
    }
    // SourbyCraft end - perf-engine P2 lag-machine
```

- [ ] **Step 3: Compile + regenerate patch**

```bash
./gradlew :sourbycraft-server:classes 2>&1 | tail -3
./gradlew rebuildPaperServerPatches 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL + new patch at `patches/minecraft/0047-...patch`. Rename to `patches/minecraft/0047-perf-engine-P2-disable-saving-fireworks.patch` if needed.

- [ ] **Step 4: Boot test**

```bash
./gradlew assembleReleaseArtifacts --no-configuration-cache 2>&1 | tail -3
cp release/SourbyCraft-12-EXP.jar test-harness/TestServer-mojmap/server.jar
rm -f test-harness/TestServer-mojmap/boot.log
cd test-harness/TestServer-mojmap && java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
PID=$!
cd - >/dev/null
until grep -q "Done (" test-harness/TestServer-mojmap/boot.log 2>/dev/null; do sleep 5; done
pkill -f "server.jar nogui"; sleep 3
```

Expected: `Done (` reached.

- [ ] **Step 5: Commit**

```bash
git add patches/minecraft/0047-*.patch
git commit -m "feat: perf-engine P2 — disable saving fireworks (lag-machine save vector)"
```

---

## Task 5: Projectile chunk-load throttle (0048) + LagMachineCounters

**Goal:** Add `LagMachineCounters` helper, NMS hook to reset per-tick counter, per-projectile counter field, and wrap projectile chunk-load call site with the two-counter check.

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/LagMachineCounters.java`
- Modify (via patch): `sourbycraft-server/src/minecraft/java/net/minecraft/server/MinecraftServer.java` (tick reset hook)
- Modify (via patch): a vanilla projectile class — exact target identified at Step 2 below
- Create: `patches/minecraft/0048-perf-engine-P2-projectile-chunkload-limiter.patch`

- [ ] **Step 1: Create `LagMachineCounters.java`**

Create the file with exactly this content:

```java
package dev.iyanz.sourbycraft.perf;

/**
 * Per-tick projectile-chunk-load counter for the P2 lag-machine throttle.
 * Reset at tick start by a MinecraftServer.tickChildren hook. Main-thread access only —
 * no synchronization needed.
 */
public final class LagMachineCounters {

    private static int projectileChunkLoadsThisTick = 0;

    private LagMachineCounters() {}

    public static void resetTickCounters() {
        projectileChunkLoadsThisTick = 0;
    }

    public static int projectileChunkLoadsThisTick() {
        return projectileChunkLoadsThisTick;
    }

    public static void incrementProjectileChunkLoad() {
        projectileChunkLoadsThisTick++;
    }
}
```

- [ ] **Step 2: Locate the projectile chunk-load trigger site in vanilla**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -3
grep -rn "getChunk\|loadChunk" sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/projectile/ 2>/dev/null | head -10
grep -rn "Projectile\b" sourbycraft-server/src/minecraft/java/net/minecraft/world/level/entity/ 2>/dev/null | head -10
grep -n "moveTo\|protected void tick\|public void tick" sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/projectile/Projectile.java | head
```

The chunk-load trigger is most commonly inside `Projectile.tick()` or the movement path's chunk-boundary cross. Identify ONE site that fires whenever a projectile triggers a chunk to load. Typical candidates:
- `Projectile.tick()` near the `super.tick()` or motion-apply step
- `Entity.checkInsideBlocks()` from a projectile context

If unclear, conservative fallback: hook the `Projectile.tick()` method's first line. Counter still works because the check fires per-tick-per-projectile (slightly over-counts but doesn't false-negative).

Pick: **`Projectile.java` — at the start of the `tick()` method** (over-conservative; preferred for simplicity unless impl-time inspection reveals a tighter site).

- [ ] **Step 3: Add `sourbyLoadsByProjectile` field to vanilla `Projectile.java`**

Open `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/projectile/Projectile.java`. Locate the class field declarations near the top. Add:

```java
    // SourbyCraft - perf-engine P2 lag-machine: per-projectile chunk-load counter
    public int sourbyLoadsByProjectile = 0;
```

- [ ] **Step 4: Wrap the `tick()` method entry with throttle check**

In the SAME `Projectile.java`, locate `public void tick()` or `protected void tick()`. Insert at the very start of the method body (before any other statement):

```java
        // SourbyCraft start - perf-engine P2 lag-machine: projectile chunk-load throttle
        {
            final int perTickMax = dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.get();
            final int perProjMax = dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.get();
            if (perTickMax > 0 &&
                dev.iyanz.sourbycraft.perf.LagMachineCounters.projectileChunkLoadsThisTick() >= perTickMax) {
                this.discard();
                return;
            }
            if (perProjMax > 0 && this.sourbyLoadsByProjectile >= perProjMax) {
                this.discard();
                return;
            }
            dev.iyanz.sourbycraft.perf.LagMachineCounters.incrementProjectileChunkLoad();
            this.sourbyLoadsByProjectile++;
        }
        // SourbyCraft end - perf-engine P2 lag-machine
```

- [ ] **Step 5: Add tick-reset hook to `MinecraftServer.tickChildren`**

Open `sourbycraft-server/src/minecraft/java/net/minecraft/server/MinecraftServer.java`. Locate the existing `BossBarTicker.tick()` hook (added by patch `0031` and used as a template for P1's `PerfSensor.tick(this)` hook). Find the P1 sensor hook (sibling line; should be near `BossBarTicker`). Add this NEW hook IMMEDIATELY AFTER the P1 hook:

```java
        // SourbyCraft start - perf-engine P2 lag-machine: reset projectile-load tick counter
        try { dev.iyanz.sourbycraft.perf.LagMachineCounters.resetTickCounters(); }
        catch (Throwable t) { /* never fail tickChildren */ }
        // SourbyCraft end - perf-engine P2 lag-machine
```

- [ ] **Step 6: Compile + regenerate patches**

```bash
./gradlew :sourbycraft-server:classes 2>&1 | tail -3
./gradlew rebuildPaperServerPatches 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. Paperweight may produce ONE combined patch covering all three sites (Projectile.java + MinecraftServer.java tick hook) or multiple. Rename the output patch to `patches/minecraft/0048-perf-engine-P2-projectile-chunkload-limiter.patch`.

If paperweight produces multiple patches, keep them as-is but ensure they all match the P2 numbering and naming.

- [ ] **Step 7: Apply + boot test**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -3
./gradlew assembleReleaseArtifacts --no-configuration-cache 2>&1 | tail -3
cp release/SourbyCraft-12-EXP.jar test-harness/TestServer-mojmap/server.jar
rm -f test-harness/TestServer-mojmap/boot.log
cd test-harness/TestServer-mojmap && java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
PID=$!
cd - >/dev/null
until grep -q "Done (" test-harness/TestServer-mojmap/boot.log 2>/dev/null; do sleep 5; done
pkill -f "server.jar nogui"; sleep 3
```

Expected: `Done (` reached. Server tick still happens normally. Throw a snowball at a far chunk to test throttle (manual; operator-driven).

- [ ] **Step 8: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/LagMachineCounters.java \
        patches/minecraft/0048-*.patch
git commit -m "feat: perf-engine P2 — projectile chunk-load throttle (lag-machine load vector)

Adds LagMachineCounters helper for per-tick projectile-chunk-load counts.
Adds public int sourbyLoadsByProjectile field to vanilla Projectile.
Wraps Projectile.tick() with two-counter check (per-tick + per-projectile).
Adds MinecraftServer.tickChildren hook to reset per-tick counter each
server tick (sibling to existing P1 PerfSensor.tick hook)."
```

---

## Task 6: Excess minecart removal patch (0049)

**Goal:** Patch `AbstractMinecart.push(Entity)` to remove excess colliding minecarts when `LAG_MACHINE_REMOVE_EXCESS_MINECARTS` is true.

**Files:**
- Modify (via patch): `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java`
- Create: `patches/minecraft/0049-perf-engine-P2-remove-excess-minecarts.patch`

- [ ] **Step 1: Apply existing patches**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -3
```

- [ ] **Step 2: Locate the `push(Entity)` method**

```bash
grep -n "public void push(Entity" sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java
```

Expected: one hit around line 540.

- [ ] **Step 3: Insert the excess-removal block at the start of `push(Entity)`**

Open the file. Find `public void push(Entity entity) {`. Insert at the very start of the method body (immediately after the opening `{`):

```java
        // SourbyCraft start - perf-engine P2 lag-machine: remove excess minecarts on collision
        if (dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.get()) {
            final int limit = dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.get();
            if (limit > 0) {
                net.minecraft.world.phys.AABB area = this.getBoundingBox().inflate(2.0);
                java.util.List<? extends AbstractMinecart> nearby =
                    this.level().getEntitiesOfClass(AbstractMinecart.class, area);
                if (nearby.size() > limit) {
                    this.discard();
                    return;
                }
            }
        }
        // SourbyCraft end - perf-engine P2 lag-machine
```

- [ ] **Step 4: Compile + regenerate patch**

```bash
./gradlew :sourbycraft-server:classes 2>&1 | tail -3
./gradlew rebuildPaperServerPatches 2>&1 | tail -5
```

Rename output patch to `patches/minecraft/0049-perf-engine-P2-remove-excess-minecarts.patch` if needed.

- [ ] **Step 5: Apply + boot test**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -3
./gradlew assembleReleaseArtifacts --no-configuration-cache 2>&1 | tail -3
cp release/SourbyCraft-12-EXP.jar test-harness/TestServer-mojmap/server.jar
rm -f test-harness/TestServer-mojmap/boot.log
cd test-harness/TestServer-mojmap && java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
PID=$!
cd - >/dev/null
until grep -q "Done (" test-harness/TestServer-mojmap/boot.log 2>/dev/null; do sleep 5; done
pkill -f "server.jar nogui"; sleep 3
```

Expected: `Done (` reached.

- [ ] **Step 6: Commit**

```bash
git add patches/minecraft/0049-*.patch
git commit -m "feat: perf-engine P2 — remove excess minecarts on collision"
```

---

## Task 7: Excess boat removal patch (0050)

**Goal:** Patch `AbstractBoat.push(Entity)` to remove excess colliding boats. Same shape as Task 6.

**Files:**
- Modify (via patch): `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/vehicle/boat/AbstractBoat.java`
- Create: `patches/minecraft/0050-perf-engine-P2-remove-excess-boats.patch`

- [ ] **Step 1: Apply existing patches**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -3
```

- [ ] **Step 2: Locate `push(Entity)` in `AbstractBoat.java`**

```bash
grep -n "public void push(Entity" sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/vehicle/boat/AbstractBoat.java
```

Expected: one hit around line 186.

- [ ] **Step 3: Insert the excess-removal block at the start of `push(Entity)`**

Open the file. Find `public void push(Entity entity) {`. Insert at the very start of the method body:

```java
        // SourbyCraft start - perf-engine P2 lag-machine: remove excess boats on collision
        if (dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.get()) {
            final int limit = dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.get();
            if (limit > 0) {
                net.minecraft.world.phys.AABB area = this.getBoundingBox().inflate(2.0);
                java.util.List<? extends AbstractBoat> nearby =
                    this.level().getEntitiesOfClass(AbstractBoat.class, area);
                if (nearby.size() > limit) {
                    this.discard();
                    return;
                }
            }
        }
        // SourbyCraft end - perf-engine P2 lag-machine
```

- [ ] **Step 4: Compile + regenerate patch**

```bash
./gradlew :sourbycraft-server:classes 2>&1 | tail -3
./gradlew rebuildPaperServerPatches 2>&1 | tail -5
```

Rename output patch to `patches/minecraft/0050-perf-engine-P2-remove-excess-boats.patch` if needed.

- [ ] **Step 5: Apply + boot test**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -3
./gradlew assembleReleaseArtifacts --no-configuration-cache 2>&1 | tail -3
cp release/SourbyCraft-12-EXP.jar test-harness/TestServer-mojmap/server.jar
rm -f test-harness/TestServer-mojmap/boot.log
cd test-harness/TestServer-mojmap && java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
PID=$!
cd - >/dev/null
until grep -q "Done (" test-harness/TestServer-mojmap/boot.log 2>/dev/null; do sleep 5; done
pkill -f "server.jar nogui"; sleep 3
```

Expected: `Done (` reached.

- [ ] **Step 6: Commit**

```bash
git add patches/minecraft/0050-*.patch
git commit -m "feat: perf-engine P2 — remove excess boats on collision"
```

---

## Task 8: Final verification + README touch-up

**Goal:** Run full clean build + operator boot test. Verify all 8 P2 knobs land in `Knobs.logLoaded()` line. Update README's perf-engine roadmap table to mark P2 as shipped.

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Clean rebuild from scratch**

```bash
./gradlew assembleReleaseArtifacts --no-configuration-cache 2>&1 | tail -5
ls -lh release/SourbyCraft-12-EXP.jar
```

Expected: BUILD SUCCESSFUL. Jar size approximately unchanged from pre-P2 (P2 adds ~5 small NMS patches + 8 knobs + 1 helper class, only kilobytes).

- [ ] **Step 2: Clean boot test (empty `libraries/`)**

```bash
cp release/SourbyCraft-12-EXP.jar test-harness/TestServer-mojmap/server.jar
rm -rf test-harness/TestServer-mojmap/libraries
rm -f test-harness/TestServer-mojmap/boot.log
cd test-harness/TestServer-mojmap && java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
PID=$!
cd - >/dev/null
until grep -q "Done (" test-harness/TestServer-mojmap/boot.log 2>/dev/null; do
    if ! kill -0 $PID 2>/dev/null; then echo DIED; tail -30 test-harness/TestServer-mojmap/boot.log; exit 1; fi
    sleep 5
done
```

- [ ] **Step 3: Verify all 8 P2 knobs in boot log**

```bash
grep "perf knobs loaded" test-harness/TestServer-mojmap/boot.log | grep -o "perf\\.lag-machine\\.[^ ]*" | wc -l
```

Expected: `8`.

```bash
grep "perf knobs loaded" test-harness/TestServer-mojmap/boot.log
```

Expected output contains: `perf.lag-machine.disable-saving-snowballs=true`, `perf.lag-machine.disable-saving-fireworks=true`, `perf.lag-machine.max-projectile-loads-per-tick=10`, `perf.lag-machine.max-projectile-loads-per-projectile=10`, `perf.lag-machine.remove-excess-minecarts=false`, `perf.lag-machine.excess-minecarts-limit=10`, `perf.lag-machine.remove-excess-boats=false`, `perf.lag-machine.excess-boats-limit=10`.

- [ ] **Step 4: Shutdown + verify 5 patches present**

```bash
pkill -f "server.jar nogui"; sleep 3
ls patches/minecraft/ | grep -c "perf-engine-P2"
```

Expected: `5`.

- [ ] **Step 5: Update README perf-engine roadmap table**

Open `README.md`. Locate the perf-engine roadmap table under `## Self-Tuning Perf Engine`. Change the P2 row from:

```
| **P2** Lag-Machine Protection | spec drafted | 8 NMS-gated knobs: snowball/firework save fixes, projectile chunk-load throttle, excess minecart/boat removal |
```

to:

```
| **P2** Lag-Machine Protection | ✓ shipped | 8 NMS-gated knobs: snowball/firework save fixes (default ON), projectile chunk-load throttle (10/tick, 10/projectile), excess minecart/boat removal (default OFF). 5 NMS patches. |
```

- [ ] **Step 6: Commit**

```bash
git add README.md
git commit -m "docs: perf-engine P2 — mark Lag-Machine Protection as shipped in roadmap"
```

---

## Final Verification (after all 8 tasks merged)

Spec Section 7 acceptance criteria walkthrough:

- [ ] **A1. 4 BoolKnobs declared**: `grep -c 'new BoolKnob("perf.lag-machine' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java` returns `4`.
- [ ] **A2. 4 IntKnobs declared**: `grep -c 'new IntKnob("perf.lag-machine' Knobs.java` returns `4`.
- [ ] **A3. yml has lag-machine section**: `grep -c '^  lag-machine:' sourbycraft-server/src/main/resources/sourbycraft.yml` returns `1`.
- [ ] **A4. yml has 8 lag-machine keys**: 8 keys present under that section.
- [ ] **A5. Operator-yml bridge wired**: `grep -c 'LAG_MACHINE_' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` returns ≥8.
- [ ] **A6. 5 P2 patches exist**: `ls patches/minecraft/ | grep -c 'perf-engine-P2'` returns ≥5.
- [ ] **A7. LagMachineCounters exists**: `ls sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/LagMachineCounters.java` shows file.
- [ ] **A8. Counter reset hook**: `grep -rn 'LagMachineCounters.resetTickCounters' patches/minecraft/` returns ≥1 hit.
- [ ] **A9. Build succeeds**: `./gradlew assembleReleaseArtifacts` BUILD SUCCESSFUL.
- [ ] **A10. Default boot reaches Done**: scenario in Task 8 Step 2.
- [ ] **A11. Knobs.logLoaded() shows 8 P2 keys**: scenario in Task 8 Step 3.
- [ ] **A12. No new JUnit**: `git diff <pre-P2-sha>..HEAD --stat sourbycraft-server/src/test/` empty.
- [ ] **A13. No new smoke harness**: `ls test-harness/scripts/ | grep lag-machine` empty.
- [ ] **A14. nms-compat CI passes**: triggered on PR open.

---

## Self-Review

**Spec coverage:**

- Spec §3 C1 (8 new Knobs) → Task 1 Step 1.
- Spec §3 C2 (yml schema) → Task 1 Step 2.
- Spec §3 C3 (operator-yml bridge) → Task 2 Step 2.
- Spec §3 C4 (LagMachineCounters) → Task 5 Step 1.
- Spec §3 C5 (Snowball save-skip patch) → Task 3.
- Spec §3 C6 (Firework save-skip patch) → Task 4.
- Spec §3 C7 (Projectile chunk-load throttle patch) → Task 5 Steps 3+4+5.
- Spec §3 C8 (Excess minecart removal patch) → Task 6.
- Spec §3 C9 (Excess boat removal patch) → Task 7.
- Spec §9 Phase 1 (Knobs + yml) → Task 1.
- Spec §9 Phase 2 (operator bridge) → Task 2.
- Spec §9 Phase 3 (Snowball + Firework) → Tasks 3 + 4.
- Spec §9 Phase 4 (Projectile throttle) → Task 5.
- Spec §9 Phase 5 (Minecart + Boat) → Tasks 6 + 7.
- Spec §9 Phase 6 (verification + docs) → Task 8.

**Placeholder scan:** Zero `TBD`/`TODO`/`implement later`. Two impl-time decisions documented (rebuild task name discovery in Task 3 Step 5; projectile chunk-load site in Task 5 Step 2). Both have explicit grep commands or fallback positions.

**Type consistency:**
- Knob names consistent: `LAG_MACHINE_DISABLE_SAVING_SNOWBALLS`, `LAG_MACHINE_DISABLE_SAVING_FIREWORKS`, `LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK`, `LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE`, `LAG_MACHINE_REMOVE_EXCESS_MINECARTS`, `LAG_MACHINE_EXCESS_MINECARTS_LIMIT`, `LAG_MACHINE_REMOVE_EXCESS_BOATS`, `LAG_MACHINE_EXCESS_BOATS_LIMIT` — same in Tasks 1, 2, 3, 4, 5, 6, 7.
- yml keys consistent: `perf.lag-machine.<name>` — same across Knobs.java + yml + SourbyCraftConfig.java + spec.
- `LagMachineCounters.resetTickCounters()` / `projectileChunkLoadsThisTick()` / `incrementProjectileChunkLoad()` — consistent in Task 5 helper + Task 5 patch + spec.
- `sourbyLoadsByProjectile` field name — consistent in Task 5 Step 3 + Step 4.
- Patch numbering: 0046 (snowball), 0047 (firework), 0048 (projectile), 0049 (minecart), 0050 (boat). Sequential, no gaps, all under `patches/minecraft/`.
