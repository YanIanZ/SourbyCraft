# Perf-engine P2 — Lag-Machine Protection (design)

**Date:** 2026-06-06
**Scope:** Sub-project P2 of the SourbyCraft self-tuning perf-engine roadmap. Builds on P0 (Knob Registry) + P1 (Load Sensor). Ships 5 NMS lag-machine protection patches gated by 8 new Knobs.
**Out-of-scope:** Tier-driven escalation (P7 controller wires that later), `/perf lag-machine` command, per-world overrides, hot-reload, existing static caps migration, behavior toggles, hopper protection, block-snapshot disabling. See Section 8.
**Status:** Draft for user review.

---

## 1. Background + Scope

P0 shipped the `Knobs` registry. P1 shipped the load sensor + tier classifier. P2 adds the first batch of NMS-level protections wired to Knobs — the lag-machine protection layer. P7 controller (future) will read `PerfSensor.currentTier()` and write to these knobs for tier-driven escalation.

```
P0 — Knob Registry API ✓
P1 — Load Sensor + Tier classifier ✓
P2 — Lag-Machine Protection batch                    ← this spec
P3 — Adaptive Entity AI
P4 — Combat Profiles
P5 — Async Chunk Pipeline
P6 — Async Packet + World subsystems
P7 — Self-Tune Controller (reads P1 tier, writes P0/P2/... knobs)
P8 — Operator UX + Telemetry
```

**Goal:** 5 lag-machine protection patches wired to Knobs. Static yml configuration (no tier-driven escalation in P2; P7 controller wires that later). Save-fixes ON by default, other protections OFF by default. Matches UniverseSpigot recommendations.

**In scope:**
- 8 new Knobs in `Knobs.java`:
  - `LAG_MACHINE_DISABLE_SAVING_SNOWBALLS` (BoolKnob, default **true**)
  - `LAG_MACHINE_DISABLE_SAVING_FIREWORKS` (BoolKnob, default **true**)
  - `LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK` (IntKnob, default 10, range 0..1000)
  - `LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE` (IntKnob, default 10, range 0..100)
  - `LAG_MACHINE_REMOVE_EXCESS_MINECARTS` (BoolKnob, default **false**)
  - `LAG_MACHINE_EXCESS_MINECARTS_LIMIT` (IntKnob, default 10, range 1..1000)
  - `LAG_MACHINE_REMOVE_EXCESS_BOATS` (BoolKnob, default **false**)
  - `LAG_MACHINE_EXCESS_BOATS_LIMIT` (IntKnob, default 10, range 1..1000)
- 5 new NMS patches (one per protection):
  - Snowball save-skip
  - Firework save-skip
  - Projectile chunk-load throttle (per-tick + per-projectile counters)
  - Excess minecart removal on collision
  - Excess boat removal on collision
- 1 new helper class `LagMachineCounters` for the per-tick projectile-load counter
- Append `perf.lag-machine:` block to `sourbycraft-server/src/main/resources/sourbycraft.yml`
- Operator yml override path via `cfgBool`/`cfgInt` helpers in `SourbyCraftConfig.init()` (existing P1 pattern)

**Out of scope:**
- Tier-driven escalation → P7 controller
- `/perf lag-machine` command → operator uses `Knobs.logLoaded()` boot line or `/perf` snapshot
- Per-world overrides → global only
- Hot-reload → restart required
- Existing static caps migration (`maxEntityPerChunk`, `maxArrowsPerWorld`, `maxFallingBlockPerChunk`, `maxRedstoneUpdatesPerTick`, `maxSpecialsPerChunk`) → stay as static `SourbyCraftConfig` fields; future cleanup sub-spec if wanted
- Behavior-toggles (block-event disabling, TNT cannon, sand-cannon) → separate sub-spec
- Block-snapshot disabling → separate sub-spec
- Spawner ticking cap → separate sub-spec
- Hopper protection/throttling → separate sub-spec
- Block/fluid ticking toggles → separate sub-spec
- Projectile entity-tracking-range adjustment → tracker concern, separate sub-spec
- TNT entity save skip → not recommended (TNT save is gameplay-relevant)

