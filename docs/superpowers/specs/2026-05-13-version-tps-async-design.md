# Version System, TPS Overhaul, SIMD, Async & Branching

**Date:** 2026-05-13
**Status:** Design approved

---

## §G Goal

Modernize SourbyCraft's versioning (v1-DEV/REL/EXP), fix branding checks, enhance /tps command with MSPT/CPU/GC, support Java 25 SIMD, set up multibranch workflow, extend async multithreading for better multicore utilization, and update README.

## §C Constraints

- Compile target stays Java 21
- Runtime support: Java 21 through Java 25
- Async tasks must not introduce data races or deadlocks
- /tps command must work without optional `oshi` library (degrade gracefully)
- Must not break existing Paper/Pufferfish patch compatibility

## §I Invariants

1. `./gradlew createMojmapPaperclipJar` succeeds with zero errors on all branches
2. Server start log shows version as `vN-DEV`, `vN-REL`, or `vN-EXP`
3. Build time shows actual GMT+7 timestamp, not epoch
4. `/tps` shows TPS + MSPT + CPU + GC info
5. SIMD activates on Java 17-25 without "only safely supported" warning
6. `ver/1.21.11` branch produces `-REL` builds, `ver/1.21.11-dev` produces `-DEV`
7. Async features can be toggled in `sourbycraft.yml` at runtime

---

## §T Tasks

| # | Task | Area | Complexity |
|---|------|------|------------|
| T1 | Replace version format with `vN-DEV`/`vN-REL`/`vN-EXP` + build time GMT+7 | Versioning | Low |
| T2 | Fix `REPOSITORY` to `YanIanZ/SourbyCraft` in PaperVersionFetcher | Branding | Low |
| T3 | Enhance `/tps` with MSPT, CPU real name/cores, GC info | Command | Medium |
| T4 | Fix SIMDChecker for Java 17+ and update warning text | SIMD | Low |
| T5 | Set up branches: `ver/1.21.11`, `ver/1.21.11-dev`, `experimental-feat` | Branching | Low |
| T6 | Add async chunk loading worker to AsyncExecutor pool | Async | Medium |
| T7 | Add async pathfinding dispatch for non-player entities | Async | Medium |
| T8 | Add parallel entity tracking compute on chunk load | Async | Medium |
| T9 | Add `async-threads`, `async-pathfinding`, `async-chunk-load` config | Async | Medium |
| T10 | Update README with versioning, branching, TPS, async features | Docs | Low |

---

## §V Verification

1. Jar manifest contains `Implementation-Version: v1-DEV` and real build time
2. Server start: no "unknown version" warning, SIMD enables on Java 25
3. `/tps` output contains 6 lines (TPS, MSPT, CPU, GC)
4. `/tps` works without `oshi` — skips CPU line gracefully
5. Setting `async-chunk-load: true` speeds up `/worldborder fill` or teleport-heavy workloads
6. `async-pathfinding: true` reduces main-thread tick time with 100+ entities
7. Branching: `git branch -a` shows all three branches

---

## §B Design Decisions

1. **Version suffix from branch** — autodetect in buildscript rather than manual toggle
2. **oshi-core** — optional dependency for CPU detection; fallback to `os.arch`/`os.name` if missing
3. **AsyncExecutor reuse** — extend existing pool rather than creating new thread pools
4. **CompletableFuture pattern** — async tasks return futures; main thread merges results. No lock contention.
5. **Runtime toggles** — all async features default OFF; opt-in via `sourbycraft.yml`
