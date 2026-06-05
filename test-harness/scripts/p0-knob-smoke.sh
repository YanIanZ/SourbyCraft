#!/usr/bin/env bash
# Perf-engine P0 — knob registry boot smoke.
# Runs 6 scenarios (0_boot_sanity, 1_default, 2_in_range, 3_clamp_hi, 4_clamp_lo,
# 5_wrong_type) against test-harness/TestServer-mojmap/, each with a different
# sourbycraft.yml entity-block override. Asserts via boot.log grep.
#
# Exit codes:
#   0 = all scenarios PASS
#   1 = missing release jar
#   2 = server died before Done (
#   3 = boot timeout
#   4 = logre assertion failed inside boot_and_assert
#   5 = scenario 2 unexpected clamp WARN
#   6 = scenario needs sourbycraft.yml but file missing
#   7 = scenario 3 final knob value wrong (clamp_hi)
#   8 = scenario 4 final knob value wrong (clamp_lo)
#   9 = scenario 5 unexpected clamp WARN (wrong_type)

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
HARNESS_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
ROOT_DIR="$( cd "$HARNESS_DIR/.." && pwd )"

JAR_SRC="$ROOT_DIR/release/SourbyCraft-v12-REL.jar"
TS_DIR="$HARNESS_DIR/TestServer-mojmap"
PORT=25600
RCON_PORT=25675
RCON_PASS=p0test

cleanup() {
    if [[ -f "$TS_DIR/.server.pid" ]]; then
        local pid
        pid=$(cat "$TS_DIR/.server.pid")
        kill -TERM "$pid" 2>/dev/null || true
        sleep 3
        kill -KILL "$pid" 2>/dev/null || true
        rm -f "$TS_DIR/.server.pid"
    fi
}
trap cleanup EXIT INT TERM

if [[ ! -f "$JAR_SRC" ]]; then
    echo "ERROR: $JAR_SRC missing. Run gradle assembleReleaseArtifacts first." >&2
    exit 1
fi

mkdir -p "$TS_DIR/plugins/SourbyCraft"
cp "$JAR_SRC" "$TS_DIR/server.jar"
echo "eula=true" > "$TS_DIR/eula.txt"

# Seed server.properties: only write the RCON+port+online-mode lines we need;
# preserve any other keys Paper has previously written.
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
        # Append any existing keys that we didn't override.
        grep -v -E '^(server-port|online-mode|enable-rcon|rcon\.port|rcon\.password|broadcast-rcon-to-ops)=' "$sp" >> "$tmp" || true
    fi
    mv "$tmp" "$sp"
}
seed_server_properties

# Capture the baseline sourbycraft.yml entity block after first boot so we can restore it
# between scenarios. Written after Step 0 boots (which lets the server generate the full file).
SOURBYCRAFT_YML_ENTITY_DEFAULT='entity:
  tick-rate-limit: false
  tick-rate: 20'

seed_sourbycraft_entity_block() {
    local sc="$TS_DIR/sourbycraft.yml"
    if [[ -f "$sc" ]]; then
        local tmp="$sc.seed"
        python3 - "$sc" "$tmp" <<'PYEOF'
import sys, re
src, dst = sys.argv[1], sys.argv[2]
with open(src) as f:
    text = f.read()
default_block = "entity:\n  tick-rate-limit: false\n  tick-rate: 20"
text = re.sub(r'^entity:(?:\n  [^\n]*)*', default_block, text, flags=re.MULTILINE)
with open(dst, 'w') as f:
    f.write(text)
PYEOF
        mv "$tmp" "$sc"
    fi
}

