#!/bin/bash
# Semaphore test for scripts/gradle_locked.sh: three builds in three different trees start at once;
# at most two may run at the same time (machine-wide slots), and two must actually overlap (the trees
# have independent locks). Each tree has a fake ./gradlew; nothing touches the real lock directory.
# Run: bash scripts/test_gradle_locked.sh   (also run by scripts/test_gradle_locked.py)
set -u
here=$(cd "$(dirname "$0")" && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/gradle-locked-test.XXXXXX")
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/locks" "$work/tmp" "$work/running" "$work/main"
git -C "$work/main" init -q
cat > "$work/fake-gradlew" <<'EOF'
#!/bin/bash
# Fake Gradle: mark this build as running, record how many builds run at once, hold for a while.
id=$(basename "$PWD")
touch "$RUN_DIR/$id"
n=$(ls "$RUN_DIR" | wc -l | tr -d ' ')
echo "$id start running=$n tmpdir=$TMPDIR" >> "$EVENTS"
sleep "${FAKE_HOLD:-3}"
echo "$id end" >> "$EVENTS"
rm -f "$RUN_DIR/$id"
EOF
chmod +x "$work/fake-gradlew"
cp "$work/fake-gradlew" "$work/main/gradlew"
for t in tree-a tree-b tree-c; do
  mkdir -p "$work/$t"; git -C "$work/$t" init -q; cp "$work/fake-gradlew" "$work/$t/gradlew"
done

export SOURBY_MAIN_TREE="$work/main" SOURBY_LOCK_ROOT="$work/locks" SOURBY_TMP_ROOT="$work/tmp"
export SOURBY_GRADLE_SLOTS=2 SOURBY_GRADLE_POLL_SECS=1 RUN_DIR="$work/running" EVENTS="$work/events"
pids=()
for t in tree-a tree-b tree-c; do
  SOURBY_TREE="$work/$t" AGENT_NAME="test-$t" bash "$here/gradle_locked.sh" > "$work/$t.out" 2>&1 &
  pids+=($!)
done
fail=0
for p in "${pids[@]}"; do wait "$p" || { echo "FAIL: a wrapper exited non-zero"; fail=1; }; done

max=$(sed -n 's/.* start running=\([0-9]*\).*/\1/p' "$work/events" | sort -n | tail -1)
starts=$(grep -c ' start ' "$work/events")
echo "events:"; sed 's/^/  /' "$work/events"
[ "$starts" -eq 3 ] || { echo "FAIL: expected 3 builds, saw $starts"; fail=1; }
[ "${max:-0}" -le 2 ] || { echo "FAIL: $max builds ran at once (limit 2)"; fail=1; }
[ "${max:-0}" -eq 2 ] || { echo "FAIL: builds never overlapped (max $max), the trees should run in parallel"; fail=1; }
# The third build may only start after one of the first two ended.
third_line=$(grep -n ' start ' "$work/events" | sed -n 3p | cut -d: -f1)
first_end=$(grep -n ' end' "$work/events" | head -1 | cut -d: -f1)
[ -n "$third_line" ] && [ -n "$first_end" ] && [ "$third_line" -gt "$first_end" ] \
  || { echo "FAIL: third build started before any build ended"; fail=1; }
# Each tree gets its own TMPDIR; every lock and slot is released afterwards.
for t in tree-a tree-b tree-c; do
  grep -q "^$t start .*tmpdir=$work/tmp/sourbycraft-wt/$t/\$" "$work/events" || { echo "FAIL: $t TMPDIR"; fail=1; }
done
left=$(ls "$work/locks")
[ -z "$left" ] || { echo "FAIL: locks left behind: $left"; fail=1; }
grep -q 'waiting for a build slot' "$work"/tree-*.out || { echo "FAIL: no build waited for a slot"; fail=1; }

[ "$fail" -eq 0 ] && echo "OK: 3 builds, max $max at once, all locks released"
exit "$fail"
