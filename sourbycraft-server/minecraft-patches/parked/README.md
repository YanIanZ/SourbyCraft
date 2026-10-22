# Parked patches

Patches held out of the build by owner decision. Nothing here is read by `applyAllPatches`.
To unpark: `git am --3way` the patch onto `sourbycraft-server/src/minecraft/java`, move the file back
to `features/` with the next free number, restore any test from `test/`, then run
`python3 scripts/patch_surface.py --check-rebuild`.

| Patch | Parked | Why | Gate to unpark |
| --- | --- | --- | --- |
| `0028-SourbyCraft-use-LeafPile-derived-Aurora-EDF-backend.patch` (+ `test/AuroraEdfSchedulerTest.java`) | 2026-10-07 | Owner: new default region scheduler must pass tests first. `AuroraEdfSchedulerTest` 6/6 passed in isolation; `scripts/test_independence_policy.py` still fails on it; boot/ownership acceptance not recorded | policy test green with the recorded set updated, full Gradle suite, boot on Sourby Demo with the user's plugins |
