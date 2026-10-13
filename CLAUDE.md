# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The canonical agent guide is `AGENTS.md` (toolchain, build, patch workflow, config surfaces, release rules, truthfulness policy). It is imported here so the two cannot drift; add shared guidance there, and only Claude-specific notes below.

@AGENTS.md

## Running tests

```bash
# Gradle suites (what CI runs; test-plugin modules need the flag)
./gradlew -PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:test

# One server test class / method
./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.awf.AwfRegionStorageTest'
./gradlew :sourbycraft-server:test --tests '*AwfRegionStorageTest.someMethod'

# Python policy/tooling suite (scripts/test_*.py), and a single file
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s scripts -p 'test_patch_policy.py'
```

The AWF Redis backend tests start their own `redis-server` and skip without it; CI installs it so they must run there.

## Where code lives

- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/` — SourbyCraft-owned services: `awf` (Aurora World Fabric: region-aware world storage/templates/instances, attached from `RegionFileStorage`; see `docs/architecture/aurora-world-fabric.md`), `bootstrap`/`startup`, `config`, `perf`/`hud`/`spark` (observability), `update` (auto-updater), `bridge`, `command`, `brand`.
- `sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/` — the region-threading engine (former Canvas/Folia internals: `threadedregions`, engine configuration behind `config/canvas-*.yml`).
- Changes to Minecraft or Paper classes cannot be made in these sources; they go through feature patches (`sourbycraft-server/{minecraft,paper}-patches/features/`, numbered `NNNN-SourbyCraft-*.patch`). Rebuild them from the materialized repos with `rebuildMinecraftFeaturePatches` / `rebuildPaperServerPatches` after committing there.
- `scripts/` — Python release, benchmark, policy (`*_policy.py`) and private-Intave tooling, each with a `test_*.py`.

## Documentation map

- `docs/README.md` — developer reading path and index of every active doc. Pre-Aurora plans/specs (formerly `docs/superpowers/`) were removed; they are only in git history.
- `docs/architecture/AURORA.md` — entry point for all Aurora architecture docs, current state verified against code, and known contradictions between docs.
- `DEVELOPMENT.md` — governing rules (truthfulness policy, performance evidence requirements, stability budget, testing matrix).
- `TODO.md` — the single prioritized to-do list; `docs/releases/` — per-build release gates and evidence.

## Concurrent agents

When `AGENT-COORDINATION.md` exists (untracked, never committed), another agent is editing the same working tree. Read it first, don't edit files another agent owns, keep edits to shared files small and targeted, and record your own owned/shared files there.
