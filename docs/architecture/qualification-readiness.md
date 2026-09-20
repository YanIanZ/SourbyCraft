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
| representative benchmark exists | **partial** | 7 of 11 workloads (§2) |
| no unexplained >3% regression in affected workloads | **blocked** | needs two certified runs to compare; none exist |
| no known region ownership bug | **one open** | §5 |
| no known persistence bug | **unknown** | nothing validates persistence (§4) |
| no unbounded queue | **met** | `UnboundedQueueAuditTest`, 3 tests |
| heap/threads/tasks stabilize after load | **blocked** | needs the 2h soak |
| shutdown completes predictably | **partial** | `AsyncPathShutdownTest`, `AuroraRuntimeTest`; not exercised under load |

---

## 2. Workloads — 7 of 11

Defined in `scripts/baseline_workloads.py`:

| §16 requires | Have | Note |
|---|---|---|
| idle | ✔ `idle` | certifiable without clients |
| 10 / 50 / 100 players | ✔ `players-10/50/100` | `requires_connected_players` |
| entity stress | ✔ `entity-stress` | `requires_connected_players` |
| network stress | ✔ `network-stress` | never certified |
| chunk traversal | ✔ `chunk-stress` | closest existing match; certifiable without clients |
| **AI stress** | ✘ | distinct from entity stress: goal/brain/pathfinding cost, not tick count |
| **generation stress** | ✘ | `chunk-stress` covers load/unload, not sustained worldgen |
| **save stress** | ✘ | no workload drives the save path |
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

## 4. Persistence — no coverage

§16 requires validation of clean shutdown, restart, chunk save integrity, player data, entity
data, region files and world metadata.

**Nothing in `scripts/` validates any of it.** `verify-patch-parity.sh` and
`verify_build_identity.py` check the build, not the data.

This is the largest single gap in T10, and the riskiest to leave: §11.6 states that no
performance gain may come from silently weakening durability, and there is currently no way to
notice if one did. A save-path optimization cannot be accepted before this exists.

---

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

1. **Persistence validation tooling** — the only gap needing no quiet machine and no clients,
   and the one guarding a failure mode that is invisible until it costs data.
2. **`idle` + `chunk-stress` certified references** — one quiet window, no clients.
3. **The four missing workloads**, AI stress first, since it covers the domain with the most
   Aurora-owned policy.
4. **Client-attached runs** for `players-*` and `entity-stress`.
5. **2h soak**, then the regression gate.
