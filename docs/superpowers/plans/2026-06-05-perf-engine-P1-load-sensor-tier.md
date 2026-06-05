# Perf-engine P1 — Load Sensor + Tier Classifier Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the multi-signal load sensor + 5-tier state machine (GREEN/YELLOW/ORANGE/RED/EMERGENCY) with dwell+band hysteresis. Pull-only API; `/perf tier` and `/perf sensors` commands; boot-smoke harness.

**Architecture:** New package `dev.iyanz.sourbycraft.perf.sensor`. `Tier` enum + `SensorSnapshot` record + `PerfSensor` static utility. Sensor ticks every 20 server ticks (1s) from `MinecraftServer.tickChildren` via NMS hook patch (mirrors existing BossBarTicker pattern). Reads TPS rolling / MSPT / mem% / GC pause-ms-per-min, classifies tier with dwell+band hysteresis, publishes immutable `SensorSnapshot` via volatile field. yml-tunable thresholds.

**Tech Stack:** Java (Paper fork, mojmap), gradle (Kotlin DSL), bash smoke harness, RCON for command-output assertions. No JUnit added — verification via boot smoke at `test-harness/TestServer-mojmap/`.

**Spec:** `docs/superpowers/specs/2026-06-05-perf-engine-P1-load-sensor-tier-design.md` (committed `a7ae43d`).

---

## Deviations / Adaptations From Spec (documented; no spec change)

1. **No existing `DynamicPerformanceScaler.tick(server)` call site.** Spec hinted P1 could insert next to it, but `DynamicPerformanceScaler.tick` is defined and never called anywhere in the repo (confirmed by grep). P1 establishes a NEW call site in `net/minecraft/server/MinecraftServer.java#tickChildren` via patch, mirroring the existing `BossBarTicker.tick()` hook (`patches/minecraft/0031-SourbyCraft-v9.23-BossBar-ticker-NMS-hook.patch`).
2. **`build.gradle.kts` patch numbering.** Next free patch number under `patches/buildscript/server/` is `0015` (last used: `0014-p0KnobSmokeTest`).
3. **`SourbyCraftConfig.ymlDouble`** already exists (US Foundation added it). Re-use as-is for threshold reads.
4. **Logging:** Use `SourbyLogger.info(String)` / `.warn(String)` / `.error(String, Throwable)` — single-arg, string concatenation.
5. **Threshold-monotonicity validation.** Spec requires loadFromYml to check that within each signal, thresholds escalate worse → worse (`tpsThresholds` decreasing; `mspt`/`mem`/`gc` increasing). On violation: WARN + restore hardcoded defaults for that signal.

---

## File Structure