**Constraints inherited:**
- Single mojmap jar
- Hot-path safe: `BoolKnob.get()` and `IntKnob.get()` are volatile reads (~1ns)
- Bool-cache pattern (P0 Foundation): hot loops cache knob reads into a local at method entry
- Defaults preserve gameplay where possible
- No JUnit, no smoke harness (per `feedback-no-smoke-harness`)
- Verification: operator boots `test-harness/TestServer-mojmap/` and observes default behavior

## 2. Architecture

```
sourbycraft.yml (jar-baked, extends existing `perf:` section)
  └─ perf:
       lag-machine:
         disable-saving-snowballs: true
         disable-saving-fireworks: true
         max-projectile-loads-per-tick: 10
         max-projectile-loads-per-projectile: 10
         remove-excess-minecarts: false
         excess-minecarts-limit: 10
         remove-excess-boats: false
         excess-boats-limit: 10
        ↓
SourbyCraftConfig.init() (existing entry point)
  ├─ Knobs.loadFromYml()                              (P0; jar yml → knobs)
  ├─ ... Bukkit-config block (existing) ...
  ├─ PerfSensor.applyOperatorConfig(...)              (P1; operator yml → sensor)
  └─ NEW: 8 cfgBool/cfgInt → Knobs.LAG_MACHINE_*.set(...) (operator yml → knobs, P2 bridge)
        ↓
Knobs (existing P0 holder; +8 new static-final fields)
        ↓
NMS patches (5 new, one per protection)
  ├─ Snowball.shouldBeSaved          → gate on DISABLE_SAVING_SNOWBALLS
  ├─ FireworkRocketEntity.shouldBeSaved → gate on DISABLE_SAVING_FIREWORKS
  ├─ Projectile chunk-load site      → per-tick + per-projectile counter; discard on exceed
  ├─ AbstractMinecart.push           → AABB scan + discard on exceed if REMOVE_EXCESS_MINECARTS
  └─ AbstractBoat.push               → AABB scan + discard on exceed if REMOVE_EXCESS_BOATS
        ↓
LagMachineCounters (NEW helper in dev.iyanz.sourbycraft.perf)
  ├─ static int projectileChunkLoadsThisTick = 0
  ├─ static void resetTickCounters()        (called from MinecraftServer.tickChildren each tick)
  ├─ static int projectileChunkLoadsThisTick()
  └─ static void incrementProjectileChunkLoad()
```

**Invariants:**
- Each NMS patch wraps EXACTLY the vanilla call site with `if (Knobs.LAG_MACHINE_X.get()) { new-behavior; } else { /* vanilla */ }`.
- Trailing comment `// SourbyCraft - perf-engine P2` on every gated insertion for grep traceability.
- Bool-cache pattern (P0 Foundation): hot loops cache `Knobs.LAG_MACHINE_X.get()` into a local at method entry.
- All 8 knobs registered via static-final fields in `Knobs.java` — class init triggers `KnobRegistry.register(...)`.
- Defaults: 2 save-fixes ON (UniverseSpigot recommended), 2 excess-vehicle OFF (avoid surprise on minecart-art servers), 4 numeric defaults match UniverseSpigot.
- Operator override path mirrors P1 sensor's `applyOperatorConfig` pattern.

**Projectile chunk-load counter design:**
- Static `int projectileChunkLoadsThisTick` in `LagMachineCounters` class.
- Per-tick reset: one-line hook in `MinecraftServer.tickChildren` (mirror existing P1 sensor pattern, sibling line).
- Per-projectile counter: `int sourbyLoadsByProjectile` field added to projectile entity classes via NMS patch.
- Check sequence on chunk-load by projectile:
  ```
  if (knobPerTickMax.get() > 0 && counter.projectileChunkLoadsThisTick() >= knobPerTickMax.get()) {
      projectile.discard(); return;
  }
  if (knobPerProjectileMax.get() > 0 && projectile.sourbyLoadsByProjectile >= knobPerProjectileMax.get()) {
      projectile.discard(); return;
  }
  counter.incrementProjectileChunkLoad();
  projectile.sourbyLoadsByProjectile++;
  // vanilla chunk-load continues
  ```

