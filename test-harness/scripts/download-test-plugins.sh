#!/usr/bin/env bash
# Idempotent fetch of NMS-compat test plugins. Reads manifest.yml, verifies sha256,
# fetches missing or mismatched files with 3x retry + exponential backoff.

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
HARNESS_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
MANIFEST="$HARNESS_DIR/test-plugins/manifest.yml"
PLUGINS_DIR="$HARNESS_DIR/test-plugins"

if [[ ! -f "$MANIFEST" ]]; then
    echo "ERROR: manifest not found at $MANIFEST" >&2
    exit 1
fi

sha256() {
    if command -v sha256sum > /dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

retry_fetch() {
    local url=$1
    local out=$2
    local attempt=1
    local delay=5
    while [[ $attempt -le 3 ]]; do
        if curl -fsSL --connect-timeout 30 --max-time 300 -o "$out" "$url"; then
            return 0
        fi
        echo "  fetch attempt $attempt failed; retrying in ${delay}s..." >&2
        sleep "$delay"
        delay=$((delay * 3))
        attempt=$((attempt + 1))
    done
    return 1
}

# Parse manifest with awk (avoids python/yaml dep).
awk '
/^  - name:/    { name=$3 }
/^    version:/ { ver=$2; gsub(/"/,"",ver) }
/^    url:/     { url=$2; gsub(/"/,"",url) }
/^    sha256:/  { sha=$2; gsub(/"/,"",sha); print name "|" ver "|" url "|" sha }
' "$MANIFEST" | while IFS='|' read -r name version url declared_sha; do
    [[ -z "$name" ]] && continue
    out="$PLUGINS_DIR/${name}-${version}.jar"

    if [[ -f "$out" ]]; then
        actual=$(sha256 "$out")
        if [[ -z "$declared_sha" ]]; then
            echo "$name: present, computing initial sha256=$actual (paste into manifest)"
            continue
        fi
        if [[ "$actual" == "$declared_sha" ]]; then
            echo "$name: cached OK ($declared_sha)"
            continue
        fi
        echo "$name: sha256 mismatch (expected $declared_sha, got $actual); refetching" >&2
        rm -f "$out"
    fi

    echo "$name: fetching $url"
    if ! retry_fetch "$url" "$out"; then
        echo "ERROR: $name: download failed after 3 retries from $url" >&2
        exit 1
    fi

    actual=$(sha256 "$out")
    if [[ -n "$declared_sha" && "$actual" != "$declared_sha" ]]; then
        echo "ERROR: $name: sha256 mismatch (expected $declared_sha, got $actual)" >&2
        exit 1
    fi
    echo "$name: fetched OK, sha256=$actual"
done

echo "All plugins ready in $PLUGINS_DIR"
