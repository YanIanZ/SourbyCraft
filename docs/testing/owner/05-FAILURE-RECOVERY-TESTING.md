# Failure and Recovery Testing (Build 50)

> **Destructive.** These procedures kill processes, freeze Redis, corrupt copies of files and
> interrupt saves. Run them only on a disposable machine, a disposable server directory and a
> dedicated Redis instance/database. Never on production data. Back up before every case; restore
> before the next one.

Every case states what "safe" means: data may be lost only in the window the design already declares
(for example, chunks since the last AWF commit), but it must never be **corrupted**, silently
duplicated, or reported as saved when it was not.

A general rule: after any destructive case, restart once more and read the data twice. A failure that
only appears on the second restart is still a failure.

---

## FR-001 — Crash with dirty AWF chunks (FILE)

**Destructive:** kills the JVM with `kill -9`. **Risk:** High.

**Setup:** disposable directory; `aurora.awf.backend: file`; a world `awfcrash`; AWF-001 passed.

**Steps:**

1. Load `awfcrash`; write 30 blocks in 10 chunks; do **not** save.
2. `kill -9 <pid>`.
3. Restart; `/awf load awfcrash`; count the blocks.
4. Read `/awf info awfcrash`; then `/stop` cleanly; restart again; count again.
5. Check the log for corruption markers.

**Expected:** blocks since the last commit may be missing (declared loss window); the store opens at
a complete generation; no `corrupt`, `checksum`, `torn` or `region file` error; the second restart
shows the same state as the first (no delayed corruption).

**Failure indicators:** a store that cannot open; a generation that mixes two worlds; blocks that
reappear on the second restart; `AWF-SHUTDOWN-INCOMPLETE` on a clean stop after the crash.

---

## FR-002 — Redis unavailable at startup

**Destructive:** uses a stopped Redis. **Risk:** High.

**Steps:**

1. Stop Redis. Start the server with `aurora.awf.backend: redis` and the fabric enabled.
2. Observe the log and the boot result.
3. `/awf redis status`; `/awf list`; `/perf awf`.
4. Start Redis; retry `/awf load <world>`.

**Expected:** one clear error naming the endpoint (no credentials); the server reaches `Done (`; AWF
worlds on Redis are unavailable, not silently recreated on local disk; after Redis returns, a load
either succeeds or fails with a reason that names Redis.

**Failure indicators:** the boot aborts; AWF creates empty local stores for a Redis world; the server
reports the world as loaded when it is not.

---

## FR-003 — Redis freeze during commits (`SIGSTOP`)

**Destructive:** freezes the Redis process; the server blocks on I/O. **Risk:** High.

**Steps:**

1. Fabric + AWF Redis backend; a world with dirty chunks; commit interval 5 s.
2. `kill -STOP $(pidof redis-server)` for 30 seconds.
3. Watch the log: renewals and commits must WARN; the server must stay responsive (players can still
   move; MSPT may rise but the server does not hang).
4. `kill -CONT $(pidof redis-server)`.
5. Wait for the next commit; `/awf save`; stop cleanly.
6. Restart; read the blocks.

**Expected:** no false "committed"; commits resume after `CONT`; blocks written before the freeze are
present; the server does not declare data saved while Redis was frozen; shutdown after the resume is
clean (or, if the freeze outlives the deadline, `AWF-SHUTDOWN-INCOMPLETE` and exit 70).

**Failure indicators:** the server hangs beyond the documented budget; a write is reported committed
while Redis was frozen and is missing after restart; lease keys remain held after the restart
(`redis-cli --scan --pattern 'awf*lock*'`).

---

## FR-004 — Redis killed and restarted from AOF

**Destructive:** kills Redis; relies on AOF. **Risk:** High.

**Steps:**

1. AWF Redis world with markers; AOF `appendonly yes`, `appendfsync everysec`.
2. `kill -9` Redis; read a chunk that is not cached (forceload a new area) in the world.
3. Start Redis again (same AOF and prefix).
4. Read the marker chunks; then write a new block; save; restart the server fully.

**Expected:** reads during the outage fail with the fence message (`writes of this position are
refused` or similar) and **never** overwrite stored chunks with regenerated terrain; after Redis
returns, the markers are present; the block written during the outage is not silently saved as if it
had been.

**Failure indicators:** regenerated terrain overwrites stored chunks; markers lost when AOF is intact;
the world reports success for a write that never reached Redis.

---