**Excess minecart/boat design:**
- Patch `AbstractMinecart.push(Entity other)` and `AbstractBoat.push(Entity other)` (or equivalent collision callbacks).
- On collision: scan AABB inflated 2 blocks, count nearby same-class entities. If `count > limit` AND `removeExcess == true`: `this.discard(); return`.
- Bool-cache at method entry.

**Concurrency:**
- All knob writes from main thread (init + future P7 controller).
- All knob reads from main-thread hot paths.
- `LagMachineCounters` fields written from main-thread only (tickChildren + projectile move).
- `sourbyLoadsByProjectile` per-entity field — single-threaded entity tick.
- No locks anywhere on hot path.

## 3. Components

### C1. `Knobs.java` — 8 new static-final fields

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java` (edit existing)

Append after existing `ENTITY_TICK_RATE` declaration:

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

### C2. `sourbycraft.yml` schema growth

**File:** `sourbycraft-server/src/main/resources/sourbycraft.yml` (edit existing `perf:` section)

Append under the existing `perf:` block, after the `sensor:` sub-section:

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

### C3. `SourbyCraftConfig.init()` operator-yml bridge

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` (edit existing `init()`)

After the existing P1 `PerfSensor.applyOperatorConfig(...)` block, add:

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

### C4. `LagMachineCounters` helper

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/LagMachineCounters.java` (new)

```java
package dev.iyanz.sourbycraft.perf;

