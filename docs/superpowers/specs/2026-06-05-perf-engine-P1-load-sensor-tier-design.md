# Perf-engine P1 — Load Sensor + Tier Classifier (design)

**Date:** 2026-06-05
**Scope:** Sub-project P1 of the SourbyCraft self-tuning perf-engine roadmap (9 sub-projects total). Adds the multi-signal load sensor + 5-tier state machine that P7 controller consumes.
**Out-of-scope:** Knob-delta application (P7), BossBar visualization (P8), `DynamicPerformanceScaler` removal (P7). See Section 8.
**Status:** Draft for user review.

---

## 1. Background + Scope

P0 shipped the `Knobs` registry. P1 adds the load-sensing layer that drives tier-based decisions. P7 controller will read `PerfSensor.currentTier()` and apply tier-mapped knob deltas via the registry.

```
P0 — Knob Registry API ✓
P1 — Load Sensor + Tier classifier                  ← this spec
P2 — Lag-Machine Protection batch
P3 — Adaptive Entity AI
P4 — Combat Profiles
P5 — Async Chunk Pipeline
P6 — Async Packet + World subsystems
P7 — Self-Tune Controller (reads P1 tier, writes P0 knobs)
P8 — Operator UX + Telemetry
```

**Goal:** Multi-signal load sensor + 5-tier state machine (GREEN/YELLOW/ORANGE/RED/EMERGENCY) with dwell+band hysteresis. Pull-API for tier + raw signals. `/perf tier` + `/perf sensors` operator commands.

**In scope:**
- `PerfSensor` class: reads TPS (1s / 1m / 5m), MSPT, mem%, GC pause ms/min each second.
- `Tier` enum (5 values, worst > best via ordinal).
- `SensorSnapshot` record (immutable readings + tier + dwell counter).
- Dwell+band hysteresis: dwell-count gates every transition; recovery (toward better tier) requires a configurable multiplier of that dwell count, making escalation faster than recovery.
- Threshold defaults hardcoded + yml override via `perf.sensor.thresholds.*`.
- `/perf tier` subcommand (current tier + time-in-tier).
- `/perf sensors` subcommand (raw signals + thresholds).
- Wire `PerfSensor.tick` from `tickServer` hot path.
- Boot-smoke harness for transition behavior.

**Out of scope (deferred to owning sub-project):**
- Knob-delta application → P7 controller.
- Listener bus / change history → P7 adds if needed.
- BossBar tier visualization → P8.
- Sentry breadcrumb on transition → P8.
- `DynamicPerformanceScaler` removal — P1 leaves DPS intact; P7 deletes it.
- JMX async GC notification listener — synchronous JMX poll at 1s sufficient.
- Per-world tier — global only.
- Hot-reload of sensor config — restart required.
- TPS true 30s window — Paper exposes 1m/5m/15m; P1 maps `tps30s` field to `recentTps[0]` (1m). Sub-minute resolution deferred.

**Constraints inherited from P0:**
- Single mojmap jar.
- Pull-only API; no listeners.
- yml-baked defaults; restart required for changes.
- Logging via `SourbyLogger` (single-arg, concat).
- No JUnit; smoke-driven verification in `test-harness/TestServer-mojmap/`.
- Hot-path safe.

## 2. Architecture

