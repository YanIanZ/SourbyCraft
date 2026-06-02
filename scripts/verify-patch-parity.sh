#!/usr/bin/env bash
# SourbyCraft v12 — verify PVP patch filtering convention.
# Run from repo root: bash scripts/verify-patch-parity.sh

set -uo pipefail

cd "$(dirname "$0")/.."

# PVP patches live in patches/minecraft/ (NMS-tier) per paperweight 2.0 convention.
shared_count=$(ls patches/minecraft/[0-8]*-*.patch 2>/dev/null | wc -l | tr -d ' ')
pvp_count=$(ls patches/minecraft/9*-*.patch 2>/dev/null | wc -l | tr -d ' ')

echo "Shared minecraft patches: $shared_count"
echo "PVP patches:              $pvp_count"

if [ "$pvp_count" -lt 1 ]; then
  echo "WARN: no PVP patches found (expected 9001-9005 once Phase F lands)" >&2
fi

fail=0

# Naming convention: PVP patches must match 9XXX-PVP-*.patch
shopt -s nullglob
for f in patches/minecraft/9*-*.patch; do
  name=$(basename "$f")
  if [[ ! "$name" =~ ^9[0-9]{3}-PVP-.+\.patch$ ]]; then
    echo "ERROR: $name does not match 9XXX-PVP-*.patch convention" >&2
    fail=1
  fi
done

# Location: 9XXX-prefixed patches must NOT live in patches/server/ or patches/api/
for d in patches/server patches/api; do
  if compgen -G "$d/9*-*.patch" > /dev/null; then
    echo "ERROR: PVP-prefixed patches found in $d/ — they belong in patches/minecraft/" >&2
    fail=1
  fi
done
shopt -u nullglob

if [ "$fail" -eq 0 ]; then
  echo "OK"
  exit 0
else
  exit 1
fi
