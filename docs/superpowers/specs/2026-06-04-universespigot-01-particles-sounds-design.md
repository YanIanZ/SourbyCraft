# UniverseSpigot config import — 01 particles + sounds (design)

**Date**: 2026-06-04
**Scope**: Sub-project 01 of the UniverseSpigot config import series. Adds 10 effective server-side gates: 4 remaining `particles.*` keys + 6 `sounds.*` keys. Follows the Foundation patch template verbatim.
**Out-of-scope**: `misc` category (~13 keys), client-only particle keys (`disableBubbleColumnParticles`, `disableSpawnerParticles`), integration smoke tests. See Section 6.
**Status**: Draft for user review.
**Foundation**: `docs/superpowers/specs/2026-06-04-universespigot-foundation-design.md` (sub-project 00). All infrastructure inherited.

---

## Background

Sub-project 00 (Foundation) landed the typed-accessor + gated-patch infrastructure and 2 reference keys (`particles.disableFallParticles`, `particles.disableDeathParticles`). Sub-project 01 fills out the remaining "pure NMS-emit gates" categories — particles and sounds — using that infrastructure unchanged.

The `misc` category was decomposed out of 01: it mixes pure NMS gates (e.g. `unlockAllRecipes`) with FS logic (`log-cleaner.*`), external SDK dependencies (`sentry.*`), and scheduler work (`throttleLoginsPerSecond`). Each of those belongs in its own narrowly-scoped spec.

Two keys (`disableBubbleColumnParticles`, `disableSpawnerParticles`) were dropped during brainstorming because their NMS sites live in `BubbleColumnBlock.animateTick` and `BaseSpawner.clientTick` — both client-side code paths that never execute on a dedicated server. UniverseSpigot's same toggles are no-ops on dedicated. Deferred to a future client-mod sub-spec.

## Constraints (inherited from Foundation)

- **Brand stays SourbyCraft.** Patch `patches/server/0003-Changed-branding.patch` is not touched. `isBrandCompatible(papermc:paper) → true` preserved.
- **Bootstrap-style slim jar.** No new fields, no new module jars, no classloader work.
- **Polish — features must not feel messy.** All 10 gates use one consistent pattern, documented in `docs/superpowers/notes/2026-06-04-us-gated-patch-template.md`.
- **No new NMS shim API.** Each gate is a direct `if (!SourbyCraftConfig.ymlBool(...)) { vanilla }` wrap at the call site.
- **No hot-reload.** Restart required for config changes.
- **Defaults off.** Vanilla Paper preserved for every operator who does not opt in.

## Architecture

Reuses Foundation infrastructure verbatim. No new components.

```
sourbycraft.yml (operator file)
  particles:
    disableFallParticles: false        ← Foundation
    disableDeathParticles: false       ← Foundation
    disableBlockBreakParticles: false  ← NEW
    disableEffectParticles: false      ← NEW
    disableWaterSplashParticles: false ← NEW
    disableNewCombatParticles: false   ← NEW
  sounds:                              ← NEW section
    disableShoulderEntityAmbientSound: false
    disablePiglinAngerSound: false
    disableFootStepSounds: false
    disableNewCombatSounds: false
    disableShieldSounds: false
    disablePistonSounds: false
        ↓
SourbyCraftConfig.ymlBool(path, false)   (Foundation; reused as-is)
        ↓
patches/minecraft/00NN-us-particles-batch1.patch  (4 gates)
patches/minecraft/00NN-us-sounds-batch1.patch     (6 gates)
        ↓
Runtime: defaults off → vanilla Paper preserved → plugin compat preserved
```

## Components

### C1. yml schema extension

**File**: `sourbycraft-server/src/main/resources/sourbycraft.yml`.

Append four keys to the existing `particles:` section (added by Foundation), then add a new top-level `sounds:` section with six keys.