## FR-005 — Save failure during unload

**Destructive:** makes the store read-only. **Risk:** High.

**Setup:** FILE backend; `awfchmod` world with dirty chunks; know the store folder.

**Steps:**

1. Save once so a known generation exists.
2. Make the store folder read-only: `chmod -R a-w <world>/region.awf` (disposable copy).
3. Write a block; `/awf unload awfchmod`.
4. Read the command's reply and the log.
5. `chmod -R u+w`; `/awf unload awfchmod` again; restart; read.

**Expected:** the first unload **fails** and says the storage was not confirmed (it must not print
"Unloaded"); the world stays loaded or is left in a recoverable state; after restoring permissions the
second unload succeeds and the block survives.

**Failure indicators (known Build 50 risk, record it):** `/awf unload` prints success while the log
says chunks were lost. File this as a bug with the exact line.

---

## FR-006 — Interrupted shutdown (SIGTERM during save)

**Destructive:** sends SIGTERM/SIGKILL during shutdown. **Risk:** High.

**Steps:**

1. Dirty AWF world, large enough that the save takes > 2 s.
2. `/stop`, then after 1 s send `kill -9` (or `kill -TERM` for a second run).
3. Restart; read the world; check `AWF-SHUTDOWN-*` markers and the exit status.
4. Repeat with a clean `/stop` as the control.

**Expected:** the clean control exits 0 with `AWF-SHUTDOWN-OK`. The interrupted run either completes
or leaves a recoverable, complete generation; it never leaves a half-written store that cannot open.
The process never reports "saved" for data that is absent after restart.

---

## FR-007 — Corrupted store on a copy

**Destructive:** edits bytes in a copy of a store. **Risk:** High.

**Steps:**

1. Back up a world; stop the server.
2. Copy `<world>/region.awf/` to a second directory and truncate one file by 16 bytes, or flip bytes
   in the middle of an index file.
3. Point a disposable server at the copy (change the registry entry name and folder).
4. `/awf load`.
5. Restore the good copy; `/awf load`.

**Expected:** the corrupted store is refused with a checksum/framing error naming the store; it is
never silently regenerated or partially applied; the good copy loads.

**Failure indicators:** the world loads with silent missing chunks; a checksum error is swallowed;
the corrupted store is rewritten.

---

## FR-008 — Corrupted startup cache

**Destructive:** edits a cache file. **Risk:** Low.

**Steps:** stop; truncate `sourbycraft_config/cache/startup-index.cache`; start.

**Expected:** the cache is discarded (`discarded 1` in the startup-index line) and plugins load
normally; no error that stops the boot.

---

## FR-009 — Lease expiry and takeover on one node

**Destructive:** kills the process holding a lease. **Risk:** Medium.

**Steps:**

1. AWF Redis backend with `lease-seconds: 15`; open a world.
2. Inspect the lease key and its TTL (`redis-cli TTL <key>`).
3. `kill -9` the server; note the TTL.
4. Start a second server (same Redis/prefix) immediately: it must be refused until the lease expires.
5. After expiry, load the world; verify it opens and the fence is higher.

**Expected:** the lease is enforced by time, not by process identity; the takeover is clean; no
double writer.

---

## FR-010 — MultiProfile crash in the middle of a switch

See `03-EXPERIMENTAL-FEATURES-TESTING.md` §MP-006. It is the same destructive class: `kill -9`
during a switch, restart, rejoin, recover. Expected: one authoritative profile, never both.

---

## FR-011 — Partial lifecycle failure

**Destructive:** deletes/moves a store while the server is stopped. **Risk:** High.

**Steps:**

1. Create an instance and a template (AWF-002).
2. Stop; move the template away; start; load the instance (this is AWF-009).
3. Restore; stop; rename the world's `region.awf` to `region.awf.bak`; start; load.
4. Observe the refusal, not an empty world.
5. Restore; load.

**Expected:** every missing piece produces a loud refusal and **no** new store is created for a world
that had data. The registry entry stays until the owner deletes it.

---

## Stop conditions

Stop the session and file a bug immediately if any of these happen:

- a case reports success and the data is absent after a clean restart;
- a store opens but silently loses a chunk that was committed;
- a crash leaves a file that cannot be parsed and no clear error names it;
- a second server writes the same world;
- a failure damages a backup or a production directory (stop using that machine for tests).

Use `07-BUG-REPORT-TEMPLATE.md`; attach the logs and the exact command sequence.
