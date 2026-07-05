# S6 — Misc Keys (dab, pvp fossil, verbose, swm.loader)

**Date:** 2026-07-05
**Status:** Approved (continuous execution authorized)
**Parent effort:** Placeholder-config remediation (S6 of S1–S6, final).

## Wirings

1. **`dab.entity-overrides`** (loaded map `"minecraft:zombie" -> [maxTickFreq,
   activationDistMod]`, zero consumers, no DAB engine survives) → wire as
   DAB-lite into `ActivationRange` (nested; same file S2 already patched):
   - `activationDistMod` SUBTRACTS blocks from the entity type's activation
     range (floor 4).
   - `maxTickFreq` = minimum ticks between inactive wakeups for that type
     (overrides the global inactive cadence; floor 20, i.e. can only make
     entities lazier, never more active — lag-reduction semantics).
   - Lookup: precomputed static `Map<EntityType<?>, int[]>` built once from
     the config map at first use (resolve via
     `EntityType.byString(key)`); unresolvable keys WARN once at build.
   - Empty overrides map → zero cost (null/empty fast path).
2. **`pvp.*` operator-file fossils** — the PvP variant was removed in the
   26.1.2 migration and the fields no longer exist in `SourbyCraftConfig`;
   operator ymls (e.g. TestServer) still carry the section. At config init,
   when `config.contains("pvp")` → one INFO: keys are from the removed PvP
   variant; combat tuning now lives in `combat.profile`
   (vanilla|balanced|pvp). Keys left in place (never deleted).
3. **`verbose`** — became live via S4 (SourbyCraftWorldConfig.init →
   `SourbyCraftConfig.log`). Verify `log()` actually gates on `verbose`; if
   not, add the gate (that was always its intent).
4. **`swm.loader`** (orphan field `swmLoader`, never even loaded) — load
   `swm.loader` (default "file"); value other than "file" → WARN that only
   the file loader ships in this build (key reserved for future loaders).

## Files

- Nested: `io/papermc/paper/entity/activation/ActivationRange.java` → 1
  feature patch (extends 0056 region; new commit).
- Outer: `SourbyCraftConfig.java` (pvp notice, swm.loader, verbose gate
  check), possibly `perf/` helper for the DAB map holder.

## After S6: closing the whole effort

Final whole-branch review (opus) over S2–S6, `assembleReleaseArtifacts`,
ledger close, memory update, operator boot checklist.

## Verification (manual)

1. `dab.entity-overrides: {"minecraft:zombie": [40, 16]}` → distant zombies
   tick lazily (1-in-40 when inactive) and activate 16 blocks closer.
2. Boot with TestServer yml (has pvp section) → single INFO fossil notice.
3. `verbose: true` → world-settings log lines appear; false → silent.
4. `swm.loader: mysql` → WARN "only file loader ships".