/**
 * Per-tick projectile-chunk-load counter. Reset at tick start by a MinecraftServer.tickChildren hook.
 * Main-thread access only — no synchronization needed.
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

### C5. NMS patch — disable Snowball NBT save

**File:** `patches/minecraft/00NN-perf-engine-P2-disable-saving-snowballs.patch` (NN = next free under `patches/minecraft/`; check at plan-time)

Target: `net/minecraft/world/entity/projectile/Snowball.java` (mojmap class name).

Insert an override of `shouldBeSaved()`:

```java
@Override
public boolean shouldBeSaved() {
    // SourbyCraft start - perf-engine P2 lag-machine: skip NBT save for snowballs
    if (dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_DISABLE_SAVING_SNOWBALLS.get()) {
        return false;
    }
    // SourbyCraft end - perf-engine P2
    return super.shouldBeSaved();
}
```

If `Snowball` does not override `shouldBeSaved()` in vanilla, this adds the override. If it does, this prepends the gate.

### C6. NMS patch — disable Firework NBT save

**File:** `patches/minecraft/00NN+1-perf-engine-P2-disable-saving-fireworks.patch`

Same shape as C5, target `FireworkRocketEntity.java`:

```java
@Override
public boolean shouldBeSaved() {
    // SourbyCraft start - perf-engine P2 lag-machine: skip NBT save for fireworks
    if (dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_DISABLE_SAVING_FIREWORKS.get()) {
        return false;
    }
    // SourbyCraft end - perf-engine P2
    return super.shouldBeSaved();
}
```

### C7. NMS patch — Projectile chunk-load throttle

**File:** `patches/minecraft/00NN+2-perf-engine-P2-projectile-chunkload-limiter.patch`

Two parts in one patch (or split if paperweight groups by file):

(a) Add `public int sourbyLoadsByProjectile = 0;` field to the appropriate vanilla class. Likely `Projectile.java` (mojmap). Plan time decision based on actual vanilla source.

(b) Tick-reset hook in `MinecraftServer.tickChildren`, sibling to existing P1 sensor hook:

```java
// SourbyCraft start - perf-engine P2 lag-machine: reset projectile-load tick counter
try { dev.iyanz.sourbycraft.perf.LagMachineCounters.resetTickCounters(); }
catch (Throwable t) { /* never fail tickChildren */ }
// SourbyCraft end - perf-engine P2
```

(c) Wrap the projectile chunk-load trigger site with the two-counter check:

```java
// at projectile's chunk-load entry (e.g. inside Projectile.tick or wherever the entity
// crosses a chunk boundary and triggers a chunk-load):
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
// SourbyCraft - perf-engine P2 lag-machine projectile-loads
// vanilla chunk-load continues
```

Implementer locates the EXACT vanilla projectile chunk-load site at plan time. Fallback target: anywhere `level.getChunk(...)` is called from a `Projectile` context.

### C8. NMS patch — Remove excess minecarts on collision

**File:** `patches/minecraft/00NN+3-perf-engine-P2-remove-excess-minecarts.patch`

Target: `AbstractMinecart.push(Entity)` or vanilla collision callback. Wrap with knob-gated AABB scan + discard:

```java
public void push(Entity other) {
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
    // SourbyCraft end - perf-engine P2
    // ... vanilla collision logic ...
}
```

Bool-cache the knob read at method entry of the collision handler if hot.

### C9. NMS patch — Remove excess boats on collision

**File:** `patches/minecraft/00NN+4-perf-engine-P2-remove-excess-boats.patch`

Same shape as C8, target `AbstractBoat.push(Entity)` or `Boat.push(Entity)`:

```java
public void push(Entity other) {
    // SourbyCraft start - perf-engine P2 lag-machine: remove excess boats on collision
    if (dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.get()) {
        final int limit = dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.get();
        if (limit > 0) {
            net.minecraft.world.phys.AABB area = this.getBoundingBox().inflate(2.0);
            java.util.List<? extends Boat> nearby =
                this.level().getEntitiesOfClass(Boat.class, area);
            if (nearby.size() > limit) {
                this.discard();
                return;
            }
        }
    }
    // SourbyCraft end - perf-engine P2
    // ... vanilla collision logic ...
}
```

### C10. Patch numbering

Next free under `patches/minecraft/`: check `ls patches/minecraft/ | sort | tail -3` at plan time. Expected free range: `0046` and up (last NMS patch was `0045-...sensor-tick-hook.patch` for P1, and Bootstrap added a `patches/server/0034-...lazy-speedtest...patch` recently).

## 4. Data flow

```
boot:
  CraftServer.enable()
    └─ SourbyCraftConfig.init()
         ├─ Knobs.loadFromYml()                              (P0)
         ├─ try { PerfSensor.loadFromYml() }                 (P1)
         ├─ ... Bukkit-config block (existing) ...
         ├─ PerfSensor.applyOperatorConfig(...)              (P1 — operator yml → sensor)
         ├─ NEW: P2 operator-yml bridge                       (operator yml → 8 LAG_MACHINE_* knobs)
         └─ Knobs.logLoaded()                                 (P0 — final knob snapshot)

per server tick (main thread):
  MinecraftServer.tickChildren
    ├─ NEW: LagMachineCounters.resetTickCounters()           (P2)
    ├─ BossBarTicker.tick()                                  (existing)
    ├─ PerfSensor.tick(server)                               (P1)
    └─ existing tickChildren continues

snowball/firework save:
  Entity.shouldBeSaved()  (Snowball / FireworkRocketEntity)
    ├─ if Knobs.LAG_MACHINE_DISABLE_SAVING_*.get(): return false  (skip NBT save)
    └─ else: return super.shouldBeSaved() (vanilla)

projectile chunk-load:
  Projectile.{move/load entry}
    ├─ perTickMax  = Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.get()
    ├─ perProjMax  = Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.get()
    ├─ if perTickMax > 0 && counter.projectileChunkLoadsThisTick() >= perTickMax: discard, return
    ├─ if perProjMax > 0 && this.sourbyLoadsByProjectile >= perProjMax: discard, return
    ├─ counter.incrementProjectileChunkLoad()
    ├─ this.sourbyLoadsByProjectile++
    └─ vanilla chunk-load proceeds

minecart/boat collision:
  push(Entity)
    ├─ if Knobs.LAG_MACHINE_REMOVE_EXCESS_*.get():
    │    └─ limit = Knobs.LAG_MACHINE_EXCESS_*_LIMIT.get()
    │    └─ if limit > 0:
    │         ├─ AABB area = this.getBoundingBox().inflate(2.0)
    │         ├─ nearby = level().getEntitiesOfClass(<EntityType>.class, area)
    │         └─ if nearby.size() > limit: this.discard(); return
    └─ vanilla collision logic