```yaml
particles:
  # ... existing Foundation keys ...
  # Disable block-break particles when a block is destroyed (UniverseSpigot: particles.disableBlockBreakParticles).
  disableBlockBreakParticles: false
  # Disable potion / status-effect ambient particles (UniverseSpigot: particles.disableEffectParticles).
  disableEffectParticles: false
  # Disable water-splash particles when an entity enters water (UniverseSpigot: particles.disableWaterSplashParticles).
  disableWaterSplashParticles: false
  # Disable sweep-attack and other 1.21+ combat particles (UniverseSpigot: particles.disableNewCombatParticles).
  disableNewCombatParticles: false

# UniverseSpigot config import — sounds category (sub-project 01).
# All toggles default to false → vanilla Paper behavior preserved.
sounds:
  # Disable parrot shoulder-perched ambient mimicry (UniverseSpigot: sounds.disableShoulderEntityAmbientSound).
  disableShoulderEntityAmbientSound: false
  # Suppress only the piglin anger ambient variant (other piglin ambient sounds unaffected) (UniverseSpigot: sounds.disablePiglinAngerSound).
  disablePiglinAngerSound: false
  # Disable entity footstep sounds across all step variants (UniverseSpigot: sounds.disableFootStepSounds).
  disableFootStepSounds: false
  # Disable PLAYER_ATTACK_* sound family from 1.9+ combat (UniverseSpigot: sounds.disableNewCombatSounds).
  disableNewCombatSounds: false
  # Disable shield-block impact sound (UniverseSpigot: sounds.disableShieldSounds).
  disableShieldSounds: false
  # Disable piston extend / retract sounds (UniverseSpigot: sounds.disablePistonSounds).
  disablePistonSounds: false
```

### C2. Particles batch-1 patch (4 keys)

**File**: `patches/minecraft/00NN-us-particles-batch1.patch` (next free Minecraft patch number; Foundation reserved 0042 so this will likely be 0043).

