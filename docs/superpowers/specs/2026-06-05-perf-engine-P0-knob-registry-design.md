# Perf-engine P0 — Knob Registry (design)

**Date**: 2026-06-05
**Scope**: Sub-project P0 of the SourbyCraft self-tuning perf-engine roadmap (9 sub-projects total). Ships the foundation API that P1–P8 consume to discover, read, and write performance knobs uniformly.
**Out-of-scope**: Sub-projects P1–P8 (each its own spec → plan → impl cycle). See Section 8.
**Status**: Draft for user review.

---

## 1. Background + Scope

SourbyCraft tracks a self-tuning performance engine as a mega-project decomposed into 9 sub-projects:

```
P0 — Knob Registry API                                  ← this spec
P1 — Load Sensor + Tier classifier (TPS/MSPT/GC/mem → GREEN/YELLOW/ORANGE/RED)
P2 — Lag-Machine Protection batch
P3 — Adaptive Entity AI (tier-aware DAB, dynamic-brain)
P4 — Combat Profiles (1.21-vanilla / 1.8-pvp / custom)
P5 — Async Chunk Pipeline
P6 — Async Packet + World subsystems
P7 — Self-Tune Controller (orchestrator)
P8 — Operator UX + Telemetry
```

P0 is the foundation — every other sub-project depends on its knob abstraction. Ship order:
`P0 → P1 → P2 → P3 → P7-skeleton → P4 → P5 → P6 → P7-full → P8`.

**Goal**: ship the foundation API that all later sub-projects consume to discover, read, and write performance knobs in a uniform way.

**In scope:**
- `PerfKnob` sealed abstraction + `BoolKnob` + `IntKnob` concrete classes.
- `Knobs` static holder class — declaration site for all knobs across the codebase.
- `KnobRegistry` package-private store + immutable snapshot API.
- yml load path: bind `Knobs.*` to values from `sourbycraft.yml`.
- One reference knob migration: `entityTickRate` (currently `SourbyCraftConfig.entityTickRate` static int).
- Boot-smoke verification via `test-harness/TestServer-mojmap/`.

**Out of scope** (deferred to their owning sub-spec):
- Load sensor / tier classifier → P1.
- Lag-machine protection toggles → P2.
- Adaptive AI knobs → P3.
- Combat profiles → P4.
- Async chunk/packet → P5/P6.
- Self-tune controller (the brain) → P7.
- Operator UX + telemetry → P8.
- Listener bus / change history → P7 adds when needed.
- `DoubleKnob` / `EnumKnob` / `ListKnob` → added when first consumer appears (likely P3/P4).
- Per-world overrides → deferred to whichever sub-spec needs it.

**Constraints inherited from the roadmap and the UniverseSpigot Foundation spec:**
- Single mojmap jar (no module split).
- Hot-path safe: getter must inline to a volatile-int / volatile-bool read (~1ns).
- Defaults match Paper vanilla — operator opt-in only.
- Reuse existing `SourbyLogger.LOGGER` + WARN-dedupe pattern.
- No new NMS shim API.

## 2. Architecture

```
sourbycraft.yml (operator file at plugins/SourbyCraft/sourbycraft.yml)
  ├─ existing sections: pvp/network/entity-tracker/combat/branding/auto-install/particles/sounds
  └─ NEW section: perf (added by this spec; ONE key in P0)
        ↓
SourbyCraftConfig.load() (existing entry point)
  └─ at end of existing yml-load: call Knobs.loadFromYml(this)   ← NEW
        ↓
dev.iyanz.sourbycraft.perf.knob   ← NEW package
  ├─ PerfKnob (sealed abstract)
  ├─ BoolKnob extends PerfKnob
  ├─ IntKnob  extends PerfKnob
  ├─ KnobRegistry (package-private; static map + WARNED set)
  └─ Knobs (public; static holder — Knobs.ENTITY_TICK_RATE etc.)
        ↓
SourbyCraftConfig.entityTickRate (existing compat surface)
  └─ becomes: `public static int entityTickRate() { return Knobs.ENTITY_TICK_RATE.get(); }`
  (field declaration replaced by static method; ~5–10 caller sites updated)
        ↓
DynamicPerformanceScaler (existing)
  └─ writes: `Knobs.ENTITY_TICK_RATE.set(rate)` (replaces direct field write)
```

