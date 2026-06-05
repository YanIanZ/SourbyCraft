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
#   2 = server died before "Done (" line appeared
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
}
trap cleanup EXIT INT TERM

# Reset sourbycraft.yml to a known baseline (empty file = use jar defaults entirely)
seed_sourbycraft_baseline() {
    # Intentionally destructive — CI harness owns this file. Empty file = use jar defaults.
    : > "$TS_DIR/sourbycraft.yml"
}

# RCON via Python (no external deps). cmd is passed as argv to avoid shell expansion inside the heredoc.
rcon_cmd() {
    local cmd="$1"
    python3 - "$RCON_PASS" "$RCON_PORT" "$cmd" <<'PYEOF'
import socket, struct, sys
rcon_pass, rcon_port, cmd = sys.argv[1], int(sys.argv[2]), sys.argv[3]
s = socket.create_connection(("127.0.0.1", rcon_port), timeout=5)
def send(req_id, kind, body):
    pkt = struct.pack('<ii', req_id, kind) + body.encode('utf-8') + b'\x00\x00'
    s.sendall(struct.pack('<i', len(pkt)) + pkt)
def recv():
    ln = struct.unpack('<i', s.recv(4))[0]
    data = s.recv(ln)
    req_id, kind = struct.unpack('<ii', data[:8])
    return req_id, kind, data[8:-2].decode('utf-8', errors='replace')
send(1, 3, rcon_pass)
recv()
send(2, 2, cmd)
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
            echo "ERROR: scenario=$scenario server died before \"Done (\"" >&2
            tail -50 boot.log >&2
            cd - >/dev/null
            exit 2
        fi
        sleep 2
    done
    if [[ $ok -eq 0 ]]; then
        echo "ERROR: scenario=$scenario BOOT_TIMEOUT after 90s" >&2
        tail -50 boot.log >&2
        kill -TERM "$pid" 2>/dev/null || true
        sleep 5
        kill -KILL "$pid" 2>/dev/null || true
        cd - >/dev/null
        exit 3
    fi

    if [[ -n "$logre" ]]; then
        if ! grep -E -q "$logre" boot.log; then
            echo "ERROR: scenario=$scenario log assertion failed; expected regex: $logre" >&2
            tail -100 boot.log >&2
            kill -TERM "$pid" 2>/dev/null || true
            sleep 5
            kill -KILL "$pid" 2>/dev/null || true
            cd - >/dev/null
            exit 4
        fi
    fi

    # Sleep 10s post-boot to let sensor samples accumulate before any caller-side assertions run
    sleep 10

    # Shutdown server before next scenario can boot. Cleanup trap is belt-and-suspenders only.
    kill -TERM "$pid" 2>/dev/null || true
    sleep 5
    kill -KILL "$pid" 2>/dev/null || true
    cd - >/dev/null

    echo "p1-tier-smoke: scenario=$scenario PASS"
}

# === SCENARIO_0_BOOT (Task 1) ===
boot_and_assert "0_boot_sanity" "" ""

# === SCENARIO_1_DEFAULT_STAYS_GREEN ===
# No yml override. Default thresholds. Idle server should stay in GREEN through the
# 10s observation window inside boot_and_assert.
# Asserts on the sensor-loaded log line emitted by PerfSensor.loadFromYml so this
# scenario doubles as a smoke-test of the boot wiring.
boot_and_assert "1_default_stays_green" "perf sensor: cadence=20 dwell=3" ""
if grep -E -q "perf tier transition" "$TS_DIR/boot.log"; then
    echo "ERROR: scenario=1 unexpected tier transition in idle boot" >&2
    grep "perf tier transition" "$TS_DIR/boot.log" >&2
    exit 5
fi

# === SCENARIO_2_FORCE_YELLOW_VIA_MSPT ===
# Lower MSPT.yellow threshold to 0.001 ms so every server tick exceeds it.
# dwell-samples=1 so transition fires after the first sample.
SCENARIO_2_YML='perf:
  sensor:
    enabled: true
    warmup-ticks: 0
    dwell-samples: 1
    thresholds:
      mspt:
        yellow: 0.001
        orange: 1000
        red: 1000
        emergency: 1000
      tps:
        yellow: 1.0
        orange: 0.5
        red: 0.1
        emergency: 0.0
      mem:
        yellow: 99.0
        orange: 99.5
        red: 99.8
        emergency: 99.9
      gc-ms-per-min:
        yellow: 100000
        orange: 200000
        red: 300000
        emergency: 400000'
boot_and_assert "2_force_yellow_via_mspt" "" "$SCENARIO_2_YML"
if ! grep -E -q "perf tier transition: GREEN -> YELLOW" "$TS_DIR/boot.log"; then
    echo "ERROR: scenario=2 expected GREEN->YELLOW transition not found in boot.log" >&2
    grep "perf tier transition" "$TS_DIR/boot.log" >&2 || echo "(no tier transition lines found)" >&2
    exit 4
fi
echo "p1-tier-smoke: scenario=2 post-boot transition check PASS"
# === SCENARIO_3_DWELL_PREVENTS_TRANSIENT (added in Task 5) ===
# === SCENARIO_4_FORCE_EMERGENCY_VIA_MEM (added in Task 5) ===
# === SCENARIO_5_NON_MONOTONIC_WARN (added in Task 5) ===
# === SCENARIO_6_SENSOR_DISABLED (added in Task 5) ===
# === SCENARIO_7_PERF_SENSORS_CMD (added in Task 4) ===

echo "p1-tier-smoke: all scenarios PASS"