| # | Key | Target file | Insertion |
|---|---|---|---|
| 1 | `particles.disableBlockBreakParticles` | `net/minecraft/world/level/Level.java` | `destroyBlock` method, around line 1218. Wrap `this.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(effectType))`. Inline `ymlBool` — single call per block destroy event. |
| 2 | `particles.disableEffectParticles` | `net/minecraft/world/entity/LivingEntity.java` | line 1024, the line immediately before `this.entityData.set(DATA_EFFECT_PARTICLES, list)`. Replace the local `list` with `List.of()` when the gate is on, then let the existing `entityData.set(...)` call run unmodified. Client renders nothing because the synced list is empty. |
| 3 | `particles.disableWaterSplashParticles` | `net/minecraft/world/entity/Entity.java` | line 2108. Wrap `this.level().addParticle(ParticleTypes.SPLASH, ...)`. Called per-entity per-water-entry — inline `ymlBool` OK (event-driven, not per-tick). |
| 4 | `particles.disableNewCombatParticles` | `net/minecraft/world/entity/player/Player.java` | line 1267 (inside `attack`). Wrap `serverLevel.sendParticles(ParticleTypes.SWEEP_ATTACK, ...)`. Cache bool at `attack` method entry — same method is hit by `disableNewCombatSounds` (C3 #4), so one cached bool per concern. |

Every inserted line carries trailing `// SourbyCraft - US import`. Vanilla `else` branches are byte-identical to pre-patch code (Foundation template invariant).

### C3. Sounds batch-1 patch (6 keys)

**File**: `patches/minecraft/00NN-us-sounds-batch1.patch` (next free after the particles patch; likely 0044).

| # | Key | Target file | Insertion |
|---|---|---|---|
| 1 | `sounds.disableShoulderEntityAmbientSound` | `net/minecraft/world/entity/animal/parrot/Parrot.java` | `imitateNearbyMobs` line 241. Wrap `level.playSound(null, parrot.getX(), parrot.getY(), parrot.getZ(), imitatedSound, parrot.getSoundSource(), 0.7F, getPitch(level.random))`. Static method; bool inline OK. |
| 2 | `sounds.disablePiglinAngerSound` | `net/minecraft/world/entity/monster/piglin/Piglin.java` | `getAmbientSound` line 462. When gate on AND the resolved sound is `SoundEvents.PIGLIN_ANGRY`, return `null` instead. All other ambient activity sounds (admiring, idle) continue to play. |
| 3 | `sounds.disableFootStepSounds` | `net/minecraft/world/entity/Entity.java` | Three sites in three distinct methods, all gated by the same key: (a) `playStepSound` at line 1851 — early-return when gate on; (b) line 1842 — swim step sound (slow); (c) line 1848 — swim step sound (fast). Cache bool once at the top of each of the three methods. UniverseSpigot considers all step sounds part of the same "footsteps" concept; matching that semantics here. |
| 4 | `sounds.disableNewCombatSounds` | `net/minecraft/world/entity/player/Player.java` | 6 emit sites inside `attack` (lines 1044, 1079, 1105, 1137, 1142, 1244). Cache bool at `attack` method entry once, reuse for all six. All sites emit `PLAYER_ATTACK_*` SoundEvents via `playServerSideSound` or `makeSound`. |
| 5 | `sounds.disableShieldSounds` | `net/minecraft/world/item/component/BlocksAttacks.java` | `onBlocked` line 73. Wrap the entire `this.blockSound.ifPresent(sound -> level.playSound(...))` block. Inline `ymlBool` OK — called on shield-block events, not per-tick. |
| 6 | `sounds.disablePistonSounds` | `net/minecraft/world/level/block/piston/PistonBaseBlock.java` | lines 180 and 249. Wrap `level.playSound(null, pos, SoundEvents.PISTON_EXTEND, ...)` and `level.playSound(null, pos, SoundEvents.PISTON_CONTRACT, ...)`. Two separate `ymlBool` calls — they are in distinct branches so a shared local cache buys nothing. |

Bool-caching rule (from template doc): caches required at `Entity.playStepSound` and `Player.attack`. Other sites use inline calls.

### C4. Accessor unit tests

**File**: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java` (extend).

Two new methods:

```java
@Test
void ymlBool_readsNewParticlesBatch1Keys() {
    assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableBlockBreakParticles", true));
    assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableEffectParticles", true));
    assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableWaterSplashParticles", true));
    assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableNewCombatParticles", true));
}

@Test
void ymlBool_readsNewSoundsBatch1Keys() {
    assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disableShoulderEntityAmbientSound", true));
    assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disablePiglinAngerSound", true));
    assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disableFootStepSounds", true));
    assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disableNewCombatSounds", true));
    assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disableShieldSounds", true));
    assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disablePistonSounds", true));
}
```

Test count grows from Foundation's 17 to 19.

### C5. CI workflow

No change. Existing `.github/workflows/nms-compat.yml` already gates on `patches/**`, `sourbycraft-server/src/main/resources/sourbycraft.yml`, and `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/**` (Foundation Task 12).

## Data flow

Identical to Foundation. Pattern A is event-driven, pattern B is hot-loop with cache:

```java
// Pattern A — event-driven (block destroy, water-splash entry, shield block):
if (!SourbyCraftConfig.ymlBool("particles.disableXxx", false)) { // SourbyCraft - US import
    /* vanilla emit, byte-identical */
}

