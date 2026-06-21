#!/usr/bin/env bash
# Aggregate boot log + nms-compat-result.json from TestServer-mojmap/
# into docs/superpowers/notes/<date>-nms-compat-matrix-r<N>.md
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
HARNESS_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
ROOT_DIR="$( cd "$HARNESS_DIR/.." && pwd )"
NOTES_DIR="$ROOT_DIR/docs/superpowers/notes"
DATE_TODAY="$(date +%Y-%m-%d)"

mkdir -p "$NOTES_DIR"

round=1
while [[ -f "$NOTES_DIR/${DATE_TODAY}-nms-compat-matrix-r${round}.md" ]]; do
    round=$((round + 1))
done
OUT="$NOTES_DIR/${DATE_TODAY}-nms-compat-matrix-r${round}.md"

emit_variant() {
    local variant=$1 ts_dir=$2
    local json="$ts_dir/nms-compat-result.json"
    if [[ ! -f "$json" ]]; then
        for p in Citizens NBTAPI DecentHolograms FastAsyncWorldEdit; do
            printf "| %-7s | %-18s | %-7s | %-7s | %-40s | %s |\n" \
                "$variant" "$p" "?" "?" "NO_RESULT_FILE" ""
        done
        return
    fi
    python3 -c "
import json, sys
data = json.load(open('$json'))
for row in data:
    print('|', '$variant', '|', row.get('plugin','?'), '|',
          'yes' if row.get('enabled') else 'no', '|',
          'yes' if row.get('sanity_passed') else 'no', '|',
          (row.get('fail_reason','') or '')[:40], '|',
          (row.get('stack_hash','') or '')[:8], '|')
"
}

cat > "$OUT" <<EOF
# NMS-compat matrix r${round} — ${DATE_TODAY}

Single-jar (mojmap) shipping per 2026-06-04 spec revision. Reobf jar dropped:
paperweight 2.0 deprecates reobf builds and the bypass produces a jar that crashes
at boot with MCTypeRegistry initializer error.

| Variant | Plugin             | Enabled | Sanity  | Fail reason                              | Stack    |
| ------- | ------------------ | ------- | ------- | ---------------------------------------- | -------- |
EOF

emit_variant "mojmap" "$HARNESS_DIR/TestServer-mojmap" >> "$OUT"

cat >> "$OUT" <<EOF

## Legend

- **Enabled**: \`Bukkit.getPluginManager().getPlugin(name)\` non-null + \`isEnabled() == true\`.
- **Sanity**: per-plugin fixture executed without exception. See
  \`test-harness/sanity-harness-plugin/src/main/java/dev/iyanz/sourbycraft/nms/SanityFixtures.java\`.
- **Fail reason**: exception class + first 40 chars of message. Truncated; full trace in boot.log.
- **Stack**: first 8 hex chars of sha1(normalized stack trace). Stable across runs for the same bug.

## Plugin sources

Pin versions in \`test-harness/test-plugins/manifest.yml\`. Latest fetch timestamp:
\`$(stat -f '%Sm' "$HARNESS_DIR/test-plugins/manifest.yml" 2>/dev/null || stat -c '%y' "$HARNESS_DIR/test-plugins/manifest.yml" 2>/dev/null)\`

## Investigation notes

(populate per row during Phase 3 fixes)
EOF

echo "Matrix written to $OUT"
