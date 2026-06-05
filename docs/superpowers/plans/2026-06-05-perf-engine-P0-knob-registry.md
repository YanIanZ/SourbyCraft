# Perf-engine P0 — Knob Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the `PerfKnob`/`BoolKnob`/`IntKnob` + `Knobs` static holder + `KnobRegistry` foundation, and migrate `entityTickRate` from a static field to a method-backed knob as the worked example.

**Architecture:** New package `dev.iyanz.sourbycraft.perf.knob` holds the abstraction. Knob value lives in a `volatile` field on the knob instance; `Knobs.ENTITY_TICK_RATE` is a `public static final` IntKnob declared in the holder. Hot-path getter inlines to a volatile load. `KnobRegistry` is a package-private `ConcurrentHashMap`. yml load happens once at boot from inside `SourbyCraftConfig.init()`.

**Tech Stack:** Java (Paper fork, mojmap), gradle (Kotlin DSL), bash smoke harness, RCON for command-output assertions. No JUnit added — verification via boot smoke at `test-harness/TestServer-mojmap/`.

**Spec:** `docs/superpowers/specs/2026-06-05-perf-engine-P0-knob-registry-design.md` (committed `ed367a0`).

---

## Deviations From Spec (resolved inline; no spec change required)

These are implementation-detail clarifications discovered when grepping the existing repo. Each is documented at the task where it applies.

1. **`loadFrom(SourbyCraftConfig cfg)` becomes `loadFrom()` (no-arg).** `SourbyCraftConfig` is a fully-static utility class. There is no instance to pass. The abstract method reads via static accessor (`SourbyCraftConfig.ymlInt(...)`).
2. **`Knobs.loadFromYml(this)` becomes `Knobs.loadFromYml()` (no-arg).** Same reason. Call site inside `SourbyCraftConfig.init()` is `Knobs.loadFromYml();`.
3. **`SourbyLogger.warn(String)` is single-arg.** No SLF4J `{}` formatting. Use string concatenation: `SourbyLogger.warn("[SourbyCraft] knob '" + key + "' value " + req + " clamped to " + clamped);`.
4. **Default value `20` (not `1`).** Existing `public static volatile int entityTickRate = 20;` ships with 20 (skip 19 of every 20 entity ticks — a SourbyCraft optimization, not Paper vanilla). Migrating to default 1 would silently regress every existing deployment. Plan preserves `20`. Reviewer can change to `1` in a separate behavioral-change commit if desired.
5. **Operator config-yml backward-compat.** Existing `entityTickRate = getInt("entity.tick-rate", entityTickRate);` reads from operator-edited Bukkit `config.yml` (NOT jar-baked `sourbycraft.yml`). Phase 3 preserves this read path by re-routing the assignment through `Knobs.ENTITY_TICK_RATE.set(...)`. Net: operator `entity.tick-rate` in config.yml still wins over jar-baked `perf.entity-tick-rate`.
6. **Validation order.** Constructor default → `Knobs.loadFromYml()` (jar-baked `perf.entity-tick-rate`, Phase 2 wire) → `SourbyCraftConfig.init()` operator-yml override (Phase 3 wire). Each subsequent write overrides the previous. Last-writer-wins is intentional.

---

## File Structure

