#!/usr/bin/env bash
# Downloads the latest stable Spark plugin into ./plugins/
# Usage: ./scripts/install-spark.sh [server-dir]
#   server-dir defaults to the current working directory.
set -euo pipefail

SERVER_DIR="${1:-.}"
PLUGINS_DIR="${SERVER_DIR%/}/plugins"
SPARK_URL="https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-bukkit/build/libs/spark-1.10.143-bukkit.jar"
TARGET="${PLUGINS_DIR}/spark.jar"

mkdir -p "${PLUGINS_DIR}"

if [ -f "${TARGET}" ]; then
    echo "Spark already installed at ${TARGET}. Re-downloading to update."
fi

echo "Downloading Spark from ${SPARK_URL}…"
if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "${TARGET}" "${SPARK_URL}"
elif command -v wget >/dev/null 2>&1; then
    wget -q -O "${TARGET}" "${SPARK_URL}"
else
    echo "Error: neither curl nor wget is installed." >&2
    exit 1
fi

echo "Spark installed to ${TARGET}."
echo "Start the server, then run /spark profiler in-game or from console."
