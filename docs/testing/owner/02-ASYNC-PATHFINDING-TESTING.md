# Async Pathfinding Testing (Build 50)

Async pathfinding is **OFF by default** (`aurora.entity.async-pathfinding: false`). Build 50 ships it
as an experimental, opt-in feature; nothing enables it automatically.

> **A run where `/perf async` shows zero admitted solves is not evidence of anything.** It only proves
> that no mob re-planned a path. Every case below therefore starts by proving the workload reaches the
> asynchronous path.

## What actually submits an async solve

From the source (`perf/AsyncPathRecompute.java`, Minecraft feature patch `0006`), a recompute is
submitted only when **all** of these hold:

- the mob is re-planning an existing, unfinished path (not the first navigation of a fresh path);
- it has no solve pending for the current navigation generation;
- its navigation is not `AmphibiousPathNavigation` (aquatic/amphibious mobs are always synchronous);
- the mob is not a Sniffer;
- `mob.getY() >= level.getMinY()`;
- the pathfinding lane has capacity (`/perf governor` shows the lane);
- the target is inside the world border and `EntityPathfindEvent` was not cancelled.

Otherwise the engine falls back to the synchronous recompute. That fallback is intentional and must
stay in place.

## Configuration

`sourbycraft_config/aurora.yml`:

```yaml
aurora:
  entity:
    async-pathfinding: false   # true for the ON runs
```

- Key: `aurora.entity.async-pathfinding`
- Default: `false`
- Lifecycle: **LIVE** (`/sourbycraft reload` applies it; no restart)
- Legacy alias: `perf.ai.async-pathfinding` is still read for old files. It is not the current name.

Worker pool (not operator-configurable): `max(1, processors / 4)` threads, queue 1024, priority one
step below normal, on the Resource Governor's pathfinding lane.

## How to read the evidence

`/perf async` (`sourbycraft.command.perf`) prints, for the current interval:

- **admitted** — solves submitted to the worker pool (this is the number that must be > 0);
- **refused** — submissions refused by saturation or preconditions (falls back to sync);
- **outstanding** — solves not yet completed;
- **solve** and **queue wait** — mean and slowest, per interval;
- a per-type breakdown (up to 64 types, then `other`).

`/perf governor` shows the pathfinding lane's threads/queue/active counts. `logs/latest.log` contains
`AsyncPath: worker pool started (N thread(s))` on first use, and the startup thread ledger has an
`Async pathfinding` row.

---

### APF-001 — Feature OFF baseline

**Purpose:** capture the synchronous baseline on the same workload used for the ON runs.

**Priority:** P0 (safety) / P2 (performance). **Risk:** Low.

**Configuration:** `aurora.entity.async-pathfinding: false`.

**Steps:**

1. Start the test server and load the test world (or let it generate).
2. Summon the mob set used in APF-003, in a walled enclosure with a moving target (see APF-002).
3. Run `/perf async`; confirm it says `disabled (aurora.entity.async-pathfinding)`.
4. Run `/perf governor`; record lane values.
5. Record MSPT/TPS with `/perf tick` for 5 minutes at steady state.
6. Save a Spark profile for 120 seconds (`/spark profiler start --timeout 120`).

**Expected:** no async activity; server stable; numbers are the baseline.

**Evidence:** `/perf async`, `/perf tick`, Spark link, build identity, hardware, player count.

**Status:** READY FOR OWNER TEST

---

### APF-002 — Feature ON and a workload that triggers re-planning

**Purpose:** prove the asynchronous path is actually exercised.

**Priority:** P0. **Risk:** Medium.

**Configuration:** `aurora.entity.async-pathfinding: true`, then `/sourbycraft reload`.

**Steps:**

1. Confirm `/perf async` no longer says disabled and the log has the worker-pool line.
2. Build a 32×32 enclosure with a target that moves every few seconds (a player walking a loop, or a
   second mob on a lead/boat is not enough — use a player or a `/tp` loop).
3. Spawn 20 zombies at the far side and give them a target (`/execute as @e[type=zombie] run ...` is not
   needed: they attack a nearby player in survival/creative-with-aggro; use `/damage` to provoke).
4. Every 5 seconds move the player 8–12 blocks, forcing the zombies to re-plan.
5. Run `/perf async` every 30 seconds for 5 minutes.

**Expected:**