**Created:**
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/PerfKnob.java` — sealed abstract base
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/BoolKnob.java` — boolean concrete subclass
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/IntKnob.java` — integer concrete subclass with clamp + WARN
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/KnobRegistry.java` — package-private static registry + warnOnce dedupe + snapshot
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java` — public static holder, declares ENTITY_TICK_RATE
- `test-harness/scripts/p0-knob-smoke.sh` — boot-smoke harness (5 scenarios)

**Modified:**
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` — line 206 (field → method), line ~365 (route through Knobs), line ~488 (call Knobs.loadFromYml())
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/DynamicPerformanceScaler.java` — lines 22, 32 (read/write via Knobs)
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/StartupOptimizer.java` — lines 23, 45 (read via method)
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java` — lines 78, 92 (read via method / write via Knobs)
- `sourbycraft-server/src/main/resources/sourbycraft.yml` — append `perf:` section
- `sourbycraft-server/build.gradle.kts` — register `p0KnobSmokeTest` gradle task
- `.github/workflows/*.yml` — add path-filter triggering smoke job (the precise file picked at Task 5)

---

## TDD adaptation

Spec testing model is "boot smoke only, no JUnit" (per user feedback). Strict TDD per code change is impractical because the smoke boot takes ~90s per scenario. Adaptation:

- **Phase 1**: smoke harness stub lands first; assertion = boot reaches `Done (`. This is the "failing test" — it fails until the package compiles.
- **Phase 2 onward**: each phase adds one smoke scenario. Scenario is written, observed to fail (because code not in place), code is implemented, scenario passes, commit.
- Between scenarios, `./gradlew :sourbycraft-server:classes` is the cheap compile gate.

---

## Task 1: Smoke harness skeleton + gradle task

**Goal:** Land the smoke script + gradle task before any production code. Script can boot the existing repo unchanged and assert `Done (`. Establishes the "failing test" surface for subsequent tasks.

**Files:**
- Create: `test-harness/scripts/p0-knob-smoke.sh`
- Modify: `sourbycraft-server/build.gradle.kts` (append gradle task block at end, alongside existing `particleSmokeTest` block)

- [ ] **Step 1: Write `test-harness/scripts/p0-knob-smoke.sh`**

```bash
#!/usr/bin/env bash
# Perf-engine P0 — knob registry boot smoke.
# Runs 5 scenarios against test-harness/TestServer-mojmap/, each with a different
# plugins/SourbyCraft/sourbycraft.yml `perf` block. Asserts via boot.log grep
# and (when scenarios extend beyond Task 1) RCON /perf output.
#
# Scenarios populated incrementally across plan tasks:
#   Task 1: SCENARIO_0_BOOT (just verify the harness wires up)
#   Task 2: SCENARIO_1_DEFAULT
#   Task 3: SCENARIO_2_IN_RANGE
#   Task 4: SCENARIO_3_CLAMP_HI, SCENARIO_4_CLAMP_LO, SCENARIO_5_WRONG_TYPE

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
HARNESS_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
ROOT_DIR="$( cd "$HARNESS_DIR/.." && pwd )"

JAR_SRC="$ROOT_DIR/release/SourbyCraft-v12-REL.jar"
TS_DIR="$HARNESS_DIR/TestServer-mojmap"
PORT=25600
RCON_PORT=25675
RCON_PASS=p0test

if [[ ! -f "$JAR_SRC" ]]; then
    echo "ERROR: $JAR_SRC missing. Run gradle assembleReleaseArtifacts first." >&2
    exit 1
fi

mkdir -p "$TS_DIR/plugins/SourbyCraft"
cp "$JAR_SRC" "$TS_DIR/server.jar"
echo "eula=true" > "$TS_DIR/eula.txt"

# Seed server.properties with port + online-mode + RCON
cat > "$TS_DIR/server.properties" <<EOF
server-port=$PORT
online-mode=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASS
broadcast-rcon-to-ops=false
EOF

boot_and_assert() {
    local scenario="$1"
    local yml="$2"
    local logre="$3"  # boot.log regex (PCRE-ish — basic grep -E); empty = no log assertion

    echo "p0-knob-smoke: scenario=$scenario"
    if [[ -n "$yml" ]]; then
        printf '%s\n' "$yml" > "$TS_DIR/plugins/SourbyCraft/sourbycraft.yml"
    else
        rm -f "$TS_DIR/plugins/SourbyCraft/sourbycraft.yml"
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
            echo "ERROR: server died before Done (" >&2
            tail -50 boot.log >&2
            exit 2
        fi
        sleep 2
    done
    if [[ $ok -eq 0 ]]; then
        echo "ERROR: scenario=$scenario BOOT_TIMEOUT after 90s" >&2
        tail -50 boot.log >&2
        kill -TERM "$pid" 2>/dev/null; sleep 5; kill -KILL "$pid" 2>/dev/null || true
        exit 3
    fi

    if [[ -n "$logre" ]]; then
        if ! grep -E -q "$logre" boot.log; then
            echo "ERROR: scenario=$scenario log assertion failed; expected regex: $logre" >&2
            tail -100 boot.log >&2
            kill -TERM "$pid" 2>/dev/null; sleep 5; kill -KILL "$pid" 2>/dev/null || true
            exit 4
        fi
    fi

    # Shutdown cleanly
    kill -TERM "$pid" 2>/dev/null || true
    sleep 5
    kill -KILL "$pid" 2>/dev/null || true
    cd - >/dev/null
    echo "p0-knob-smoke: scenario=$scenario PASS"
}

# === SCENARIO_0_BOOT (Task 1) ===
# Sanity: harness boots the current jar with no perf yml; assert Done ( reached.
boot_and_assert "0_boot_sanity" "" ""

# === SCENARIO_1_DEFAULT (added in Task 2) ===
# === SCENARIO_2_IN_RANGE (added in Task 3) ===
# === SCENARIO_3_CLAMP_HI (added in Task 4) ===
# === SCENARIO_4_CLAMP_LO (added in Task 4) ===
# === SCENARIO_5_WRONG_TYPE (added in Task 4) ===

echo "p0-knob-smoke: all scenarios PASS"
```

- [ ] **Step 2: Make script executable**

Run:
```bash
chmod +x test-harness/scripts/p0-knob-smoke.sh
```

- [ ] **Step 3: Register gradle task in `sourbycraft-server/build.gradle.kts`**

Locate the existing `if (runParticleSmoke) { tasks.register<Exec>("particleSmokeTest") { ... } }` block. Append a sibling block:

```kotlin
val runP0KnobSmoke = (project.findProperty("runP0KnobSmoke") as String?)?.toBoolean() ?: false
if (runP0KnobSmoke) {
    tasks.register<Exec>("p0KnobSmokeTest") {
        group = "verification"
        description = "Boot SourbyCraft jar with perf knob registry scenarios; assert via boot.log + RCON"
        dependsOn(":assembleReleaseArtifacts")
        workingDir = rootProject.rootDir
        commandLine("bash", rootProject.file("test-harness/scripts/p0-knob-smoke.sh").absolutePath)
    }
}
```

(Confirm the `runParticleSmoke` block exists first via `grep particleSmokeTest sourbycraft-server/build.gradle.kts`. If the existing block uses a slightly different gradle DSL idiom, mirror its exact pattern. Do not move or rename the existing block.)

- [ ] **Step 4: Verify gradle task is registered**

Run:
```bash
./gradlew tasks --all -PrunP0KnobSmoke=true 2>&1 | grep p0KnobSmokeTest
```
Expected: one line like `p0KnobSmokeTest - Boot SourbyCraft jar with perf knob registry scenarios...`

- [ ] **Step 5: Build release jar (smoke prerequisite)**

Run:
```bash
./gradlew assembleReleaseArtifacts
```
Expected: BUILD SUCCESSFUL. Produces `release/SourbyCraft-v12-REL.jar`.

- [ ] **Step 6: Run smoke — must PASS the SCENARIO_0_BOOT sanity check**

Run:
```bash
./gradlew p0KnobSmokeTest -PrunP0KnobSmoke=true
```
Expected:
```
p0-knob-smoke: scenario=0_boot_sanity
p0-knob-smoke: scenario=0_boot_sanity PASS
p0-knob-smoke: all scenarios PASS
BUILD SUCCESSFUL
```

If FAIL: read `test-harness/TestServer-mojmap/boot.log` tail. Common causes: RCON port already bound (kill stragglers), EULA missing (script writes it; check filesystem perms), JAR not built (Step 5).

- [ ] **Step 7: Commit**

```bash
git add -f test-harness/scripts/p0-knob-smoke.sh sourbycraft-server/build.gradle.kts
git commit -m "test: perf-engine P0 — smoke harness skeleton (boot sanity scenario)"
```

---

## Task 2: Knob abstraction skeleton + reference knob + yml + load + default scenario

**Goal:** Land the full knob package + declare `Knobs.ENTITY_TICK_RATE` + append yml + wire `Knobs.loadFromYml()` into `SourbyCraftConfig.init()`. Add SCENARIO_1_DEFAULT to the smoke harness. After this task, the knob is loadable but not yet wired into any read path — `SourbyCraftConfig.entityTickRate` static field is still the active hot-path source.

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/PerfKnob.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/BoolKnob.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/IntKnob.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/KnobRegistry.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java`
- Modify: `sourbycraft-server/src/main/resources/sourbycraft.yml` (append `perf` section at end of file)
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` (append `Knobs.loadFromYml();` call inside `init()` just before existing `VirtualExecutor.init();` at line ~488)
- Modify: `test-harness/scripts/p0-knob-smoke.sh` (add SCENARIO_1_DEFAULT)

- [ ] **Step 1: Add failing scenario to smoke**

Edit `test-harness/scripts/p0-knob-smoke.sh`. Replace the comment `# === SCENARIO_1_DEFAULT (added in Task 2) ===` with this block:

```bash
# === SCENARIO_1_DEFAULT ===
# yml omits perf block entirely; expect default value 20 (preserves existing behavior).
# Assertion: boot.log contains the StartupOptimizer info line that prints the rate.
boot_and_assert "1_default_no_perf_block" "" \
  "Entity Tick Rate: 1/20"
```

- [ ] **Step 2: Run smoke — must FAIL on SCENARIO_1_DEFAULT (no implementation yet should be same as before; assertion may still pass coincidentally because StartupOptimizer already prints `1/20`)**

Run:
```bash
./gradlew p0KnobSmokeTest -PrunP0KnobSmoke=true
```
Expected: SCENARIO_0 PASS, SCENARIO_1 PASS (rate=20 already prints because existing default is 20). This is intentional — Task 2 does NOT change the rate value; it only adds the knob load plumbing. The test is failure-proof for the default scenario by design.

If unexpectedly fails: investigate. Don't proceed until SCENARIO_1 passes against the unchanged repo, because we want to assert that Task 2's added load-from-yml does not REGRESS the value.

- [ ] **Step 3: Create `PerfKnob.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

/**
 * Sealed base for a runtime-tunable performance knob. Subclasses own a typed value
 * and a clamp policy. Each instance auto-registers in KnobRegistry on construction.
 * Knob declarations live in {@link Knobs}.
 */
