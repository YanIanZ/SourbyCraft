#!/bin/bash
# SourbyCraft GC Auto-Selector & Tuner
# Detects system specs and recommends optimal GC + flags
# Usage: ./gc-tuner.sh [OPTIONS]
#   --apply          Write flags to start.flags and start server
#   --flags-only     Write flags to start.flags (no start)
#   --jar <file>     Specify server JAR (default: sourbycraft-paperclip-v5-REL-mojmap.jar)

set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

# Defaults
JAR_FILE="sourbycraft-paperclip-v5-REL-mojmap.jar"
START=false
FLAGS_ONLY=false

# Parse args
while [ $# -gt 0 ]; do
    case "$1" in
        --apply|--start) START=true; shift ;;
        --flags-only) FLAGS_ONLY=true; shift ;;
        --jar) JAR_FILE="$2"; shift 2 ;;
        --gc) GC_CHOICE="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: ./gc-tuner.sh [--apply|--start] [--flags-only] [--jar <file>] [--gc auto|zgc|g1]"
            echo "  --apply, --start   Write flags and start server"
            echo "  --flags-only       Write flags to start.flags (no start)"
            echo "  --jar <file>       Server JAR file (default: $JAR_FILE)"
            echo "  --gc <choice>      auto (default), zgc, g1"
            exit 0 ;;
        *) shift ;;
    esac
done
GC_CHOICE="${GC_CHOICE:-auto}"

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

# Decide GC. Auto-rule: ZGC generational if heap >= 8 GB AND Java >= 21, else G1.
JAVA_MAJOR=$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | awk -F'.' '{print ($1 == "1") ? $2 : $1}')
if [ "$GC_CHOICE" = "auto" ]; then
    if [ "$HEAP_GB" -ge 8 ] && [ "${JAVA_MAJOR:-0}" -ge 21 ]; then
        GC_CHOICE="zgc"
    else
        GC_CHOICE="g1"
    fi
fi

case "$GC_CHOICE" in
    zgc)
        GC_FLAGS="-XX:+UseZGC -XX:+ZGenerational -XX:+UseTransparentHugePages -XX:+AlwaysPreTouch -XX:+UseCompressedOops"
        echo -e "${GREEN}GC selected:${NC} ZGC generational (heap ${HEAP_GB}GB, Java ${JAVA_MAJOR})"
        ;;
    g1)
        GC_FLAGS="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UseTransparentHugePages -XX:+AlwaysPreTouch -XX:G1HeapRegionSize=8M"
        echo -e "${YELLOW}GC selected:${NC} G1 (heap ${HEAP_GB}GB, Java ${JAVA_MAJOR})"
        ;;
    *)
        echo -e "${RED}Unknown --gc choice: $GC_CHOICE${NC}" >&2
        exit 1
        ;;
esac

FLAGS="-Xms${HEAP_GB}G -Xmx${HEAP_GB}G ${GC_FLAGS}"
if [ "$FLAGS_ONLY" = "true" ] || [ "$START" = "true" ]; then
    echo "$FLAGS" > start.flags
    echo "Wrote: start.flags"
fi
if [ "$START" = "true" ]; then
    java $FLAGS -jar "$JAR_FILE" nogui
fi