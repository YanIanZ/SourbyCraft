# S5 network/memory/async Binding — Implementation Plan

Spec: docs/superpowers/specs/2026-07-05-s5-network-memory-async-design.md
Repo: /Users/rheninxy/Sourby/SourbyCraft, branch release/26.1.2.
Task 1 = outer. Task 2 = nested (1 file). Task 3 = driver.

The implementer has HIGHER latitude than S1-S4 plans: locate the exact
loaders/sites by following the named patterns, keep the semantics below
exact, and report every site chosen.

## Task 1 (outer)

- [ ] **GcAdvisor gate** — find how `PerfSensor.loadFromYml`/`Knobs.loadFromYml`
  read the baked `resources/sourbycraft.yml`; add the same for
  `branding.gc-advisor.enabled` (default true) into a static
  `GcAdvisor.ENABLED` flag; early-return in `GcAdvisor.run()` when disabled.
- [ ] **Compression bridge** — in `SourbyCraftConfig` after
  `compressionLevel = getInt("network.compression-level", ...)`: clamp 0..9;
  if value != 4 (compiled default) set
  `io.papermc.paper.configuration.GlobalConfiguration.get().misc.compressionLevel`
  to it (the field is Paper's `IntOr.Default` type — construct accordingly;
  verify the exact type in GlobalConfiguration.java). Guard with try/catch +
  WARN (GlobalConfiguration may not be initialized yet at first config load —
  if so, defer the bridge to SWPlugin.onEnable and note it).
- [ ] **ViewThrottle** — new `dev.iyanz.sourbycraft.perf.ViewThrottle`:
  - `register(Plugin)`: if `!SourbyCraftConfig.autoThrottleView` → log
    "disabled" + return. Else runTaskTimer every 100 ticks.
  - Tick: read the live PerfSensor tier (find its public API — a
    tier/level/state getter; SelfTuneController uses it). Degraded/worst tier
    → for each world, `setViewDistance(max(minViewDistance, current - 1))`.
    Healthy tier → step +1 toward the world's original view distance
    (captured on first touch in a per-world map).
  - Clamp minViewDistance 2..32 at read.
  - One INFO per actual change ("view distance world=X 8→7 (tier=RED)").
- [ ] **max.bg.threads** — in `SourbyCraftConfig` where
  `maxPlatformThreads` is read: if key != 4 (default) and
  `System.getProperty("max.bg.threads") == null` → set it. (Verify the exact
  property name Util/Paper reads — grep `bg.threads` / `getMaxThreads` in
  nested Util.java — and use that name; report it.)
- [ ] **motd-suffix** — listener on
  `com.destroystokyo.paper.event.server.PaperServerListPingEvent` in a new
  small class or inside SWPlugin: when the baked key
  `branding.motd-suffix` is true (load via the same baked-yml reader as
  gc-advisor), append `Component.text(" | SourbyCraft")` (gray) to the motd.
- [ ] **Alias seeds** — `auto-install.enabled` (baked) seeds `swm.auto-install`
  ONLY when operator key absent (S3 pattern); `branding.compact-plugin-list`
  seeds `branding.compact-plugin-log`'s flag when the log key is absent from
  the baked file (adapt to however compact-plugin-log is loaded; if both are
  baked-only, make list an OR-input to the same flag and document).
- [ ] **SupersededKeys** — new class with a single static `report()` called
  at the end of `SourbyCraftConfig.init`: builds ONE INFO line naming the
  superseded keys (spec list) + their Paper equivalents; for each such key
  whose loaded value differs from its compiled default, a WARN
  "<key> is superseded in 26.1.2; use <paper equivalent>". No per-tick cost.
- [ ] **Registrations** in SWPlugin.onEnable: ViewThrottle.register(this) +
  motd listener.
- [ ] Compile → BUILD SUCCESSFUL. Commit:
  `perf: S5 bindings — gc-advisor gate, compression bridge, ViewThrottle engine, bg-threads property, motd suffix, alias seeds, superseded-keys report`

## Task 2 (nested, 1 file)

- [ ] Find where the per-level chained-neighbor-update budget flows into
  `CollectingNeighborUpdater` (ServerLevel/Level ctor arg, ultimately from
  server.properties `max-chained-neighbor-updates`). Clamp it:

```java
// SourbyCraft S5 - entity.max-redstone-updates-per-tick caps the chained neighbor-update budget
<budget expr> = dev.iyanz.sourbycraft.SourbyCraftConfig.maxRedstoneUpdatesPerTick > 0
    ? Math.min(<budget expr>, dev.iyanz.sourbycraft.SourbyCraftConfig.maxRedstoneUpdatesPerTick)
    : <budget expr>;
```

  Also ensure `maxRedstoneUpdatesPerTick` is actually LOADED in
  SourbyCraftConfig (survey says the field exists but is never read from
  yml — add `maxRedstoneUpdatesPerTick = getInt("entity.max-redstone-updates-per-tick", maxRedstoneUpdatesPerTick);`
  next to the other entity reads — that sub-edit is OUTER, do it in Task 1).
- [ ] Compile → BUILD SUCCESSFUL. Nested commit:
  `SourbyCraft S5: redstone chained-neighbor-update budget from entity.max-redstone-updates-per-tick`

## Task 3 (driver)

- rebuildMinecraftFeaturePatches → patch commit
  `perf: S5 redstone budget clamp (feature patch)` → combined review.