public sealed abstract class PerfKnob permits BoolKnob, IntKnob {

    protected final String key;

    protected PerfKnob(String key) {
        this.key = key;
        KnobRegistry.register(this);
    }

    public final String key() { return key; }

    /** Boxed snapshot value for {@link Knobs#snapshot()}. */
    public abstract Object snapshot();

    /** Read from sourbycraft.yml (jar-baked) and apply to this knob. Called at boot. */
    abstract void loadFrom();
}
```

- [ ] **Step 4: Create `BoolKnob.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.SourbyCraftConfig;

public final class BoolKnob extends PerfKnob {

    private final boolean defaultValue;
    private volatile boolean value;

    public BoolKnob(String key, boolean defaultValue) {
        super(key);
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public boolean get() { return value; }

    public void set(boolean v) { this.value = v; }

    @Override public Object snapshot() { return value; }

    @Override void loadFrom() {
        this.value = SourbyCraftConfig.ymlBool(key, defaultValue);
    }
}
```

- [ ] **Step 5: Create `IntKnob.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.SourbyCraftConfig;

public final class IntKnob extends PerfKnob {

    private final int defaultValue;
    private final int min;
    private final int max;
    private volatile int value;

    public IntKnob(String key, int defaultValue, int min, int max) {
        super(key);
        if (min > max) throw new IllegalArgumentException("min > max for " + key);
        this.min = min;
        this.max = max;
        this.defaultValue = clamp(defaultValue, min, max);
        this.value = this.defaultValue;
    }

