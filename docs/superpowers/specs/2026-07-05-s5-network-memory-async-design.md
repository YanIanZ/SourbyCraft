# S5 — network/memory/async/multithreading Binding

**Date:** 2026-07-05
**Status:** Approved (continuous execution authorized)
**Parent effort:** Placeholder-config remediation (S5 of S1–S6).

## Reality from the survey

The v9 subsystems these keys once drove (custom MT dispatcher, async
lighting/pathfinding, memory pools, netty tuning, proxy handling) were
replaced wholesale by moonrise + Paper in the 26.1.2 migration. Re-building
them would duplicate moonrise. Policy split:

- **Wire for real** where a live engine exists or a lightweight new one is
  justified (below).
- **Superseded keys** get one honest boot INFO line naming them and the Paper
  equivalent; keys stay loaded (never deleted), and setting them non-default
  triggers a WARN pointing at the Paper knob. This is documentation-as-logic:
  the key now *does something observable* (informs), no silent placebo.

## Real wirings

1. **`branding.gc-advisor.enabled`** — `GcAdvisor` currently runs
   unconditionally; gate it with the key (same baked-yml loader pattern as
   `PerfSensor.loadFromYml`).
2. **`network.compression-level`** — bridge into Paper's live engine:
   `GlobalConfiguration.get().misc.compressionLevel` (consumed at
   `Connection.java:816` per login) set at plugin enable when the operator
   changed our key from default 4. Clamp 0..9.
3. **`network.auto-throttle-view` + `network.min-view-distance`** — NEW
   lightweight `ViewThrottle` engine (perf-engine family): every 100 ticks
   read the live PerfSensor tier; on degraded tier step each world's view
   distance down one (floor `min-view-distance`), on healthy tier step back
   up to the world's original value. Uses Paper `World#setViewDistance`.
   Disabled → zero cost (no scheduler work beyond a boolean).
4. **`performance.max-platform-threads`** — set the `max.bg.threads` system
   property (Util background pool sizing) at earliest config load when the
   property is not already set and the key differs from default.
5. **`branding.motd-suffix`** — PaperServerListPingEvent listener appends the
   SourbyCraft suffix to the MOTD when enabled.
6. **`auto-install.enabled`** (baked) — alias-seed of `swm.auto-install`
   (same seeding pattern as S3 wildstacker aliases).
   **`branding.compact-plugin-list`** — alias-seed of the live
   `branding.compact-plugin-log`.
7. **`entity.max-redstone-updates-per-tick`** — bridge to the engine vanilla
   already ships: the per-level chained-neighbor-update budget
   (`CollectingNeighborUpdater` limit). Nested one-liner clamps the level's
   budget to our key (when key > 0 and lower than the vanilla/properties
   value). Real redstone-lag protection.

## Superseded (boot INFO + non-default WARN)

`multithreading.enabled`, `performance.async-chunk-load`,
`performance.async-pathfinding`, `performance.structured-concurrency`,
`performance.v9.*` (whole block), `memory.*` (4 keys),
`chunk.async-save-batch` → moonrise/Paper own these domains now.
Baked keys `network.proxy-mode`, `netty.*`, `proxy-kick-*`,
`entity-tracker.*`, `perf.entity-tick-rate` → Paper/spigot equivalents named
in the INFO line.

## Files

- Outer: `perf/ViewThrottle.java` (new), `brand/GcAdvisor.java` (gate),
  `perf/SupersededKeys.java` (new: INFO/WARN emitter), `SourbyCraftConfig.java`
  (aliases, compression bridge, max.bg.threads, superseded call),
  `swm/plugin/SWPlugin.java` (ViewThrottle + motd listener registration).
- Nested (1 file): `ServerLevel` (or wherever the neighbor-update budget is
  constructed) redstone budget clamp → 1 feature patch.

## Verification (manual)

1. Boot log: gc-advisor gated line, superseded INFO line, bridge INFO when
   compression-level changed.
2. `auto-throttle-view: true` + artificial load (lag machine) → view distance
   steps down, recovers when idle.
3. `max-redstone-updates-per-tick: 500` → giant redstone clock stops
   propagating past budget instead of freezing the tick.
4. `motd-suffix: true` → server list shows suffix.
