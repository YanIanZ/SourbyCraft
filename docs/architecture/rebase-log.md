# Upstream rebase log

AURORA-TASKS H: "track rebase conflict count". One row per `canvasRef` bump. The number that
matters for independence is how many SourbyCraft patch files needed a human, not how many
upstream commits moved.

| Date | Commit | `canvasRef` | Upstream delta | Patch files needing intervention | Detail |
| --- | --- | --- | --- | --- | --- |
| 2026-07-20 | `f06a45a1` | Folia → Canvas `df0f2ebb` | Re-platform, not a rebase | n/a | Upstream switched from Folia to Canvas; 146 vendored feature patches were removed rather than ported. Not comparable with later rows |
| 2026-08-30 | `4be7187b` | `df0f2ebb` → `6a600b89` | 86 commits, +4315 −7806 | **7** | 2 build-script hunks dropped (`sourbycraft-server/build.gradle.kts.patch`, `sourbyapi/build.gradle.kts.patch`); 2 config patches rebuilt (`GlobalConfiguration`, `WorldConfig`); 2 Spark patches deleted as obsolete (later re-added for Sourby metrics/config); 1 feature patch (`0003`, waypoint manager) parked in `.skipped/` because upstream moved the file into its region-threading patch |
| 2026-10-05 | `37325d5` | `6a600b89` → `2a3bf65c` | 6 commits (3 Paper upstream updates, Gradle 9.8.0) | **0** | All patches applied unchanged. Added one new source patch (`ShapelessRecipe`) because Paper's new PredicateChoice test exposed Canvas's Pufferfish greedy matcher; that was a latent upstream interaction, not a rebase conflict |

## Recording a bump

In the same commit that moves `canvasRef`:

1. Run `./gradlew applyAllPatches` before any fix and list every patch file that fails or needs a
   rebuild (`rebuild*Patches`), including files deleted or parked in `.skipped/`.
2. Add a row: date, commit, old → new ref, upstream commit count (`git rev-list --count old..new`
   in the Canvas checkout), the number of patch files from step 1, and one clause per file saying
   what happened.
3. A parked patch stays listed in the row until it is ported or deliberately dropped; say which.

Build 47 added four Paper patch files (the bridge load gates and `CraftScheduler`) and extended the
Spark patch. They have not yet been through a bump; the next row will show what they cost.
