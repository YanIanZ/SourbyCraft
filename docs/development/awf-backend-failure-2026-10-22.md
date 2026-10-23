# AWF backend failure — Build 49 gate evidence (2026-10-22, opus-49-backend)

Gate row: [26.2-build-49.md](../releases/26.2-build-49.md) *Backend failure* (PARTIAL). Required:
D4 closed or accepted in writing; Redis over a real network; SIGSTOP rerun on current code; Moonrise
read-failure log flood bounded. Scenario definitions and earlier runs:
[aurora-world-fabric.md § Backend failure](../architecture/aurora-world-fabric.md#backend-failure-redis-gone--lease-lost-on-a-running-server).
Runbook: [G09](../releases/runbooks/build-49-real-test.md#g09-backend).

**What this is.** Functional evidence from the agent test server on a shared developer laptop, with no
players. It is **not** the G09 run: that needs the release jar, at least 2 players and Redis on a
separate host over a real network, and it stays owner-run. Nothing here is a qualification or a
performance claim. Timings are single observations on a shared machine.

## Setup

| Item | Value |
| --- | --- |
| Host | Apple M2, 16 GiB, macOS 27.0, JDK 25.0.2; shared with other agents' servers and Gradle builds |
| Tree | `26.2` @ `001b86f1` (Build 48 merge, includes MC 0071/0072); worktree `sc-worktrees/opus-49-backend` |
| Jars | run 1: `7c4c731e8579…` (001b86f1, unchanged code); runs 2–4: `0075add72b24…` (001b86f1 + the log change below, `/ver` "Build 48-dirty") |
| Harness | `scripts/agent_testserver.py`, 2048 MiB heap, view distance 4, seed 440044, `online-mode=false` |
| Redis | `redis-server` 8.10.2 on 127.0.0.1:6402 (slot-2 convention), `appendonly yes`, `appendfsync everysec`, `noeviction`, started for these runs |
| `aurora.toml` | `[aurora.awf] worlds=["world"]`, `backend="redis"`, `commit-interval-seconds=5`, `resident-chunks=1`, `commit-attempts=3`; `[aurora.awf.redis] lease-seconds=15`, `timeout-ms` 5000 (default), `shutdown-deadline-ms` 30000 (default) |
| Faults | `kill -STOP`/`-CONT`/`-9` on the redis-server PID; for the network approximation a userspace TCP proxy (below) |
| Probes | marker blocks placed with `setblock`; presence checked after reloads/reboots with `clone X 100 Z X 100 Z X Y' Z filtered <block>` ("cloned 1" = present) |
| Raw evidence | `/Volumes/Ext/DevCache/agent-tmp/sourbycraft/opus-49-backend/` (`events.txt` timeline, `run1..4-server.log`, `s3-sofar.log`, `s4-outage.log`, `s5-outage.log`, `n2.log`, `s2-stop.json`, `n4-stop.json`, `faultproxy.py`) |

## 1. D4 — `save-all flush` durability (MC 0072): closed by code, tests and runs

D4 as defined in aurora-world-fabric.md: on a region thread `AwfRegionStorage.flush` only starts an
async commit, so `save-all flush` printed "Saved the game" while AWF commits failed.

Code on `001b86f1`: MC 0072 replaces the one `sendSuccess("commands.save.success")` in
`SaveAllCommand` with `SaveAllFeedback.saved(source, flush)`. With AWF storages open and `flush`,
`AwfFlushReporting.afterRegionSaves` sends only a pending line, waits (≤ 30 s) for the chunk I/O
queue to go idle, then takes one `AwfRegionStorage.flushBarrier()` per open storage and sends the
vanilla success **only** when every barrier is `DURABLE` and the queue drained; otherwise
"Save NOT durable: …" with the storages and dirty counts. `flushBarrier` returns `DURABLE` only for a
commit started after the call that succeeded (or with nothing dirty); dirty entries leave
`AwfWorld.dirty` only after `store.commit` returned (`AwfWorld.commitInOrder`, version-checked
`remove`), so "nothing dirty" means everything written was confirmed. For Redis a confirmed commit
waited for `WAITAOF 1 0` (local AOF fsync) when `wait-for-aof = true` (default; logged at boot:
"commits wait for AOF"). Tests: `AwfFlushReportingTest` 7/0 (pending first; success only when every
storage is durable; a failing storage named with its dirty count and no success; I/O that never
idles is unconfirmed; barrier waits for I/O; plain `save-all` unchanged; closed storages skipped).

Server observations (all with the AWF Redis world):

| Case | Output | Durable state |
| --- | --- | --- |
| Healthy, loopback (run 1, 13:44:11) | pending → "Saved the game" → "9 storage(s) durable, 100 chunk(s) committed" | markers M1/M2 present after reboot |
| Redis SIGSTOPped (S1, 13:45:15) | pending; no success while frozen; after SIGCONT (13:46:00) "Saved the game … 392 chunk(s) committed" at 13:46:01 | the success came only after the commit was confirmed; M3 present after reboot |
| Redis SIGKILLed (S3, 13:48:00) | pending → "Save NOT durable: … could not commit 2 of 9 storage(s), 1274 chunk(s) only in memory … Connection refused", **no** "Saved the game" | — |
| Partition via proxy (N2, 14:14:14) | pending → after 61 s "Save NOT durable … Read timed out", no success; after heal the next flush "durable" in 2 s | M7 present after reboot |
| Healthy behind 50 ms / 200±100 ms delay (N1, N5) | pending → success after 6.9 s / 57 s | M6 present after reboot |

Every marker acknowledged by a "Saved the game" from `save-all flush` (M1, M2, M3, M4, M6, M7, M8,
M9) was present after a reboot; the two markers placed after the last confirmed save (M5 at S2, M10
at N4) were absent, and the shutdown reported their storages UNCERTAIN/REFUSED, not saved.

**Statement: D4 is closed** on code that contains MC 0072 (26.2 since Build 48), as far as D4 is
defined. Residual behaviour that an owner may want to accept explicitly: plain `save-all` (no
`flush`) still prints "Saved the game" at once (vanilla meaning, plus a line with the AWF chunks still
in memory); with the backend unreachable a flush answer can take `commit-attempts × timeout-ms` plus up
to 30 s of I/O wait (S1: 46 s, N2: 61 s); a chunk regenerated in place of a fenced (unreadable) one is
refused, not dirty, so it does not make a flush "NOT durable" — that is D1's protection by design.
Without AOF (`wait-for-aof` false or Redis < 7.2) "durable" means acknowledged by Redis's memory.

## 2. SIGSTOP rerun on current code

Run `opus-49-backend-1` (jar `7c4c731e…`, 26.2 @ 001b86f1 unchanged). Timeline in `events.txt`.

**S1 — SIGSTOP 47 s with a commit in flight** (13:45:13–13:46:00). Before: gen 5, markers M1 diamond
(chunk 100,100) and M2 emerald (106,106) committed and evicted. A `forceload` of 100 new chunks was
issued 4 s before the freeze so commits were large and running.

| Expected (doc) | Observed |
| --- | --- |
| calls fail within `timeout-ms` | lease renewals "Read timed out" every 5 s (5002 ms per pass, WARN that it exceeded half the period) |
| periodic commit fails after its attempts, chunks stay dirty | overworld/region commit failed at 13:45:30, retried; `/perf awf` retries/failures 4/1 |
| reads of evicted stored chunks fail and are fenced (D1) | `forceload add 1600 1600`: 100,100 and neighbours failed one at a time (5 s each), 8 positions fenced; the chunk was generated in memory (diamond absent while loaded); writes of fenced positions refused |
| new terrain still generates | M3 gold placed in a newly generated chunk during the freeze; present after reboot |
| `save-all flush` not "Saved" while not durable | see D4 table: no success until after SIGCONT |
| after SIGCONT commits resume, expired leases taken back | first commit within 1 s; gen 5 → 6; leases held again (PTTL ~12 s); dirty 0 |
| fenced stored chunk loads intact once Redis answers | after unload/reload of 100,100: "AWF read chunk … again" for 8 positions; diamond present (`cloned 1`) |

Finding (observability, not data safety): no "lease … had lapsed … taken back" line appeared after
SIGCONT, although the leases had expired in Redis. The renewals and commits queued in the socket
buffers during the freeze were executed by Redis on SIGCONT; the first of them took the lease back
under the D2 rule and its reply was lost (the client had already closed that connection), so the
server never saw the take-back. The same happened after the proxy partition (N2). With SIGKILL +
restart (S3/S5) the take-back was logged ("had lapsed … taken back", 9 storages).

**S2 — SIGSTOP during `stop`** (14:00:57). M4 lapis placed and confirmed by `save-all flush`
("9 storage(s) durable"); M5 redstone placed after it (dirty) and 16 new chunks forceloaded; Redis
SIGSTOPped; `stop`.

| Expected (D6 fix) | Observed |
| --- | --- |
| stop bounded by `shutdown-deadline-ms` 30000 | harness stop returned after **31.2 s**; log "shutdown began" 14:00:57, deadline report 14:01:27 (30013 ms) |
| exit status 70 (MC 0071) | `exit_code: 70` |
| one aggregate alert with outcomes | `AWF-SHUTDOWN-INCOMPLETE`: overworld/region UNCERTAIN (1467 dirty positions), overworld/entities REFUSED (66), 544 refused writes (the fenced positions); 7 other storages "committed … could not release … no chunks are lost" |
| after SIGCONT an uncertain commit may or may not appear | gen stayed 15 → the uncertain commit was **not** applied this time |
| reboot reads what was confirmed | run 2 over the same world and Redis (after lease expiry): M1, M2, M3, M4 present; M5 absent (it was never confirmed) |

## 3. Moonrise read-failure log flood

Already bounded before this change (2026-10-20: `ReadFailureLog`, `RepeatedReadFailureLogFilter`);
rerun to measure it on current code, which found two other per-event records of the same outage
that were not bounded.

**S3, unchanged jar** — Redis SIGKILLed, `forceload add 0 0` over the stored spawn area (60 s log):
529 reads failed; **4 ERROR records** in total (SourbyCraft first failure with stack, the two Moonrise
records of that first failure, one summary "failed 529 time(s) in the last 11 s"). The read-failure
flood is bounded: the Moonrise records of repeats were all dropped. But the log of those 60 s was
749 lines / 74.6 KB, of which **603 lines were 28 WARN records "AWF commit for … failed" each with a
full stack** (one per 5 s commit interval per failing storage, unbounded over an outage). After
Redis came back, reloading the area logged **529 INFO lines "AWF read chunk … again" in one second**.

**Change (logging only; Sourby code, no patch):** `ReadFailureLog` gained a summary formatter and two
factories; `AwfRegionStorage` uses them:
- `recoveryLog` (INFO, 10 s window): first recovered position on its own line, then "AWF read N fenced
  chunk position(s) of <storage> again in the last S s …".
- `commitFailureLog` (WARN, 60 s window): the first failed periodic commit of a window with its stack,
  the rest counted; the summary "AWF commits for <storage> failed N time(s) in the last S s; only the
  first was logged with its stack …" is written when the window ends or as soon as a periodic commit
  succeeds.
Fencing, refused writes, retries, `lastCommitFailed`, flush reporting and the read-failure log are
unchanged. Files: `awf/ReadFailureLog.java`, `awf/AwfRegionStorage.java`,
`awf/AwfReadFailureTest.java` (+2 tests: 729 recoveries → 1 line + 1 summary, all unfenced, writes
accepted; 5 failed periodic commits → 10 attempts as before, 1 WARN with stack + 1 summary on the
first success). Mutation check: restoring the per-event log calls makes both tests fail (2 failures),
restored.

**Reruns with the change** (jar `0075add7…`, run 2):

| Window | Lines / bytes | Records |
| --- | --- | --- |
| S4: SIGKILL, `forceload add 1600 1600` (729 stored positions), 77 s | 209 / 27.2 KB | 8 ERROR (2 storages × first + 2 Moonrise + summary "failed 729 time(s)") |
| S5: SIGKILL with dirty chunks (setblock every 2 s), 75 s | 129 / 13.1 KB | 6 commit WARN records (per storage: first with stack, summary "failed 12 time(s) in the last 61 s", next window's first); summaries "failed 3 time(s)" on recovery |
| Recovery after S4 | 4 INFO lines | "AWF read 25 fenced chunk position(s) … again" + 2 single lines (entities, region) |

So per storage and outage minute: read failures ≤ 1 first record (+ 2 Moonrise records of it) and 1
summary per 10 s; commit failures ≤ 1 record with stack and 1 summary per 60 s; recoveries ≤ 1 line
and 1 summary per 10 s. Remaining per-event lines: the lease-renewal WARN pair every renewal period
(one line each for all stores, no stack), unchanged.

## 4. "Redis over a real network" — loopback approximation only

Docker was not available (Docker Desktop installed but its daemon was not running; not started, so as
not to load the shared machine), so no `tc netem`. Used a userspace TCP proxy instead
(`faultproxy.py`: 127.0.0.1:6412 → 6402; modes `delay <ms> [jitter]` per forwarded read chunk in each
direction, `blackhole` = stop forwarding with connections left open, `dropreplies` = requests reach
Redis, replies discarded). It has no packet loss or reordering (TCP stays reliable) and its per-chunk
delay also throttles bandwidth for large commits, so it is harsher than a real link of the same RTT
for big commits and milder for loss. **This is a labelled approximation; a run with Redis on a
separate host over a real network stays owner-run (G09).** Runs 3 and 4 (jar `0075add7…`).

| Scenario | Observed |
| --- | --- |
| N1 delay 50 ms/direction | boot `Done` 18.6 s (loopback 9.0 s, same world); stored markers read back; `save-all flush` durable after 6.9 s; one 228-chunk commit took 5.1 s (proxy throttle) |
| N2 partition 70 s, M7 placed | renewals time out; flush "Save NOT durable" after 61 s, no success; heal → next flush durable in 2 s; no AWFLEASE; take-back not logged (same buffered-request effect as S1) |
| N3 replies dropped 25 s with a commit in flight | gen 29 → 31 while the server saw only timeouts (commits applied, replies lost); after heal commits continue (gen 33), **no** "lost its Redis lease"/AWFLEASE: the D2 fix holds |
| N4 partition, then `stop` | 31.1 s, exit 70, region UNCERTAIN (5 positions), entities REFUSED (5); gen unchanged after heal; reboot: M6–M9 present, M10 (unconfirmed) absent |
| N5 delay 200 ms + 0–100 ms jitter | 49 new chunks + flush: durable after 57 s; overworld/region commit p50 8.7 s, p95 18.5 s, 5 commit retries, 0 failures |
| clean stop at 50 ms | `AWF-SHUTDOWN-OK … 9 storage(s) … 12785 ms` (loopback 1545 ms), exit 0, 0 lock keys |

Findings for the real-network run (not fixed here): with commit payloads of several MB, `timeout-ms`
5000 is reached on a slow path and commits are retried (idempotent, but each retry resends the whole
payload); the default 30 s shutdown deadline left 17 s of margin at 50 ms and would be tight at
higher latency — measure both on the real network before choosing defaults.

## Tests and checks (worktree)

- `:sourbycraft-server:test` (SourbyCraftTestSuite, `SOURBY_REQUIRE_REDIS=1`): 2308 tests, 0 failures,
  6 skipped; `AwfReadFailureTest` 9/0, `ReadFailureLogTest` 6/0, `RepeatedReadFailureLogFilterTest`
  8/0, `AwfFlushReportingTest` 7/0, `AwfShutdownDeadlineTest` 7/0, `RedisBackendTest` 21/0.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`: 619 OK.
- `patch_policy.py`, `baseline_policy.py`, `inherited_guard_policy.py`: OK; `patch_surface.py --check`: passed.
- All runs stopped (runs 2 and 4 exit 0, `AWF-SHUTDOWN-OK`; runs 1 and 3 exit 70 by design of S2/N4);
  slots released; Redis and proxy stopped.

## Gate items

| Required | State after this work |
| --- | --- |
| D4 closed or accepted in writing | closed by MC 0072 (code, 7 unit tests, healthy/frozen/down/partition runs, marker read-back); residuals listed in § 1 for the owner |
| Redis over a real network | **not done**: loopback proxy approximation only (§ 4); owner-run on a separate host remains |
| SIGSTOP rerun on current code | done on 001b86f1 (S1, S2), behaviour as documented; one observability finding (silent take-back) |
| Moonrise read-failure log flood bounded | read failures bounded (unchanged code, S3); the recovery INFO and periodic-commit WARN floods found in the rerun are now windowed too (logging-only change, not yet applied to 26.2) |
