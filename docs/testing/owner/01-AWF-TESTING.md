# AWF Testing — Aurora World Fabric (Build 50)

> Owner report 2026-10-24: AWF testing is complete. The cases below remain reproducible procedures;
> attach the result sheets/evidence with `06-RESULTS-TEMPLATE.md`.

All procedures assume the [environment in the index](00-TESTING-INDEX.md#environment-every-test-assumes)
and a **disposable** server directory. Status labels are defined in the index.

AWF words used below:

- **store** — the AWF data of one world, on the FILE backend under `<world>/region.awf` etc., on the
  Redis backend under keys with the configured prefix.
- **template** — a frozen world under `awf-templates/<name>/`; instances read through it with
  copy-on-write and never modify it.
- **commit** — an atomic generation publish; the last complete generation is the rollback point.

Useful files after a run: `logs/latest.log`, `awf/aurora-worlds.json`, `awf-templates/`,
`<world>/region.awf/`, and `sourbycraft_config/aurora.yml`.

Before every case, record the build identity with `/ver` and `/sourbycraft status`.

---

## AWF-001 — Persistent world creation (FILE backend)

**Purpose:** Verify AWF creates, saves, restarts and loads a normal world with the FILE backend.

**Priority:** P0. **Risk:** Low on a disposable server.

**Prerequisites:** Java 25; jar under test; disposable directory; operator permission
`sourbycraft.command.awf`.

**Configuration** (`sourbycraft_config/aurora.yml`):

```yaml
aurora:
  awf:
    worlds: []
    backend: "file"
    persistence: "incremental"
    commit-interval-seconds: 30
```

Restart required after editing.

**Steps:**

1. Start the server and wait for `Done (`.
2. Console: `/awf create awftest normal void`
3. Wait for the success message (world creation is asynchronous).
4. Console: `/awf info awftest` — note environment, generator, storage and generation.
5. Place a marker block:
   `/execute in minecraft:awftest run setblock 0 64 0 minecraft:diamond_block`
   (The dimension key of a Bukkit world is `minecraft:<name>`; if your server rejects `in minecraft:awftest`,
   enter the world with your world-manager plugin instead and keep the same coordinates.)
6. Read it back:
   `/execute in minecraft:awftest run data get block 0 64 0`
7. Console: `/awf save awftest`
8. Console: `/stop` and wait for the process to exit.
9. Start the same directory again; wait for `Done (`.
10. Console: `/awf load awftest`
11. Console: `/execute in minecraft:awftest run data get block 0 64 0`
12. Console: `/awf info awftest`

**Expected result:**

- Step 3 reports the world created; `/awf list` contains `awftest`.
- Step 6 prints `minecraft:diamond_block`.
- Step 7 reports a committed save.
- Step 11 prints `minecraft:diamond_block` again (same world, same generation or a higher one).
- Startup log contains `AWF store … opened at generation …` for the world's stores.
- Stop log contains `AWF-SHUTDOWN-OK`.
- No `AWF-SHUTDOWN-INCOMPLETE`, no `Unsafe terrain read`, no region-ownership exception.

**Evidence to collect:** console output of every step, `logs/latest.log`, `/awf info` output before
and after the restart, screenshot of the block before and after.

**Pass criteria:** every expected line is observed; the block survives; no error above WARN related
to AWF.

**Failure indicators:** world missing after restart; block replaced by air; a new empty generation;
`AWF-SHUTDOWN-INCOMPLETE`; exit status 70.

**Cleanup:** `/awf delete awftest confirm`, then stop the server; remove the disposable directory.

**Status:** READY FOR OWNER TEST

---

## AWF-002 — Template and copy-on-write isolation

**Purpose:** Verify an instance reads the template and writes only its own store.

**Priority:** P0. **Risk:** High (data isolation).

**Prerequisites:** AWF-001 passed; a second disposable world name.

**Configuration:** same as AWF-001.

**Steps:**

1. `/awf create awfsource normal void`
2. Write two markers:
   `/execute in minecraft:awfsource run setblock 0 64 0 minecraft:emerald_block`
   `/execute in minecraft:awfsource run setblock 10 64 0 minecraft:gold_block`
3. `/awf save awfsource`
4. `/awf template save awfsource awftpl`
5. `/awf create awfinst from awftpl`
6. Read both coordinates in `awfinst` with `data get block`.
7. Change one coordinate in the instance:
   `/execute in minecraft:awfinst run setblock 0 64 0 minecraft:redstone_block`
8. `/awf save awfinst`
9. Read coordinate `0 64 0` and `10 64 0` in `awfsource`.
10. Unload both worlds: `/awf unload awfinst`, `/awf unload awfsource`.
11. Load both again and repeat step 9, then read `awfinst` at both coordinates.

**Expected result:**

- Step 6: emerald at `0 64 0`, gold at `10 64 0`.
- Step 9: `awfsource` still emerald and gold; the instance's redstone is invisible to it.
- After step 11: `awfinst` has redstone at `0 64 0` (its own write survived), gold at `10 64 0`
  (still read through the template).
- Template folder `awf-templates/awftpl/` is unchanged by any instance write (compare file hashes
  before step 7 and after step 11).

**Evidence to collect:** `sha256sum awf-templates/awftpl/*` before and after, `/awf info` for both
worlds, block reads, console output.

**Pass criteria:** template bytes unchanged; instance writes isolated; instance reads template chunks.

**Failure indicators:** source world changes; instance loses its own block; template hash changes;
instance cannot load (`WORLD_UNAVAILABLE`) while the template exists.

**Cleanup:** `/awf delete awfinst confirm`, `/awf delete awfsource confirm`,
`/awf template delete awftpl confirm`.

**Status:** READY FOR OWNER TEST

---

## AWF-003 — Redis backend, lease and second-server refusal

**Purpose:** Verify Redis persistence, the per-world lease and the refusal of a second writer.

**Priority:** P0. **Risk:** High.

**Prerequisites:** `redis-server` 7+ with AOF `everysec`; two **disposable** server directories with
different names but the same Redis prefix is **not** allowed for this case — use two copies of the
same directory (same `server-id`) or two directories with the same world name.

**Configuration** (`sourbycraft_config/aurora.yml`):

```yaml
aurora:
  awf:
    worlds: []
    backend: "redis"
    persistence: "incremental"
    shutdown-deadline-ms: 30000
    redis:
      uri: "redis://127.0.0.1:6379/0"
      key-prefix: "awf-test"
      pool-size: 8
      timeout-ms: 2000
      lease-seconds: 15
      commit-interval-seconds: 5
```

Restart required.

**Steps:**

1. Start server A. `/awf create awfredis normal void`.
2. `/execute in minecraft:awfredis run setblock 0 64 0 minecraft:emerald_block`
3. `/awf save awfredis`; wait for the commit message.
4. Stop server A cleanly, wait for `AWF-SHUTDOWN-OK`.
5. Start server A again; `/awf load awfredis`; read the block.
6. With server A still running and the world loaded, start server B from the **same directory
   copy** and try `/awf load awfredis` there.
7. On server B, read `logs/latest.log` for the lease refusal.
8. Stop server B without touching the world; on server A read the block again.

**Expected result:**

- Step 3 reports `committed`.
- Step 5: emerald block is present; `/awf info awfredis` shows a generation at least as high as
  before.
- Step 6 fails with a message naming another server's lease (the exact text comes from
  `RedisBackend`, e.g. `AWF store … is held by another server (lease …)`).
