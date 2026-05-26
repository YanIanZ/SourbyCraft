#!/usr/bin/env bash
# SourbyCraft v9 launcher: auto-tunes heap and GC, then starts server.
# Usage: ./scripts/run-v9.sh [--gc auto|zgc|g1] [--jar <file>]
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "${DIR}/gc-tuner.sh" --apply "$@"