**Created:**
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/Tier.java` — enum, 5 values
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/SensorSnapshot.java` — immutable record
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/PerfSensor.java` — static utility, reads signals + classifies tier
- `patches/minecraft/00NN-SourbyCraft-perf-engine-P1-sensor-tick-hook.patch` — adds `PerfSensor.tick(this)` call in `MinecraftServer.tickChildren` next to existing BossBarTicker hook
- `patches/buildscript/server/0015-p1TierSmokeTest-gradle-task.patch` — gradle task registration
- `test-harness/scripts/p1-tier-smoke.sh` — boot-smoke harness with 7 scenarios

**Modified:**
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` — add `PerfSensor.loadFromYml()` call after existing `Knobs.loadFromYml()` (wrapped in try/catch)
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java` — add `Commands.literal("tier")` + `Commands.literal("sensors")` subtrees
- `sourbycraft-server/src/main/resources/sourbycraft.yml` — append `sensor:` block under existing `perf:` section
- `.github/workflows/nms-compat.yml` — add `perf/sensor/**` and `p1-tier-smoke.sh` to path filter; add `Run perf-engine P1 tier smoke` step + upload-on-failure step

---

## TDD adaptation

Identical to P0 model — smoke-only, no JUnit. Each phase adds one or more smoke scenarios; the scenarios are written first, observed to fail (because code not in place), then implementation makes them pass. Cheap compile gate (`./gradlew :sourbycraft-server:classes`) between code edits and full smoke (`./gradlew p1TierSmokeTest -PrunP1TierSmoke=true`).

---

## Task 1: Smoke harness skeleton + gradle task

**Goal:** Land the smoke script + gradle task before any production code. SCENARIO_0_BOOT only — boots existing jar, asserts `Done (`. Establishes failing-test surface for Tasks 2-6.

**Files:**
- Create: `test-harness/scripts/p1-tier-smoke.sh`
- Create: `patches/buildscript/server/0015-p1TierSmokeTest-gradle-task.patch`

- [ ] **Step 1: Write `test-harness/scripts/p1-tier-smoke.sh`**

```bash
#!/usr/bin/env bash
# Perf-engine P1 — load sensor + tier classifier boot smoke.
# Runs 7 scenarios (0_boot_sanity, 1_default_stays_green, 2_force_yellow,
# 3_dwell_prevents, 4_force_emergency, 5_non_monotonic_warn, 6_sensor_disabled,
# 7_perf_sensors_cmd) against test-harness/TestServer-mojmap/, each with a
# different operator sourbycraft.yml sensor-block override.
#
# Exit codes:
#   0 = all scenarios PASS
#   1 = missing release jar
#   2 = server died before Done (
#   3 = boot timeout
#   4 = logre assertion failed inside boot_and_assert
#   5 = scenario 1 unexpected transition fired
#   6 = scenario 3 dwell did not block transition
#   7 = scenario 5 missing non-monotonic WARN
#   8 = scenario 6 transition fired with sensor disabled
#   9 = scenario 7 cmd output missing required substring

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
HARNESS_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
ROOT_DIR="$( cd "$HARNESS_DIR/.." && pwd )"

JAR_SRC="$ROOT_DIR/release/SourbyCraft-v12-REL.jar"
TS_DIR="$HARNESS_DIR/TestServer-mojmap"
PORT=25600
RCON_PORT=25675
RCON_PASS=p1test

if [[ ! -f "$JAR_SRC" ]]; then
    echo "ERROR: $JAR_SRC missing. Run gradle assembleReleaseArtifacts first." >&2
    exit 1
fi

mkdir -p "$TS_DIR/plugins/SourbyCraft"
cp "$JAR_SRC" "$TS_DIR/server.jar"
echo "eula=true" > "$TS_DIR/eula.txt"

# Seed server.properties (merge style: keep prior keys not in our list)
seed_server_properties() {
    local sp="$TS_DIR/server.properties"
    local tmp="$sp.seed"
    {
        echo "server-port=$PORT"
        echo "online-mode=false"
        echo "enable-rcon=true"
        echo "rcon.port=$RCON_PORT"
        echo "rcon.password=$RCON_PASS"
        echo "broadcast-rcon-to-ops=false"
    } > "$tmp"
    if [[ -f "$sp" ]]; then
        grep -v -E '^(server-port|online-mode|enable-rcon|rcon\.port|rcon\.password|broadcast-rcon-to-ops)=' "$sp" >> "$tmp" || true
    fi
    mv "$tmp" "$sp"
}
seed_server_properties

# Cleanup on any exit
cleanup() {
    if [[ -f "$TS_DIR/.server.pid" ]]; then
        local pid
        pid=$(cat "$TS_DIR/.server.pid")
        kill -TERM "$pid" 2>/dev/null || true
        sleep 3
        kill -KILL "$pid" 2>/dev/null || true
        rm -f "$TS_DIR/.server.pid"
    fi
    rm -f "$TS_DIR/sourbycraft.yml.seed" "$TS_DIR/server.properties.seed"
}
trap cleanup EXIT INT TERM

# Reset sourbycraft.yml to a known baseline (empty file = use jar defaults entirely)
seed_sourbycraft_baseline() {
    : > "$TS_DIR/sourbycraft.yml"
}

# RCON via Python (no external deps)
rcon_cmd() {
    local cmd="$1"
    python3 - <<PYEOF
import socket, struct
s = socket.create_connection(("127.0.0.1", $RCON_PORT), timeout=5)
def send(req_id, kind, body):
    pkt = struct.pack('<ii', req_id, kind) + body.encode('utf-8') + b'\x00\x00'
    s.sendall(struct.pack('<i', len(pkt)) + pkt)
def recv():
    ln = struct.unpack('<i', s.recv(4))[0]
    data = s.recv(ln)
    req_id, kind = struct.unpack('<ii', data[:8])
    return req_id, kind, data[8:-2].decode('utf-8', errors='replace')
send(1, 3, "$RCON_PASS")
recv()
send(2, 2, "$cmd")
_, _, body = recv()
print(body)
s.close()
PYEOF
}

boot_and_assert() {
    local scenario="$1"
    local logre="$2"                          # boot.log regex (grep -E); empty = no log assertion
    local sourbycraft_override="${3:-}"       # full content for sourbycraft.yml; empty = baseline

    echo "p1-tier-smoke: scenario=$scenario"

    seed_sourbycraft_baseline
    if [[ -n "$sourbycraft_override" ]]; then
        printf '%s\n' "$sourbycraft_override" > "$TS_DIR/sourbycraft.yml"
    fi

    cd "$TS_DIR"
    rm -f boot.log
    java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
    local pid=$!
    echo "$pid" > .server.pid

    local deadline=$(($(date +%s) + 90))
    local ok=0
    while [[ $(date +%s) -lt $deadline ]]; do
        if grep -q "Done (" boot.log 2>/dev/null; then ok=1; break; fi
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "ERROR: scenario=$scenario server died before Done (" >&2
            tail -50 boot.log >&2
            exit 2
        fi
        sleep 2
    done
    if [[ $ok -eq 0 ]]; then
        echo "ERROR: scenario=$scenario BOOT_TIMEOUT after 90s" >&2
        tail -50 boot.log >&2
        exit 3
    fi

    if [[ -n "$logre" ]]; then
        if ! grep -E -q "$logre" boot.log; then
            echo "ERROR: scenario=$scenario log assertion failed; expected regex: $logre" >&2
            tail -100 boot.log >&2
            exit 4
        fi
    fi

    # Sleep 10s post-boot to let sensor samples accumulate before any caller-side assertions run
    sleep 10

    echo "p1-tier-smoke: scenario=$scenario PASS"
}

# === SCENARIO_0_BOOT (Task 1) ===
boot_and_assert "0_boot_sanity" "" ""

# === SCENARIO_1_DEFAULT_STAYS_GREEN (added in Task 3) ===
# === SCENARIO_2_FORCE_YELLOW_VIA_MSPT (added in Task 3) ===
# === SCENARIO_3_DWELL_PREVENTS_TRANSIENT (added in Task 5) ===
# === SCENARIO_4_FORCE_EMERGENCY_VIA_MEM (added in Task 5) ===
# === SCENARIO_5_NON_MONOTONIC_WARN (added in Task 5) ===
# === SCENARIO_6_SENSOR_DISABLED (added in Task 5) ===
# === SCENARIO_7_PERF_SENSORS_CMD (added in Task 4) ===

echo "p1-tier-smoke: all scenarios PASS"
```

- [ ] **Step 2: Make script executable**

```bash
chmod +x test-harness/scripts/p1-tier-smoke.sh
```

- [ ] **Step 3: Create `patches/buildscript/server/0015-p1TierSmokeTest-gradle-task.patch`**

Mirror the existing `patches/buildscript/server/0014-p0KnobSmokeTest-gradle-task.patch` exactly. Generate the patch with:

```bash
cat patches/buildscript/server/0014-p0KnobSmokeTest-gradle-task.patch
```

Copy its format. The added block in `sourbycraft-server/buildscript/build.gradle.kts` (at the end of the file, after the existing `if (runP0KnobSmoke) { tasks.register<Exec>("p0KnobSmokeTest") { ... } }` block) should be:

```kotlin
val runP1TierSmoke = providers.gradleProperty("runP1TierSmoke").map { it.toBoolean() }.getOrElse(false)
if (runP1TierSmoke) {
    tasks.register<Exec>("p1TierSmokeTest") {
        group = "verification"
        description = "Boot SourbyCraft jar with perf-engine P1 tier sensor scenarios; assert via boot.log + RCON"
        dependsOn(":assembleReleaseArtifacts")
        workingDir = rootProject.rootDir
        commandLine("bash", rootProject.file("test-harness/scripts/p1-tier-smoke.sh").absolutePath)
    }
}
```

Apply the gradle file edit (since `sourbycraft-server/build.gradle.kts` is a gitignored symlink target, you edit `sourbycraft-server/buildscript/build.gradle.kts` directly and ALSO generate a patch file under `patches/buildscript/server/0015-p1TierSmokeTest-gradle-task.patch`). To generate the patch from the existing 0014 as template:

```bash
sed 's/p0KnobSmokeTest/p1TierSmokeTest/g; s/runP0KnobSmoke/runP1TierSmoke/g; s/perf knob registry scenarios/perf-engine P1 tier sensor scenarios/g; s|test-harness/scripts/p0-knob-smoke.sh|test-harness/scripts/p1-tier-smoke.sh|g' patches/buildscript/server/0014-p0KnobSmokeTest-gradle-task.patch > patches/buildscript/server/0015-p1TierSmokeTest-gradle-task.patch
```

Then visually inspect the resulting patch to confirm hunks apply cleanly against the post-Task-1 `buildscript/build.gradle.kts`. If the patch context drifted, regenerate via `git diff > 0015-...patch` after editing the file directly.

- [ ] **Step 4: Apply patch to working tree**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. The new patch applies. If the patch fails, fix and regenerate.

- [ ] **Step 5: Verify gradle task is registered**

```bash
./gradlew tasks --all -PrunP1TierSmoke=true 2>&1 | grep p1TierSmokeTest
```

Expected: one line `p1TierSmokeTest - Boot SourbyCraft jar with perf-engine P1 tier sensor scenarios; assert via boot.log + RCON`.

- [ ] **Step 6: Build release jar (smoke prerequisite)**

```bash
./gradlew assembleReleaseArtifacts
```

Expected: BUILD SUCCESSFUL. Takes ~1m.

- [ ] **Step 7: Run smoke — must PASS SCENARIO_0_BOOT**

```bash
./gradlew p1TierSmokeTest -PrunP1TierSmoke=true
```

Expected:
```
p1-tier-smoke: scenario=0_boot_sanity PASS
p1-tier-smoke: all scenarios PASS
BUILD SUCCESSFUL
```

If FAIL: read `test-harness/TestServer-mojmap/boot.log` tail. Common causes match Task 1 of P0: stale server on port 25600, EULA missing, JAR not built.

- [ ] **Step 8: Commit**

```bash
git add -f test-harness/scripts/p1-tier-smoke.sh patches/buildscript/server/0015-p1TierSmokeTest-gradle-task.patch
git add sourbycraft-server/buildscript/build.gradle.kts || true   # symlink target may or may not be tracked depending on .gitignore
git commit -m "test: perf-engine P1 — tier smoke skeleton (boot sanity scenario)"
```

---

## Task 2: Tier enum + SensorSnapshot record

**Goal:** Land the pure data types. Compile-only validation; no behavior, no smoke change.

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/Tier.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/SensorSnapshot.java`

- [ ] **Step 1: Create `Tier.java`**

```java
package dev.iyanz.sourbycraft.perf.sensor;

/**
 * Server load tier — worse = higher ordinal. Used by PerfSensor's classifier and by
 * downstream consumers (P7 controller, P8 telemetry). Cardinality is fixed; do not
 * insert new values mid-list without auditing every Tier.ordinal()-indexed array.
 */