    public int get() { return value; }

    public void set(int v) {
        int clamped = clamp(v, min, max);
        if (clamped != v) KnobRegistry.warnOnce(key, v, clamped);
        this.value = clamped;
    }

    public int min() { return min; }
    public int max() { return max; }

    @Override public Object snapshot() { return value; }

    @Override void loadFrom() {
        set(SourbyCraftConfig.ymlInt(key, defaultValue));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
```

- [ ] **Step 6: Create `KnobRegistry.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.util.SourbyLogger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class KnobRegistry {

    private static final Map<String, PerfKnob> KNOBS = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private KnobRegistry() {}

    static void register(PerfKnob k) {
        if (KNOBS.putIfAbsent(k.key(), k) != null) {
            throw new IllegalStateException("duplicate knob key: " + k.key());
        }
    }

    static void warnOnce(String key, int requested, int clamped) {
        String dedupeKey = key + ":" + (requested < clamped ? "lo" : "hi");
        if (WARNED.add(dedupeKey)) {
            SourbyLogger.warn(
                "[SourbyCraft] knob '" + key + "' value " + requested
                    + " clamped to " + clamped
            );
        }
    }

    static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        KNOBS.forEach((k, knob) -> out.put(k, knob.snapshot()));
        return Collections.unmodifiableMap(out);
    }

    static void loadAllFromYml() {
        for (PerfKnob k : KNOBS.values()) k.loadFrom();
    }
}
```

- [ ] **Step 7: Create `Knobs.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

import java.util.Map;

/**
 * Static declaration site for all SourbyCraft performance knobs. Each public-static-final
 * field declares one knob; class init triggers KnobRegistry registration. Hot-path readers
 * call {@code Knobs.<KNOB>.get()}; controllers and commands call {@code Knobs.<KNOB>.set(...)}.
 */
public final class Knobs {

    private Knobs() {}

    /** Skip-rate for entity ticking. 1 = tick every server tick (vanilla); 20 = once per second. */
    public static final IntKnob ENTITY_TICK_RATE =
        new IntKnob("perf.entity-tick-rate", 20, 1, 20);

    public static Map<String, Object> snapshot() {
        return KnobRegistry.snapshot();
    }

    public static void loadFromYml() {
        KnobRegistry.loadAllFromYml();
    }
}
```

- [ ] **Step 8: Append `perf:` section to `sourbycraft-server/src/main/resources/sourbycraft.yml`**

Append at the END of the file (after the last existing section):

```yaml

perf:
  # Skip-rate for entity ticking. 1 = tick every server tick (vanilla);
  # higher = skip ticks (less smooth movement, lower CPU).
  # Range 1..20. Default 20 (SourbyCraft historical default).
  # Operator config.yml `entity.tick-rate` overrides this at boot.
  # Auto-tuned at runtime by DynamicPerformanceScaler when /perf scale on.
  entity-tick-rate: 20
```

- [ ] **Step 9: Wire `Knobs.loadFromYml()` into `SourbyCraftConfig.init()`**

Open `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`. Find the line `dev.iyanz.sourbycraft.util.VirtualExecutor.init();` (currently line 488). Insert immediately BEFORE it:

```java
        dev.iyanz.sourbycraft.perf.knob.Knobs.loadFromYml();
```

Resulting block reads:

```java
        // SourbyCraft end PvP overrides