// Pattern B — hot loop or multi-site method (Entity.playStepSound, Player.attack):
public void method(...) {
    final boolean toggleOff = SourbyCraftConfig.ymlBool("category.disableXxx", false); // SourbyCraft - US import
    ...
    if (!toggleOff) /* vanilla emit */; // SourbyCraft - US import
}
```

**Special-case wrappers:**

- `disableEffectParticles` (C2 #2): the `else` branch is byte-identical to vanilla. The `if` branch replaces the local `list` variable with `List.of()` *before* the existing `entityData.set(DATA_EFFECT_PARTICLES, list)` line. The `set(...)` call itself remains unmodified — only its input differs. This keeps the patch minimal and preserves vanilla synced-data semantics (the field is always set; just with empty content when the gate fires).

- `disablePiglinAngerSound` (C3 #2): not a wrap; a filter. Vanilla:

  ```java
  return this.level().isClientSide() ? null : PiglinAi.getSoundForCurrentActivity(this).orElse(null);
  ```

  Gated:

  ```java
  if (this.level().isClientSide()) return null;
  SoundEvent s = PiglinAi.getSoundForCurrentActivity(this).orElse(null); // SourbyCraft - US import
  if (s == SoundEvents.PIGLIN_ANGRY && SourbyCraftConfig.ymlBool("sounds.disablePiglinAngerSound", false)) return null; // SourbyCraft - US import
  return s;
  ```

  Other ambient sounds (admiring, idle) pass through.

## Error handling

Same as Foundation — accessor-level handling covers all cases. No new exception types, no new logger channels, no new boot-time work.

| Case | Behavior |
|---|---|
| Operator deletes a key from yml | `ymlBool(path, false)` returns `false` silently → vanilla. |
| Operator writes wrong type (`disableXxx: "yes"`) | `ymlBool` returns default, `warnOnce` logs WARN once via `SourbyLogger`. |
| Operator deletes whole `sounds:` block | Each `ymlBool` returns default → full vanilla. |
| Yaml malformed | SnakeYAML throws at boot, existing handler falls back to bundled defaults (`particles:` + `sounds:` from jar resource). |
| Patch fails to apply | `applyAllPatches` errors at build time. CI gates on this (existing `nms-compat.yml`). |

## Testing

Unit tests only.

| Layer | Coverage |
|---|---|
| Unit (C4) | Two new test methods verify all 10 new keys default to `false` in the bundled baseline yml. |
| CI gate | Existing workflow re-runs on any `patches/**`, `sourbycraft.yml`, or accessor-test change. |
| Boot smoke | None (operator manually verifies). |
| Patch lint | `grep -c 'SourbyCraft - US import' patches/minecraft/00NN-us-particles-batch1.patch >= 4` and `>= 6` for sounds. |
| Regression | Existing `SourbyCraftConfigAccessorsTest` (17) plus 2 new = 19 must stay green. Existing `nmsCompatTest` 4-plugin baseline unchanged. |

Boot-integration testing was explicitly out of scope (user choice during brainstorm). Trade-off: faster CI, but patch wiring bugs surface at operator time, not at PR time.

## Out of scope

1. **`misc` category (~13 keys)** — `sendParticleFeedbackToNonPlayers`, `unlockAllRecipes`, `throttleLoginsPerSecond`, `disableJoinQuitMessage`, `log-cleaner.{enabled, older-than, max-count}`, `sentry.{dsn, tags}`. Future sub-spec 01b. Some of those keys need FS, scheduler, or external-SDK work that doesn't belong here.
2. **Client-only particle keys** — `disableBubbleColumnParticles` (`BubbleColumnBlock.animateTick`) and `disableSpawnerParticles` (`BaseSpawner.clientTick`). Both fire on client only; no-op on dedicated. Future client-mod sub-spec.
3. **Boot-integration smoke tests** — no `particleSmokeTest`-style task added for batch 1. Foundation's `particleSmokeTest` already exists for the 2 reference keys.
4. **Per-world overrides** — same as Foundation. All 10 keys global.
5. **Hot-reload** — same as Foundation. Restart required.
6. **New accessor types** — Foundation's `ymlBool` covers everything in this batch (all 10 are pure booleans).
7. **Brand identity changes** — patch `0003-Changed-branding.patch` untouched. `SourbyCraft` brand preserved.
8. **Sound capture infrastructure** — no JUnit-side or netty-side sound-packet assertion. Sound suppression is manually verified by operator.
9. **Performance-sensitive sites in sounds** — `Entity.playStepSound` is the only per-tick site; bool-cache rule applied. Other sites are event-driven.

## Acceptance criteria

After sub-project 01 lands, all of the following hold:

| Check | Command | Expected |
|---|---|---|
| 4 new particle keys in yml | `grep -cE 'disable(BlockBreak\|Effect\|WaterSplash\|NewCombat)Particles' sourbycraft-server/src/main/resources/sourbycraft.yml` | `4` |
| 6 new sound keys in yml | `grep -cE 'disable(ShoulderEntityAmbient\|PiglinAnger\|FootStep\|NewCombat\|Shield\|Piston)Sound' sourbycraft-server/src/main/resources/sourbycraft.yml` | `6` |
| `sounds:` top-level section present | `grep -c '^sounds:' sourbycraft-server/src/main/resources/sourbycraft.yml` | `1` |
| Particles batch-1 patch exists | `ls patches/minecraft/*-us-particles-batch1.patch` | exists |
| Sounds batch-1 patch exists | `ls patches/minecraft/*-us-sounds-batch1.patch` | exists |
| Both patches under 200 lines | `wc -l patches/minecraft/*us-particles-batch1.patch patches/minecraft/*us-sounds-batch1.patch` | each `< 200` |
| US import markers in particles patch | `grep -c 'SourbyCraft - US import' patches/minecraft/*us-particles-batch1.patch` | `>= 4` |
| US import markers in sounds patch | `grep -c 'SourbyCraft - US import' patches/minecraft/*us-sounds-batch1.patch` | `>= 6` |
| Accessor tests pass | `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest` | green, 19 tests |
| Patches apply cleanly | `./gradlew applyAllPatches` | exit 0 |
| Brand patch untouched | `git diff <pre-01-base>..HEAD -- patches/server/0003-Changed-branding.patch` | empty |
| Foundation patch untouched | `git diff <pre-01-base>..HEAD -- patches/minecraft/0042-us-particles-fall-death.patch` | empty |
| `isBrandCompatible(paper)` preserved | `grep 'BRAND_PAPER_ID' patches/server/0003-Changed-branding.patch` | unchanged |

## Phases (handed to writing-plans)

Suggested phase breakdown for the implementation plan:

1. **Phase 1 — yml schema extension**: append 4 particles keys + new `sounds:` section to `sourbycraft.yml`. One commit. Verify with new accessor tests in same commit.
2. **Phase 2 — Particles batch-1 patch (4 gates)**: locate each NMS site (already documented in this spec), edit upstream cache, format-patch, place at next-free Minecraft patch number. One commit.
3. **Phase 3 — Sounds batch-1 patch (6 gates)**: same workflow as Phase 2 for the 6 sound sites. One commit.
4. **Phase 4 — Verification**: run accessor tests, `applyAllPatches`, grep marker counts, grep `wc -l`. Document any deviations in the commit body.

`writing-plans` owns the detailed step decomposition.

## Out-of-scope reminders (next sub-spec candidates)

After 01 lands, the natural next sub-projects in dependency order:

- **01b — misc category** (subset of original misc category): pure NMS gates only — `sendParticleFeedbackToNonPlayers`, `unlockAllRecipes`, `disableJoinQuitMessage`. Defer `log-cleaner.*`, `sentry.*`, `throttleLoginsPerSecond` to their own narrower specs.
- **02 — limiters category** (~6 keys): `item.max-merge-attempts-per-tick`, `projectile.maxProjectileLoadsPer*`, `remove-excess.*`, `non-tickable-entities`. Foundation's `ymlEntityTypeMap` accessor exists for the entity list.
- **03 — behavior toggles** (~60 keys): largest category; requires its own internal decomposition.
