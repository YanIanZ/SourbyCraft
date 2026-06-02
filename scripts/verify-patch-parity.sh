#!/usr/bin/env bash
# SourbyCraft v12 — verify PVP patch filtering convention.
# Run from repo root: bash scripts/verify-patch-parity.sh

set -euo pipefail

cd "$(dirname "$0")/.."

# PVP patches live in patches/minecraft/ (NMS-tier) per paperweight 2.0 convention.
shared_count=$(ls patches/minecraft/[0-8]*-*.patch 2>/dev/null | wc -l | tr -d ' ')
pvp_count=$(ls patches/minecraft/9*-*.patch 2>/dev/null | wc -l | tr -d ' ')

echo "Shared minecraft patches: $shared_count"
echo "PVP patches:              $pvp_count"

if [ "$pvp_count" -lt 1 ]; then
  echo "WARN: no PVP patches found (expected 9001-9005). Continue anyway." >&2
fi

fail=0
for f in patches/minecraft/9*-*.patch 2>/dev/null; do
  # Bash glob may yield literal pattern if no match — skip non-existent
  [ -e "$f" ] || continue
  name=$(basename "$f")
  if [[ ! "$name" =~ ^9[0-9]{3}-PVP-.+\.patch$ ]]; then
    echo "ERROR: $name does not match 9XXX-PVP-*.patch convention" >&2
    fail=1
  fi
done

# Also fail if any patches/server/ or patches/api/ file has the 9XXX prefix
# (PVP patches should only live in patches/minecraft/)
for d in patches/server patches/api; do
  if ls "$d"/9*-*.patch >/dev/null 2>&1; then
    echo "ERROR: PVP-prefixed patches found in $d/ — they should live in patches/minecraft/" >&2
    fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "OK"
  exit 0
else
  exit 1
fi
