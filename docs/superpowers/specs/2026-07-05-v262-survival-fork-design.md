# 26.2 — Survival Fork (MC 26.2 rebase, SWM stripped, modloader kept)

**Date:** 2026-07-05
**Status:** Approved (user directives locked via AskUserQuestion)
**Branch:** `release/26.2` (created from `release/26.1.2`)

## User directives (locked)

1. **26.2 = real MC/Paper 26.2 rebase** (not a variant tag). Confirmed feasible:
   mache `26.2+build.1` (stable) exists; Paper base commit
   `0ae1b4239e9559516917f86bfcac404a091c710c` has `mcVersion=26.2`
   (2026-06-28). Current 26.1.2 base: paperRef `76d2ac75…`, mache
   `26.1.2+build.3`.
2. **26.1.2 stays the skyblock/minigames line (SWM-based); 26.2 is the
   survival line.** Two parallel release tracks going forward.
3. **SWM NOT carried into 26.2.** Remove the `dev.iyanz.sourbycraft.swm.*`
   package (53 files: SWPlugin + slime loaders + API) and slime chunk-loading.
   The nested SWM NMS patches (null-guards / moonrise hooks, 0044-0051 family)
   are left in place — inert without slime worlds, low-risk (chosen over a
   full paperweight revert). Worlds are vanilla region storage (survival).
4. **Keep everything else:** security (S1), stacker (S3), antixray (S4),
   entity/item caps (S2), network/memory/perf bindings (S5), DAB/misc (S6),
   MT1 multithread uplift, ML1 SourbyMod loader.

## The host-plugin problem (central design issue)

`SWPlugin` (paper-plugin.yml `main`) is the bundled JavaPlugin that hosts the
entire runtime: it registers all ModuleRegistry modules (EntityStacker,
OreReveal, ConfigBridge, LagLimits, OwnerProtection, ViewThrottle,
SignSanitizer, motd) and calls `ModLoader.enrollInto()` +
`ModuleRegistry.enableAll()`. Removing SWM deletes SWPlugin → nothing hosts
the modules/mods.

**Solution:** new bundled plugin `dev.iyanz.sourbycraft.core.SourbyCorePlugin`
(JavaPlugin), set as paper-plugin.yml `main`. It takes over ONLY the
non-SWM responsibilities of SWPlugin.onEnable/onDisable:
- onEnable: `PerWorldHolder.registerCleanup` → `ModuleRegistry.clear()` →
  first-party `ModuleRegistry.add(...)` (the 7 modules + conditional motd) →
  `ModLoader.enrollInto()` → `ModuleRegistry.enableAll(this)`.
- onDisable: `ModuleRegistry.disableAll()`.
- No slime world loading, no default-world slime bridge, no SWM command.
The MT1/ML1 lifecycle is preserved verbatim; only the SWM host is swapped.

`DedicatedServer.initServer` `ModLoader.bootstrap()` hook stays (nested,
unchanged — mods load regardless of which plugin hosts enable).

## Rebase strategy (paperweight MC 26.1.2 → 26.2)

1. `gradle.properties`: `releaseVersion=26.2`, `mcVersion=26.2`,
   `apiVersion=26.2`, `paperRef=0ae1b4239e…`; `sourbycraft-server/build.gradle.kts`
   mache → `io.papermc:mache:26.2+build.1`.
2. `./gradlew applyAllPatches` (or the fork's setup task) re-applies our ~60
   feature patches onto the new Paper 26.2 source. Expect a subset to FAIL
   (Paper 26.2 moved code). Each failure → resolve in the nested git rebase
   (the paperweight-2 quirk workflow from memory: abort mid-rebase state,
   commit edits on nested main, expect renumber + scaffolding patches).
3. Compile green → `rebuildMinecraftFeaturePatches` to regenerate the patch
   set against 26.2.
4. Known-risk patches: anything touching classes Paper reworked in 26.2 —
   surfaced empirically by the setup run (Phase 1 output is the real scope).

## Survival focus (config defaults)

Same engines, survival-tuned baked defaults where skyblock assumed islands:
- `swm.*` keys removed from the baked `sourbycraft.yml` (dead without SWM).
- Keep the S2 caps but note survival spawner farms — leave
  `entity.max-per-chunk` operator-tunable (documented in release notes as it
  was for r47).
- No functional engine change; survival vs skyblock is world-storage +
  branding, not new mechanics in this pass.

## Rebrand

- Banner / `/ver` / brand strings: 26.2 survival line.
- `codename` bump.
- README section for the two-line split (26.1.2 skyblock, 26.2 survival).

## Non-goals (this pass)

- No new survival gameplay features (that's a later sub-effort if wanted).
- No reintroduction of SWM under any flag.
- No MC 26.3 (main has moved there; we pin 26.2 stable).

## Phases (see plan)

- **P1** — version-coordinate bump + paperweight rebase to 26.2 (the long
  pole; surfaces true conflict scope).
- **P2** — SWM strip + SourbyCorePlugin host + paper-plugin.yml main swap.
- **P3** — survival config/rebrand + baked-yml SWM key removal.
- **P4** — build + artifact + smoke checklist + release r-262-1 + PR to main
  (separate from the 26.1.2 PR #9).

## Verification (manual TestServer, per convention)

1. Boot on MC 26.2 → server starts, `/ver` shows 26.2 survival.
2. `[SourbyCraft] modules: … (N enabled)` via SourbyCorePlugin (no SWPlugin).
3. Vanilla survival world generates + saves to region files (no slime).
4. Security/stacker/antixray/caps/MT1 async-spawn/ML1 mods all active
   (same checklists as r47/r48).
5. No SWM command, no slime_worlds dir usage.
