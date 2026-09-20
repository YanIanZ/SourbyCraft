# Aurora Network Engine — Ownership

T5 deliverable for §11.5 of `docs/AURORA-FULL-TRANSITION.md`.

## Summary

**Aurora owns no network policy today.** It measures the network lane and nothing else. This
document exists because the T5 gate asks for a boundary per domain, and a domain with an empty
implementation is still a boundary worth writing down — it is what stops the next reader
assuming the work was done.

## 1. What Aurora owns

| Service | Owns |
|---|---|
| `execution/ExecutionLane.NETWORK` | Attribution of packet encode, decode and socket work, by thread name: `Netty Epoll IO`, `Netty Kqueue IO`, `Netty NIO IO`, `Netty Server IO` |

That is the whole list. There is no Aurora packet path, no compression policy, no queue-health
service, and **no direct NMS patch touches networking** — all 16 feature patches are entity, AI,
chunk, world, runtime or command patches.

## 2. Metrics

| Metric | Source | Surfaced by |
|---|---|---|
| Network lane CPU share | `LaneCpuSampler` / `ExecutionLane.NETWORK` | `/perf lanes` |

Observed on the deployment server (idle, 0 players): `Network: 0.01 cores (5.6% of used)`. The
lane resolves and reports, so the measurement surface is real — it has simply never been put
under load.

## 3. What Aurora does not own

Everything §11.5 lists:

- packet instrumentation
- allocation/copy analysis
- compression policy implementation
- chunk packet path
- entity tracking packet path
- queue health

§11.5 also fixes one constraint in advance: **operator-visible compression settings remain
operator-owned.** Whatever Aurora eventually implements here, it does not get to quietly choose
an operator's compression threshold.

## 4. Why this domain is not started

Two reasons, both measurement:

1. **There is no network workload evidence.** `network-stress` exists in
   `run_baseline.py --workload`, but no certified run of it exists, and §4.5 allows no
   optimization without one.
2. **Network cost scales with connected clients**, which is the same constraint that blocks the
   entity domain ([client gap](engine-entity-ai.md#4-the-measurement-constraint-this-domain-has)).
   A network baseline with zero players measures an idle socket.

## 5. Gate status

| T5 requirement | Network |
|---|---|
| Ownership document | this file |
| Metrics | network lane CPU (`/perf lanes`) |
| Implementation boundary | §1 — measurement only, no policy |

Honest status: the lane is instrumented, the domain is unowned, and the first real step is a
certified `network-stress` run with clients attached, not code.