```

### Hot-path cost analysis

| Operation | Cost | Notes |
|---|---|---|
| `BoolKnob.get()` | ~1ns | volatile boolean read |
| `IntKnob.get()` | ~1ns | volatile int read |
| `LagMachineCounters.resetTickCounters()` | ~5ns | 1 field write per tick (1× per 50ms) |
| `LagMachineCounters.incrementProjectileChunkLoad()` | ~5ns | 1 field increment per projectile chunk load |
| `Snowball.shouldBeSaved()` | ~1ns | 1 volatile read, 1 branch |
| `FireworkRocketEntity.shouldBeSaved()` | ~1ns | same |
| Projectile chunk-load check | ~10ns | 2 volatile reads, 2 branches, 1-2 increments |
| Minecart/Boat collision check | ~5µs when knob ON, ~1ns when OFF | bool-check fast-path; AABB scan only when enabled |

### Concurrency model

- Main thread only on knob writes (init + future P7 controller).
- Main thread only on knob reads (entity save, projectile move, collision).
- `LagMachineCounters` static fields: main-thread reset (`tickChildren`) + main-thread increment (projectile move). No cross-thread.
- `sourbyLoadsByProjectile` per-entity field: single-threaded entity tick.
- No locks.

### Bool-cache pattern (P0 Foundation)

Hot loops cache knob reads into a local at method entry:

```java
public void push(Entity other) {
    final boolean removeExcess = Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.get();  // SourbyCraft - perf-engine P2
    final int limit = removeExcess ? Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.get() : 0;
    // ... use locals ...
}
```

### Reload

Not supported. Restart required for yml changes. yml header documents this.

## 5. Error handling

| Case | Behavior |
|---|---|
| Missing `perf.lag-machine.*` yml block | All 8 knobs load with their constructor defaults. Silent. |
| Missing single key | Knob loads with its constructor default. Silent. |
| Wrong type for key | Existing `ymlInt`/`ymlBool` (jar) or `cfgInt`/`cfgBool` (operator) returns default + emits one WARN via existing dedupe. Knob receives default. |
| Out-of-range int from yml | `IntKnob.set` clamps to range + emits `KnobRegistry.warnOnce` WARN once per (knob,direction). |
| `excess-minecarts-limit: 0` or negative | `IntKnob.set` clamps to min=1 + WARN once. Vehicle never triggers excess-removal at limit=1 unless 2+ vehicles collide. |
| `disable-saving-snowballs: "yes"` (string) | `cfgBool` returns default, WARN. Knob unchanged. |
| Snowball/Firework save site invoked from non-main thread (shouldn't happen) | `BoolKnob.get()` is thread-safe volatile read. Returns last visible value. No crash. |
| `LagMachineCounters.resetTickCounters()` throws | Wrapped in `try { ... } catch (Throwable t) { /* never fail tickChildren */ }` at the tick-hook site. Counter retains stale value (worst case: extra projectile discards). |
| `level().getEntitiesOfClass()` returns null (defensive) | Treat as empty list, no entities discarded. |
| Vehicle collision `discard()` called twice on same entity | `Entity.discard()` is idempotent. Safe. |
| Knob value changed mid-tick | Hot paths cache via bool-cache pattern; mid-tick change applies next tick. Acceptable for lag-machine context. |
| Projectile entity removed while in chunk-load path | Vanilla handles already-discarded entities. Our `this.discard()` followed by `return` exits cleanly. |
| Knob hot-reload (not supported) | yml header documents "Changes require server restart." |

**No new exception types.** All logging via existing `SourbyLogger.warn(String)` single-arg concat.

**Bool-cache invariant:** patches MUST cache bool/int knob reads into a local at hot-method entry. Patch template comment `// SourbyCraft - perf-engine P2` flags every gated insertion. Code review checks the cache is in scope of the loop, not inside.

