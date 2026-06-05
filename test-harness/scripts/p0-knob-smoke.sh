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

# Seed plugins/SourbyCraft/config.yml: enable entity-tick-rate-limit so
# StartupOptimizer prints the "Entity Tick Rate:" line we assert on.
seed_sourbycraft_config() {
    local cfg="$TS_DIR/plugins/SourbyCraft/config.yml"
    local tmp="$cfg.seed"
    {
        echo "entity.tick-rate-limit: true"
        echo "entity.tick-rate: 20"
    } > "$tmp"
    if [[ -f "$cfg" ]]; then
        grep -v -E '^(entity\.tick-rate-limit|entity\.tick-rate):' "$cfg" >> "$tmp" || true
    fi
    mv "$tmp" "$cfg"
}
seed_sourbycraft_config

boot_and_assert() {
    local scenario="$1"
    local yml="$2"
    local logre="$3"  # boot.log regex (basic grep -E); empty = no log assertion

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

# === SCENARIO_1_DEFAULT ===
# yml omits perf block entirely; expect default value 20 (preserves existing behavior).
# Assertion: KnobRegistry prints a summary line at boot listing loaded knob values.
# StartupOptimizer.print() is not yet wired into server startup (Task 3+ concern);
# the registry log line is the canonical proof that loadFromYml() ran and the value loaded.
boot_and_assert "1_default_no_perf_block" "" \
  "\[SourbyCraft\] perf knobs loaded:.*perf\.entity-tick-rate=20"

# === SCENARIO_2_IN_RANGE (added in Task 3) ===
# === SCENARIO_3_CLAMP_HI (added in Task 4) ===
# === SCENARIO_4_CLAMP_LO (added in Task 4) ===
# === SCENARIO_5_WRONG_TYPE (added in Task 4) ===

echo "p0-knob-smoke: all scenarios PASS"
