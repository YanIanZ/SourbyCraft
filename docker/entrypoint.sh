#!/usr/bin/env sh
# SourbyCraft container entrypoint.
# Launches the server as PID 1 (exec) with single-JVM Auto-CDS + Aikar G1 tuning.
# No fork: the JVM itself is the tracked process, so SIGTERM = clean /stop and the
# container memory graph reflects the real heap.
set -eu

JAR="${SERVER_JAR:-/app/server.jar}"
DATA="${DATA_DIR:-/data}"
cd "$DATA"
mkdir -p cache

# EULA: set EULA=true to accept https://aka.ms/MinecraftEULA
if [ "${EULA:-}" = "true" ] || [ "${EULA:-}" = "TRUE" ]; then
  echo "eula=true" > eula.txt
fi

# Heap sizing. MEMORY / SERVER_MEMORY are MiB (SERVER_MEMORY is the panel egg var).
# With neither, let the JVM size from the cgroup limit (container-aware in JDK 25).
if [ -n "${MEMORY:-}" ]; then
  HEAP_FLAGS="-Xms${MEMORY}M -Xmx${MEMORY}M"
elif [ -n "${SERVER_MEMORY:-}" ]; then
  HEAP_FLAGS="-Xms${SERVER_MEMORY}M -Xmx${SERVER_MEMORY}M"
else
  HEAP_FLAGS="-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0"
fi

# Single-JVM CDS. Archive lives on the /data volume → survives restarts, self-heals
# when the jar or JDK changes. Because this flag is present, the SourbyBootstrap CDS
# layer stays out of the way (no fork).
CDS_FLAGS="-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=cache/sourbycraft.jsa"

# Aikar-style G1 flags, sized for large Minecraft heaps.
GC_FLAGS="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch \
-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M \
-XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 \
-XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 \
-XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 \
-XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"

# Force UTF-8 console + file encoding. The base image has no locale set, so stdout.encoding
# would default to US-ASCII and the branded box-drawing chars (═, …) print as ? / �.
ENCODING_FLAGS="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8"

# Resolve the SIMD incubator module so Luminol's SIMDConfig auto-uses vectorized ops (map
# colors, mob AI). The inline (no-fork) container boot can't add this to a running JVM, so it
# must be present at launch — this silences the "SIMD ... not configured" warning.
SIMD_FLAGS="--add-modules=jdk.incubator.vector"

# exec → java becomes PID 1 and receives SIGTERM directly for a clean shutdown.
# shellcheck disable=SC2086
exec java $ENCODING_FLAGS $SIMD_FLAGS $HEAP_FLAGS $CDS_FLAGS $GC_FLAGS ${JVM_OPTS:-} -jar "$JAR" --nogui "$@"