**No partial-apply:** If a single knob's operator-yml override throws, the entire P2 operator-yml bridge `try { ... } catch (Throwable)` block logs error and the other 7 knobs retain their jar-yml defaults. Acceptable degradation; never blocks boot.

## 6. Testing

Per `feedback-no-smoke-harness` memory: NO automated test surface. NO JUnit. NO bash smoke harness. Verification is operator-driven.

| Check | How |
|---|---|
| 8 knobs registered | `unzip -p release/SourbyCraft-12-REL.jar dev/iyanz/sourbycraft/perf/knob/Knobs.class` then `javap -p` shows 8 new `LAG_MACHINE_*` static-final fields |
| yml has lag-machine section | `grep -c '^  lag-machine:' sourbycraft-server/src/main/resources/sourbycraft.yml` returns 1 |
| Knobs.logLoaded() shows P2 keys at boot | Boot, observe `perf knobs loaded [boot]:` line contains 8 `perf.lag-machine.*=*` entries |
| 5 NMS patches applied | `ls patches/minecraft/ \| grep -c perf-engine-P2` returns ≥5 |
| Snowball NBT skip works | Spawn 1000 snowballs, save world, unload+reload chunk → gone (knob ON) vs persist (knob OFF) |
| Firework NBT skip works | Same with firework rockets |
| Projectile chunk-load throttle | Build projectile load-bomb, observe discards after threshold |
| Excess minecart removal | Stack 15 minecarts with limit=10 + remove=true, observe 5 discards |
| Excess boat removal | Same with boats |
| Default boot unchanged | Boot with default yml; vanilla behavior, save-fixes silently apply (zero gameplay impact) |

**Inherited CI gates stay:**
- `.github/workflows/nms-compat.yml` runs `nmsCompatTest` + `particleSmokeTest` + `p0KnobSmokeTest` against the release jar. Slim jar must still pass.
- No new CI step added.

**No JUnit added. No bash smoke harness added.**

## 7. Acceptance criteria

| # | Check | Command | Expected |
|---|---|---|---|
| 1 | 4 BoolKnobs declared | `grep -c 'new BoolKnob("perf.lag-machine' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java` | `4` |
| 2 | 4 IntKnobs declared | `grep -c 'new IntKnob("perf.lag-machine' Knobs.java` | `4` |
| 3 | yml has lag-machine section | `grep -c '^  lag-machine:' sourbycraft-server/src/main/resources/sourbycraft.yml` | `1` |
| 4 | yml has 8 lag-machine keys | `grep -cE '^    (disable-saving\|max-projectile\|remove-excess\|excess-(minecarts\|boats)-limit)' sourbycraft.yml` | `8` |
| 5 | Operator-yml bridge wired | `grep -c 'LAG_MACHINE_' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` | `≥8` |
| 6 | 5 P2 patches exist | `ls patches/minecraft/ patches/server/ 2>/dev/null \| grep -c 'perf-engine-P2'` | `≥5` |
| 7 | `LagMachineCounters` exists | `ls sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/LagMachineCounters.java` | file exists |
| 8 | Counter reset hook in tickChildren | `grep -rn 'LagMachineCounters.resetTickCounters' patches/minecraft/ \| grep -v build/` | ≥1 hit |
| 9 | Build succeeds | `./gradlew assembleReleaseArtifacts` | BUILD SUCCESSFUL |
| 10 | Default boot reaches Done | Boot `test-harness/TestServer-mojmap/` with default yml | `Done (` ≤90s |
| 11 | Knobs.logLoaded() shows 8 P2 keys | After boot, `grep "perf knobs loaded" boot.log` | line contains all 8 `perf.lag-machine.*=*` entries |
| 12 | No new JUnit | `git diff <pre-P2-sha>..HEAD --stat sourbycraft-server/src/test/` | empty |
| 13 | No new smoke harness | `ls test-harness/scripts/ \| grep lag-machine` | empty |
| 14 | Existing nms-compat CI passes | `.github/workflows/nms-compat.yml` against the slim jar | green |
| 15 | Manual: snowball NBT skip | Operator stages 100 snowballs + chunk reload | snowballs disappear |
| 16 | Manual: projectile load throttle | Operator builds load-bomb | projectiles discard after threshold |