- Server B does **not** write the world.
- Server A's block is unchanged after server B stops.

**Evidence to collect:** console of both servers, `redis-cli --scan --pattern 'awf-test*'`,
`redis-cli GET <lease key>` before and after, `redis-cli XLEN` not required.

**Pass criteria:** data survives restart; the lease refuses the second writer; no AWF data written by
server B.

**Failure indicators:** server B loads and writes; server A's lease is stolen while it is alive;
data missing after restart; exit 70 on a clean stop.

**Cleanup:** `/awf delete awfredis confirm`; stop both servers; remove the disposable directories;
delete only the `awf-test*` keys if the Redis instance is dedicated.

**Status:** READY FOR OWNER TEST

---

## AWF-004 — Unload with players present, and `nosave`

**Purpose:** Verify a world with players is not unloaded, and that `nosave` discards since the last
commit.

**Priority:** P0. **Risk:** High.

**Prerequisites:** AWF-001; one connected player.

**Steps:**

1. `/awf create awfhold normal void`
2. Teleport the player into it:
   `/execute in minecraft:awfhold as <player> at @s run tp @s 0 100 0`
3. Console: `/awf unload awfhold`
4. Read the reply; the world must stay loaded (`/awf info awfhold` shows loaded).
5. Player places a block at `0 64 0`.
6. Console: `/awf save awfhold`; then `/awf unload awfhold`.
7. `/awf load awfhold`; read the block.
8. Player breaks the block and places a different one **after** the last save; do not save.
9. Console: `/awf unload awfhold nosave`
10. `/awf load awfhold`; read `0 64 0`.

