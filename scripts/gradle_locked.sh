#!/bin/bash
# Runs ./gradlew for one SourbyCraft tree with a per-tree lock plus a machine-wide build semaphore.
#
#   AGENT_NAME=<you> scripts/gradle_locked.sh <gradle args...>
#
# Tree: $SOURBY_TREE, else the git toplevel of the current directory (climbing out of the nested
# materialized repos such as sourbycraft-server/src/minecraft/java), else the main tree.
# Locks (mkdir + owner file). A lock is broken when its owner's pid is gone; an owner line without a pid
# (written by the old scratchpad wrapper) is broken once the lock is older than the stale age (2400 s).
#   main tree      $LOCK_ROOT/sourby-gradle.lock              (the historical global lock path)
#   other trees    $LOCK_ROOT/sourby-gradle-<tree-name>.lock
#   semaphore      $LOCK_ROOT/sourby-gradle-slot-{1..N}       (N=2: at most two Gradle builds at once, RAM)
# The tree lock is taken first, then a slot, so a build waiting for its tree never holds a slot.
# TMPDIR and java.io.tmpdir stay on /Volumes/Ext (the system disk is full), one subdirectory per tree.
#
# Overrides (tests): SOURBY_MAIN_TREE, SOURBY_LOCK_ROOT, SOURBY_TMP_ROOT, SOURBY_GRADLE_SLOTS,
# SOURBY_GRADLE_POLL_SECS (whole seconds), SOURBY_GRADLE_STALE_SECS.
set -u

main_tree=${SOURBY_MAIN_TREE:-/Volumes/Ext/Sourby/SourbyCraft}
lock_root=${SOURBY_LOCK_ROOT:-/private/tmp/claude-501}
tmp_root=${SOURBY_TMP_ROOT:-/Volumes/Ext/DevCache/agent-tmp}
slots=${SOURBY_GRADLE_SLOTS:-2}
poll=${SOURBY_GRADLE_POLL_SECS:-5}
stale_after=${SOURBY_GRADLE_STALE_SECS:-2400}
agent=${AGENT_NAME:-$$}

log() { echo "[gradle_locked] $(date '+%Y-%m-%dT%H:%M:%S') $*"; }

resolve_tree() {
  if [ -n "${SOURBY_TREE:-}" ]; then
    if [ ! -x "$SOURBY_TREE/gradlew" ]; then echo "[gradle_locked] SOURBY_TREE=$SOURBY_TREE has no ./gradlew" >&2; return 1; fi
    (cd "$SOURBY_TREE" && pwd -P); return
  fi
  local dir top
  dir=$(pwd -P)
  while top=$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null) && [ -n "$top" ]; do
    if [ -x "$top/gradlew" ]; then (cd "$top" && pwd -P); return; fi
    [ "$top" = "/" ] && break
    dir=$(dirname "$top")
  done
  (cd "$main_tree" && pwd -P)
}

tree=$(resolve_tree) || exit 2
main_real=$(cd "$main_tree" 2>/dev/null && pwd -P || echo "$main_tree")
if [ "$tree" = "$main_real" ]; then
  tree_name=main
  tree_lock=$lock_root/sourby-gradle.lock
  tree_tmp=$tmp_root/sourbycraft
else
  tree_name=$(basename "$tree")
  tree_lock=$lock_root/sourby-gradle-$tree_name.lock
  tree_tmp=$tmp_root/sourbycraft-wt/$tree_name
fi

# Stale: the owner pid no longer runs; or, for an owner line without a pid, the lock is older than $stale_after.
# Breaking renames the directory first (atomic), so two waiters cannot both break and race.
break_if_stale() {
  local lock=$1 owner pid age
  owner=$(cat "$lock/owner" 2>/dev/null)
  age=$(( $(date +%s) - $(mtime "$lock") ))
  pid=$(printf '%s' "$owner" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p')
  if [ -n "$pid" ]; then
    ps -p "$pid" >/dev/null 2>&1 && return 1
    log "breaking $lock: holder ${owner:-?} is gone"
  elif [ "$age" -gt "$stale_after" ]; then
    log "breaking stale $lock from ${owner:-?} (${age}s)"
  else
    return 1
  fi
  local grave="$lock.stale.$$.$RANDOM"
  mv "$lock" "$grave" 2>/dev/null && rm -rf "$grave"
  return 0
}

# mtime in epoch seconds: BSD stat on macOS, GNU stat on Linux (the Python suite also runs in CI).
if stat -c %Y / >/dev/null 2>&1; then mtime() { stat -c %Y "$1" 2>/dev/null || date +%s; }
else mtime() { stat -f %m "$1" 2>/dev/null || date +%s; }; fi

held=()
release() {
  local i
  for (( i=${#held[@]}-1; i>=0; i-- )); do rm -rf "${held[$i]}"; done
  held=()
}
take() {  # take <lockdir>: mkdir + owner file; returns 1 if held
  mkdir "$1" 2>/dev/null || return 1
  echo "$agent pid=$$ tree=$tree_name path=$tree since=$(date +%s)" > "$1/owner"
  held+=("$1")
}

mkdir -p "$lock_root"
trap release EXIT

waited=0
until take "$tree_lock"; do
  break_if_stale "$tree_lock" && continue
  [ $((waited % 60)) -eq 0 ] && log "waiting for tree lock $tree_lock held by $(cat "$tree_lock/owner" 2>/dev/null || echo ?)"
  sleep "$poll"; waited=$((waited + poll))
done
log "tree lock $tree_lock taken by $agent"

waited=0; slot=
while [ -z "$slot" ]; do
  for i in $(seq 1 "$slots"); do
    if take "$lock_root/sourby-gradle-slot-$i"; then slot=$i; break; fi
  done
  [ -n "$slot" ] && break
  broke=0
  for i in $(seq 1 "$slots"); do break_if_stale "$lock_root/sourby-gradle-slot-$i" && broke=1; done
  [ "$broke" -eq 1 ] && continue
  if [ $((waited % 60)) -eq 0 ]; then
    log "waiting for a build slot ($slots in use):"
    for i in $(seq 1 "$slots"); do echo "  slot-$i: $(cat "$lock_root/sourby-gradle-slot-$i/owner" 2>/dev/null || echo ?)"; done
  fi
  sleep "$poll"; waited=$((waited + poll))
done
log "slot $slot taken; running ./gradlew in $tree (tree $tree_name)"

mkdir -p "$tree_tmp"
export TMPDIR="$tree_tmp/"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$tree_tmp"

cd "$tree" || exit 2
./gradlew "$@" &
child=$!
trap 'kill -TERM "$child" 2>/dev/null' INT TERM
wait "$child"; rc=$?
while kill -0 "$child" 2>/dev/null; do wait "$child"; rc=$?; done
log "gradle exited $rc; releasing slot $slot and tree lock ($tree_name)"
release
exit "$rc"