```
MinecraftServer.tickServer (existing main-thread loop)
  └─ each tick: PerfSensor.tick(server)
       └─ if ++tickCounter < 20: return    (1s cadence)
       └─ read 4 signals:
            ├─ TPS rolling 1s/1m/5m   ← server.recentTps[0..2]; tps1s from getAverageTickTimeNanos
            ├─ MSPT current avg        ← server.getAverageTickTimeNanos / 1e6
            ├─ mem%                    ← Runtime.totalMemory/maxMemory
            └─ GC pause ms/min         ← JMX GarbageCollectorMXBean cumulative delta, ring-buffered over 60 samples
       └─ classify(reading) → candidateTier
       └─ apply dwell+band hysteresis → maybe transition currentTier
       └─ store SensorSnapshot in volatile field

dev.iyanz.sourbycraft.perf.sensor   ← NEW package
  ├─ Tier (enum: GREEN/YELLOW/ORANGE/RED/EMERGENCY)
  ├─ SensorSnapshot (record)
  └─ PerfSensor (static utility)
       ├─ static fields:
       │    ├─ volatile SensorSnapshot lastSnapshot
       │    ├─ Tier currentTier
       │    ├─ int dwellCount
       │    └─ Tier candidateTier
       ├─ public static tick(MinecraftServer)
       ├─ public static currentTier()
       ├─ public static snapshot()
       ├─ public static thresholdsFor(String signal)   (used by /perf sensors)
       └─ private static classifyAll(reading) → Tier

sourbycraft.yml (jar-baked, extends P0 `perf:` section)
  └─ perf:
       sensor:
         enabled: true
         cadence-ticks: 20
         dwell-samples: 3
         recovery-dwell-multiplier: 2.0   # downgrade to a better tier requires 2x the dwell of escalation
         thresholds:
           tps:           { yellow: 19.5, orange: 18.0, red: 15.0, emergency: 10.0 }
           mspt:          { yellow: 30,   orange: 40,   red: 60,   emergency: 100 }
           mem:           { yellow: 75,   orange: 85,   red: 92,   emergency: 97 }
           gc-ms-per-min: { yellow: 20,   orange: 50,   red: 100,  emergency: 300 }

PerfCommand (existing in dev.iyanz.sourbycraft.perf)
  ├─ existing: scale on/off, rate N
  └─ new: tier, sensors  (Brigadier `.then(literal(...))`)
```

**Invariants:**
- Sensor writes only from `tickServer` (main thread). No locks on hot path.
- `lastSnapshot` is `volatile` → consumers see latest visible without sync; `SensorSnapshot` is a record with final fields, so the whole tuple publishes atomically.
- Tier transition fires `SourbyLogger.info("perf tier transition: <old> -> <new> (after <dwell> samples in candidate)")`. Single log line per transition.
- JMX GC bean list cached lazily in static field (first read of `gcMsLastMinute`). Single-threaded — main only.
- yml override loaded once in `PerfSensor.loadFromYml()` called from `SourbyCraftConfig.init()` (right after P0 `Knobs.loadFromYml()`).
- Threshold arrays sized 5, indexed by `Tier.ordinal()`. Index 0 (GREEN) is a sentinel boundary (`Double.MAX_VALUE` for lower-is-worse signals; `Double.MIN_VALUE` for higher-is-worse) so the classifier never returns "no tier".
- Tier ordering (worst-to-best): EMERGENCY > RED > ORANGE > YELLOW > GREEN. Classifier picks worst across 4 signals via `Tier.worse(...)`.

## 3. Components

### C1. `Tier` enum

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/Tier.java` (new)

```java
package dev.iyanz.sourbycraft.perf.sensor;

/** Server load tier — worse = higher ordinal. */
public enum Tier {
    GREEN,
    YELLOW,
    ORANGE,
    RED,
    EMERGENCY;

    public boolean isWorseThan(Tier other) { return this.ordinal() > other.ordinal(); }
    public Tier worse(Tier other) { return this.ordinal() >= other.ordinal() ? this : other; }
}
```

### C2. `SensorSnapshot` record

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/SensorSnapshot.java` (new)

```java
package dev.iyanz.sourbycraft.perf.sensor;

/**
 * Immutable point-in-time reading of all 4 load signals + computed tier + dwell state.
 * Produced once per sensor tick (1s cadence). volatile-published from PerfSensor.
 */
public record SensorSnapshot(
    long timestampNanos,
    double tps1s,
    double tps30s,
    double tps5m,
    double msptAvg,
    double memPct,
    double gcMsPerMin,
    Tier tier,
    Tier candidateTier,
    int dwellSamples
) {
    public static final SensorSnapshot INITIAL = new SensorSnapshot(
        0L, 20.0, 20.0, 20.0, 0.0, 0.0, 0.0, Tier.GREEN, Tier.GREEN, 0
    );
}
```