**Invariants:**
- Knob value lives in the `PerfKnob` instance (volatile field). No other store.
- Compile-time refs via `Knobs.ENTITY_TICK_RATE` — IDE jump-to-definition works, no string lookups in hot paths.
- Knob declarations are pure data — `new IntKnob(...)` registers itself in its constructor. Class init of `Knobs` populates the registry.
- yml load is one-shot at boot. No hot-reload in P0.
- Hot-path getter: `Knobs.ENTITY_TICK_RATE.get()` → returns `this.value` (volatile read). JIT inlines.
- All writes go through `set()` → clamp → assign. No public mutable field.
- `SourbyCraftConfig.entityTickRate` stays as compat readable surface but is now a method, not a field — callers update to the `()` form.

## 3. Components

### C1. `PerfKnob` sealed abstract

**File**: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/PerfKnob.java` (new)

```java
public sealed abstract class PerfKnob permits BoolKnob, IntKnob {
    protected final String key;          // e.g. "perf.entity-tick-rate"
    protected PerfKnob(String key) { this.key = key; KnobRegistry.register(this); }
    public final String key() { return key; }
    public abstract Object snapshot();   // boxed value for snapshot map
    abstract void loadFrom(SourbyCraftConfig cfg);   // package-private; binds from yml
}
```

### C2. `BoolKnob`

**File**: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/BoolKnob.java` (new)

```java
public final class BoolKnob extends PerfKnob {
    private final boolean defaultValue;
    private volatile boolean value;
    public BoolKnob(String key, boolean defaultValue) {
        super(key); this.defaultValue = defaultValue; this.value = defaultValue;
    }
    public boolean get() { return value; }
    public void set(boolean v) { this.value = v; }   // no clamp for bool
    @Override public Object snapshot() { return value; }
    @Override void loadFrom(SourbyCraftConfig cfg) {
        this.value = SourbyCraftConfig.ymlBool(key, defaultValue);
    }
}
```

### C3. `IntKnob` (clamp + WARN-once)

**File**: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/IntKnob.java` (new)

```java
public final class IntKnob extends PerfKnob {
    private final int defaultValue, min, max;
    private volatile int value;
    public IntKnob(String key, int defaultValue, int min, int max) {
        super(key);
        if (min > max) throw new IllegalArgumentException("min > max for " + key);
        this.defaultValue = clamp(defaultValue, min, max);
        this.min = min; this.max = max; this.value = this.defaultValue;
    }
    public int get() { return value; }
    public void set(int v) {
        int clamped = clamp(v, min, max);
        if (clamped != v) KnobRegistry.warnOnce(key, v, clamped);
        this.value = clamped;
    }
    public int min() { return min; } public int max() { return max; }
    @Override public Object snapshot() { return value; }
    @Override void loadFrom(SourbyCraftConfig cfg) {
        set(SourbyCraftConfig.ymlInt(key, defaultValue));
    }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
```

### C4. `KnobRegistry` (package-private)

**File**: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/KnobRegistry.java` (new)

```java
final class KnobRegistry {
    private static final Map<String, PerfKnob> KNOBS = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    static void register(PerfKnob k) {
        if (KNOBS.putIfAbsent(k.key(), k) != null)
            throw new IllegalStateException("duplicate knob key: " + k.key());
    }

    static void warnOnce(String key, int requested, int clamped) {
        String dedupeKey = key + ":" + (requested < clamped ? "lo" : "hi");
        if (WARNED.add(dedupeKey))
            SourbyLogger.LOGGER.warn(
                "[SourbyCraft] knob '{}' value {} clamped to {}", key, requested, clamped);
    }

    static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        KNOBS.forEach((k, knob) -> out.put(k, knob.snapshot()));
        return Collections.unmodifiableMap(out);
    }

    static void loadAllFromYml(SourbyCraftConfig cfg) {
        for (PerfKnob k : KNOBS.values()) k.loadFrom(cfg);
    }
}
```

### C5. `Knobs` public holder

**File**: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java` (new)

End-state shown below. Phase 1 lands the holder with NO knob fields (just the static methods); Phase 2 adds the `ENTITY_TICK_RATE` declaration. The split is to keep each commit small and CI-green.

```java
public final class Knobs {
    private Knobs() {}

    // Reference knob — migrated from SourbyCraftConfig.entityTickRate
    public static final IntKnob ENTITY_TICK_RATE =
        new IntKnob("perf.entity-tick-rate", 1, 1, 20);