public enum Tier {
    GREEN,
    YELLOW,
    ORANGE,
    RED,
    EMERGENCY;

    /** {@code true} if this tier represents worse load than {@code other}. */
    public boolean isWorseThan(Tier other) {
        return this.ordinal() > other.ordinal();
    }

    /** Returns whichever of {@code this} or {@code other} represents worse load. */
    public Tier worse(Tier other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }
}
```

- [ ] **Step 2: Create `SensorSnapshot.java`**

```java
package dev.iyanz.sourbycraft.perf.sensor;

/**
 * Immutable point-in-time reading of all 4 load signals + computed tier + dwell state.
 * Produced once per sensor tick (1s cadence at default cadence-ticks=20). Published via
 * a volatile field on PerfSensor; consumers see the latest visible snapshot via volatile
 * semantics. Record is the canonical pull-API payload.
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
    /** Snapshot returned before the first sensor tick has run. All signals zero; tier GREEN. */
    public static final SensorSnapshot INITIAL = new SensorSnapshot(
        0L, 20.0, 20.0, 20.0, 0.0, 0.0, 0.0, Tier.GREEN, Tier.GREEN, 0
    );
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew :sourbycraft-server:classes
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/Tier.java \
        sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/SensorSnapshot.java
git commit -m "feat: perf-engine P1 — Tier enum + SensorSnapshot record"
```

---

## Task 3: PerfSensor + yml load + tick wiring + default-stays-green scenario

**Goal:** Land the bulk of P1: `PerfSensor.java` with all signal readers + classifier + dwell+band hysteresis + yml load + `tick(server)`. Wire `loadFromYml()` into `SourbyCraftConfig.init()`. Wire `tick(server)` into `MinecraftServer.tickChildren` via a new NMS patch. Add SCENARIO_1_DEFAULT_STAYS_GREEN + SCENARIO_2_FORCE_YELLOW to smoke.

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/PerfSensor.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
- Modify: `sourbycraft-server/src/main/resources/sourbycraft.yml`
- Create: `patches/minecraft/00NN-SourbyCraft-perf-engine-P1-sensor-tick-hook.patch` (NN = next free integer; check `ls patches/minecraft/ | tail -3`)
- Modify: `test-harness/scripts/p1-tier-smoke.sh`

- [ ] **Step 1: Locate next free patch number under `patches/minecraft/`**

```bash
ls patches/minecraft/ | sort | tail -3
```

Pick the next integer (e.g. if last is `0034`, use `0035`). Substitute `00NN` with the actual number throughout this task.

- [ ] **Step 2: Add SCENARIO_1_DEFAULT_STAYS_GREEN + SCENARIO_2_FORCE_YELLOW to smoke**

Edit `test-harness/scripts/p1-tier-smoke.sh`. Replace the comments `# === SCENARIO_1_DEFAULT_STAYS_GREEN (added in Task 3) ===` and `# === SCENARIO_2_FORCE_YELLOW_VIA_MSPT (added in Task 3) ===` with:

```bash
# === SCENARIO_1_DEFAULT_STAYS_GREEN ===
# No yml override. Default thresholds. Idle server should stay in GREEN through the
# 10s observation window inside boot_and_assert.
boot_and_assert "1_default_stays_green" "" ""
if grep -E -q "perf tier transition" "$TS_DIR/boot.log"; then
    echo "ERROR: scenario=1 unexpected tier transition in idle boot" >&2
    grep "perf tier transition" "$TS_DIR/boot.log" >&2
    exit 5
fi
# Verify RCON /perf tier returns GREEN
TIER_OUT=$(rcon_cmd "perf tier")
if ! echo "$TIER_OUT" | grep -E -q "Tier:\s+GREEN"; then
    echo "ERROR: scenario=1 /perf tier did not return GREEN. Output:" >&2
    echo "$TIER_OUT" >&2
    exit 5
fi

# === SCENARIO_2_FORCE_YELLOW_VIA_MSPT ===
# Lower MSPT.yellow threshold to 0.001 ms so every server tick exceeds it.
# dwell-samples=1 so transition fires after the first sample.
SCENARIO_2_YML='perf:
  sensor:
    enabled: true
    dwell-samples: 1
    thresholds:
      mspt:
        yellow: 0.001
        orange: 1000
        red: 1000
        emergency: 1000'
boot_and_assert "2_force_yellow_via_mspt" \
  "perf tier transition: GREEN -> YELLOW" \
  "$SCENARIO_2_YML"
```

- [ ] **Step 3: Run smoke — SCENARIO_1 should PASS, SCENARIO_2 should FAIL**

```bash
./gradlew assembleReleaseArtifacts && ./gradlew p1TierSmokeTest -PrunP1TierSmoke=true
```