### C3. `PerfSensor` (the bulk of P1)

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/PerfSensor.java` (new; ~220 LOC).

Public API:
- `static void loadFromYml()` — load cadence/dwell/thresholds from JAR yml; validates threshold monotonicity, clamps cadence and dwell to ≥1.
- `static void tick(MinecraftServer server)` — main-thread entry; gated by `cadenceTicks`. Reads 4 signals, classifies, applies hysteresis, publishes snapshot, logs transitions.
- `static Tier currentTier()` — volatile read of `lastSnapshot.tier()`.
- `static SensorSnapshot snapshot()` — volatile read of `lastSnapshot`.
- `static boolean isEnabled()`.
- `static double[] thresholdsFor(String signal)` — returns defensive clone of threshold array; supported signals `tps`, `mspt`, `mem`, `gc-ms-per-min`. Throws `IllegalArgumentException` on unknown signal.

Private internals:
- `classifyAll(tps, mspt, mem, gc) → Tier` — picks worst tier across 4 signals.
- `classifySignal(value, thresholds, lowerIsWorse) → Tier` — walks thresholds EMERGENCY → GREEN, returns first boundary the value crosses.
- `transition(newTier)` — assigns `currentTier`, resets `dwellCount`, emits log line.
- `tpsFromNanos`, `paperTpsAvg`, `memUsagePercent`, `gcMsLastMinute` — signal readers (see Section 4 for cost).

`loadFromYml` defaults:
- `cadenceTicks = 20`
- `dwellSamples = 3`
- `recoveryDwellMultiplier = 2.0`
- `tpsThresholds = {MAX, 19.5, 18.0, 15.0, 10.0}` (lower-is-worse)
- `msptThresholds = {MIN, 30, 40, 60, 100}` (higher-is-worse)
- `memThresholds = {MIN, 75, 85, 92, 97}`
- `gcMsThresholds = {MIN, 20, 50, 100, 300}`

**Hysteresis logic** (dwell+band combined):

```
on each sensor tick:
  reading = classifyAll(...)
  if reading == currentTier:
      candidateTier = currentTier
      dwellCount = 0
  else if reading == candidateTier:
      requiredDwell = reading.isWorseThan(currentTier)
                          ? dwellSamples
                          : (int) Math.ceil(dwellSamples * recoveryDwellMultiplier)
      dwellCount++
      if dwellCount >= requiredDwell: transition(reading)
  else:
      candidateTier = reading
      dwellCount = 1
```

This achieves band hysteresis: escalation (toward worse tier) needs `dwellSamples` consecutive samples; recovery (toward better tier) needs `dwellSamples × recoveryDwellMultiplier` consecutive samples. Default 2.0 → recover takes 6 samples vs 3 for escalation, biasing the system to act on protection earlier than it relaxes.

Threshold-array shape: index 0 (GREEN) is a sentinel boundary; classifier walks from index 4 (EMERGENCY) down and returns the first index whose threshold the value crosses. Below all thresholds → returns GREEN.

### C4. Wire `PerfSensor.tick` into the tickServer hot path

The existing `DynamicPerformanceScaler.tick(server)` is called somewhere in the main tick loop. Locate that call site (grep `DynamicPerformanceScaler.tick` across `patches/` and `sourbycraft-server/src`), insert `PerfSensor.tick(server)` as a sibling line immediately after it. If the existing call site lives in a `patches/server/` patch, add a small new patch that adds one line. If it's in a Java file directly, edit in place.

The sensor must run on the main thread. Verified by inspection: the existing `DynamicPerformanceScaler.tick(server)` runs main-thread (it calls `server.getAverageTickTimeNanos()` from `MinecraftServer.tickServer`), so the same call site is correct for the sensor.

### C5. Wire `PerfSensor.loadFromYml()` into `SourbyCraftConfig.init()`

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` (edit)

