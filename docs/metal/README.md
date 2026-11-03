# Aurora Metal Program — Build 56–100

**Role:** forward-looking major upgrade program for SourbyCraft/Aurora Engine. **Status:** PROPOSAL, not a shipped feature or measured optimization. Target owner: SourbyCraft maintainer. First delivery: [Metal vendored hardening](../../Metal/README.md) (candidate code; default provider unchanged).

> **Metal here is the Spottedleaf/Luminol Java concurrency utility family**, not Apple's Metal GPU framework. This program concerns Minecraft CPU, thread scheduling, memory, chunk/region data structures, queueing and storage. No GPU acceleration, Vulkan or Apple GPU backend is implied.

## Purpose

Use Metal utilities as a **well-defined, replaceable foundation** for Aurora's concurrency internals across planned Builds 56–100, without violating Folia/region thread ownership and without treating map thread-safety as permission to mutate world state off-thread. The architecture separates a stable public Minecraft/plugin API from internal helpers. Later benchmarks determine whether an upgrade wins.

## Baseline and upgrade lanes

- **Default: `-PmetalProvider=upstream`** (or absent) — existing `ca.spottedleaf:leafpile:1.2.0`. This preserves the current SourbyCraft default while the owner tests alternatives.
- **Canary: `-PmetalProvider=modular`** — BOM version pinned in root `metalModularVersion=1.3.2`; modules selected in the SourbyCraft server build patch. Upstream LeafPile releases are not automatically applied.
- **Experimental source-owned: `-PmetalProvider=vendored`** — builds tracked `:Metal` source with generated specialized maps. Keep this mode isolated until classpath, licensing, ABI and benchmark gates pass.
- All three lanes are **build-time selections**, not effective `aurora.yml` switches. Do not put overlapping provider classes on one production runtime classpath.

As of the 2026-11-03 investigation, upstream [LeafPile v1.3.2](https://github.com/Tuinity/LeafPile/releases/tag/v1.3.2) is a released modular version; upstream master has a newer SNAPSHOT which is not adopted automatically. Current Paper's mainline Gradle build also uses BOM 1.3.2, but SourbyCraft 26.2 has a different pinned Paper baseline. Do not infer ABI compatibility without owner build evidence.

## Documents

1. [ARCHITECTURE.md](ARCHITECTURE.md) — boundaries, ownership/threading, integration zones, resource governance.
2. [BUILD-56-100-ROADMAP.md](BUILD-56-100-ROADMAP.md) — release-range epics and feature gates, not a promise to ship all features on exact builds.
3. [UPSTREAM-AND-ABI.md](UPSTREAM-AND-ABI.md) — long-term update process, version pins, dependencies, fork provenance and compat.
4. [PERFORMANCE-QUALIFICATION.md](PERFORMANCE-QUALIFICATION.md) — owner benchmarks, profiling, correctness and rollback.
5. [CURRENT-PR-SCOPE.md](CURRENT-PR-SCOPE.md) — exactly what the first upgrade implements and what it intentionally defers.

## Design priorities

1. **Correctness and region ownership**: old world and profile state must not be corrupted or concurrently mutated.
2. **Reproducible build**: explicit upstream versions, immutable source provenance, generated code reproducible.
3. **Latency isolation**: async tasks, I/O lanes and bounded queues preserve player/region tick budgets.
4. **Memory efficiency**: fewer unneeded allocations, safe buffer/object lifetimes, no unbounded caches.
5. **Observability**: per-subsystem contention, queue wait, p95/p99, memory and fallback counts.
6. **Performance**: benchmarks may select a new default only when gains are repeatable and no unacceptable regression exists.
7. **Maintainability**: no duplicate upstream classes, undocumented fork deltas or startup auto-updates.

## Compatibility with Aurora Territory Sharding

Metal remains **single-JVM, in-memory concurrency infrastructure**. It cannot enforce cross-server world ownership, replace Redis/SQL, or make remote chunks safe to tick. Any territory/writer fencing is enforced at the authoritative storage/commit protocol, not by swapping hash-map implementations. See the [ARF/AIE companion documentation PR #63](https://github.com/YanIanZ/SourbyCraft/pull/63) for the separately proposed partitioned-world research plan (not part of this Metal PR).

## Governance

Every update has: upstream tag/commit, vendored diff, reproducible candidate SHA, risk classification, qualified provider, owner acceptance cases, rollback procedure and measured delta if a performance claim is made. A new release number alone is neither a performance improvement nor acceptance. Existing [docs/TODO.md](../TODO.md) and [Build 50 readiness](../releases/BUILD-50-READINESS.md) remain authoritative for **current** release status.

**Build 100** is the existing 26.3/Aurora Forge milestone. This program does not edit `sourbyBuild`, `release`, Gradle/Paper upstream pins or shipping flags without explicit qualification.