    public static Map<String, Object> snapshot() { return KnobRegistry.snapshot(); }
    public static void loadFromYml(SourbyCraftConfig cfg) {
        KnobRegistry.loadAllFromYml(cfg);
    }
}
```

### C6. `SourbyCraftConfig` integration

**File**: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` (edit)

- Replace the existing `public static int entityTickRate = 1;` field declaration with:
  ```java
  public static int entityTickRate() { return Knobs.ENTITY_TICK_RATE.get(); }
  ```
- Inside the existing `load()` method, after the yml load completes, append:
  ```java
  Knobs.loadFromYml(this);
  ```
- All `SourbyCraftConfig.entityTickRate` read-site callers update from field access to method call (5–10 sites — `grep -rn 'SourbyCraftConfig\.entityTickRate[^(]'` enumerates them).

### C7. `DynamicPerformanceScaler` write migration

**File**: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/DynamicPerformanceScaler.java` (edit)

- Change `SourbyCraftConfig.entityTickRate = rate;` → `Knobs.ENTITY_TICK_RATE.set(rate);`
- Change in-file reads of `SourbyCraftConfig.entityTickRate` → `Knobs.ENTITY_TICK_RATE.get()` (avoids the compat-method double-hop within the scaler itself).

### C8. `sourbycraft.yml` schema growth

**File**: `sourbycraft-server/src/main/resources/sourbycraft.yml` (edit)

Append:

```yaml
perf:
  # Entity tick rate (1=every tick, higher=skip ticks). Range 1..20.
  # Auto-tuned by DynamicPerformanceScaler when enabled.
  entity-tick-rate: 1
```

Each key comment includes (a) one-line description, (b) range, (c) auto-tune note when applicable.

### C9. Boot smoke test (replaces JUnit)

**Files**: `test-harness/scripts/p0-knob-smoke.sh` (new), `sourbycraft-server/build.gradle.kts` (edit — add gradle task).

Workflow:

1. Gradle task `p0KnobSmokeTest` (only registered when `-PrunP0KnobSmoke=true`) → invokes `test-harness/scripts/p0-knob-smoke.sh`.
2. Script reuses existing `boot-mojmap.sh` infrastructure (copies release jar, sets eula, seeds `server.properties`).
3. For each of these scenarios, write a temp `test-harness/TestServer-mojmap/plugins/SourbyCraft/sourbycraft.yml` containing only the relevant `perf` block, boot, wait for `Done (`, grep `boot.log`, kill:

| Scenario | yml setting | Assertion |
|---|---|---|
| Default (key missing) | `perf:` block omitted | boot reaches `Done (`; `/perf` cmd via RCON shows `rate: 1` |
| In-range value | `perf.entity-tick-rate: 4` | boot reaches `Done (`; `/perf` shows `rate: 4`; no clamp WARN in boot.log |
| Above max → clamp hi | `perf.entity-tick-rate: 99` | boot reaches `Done (`; `/perf` shows `rate: 20`; boot.log contains `knob 'perf.entity-tick-rate' value 99 clamped to 20` |
| Below min → clamp lo | `perf.entity-tick-rate: 0` | boot reaches `Done (`; `/perf` shows `rate: 1`; boot.log contains `knob 'perf.entity-tick-rate' value 0 clamped to 1` |
| Wrong type | `perf.entity-tick-rate: "high"` | boot reaches `Done (`; `/perf` shows `rate: 1`; boot.log contains existing `ymlInt` type-mismatch WARN |

4. RCON access enabled in `server.properties` (`enable-rcon=true`, `rcon.password=p0test`, `rcon.port=25675`). Shell RCON helper picked at plan time (likely `mcrcon` — installed manually or via plan step).
5. Boot timeout 90s per scenario (matches existing harness).
6. Script exits non-zero on first failing scenario; prints the offending scenario name + last 50 lines of `boot.log`.
7. CI gate: extend `.github/workflows/` matrix to add `runP0KnobSmoke=true` on PRs touching `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/**` or the `sourbycraft.yml` perf section.

No JUnit added in P0. Existing 28 SourbyCraft JUnit tests stay untouched. Paper upstream 169 tests untouched.

## 4. Data flow

```
boot:
  CraftServer.enable()
    └─ existing SourbyCraftConfig.load() reads sourbycraft.yml (Bukkit YAML; unchanged)
    └─ NEW at end of load(): Knobs.loadFromYml(this)
         └─ KnobRegistry.loadAllFromYml(this)
            └─ for each PerfKnob k: k.loadFrom(this)
               └─ IntKnob.loadFrom → SourbyCraftConfig.ymlInt(key, default) → set(clamped)
               └─ BoolKnob.loadFrom → SourbyCraftConfig.ymlBool(key, default) → assign

read path (hot — e.g. entity tick loop):
  Knobs.ENTITY_TICK_RATE.get()
    └─ return this.value                    ← volatile int load (~1ns, JIT inlines)

write path (cold — controller, /perf cmd):
  Knobs.ENTITY_TICK_RATE.set(rate)
    └─ clamp(rate, min, max)
    └─ if clamped != requested: warnOnce(key, requested, clamped)
    └─ this.value = clamped                 ← volatile int store (~5ns)
```

### Hot-path cost analysis

| Operation | Cost | Notes |
|---|---|---|
| `IntKnob.get()` | ~1ns on x86-64 | volatile int read; HotSpot inlines the `get()` call through the `public static final` Knobs holder, volatile load remains as a single MOV (no fence on x86-64; LDAR on aarch64) |
| `BoolKnob.get()` | ~1ns on x86-64 | same — volatile boolean load |
| `IntKnob.set(v)` | ~10ns | clamp branch + volatile store; cold path |
| `Knobs.snapshot()` | ~5µs | iterates ConcurrentHashMap, boxes ints/bools; only called by `/perf` cmd renderer |
| `Knobs.loadFromYml()` | one-shot at boot | not a hot path |

### Bool-cache pattern

Existing US Foundation patch template (`docs/superpowers/notes/2026-06-04-us-gated-patch-template.md`) requires patch authors to cache yml lookups into a local at method entry for hot loops. Same applies to `Knobs.*.get()` calls inside per-entity-per-tick loops:

```java
// hot path:
public void tickEntities() {
    final int rate = Knobs.ENTITY_TICK_RATE.get();   // SourbyCraft - perf-engine P0
    for (Entity e : entities) {
        if (server.getTickCount() % rate != 0) continue;
        e.tick();
    }
}
```

`Knobs.ENTITY_TICK_RATE` is `public static final` referencing a `final` `IntKnob`. HotSpot inlines the `.get()` call so only the volatile field load remains (single MOV on x86-64). Bool-cache is still recommended belt-and-suspenders for very tight loops — it pulls the load out of the loop entirely and lets the value live in a register.

### Concurrency model

- All knob writes from the main thread, the `DynamicPerformanceScaler` tick (also main thread), and any future controller (P7, may run on scheduler thread).
- `volatile` field on each knob: writes publish, reads see latest. Int/bool writes are atomic on the JVM — no torn reads.
- `KnobRegistry.KNOBS` is a `ConcurrentHashMap` — concurrent reads safe; registrations only happen at class-init (single-threaded by JVM).
- `KnobRegistry.WARNED` is a `ConcurrentHashMap.newKeySet()` — dedupe safe across threads.
- No locks anywhere on the hot path.

### Reload

Not supported in P0. yml header documents: "Changes require server restart." Future sub-spec owns hot-reload if it materializes.

## 5. Error handling

| Case | Behavior |
|---|---|
| Missing `sourbycraft.yml` file | Existing bundled-resource copy path applies (no change). |
| Malformed YAML | Existing SnakeYAML error handler logs + falls back to bundled defaults (no change). |
| Missing `perf` block in yml | All knobs load with their constructor default. Silent. |
| Missing single key (e.g. `perf.entity-tick-rate`) | Knob loads with its constructor default. Silent. |
| Wrong type for key (`perf.entity-tick-rate: "high"`) | Existing `SourbyCraftConfig.ymlInt` returns default + emits one WARN via existing dedupe `Set<String>`. Knob receives default. |
| Out-of-range int from yml (`perf.entity-tick-rate: 99`) | `IntKnob.loadFrom` calls `set(99)` → clamps to 20 → emits `KnobRegistry.warnOnce(key, 99, 20)` → WARN line: `[SourbyCraft] knob 'perf.entity-tick-rate' value 99 clamped to 20`. |
| Out-of-range int repeat boot | WARN per (knob,direction) fires once per JVM lifetime. |
| Caller `Knob.set(99)` at runtime (controller) | Same clamp + WARN dedupe path as boot-time. |
| Duplicate knob key registered at class-init | `KnobRegistry.register` throws `IllegalStateException("duplicate knob key: <key>")`. Fail fast — programming error, never reached in shipped code. |
| `IntKnob` constructor with `min > max` | Throws `IllegalArgumentException("min > max for <key>")`. Programmer error. |
| Concurrent writes to same knob | Last writer wins. Acceptable: only the controller or `/perf` cmd writes any given knob; no contention in practice. |
| Boot fails before `Knobs.loadFromYml()` runs | Knobs hold constructor defaults. Server boots as vanilla Paper. Safe fallback. |

All WARNs go through existing `SourbyLogger.LOGGER` channel. No new exception types.

## 6. Testing

Test pyramid for P0 — smoke only, no JUnit added.

| Layer | Coverage | Mechanism |
|---|---|---|
| Boot smoke (C9) | 5 scenarios: default / in-range / clamp-hi / clamp-lo / wrong-type | `test-harness/scripts/p0-knob-smoke.sh` → boots `test-harness/TestServer-mojmap/` via existing `boot-mojmap.sh` infra; reads `boot.log` + RCON `/perf` output |
| CI gate | PRs touching `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/**` or `sourbycraft.yml` perf section trigger the smoke | Existing `.github/workflows/` matrix; new job `p0-knob-smoke` running `./gradlew p0KnobSmokeTest -PrunP0KnobSmoke=true` |
| Boot regression | Full clean boot of single mojmap jar with default `sourbycraft.yml` must reach `Done (` within existing baseline window | Reuses existing `nmsCompatTest` boot path; P0 must not regress boot timing |
| Plugin compat regression | r1 baseline matrix preserved | Reuses existing operator-checklist matrix; default-config boot |

**Bench (informational, not gated)**: P0 does not require a JMH bench. If a reviewer requests, a one-off JMH bench comparing `SourbyCraftConfig.entityTickRate()` (new getter) vs the old static field read can be run locally — must show ≤1ns/op median. Not a CI gate.

Existing 28 SourbyCraft JUnit tests stay untouched (separate cleanup spec owns deletion if desired). Paper upstream 169 tests untouched.

## 7. Acceptance criteria

After P0 lands, all of the following hold:

| Check | Command | Expected |
|---|---|---|
| Knob package created | `ls sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/` | `PerfKnob.java BoolKnob.java IntKnob.java KnobRegistry.java Knobs.java` |
| Sealed hierarchy correct | `grep 'sealed abstract class PerfKnob' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/PerfKnob.java` | match |
| Reference knob declared | `grep 'ENTITY_TICK_RATE' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java` | match |
| Field → getter migration | `grep 'public static int entityTickRate' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` | shows method declaration `entityTickRate()`, not field |
| All callers updated | `grep -rn 'SourbyCraftConfig\.entityTickRate[^(]' sourbycraft-server/ paper-server/ patches/ \| grep -v build/` | empty |
| DynamicPerformanceScaler writes via knob | `grep 'Knobs.ENTITY_TICK_RATE.set' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/DynamicPerformanceScaler.java` | match |
| yml schema has perf section | `grep -c '^perf:' sourbycraft-server/src/main/resources/sourbycraft.yml` | `1` |
| Smoke script present | `ls test-harness/scripts/p0-knob-smoke.sh` | file exists, executable |
| Gradle task registered | `./gradlew tasks --all -PrunP0KnobSmoke=true \| grep p0KnobSmokeTest` | listed |
| Smoke passes all 5 scenarios | `./gradlew p0KnobSmokeTest -PrunP0KnobSmoke=true` | green |
| No new JUnit added in sourbycraft-server | `git diff main --stat sourbycraft-server/src/test/` | empty |
| Default boot unchanged | Clean boot with default yml | reaches `Done (` within prior baseline window |
| Plugin compat r1 preserved | Re-run compat matrix with default config | no new failures vs r1 |
| Hot-path microbench (informational) | local JMH: `Knobs.ENTITY_TICK_RATE.get()` vs old static field | ≤1ns/op median |

## 8. Out of scope

P0 explicitly does NOT cover:

1. **Sub-projects P1–P8** — each its own spec → plan → impl cycle.
2. **Listener bus / change history** — deferred; P7 adds when controller audit log lands.
3. **`DoubleKnob`, `EnumKnob`, `ListKnob`** — added when first consumer appears (P3 DAB activation modifier likely needs Double; P4 combat profile likely needs Enum).
4. **Hot-reload command** (`/sc reload knobs`) — restart required.
5. **Per-world knob overrides** — global only.
6. **Knob persistence across restart** — value always re-init from yml at boot.
7. **In-game knob viewer** (`/sc knob list / get / set <key> <val>`) — operator reads yml directly; controller (P7) writes programmatically. Add cmd in P8 if needed.
8. **Migrating other existing knobs** — only `entityTickRate` migrated as reference. `mobAIDistanceCutoff`, DAB params, packet buffer pre-size, etc. migrate in their owning sub-spec.
9. **Deleting existing 28 SourbyCraft JUnit tests** — separate cleanup spec if wanted.
10. **Sourby Bootstrap** (jar slimming + first-boot resolver) — separate brainstorm cycle after P0.
11. **DynamicPerformanceScaler logic changes** — only the write call site is migrated. The TPS-tier logic itself moves to P1+P7. P0 leaves the file structurally intact.
12. **Async writes to knobs** — P0 assumes main-thread writes. Async controllers in P5/P6/P7 may need stricter publication discipline; the volatile field already supports concurrent reads, but write contention is out of scope.

## 9. Phases (handed to writing-plans)

Suggested phase breakdown for the implementation plan. `writing-plans` owns the detailed step decomposition.

### Phase 1 — Knob abstraction skeleton
- Create `dev.iyanz.sourbycraft.perf.knob` package.
- Add `PerfKnob` sealed abstract class.
- Add `BoolKnob`, `IntKnob` concrete subclasses.
- Add `KnobRegistry` (package-private; ConcurrentHashMap + WARNED dedupe set).
- Add `Knobs` holder class with no knobs declared yet (just `snapshot()` + `loadFromYml()` plumbing).
- One commit: `feat: perf-engine P0 — PerfKnob/BoolKnob/IntKnob/Knobs skeleton`.

### Phase 2 — Reference knob declaration
- Add `Knobs.ENTITY_TICK_RATE = new IntKnob("perf.entity-tick-rate", 1, 1, 20)`.
- Append `perf:` block to `sourbycraft-server/src/main/resources/sourbycraft.yml` with `entity-tick-rate: 1`.
- Wire `SourbyCraftConfig.load()` to call `Knobs.loadFromYml(this)` after existing yml-load completes.
- One commit: `feat: perf-engine P0 — declare ENTITY_TICK_RATE knob + wire yml load`.

### Phase 3 — Field → getter migration
- Convert `SourbyCraftConfig.entityTickRate` from `public static int = 1` field to `public static int entityTickRate()` method returning `Knobs.ENTITY_TICK_RATE.get()`.
- Update all read-site callers across `sourbycraft-server/`, `paper-server/`, `patches/` to use `()` form. Grep first to enumerate; expected ~5–10 sites.
- Update `DynamicPerformanceScaler` to write via `Knobs.ENTITY_TICK_RATE.set(rate)` and read via `Knobs.ENTITY_TICK_RATE.get()`.
- One commit: `refactor: migrate entityTickRate field → Knobs.ENTITY_TICK_RATE getter`.

### Phase 4 — Smoke harness
- Add `test-harness/scripts/p0-knob-smoke.sh` (5 scenarios: default / in-range / clamp-hi / clamp-lo / wrong-type; reuses `boot-mojmap.sh` infra; RCON helper for `/perf` output capture).
- Add `p0KnobSmokeTest` gradle task in `sourbycraft-server/build.gradle.kts` gated by `-PrunP0KnobSmoke=true`.
- One commit: `test: perf-engine P0 — smoke harness for knob clamp + load`.

### Phase 5 — CI gate
- Extend `.github/workflows/` matrix: add job `p0-knob-smoke` triggered by path filter `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/**`, `sourbycraft.yml` perf section, `test-harness/scripts/p0-knob-smoke.sh`.
- One commit: `ci: gate perf-engine P0 knob smoke on changed paths`.

### Optional Phase 6 — Informational JMH bench
- Not required to ship. Only run if reviewer requests.
- Local JMH bench: `Knobs.ENTITY_TICK_RATE.get()` vs prior static-field read; assert ≤1ns/op median.
- Not committed unless results are interesting.

## 10. Out-of-scope reminders (next sub-spec candidates)

After P0 lands, the natural next sub-spec is **P1 — Load Sensor + Tier classifier**. It builds on P0's knob registry by exposing the multi-signal load reading as a separate API; the tier-state-machine output feeds into P2 (lag-machine batch) and ultimately P7 (the controller). P1 has no NMS surface — it reads `MinecraftServer` tick stats + JMX GC notifications, runs a small state machine, and exposes `/perf tier` + `/perf sensors` commands.

P2 (lag-machine protection) is the first sub-spec that adds new knobs to the `Knobs` holder declared here in P0. It validates the knob-declaration pattern at category scope before the higher-risk P3–P6 work.
