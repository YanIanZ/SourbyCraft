#!/bin/bash
# SourbyCraft GC Auto-Selector & Tuner
# Detects system specs and recommends optimal GC + flags
# Usage: ./gc-tuner.sh [OPTIONS]
#   --apply          Write flags to start.flags and start server
#   --flags-only     Write flags to start.flags (no start)
#   --jar <file>     Specify server JAR (default: sourbycraft-paperclip-v4-REL-mojmap.jar)

set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

# Defaults
JAR_FILE="sourbycraft-paperclip-v4-REL-mojmap.jar"
START=false
FLAGS_ONLY=false

# Parse args
while [ $# -gt 0 ]; do
    case "$1" in
        --apply|--start) START=true; shift ;;
        --flags-only) FLAGS_ONLY=true; shift ;;
        --jar) JAR_FILE="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: ./gc-tuner.sh [--apply|--start] [--flags-only] [--jar <file>]"
            echo "  --apply, --start   Write flags and start server"
            echo "  --flags-only       Write flags to start.flags (no start)"
            echo "  --jar <file>       Server JAR file (default: $JAR_FILE)"
            exit 0 ;;
        *) shift ;;
    esac
done

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
    GC_FLAGS="$GC_FLAGS -XX:ZCollectionInterval=5 -XX:ZUncommitDelay=300 -XX:ZAllocationSpikeTolerance=3"
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

COMMON_FLAGS="-XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseContainerSupport"
COMMON_FLAGS="$COMMON_FLAGS -XX:CICompilerCount=2 -XX:TieredStopAtLevel=1"
COMMON_FLAGS="$COMMON_FLAGS -XX:MaxRAMPercentage=95.0"
COMMON_FLAGS="$COMMON_FLAGS --add-modules=jdk.incubator.vector"
COMMON_FLAGS="$COMMON_FLAGS -Dterminal.jline=false -Dterminal.ansi=true"

JAVA_OPTS="$GC_FLAGS $COMMON_FLAGS"

echo -e "${GREEN}Recommended GC:${NC} $GC"
echo ""
echo -e "${YELLOW}Generated flags:${NC}"
echo "$JAVA_OPTS"
echo ""

# Write config
CONFIG_FILE="start.flags"
echo "# SourbyCraft Auto-Tuned GC Config ($(date))" > "$CONFIG_FILE"
echo "# System: $CORES cores, ${TOTAL_MEM_GB}GB RAM" >> "$CONFIG_FILE"
echo "# Selected GC: $GC" >> "$CONFIG_FILE"
echo "" >> "$CONFIG_FILE"
echo "$JAVA_OPTS" >> "$CONFIG_FILE"

echo -e "${GREEN}Written to: $CONFIG_FILE${NC}"

if [ "$START" = true ]; then
    echo ""
    echo -e "${GREEN}Starting server...${NC}"
    echo "  java @${CONFIG_FILE} -jar $JAR_FILE --nogui"
    java @${CONFIG_FILE} -jar "$JAR_FILE" --nogui
elif [ "$FLAGS_ONLY" != true ]; then
    echo ""
    echo "To start the server:"
    echo "  java @${CONFIG_FILE} -jar $JAR_FILE --nogui"
    echo ""
    echo "Or re-run with --apply to auto-start."
fi