boot_and_assert() {
    local scenario="$1"
    local logre="$2"  # boot.log regex (basic grep -E); empty = no log assertion
    local sourbycraft_entity_override="${3:-}"  # entity: block content to inject into sourbycraft.yml; empty = leave as-is

    echo "p0-knob-smoke: scenario=$scenario"

    # Reset operator yml to known defaults before each scenario.
    seed_sourbycraft_entity_block

    # If a sourbycraft.yml entity-block override is provided, inject it before boot.
    # sourbycraft.yml is the operator config read by SourbyCraftConfig.init().
    if [[ -n "$sourbycraft_entity_override" ]]; then
        if [[ ! -f "$TS_DIR/sourbycraft.yml" ]]; then
            echo "ERROR: scenario=$scenario needs sourbycraft.yml present but file missing; SCENARIO_0 should have created it" >&2
            exit 6
        fi
        local sc="$TS_DIR/sourbycraft.yml"
        local tmp="$sc.override"
        local override_file="$sc.override_block"
        printf '%s\n' "$sourbycraft_entity_override" > "$override_file"
        # Replace the entity: block (and its indented children) with the override content.
        python3 - "$sc" "$override_file" "$tmp" <<'PYEOF'
import sys, re
src, over_f, dst = sys.argv[1], sys.argv[2], sys.argv[3]
with open(over_f) as f:
    override = f.read()
with open(src) as f:
    text = f.read()
# Replace entity: block (entity: followed by indented lines) with override.
text = re.sub(r'^entity:(?:\n  [^\n]*)*', override.rstrip('\n'), text, flags=re.MULTILINE)
with open(dst, 'w') as f:
    f.write(text)
PYEOF
        mv "$tmp" "$sc"
        rm -f "$override_file"
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

# === SCENARIO_1_DEFAULT ===
# yml omits perf block entirely; expect default value 20 (preserves existing behavior).
# Assertion: KnobRegistry prints a summary line at boot listing loaded knob values.
# StartupOptimizer.print() is not yet wired into server startup (Task 3+ concern);
# the registry log line is the canonical proof that loadFromYml() ran and the value loaded.
boot_and_assert "1_default_no_perf_block" \
  "perf knobs loaded \[boot\]:.*perf\.entity-tick-rate=20" ""

# === SCENARIO_2_IN_RANGE ===
# Operator sourbycraft.yml `entity.tick-rate: 4` should bridge into Knobs.ENTITY_TICK_RATE.
# SourbyCraftConfig reads from sourbycraft.yml (server root); the bridge routes the Bukkit-config
# read through Knobs.ENTITY_TICK_RATE.set() so the knob reflects the operator value.
# After Task 3 wiring, KnobRegistry log line shows perf.entity-tick-rate=4.
SCENARIO_2_CFG='entity:
  tick-rate-limit: true
  tick-rate: 4'
boot_and_assert "2_in_range_rate_4" \
  "perf knobs loaded \[boot\]:.*perf\.entity-tick-rate=4" \
  "$SCENARIO_2_CFG"

# Negative assertion: no clamp WARN should appear (4 is in-range)
if grep -E -q "knob 'perf\.entity-tick-rate' value 4 clamped" "$TS_DIR/boot.log"; then
    echo "ERROR: scenario=2 unexpected clamp WARN" >&2; exit 5
fi

# === SCENARIO_3_CLAMP_HI ===
# Operator sourbycraft.yml `entity.tick-rate: 99` exceeds IntKnob max of 20.
# The Task 3 bridge (SourbyCraftConfig.init) calls Knobs.ENTITY_TICK_RATE.set(99),
# which clamps to 20 and emits a KnobRegistry.warnOnce WARN line.
SCENARIO_3_CFG='entity:
  tick-rate-limit: true
  tick-rate: 99'
boot_and_assert "3_clamp_hi_99" \
  "knob 'perf\.entity-tick-rate' value 99 clamped to 20" \
  "$SCENARIO_3_CFG"
# Also verify the final knob summary shows the clamped value
if ! grep -E -q "perf knobs loaded \[boot\]:.*perf\.entity-tick-rate=20([^0-9]|$)" "$TS_DIR/boot.log"; then
    echo "ERROR: scenario=3 final knob value not 20 after clamp" >&2; exit 7
fi

# === SCENARIO_4_CLAMP_LO ===
# Operator sourbycraft.yml `entity.tick-rate: 0` is below IntKnob min of 1.
# The Task 3 bridge calls Knobs.ENTITY_TICK_RATE.set(0), which clamps to 1
# and emits a KnobRegistry.warnOnce WARN line.
SCENARIO_4_CFG='entity:
  tick-rate-limit: true
  tick-rate: 0'
boot_and_assert "4_clamp_lo_0" \
  "knob 'perf\.entity-tick-rate' value 0 clamped to 1" \
  "$SCENARIO_4_CFG"
# Also verify the final knob summary shows the clamped value
if ! grep -E -q "perf knobs loaded \[boot\]:.*perf\.entity-tick-rate=1([^0-9]|$)" "$TS_DIR/boot.log"; then
    echo "ERROR: scenario=4 final knob value not 1 after clamp" >&2; exit 8
fi

# === SCENARIO_5_WRONG_TYPE ===
# Operator sourbycraft.yml `entity.tick-rate: "high"` — string where int is expected.
# Paper's YamlConfiguration.getInt(path, default) silently returns the supplied default
# (the knob's current value, 20) without emitting any WARN. The bridge then calls
# Knobs.ENTITY_TICK_RATE.set(20), which is in-range so no clamp WARN fires either.
# Assert safe-fallback: boot succeeds, final knob value is default 20, no clamp WARN.
# NOTE: the originally-planned type-mismatch WARN (from SourbyCraftConfig.warnOnce /
# ymlInt path) is not reachable via operator sourbycraft.yml — that path only fires
# for JAR-baked yml reads, not Bukkit-config getInt() calls.
SCENARIO_5_CFG='entity:
  tick-rate-limit: true
  tick-rate: "high"'
boot_and_assert "5_wrong_type_string" \
  "perf knobs loaded \[boot\]:.*perf\.entity-tick-rate=20" \
  "$SCENARIO_5_CFG"
if grep -E -q "knob 'perf\.entity-tick-rate' value .* clamped" "$TS_DIR/boot.log"; then
    echo "ERROR: scenario=5 unexpected clamp WARN (string should have fallen back silently)" >&2; exit 9
fi

echo "p0-knob-smoke: all scenarios PASS"
