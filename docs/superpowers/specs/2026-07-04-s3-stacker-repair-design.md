# S3 — Stacker Repair (wildstacker alias + blacklist/hologram/LOS wiring)

**Date:** 2026-07-04
**Status:** Approved (continuous execution authorized by user: "lanjut aja sampe semuanya selesai")
**Parent effort:** Placeholder-config remediation (S3 of S1–S6).

## Problem

1. **Live bug:** operator files from pre-26.1.2 era carry
   `performance.wildstacker.enabled: true`, but the item stacker reads
   `stacker.enabled` (default `false`). Result: stacker silently OFF on
   servers that believe it is on (confirmed in `TestServer/sourbycraft.yml`,
   which has `performance.wildstacker.*` and **no** `stacker:` section).
2. `stacker.blacklist` is parsed (`SourbyCraftConfig.java:370-379`) but never
   consumed by `EntityStacker`.
3. `performance.wildstacker.hologram` and `performance.wildstacker.los-check`
   are dead keys; in code the hologram and the line-of-sight check are
   hardcoded ON.

## Design

Per user direction: never delete config systems — wire them.

### 1. Alias + seed migration (SourbyCraftConfig)

`stacker.*` stays canonical. When a canonical key is **absent** and the legacy
`performance.wildstacker.*` twin **exists**, seed the canonical key from the
legacy value, then read canonical as usual (helpers persist it to disk).
Legacy keys are left in place (documented aliases, never deleted).

- `performance.wildstacker.enabled` → seeds `stacker.enabled`
- `performance.wildstacker.hologram` → seeds new `stacker.hologram` (default true)
- `performance.wildstacker.los-check` → seeds new `stacker.los-check` (default true)

Explicit `stacker.*` always wins (isSet check — seed only when absent).

### 2. New config fields

`stackerHologram` (bool, true), `stackerLosCheck` (bool, true).

### 3. EntityStacker wiring

- `BLACKLIST`: parse `stackerBlacklist` strings via `Material.matchMaterial`
  into an immutable `Set<Material>` at `reload()`. Names that are not
  materials (legacy EntityType defaults like `PLAYER`, `WITHER`) are ignored
  harmlessly — semantic: blacklist of **item materials excluded from
  stacking** (spawn-merge, sweep-merge, and extended-cap on vanilla merge all
  skip blacklisted types).
- `LOS_CHECK`: gates both `hasLineOfSight` call sites (spawn path + sweep).
  Disabled → merge by radius only (cheaper, WildStacker-classic behavior).
- `HOLOGRAM`: gates hologram creation/refresh. When off, `updateHologram`
  degrades to `removeHologram` so stale holograms self-clean on next touch.
- Boot log line extended with hologram/los/blacklist-size.

## Non-goals

- No mob stacking (explicit prior user direction: items only).
- No JUnit (project convention; manual TestServer boot).
- NMS untouched — pure `src/main/java`, no feature patch, no nested git.

## Verification (manual)

1. Boot TestServer with existing yml (has `performance.wildstacker.enabled:
   true`, no `stacker:`) → log shows `[stacker] item stacker ENABLED`, and
   `stacker.enabled: true` materializes in yml.
2. Drop 2 stacks of same item within radius → merge + hologram `Item xN`.
3. Set `stacker.blacklist: [DIAMOND]` → dropped diamonds never stack.
4. Set `stacker.hologram: false` → merges continue, no TextDisplay spawned.
