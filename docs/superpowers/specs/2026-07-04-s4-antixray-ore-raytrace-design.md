# S4 — Antixray Ore Raytrace + Per-World Anti-Xray Config Wiring

**Date:** 2026-07-04
**Status:** Approved (continuous execution authorized)
**Parent effort:** Placeholder-config remediation (S4 of S1–S6). Root cause of
"antixray masih bocor": Paper engine-mode 1 only hides ores fully enclosed by
solid blocks — cave-exposed ores leak through walls. The fork's designed
answer (`antixray.raytrace.enabled` → `RayTraceWorker`) was never wired:
`RayTraceWorker.submit()` has zero call sites, `VisibilityCache` unused,
`SourbyCraftWorldConfig` never instantiated, `antixray.fluid-obscures` never
read.

## Architecture: complementary reveal layer (no palette surgery)

Paper's engine keeps handling buried ores. SourbyCraft adds a second layer for
the gap — **exposed** ores:

1. **Hide on send** — NMS hook (1 line) in `PlayerChunkSender.sendChunk`
   (main thread, right after the chunk packet is sent): scan the chunk for
   exposed ore positions, send the player per-block fake updates
   (stone/deepslate/netherrack/end-stone by Y+dimension), record positions in
   a per-player pending set. Client applies chunk + updates in the same tick
   batch → renders hidden.
2. **Raytrace loop** — Bukkit scheduler every `antixray.raytrace.interval-ticks`
   (def 10): per player, walk pending positions within
   `antixray.raytrace.distance` (def 48); ≤ 8 blocks → reveal immediately
   (mining UX, mirrors EntityVisibilityCheck NEAR bypass); otherwise submit to
   the existing `RayTraceWorker` (async `level.clip` on VirtualExecutor →
   `VisibilityCache`).
3. **Reveal** — same scheduler pass: pending positions confirmed in
   `VisibilityCache` get one real-state `ClientboundBlockUpdatePacket` and
   leave the pending set. Once revealed, stays revealed until the chunk is
   resent (no re-hide in v1 — avoids flicker; documented limitation).

Palette-level packet rewriting rejected: obfuscator hot path, high complexity.
Known v1 limitation: the raw chunk packet still contains exposed ores for one
client-side tick before the fake updates land — a packet-logging client can
record them. Netting: massively better than今 status (everything leaks), zero
overhead when disabled, upgrade path to palette surgery stays open.

## Config wiring (all previously dead)

| Key | Wiring |
|---|---|
| `antixray.raytrace.enabled` | master toggle for hide/raytrace/reveal pipeline (already flips `RayTraceWorker.ENABLED`) |
| `antixray.fluid-obscures` (global) AND per-world `anticheat.anti-xray.fluid-obscures` | effective = global && per-world. In the exposure scan a fluid neighbor does not count as exposing; in the raytrace the ray collides with fluids (`ClipContext.Fluid.ANY`) |
| per-world `anticheat.anti-xray.all-blocks` | candidate set = ore tags ∪ Paper `hidden-blocks` list for that world (operator-extendable coverage) |
| per-world `anticheat.anti-xray.entity-obfuscation` | per-world gate inside `EntityVisibilityCheck.isVisibleSync` |
| per-world `anticheat.anti-xray.entity-obfuscation-range` | beyond range → skip raytrace (entity shown; tracker range governs). Bounds the per-tracker clip cost |

New keys (raytrace tuning, `antixray.raytrace.*`): `interval-ticks` 10,
`distance` 48 (clamp 8..128), `max-checks-per-cycle` 192 (clamp 16..2048),
`max-pending-per-player` 8192 (clamp 512..65536; budget full → ore stays
visible, fail-open for gameplay).

Ore candidate tags: COAL/IRON/COPPER/GOLD/REDSTONE/EMERALD/LAPIS/DIAMOND_ORES
+ NETHER_QUARTZ_ORE + ANCIENT_DEBRIS. No Y cap in our layer (covers copper
y112 / mountain emerald that Paper `max-block-height: 64` misses).

`SourbyCraftWorldConfig` finally instantiated: lazy static holder
`SourbyCraftWorldConfig.get(ServerLevel)` keyed by world name (main-thread
call sites only).

## Consistency / cleanup

- Player quit → drop pending set + `VisibilityCache.clear`.
- World change → same (BlockPos long keys are world-agnostic; must not leak
  across dimensions).
- Section scan gated by `PalettedContainer.maybeHas(candidate)` — sections
  without ore palettes cost one palette walk, no block iteration.
- Unloaded neighbor chunk counts as obscuring (safe: hide + raytrace decides).

## Files touched

- `SourbyCraftConfig.java` — 4 new raytrace tuning fields + loads + clamps
- `SourbyCraftWorldConfig.java` — static lazy holder
- `antixray/OreReveal.java` — NEW (scan/hide/schedule/reveal + listeners)
- `antixray/OcclusionUtil.java` — fluid-aware overload
- `antixray/RayTraceWorker.java` — pass fluid flag
- `antixray/EntityVisibilityCheck.java` — per-world gate + range
- `swm/plugin/SWPlugin.java` — `OreReveal.register(this)`
- nested `net/minecraft/server/network/PlayerChunkSender.java` — 1-line hook
  (feature patch)

## Verification (manual TestServer)

1. `antixray.raytrace.enabled: true` + Paper anti-xray on → boot, join, cave
   wall at 20 blocks: xray-texture-pack shows no exposed ores behind walls;
   walking around the corner reveals them within ~0.5 s.
2. Mine toward a vein → ore face appears as soon as the covering block breaks
   (≤ 8-block instant reveal).
3. `entity-obfuscation: false` in world-settings → mobs behind walls visible
   again (gate works); range 16 → distant mobs always visible.
4. Toggle `antixray.raytrace.enabled: false` → zero scan cost, vanilla+Paper
   behavior only.
