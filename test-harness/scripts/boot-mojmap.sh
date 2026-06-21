#!/usr/bin/env bash
# Boot SourbyCraft mojmap paperclip jar with all 4 test plugins; wait for "Done (" or timeout 90s.
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
HARNESS_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
ROOT_DIR="$( cd "$HARNESS_DIR/.." && pwd )"

JAR_SRC="$ROOT_DIR/release/SourbyCraft-12-REL.jar"
TS_DIR="$HARNESS_DIR/TestServer-mojmap"
PORT=25600

if [[ ! -f "$JAR_SRC" ]]; then
    echo "ERROR: $JAR_SRC missing. Run gradle assembleReleaseArtifacts first." >&2
    exit 1
fi

mkdir -p "$TS_DIR/plugins"
cp "$JAR_SRC" "$TS_DIR/server.jar"
echo "eula=true" > "$TS_DIR/eula.txt"

if [[ ! -f "$TS_DIR/server.properties" ]] || ! grep -q "^server-port=$PORT" "$TS_DIR/server.properties"; then
    printf "server-port=%s\nonline-mode=false\n" "$PORT" > "$TS_DIR/server.properties.seed"
    if [[ -f "$TS_DIR/server.properties" ]]; then
        grep -v "^server-port=\|^online-mode=" "$TS_DIR/server.properties" >> "$TS_DIR/server.properties.seed"
    fi
    mv "$TS_DIR/server.properties.seed" "$TS_DIR/server.properties"
fi

# Copy plugin jars (overwriting any older versions)
rm -f "$TS_DIR"/plugins/Citizens-*.jar "$TS_DIR"/plugins/NBTAPI-*.jar \
      "$TS_DIR"/plugins/DecentHolograms-*.jar "$TS_DIR"/plugins/FastAsyncWorldEdit-*.jar \
      "$TS_DIR"/plugins/sanity-harness-plugin*.jar
shopt -s nullglob
for j in "$HARNESS_DIR"/test-plugins/*.jar; do
    cp "$j" "$TS_DIR/plugins/"
done
shopt -u nullglob
SANITY_JAR="$HARNESS_DIR/sanity-harness-plugin/build/libs/sanity-harness-plugin.jar"
if [[ -f "$SANITY_JAR" ]]; then
    cp "$SANITY_JAR" "$TS_DIR/plugins/"
fi

cd "$TS_DIR"
rm -f boot.log nms-compat-result.json
java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
SERVER_PID=$!
echo "$SERVER_PID" > .server.pid
echo "boot-mojmap: PID $SERVER_PID, log=$TS_DIR/boot.log"

deadline=$(($(date +%s) + 90))
while [[ $(date +%s) -lt $deadline ]]; do
    if grep -q "Done (" boot.log 2>/dev/null; then
        echo "boot-mojmap: server up"
        exit 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "ERROR: boot-mojmap: server died before reaching Done (" >&2
        tail -50 boot.log >&2
        exit 2
    fi
    sleep 2
done

echo "ERROR: boot-mojmap: BOOT_TIMEOUT after 90s" >&2
tail -50 boot.log >&2
kill -TERM "$SERVER_PID" 2>/dev/null
sleep 5
kill -KILL "$SERVER_PID" 2>/dev/null || true
exit 3