- `admitted` rises above 0 within the first minutes; `outstanding` returns to 0 at steady state.
- `refused` is 0 in a normal load; if it rises, `/perf governor` shows the lane at capacity.
- No exception, no `RejectedExecutionException`, no region-ownership violation.
- Mob movement continues; no frozen or sliding mobs.

**Evidence:** screenshots of `/perf async` at each interval, server log, video of the enclosure.

**Failure indicators:** `admitted` stays 0 (workload does not trigger the feature); mobs freeze;
pathfinding lane permanently full; ownership exceptions.

**Status:** READY FOR OWNER TEST

---

### APF-003 — Mob matrix

**Purpose:** exercise each mob family separately; some are intentionally synchronous.

**Priority:** P1. **Risk:** Medium.

**Run each row twice** (OFF then ON) on the same enclosure and record the per-type rows of
`/perf async`.

| Mob | How to force re-planning | Expected async? |
| --- | --- | --- |
| Zombie | player moves away, then behind a wall | yes (`admitted` for `zombie`) |
| Villager | workstation removed/moved; player blocks the path | yes |
| Bee | flower moved; hive moved | yes (flying navigation) |
| Turtle / axolotl / dolphin | target moved in water | no — amphibious navigation is inline; `refused` or no submission, movement unchanged |
| Creaking | player moves out of line of sight | yes |
| Door / fence | close a door or gate in the path | yes, and the mob opens it as before |
| Water / lava edge | target across a water/lava boundary | yes where a land path exists; lava must never be walked into |
| Sniffer | move target | no — explicitly synchronous |

**Expected:** behaviour identical to OFF for every mob; only the *thread* that computes the path
changes. No mob walks into lava that it avoided with the feature off. Villagers still reach
workstations. Bees still return to hives.

**Evidence:** one result sheet per mob (template `06`), `/perf async` per-type rows, short video per
mob family.

**Status:** READY FOR OWNER TEST

---

### APF-004 — Target changes, region transitions, entity removal

**Purpose:** the stale-result and ownership paths.

**Priority:** P0 (region safety). **Risk:** High.

**Steps:**

1. With the feature ON, make a zombie re-plan (APF-002), then immediately:
   - change its target to another player 30 blocks away;
   - teleport the zombie 200 blocks (region transition) while a solve is outstanding;
   - `/kill` the zombie while a solve is outstanding;
   - `/remove` the target player (disconnect) while a solve is outstanding.
2. Repeat each 20 times with `/perf async` visible.
3. Watch for stale paths: a mob walking to the *old* target after a change.

**Expected:**

- A stale solve is rejected; the mob follows the new target.
- A removed entity never receives a path (no "entity not in world" or retired-task warning beyond the
  documented WARN).
- Region transitions do not produce ownership violations.

**Evidence:** logs, `/perf incidents` (if it reports), video of the target-change case.

**Failure indicators:** mob follows a stale target; exception naming a retired entity; region
ownership violation; task applied on the wrong region.

**Status:** READY FOR OWNER TEST

---

### APF-005 — Worker saturation and shutdown

**Purpose:** verify the bounded queue and the shutdown path.

**Priority:** P1. **Risk:** Medium.

**Steps:**

1. With the feature ON, create a dense enclosure (200+ mobs) and keep a player moving for 5 minutes.
2. Watch `/perf async` `refused` and `/perf governor` pathfinding lane saturation.
3. While the load is running, `/stop`.
4. Restart and check the world/log.

**Expected:**

- Saturation shows as `refused`, not as an unbounded queue; the server stays responsive (MSPT does not
  climb without bound).
- Refused solves fall back to the synchronous path; behaviour stays correct.
- `/stop` completes; no `AsyncPath` task runs after the world is closed; no exception at shutdown.
- Disabling the feature with `/sourbycraft reload` stops new submissions; outstanding solves drain.

**Evidence:** `/perf async`, `/perf governor`, `/perf tick`, shutdown log, exit status.

**Status:** READY FOR OWNER TEST

---

## Interpretation rules

- A performance improvement is only a claim if APF-001 and APF-002 are run on the **same** world,
  player count, mob count, hardware and JVM flags, with the certification rule of
  `docs/architecture/qualification-readiness.md` §6 satisfied. Follow `04-PERFORMANCE-BENCHMARK-GUIDE.md`.
- An uncertified run may guide work but supports no release-wide statement.
- Correctness always outranks throughput: a faster run with one frozen mob or one stale path is a
  failure, not a win.
