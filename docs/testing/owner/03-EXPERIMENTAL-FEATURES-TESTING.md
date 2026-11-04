# Experimental Features Testing (Build 50)

Every section names the exact runtime condition that exercises the code. Features marked **BLOCKED**
cannot be exercised on one machine or without extra infrastructure; they are not "passed by
inspection".

Shared environment: see [the index](00-TESTING-INDEX.md#environment-every-test-assumes).

---

# 1. AWF MultiProfile (P0, disabled by default)

> Owner report 2026-10-24: MultiProfile testing is complete (MP-007 and MP-010 stay BLOCKED).

MultiProfile keeps several durable player states (inventory, armor, ender chest, XP, health, food,
effects, location) and switches between them. Both features below it are off unless enabled.

## Configuration

`sourbycraft_config/aurora.yml`:

```yaml
aurora:
  awf:
    multiprofile:
      enabled: true
      mode: "standalone"          # standalone; distributed startup is refused in this candidate
      default-slots: 3
      max-slots: 8
      switching:
        enabled: true
        timeout-ms: 15000
        require-durable-save: true
      isolation:
        inventory: true
        armor: true
        ender-chest: true
        experience: true
        health: true
        food: true
        potion-effects: true
        location: true
      worlds:
        idle-unload-seconds: 300
      coordination:
        provider: "local"         # local | redis
        fencing-enabled: true
        single-active-session: true
      directory: "awf/profiles"
```

Every key is **RESTART_REQUIRED**. Disabled means no `awf/profiles` directory, no listener, no thread
and no service registration.

Commands (`sourbycraft.command.awf.multiprofile`; `create`/`switch`/`recover` additionally need
`sourbycraft.command.awf.multiprofile.admin`):

```
/awf multiprofile status
/awf multiprofile list <player|uuid>
/awf multiprofile create <player> [type]
/awf multiprofile switch <player> <profile-uuid>
/awf multiprofile inspect <profile-uuid>
/awf multiprofile recover <transition-uuid>
```

A plugin can also use `Bukkit.getServicesManager().load(AuroraProfiles.class)`; `null` means the
feature is off. Switching is asynchronous; never join the future on a region/entity thread.

---

### MP-001 — Enable, create, list, inspect

**Priority:** P0. **Risk:** Medium.

**Steps:**

1. Set `enabled: true`, restart. Log: `AWF MultiProfile: enabled (standalone, node …, directory awf/profiles, …)`.
2. Join as a player; note the account UUID (`/awf multiprofile list <player>` works with a name).
3. `/awf multiprofile status` — mode, node, ownership, switching budget, isolation summary.
4. `/awf multiprofile create <player> pvp`
5. `/awf multiprofile list <player>` — the new profile appears.
6. Copy the profile UUID and run `/awf multiprofile inspect <uuid>`.
7. Verify `awf/profiles/` contains accounts/profiles, and that each file is a checksummed, framed
   record (non-zero size, no `*.tmp` left behind).

**Expected:** the profile is created with `type=pvp`, `revision 0`, not active until switched; one
`awf/profiles` tree; no exception; no player inventory change.

**Failure indicators:** `/awf multiprofile` says disabled; directory missing; create returns
`SLOT_LIMIT` for the first profile; `*.tmp` files remain.

**Status:** READY FOR OWNER TEST

---

### MP-002 — Switch and isolation

**Purpose:** the core data-safety test: state must move with the profile and nowhere else.

**Priority:** P0. **Risk:** High (item loss / duplication).

**Preconditions:** two profiles A (active) and B; a second player is **not** needed.

**Steps:**

1. As the player, fill the hotbar with 9 named items, wear full armor, put a named item in the ender
   chest, gain XP levels, set health to half, eat to a known food level, drink a potion with a visible
   effect, and note the coordinates.
2. Console: `/awf multiprofile switch <player> <B>`; wait for `Switched`.
3. Verify: inventory empty (or B's old contents), armor empty, ender chest empty, XP 0, health/food
   full, no potion effect, and the player at B's stored location (or spawn if B has none).
4. Put a distinct named item in B's inventory; switch back to A; verify A's full state returns and B's
   item is gone from A.
5. Switch to B again and verify its item is back.
6. Repeat with one isolation key set to `false` at a time (restart each time) and record exactly which
   category stops being isolated.

**Expected:** each category moves with its profile; `false` leaves that category on the player;
no duplication, no disappearance; the switch result names the target profile.

**Failure indicators:** items from A visible in B; B's item in A; XP duplicated; health above max
after applying a stored value; a switch that reports success but applied nothing.

**Evidence:** screenshots of the inventory/ender chest/XP before and after each switch, console
output, `awf/profiles` file sizes before/after.

**Cleanup:** switch back to A before deleting B.

**Status:** READY FOR OWNER TEST

---

### MP-003 — Reconnect after a switch (Build 50 fix)

**Purpose:** the previous implementation leaked the target's ownership claim, so a rejoin within the
lease window was rejected as a duplicate and the player was kicked.

**Priority:** P0. **Risk:** High.

**Steps:**

1. `single-active-session: true`; switch to B; wait for `Switched`.
2. Disconnect the client; reconnect within 5 seconds.
3. Repeat 10 times, including a disconnect during the switch (`Switched` may not have arrived).
4. Also run `/awf multiprofile switch <player> <B>` again while the player is already on B.

**Expected:** the player joins normally every time; no message `Your profile is active on …`; a
switch to the profile already active either succeeds as a no-op or is refused with a clear
`SWITCH_IN_PROGRESS`/`PROFILE_LOCKED`-style reason — but never a kick.

**Failure indicators:** kick with `Your profile is active on <this node>`; ownership conflict on
rejoin; a stale claim visible in `/awf multiprofile status` after the player quit.

**Status:** READY FOR OWNER TEST

---

### MP-004 — Save on quit and explicit save

**Priority:** P0. **Risk:** High.

**Steps:**

1. On profile A, add a uniquely named item; quit normally; rejoin.
2. Verify the item is still there and A's revision increased (`/awf multiprofile inspect`).
3. Add a second item; with the player online, call `saveProfile` from a test plugin **or** run a
   switch-stress case instead (the API path is the same engine method).
4. While a switch is running, try to force a quit-save (disconnect immediately after `switch`).
5. Rejoin and inspect both profiles.

**Expected:** quit saves the active profile once; a quit during a switch does not write the target's
state under the profile being left; the revision increases by one per save; no `*.tmp` files.

**Failure indicators:** items from the target profile appear under the previous profile; two
snapshots with the same revision; a quit-save after the switch committed overwrites it.

**Status:** READY FOR OWNER TEST

---

### MP-005 — Switch timeout

**Priority:** P1. **Risk:** Medium.

**Setup:** `switching.timeout-ms: 1000`; make the target slow (a very large template world, or saturate
the storage lane with parallel `/awf create` commands).

**Steps:**

1. Start a switch; time it.
2. Wait for the result.
3. Read `/awf multiprofile status` for pending transitions.
4. Rejoin and verify the player is still on the previous profile with its full state.

**Expected:** a switch past the deadline fails with `TIMEOUT`, the player is restored to the previous
snapshot, and the journal is either removed (rolled back) or listed for `recover` — never silently
left as a phantom success.

**Failure indicators:** the player is stuck with no inventory; `SWITCH_IN_PROGRESS` forever; a journal
that no command can recover.

**Status:** READY FOR OWNER TEST

---

### MP-006 — Recovery of an interrupted switch

**Priority:** P0. **Risk:** High (destructive: disposable server only).

**Steps:**

1. Start a switch to a large profile world; while the console shows the transition, `kill -9` the
   server.
2. Restart; join the player.
3. Observe `recoverPending` on join or run `/awf multiprofile recover <transition>` from
   `/awf multiprofile status`.
4. Verify: either the switch finished forward (account points at the target, target state intact) or
   it rolled back to the previous profile. Never both, never neither.
5. Repeat with the kill before and after the commit point (watch `awf/profiles/transitions/`).

**Expected:** a single authoritative profile; the journal is terminal or removed; no duplicated
inventory; `/awf multiprofile status` shows no stuck transition.

**Evidence:** `awf/profiles/transitions/*` before restart, console of the recovery, both profiles'
contents.

**Status:** READY FOR OWNER TEST

---

### MP-007 — Idle world unload

**Status:** READY FOR OWNER TEST — public binding is now available. Follow
[Tester Owner](../../tester%20owner.md) B50-09…12: bind an inactive profile, activate it,
switch away, wait for idle unload, then inject one unload failure and verify a later retry.
Use a short `worlds.idle-unload-seconds` on a disposable server. Unbind must not delete worlds.

---

### MP-008 — Disabled feature creates nothing

**Priority:** P1. **Risk:** Low.

**Steps:**

1. `enabled: false`; delete `awf/profiles`; restart.
2. `/awf multiprofile status`
3. Check that `awf/profiles` was not recreated and no `SourbyCraft-AWF-profile-*` thread exists
   (`jcmd <pid> Thread.print | grep AWF-profile`).
4. Load a plugin that looks up `AuroraProfiles` in `onEnable`: it must see `null`.

**Expected:** `AWF MultiProfile is unavailable; check aurora.awf.multiprofile.enabled and startup errors`; no directory,
thread, listener or service.

**Status:** READY FOR OWNER TEST

---

### MP-009 — Distributed mode refuses unsafe combinations

**Priority:** P1. **Risk:** Medium.

**Steps:**

1. `mode: distributed` with `aurora.awf.redis.fabric.enabled: false` → start the server.
2. `mode: distributed`, fabric on, `coordination.provider: local` → start.
3. `mode: distributed`, fabric on, provider `redis`, `fencing-enabled: false` → start.
4. Finally all correct (`fabric.enabled: true`, provider `redis`, fencing `true`, reachable Redis).

**Expected:** cases 1–3 fail the `awf multiprofile` boot stage with a message naming the missing
requirement; the server still reaches `Done (` without the feature. Case 4 also refuses with the
FILE repository / atomic fencing explanation; the service remains unregistered.

**Failure indicators:** the stage reports success while the feature refused; local ownership runs in
distributed mode.

**Status:** READY FOR OWNER TEST

---

### MP-010 — Two-node ownership and takeover

**Status:** BLOCKED BY IMPLEMENTATION — requires shared profile storage with atomic fenced writes
before any two-node environment can qualify it. This candidate refuses distributed startup.
The following is a future acceptance checklist, not a runnable feature claim:


- node A claims the profile; node B's join is refused while A is alive;
- A is killed; B takes over after the lease and the epoch increases;
- A restarts and cannot commit with its old fence;
- Redis is stopped; every durable profile write is refused, not written locally;
- `/awf redis sessions <profile>` on both nodes agrees on node, epoch and fence.

---

# 2. Aurora Redis Fabric (P1, disabled by default)

## Configuration

```yaml
aurora:
  awf:
    redis:
      uri: "redis://127.0.0.1:6379/0"
      key-prefix: "awf"
      pool-size: 8
      timeout-ms: 2000
      lease-seconds: 15
      commit-interval-seconds: 5
      fabric:
        enabled: true
        services:
          sessions: true
          coordination: true
          events: false        # off by default: no consumer ships in this build
          cache: true
          routing: true
          telemetry: true
        pools:
          storage: 8
          coordination: 4
          events: 2
          control: 2
```

All keys are **RESTART_REQUIRED**; the fabric opens no socket and starts no thread when disabled.

---

### RF-001 — Start and reachability

**Priority:** P1. **Risk:** Medium.

**Steps:**

1. Start Redis (dedicated database/prefix), enable the fabric, restart.
2. Log must contain `Aurora Redis Fabric node=… redis://… keys=awf* pools=8/4/2/2 redis <version> (<N> us)`.
3. Console: `/awf redis status` and `/awf redis health`.
4. Point `uri` at a closed port and restart.

**Expected:** healthy run reports reachable, version, latency, pool sizes, and **measured**
operations/failures/timeouts (the Build 50 fix wires the counters to the pools). Unreachable run logs
one error, leaves the fabric off, and the server still reaches `Done (`.

**Status:** READY FOR OWNER TEST

---

### RF-002 — Pool isolation and saturation visibility

**Priority:** P1. **Risk:** Medium.

**Steps:**

1. `/awf redis pools` — storage/coordination/events/control with `N in use of M`.
2. Run `/awf redis status` repeatedly while a world commits; storage `in use` should move.
3. With `pools.storage: 1`, run parallel `/awf save` commands on several worlds and watch the pool.

**Expected:** sizes match the config; a saturated pool surfaces as a wait, and `/awf redis status`
operations/timeouts/saturation rise instead of silently blocking a tick thread.

**Status:** READY FOR OWNER TEST

---

### RF-003 — Fabric and AWF share the STORAGE pool

**Priority:** P0. **Risk:** High.

**Steps:**

1. Fabric enabled, `aurora.awf.backend: redis`, worlds on Redis.
2. Restart and load a world; `/awf redis pools` shows storage in use while a commit runs.
3. Compare with the fabric disabled: the backend opens its own `pool-size` connections.

**Expected:** with the fabric on there is **one** storage pool of `pools.storage`; the world store
never opens a second one. No command fails with "no free Redis connection" while the feature is idle.

**Status:** READY FOR OWNER TEST

---

### RF-004 — Shutdown ordering (Build 50 fix)

**Purpose:** the fabric used to close the shared STORAGE pool before the final AWF commit, so a clean
stop lost the last interval and exited 70.

**Priority:** P0. **Risk:** High.

**Steps:**

1. Fabric on, AWF Redis backend, a loaded world with dirty chunks (edit blocks, do not save).
2. `/stop`; wait for exit; record the exit status.
3. Look for `AWF-SHUTDOWN-OK` and absence of `AWF-SHUTDOWN-INCOMPLETE`.
4. Restart and read the blocks.
5. Also verify the fabric's own pools close after: no `Redis client is closed` error during the final
   commit.

**Expected:** exit 0, `AWF-SHUTDOWN-OK`, all blocks present, leases released
(`redis-cli --scan --pattern 'awf*lock*'` empty or expiring).

**Failure indicators:** exit 70; `AWF-SHUTDOWN-INCOMPLETE`; blocks missing; errors saying the Redis
client is closed during `closeAll`.

**Status:** READY FOR OWNER TEST

---

### RF-005 — Events are off by default

**Priority:** P2. **Risk:** Medium.

**Steps:**

1. Default config: `/awf redis events` → `service disabled`.
2. Set `services.events: true`, restart: `/awf redis events` reports backlog and the dead-letter
   stream, and `events` counters start at 0.
3. Only if you have a consumer: run it, publish profile events, verify ack and dead-letter after
   `max-deliveries`.
4. Without a consumer: leave the server running; after a long time, publishing is refused at
   `events.retention` (this is why the default is off).

**Expected:** default off; enabling without a consumer eventually reports backpressure instead of
growing the stream without bound.

**Status:** READY FOR OWNER TEST (consumer-dependent part optional)

---

### RF-006 — Invalid Redis URI does not abort world load (Build 50 fix)

**Priority:** P1. **Risk:** Medium.

**Steps:**

1. Set `aurora.awf.redis.uri: "redis://:bad-port"` (or an unparsable URI) with the fabric enabled
   and `aurora.awf.backend: redis`.
2. Start the server.
3. Read the log.

**Expected:** one `Aurora Redis Fabric could not start: …` / `AWF … misconfigured: …` error, no
exception escaping `RegionFileStorageHooks`, and the server reaches `Done (` (AWF worlds may be
unavailable; that is reported, not a crash).

**Status:** READY FOR OWNER TEST

---

# 3. Aurora Compatibility Bridge (P1)

## Configuration

`sourbycraft_config/aurora.yml`:

```yaml
aurora:
  bridge:
    mode: "off"            # off | safe   (RESTART_REQUIRED)
    quarantine-after: 3
    sync-route: "caller-region"
    thread-checks: "relaxed"
```

`config/aurora-server.yml`:

```yaml
region-scheduler:
  guard-severity: "LOG"    # SILENT | LOG | THROW; LIVE
```

### BR-001 — Admission, refusal and guard logging

**Priority:** P1. **Risk:** High (plugin semantics).

**Steps:**

1. With `mode: off`, install a plugin **without** `folia-supported`/`canvas-supported` in `plugin.yml`
   (for example a small test plugin) and restart.
2. Look for the refusal:
   `Not loading <display> (<jar>): … aurora.bridge.mode = off …`.
3. Set `mode: safe`, restart.
4. Look for the admission warning naming the plugin and the "not qualified" sentence.
5. Run a task from that plugin that touches another region; with `guard-severity: LOG` the guard logs,
   and after `quarantine-after` hits the log contains `Aurora Bridge quarantined <plugin> after N
   region-ownership violation(s)`.
6. `/perf plugins` and `/plugins filter bridged` show the plugin state.
7. Set `guard-severity: THROW` and repeat; the violation must throw inside the plugin task, not kill
   the region thread.

**Expected:** default off refuses unmarked plugins; safe admits them with a warning; quarantine is
observable; `LOG` never throws; `THROW` throws only in the offending plugin's task.

**Evidence:** plugin log lines, `/perf plugins`, `/plugins <name>`, server log around the violation.

**Status:** READY FOR OWNER TEST

---

### BR-002 — Per-plugin task limits

**Priority:** P2. **Risk:** Medium.

**Steps:**

1. Set `aurora.bridge.max-pending-tasks-per-plugin: 4` and `max-running-async-tasks-per-plugin: 2`
   (LIVE).
2. A test plugin schedules 10 delayed tasks and 5 async tasks.
3. Watch `/plugins <name>` and `/perf plugins` counters; check that excess tasks are refused with a
   clear message and that cancellation cleans the counters.

**Expected:** limits apply per plugin, defaults `0` mean unlimited, a plugin cannot consume another
plugin's budget.

**Status:** READY FOR OWNER TEST

---

# 4. Aurora Resource Governor (P1)

```yaml
aurora:
  scheduler:
    storage-threads: 1
    storage-queue: 64
    bridge-io-threads: 0      # 0 = max(2, processors / 4)
    bridge-io-queue: 256
```

All four are **RESTART_REQUIRED**.

### RG-001 — Lanes

**Steps:**

1. `/perf governor` — every lane with threads, queue, active, completed, rejected.
2. `/perf lanes` — the same view by lane name.
3. Saturate the storage lane (parallel `/awf save` on many worlds) and confirm rejected/queued rise
   while the tick thread stays responsive.
4. Startup log has the thread ledger (`Thread ledger: N configured platform thread(s)…`).

**Expected:** four bounded lanes, no unbounded queue, the server does not create a thread per task.

**Status:** READY FOR OWNER TEST

---

# 5. Instant Startup Cache (P2)

JVM flag `-Daurora.startup.cache=false` disables it (default on). **RESTART_REQUIRED.**

### SC-001 — Cold vs warm

**Steps:**

1. Delete `sourbycraft_config/cache/startup-index.cache`; start; note
   `Aurora startup index: N jar(s), cold start (hit 0, miss N, discarded 0) in X ms`.
2. `/sys` — `Startup index:` row; `/plugins <name>` — `Startup cache: hit|miss`.
3. Stop and start again without changing jars: `warm start (hit N, miss 0, …)`.
4. Replace one plugin jar: that jar is a `miss`; the rest are hits.
5. Start with `-Daurora.startup.cache=false`: the line must not claim a cache hit.

**Expected:** the cache only changes scan time, never which plugins load; a changed jar is rescanned;
an incompatible environment (game/ABI/format/Java change) discards the cache.

**Status:** READY FOR OWNER TEST

---

# 6. Chunk and region measurement (P1/P2)

### CH-001 — Chunk generation metrics

`aurora.diagnostics.chunk-generation-metrics: true` (RESTART_REQUIRED; also
`-Dsourbycraft.chunk-generation-metrics.enabled=true`).

**Steps:** enable, restart, walk into new terrain for 5 minutes, run `/perf chunks`. Default off:
the command must print the disabled instruction and the server must not allocate metric objects.

**Expected:** per-stage queue/run p95/p99 only when enabled; no gameplay change.

**Status:** READY FOR OWNER TEST

### CH-002 — Region and chunk thread sizing

`config/paper-global.yml`:

```yaml
threaded-regions:
  threads: -1          # resolves through aurora.cpu.cores, else every processor
  grid-exponent: 4
  scheduler: EDF
chunk-system:
  worker-threads: 4
  io-threads: 4
```

**Steps:** start with `threads: 4`; check the startup ledger and `/perf governor` report 4 region
threads. Repeat with `-1` and `aurora.cpu.cores: 8`; the ledger must say
`Region tick threads: 8 (aurora.cpu.cores …)`.

**Expected:** the reported count matches the config; no extra executor is created per region.

**Status:** READY FOR OWNER TEST

---

# 7. Network counters (P2)

`aurora.network.counters: true` (LIVE). `/perf network` prints wire bytes/packets in/out per second,
totals, connections, outbound queue sizes and AbyssLink status.

**Test:** record the counters idle and with one player moving; they must move. Turning the key off
must remove the counters, not the traffic. There is **no experimental network path** in this build;
the only other network controls are the limits in `sourbycraft-security.yml`.

**Status:** READY FOR OWNER TEST

---

# 8. AbyssLink (P1/P2)

```yaml
aurora:
  abysslink:
    enabled: false
```

All keys RESTART_REQUIRED; `tls: false` is rejected.

### AB-001 — Disabled path

`/ping` prints `AbyssLink: disabled`, no proxy line, and uses backend latency; `/perf network` shows
`AbyssLink disabled` with zeroed control rows. Gameplay is unaffected.

**Status:** READY FOR OWNER TEST

### AB-002 — Enabled against a test peer

**Status:** BLOCKED — requires a Leviathan/AbyssLink test peer with mTLS certificates. Do not point a
test server at a production peer. When available: `/ping` distinguishes `Client → Leviathan` from
`Backend latency` only with a live sample, reconnects with backoff, and a peer outage never blocks
gameplay.

---

# 9. Auto-updater (P2)

`sourbycraft_config/sourbycraft_global_config.yml`:

```yaml
updater:
  enabled: true
  apply-mode: "notify"     # notify | stage | auto | off
  check-interval-minutes: 30
```

### UP-001 — Notify mode

**Steps:** start with `apply-mode: notify`; `/update status`; `/update check`; when a newer build
exists, the console banner and the join message appear, and **nothing is downloaded or applied**.
`/update apply` is the explicit action.

**Expected:** notify never restarts the server; `auto` (not used in this test) does. Do not run this
against a production server.

**Status:** READY FOR OWNER TEST
---

# 10. SourbyMod (ML1)

`mods/*.jar` with `sourbymod.yml` at the root load at boot. Format: `docs/SOURBYMODS.md`.
Lifecycle: `onLoad` in the `sourby mods` boot stage (before worlds), `onEnable` on
`ServerLoadEvent(STARTUP)`, `onDisable` during shutdown. No Fabric/Forge support.

### SM-001 — Load a mod, reject unsupported jars, isolate a broken mod

**Priority:** P1. **Risk:** Medium (arbitrary code, same trust as a plugin).

**Prerequisites:** a disposable server with a JDK 25; the server's compiled classes
(`sourbycraft-server/build/classes/java/main`) or the build output directory.

**Steps:**

1. Create `/tmp/sm/ExampleMod.java`:

```java
package com.example;
import dev.iyanz.sourbycraft.mod.ModContext;
import dev.iyanz.sourbycraft.mod.SourbyMod;
public class ExampleMod implements SourbyMod {
    public void onLoad(ModContext ctx) { ctx.logger().info("onLoad " + ctx.modId()); }
    public void onEnable() { System.out.println("SM onEnable"); }
    public void onDisable() { System.out.println("SM onDisable"); }
}
```

2. Compile and package:

```bash
javac -cp sourbycraft-server/build/classes/java/main -d /tmp/sm/classes /tmp/sm/ExampleMod.java
printf 'id: example-mod\nname: Example Mod\nversion: 1.0.0\nmain: com.example.ExampleMod\napi: 1\n' > /tmp/sm/sourbymod.yml
jar --create --file mods/example-mod.jar -C /tmp/sm/classes . -C /tmp/sm sourbymod.yml
```

3. Start the server. The log must contain `mods: example-mod@1.0.0 (1 loaded)` and the mod's
   `onLoad` line **before** `Done (`. After startup, `SM onEnable` appears.
4. Stop; `SM onDisable` appears before the process exits.
5. Put a Fabric/Forge jar (no `sourbymod.yml`) in `mods/`, restart: one WARN naming the jar and
   `Fabric/Forge mods are not supported`; the server still reaches `Done (`.
6. Add a second jar with the same `id`, restart: the second is skipped with a duplicate-id WARN.
7. Add a mod whose `onLoad` throws, restart: WARN naming the mod, the valid mod still loads, the
   server reaches `Done (`.
8. Remove the jars, restart: `mods: (0 loaded)`.

**Expected:** only valid SourbyMods run; every rejection is one WARN; no jar can prevent the server
from starting; `onDisable` runs in reverse load order.

**Failure indicators:** a Fabric jar is loaded; a broken `onLoad` aborts the boot; `onEnable` runs
before worlds/plugins exist; `onDisable` does not run; a classloader leak keeps the jar locked.

**Status:** READY FOR OWNER TEST