**Expected result:**

- Step 3/4: unload refused with a "players present" style error; the world remains loaded.
- Step 7: the block from step 5 is present.
- Step 10: the block from step 8 is gone; the step-5 block is back (last commit restored).

**Evidence:** console output, `/awf info` "loaded" field, block reads.

**Pass criteria:** refusal with players inside; `nosave` returns to the last commit, not to an empty
world.

**Failure indicators:** world unloads with a player inside; `nosave` keeps the unsaved block; `nosave`
deletes the world's earlier content.

**Cleanup:** `/awf delete awfhold confirm`.

**Status:** READY FOR OWNER TEST

---

## AWF-005 — Delete a world and a template

**Purpose:** Verify deletion is complete and irreversible, and that a template cannot be deleted while
an instance exists (or is explicitly allowed — record which behaviour you observe).

**Priority:** P0. **Risk:** High (irreversible).

**Steps:**

1. Create `awfdel`, save it, `/awf template save awfdel awfdeltpl`, create `awfdel-inst from awfdeltpl`.
2. `/awf delete awfdel confirm` → expect the world folder to disappear and `/awf list` to no longer
   contain it.
3. `/awf template delete awfdeltpl confirm` → record whether it refuses because `awfdel-inst` still
   uses it or deletes it; both behaviours must be documented, not silently ignored.
4. `/awf delete awfdel-inst confirm`, then repeat step 3 if it was refused.
5. Restart the server; `/awf list` must not resurrect deleted names.

**Expected:** deleted entries stay deleted after restart; no orphan store (`AWF store … exists but its
world is not in the registry` on startup means an orphan was left).

**Evidence:** `find . -name '*awfdel*'` before/after, `/awf list`, startup log.

**Cleanup:** remove the disposable directory.

**Status:** READY FOR OWNER TEST

---

## AWF-006 — `/awf save` durability boundary

**Purpose:** Measure whether "saved" means the data reached the store (the current implementation may
return when the save was scheduled). This case records the observed boundary; it is not a claim that
it is safe.

**Priority:** P0. **Risk:** High.

**Steps:**

1. Load `awfhold` (or create `awfsave`), place 20 distinct blocks in different chunks.
2. Console: `/awf save awfsave`; note the exact time the success message arrives.
3. Immediately kill the JVM with `kill -9` (disposable server only).
4. Restart; `/awf load awfsave`; count how many of the 20 blocks exist.
5. Repeat with a 5-second wait between the save message and `kill -9`.

**Expected:**

- The run with the wait loses nothing.
- The immediate run may lose the blocks that were still queued; record the count and the log lines.
- No case may produce a corrupt world (a load failure or a half-written generation is a FAIL).

**Evidence:** timestamps, saved block list, block dump after restart, `logs/latest.log`.

**Pass criteria:** with a wait, 20/20; without a wait, either 20/20 or a clean, explainable loss with
no corruption. A corrupt store is a failure.

**Status:** READY FOR OWNER TEST (behaviour under investigation)

---

## AWF-007 — Clean stop with dirty chunks

**Purpose:** Verify the shutdown commit and the `AWF-SHUTDOWN-OK` / `AWF-SHUTDOWN-INCOMPLETE` contract.

**Priority:** P0. **Risk:** High.

**Configuration:** `aurora.awf.shutdown-deadline-ms: 30000` (default).

**Steps:**

1. Create `awfstop`; edit blocks near `0 64 0`; **do not** run `/awf save`.
2. `/stop`; wait for exit; record the exit status.
3. Restart; `/awf load awfstop`; read the edited blocks.
4. Repeat with `shutdown-deadline-ms: 1000` and a large dirty world (or a slow disk) to force the
   deadline.