        dev.iyanz.sourbycraft.perf.knob.Knobs.loadFromYml();
        dev.iyanz.sourbycraft.util.VirtualExecutor.init();
    }
```

- [ ] **Step 10: Compile**

Run:
```bash
./gradlew :sourbycraft-server:classes
```
Expected: BUILD SUCCESSFUL. No compile errors in the new package.

If FAIL: read the error, fix syntactic issue, re-run. Common: missing import, wrong package, typo in sealed permits clause.

- [ ] **Step 11: Rebuild release jar (smoke prerequisite)**

Run:
```bash
./gradlew assembleReleaseArtifacts
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Run full smoke — both scenarios must PASS**

Run:
```bash
./gradlew p0KnobSmokeTest -PrunP0KnobSmoke=true
```
Expected:
```
p0-knob-smoke: scenario=0_boot_sanity PASS
p0-knob-smoke: scenario=1_default_no_perf_block PASS
p0-knob-smoke: all scenarios PASS
```

If FAIL on SCENARIO_1: server crashed at boot. Inspect `test-harness/TestServer-mojmap/boot.log`. Most likely class-init issue in `Knobs` — `new IntKnob(...)` throws because of typo in min/max, or `KnobRegistry.register` is called before the class is loaded. Fix and re-run.

- [ ] **Step 13: Commit**

```bash
git add -f sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/PerfKnob.java \
            sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/BoolKnob.java \
            sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/IntKnob.java \
            sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/KnobRegistry.java \
            sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java
git add    sourbycraft-server/src/main/resources/sourbycraft.yml \
            sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java \
            test-harness/scripts/p0-knob-smoke.sh
git commit -m "feat: perf-engine P0 — Knob abstraction + ENTITY_TICK_RATE + yml load"
```

---

## Task 3: Field → method migration + scaler write + in-range scenario

**Goal:** Replace `public static volatile int entityTickRate` field with `public static int entityTickRate()` method. Update all 6 caller sites. Route the existing operator-config Bukkit read through `Knobs.ENTITY_TICK_RATE.set(...)`. Add SCENARIO_2_IN_RANGE to smoke.

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` (line 206 declaration + line ~365 Bukkit-config read)
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/DynamicPerformanceScaler.java` (lines 22, 32)
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/StartupOptimizer.java` (lines 23, 45)
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java` (lines 78, 92)
- Modify: `test-harness/scripts/p0-knob-smoke.sh` (add SCENARIO_2_IN_RANGE)

- [ ] **Step 1: Add failing scenario to smoke**

Edit `test-harness/scripts/p0-knob-smoke.sh`. Replace the comment `# === SCENARIO_2_IN_RANGE (added in Task 3) ===` with this block:

```bash
# === SCENARIO_2_IN_RANGE ===
# yml sets perf.entity-tick-rate to 4 (in-range). Expect Knob.get() returns 4.
# Existing StartupOptimizer log line prints "Entity Tick Rate: 1/4".
# Assertion: boot.log contains that line; no clamp WARN appears.
SCENARIO_2_YML='perf:
  entity-tick-rate: 4'
boot_and_assert "2_in_range_rate_4" "$SCENARIO_2_YML" \
  "Entity Tick Rate: 1/4"

# Negative assertion: no clamp WARN should appear in this scenario
if grep -E "knob 'perf.entity-tick-rate' value 4 clamped" "$TS_DIR/boot.log" >/dev/null; then
    echo "ERROR: scenario=2 unexpected clamp WARN in boot.log" >&2
    exit 5
fi
```

- [ ] **Step 2: Run smoke — must FAIL on SCENARIO_2_IN_RANGE**

Run:
```bash
./gradlew p0KnobSmokeTest -PrunP0KnobSmoke=true
```
Expected: SCENARIO_0 PASS, SCENARIO_1 PASS, SCENARIO_2 **FAIL** because `StartupOptimizer` still reads the static field set by Bukkit config (which now ignores the new `perf.entity-tick-rate` jar yml — Bukkit config has no `entity.tick-rate`, so field stays at default 20, so log says `1/20`, not `1/4`).

This is the failing test. Now implement.

- [ ] **Step 3: Replace the field declaration in `SourbyCraftConfig.java`**

Locate line 206:
```java
    public static volatile int entityTickRate = 20;
```

Replace with:
```java
    public static int entityTickRate() {
        return dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.get();
    }
```

- [ ] **Step 4: Route the Bukkit config read through Knobs in `SourbyCraftConfig.java`**

Locate line 365 (existing):
```java
        entityTickRate = getInt("entity.tick-rate", entityTickRate);
```

Replace with:
```java
        dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.set(
            getInt("entity.tick-rate", dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.get())
        );
```

This keeps operator `entity.tick-rate` in config.yml authoritative if set, falling back to the knob's current value (which by this point has been loaded from jar yml via `Knobs.loadFromYml()` in Step 9 of Task 2).

Note that this line runs INSIDE `init()`, AFTER `Knobs.loadFromYml()` runs (Task 2 added the call at the end of init() right before VirtualExecutor.init()). But the Bukkit-config read at line 365 happens earlier in init() than line 488. Re-order the `Knobs.loadFromYml()` call so it happens BEFORE the Bukkit-config block at line 365. Move the call from "just before VirtualExecutor.init()" to "right after `try { config.load(CONFIG_FILE); } catch ...`" — i.e. just before line 312 where `asyncChunkLoad = getBoolean(...)` starts. Confirm exact position by grepping: `grep -n "asyncChunkLoad = getBoolean" sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` — insert the `Knobs.loadFromYml();` line on the preceding blank line.

The post-Task-3 init() shape:

```java
public static void init(File configFile) {
    // ... existing config.load() block ...

    dev.iyanz.sourbycraft.perf.knob.Knobs.loadFromYml();   // SourbyCraft - perf-engine P0

    asyncChunkLoad = getBoolean("performance.async-chunk-load", asyncChunkLoad);
    // ... other getBoolean/getInt calls ...
    dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.set(
        getInt("entity.tick-rate", dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.get())
    );
    // ... rest ...
    dev.iyanz.sourbycraft.util.VirtualExecutor.init();
}
```

Remove the `Knobs.loadFromYml()` call that Task 2 placed before `VirtualExecutor.init()`.

- [ ] **Step 5: Update `DynamicPerformanceScaler.java`**

Locate line 22:
```java
        int rate = SourbyCraftConfig.entityTickRate;
```
Replace with:
```java
        int rate = dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.get();
```

Locate line 32:
```java
        SourbyCraftConfig.entityTickRate = rate;
```
Replace with:
```java
        dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.set(rate);
```

- [ ] **Step 6: Update `StartupOptimizer.java`**

Locate line 23:
```java
            server.LOGGER.info("  Entity Tick Rate: 1/{} (limiter ON)", SourbyCraftConfig.entityTickRate);
```
Replace with:
```java
            server.LOGGER.info("  Entity Tick Rate: 1/{} (limiter ON)", SourbyCraftConfig.entityTickRate());
```

Locate line 45:
```java
        if (SourbyCraftConfig.entityTickRate <= 1 && !DynamicPerformanceScaler.isEnabled()) {
```
Replace with:
```java
        if (SourbyCraftConfig.entityTickRate() <= 1 && !DynamicPerformanceScaler.isEnabled()) {
```

- [ ] **Step 7: Update `PerfCommand.java`**

Locate line 78 (use exact text from current file):
```java
        src.sendSystemMessage(Component.literal("  Scale: " + scaling + " | Rate: 1/" + SourbyCraftConfig.entityTickRate)
```
Replace `SourbyCraftConfig.entityTickRate` with `SourbyCraftConfig.entityTickRate()`.

Locate line 92:
```java
        SourbyCraftConfig.entityTickRate = rate;
```
Replace with:
```java
        dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.set(rate);
```

- [ ] **Step 8: Verify no remaining field-access call sites**

Run:
```bash
grep -rn 'SourbyCraftConfig\.entityTickRate[^(]' sourbycraft-server/src/main paper-server/src/main patches/ 2>/dev/null | grep -v build/
```
Expected: empty output. If any matches remain, update them to `()` form (or to `Knobs.ENTITY_TICK_RATE.set(...)` if it's a write).

- [ ] **Step 9: Compile**

Run:
```bash
./gradlew :sourbycraft-server:classes
```
Expected: BUILD SUCCESSFUL. If FAIL on `cannot find symbol entityTickRate`, you missed a call site — re-run Step 8 grep.

- [ ] **Step 10: Rebuild release jar**

Run:
```bash
./gradlew assembleReleaseArtifacts
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Run full smoke — all 3 scenarios must PASS**

Run:
```bash
./gradlew p0KnobSmokeTest -PrunP0KnobSmoke=true
```
Expected:
```
p0-knob-smoke: scenario=0_boot_sanity PASS
p0-knob-smoke: scenario=1_default_no_perf_block PASS
p0-knob-smoke: scenario=2_in_range_rate_4 PASS
p0-knob-smoke: all scenarios PASS
```

If SCENARIO_2 still fails: check `test-harness/TestServer-mojmap/boot.log` for the StartupOptimizer log line. If line reads `1/20` instead of `1/4`, the yml load order is wrong — verify Step 4 placement of `Knobs.loadFromYml()` BEFORE the Bukkit-config read block. If line is missing entirely, entity-tick-rate-limit is off in the test server's config.yml — Step 2 should still find the line because the limiter print is unconditional for SourbyCraft default config. Confirm by inspecting StartupOptimizer.java line 22 to see what gates the print.

- [ ] **Step 12: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java \
        sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/DynamicPerformanceScaler.java \
        sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/StartupOptimizer.java \
        sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java \
        test-harness/scripts/p0-knob-smoke.sh
git commit -m "refactor: migrate entityTickRate field → Knobs.ENTITY_TICK_RATE getter"
```

---

## Task 4: Clamp + wrong-type smoke scenarios

**Goal:** Add SCENARIO_3_CLAMP_HI, SCENARIO_4_CLAMP_LO, SCENARIO_5_WRONG_TYPE. Each scenario asserts on a specific log line written by `KnobRegistry.warnOnce` or the existing `SourbyCraftConfig.warnOnce` type-mismatch path. No production code change in this task — the implementation from Task 2 must already pass these.

**Files:**
- Modify: `test-harness/scripts/p0-knob-smoke.sh` (replace the three remaining SCENARIO comments with implementations)

- [ ] **Step 1: Add failing scenarios to smoke**

Edit `test-harness/scripts/p0-knob-smoke.sh`. Replace the three trailing comment lines with this block:

```bash
# === SCENARIO_3_CLAMP_HI ===
# yml sets perf.entity-tick-rate to 99 (above max=20). Expect clamp to 20 + WARN.
SCENARIO_3_YML='perf:
  entity-tick-rate: 99'
boot_and_assert "3_clamp_hi_99" "$SCENARIO_3_YML" \
  "knob 'perf\.entity-tick-rate' value 99 clamped to 20"

# === SCENARIO_4_CLAMP_LO ===
# yml sets perf.entity-tick-rate to 0 (below min=1). Expect clamp to 1 + WARN.
# Note: Bukkit-config override at SourbyCraftConfig.java line ~365 reads `entity.tick-rate`
# from config.yml, which falls back to the knob value if not set. So this scenario's
# WARN should appear during Knobs.loadFromYml() with the literal yml value 0.
SCENARIO_4_YML='perf:
  entity-tick-rate: 0'
boot_and_assert "4_clamp_lo_0" "$SCENARIO_4_YML" \
  "knob 'perf\.entity-tick-rate' value 0 clamped to 1"

# === SCENARIO_5_WRONG_TYPE ===
# yml sets perf.entity-tick-rate to a string. Expect ymlInt type-mismatch WARN from
# SourbyCraftConfig.warnOnce. The knob falls back to its default (20).
SCENARIO_5_YML='perf:
  entity-tick-rate: "high"'
boot_and_assert "5_wrong_type_string" "$SCENARIO_5_YML" \
  "config key 'perf\.entity-tick-rate' invalid type 'String'"
```

- [ ] **Step 2: Run smoke — verify all scenarios PASS without code changes**

Run:
```bash
./gradlew p0KnobSmokeTest -PrunP0KnobSmoke=true
```
Expected: all 6 scenarios (0_boot_sanity through 5_wrong_type_string) PASS.

If SCENARIO_3 or 4 FAILS (no clamp WARN in boot.log): inspect `IntKnob.loadFrom()` and `KnobRegistry.warnOnce()`. The WARN should appear at boot when `Knobs.loadFromYml()` runs. Most likely cause: SourbyLogger output not flushed to boot.log before the assertion runs — boot.log captures stdout/stderr; JUL routes WARN to stderr by default. Verify by running the boot manually and checking boot.log for the line.

If SCENARIO_5 FAILS: inspect the exact string emitted by `SourbyCraftConfig.warnOnce` in the existing code (line 558-565). The current format is `"[SourbyCraft] config key '" + path + "' invalid type '" + actual.getClass().getSimpleName() + "', expected " + expected + " — using default"`. Adjust the smoke assertion regex to match the actual format exactly. The class name for a yml string value is `String`, so `'String'` should match.

- [ ] **Step 3: Commit**

```bash
git add test-harness/scripts/p0-knob-smoke.sh
git commit -m "test: perf-engine P0 — clamp + wrong-type smoke scenarios"
```

---

## Task 5: CI gate path filter

**Goal:** Add a CI job that runs `p0KnobSmokeTest` on PRs touching the knob package or yml.

**Files:**
- Modify: `.github/workflows/<existing-workflow>.yml` (precise file picked at task time — see Step 1)
- Or create: `.github/workflows/p0-knob-smoke.yml` (if no existing workflow is a fit)

- [ ] **Step 1: Locate existing CI workflow files**

Run:
```bash
ls -la .github/workflows/
```
Read each file. Goal: find an existing workflow that runs gradle smoke tests on PRs (the existing `nmsCompatTest`/`particleSmokeTest` jobs). The simplest path is to add a sibling job to that workflow.

Recent commits to model after (per git log):
- `37fa054 ci: nms-compat gates accessor + yml paths; runs particleSmokeTest`
- `40268f3 ci: NMS-compat gate on PRs touching patches/, release/, paperRef, or test-harness/`

Read those commits' diffs to see the exact workflow file modified:
```bash
git show --stat 37fa054 | grep .github
git show --stat 40268f3 | grep .github
```

- [ ] **Step 2: Add new job (or extend existing)**

If adding a sibling job to an existing workflow, the job stanza pattern is:

```yaml
  p0-knob-smoke:
    name: Perf-engine P0 knob smoke
    runs-on: ubuntu-latest
    if: |
      github.event_name == 'pull_request' && (
        contains(toJSON(github.event.pull_request), 'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/') ||
        contains(toJSON(github.event.pull_request), 'sourbycraft-server/src/main/resources/sourbycraft.yml') ||
        contains(toJSON(github.event.pull_request), 'test-harness/scripts/p0-knob-smoke.sh') ||
        contains(toJSON(github.event.pull_request), 'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/DynamicPerformanceScaler.java') ||
        contains(toJSON(github.event.pull_request), 'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/StartupOptimizer.java') ||
        contains(toJSON(github.event.pull_request), 'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java') ||
        contains(toJSON(github.event.pull_request), 'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java')
      )
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '25'
      - name: Cache gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
      - name: Run smoke
        run: ./gradlew p0KnobSmokeTest -PrunP0KnobSmoke=true
```

Use the existing `nmsCompatTest`/`particleSmokeTest` job in the same workflow as the structural template — copy its setup-java version, cache key, and any other repo-specific steps. The `if:` condition above is the path filter; alternative idioms (`paths:` on `on:` block, or `dorny/paths-filter@v3`) may be preferred if the existing CI uses them — match the existing style.

If no fitting workflow exists, create `.github/workflows/p0-knob-smoke.yml` with the same content wrapped in a full workflow header:

```yaml
name: p0-knob-smoke
on:
  pull_request:
    paths:
      - 'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/**'
      - 'sourbycraft-server/src/main/resources/sourbycraft.yml'
      - 'test-harness/scripts/p0-knob-smoke.sh'
      - 'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/DynamicPerformanceScaler.java'
      - 'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/StartupOptimizer.java'
      - 'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfCommand.java'
      - 'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java'

jobs:
  smoke:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '25'
      - name: Run smoke
        run: ./gradlew p0KnobSmokeTest -PrunP0KnobSmoke=true
```

- [ ] **Step 3: Lint the workflow file**

Run:
```bash
yamllint .github/workflows/<file>.yml 2>/dev/null || true
```
(If `yamllint` isn't installed locally, skip — CI will catch malformed YAML on PR open.)

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/<file>.yml
git commit -m "ci: gate perf-engine P0 knob smoke on changed paths"
```

- [ ] **Step 5: Push branch + open PR + watch CI**

```bash
git push -u origin HEAD
```

Open a PR via `gh pr create` (only if user explicitly asks — per CLAUDE.md, do not push or open PRs unless requested).

Verify the smoke job triggers when the PR touches the gated paths.

---

## Final Verification (after all 5 tasks merged)

Run each acceptance check from spec Section 7:

- [ ] **A1. Package structure**: `ls sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/` returns `PerfKnob.java BoolKnob.java IntKnob.java KnobRegistry.java Knobs.java`.
- [ ] **A2. Sealed hierarchy**: `grep 'sealed abstract class PerfKnob' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/PerfKnob.java` matches.
- [ ] **A3. Reference knob declared**: `grep 'ENTITY_TICK_RATE' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java` matches.
- [ ] **A4. Field → method**: `grep 'public static int entityTickRate' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` shows `entityTickRate()` declaration.
- [ ] **A5. All callers updated**: `grep -rn 'SourbyCraftConfig\.entityTickRate[^(]' sourbycraft-server/ paper-server/ patches/ | grep -v build/` returns empty.
- [ ] **A6. Scaler writes via knob**: `grep 'Knobs.ENTITY_TICK_RATE.set' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/DynamicPerformanceScaler.java` matches.
- [ ] **A7. yml has perf section**: `grep -c '^perf:' sourbycraft-server/src/main/resources/sourbycraft.yml` returns `1`.
- [ ] **A8. Smoke script present**: `ls test-harness/scripts/p0-knob-smoke.sh` returns the file.
- [ ] **A9. Gradle task registered**: `./gradlew tasks --all -PrunP0KnobSmoke=true | grep p0KnobSmokeTest` lists the task.
- [ ] **A10. Smoke green**: `./gradlew p0KnobSmokeTest -PrunP0KnobSmoke=true` returns BUILD SUCCESSFUL, all 6 scenarios PASS.
- [ ] **A11. No new JUnit**: `git diff main --stat sourbycraft-server/src/test/` returns empty.
- [ ] **A12. Default boot unchanged**: clean boot of `test-harness/TestServer-mojmap/` with default sourbycraft.yml reaches `Done (` within prior baseline window (≤90s as configured).
- [ ] **A13. Plugin compat regression**: re-run the operator compat matrix (`docs/superpowers/notes/2026-06-04-nms-compat-operator-checklist.md` if applicable) with default config. No new failures vs the existing r1 baseline.

A14 (JMH bench) is optional and not gated. Skip unless reviewer requests.

---

## Self-Review

**Spec coverage** (each section/requirement maps to a task):

- Spec §3 C1 (`PerfKnob`) → Task 2 Step 3.
- Spec §3 C2 (`BoolKnob`) → Task 2 Step 4.
- Spec §3 C3 (`IntKnob`) → Task 2 Step 5.
- Spec §3 C4 (`KnobRegistry`) → Task 2 Step 6.
- Spec §3 C5 (`Knobs` holder + ENTITY_TICK_RATE) → Task 2 Step 7.
- Spec §3 C6 (`SourbyCraftConfig` integration: field → method + wire load) → Task 2 Step 9 (wire) + Task 3 Step 3 (field → method) + Task 3 Step 4 (Bukkit override re-route).
- Spec §3 C7 (`DynamicPerformanceScaler` write migration) → Task 3 Step 5.
- Spec §3 C8 (`sourbycraft.yml` perf section) → Task 2 Step 8.
- Spec §3 C9 (boot smoke test, 5 scenarios) → Task 1 (skeleton), Task 2 Step 1 (SCENARIO_1), Task 3 Step 1 (SCENARIO_2), Task 4 Step 1 (SCENARIOS 3, 4, 5).
- Spec §9 Phase 1 (skeleton) → Task 2 Steps 3–6.
- Spec §9 Phase 2 (declare ref knob + yml + wire load) → Task 2 Steps 7–9.
- Spec §9 Phase 3 (field → getter migration) → Task 3 Steps 3–7.
- Spec §9 Phase 4 (smoke harness) → Task 1.
- Spec §9 Phase 5 (CI gate) → Task 5.
- Spec §9 Phase 6 (optional JMH bench) → skipped, noted in plan header.

**Placeholder scan**: zero `TBD`, `TODO`, `implement later` in this plan. Two references to "match the existing style" (Task 5 Step 2) are operational guidance, not placeholder content — the alternative idiom is fully spelled out in the same step.

**Type consistency**:
- `PerfKnob.loadFrom()` (no-arg) — same signature in Task 2 Steps 3, 4, 5, 6.
- `Knobs.loadFromYml()` (no-arg) — same in Task 2 Steps 7, 9 + Task 3 Step 4.
- `Knobs.ENTITY_TICK_RATE.get()` — used in Task 3 Steps 4, 5, 6, 7.
- `Knobs.ENTITY_TICK_RATE.set(int)` — used in Task 3 Steps 4, 5, 7.
- `SourbyCraftConfig.entityTickRate()` — method form used in Task 3 Steps 3, 6, 7.
- `SourbyLogger.warn(String)` — single-arg concat form used in Task 2 Step 6.
- Default value `20`, range `1..20` — consistent in Task 2 Step 7 and Task 4 scenarios.