Expected:
- SCENARIO_0_BOOT PASS
- SCENARIO_1_DEFAULT_STAYS_GREEN FAIL on `/perf tier` (cmd doesn't exist yet) — but actually the boot.log transition check passes (no PerfSensor → no transitions ever). Need to handle: SCENARIO_1's `rcon_cmd "perf tier"` will respond `Unknown or incomplete command, see below for error` because `/perf tier` subcommand isn't registered until Task 4. That's expected fail.
- SCENARIO_2_FORCE_YELLOW_VIA_MSPT FAIL on boot.log assertion (no PerfSensor → no transition log line ever).

Both are the intended failing tests. Now implement.

NOTE: Tasks 3 + 4 land together as a tightly-coupled set; otherwise smoke can't go green. If you want one-commit-per-task discipline, split: in Task 3, comment out the `rcon_cmd "perf tier"` block of SCENARIO_1, and add a simpler positive assertion (e.g. the registry-loaded log line — see below). Then re-enable the RCON check in Task 4.

For SCENARIO_1 simpler positive assertion (during Task 3 — replaces the rcon_cmd block):

```bash
# Verify sensor module loaded at boot (loadFromYml ran without throwing)
if ! grep -E -q "perf sensor: cadence=20 dwell=3" "$TS_DIR/boot.log"; then
    echo "ERROR: scenario=1 sensor load log line missing" >&2
    exit 5
fi
```

(This log line gets emitted by `PerfSensor.loadFromYml()` in Step 5 below.) Re-enable the `/perf tier` RCON check in Task 4 Step 3.

- [ ] **Step 4: Create `PerfSensor.java`**

```java
package dev.iyanz.sourbycraft.perf.sensor;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import net.minecraft.server.MinecraftServer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Multi-signal load sensor for the SourbyCraft self-tuning perf-engine.
 * Reads TPS rolling / MSPT / mem% / GC pause-ms-per-min on a configurable cadence
 * (default 1s at 20 TPS), classifies into a 5-tier scale (GREEN/YELLOW/ORANGE/
 * RED/EMERGENCY) with dwell+band hysteresis, and publishes immutable
 * {@link SensorSnapshot} via a volatile field for pull-API consumers.
 *
 * <p>Sensor writes only from the main server thread ({@code tick(server)} is called
 * from {@code MinecraftServer.tickChildren}). All read paths are volatile-published
 * and lock-free.
 */
public final class PerfSensor {

    private PerfSensor() {}

    // --- Configuration (yml-overridable; written once at boot) ---
    private static volatile boolean enabled = true;
    private static volatile int cadenceTicks = 20;
    private static volatile int dwellSamples = 3;
    private static volatile double recoveryDwellMultiplier = 2.0;

    // Threshold arrays indexed by Tier.ordinal(). Index 0 (GREEN) is a sentinel boundary.
    // tpsThresholds: lower value is worse — entry at index N means "value below this -> at least Tier.values()[N]".
    // msptThresholds / memThresholds / gcMsThresholds: higher value is worse.
    private static volatile double[] tpsThresholds   = {Double.MAX_VALUE, 19.5, 18.0, 15.0, 10.0};
    private static volatile double[] msptThresholds  = {Double.MIN_VALUE, 30.0, 40.0, 60.0, 100.0};
    private static volatile double[] memThresholds   = {Double.MIN_VALUE, 75.0, 85.0, 92.0, 97.0};
    private static volatile double[] gcMsThresholds  = {Double.MIN_VALUE, 20.0, 50.0, 100.0, 300.0};

    // --- Runtime state (main-thread writes only; volatile reads for snapshot publication) ---
    private static int tickCounter = 0;
    private static Tier currentTier = Tier.GREEN;
    private static Tier candidateTier = Tier.GREEN;
    private static int dwellCount = 0;
    private static long tierSinceNanos = 0L;
    private static volatile SensorSnapshot lastSnapshot = SensorSnapshot.INITIAL;

    // --- JMX GC bean cache + per-minute pause ring ---
    private static List<GarbageCollectorMXBean> gcBeans;
    private static long gcPauseTotalMsAtLastSecond = 0L;
    private static final long[] gcPauseMsRing = new long[60];
    private static int gcPauseRingIdx = 0;

    /** Load configuration from sourbycraft.yml (JAR-baked). Call from SourbyCraftConfig.init(). */
    public static void loadFromYml() {
        if (!SourbyCraftConfig.ymlBool("perf.sensor.enabled", true)) {
            enabled = false;
            SourbyLogger.info("perf sensor: disabled via yml");
            return;
        }
        cadenceTicks = clampInt(SourbyCraftConfig.ymlInt("perf.sensor.cadence-ticks", 20), 1, "cadence-ticks");
        dwellSamples = clampInt(SourbyCraftConfig.ymlInt("perf.sensor.dwell-samples", 3), 1, "dwell-samples");
        double mult = SourbyCraftConfig.ymlDouble("perf.sensor.recovery-dwell-multiplier", 2.0);
        if (Double.isNaN(mult) || mult < 1.0) {
            SourbyLogger.warn("perf sensor recovery-dwell-multiplier " + mult + " < 1.0, clamping to 1.0");
            mult = Math.max(1.0, Double.isNaN(mult) ? 2.0 : mult);
        }
        recoveryDwellMultiplier = mult;

        loadThresholds("tps",            tpsThresholds,  /*lowerIsWorse*/ true);
        loadThresholds("mspt",           msptThresholds, false);
        loadThresholds("mem",            memThresholds,  false);
        loadThresholds("gc-ms-per-min",  gcMsThresholds, false);

        SourbyLogger.info("perf sensor: cadence=" + cadenceTicks + " dwell=" + dwellSamples
            + " recovery-mult=" + recoveryDwellMultiplier);
    }

    private static int clampInt(int v, int min, String name) {
        if (v < min) {
            SourbyLogger.warn("perf sensor " + name + " " + v + " < " + min + ", clamping");
            return min;
        }
        return v;
    }

    private static void loadThresholds(String signal, double[] dst, boolean lowerIsWorse) {
        String[] tierKeys = {null, "yellow", "orange", "red", "emergency"};
        double[] candidate = dst.clone();
        for (int i = 1; i < 5; i++) {
            String path = "perf.sensor.thresholds." + signal + "." + tierKeys[i];
            candidate[i] = SourbyCraftConfig.ymlDouble(path, candidate[i]);
        }
        if (!isMonotonic(candidate, lowerIsWorse)) {
            SourbyLogger.warn("sensor threshold '" + signal + "' non-monotonic, reverting to defaults");
            return; // dst keeps hardcoded defaults
        }
        for (int i = 1; i < 5; i++) dst[i] = candidate[i];
    }

    private static boolean isMonotonic(double[] arr, boolean lowerIsWorse) {
        // For lowerIsWorse: arr[1] (yellow) > arr[2] (orange) > arr[3] (red) > arr[4] (emergency).
        // For higherIsWorse: arr[1] < arr[2] < arr[3] < arr[4].
        for (int i = 2; i < 5; i++) {
            if (lowerIsWorse) {
                if (arr[i] >= arr[i - 1]) return false;
            } else {
                if (arr[i] <= arr[i - 1]) return false;
            }
        }
        return true;
    }

    /** Main-thread entry. Called every server tick from MinecraftServer.tickChildren. */
    public static void tick(MinecraftServer server) {
        if (!enabled) return;
        if (++tickCounter < cadenceTicks) return;
        tickCounter = 0;

        double tps1s  = tpsFromNanos(server.getAverageTickTimeNanos());
        double tps30s = paperTpsAvg(server, 30);
        double tps5m  = paperTpsAvg(server, 300);
        double mspt   = server.getAverageTickTimeNanos() / 1_000_000.0;
        double memPct = memUsagePercent();
        double gcMs   = gcMsLastMinute();

        Tier reading = classifyAll(tps1s, mspt, memPct, gcMs);

        // Hysteresis: dwell + band (recovery requires multiplier × dwell samples)
        if (reading == currentTier) {
            candidateTier = currentTier;
            dwellCount = 0;
        } else if (reading == candidateTier) {
            int requiredDwell = reading.isWorseThan(currentTier)
                ? dwellSamples
                : (int) Math.ceil(dwellSamples * recoveryDwellMultiplier);
            dwellCount++;
            if (dwellCount >= requiredDwell) transition(reading);
        } else {
            candidateTier = reading;
            dwellCount = 1;
        }

        lastSnapshot = new SensorSnapshot(
            System.nanoTime(), tps1s, tps30s, tps5m, mspt, memPct, gcMs,
            currentTier, candidateTier, dwellCount
        );
    }

    // --- Classifier ---
    private static Tier classifyAll(double tps, double mspt, double memPct, double gcMs) {
        Tier t = classifySignal(tps,    tpsThresholds,   /*lowerIsWorse*/ true);
        t = t.worse(classifySignal(mspt,   msptThresholds,  false));
        t = t.worse(classifySignal(memPct, memThresholds,   false));
        t = t.worse(classifySignal(gcMs,   gcMsThresholds,  false));
        return t;
    }

    private static Tier classifySignal(double value, double[] thresholds, boolean lowerIsWorse) {
        if (Double.isNaN(value)) return Tier.GREEN;
        for (int i = 4; i >= 1; i--) {
            boolean exceed = lowerIsWorse ? (value < thresholds[i]) : (value > thresholds[i]);
            if (exceed) return Tier.values()[i];
        }
        return Tier.GREEN;
    }

    private static void transition(Tier newTier) {
        Tier old = currentTier;
        currentTier = newTier;
        dwellCount = 0;
        tierSinceNanos = System.nanoTime();
        SourbyLogger.info("perf tier transition: " + old + " -> " + newTier
            + " (after dwell=" + dwellSamples + " sample(s))");
    }

    // --- Signal readers ---
    private static double tpsFromNanos(long nanos) {
        return nanos > 0 ? Math.min(20.0, 1_000_000_000.0 / nanos) : 20.0;
    }

    private static double paperTpsAvg(MinecraftServer server, int windowSeconds) {
        // Paper exposes recentTps as {tps1m, tps5m, tps15m}. Map windowSeconds approximately.
        double[] r = server.recentTps;
        if (r == null || r.length < 3) return tpsFromNanos(server.getAverageTickTimeNanos());
        if (windowSeconds <= 60)  return r[0];
        if (windowSeconds <= 300) return r[1];
        return r[2];
    }

    private static double memUsagePercent() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        return max > 0 ? 100.0 * used / max : 0.0;
    }

    private static double gcMsLastMinute() {
        if (gcBeans == null) gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long total = 0;
        for (GarbageCollectorMXBean b : gcBeans) total += b.getCollectionTime();
        long delta = total - gcPauseTotalMsAtLastSecond;
        gcPauseTotalMsAtLastSecond = total;
        if (delta < 0) delta = 0; // defensive: handles unlikely bean reset
        gcPauseMsRing[gcPauseRingIdx] = delta;
        gcPauseRingIdx = (gcPauseRingIdx + 1) % gcPauseMsRing.length;
        long sum = 0;
        for (long v : gcPauseMsRing) sum += v;
        return sum;
    }

    // --- Public read API ---
    public static Tier currentTier() { return lastSnapshot.tier(); }
    public static SensorSnapshot snapshot() { return lastSnapshot; }
    public static boolean isEnabled() { return enabled; }

    /** Time in nanoseconds since the last tier transition. 0 if no transition has occurred yet. */
    public static long timeInTierNanos() {
        return tierSinceNanos == 0 ? 0 : System.nanoTime() - tierSinceNanos;
    }

    /** Returns a defensive clone of the threshold array for the named signal. */
    public static double[] thresholdsFor(String signal) {
        return switch (signal) {
            case "tps"             -> tpsThresholds.clone();
            case "mspt"            -> msptThresholds.clone();
            case "mem"             -> memThresholds.clone();
            case "gc-ms-per-min"   -> gcMsThresholds.clone();
            default -> throw new IllegalArgumentException("unknown signal: " + signal);
        };
    }
}
```

- [ ] **Step 5: Append `sensor:` block to `sourbycraft.yml`**

Open `sourbycraft-server/src/main/resources/sourbycraft.yml`. Find the existing `perf:` block (added by P0). After the `entity-tick-rate: 20` line, append (note: 2-space indent under `perf:`):

```yaml
  sensor:
    # Multi-signal load sensor feeding the 5-tier state machine
    # (GREEN/YELLOW/ORANGE/RED/EMERGENCY). Runs on the main thread every
    # cadence-ticks ticks. P7 controller will read PerfSensor.currentTier().
    enabled: true
    cadence-ticks: 20                # 1s at 20 TPS
    dwell-samples: 3                 # samples in candidate tier required before escalation
    recovery-dwell-multiplier: 2.0   # recovery requires dwell-samples * multiplier samples
    thresholds:
      # TPS: lower value escalates worse. yellow=19.5 means TPS<19.5 -> at-least YELLOW.
      tps:           { yellow: 19.5, orange: 18.0, red: 15.0, emergency: 10.0 }
      # MSPT / mem / GC: higher value escalates worse.
      mspt:          { yellow: 30,   orange: 40,   red: 60,   emergency: 100 }
      mem:           { yellow: 75,   orange: 85,   red: 92,   emergency: 97 }
      gc-ms-per-min: { yellow: 20,   orange: 50,   red: 100,  emergency: 300 }
```

- [ ] **Step 6: Wire `PerfSensor.loadFromYml()` into `SourbyCraftConfig.init()`**

Open `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`. Find the existing line:

```java
        dev.iyanz.sourbycraft.perf.knob.Knobs.loadFromYml();
```

Add immediately AFTER it (wrap in try/catch so a sensor-config bug never blocks boot):

```java
        try {
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.loadFromYml();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("PerfSensor.loadFromYml failed; using defaults", t);
        }
```

- [ ] **Step 7: Create `patches/minecraft/00NN-SourbyCraft-perf-engine-P1-sensor-tick-hook.patch`**

Mirror the existing BossBarTicker hook at `patches/minecraft/0031-SourbyCraft-v9.23-BossBar-ticker-NMS-hook.patch`. Read that patch as a template:

```bash
cat patches/minecraft/0031-SourbyCraft-v9.23-BossBar-ticker-NMS-hook.patch
```

The new patch must insert into `net/minecraft/server/MinecraftServer.java#tickChildren`, AFTER the existing BossBarTicker block, this snippet:

```java
        // SourbyCraft start - perf-engine P1 sensor tick (NMS-driven, main thread)
        try { dev.iyanz.sourbycraft.perf.sensor.PerfSensor.tick(this); }
        catch (Throwable t) { /* ignore - never fail tickChildren */ }
        // SourbyCraft end - perf-engine P1 sensor tick
```

To create the patch: apply the existing patches with `./gradlew applyAllPatches --offline`, edit `net/minecraft/server/MinecraftServer.java` directly under the paperweight checkout (the path will be inside `.gradle/caches/paperweight/.../sources/` or wherever the paperweight workflow lands the source — check `git status` and `rebuildAllPatches`-style flow), then run `./gradlew rebuildPaperPatches` (or whatever the project's name is — check `./gradlew tasks --group paperweight`) to materialize the new `.patch` file.

If the precise paperweight invocation differs, follow the repo's existing pattern documented in any `docs/superpowers/notes/*` file or README. The accept criterion is: `patches/minecraft/00NN-SourbyCraft-perf-engine-P1-sensor-tick-hook.patch` exists and applies cleanly.

- [ ] **Step 8: Apply patches + compile**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -10
./gradlew :sourbycraft-server:classes 2>&1 | tail -5
```

Expected: both BUILD SUCCESSFUL.

If apply fails: the new patch is malformed; regenerate via paperweight.
If compile fails: the new code references something incorrectly; read the error and fix.

- [ ] **Step 9: Rebuild release jar**

```bash
./gradlew assembleReleaseArtifacts
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Run smoke — SCENARIO_1 + SCENARIO_2 should now PASS**

```bash
./gradlew p1TierSmokeTest -PrunP1TierSmoke=true
```

Expected:
```
p1-tier-smoke: scenario=0_boot_sanity PASS
p1-tier-smoke: scenario=1_default_stays_green PASS
p1-tier-smoke: scenario=2_force_yellow_via_mspt PASS
p1-tier-smoke: all scenarios PASS
```

If SCENARIO_2 fails because the GREEN→YELLOW transition log line is absent: the sensor isn't running. Inspect `boot.log` for `perf sensor: cadence=20 dwell=3` (proves `loadFromYml()` ran). If absent, the wire-up in `SourbyCraftConfig.init()` (Step 6) didn't take effect. If present but no transition, the NMS hook (Step 7) didn't take effect.

- [ ] **Step 11: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/PerfSensor.java \
        sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java \
        sourbycraft-server/src/main/resources/sourbycraft.yml \
        test-harness/scripts/p1-tier-smoke.sh \
        patches/minecraft/00NN-SourbyCraft-perf-engine-P1-sensor-tick-hook.patch
git commit -m "feat: perf-engine P1 — PerfSensor with 4 signals + dwell+band hysteresis

Adds dev.iyanz.sourbycraft.perf.sensor.PerfSensor: reads TPS rolling /
MSPT / mem% / GC pause-ms-per-min on a configurable cadence (default 1s
at 20 TPS), classifies into a 5-tier state machine with dwell+band
hysteresis (recovery requires dwell-samples × recovery-dwell-multiplier
samples). yml-tunable thresholds under perf.sensor.*. PerfSensor.tick(server)
hooked into MinecraftServer.tickChildren via NMS patch (same pattern as
BossBarTicker). PerfSensor.loadFromYml() called from SourbyCraftConfig.init()
wrapped in try/catch so a config bug never blocks boot. Pull-only API:
currentTier(), snapshot(), thresholdsFor(signal)."
```

---

## Task 4: `/perf tier` + `/perf sensors` subcommands + SCENARIO_7

**Goal:** Add Brigadier subcommands to existing `PerfCommand`. Restore SCENARIO_1's `/perf tier` RCON assertion (commented out in Task 3) + add SCENARIO_7 `/perf sensors` substring check.

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java`
- Modify: `test-harness/scripts/p1-tier-smoke.sh`

- [ ] **Step 1: Inspect current PerfCommand structure**

```bash
cat sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java
```

Note the import block + the existing `register(CommandDispatcher<CommandSourceStack> dispatcher)` method shape. Subcommands are added via `.then(Commands.literal(...))`.

- [ ] **Step 2: Add `tier` subcommand**

Edit the existing `dispatcher.register(Commands.literal("perf") ... )` chain. Right before the closing `);` of the chain, add a sibling `.then(...)`:

```java
            .then(Commands.literal("tier")
                .executes(ctx -> showTier(ctx.getSource())))
```

Then add the handler method to the class:

```java
    private static int showTier(CommandSourceStack src) {
        if (!dev.iyanz.sourbycraft.perf.sensor.PerfSensor.isEnabled()) {
            src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[Perf] Sensor disabled (perf.sensor.enabled=false)"));
            return 0;
        }
        dev.iyanz.sourbycraft.perf.sensor.SensorSnapshot snap =
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.snapshot();
        long timeInTier = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.timeInTierNanos();
        long timeInTierSec = timeInTier / 1_000_000_000L;
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "[Perf] Tier: " + snap.tier()));
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "[Perf] Candidate: " + snap.candidateTier() + " (" + snap.dwellSamples() + " samples in candidate)"));
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "[Perf] Time in tier: " + timeInTierSec + "s"
                + (snap.timestampNanos() == 0L ? " (no samples yet)" : "")));
        return 1;
    }
```

- [ ] **Step 3: Add `sensors` subcommand**

Add a sibling `.then(...)` to the register chain:

```java
            .then(Commands.literal("sensors")
                .executes(ctx -> showSensors(ctx.getSource())))
```

Add the handler:

```java
    private static int showSensors(CommandSourceStack src) {
        if (!dev.iyanz.sourbycraft.perf.sensor.PerfSensor.isEnabled()) {
            src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[Perf] Sensor disabled (perf.sensor.enabled=false)"));
            return 0;
        }
        dev.iyanz.sourbycraft.perf.sensor.SensorSnapshot snap =
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.snapshot();
        double[] tpsT = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.thresholdsFor("tps");
        double[] msptT = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.thresholdsFor("mspt");
        double[] memT = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.thresholdsFor("mem");
        double[] gcT = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.thresholdsFor("gc-ms-per-min");
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            String.format("[Perf] TPS:  %.2f (1s)  %.2f (1m)  %.2f (5m)  thresholds Y/O/R/E: %.1f/%.1f/%.1f/%.1f",
                snap.tps1s(), snap.tps30s(), snap.tps5m(), tpsT[1], tpsT[2], tpsT[3], tpsT[4])));
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            String.format("[Perf] MSPT: %.1f ms  thresholds Y/O/R/E: %.0f/%.0f/%.0f/%.0f",
                snap.msptAvg(), msptT[1], msptT[2], msptT[3], msptT[4])));
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            String.format("[Perf] Mem:  %.0f%% used  thresholds Y/O/R/E: %.0f/%.0f/%.0f/%.0f",
                snap.memPct(), memT[1], memT[2], memT[3], memT[4])));
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            String.format("[Perf] GC:   %.0f ms/min  thresholds Y/O/R/E: %.0f/%.0f/%.0f/%.0f",
                snap.gcMsPerMin(), gcT[1], gcT[2], gcT[3], gcT[4])));
        return 1;
    }
```

- [ ] **Step 4: Restore SCENARIO_1's `/perf tier` RCON check**

Edit `test-harness/scripts/p1-tier-smoke.sh`. In SCENARIO_1_DEFAULT_STAYS_GREEN, remove the "simpler positive assertion" added in Task 3 Step 3 (the `grep -E "perf sensor: cadence=20"` block), and replace with the RCON check that was originally planned:

```bash
# Verify RCON /perf tier returns GREEN
TIER_OUT=$(rcon_cmd "perf tier")
if ! echo "$TIER_OUT" | grep -E -q "Tier:\s+GREEN"; then
    echo "ERROR: scenario=1 /perf tier did not return GREEN. Output:" >&2
    echo "$TIER_OUT" >&2
    exit 5
fi
```

- [ ] **Step 5: Add SCENARIO_7_PERF_SENSORS_CMD to smoke**

Replace the comment `# === SCENARIO_7_PERF_SENSORS_CMD (added in Task 4) ===` with:

```bash
# === SCENARIO_7_PERF_SENSORS_CMD ===
# Boot with defaults; verify /perf sensors output contains the required signal labels.
boot_and_assert "7_perf_sensors_cmd" "" ""
SENSORS_OUT=$(rcon_cmd "perf sensors")
for token in "TPS:" "MSPT:" "Mem:" "GC:"; do
    if ! echo "$SENSORS_OUT" | grep -F -q "$token"; then
        echo "ERROR: scenario=7 /perf sensors output missing token: $token" >&2
        echo "Output was:" >&2
        echo "$SENSORS_OUT" >&2
        exit 9
    fi
done
```

- [ ] **Step 6: Compile**

```bash
./gradlew :sourbycraft-server:classes
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Rebuild release jar + run smoke**

```bash
./gradlew assembleReleaseArtifacts
./gradlew p1TierSmokeTest -PrunP1TierSmoke=true
```

Expected: SCENARIO_0, 1, 2, 7 all PASS.

If SCENARIO_7 fails: inspect SENSORS_OUT in error message. The formatter strings in Step 3 must produce literal "TPS:", "MSPT:", "Mem:", "GC:" substrings — verify exact spelling.

If SCENARIO_1 fails: the `rcon_cmd "perf tier"` returns command-not-found. The Brigadier registration (Step 2) didn't take effect. Check that the new `.then(...)` is INSIDE the `register(...)` chain, not OUTSIDE.

- [ ] **Step 8: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java \
        test-harness/scripts/p1-tier-smoke.sh
git commit -m "feat: perf-engine P1 — /perf tier + /perf sensors subcommands

Adds Brigadier subcommands to existing /perf command. /perf tier shows
current Tier + candidate tier + dwell progress + time-in-tier. /perf
sensors shows raw 4-signal readings with their thresholds inline. Both
respect PerfSensor.isEnabled() and print 'Sensor disabled' when the
operator has set perf.sensor.enabled=false."
```

---

## Task 5: Forced-transition + boundary smoke scenarios

**Goal:** Add the 4 remaining scenarios (3 dwell, 4 emergency, 5 non-monotonic warn, 6 sensor disabled). No production code change — these exercise the existing PerfSensor implementation.

**Files:**
- Modify: `test-harness/scripts/p1-tier-smoke.sh`

- [ ] **Step 1: Replace remaining scenario placeholders**

Edit `test-harness/scripts/p1-tier-smoke.sh`. Replace the four remaining comment placeholders with active blocks:

```bash
# === SCENARIO_3_DWELL_PREVENTS_TRANSIENT ===
# MSPT threshold matched as in SCENARIO_2 (so every sample classifies YELLOW) but
# dwell-samples is set very high so no transition fires within the 10s observation
# window.
SCENARIO_3_YML='perf:
  sensor:
    enabled: true
    dwell-samples: 999
    thresholds:
      mspt:
        yellow: 0.001
        orange: 1000
        red: 1000
        emergency: 1000'
boot_and_assert "3_dwell_prevents_transient" "" "$SCENARIO_3_YML"
if grep -E -q "perf tier transition" "$TS_DIR/boot.log"; then
    echo "ERROR: scenario=3 transition fired despite dwell-samples=999" >&2
    grep "perf tier transition" "$TS_DIR/boot.log" >&2
    exit 6
fi

# === SCENARIO_4_FORCE_EMERGENCY_VIA_MEM ===
# Mem thresholds set so every tier escalates immediately. dwell=1, single sample
# triggers transition to EMERGENCY.
SCENARIO_4_YML='perf:
  sensor:
    enabled: true
    dwell-samples: 1
    thresholds:
      mem:
        yellow: 0.1
        orange: 0.2
        red: 0.3
        emergency: 0.5'
boot_and_assert "4_force_emergency_via_mem" \
  "-> EMERGENCY" \
  "$SCENARIO_4_YML"

# === SCENARIO_5_NON_MONOTONIC_WARN ===
# Operator misconfigures mspt thresholds (yellow > orange). Sensor loadFromYml
# detects, logs WARN, reverts to defaults.
SCENARIO_5_YML='perf:
  sensor:
    thresholds:
      mspt:
        yellow: 100
        orange: 50
        red: 60
        emergency: 80'
boot_and_assert "5_non_monotonic_warn" \
  "sensor threshold .mspt. non-monotonic, reverting to defaults" \
  "$SCENARIO_5_YML"

# === SCENARIO_6_SENSOR_DISABLED ===
# Operator disables sensor. Tier should remain GREEN forever (no transitions).
SCENARIO_6_YML='perf:
  sensor:
    enabled: false'
boot_and_assert "6_sensor_disabled" \
  "perf sensor: disabled via yml" \
  "$SCENARIO_6_YML"
if grep -E -q "perf tier transition" "$TS_DIR/boot.log"; then
    echo "ERROR: scenario=6 transition fired despite sensor disabled" >&2
    exit 8
fi
TIER_OUT=$(rcon_cmd "perf tier")
if ! echo "$TIER_OUT" | grep -F -q "Sensor disabled"; then
    echo "ERROR: scenario=6 /perf tier did not report 'Sensor disabled'. Output:" >&2
    echo "$TIER_OUT" >&2
    exit 8
fi
```

- [ ] **Step 2: Run full smoke — all 7 scenarios must PASS**

```bash
./gradlew p1TierSmokeTest -PrunP1TierSmoke=true
```

Expected: all 7 scenarios PASS.

If any FAIL, **STOP and report BLOCKED**. Do NOT add production code to make a smoke scenario pass — Task 5 adds smoke only. A failing scenario here signals a real bug introduced by Tasks 3-4; fix the underlying code.

Common causes for each scenario failure:
- SCENARIO_3 fails (transition fires): dwell logic is wrong. Inspect `PerfSensor.tick`'s `if (reading == candidateTier)` branch — does it correctly compare `dwellCount >= requiredDwell`?
- SCENARIO_4 fails (no EMERGENCY): mem reading reports 0% (Runtime.maxMemory()==0 or unusable). Check `memUsagePercent` boundary.
- SCENARIO_5 fails (no WARN line): non-monotonic detection is broken. Inspect `isMonotonic` logic.
- SCENARIO_6 fails (transition fires): `tick()` doesn't early-return when `!enabled`. Verify the first line of `tick()` is `if (!enabled) return;`.

- [ ] **Step 3: Commit**

```bash
git add test-harness/scripts/p1-tier-smoke.sh
git commit -m "test: perf-engine P1 — transition + boundary smoke scenarios

Adds SCENARIO_3_DWELL_PREVENTS_TRANSIENT (high dwell blocks transition
despite continuous threshold violation), SCENARIO_4_FORCE_EMERGENCY_VIA_MEM
(low mem thresholds force EMERGENCY tier), SCENARIO_5_NON_MONOTONIC_WARN
(misconfigured mspt thresholds emit WARN and revert to defaults),
SCENARIO_6_SENSOR_DISABLED (perf.sensor.enabled=false stops all transitions
and /perf tier reports disabled). No production code change."
```

---

## Task 6: CI gate path filter

**Goal:** Extend `.github/workflows/nms-compat.yml` to run `p1TierSmokeTest` on PRs that touch perf-engine P1 paths. Add step-id-gated upload-on-failure for the P1 boot log.

**Files:**
- Modify: `.github/workflows/nms-compat.yml`

- [ ] **Step 1: Add new path to `on.pull_request.paths`**

Open `.github/workflows/nms-compat.yml`. Find the existing path list. Add immediately after the existing `'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/**'` line (added by P0 Task 5):

(Confirm by `grep -n perf .github/workflows/nms-compat.yml` first — the line already exists from P0 and covers the new `perf/sensor/**` subdirectory. No new path needed unless you want to be more specific. Skip this step if `perf/**` is already in place; in that case proceed directly to Step 2.)

- [ ] **Step 2: Add `Run perf-engine P1 tier smoke` step with id**

Find the existing `Run perf-engine P0 knob smoke` step:

```yaml
      - name: Run perf-engine P0 knob smoke
        id: p0_knob_smoke
        run: ./gradlew :sourbycraft-server:p0KnobSmokeTest -PrunP0KnobSmoke=true
```

Add a sibling step AFTER it:

```yaml
      - name: Run perf-engine P1 tier smoke
        id: p1_tier_smoke
        run: ./gradlew :sourbycraft-server:p1TierSmokeTest -PrunP1TierSmoke=true
```

- [ ] **Step 3: Add upload-on-failure step for P1**

Find the existing `Upload P0 knob smoke boot log` step. Add a sibling AFTER it (and before `Upload JUnit XML`):

```yaml
      - name: Upload P1 tier smoke boot log
        if: failure() && steps.p1_tier_smoke.conclusion == 'failure'
        uses: actions/upload-artifact@v4
        with:
          name: p1-tier-smoke-boot-log
          path: test-harness/TestServer-mojmap/boot.log
```

- [ ] **Step 4: Validate YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/nms-compat.yml'))" && echo "YAML OK"
```

Expected: `YAML OK`. If `yaml` unavailable, skip — CI will catch malformed YAML on PR open.

- [ ] **Step 5: Run smoke locally one more time to confirm everything still green**

```bash
./gradlew p1TierSmokeTest -PrunP1TierSmoke=true
```

Expected: all 7 scenarios PASS.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/nms-compat.yml
git commit -m "ci: gate perf-engine P1 tier smoke on changed paths

Adds 'Run perf-engine P1 tier smoke' step with id: p1_tier_smoke to
the existing nms-compat workflow. Adds an upload-on-failure step gated
on that step's conclusion so the P1 boot log artifact is only uploaded
when the P1 smoke itself fails."
```

---

## Final Verification (after all 6 tasks merged)

Walk through spec Section 7 acceptance criteria. Run each:

- [ ] **A1. Sensor package created**
  ```bash
  ls sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/
  ```
  Expected: `PerfSensor.java SensorSnapshot.java Tier.java`

- [ ] **A2. Tier enum has 5 values**
  ```bash
  grep -cE '^\s*(GREEN|YELLOW|ORANGE|RED|EMERGENCY)' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/Tier.java
  ```
  Expected: `5`

- [ ] **A3. SensorSnapshot is a record**
  ```bash
  grep 'public record SensorSnapshot' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/sensor/SensorSnapshot.java
  ```
  Expected: match

- [ ] **A4. PerfSensor.tick wired into tickServer**
  ```bash
  grep -rn 'PerfSensor.tick' patches/minecraft/ patches/server/ sourbycraft-server/src/main/java/ 2>/dev/null | grep -v build/
  ```
  Expected: ≥1 hit in `patches/minecraft/00NN-...patch`

- [ ] **A5. PerfSensor.loadFromYml wired into init**
  ```bash
  grep 'PerfSensor.loadFromYml' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java
  ```
  Expected: match

- [ ] **A6. yml has sensor section**
  ```bash
  grep -c 'sensor:' sourbycraft-server/src/main/resources/sourbycraft.yml
  ```
  Expected: ≥1

- [ ] **A7. /perf tier subcommand registered**
  ```bash
  grep 'literal("tier")' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java
  ```
  Expected: match

- [ ] **A8. /perf sensors subcommand registered**
  ```bash
  grep 'literal("sensors")' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java
  ```
  Expected: match

- [ ] **A9. Smoke script present, executable**
  ```bash
  ls -la test-harness/scripts/p1-tier-smoke.sh
  ```
  Expected: `-rwxr-xr-x`

- [ ] **A10. Gradle task registered**
  ```bash
  ./gradlew tasks --all -PrunP1TierSmoke=true | grep p1TierSmokeTest
  ```
  Expected: listed with description

- [ ] **A11. Smoke green**
  ```bash
  ./gradlew p1TierSmokeTest -PrunP1TierSmoke=true
  ```
  Expected: all 7 scenarios PASS, BUILD SUCCESSFUL

- [ ] **A12. CI gate added**
  ```bash
  grep p1TierSmokeTest .github/workflows/nms-compat.yml
  ```
  Expected: match

- [ ] **A13. No new JUnit**
  ```bash
  git diff a7ae43d..HEAD --stat sourbycraft-server/src/test/
  ```
  Expected: empty

- [ ] **A14. Default boot unchanged** — scenario 0 reaches `Done (` within prior baseline window (≤90s). Verified by SCENARIO_0_BOOT pass in A11.

- [ ] **A15. DynamicPerformanceScaler unchanged**
  ```bash
  git diff a7ae43d..HEAD sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/DynamicPerformanceScaler.java
  ```
  Expected: empty (P7 owns removal)

---

## Self-Review

**Spec coverage:**

- Spec §3 C1 (`Tier`) → Task 2 Step 1.
- Spec §3 C2 (`SensorSnapshot`) → Task 2 Step 2.
- Spec §3 C3 (`PerfSensor` + dwell+band hysteresis logic) → Task 3 Step 4.
- Spec §3 C4 (sensor tick wired into tickServer) → Task 3 Step 7 (`patches/minecraft/00NN-...patch`).
- Spec §3 C5 (`PerfSensor.loadFromYml()` wired into init) → Task 3 Step 6.
- Spec §3 C6 (`sourbycraft.yml` sensor section) → Task 3 Step 5.
- Spec §3 C7 (`/perf tier` + `/perf sensors` subcommands) → Task 4 Steps 2 + 3.
- Spec §3 C8 (boot smoke harness) → Task 1 (skeleton) + Task 3 (scenarios 1, 2) + Task 4 (scenario 7) + Task 5 (scenarios 3, 4, 5, 6).
- Spec §9 Phase 1 (smoke harness skeleton) → Task 1.
- Spec §9 Phase 2 (Tier + SensorSnapshot) → Task 2.
- Spec §9 Phase 3 (PerfSensor + wiring) → Task 3.
- Spec §9 Phase 4 (subcommands) → Task 4.
- Spec §9 Phase 5 (forced-transition smoke) → Task 5.
- Spec §9 Phase 6 (CI gate) → Task 6.

**Placeholder scan:** Zero `TBD`/`TODO`/`implement later` in this plan. Two references to `00NN` (Task 1 Step 3 and Task 3 Step 1) — these are template substitutions, with explicit grep commands to look up the actual next-free number at impl time. Acceptable per scope-deferred-decision pattern.

**Type consistency:**
- `Tier` enum values used identically across Tier.java, SensorSnapshot.java, PerfSensor.java, smoke regexes, command output.
- `Tier.isWorseThan` + `Tier.worse` used in PerfSensor's hysteresis logic.
- `SensorSnapshot.INITIAL` referenced as the volatile-published default in PerfSensor.
- `PerfSensor.loadFromYml()` no-arg form consistent with P0 `Knobs.loadFromYml()`.
- `PerfSensor.tick(MinecraftServer server)` consistent with the NMS patch insertion site.
- `PerfSensor.currentTier()`, `snapshot()`, `isEnabled()`, `timeInTierNanos()`, `thresholdsFor(String)` used identically in PerfCommand.
- yml path `perf.sensor.thresholds.<signal>.<tier>` consistent between PerfSensor.loadThresholds and smoke override scenarios.
- Log line format `perf tier transition: <old> -> <new>` consistent between `PerfSensor.transition()` and SCENARIO_2/4 regex assertions.
- Log line format `perf sensor: cadence=N dwell=N` consistent between `PerfSensor.loadFromYml()` and SCENARIO_6 assertion.