Add immediately AFTER existing `Knobs.loadFromYml()` line and BEFORE the Bukkit-config `getInt`/`getBoolean` block (the position established by P0 Task 3):

```java
        dev.iyanz.sourbycraft.perf.sensor.PerfSensor.loadFromYml();
```

Wrap the call in `try/catch (Throwable t) { SourbyLogger.error("PerfSensor.loadFromYml failed; using defaults", t); }` so a sensor-config bug never blocks boot.

### C6. `sourbycraft.yml` schema growth

**File:** `sourbycraft-server/src/main/resources/sourbycraft.yml` (edit — extend `perf:` section added by P0)

Append under existing `perf:` block:

```yaml
  sensor:
    # Multi-signal load sensor feeding the 5-tier state machine.
    # Runs on the main thread every cadence-ticks ticks.
    enabled: true
    cadence-ticks: 20                # 1s at 20 TPS
    dwell-samples: 3                 # samples in candidate tier required before escalation
    recovery-dwell-multiplier: 2.0   # recovery requires dwell-samples * multiplier samples
    thresholds:
      # TPS: lower is worse. Value below tier threshold escalates AT LEAST to that tier.
      tps:           { yellow: 19.5, orange: 18.0, red: 15.0, emergency: 10.0 }
      # MSPT / mem / GC: higher is worse. Value above tier threshold escalates AT LEAST to that tier.
      mspt:          { yellow: 30,   orange: 40,   red: 60,   emergency: 100 }
      mem:           { yellow: 75,   orange: 85,   red: 92,   emergency: 97 }
      gc-ms-per-min: { yellow: 20,   orange: 50,   red: 100,  emergency: 300 }
```