## 8. Out of scope

P2 explicitly does NOT cover:

1. **Tier-driven escalation** — P7 controller wires sensor tier → knob writes.
2. **`/perf lag-machine` command** — operator reads via `Knobs.logLoaded()` boot line or `/perf` snapshot.
3. **Per-world overrides** — global only.
4. **Hot-reload of yml** — restart required.
5. **Existing static caps migration** (`maxEntityPerChunk`, `maxArrowsPerWorld`, `maxFallingBlockPerChunk`, `maxRedstoneUpdatesPerTick`, `maxSpecialsPerChunk`) — stay as static `SourbyCraftConfig` fields.
6. **Behavior toggles** (block-event disabling, TNT cannon, sand-cannon) — separate sub-spec.
7. **Block-snapshot disabling** — separate sub-spec.
8. **Spawner ticking cap** — separate sub-spec.
9. **Hopper protection / throttling** — separate sub-spec.
10. **Block/fluid ticking toggles** — separate sub-spec.
11. **Projectile entity-tracking-range adjustment** — tracker concern, separate sub-spec.
12. **TNT entity save skip** — not recommended (TNT save is gameplay-relevant).
13. **Multi-OS support for any of this** — server-side NMS only.

## 9. Phases (handed to writing-plans)

Suggested phase breakdown. `writing-plans` owns detailed step decomposition.

### Phase 1 — Knobs.java additions + yml schema growth
- Add 8 new `LAG_MACHINE_*` fields to `Knobs.java`.
- Append `lag-machine:` block to `sourbycraft.yml`.
- Compile gate; no NMS patch yet.
- One commit: `feat: perf-engine P2 — Knobs + yml schema for 8 lag-machine toggles`.

### Phase 2 — SourbyCraftConfig operator-yml bridge
- Add P2 bridge block to `init()` (8 `cfgBool`/`cfgInt` → `Knob.set(...)`, wrapped try/catch).
- Boot test: verify `Knobs.logLoaded()` line contains all 8 P2 entries.
- One commit: `feat: perf-engine P2 — operator sourbycraft.yml bridge for lag-machine knobs`.

### Phase 3 — Snowball + Firework save-skip patches
- Identify exact vanilla `shouldBeSaved()` site on `Snowball.java` and `FireworkRocketEntity.java`.
- Apply two NMS patches via paperweight rebuild.
- Boot test: spawn snowballs/fireworks with knob ON, chunk reload, observe entities gone.
- Two commits (one per protection):
  - `feat: perf-engine P2 — disable saving snowballs (lag-machine save vector)`
  - `feat: perf-engine P2 — disable saving fireworks (lag-machine save vector)`

### Phase 4 — Projectile chunk-load throttle
- Create `LagMachineCounters.java` in `dev.iyanz.sourbycraft.perf`.
- Add tick-reset hook patch in `MinecraftServer.tickChildren` (sibling to existing P1 sensor hook).
- Add `sourbyLoadsByProjectile` int field to vanilla Projectile class via NMS patch.
- Wrap projectile chunk-load site with the two-counter check.
- Boot test: build projectile load-bomb, observe discards after threshold.
- One commit: `feat: perf-engine P2 — projectile chunk-load throttle (lag-machine load vector)`.

### Phase 5 — Excess minecart + boat removal
- Patch `AbstractMinecart.push(Entity)` collision handler.
- Patch `AbstractBoat.push(Entity)` collision handler.
- Boot test: stack vehicles with knob ON + low limit, observe discards.
- Two commits:
  - `feat: perf-engine P2 — remove excess minecarts on collision`
  - `feat: perf-engine P2 — remove excess boats on collision`

### Phase 6 — Verification + docs
- Run full clean build + operator boot test.
- Verify all 8 knobs in `Knobs.logLoaded()` line.
- Update operator docs if needed (likely brief README note linking to new yml section).
- One commit (only if docs touched): `docs: perf-engine P2 — lag-machine yml keys`.