**Expected:**

- Default deadline: stop exits 0, log has `AWF-SHUTDOWN-OK`, blocks are present after restart.
- Too-small deadline: log has `AWF-SHUTDOWN-INCOMPLETE` naming the storage(s), exit status 70, and the
  message never says "saved".
- No world is left with a torn generation.

**Evidence:** exit status, the two marker lines, block reads.

**Status:** READY FOR OWNER TEST

---

## AWF-008 — Concurrent lifecycle operations

**Purpose:** Verify one winner per name and no leaked storage under concurrent commands.

**Priority:** P0. **Risk:** Medium.

**Steps:**

1. From console, run `/awf create awfracen normal void` and, in the same second, run
   `/awf load awfracen`, `/awf unload awfracen`, `/awf delete awfracen confirm` (paste them quickly or
   use a command block loop).
2. Repeat 10 times with a fresh name each time.
3. Reproduce with two players running the commands at once.

**Expected:**

- Every attempt either succeeds or fails with a busy/exists/no-such-world message; no stack trace.
- `/awf list` contains exactly the names that succeeded.
- At the end, stop the server; no `AWF store … is already open` and no `AWF-SHUTDOWN-INCOMPLETE`.

**Evidence:** all replies, `/awf list`, shutdown log.

**Status:** READY FOR OWNER TEST

---

## AWF-009 — Missing template is refused (Build 50 change)

**Purpose:** Verify an instance never loads as an empty world when its template is gone.

**Priority:** P0. **Risk:** High. **This is the regression test for a Build 50 fix.**

**Steps:**

1. Create `awftpl-src`, set a marker, save, `/awf template save awftpl-src awf-missing-tpl`.
2. `/awf create awfm-i from awf-missing-tpl`; verify the marker is visible.
3. `/awf unload awfm-i`; stop the server.
4. Move the template away: `mv awf-templates/awf-missing-tpl awf-templates/awf-missing-tpl.bak`.
5. Start the server; `/awf load awfm-i`.
6. Also try creating a new instance: `/awf create awfm-i2 from awf-missing-tpl`.

**Expected:**

- Step 5 fails with a message saying the world is an instance of a missing template and refuses to
  load it; the world does **not** appear as an empty/void world.
- Step 6 fails with the same reason.
- `awfm-i`'s store is not modified; `/awf info awfm-i` still reports the template.

**Failure indicator (former behaviour):** the world loads, looks empty or regenerated, and later
saves over the instance.

**Cleanup:** restore the template folder; delete both instances.

**Status:** READY FOR OWNER TEST

---

## AWF-010 — Hand-edited registry is rejected safely (Build 50 change)

**Purpose:** Verify a malformed or unusable registry entry cannot crash the boot or attach AWF to the
wrong world.

**Priority:** P1. **Risk:** Medium.

**Steps:**

1. Stop the server. Back up `awf/aurora-worlds.json`.
2. Add an entry with an invalid name (for example `"name": "World!"`) and one with `"name": null`.
3. Start the server.
4. Observe the log warning naming the ignored entry, and that the server reaches `Done (`.
5. Restore the backup.

**Expected:**

- One WARN per unusable entry, starting with `Aurora World Fabric: ignoring the registry entry`.
- No NPE, no boot crash, no AWF attached to `world`/`overworld` dimensions.
- Valid entries still load.

**Status:** READY FOR OWNER TEST

---

## AWF-011 — World properties and clone

**Purpose:** Verify `set` and `clone` keep the documented properties and instance relationship.

**Priority:** P1. **Risk:** Medium.

**Steps:**

1. `/awf create awfprops normal void`, then:
   `/awf set awfprops difficulty hard`
   `/awf set awfprops pvp false`
   `/awf set awfprops monsters false`
   `/awf set awfprops bounds 0 0 128 128`
2. `/awf info awfprops` and restart; confirm the values persist.
3. `/awf template save awfprops awfprops-tpl`.
4. `/awf create awfprops-i from awfprops-tpl`, then `/awf clone awfprops-i awfprops-i2`.
5. Inspect both: the clone of an instance keeps the template (`/awf info`).

**Expected:** properties persist across restart; `clone` refuses a loaded world; an instance clone
keeps its template; no world is duplicated with the same name.

**Status:** READY FOR OWNER TEST