### C7. `PerfCommand` subcommands `tier` + `sensors`

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java` (edit)

Add two `.then(Commands.literal("tier"))` and `.then(Commands.literal("sensors"))` to the existing register chain.

`/perf tier` output (sent via `src.sendSystemMessage`):

```
[Perf] Tier: ORANGE
[Perf] Candidate: RED (2/3 samples)
[Perf] Sensor uptime: 47s
```

(Sensor uptime = `(System.nanoTime() - lastSnapshot.timestampNanos()) / 1e9`, expressed in seconds. When sensor never ticked: "Sensor uptime: 0s (no samples yet)".)

`/perf sensors` output:

```
[Perf] Sensor cadence: 1s, dwell: 3 samples
[Perf] TPS:  19.95 (1s)  19.87 (1m)  19.92 (5m)   thresholds Y/O/R/E: 19.5/18.0/15.0/10.0
[Perf] MSPT: 32.4 ms                              thresholds Y/O/R/E: 30/40/60/100
[Perf] Mem:  78% used                             thresholds Y/O/R/E: 75/85/92/97
[Perf] GC:   45 ms/min                            thresholds Y/O/R/E: 20/50/100/300
```

When `enabled: false`: both commands print `[Perf] Sensor disabled (perf.sensor.enabled=false)` and exit.

### C8. Boot smoke harness

**Files:** `test-harness/scripts/p1-tier-smoke.sh` (new), `patches/buildscript/server/0015-p1TierSmokeTest.patch` (new).

Pattern mirrors P0 (`test-harness/scripts/p0-knob-smoke.sh` + `patches/buildscript/server/0014-p0KnobSmokeTest-gradle-task.patch`). Gated by `-PrunP1TierSmoke=true`.

Scenarios:

| # | Name | yml override | Asserts |
|---|---|---|---|
| 0 | `boot_sanity` | none | server reaches `Done (`; no crash |
| 1 | `default_stays_green` | none | RCON `perf tier` returns `Tier: GREEN`; boot.log shows zero `perf tier transition` lines in 10s observation window |
| 2 | `force_yellow_via_mspt` | `perf.sensor.thresholds.mspt.yellow: 0.001` + `perf.sensor.dwell-samples: 1` | boot.log contains `perf tier transition: GREEN -> YELLOW` within 5s |
| 3 | `dwell_prevents_transient` | `perf.sensor.thresholds.mspt.yellow: 0.001` + `perf.sensor.dwell-samples: 999` | boot.log contains zero `perf tier transition` lines in 10s |
| 4 | `force_emergency_via_mem` | `perf.sensor.thresholds.mem: { yellow: 0.1, orange: 0.2, red: 0.3, emergency: 0.5 }` + `perf.sensor.dwell-samples: 1` | boot.log contains `-> EMERGENCY` transition |
| 5 | `non_monotonic_warn` | `perf.sensor.thresholds.mspt: { yellow: 100, orange: 50, red: 60, emergency: 80 }` | boot.log contains `sensor threshold 'mspt' non-monotonic, reverting to defaults` WARN |
| 6 | `sensor_disabled` | `perf.sensor.enabled: false` | RCON `perf tier` returns `Sensor disabled`; zero transition lines |
| 7 | `perf_sensors_cmd` | none | RCON `perf sensors` output contains substrings `TPS:`, `MSPT:`, `Mem:`, `GC:` |

(`recovery-dwell-multiplier` behavior is not smoke-asserted — would require mid-boot threshold revert which the yml-is-read-once architecture cannot do. Verified at impl time by code review + manual: set `dwell-samples: 2`, `recovery-dwell-multiplier: 5.0`, observe escalation in 2 ticks and recovery requiring 10 ticks.)

Exit codes:

```
0 = all PASS
1 = missing release jar
2 = server died before Done (
3 = boot timeout
4 = boot.log assertion fail (inside boot_and_assert)
5 = scenario 1 unexpected transition fired
6 = scenario 3 dwell did not block transition
7 = scenario 5 missing non-monotonic WARN
8 = scenario 6 transition fired with sensor disabled
9 = scenario 7 cmd output missing required substring
```

RCON usage: scenarios 1, 6, 7 invoke `perf tier` / `perf sensors` via the existing P0 smoke RCON-seeding (port 25675, password `p1test`). Helper function `rcon_cmd` (TBD at impl time — likely `mcrcon` if installed, else shell-out via Python `socket`).

CI gate: extend `.github/workflows/nms-compat.yml`:
- Add `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/**` and `test-harness/scripts/p1-tier-smoke.sh` to pull_request `paths`.
- Add `Run perf-engine P1 tier smoke` step with `id: p1_tier_smoke` after the existing `Run perf-engine P0 knob smoke` step.
- Add `Upload P1 tier smoke boot log` step gated on `failure() && steps.p1_tier_smoke.conclusion == 'failure'`.

No JUnit added.

## 4. Data flow

```
boot:
  CraftServer.enable()
    └─ SourbyCraftConfig.init()
         ├─ Knobs.loadFromYml()            (P0, existing)
         ├─ PerfSensor.loadFromYml()       (NEW: cadence/dwell/thresholds from JAR yml; clamp + WARN on non-monotonic)
         ├─ ... existing Bukkit-config block ...
         └─ Knobs.logLoaded()              (P0, existing)

per server tick (main thread):
  MinecraftServer.tickServer
    └─ DynamicPerformanceScaler.tick(server)   (existing; P7 will delete)
    └─ PerfSensor.tick(server)                 (NEW)
         ├─ tickCounter++; return if < 20      (1s gate, ~1ns)
         ├─ read TPS rolling                   (~10ns; recentTps array)
         ├─ read MSPT                          (~10ns; server.getAverageTickTimeNanos)
         ├─ read mem%                          (~50ns; 2 Runtime calls + div)
         ├─ read GC ms/min                     (~3µs; JMX bean iter + ring update)
         ├─ classifyAll(4 signals)             (~40ns; 4 × small array walk)
         ├─ hysteresis update                  (~5ns; 3 int compares)
         ├─ if transition: SourbyLogger.info   (~10µs amortized; rare)
         └─ publish lastSnapshot (volatile)    (~5ns)
    TOTAL per sensor tick: ~3.2µs (every 20 ticks)
    TOTAL amortized per server tick: ~160ns

read path (commands; future P7/P8):
  PerfSensor.currentTier()
    └─ return lastSnapshot.tier()              (volatile read, ~1ns)
  PerfSensor.snapshot()
    └─ return lastSnapshot                     (volatile read, ~1ns; record is immutable)
```

**Concurrency model:**
- Sensor writes only from `tickServer` main thread. No locks. Writes are simple field assigns + a single `volatile SensorSnapshot` publish.
- All read paths see latest snapshot via volatile semantics. Record reference is atomic; record fields are final.
- Threshold arrays (`tpsThresholds`, `msptThresholds`, `memThresholds`, `gcMsThresholds`) are `volatile`-published from `loadFromYml` at boot. Sensor-tick reads them without sync; OK because writes complete before `tickServer` starts.
- `gcBeans` lazy-init on first sensor tick; single-threaded.

**Boundary cases:**
- First sensor tick before completion: `lastSnapshot == SensorSnapshot.INITIAL` (GREEN, all zeros).
- GC pause ring buffer: first 60 sensor ticks (60s) under-report `gcMsPerMin` (ring not yet full). Conservative — under-report means fewer false escalations.
- `MinecraftServer.recentTps` shape: Paper exposes `double[]{tps1m, tps5m, tps15m}`. P1 maps `tps30s` field to `recentTps[0]` (1m) since no 30s window exists. Naming retained for forward-compat.
- Threshold ordering invariant: `tpsThresholds` decreasing (`yellow > orange > red > emergency`, since lower TPS is worse); `mspt`/`mem`/`gc` increasing. `loadFromYml` validates; on violation logs WARN and restores hardcoded defaults for that signal.

**Reload:** not supported in P1. yml header documents "Changes require server restart."

## 5. Error handling

| Case | Behavior |
|---|---|
| Missing `perf.sensor.*` yml block | All values default to hardcoded constants. Silent. |
| `perf.sensor.enabled: false` | `tick` returns immediately. `currentTier()` returns last snapshot (initial GREEN if never ran). Commands print "Sensor disabled". |
| Threshold yml wrong type | Existing `SourbyCraftConfig.ymlDouble` returns default + one WARN per key (P0 dedupe). Threshold stays at hardcoded constant. |
| Threshold ordering violation | `loadFromYml` validates each signal array post-load: must be monotonic in worse-direction. On violation: WARN `sensor threshold '<signal>' non-monotonic, reverting to defaults`, restore hardcoded array for that signal. |
| Negative `cadence-ticks` / `dwell-samples` | Clamp at boot: `Math.max(1, ...)`. WARN once on clamp. |
| `recovery-dwell-multiplier` < 1.0 or NaN | Clamp at boot: `Math.max(1.0, ...)`; NaN → default 2.0. WARN once. |
| `MinecraftServer.recentTps` null/short | `paperTpsAvg` falls back to `tpsFromNanos(server.getAverageTickTimeNanos())` for all windows. |
| `Runtime.maxMemory() == 0` (rare) | `memUsagePercent` returns 0.0. Mem signal always GREEN. |
| `getGarbageCollectorMXBeans` empty | `gcMsLastMinute` returns 0.0 every tick. GC signal always GREEN. |
| `getCollectionTime` overflow | ~292 million years of cumulative GC. Not handled. |
| Rapid tier flapping | Prevented by dwell+band hysteresis. Worst case: 1 transition log per `dwellSamples × cadenceTicks` = 3s at defaults. |
| Concurrent `tick()` calls (mis-wired) | Impossible by design. Not guarded. If a developer adds a scheduler call by mistake, `tickCounter`/`dwellCount`/`currentTier` race; result is incorrect tier classification but no crash. |
| `currentTier()` before first tick | Returns `Tier.GREEN` via `SensorSnapshot.INITIAL`. |
| `loadFromYml` throws | Caller wraps in try/catch + logs error; sensor falls back to hardcoded defaults; boot continues. |
| `/perf tier` / `/perf sensors` with sensor disabled | Prints "Sensor disabled (perf.sensor.enabled=false)". |
| NaN/Infinity signal readings (shouldn't happen) | `/perf sensors` formats as `n/a`; classifier skips that signal (treats as GREEN for that signal). |

**No new exception types.** All errors routed through `SourbyLogger` (warn or error). Hardcoded defaults always provide a working sensor.

## 6. Testing

Smoke-only, per project convention. No JUnit added.

| Layer | Coverage | Mechanism |
|---|---|---|
| Boot smoke (C8) | 7 scenarios | `test-harness/scripts/p1-tier-smoke.sh` boots `test-harness/TestServer-mojmap/` via P0's harness pattern |
| CI gate | PRs touching `perf/sensor/**` or `p1-tier-smoke.sh` | `nms-compat.yml` matrix; new step `Run perf-engine P1 tier smoke` |
| Boot regression | Default boot must reach `Done (` within prior baseline (≤90s) | Reuses existing harness boot path |
| Plugin compat regression | r1 baseline preserved | Reuses existing operator checklist |

**No JUnit.** Existing 28 SourbyCraft JUnit tests untouched. Paper upstream 169 tests untouched.

## 7. Acceptance criteria

| # | Check | Command | Expected |
|---|---|---|---|
| 1 | Sensor package created | `ls sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/` | `Tier.java SensorSnapshot.java PerfSensor.java` |
| 2 | Tier enum has 5 values | `grep -cE '^\s*(GREEN\|YELLOW\|ORANGE\|RED\|EMERGENCY)' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/Tier.java` | `5` |
| 3 | SensorSnapshot is a record | `grep 'public record SensorSnapshot' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/SensorSnapshot.java` | match |
| 4 | PerfSensor.tick wired into tickServer | `grep -rn 'PerfSensor.tick' patches/server/ sourbycraft-server/src/main/java/` | ≥1 hit at a hot-path call site |
| 5 | PerfSensor.loadFromYml wired into init | `grep 'PerfSensor.loadFromYml' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` | match |
| 6 | yml has sensor section | `grep -c 'sensor:' sourbycraft-server/src/main/resources/sourbycraft.yml` | ≥1 |
| 7 | /perf tier subcommand registered | `grep 'literal("tier")' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java` | match |
| 8 | /perf sensors subcommand registered | `grep 'literal("sensors")' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java` | match |
| 9 | Smoke script present, executable | `ls -la test-harness/scripts/p1-tier-smoke.sh` | `-rwxr-xr-x` |
| 10 | Gradle task registered | `./gradlew tasks --all -PrunP1TierSmoke=true \| grep p1TierSmokeTest` | listed |
| 11 | Smoke green | `./gradlew p1TierSmokeTest -PrunP1TierSmoke=true` | all 7 scenarios PASS |
| 12 | CI gate added | `grep p1TierSmokeTest .github/workflows/nms-compat.yml` | match |
| 13 | No new JUnit | `git diff <P0-final>..HEAD --stat sourbycraft-server/src/test/` | empty |
| 14 | Default boot unchanged | scenario 0 | reaches `Done (` ≤90s |
| 15 | DynamicPerformanceScaler unchanged | `git diff <P0-final>..HEAD sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/DynamicPerformanceScaler.java` | empty (P7 owns removal) |

## 8. Out of scope

P1 explicitly does NOT cover:

1. **Knob-delta application** — P7 controller reads `PerfSensor.currentTier()` and applies knob writes; P1 only emits tier.
2. **Listener bus / change history** — pull-only. P7 adds if needed.
3. **BossBar tier visualization** — P8.
4. **Sentry breadcrumb on transition** — P8.
5. **`DynamicPerformanceScaler` removal** — P7 deletes after migrating its logic into the controller.
6. **Per-world tier** — global only.
7. **JMX async GC notification listener** — synchronous JMX poll at 1s sufficient.
8. **Tier transition history persistence** — in-memory only; restart resets.
9. **Hot-reload of sensor config** — restart required.
10. **Custom signal plugins** — 4 signals only. No SPI for adding more.
11. **PerfSensor pause/resume at runtime** — only `enabled: false` at boot.
12. **TPS true 30s window** — Paper exposes 1m/5m/15m; `tps30s` slot maps to `recentTps[0]` (1m).

## 9. Phases (handed to writing-plans)

Suggested phase breakdown. `writing-plans` owns detailed step decomposition.

### Phase 1 — Smoke harness skeleton
- `test-harness/scripts/p1-tier-smoke.sh` with SCENARIO_0_BOOT only.
- Gradle task `p1TierSmokeTest` via `patches/buildscript/server/0015-p1TierSmokeTest.patch` (mirror P0).
- One commit: `test: perf-engine P1 — tier smoke skeleton (boot sanity)`.

### Phase 2 — Tier enum + SensorSnapshot record
- Create package `dev.iyanz.sourbycraft.perf.sensor`.
- `Tier.java` and `SensorSnapshot.java`. Compile-only.
- One commit: `feat: perf-engine P1 — Tier enum + SensorSnapshot record`.

### Phase 3 — PerfSensor + yml load + tick wiring
- `PerfSensor.java` with `loadFromYml()`, signal readers, classifier, hysteresis, `tick()`.
- Append `sensor:` block to `sourbycraft.yml`.
- Wire `PerfSensor.loadFromYml()` into `SourbyCraftConfig.init()` (after `Knobs.loadFromYml()`, before Bukkit-config block).
- Wire `PerfSensor.tick(server)` into the tickServer hot path (locate `DynamicPerformanceScaler.tick` call site, insert sibling).
- Add SCENARIO_1_DEFAULT_STAYS_GREEN.
- One commit: `feat: perf-engine P1 — PerfSensor with 4 signals + hysteresis`.

### Phase 4 — `/perf tier` + `/perf sensors` subcommands
- Edit `PerfCommand.java` to add `Commands.literal("tier")` and `Commands.literal("sensors")` subtrees.
- Implement output formatters.
- Add SCENARIO_7_PERF_SENSORS_CMD; add cmd-output substring check for SCENARIO_1 + SCENARIO_6.
- One commit: `feat: perf-engine P1 — /perf tier + /perf sensors subcommands`.

### Phase 5 — Forced-transition + boundary smoke scenarios
- Add SCENARIO_2_FORCE_YELLOW, SCENARIO_3_DWELL_PREVENTS, SCENARIO_4_FORCE_EMERGENCY, SCENARIO_5_NON_MONOTONIC_WARN, SCENARIO_6_SENSOR_DISABLED.
- No production code change. If a scenario fails, fix Phase 3/4 — do not paper over.
- One commit: `test: perf-engine P1 — transition + boundary smoke scenarios`.

### Phase 6 — CI gate
- Extend `.github/workflows/nms-compat.yml` paths, add `Run perf-engine P1 tier smoke` step with `id: p1_tier_smoke`, add upload-on-failure gated on that step.
- One commit: `ci: gate perf-engine P1 tier smoke on changed paths`.

## 10. Out-of-scope reminders (next sub-spec candidates)

After P1 lands, the next natural sub-spec is **P2 — Lag-Machine Protection batch** (lowest risk in remaining roadmap; pure NMS toggles via Knobs). P2 validates the per-knob-per-tier shape that P7 will eventually use. The P7 controller spec then bridges P0 (knobs) + P1 (tier) + P2/P3 (gated NMS toggles) into a complete self-tuning loop.
