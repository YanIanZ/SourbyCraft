# T10 Qualification — Readiness

What §16 of `docs/AURORA-FULL-TRANSITION.md` requires, against what exists. Written so the
remaining work is a list rather than a rediscovery, and so nothing here reads as done when it
is not.

T10 is the gate the rest of the transition waits on: §4.5 allows no optimization without
measurement, so T5's engine domains cannot move from *documented* to *optimised* until this
passes.

---

## 1. Gate items

| Gate requirement | State | Evidence |
|---|---|---|
| representative benchmark exists | **partial** | 8 of 11 workloads (§2) |
| no unexplained >3% regression in affected workloads | **blocked** | needs two certified runs to compare; none exist |
| no known region ownership bug | **one open** | §5 |
| no known persistence bug | **no bug found** | `verify_persistence.py`, 16 checks; 4/4 on the deployment server (§4) |
| no unbounded queue | **met** | `UnboundedQueueAuditTest`, 3 tests |
| heap/threads/tasks stabilize after load | **blocked** | needs the 2h soak |
| shutdown completes predictably | **partial** | unit tests, plus two clean console shutdowns and a live restart on the deployment server; not yet exercised *under load* |

---

## 2. Workloads — 8 of 11

Defined in `scripts/baseline_workloads.py`:

| §16 requires | Have | Note |
|---|---|---|
| idle | ✔ `idle` | certifiable without clients |
| 10 / 50 / 100 players | ✔ `players-10/50/100` | `requires_connected_players` |
| entity stress | ✔ `entity-stress` | `requires_connected_players` |
| network stress | ✔ `network-stress` | never certified |
| chunk traversal | ✔ `chunk-stress` | certifiable without clients |
| generation stress | ✔ `chunk-stress` | its moving window generates fresh terrain continuously; the plan's own fidelity note says generation dominates. Listed as missing here until 2026-09-20, which was wrong |
| save stress | ✔ `save-stress` | rewrites loaded chunks that keep changing, then flushes; needs no clients, so certifiable in the same window as `idle` |
| **AI stress** | ✘ | distinct from entity stress: goal/brain/pathfinding cost, not tick count. Needs connected clients to mean anything |
| **plugin-heavy representative** | ✘ | no representative plugin set defined |

---

## 3. Metrics — 10 of 14

From a real `baseline.json` (`idle`, 2026-09-20):

| §16 requires | State | Source |
|---|---|---|
| TPS | ✔ | `metrics.tick.tps` (mean/min/p50/p95/p99/max) |
| MSPT average | ✔ | `metrics.tick.mspt.mean` |
| MSPT p50/p95/p99/max | ✔ | `metrics.tick.mspt` |
| CPU | ✔ | `metrics.cpu` — process, machine and foreign fractions |
| heap | ✔ | `metrics.tick.heap_used_bytes` |
| RSS | ✔ | `metrics.rss` + `metrics.drift` |
| allocation rate | ✔ | `metrics.allocation.bytes_per_second` |
| GC pauses | ✔ | `metrics.gc` |
| queue depths | **partial** | async-path pool only (`/perf async`); no chunk, network or save queue |
| task latency | **partial** | async-path wait latency only |
| chunk latency | ✘ | — |
| entity cost | ✘ | — |
| network throughput | ✘ | — |
| storage backlog | ✘ | — |

The four absent ones are the T7 telemetry items that need hooks into upstream paths; see
[the network](engine-network.md) and [storage](engine-storage.md) domain documents.

---

## 4. Persistence — covered and passing

`scripts/verify_persistence.py` writes known state, stops the server, inspects the files on
disk while nothing holds them, boots a second server over the same directory and reads the
state back. Structural checks and round-trip checks are both kept, because a region file can be
structurally valid and hold the wrong chunk.

| Run | Result |
|---|---|
| Local fixture, build 46c | **16 / 16 checks** |
| Deployment server, live restart | **4 / 4** — 64/64 blocks, 24/24 entities, game time advanced, gamerule survived |

Two limits are stated rather than hidden:

- **Player data is structural only.** No client connects, so there is no player file to
  round-trip; the check reports its own limitation in its result line.
- **Region files in unused dimensions** reference up to one sector past EOF while the
  overworld's are exact, and the server reads all of them. The trailing sector is unpadded, not
  missing, so overruns under 4 KiB are reported benign and a whole missing sector still fails.

§11.6 forbids buying performance with durability. This is the check that would notice.

## 5. Known region-ownership bug

One, open and unreproduced.

`/say` from the console threw, once, during a config reload on the deployment server:

```text
NullPointerException: Cannot invoke "ServerLevel.getGameRules()" because the return value of
  "CommandSourceStack.getLevel()" is null
    at Commands.executeCommandInContext(Commands.java:461)
    at RegionizedServer.globalTick(RegionizedServer.java:278)
```

Same family as the fixed `/execute if block` crash (patch 0016): a console command running on a
tick thread with no region context bound. **24 subsequent `/say` calls interleaved with a
reload produced 0 reproductions**, so it is recorded rather than patched — a speculative guard
on one sighting would be a change nobody can verify.

The gate says *no known region ownership bug*. This is a known one until it is either
reproduced and fixed, or explained.

---

## 6. Blocked on a quiet machine

Certification requires foreign CPU below **10%** of the machine for the whole window.
Four attempts on 2026-09-18/20 measured 21–23% average with peaks of 55–78%, against a
development desktop in active use. The runs produce complete evidence and are refused only as
*comparison references*, which is the correct behaviour.

Consequences, in order of what they block:

1. no certified reference → no regression comparison → the >3% gate cannot be evaluated,
2. no 2h soak → heap/thread/task stabilisation cannot be shown,
3. `players-*` and `entity-stress` additionally need real clients attached
   (`--connected-players N`), because entity activation is computed around players — see the
   [client gap](engine-entity-ai.md#4-the-measurement-constraint-this-domain-has).

`idle` and `chunk-stress` certify without clients and are therefore the cheapest first
references to obtain.

---

## 7. Order of remaining work

1. ~~Persistence validation tooling~~ — **done**, §4.
2. **`idle` + `chunk-stress` certified references** — one quiet window, no clients. Still the
   binding constraint: without a reference there is nothing to compare a regression against.
3. **The remaining workloads**: AI stress, which needs clients, and a plugin-heavy
   representative set, which needs a decision about which plugins represent the product.
4. **Client-attached runs** for `players-*` and `entity-stress`.
5. **The regression gate**, once two certified runs of the same workload exist.

### What the deployment server can and cannot settle

The panel is dedicated and idle, which is exactly what this desktop is not, so the soak runs
there. What it reaches without clients: sustained load, chunk load/unload cycling, heap
recovery after the load is removed, restart, and shutdown. What it cannot reach: repeated
joins/quits and any entity-AI load, because mob AI does not run without a player in activation
range — driving mobs there would measure the inactive path and call it a soak.

It also cannot produce a *certified baseline*: `run_baseline.py` needs a shell on the host to
record JFR and operating-system samples, and the panel offers a console and a file API. So the
panel settles stability; certification still needs the local harness on a quiet machine.
