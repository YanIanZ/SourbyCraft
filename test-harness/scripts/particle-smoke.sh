#!/usr/bin/env bash
# UniverseSpigot Foundation — particle suppression smoke test.
# Boots single-jar (mojmap) with particles.disableFallParticles=true and
# particles.disableDeathParticles=true, drops a zombie from y=64, kills it,
# and asserts both gated-branch DEBUG marker lines fire.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$ROOT/test-harness/TestServer-particle-smoke"
JAR_GLOB="$ROOT/release/SourbyCraft-v12-REL.jar"

rm -rf "$WORK"
mkdir -p "$WORK/plugins/SourbyCraft"

cp "$(ls $JAR_GLOB | head -1)" "$WORK/server.jar"
echo "eula=true" > "$WORK/eula.txt"
cp "$ROOT/test-harness/scripts/particle-smoke.conf.yml" "$WORK/plugins/SourbyCraft/sourbycraft.yml"

cd "$WORK"

# Run server in foreground, send commands via stdin pipe, capture stdout to boot.log.
# Deadline: ~90s for boot, then run the test commands, then "stop".
(
  sleep 90
  echo "summon minecraft:zombie ~ 64 ~"
  sleep 2
  echo "kill @e[type=minecraft:zombie,limit=1]"
  sleep 2
  echo "stop"
) | timeout 180 java -Xmx2G -Ddev.iyanz.sourbycraft.particle.debug=true \
    -jar server.jar nogui --nojline > boot.log 2>&1 || true

if ! grep -q 'Done (' boot.log; then
    echo "FAIL: server did not finish booting"
    tail -50 boot.log
    exit 1
fi

if ! grep -q '\[SourbyCraft\] particle gated:fall' boot.log; then
    echo "FAIL: fall-particle gate did not fire — patch may not be wired"
    grep '\[SourbyCraft\] particle' boot.log | head -5 || true
    exit 1
fi

if ! grep -q '\[SourbyCraft\] particle gated:death' boot.log; then
    echo "FAIL: death-particle gate did not fire — patch may not be wired"
    grep '\[SourbyCraft\] particle' boot.log | head -5 || true
    exit 1
fi

echo "PASS: both particle gates fired under toggles=on"
