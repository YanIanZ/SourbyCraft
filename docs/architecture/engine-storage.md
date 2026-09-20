# Aurora Storage Engine — Ownership

T5 deliverable for §11.6 of `docs/AURORA-FULL-TRANSITION.md`.

## Summary

**Aurora owns no world-persistence policy today.** It measures the world I/O lane, and it owns
one executor that is explicitly *not* world persistence. The distinction matters enough to be
the first thing this document says, because the thread-name prefix invites the opposite
conclusion.

## 1. What Aurora owns

| Service | Owns | Not |
|---|---|---|
| `execution/ExecutionLane.WORLD_IO` | Attribution of reading and writing world data, by thread name: `Dimension-Data-IO-Worker`, `Paper I/O Worker`, `SourbyCraft-IO` | — |
| `util/BoundedIoExecutor` | **Administrative** blocking I/O: virtual threads named `SourbyCraft-IO-`, fixed admission bound, no queue, never blocks the caller | **Not** world saving, region-file writes, or chunk serialization |

`BoundedIoExecutor` exists so administrative work (updater downloads, provisioning, config
reads) cannot block a region thread. It carries no world data. The `WORLD_IO` lane attributes
its threads alongside upstream's I/O workers only because they share the lane's purpose —
*reading and writing data off the tick* — not because Aurora handles persistence.

**No direct NMS patch touches storage.** None of the 16 feature patches touch region files,
chunk serialization, or the save queue.

## 2. Metrics

| Metric | Source | Surfaced by |
|---|---|---|
| World I/O lane CPU share | `LaneCpuSampler` / `ExecutionLane.WORLD_IO` | `/perf lanes` |
| RSS over the measurement window, with drift | `run_baseline.py` (`metrics.rss`, `metrics.drift`) | `baseline.json` |

## 3. What Aurora does not own

Everything §11.6 lists:

- save queue behavior
- serialization handoff
- region-file write policy
- shutdown flush
- persistence validation

## 4. The rule that governs this domain

§11.6 states it directly, and it outranks any performance argument:

> No performance gain may come from silently weakening durability.

This is the one T5 domain where a "win" can be indistinguishable from data loss until much
later. Any future work here needs persistence validation *before* a throughput number, not
after — `docs/BASELINE.md` already treats persistence as a separate qualification from
performance, and T10 keeps them separate.

A related standing caution: the baseline harness records `metrics.drift` for RSS, and a rising
RSS over a short window is **not** by itself a leak. A five-minute `idle` run on 2026-09-20
showed RSS moving 1.97 GB → 2.48 GB (+26%) with only two GCs in the window — too few
heap-after-GC samples for the harness to even compute a heap trend (`"too few heap-after-GC
samples for a trend (2)"`). A leak claim from this domain needs a soak, not a short run; a
previous "+310 MB/hour" claim in this project was made from four GC points and withdrawn when a
fifth dropped 217 MB.

## 5. Gate status

| T5 requirement | Storage |
|---|---|
| Ownership document | this file |
| Metrics | world I/O lane CPU, RSS + drift |
| Implementation boundary | §1 — measurement plus administrative I/O only, no persistence policy |

Honest status: unowned, instrumented, and gated behind durability validation rather than a
performance target.
