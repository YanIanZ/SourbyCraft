#!/bin/bash
# SourbyCraft GC Auto-Selector & Tuner
# Detects system specs and recommends optimal GC + flags
# Usage: ./gc-tuner.sh [--apply]

set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

# Detect system
OS=$(uname -s)
CORES=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
TOTAL_MEM_KB=$(grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}' || echo 0)
if [ "$TOTAL_MEM_KB" -eq 0 ]; then
    TOTAL_MEM_KB=$(sysctl -n hw.memsize 2>/dev/null | awk '{print $1/1024}' || echo 0)
fi
TOTAL_MEM_GB=$((TOTAL_MEM_KB / 1024 / 1024))
HEAP_GB=$((TOTAL_MEM_GB * 70 / 100))
[ "$HEAP_GB" -lt 2 ] && HEAP_GB=2
[ "$HEAP_GB" -gt 32 ] && HEAP_GB=32

echo -e "${GREEN}SourbyCraft GC Auto-Tuner${NC}"
echo "  CPU cores : $CORES"
echo "  Total RAM : ${TOTAL_MEM_GB}GB"
echo "  Heap suggestion : ${HEAP_GB}GB"
echo ""

# Select GC based on heap + cores
if [ "$HEAP_GB" -ge 8 ] && [ "$CORES" -ge 4 ]; then
    GC="zgc"
    GC_FLAGS="-XX:+UseZGC -XX:+ZGenerational -XX:SoftMaxHeapSize=${HEAP_GB}G"
    GC_THREADS=$((CORES / 4))
    [ "$GC_THREADS" -lt 1 ] && GC_THREADS=1
    GC_FLAGS="$GC_FLAGS -XX:ConcGCThreads=$GC_THREADS -XX:ParallelGCThreads=$((CORES / 2))"
    GC_FLAGS="$GC_FLAGS -XX:ZCollectionInterval=5 -XX:ZUncommitDelay=300"
elif [ "$HEAP_GB" -ge 4 ] && [ "$CORES" -ge 2 ]; then
    GC="shenandoah"
    GC_FLAGS="-XX:+UseShenandoahGC -XX:ShenandoahGCHeuristics=adaptive"
    GC_FLAGS="$GC_FLAGS -XX:ShenandoahAllocSpikeFactor=3 -XX:ConcGCThreads=$((CORES / 2))"
else
    GC="g1"
    GC_FLAGS="-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=4M"
    GC_FLAGS="$GC_FLAGS -XX:G1NewSizePercent=20 -XX:G1ReservePercent=10"
    GC_FLAGS="$GC_FLAGS -XX:ConcGCThreads=2 -XX:ParallelGCThreads=$((CORES / 2))"
fi

COMMON_FLAGS="-XX:+AlwaysPreTouch -XX:+UseTransparentHugePages -XX:+UseStringDeduplication"
COMMON_FLAGS="$COMMON_FLAGS -XX:+UseNUMA -XX:+UseContainerSupport"
COMMON_FLAGS="$COMMON_FLAGS -XX:+PerfDisableSharedMem"

JAVA_OPTS="$GC_FLAGS $COMMON_FLAGS -Xms${HEAP_GB}G -Xmx${HEAP_GB}G"

echo -e "${GREEN}Recommended GC:${NC} $GC"
echo ""
echo -e "${YELLOW}Generated flags:${NC}"
echo "$JAVA_OPTS"
echo ""

# Write config
CONFIG_FILE="${1:-paperclip.conf}"
echo "# SourbyCraft Auto-Tuned GC Config ($(date))" > "$CONFIG_FILE"
echo "# System: $CORES cores, ${TOTAL_MEM_GB}GB RAM" >> "$CONFIG_FILE"
echo "# Selected GC: $GC" >> "$CONFIG_FILE"
echo "" >> "$CONFIG_FILE"
echo "# Memory" >> "$CONFIG_FILE"
echo "-Xms${HEAP_GB}G" >> "$CONFIG_FILE"
echo "-Xmx${HEAP_GB}G" >> "$CONFIG_FILE"
echo "" >> "$CONFIG_FILE"
echo "# GC" >> "$CONFIG_FILE"
echo "$GC_FLAGS" | tr ' ' '\n' >> "$CONFIG_FILE"
echo "" >> "$CONFIG_FILE"
echo "# Common" >> "$CONFIG_FILE"
echo "$COMMON_FLAGS" | tr ' ' '\n' >> "$CONFIG_FILE"

echo -e "${GREEN}Written to: $CONFIG_FILE${NC}"

if [ "$2" = "--start" ]; then
    echo ""
    echo "Starting with: java @${CONFIG_FILE} -jar sourbycraft-paperclip.jar --nogui"
    java @${CONFIG_FILE} -jar sourbycraft-paperclip.jar --nogui
fi
