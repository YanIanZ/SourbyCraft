# UniverseSpigot 01 — particles + sounds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land 10 server-effective UniverseSpigot config gates (4 remaining `particles.*` + 6 `sounds.*`) following the Foundation patch template verbatim, with all defaults off so vanilla Paper and plugin compat are preserved.

**Architecture:** Append yml schema → 2 paperweight feature patches (`patches/minecraft/`) using the `git format-patch` workflow against the paperweight upstream cache → 2 accessor unit tests asserting baseline reads. No new infrastructure; everything reuses Foundation.

**Tech Stack:** Java 21+, Paper 1.21.11 (paperweight 2.0, mache), Gradle 8+, JUnit Jupiter 5.12.2, SnakeYAML.

**Spec:** `docs/superpowers/specs/2026-06-04-universespigot-01-particles-sounds-design.md`
**Foundation spec:** `docs/superpowers/specs/2026-06-04-universespigot-foundation-design.md`
**Patch template:** `docs/superpowers/notes/2026-06-04-us-gated-patch-template.md`
**Reference patch:** `patches/minecraft/0042-us-particles-fall-death.patch`

**Branch:** `feat/pvp-server` (commits land on the current branch; no new branch needed).

**Branding constraint:** `patches/server/0003-Changed-branding.patch` MUST NOT be modified. Brand stays SourbyCraft; `isBrandCompatible(papermc:paper) → true` preserved.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `sourbycraft-server/src/main/resources/sourbycraft.yml` | Modify | Append 4 keys to existing `particles:` section + add new `sounds:` top-level section with 6 keys. |
| `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java` | Modify | Append two test methods (one per category) verifying each new key defaults to `false`. |
| `patches/minecraft/0043-us-particles-batch1.patch` | Create | Paperweight feature patch gating the 4 new particle emit sites. Generated via `git format-patch` on the upstream cache. |
| `patches/minecraft/0044-us-sounds-batch1.patch` | Create | Paperweight feature patch gating the 6 sound emit sites across 5 NMS files. Generated via `git format-patch`. |

Conventions discovered in the repo (lifted from Foundation execution):

- Next-free Minecraft patch numbers: 0042 (Foundation reserved) → 0043 (particles batch-1) → 0044 (sounds batch-1).
- The paperweight upstream cache for NMS sources lives at `.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java/`. It has its own `.git`. The existing `patches/minecraft/*.patch` are NOT visibly applied to that cache's HEAD (a pre-existing state — same as Foundation Task 10), so the workflow is: edit live files in the cache → `git commit` there → `git format-patch -1 HEAD -o /tmp/...` → copy patch to `patches/minecraft/` → `git -C <cache> reset --hard HEAD~1` to leave the cache clean.
- `docs/superpowers/` is in `.gitignore` (line 418); test files and patches are not. Use `git add -f` only for files under `docs/superpowers/` (this plan doesn't touch those, so no `-f` needed).
- Every inserted line in a US patch carries trailing `// SourbyCraft - US import` per the template.

---

## Phase 1 — yml schema extension + accessor tests

### Task 1: Append 4 particle keys + new `sounds:` section to bundled yml

**Files:**
- Modify: `sourbycraft-server/src/main/resources/sourbycraft.yml`

- [ ] **Step 1: Append the new keys to the resource yml**

Open `sourbycraft-server/src/main/resources/sourbycraft.yml`. Find the existing `particles:` section (added by Foundation; currently has `disableFallParticles` and `disableDeathParticles`). Append four keys under it, then add a new top-level `sounds:` section.

Replace this block:

```yaml
particles:
  # Disable entity fall particles (UniverseSpigot: particles.disableFallParticles).
  disableFallParticles: false
  # Disable entity death particles (UniverseSpigot: particles.disableDeathParticles).
  disableDeathParticles: false
```

With:

```yaml
particles:
  # Disable entity fall particles (UniverseSpigot: particles.disableFallParticles).
  disableFallParticles: false
  # Disable entity death particles (UniverseSpigot: particles.disableDeathParticles).
  disableDeathParticles: false
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
  # Disable entity footstep sounds across step + swim-step variants (UniverseSpigot: sounds.disableFootStepSounds).
  disableFootStepSounds: false
  # Disable PLAYER_ATTACK_* sound family from 1.9+ combat (UniverseSpigot: sounds.disableNewCombatSounds).
  disableNewCombatSounds: false
  # Disable shield-block impact sound (UniverseSpigot: sounds.disableShieldSounds).
  disableShieldSounds: false
  # Disable piston extend / retract sounds (UniverseSpigot: sounds.disablePistonSounds).
  disablePistonSounds: false
```

- [ ] **Step 2: Verify the yml parses cleanly**

Run: `python3 -c "import yaml; y=yaml.safe_load(open('sourbycraft-server/src/main/resources/sourbycraft.yml')); assert y['particles']['disableBlockBreakParticles'] is False; assert y['sounds']['disablePistonSounds'] is False; print('OK')"`

Expected: `OK`.

- [ ] **Step 3: Verify all 10 keys are now in the file**

Run: `grep -cE 'disable(BlockBreak|Effect|WaterSplash|NewCombat)Particles' sourbycraft-server/src/main/resources/sourbycraft.yml`

Expected: `4`.

Run: `grep -cE 'disable(ShoulderEntityAmbient|PiglinAnger|FootStep|NewCombat|Shield|Piston)Sound' sourbycraft-server/src/main/resources/sourbycraft.yml`

Expected: `6`.

Run: `grep -c '^sounds:' sourbycraft-server/src/main/resources/sourbycraft.yml`

Expected: `1`.

- [ ] **Step 4: Commit**

```bash
git add sourbycraft-server/src/main/resources/sourbycraft.yml
git commit -m "$(cat <<'EOF'
feat: sourbycraft.yml — particles batch-1 + sounds section (US 01)

Appends 4 remaining UniverseSpigot particles.* keys to the existing particles:
section and adds a new sounds: section with 6 keys. All defaults false →
vanilla Paper preserved; plugin compat and SourbyCraft brand identity unaffected.
Sub-project 01 of the UniverseSpigot config import.
EOF
)"
```

---

### Task 2: Add accessor test for the 4 new particle keys

**Files:**
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java`

- [ ] **Step 1: Append the failing particle-batch1 test method**

Open `SourbyCraftConfigAccessorsTest.java`. Find the existing `ymlBool_readsNewParticlesSection` method (added by Foundation). After it, append:

```java
    @Test
    void ymlBool_readsNewParticlesBatch1Keys() {
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableBlockBreakParticles", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableEffectParticles", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableWaterSplashParticles", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableNewCombatParticles", true));
    }
```

- [ ] **Step 2: Run the test to verify it passes**

Because Task 1 already appended the keys to the bundled yml, this test is expected to PASS immediately. This is not strict TDD (no failing-first step) — the yml change is what makes the test pass, and that change is already in the previous commit. The test exists to guard against future yml edits dropping these keys.

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: 18 tests pass (17 existing + 1 new).

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java
git commit -m "test: accessor test for particles batch-1 baseline reads"
```

---

### Task 3: Add accessor test for the 6 new sound keys

**Files:**
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java`

- [ ] **Step 1: Append the sounds-batch1 test method**

Open `SourbyCraftConfigAccessorsTest.java`. After `ymlBool_readsNewParticlesBatch1Keys`, append:

```java
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

- [ ] **Step 2: Run the test to verify it passes**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: 19 tests pass.

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java
git commit -m "test: accessor test for sounds batch-1 baseline reads"
```

---

## Phase 2 — Particles batch-1 patch (4 gates)

This phase writes the paperweight feature patch using the format-patch workflow demonstrated in Foundation Task 10.

### Task 4: Edit upstream cache to apply the 4 particle gates

**Files (edited in the cache, NOT in the repo):**
- Modify (cache only): `.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java/net/minecraft/world/level/Level.java`
- Modify (cache only): `.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java/net/minecraft/world/entity/LivingEntity.java`
- Modify (cache only): `.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java/net/minecraft/world/entity/Entity.java`
- Modify (cache only): `.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java/net/minecraft/world/entity/player/Player.java`

The variable `CACHE` in the commands below refers to `/Users/rheninxy/Sourby/SourbyCraft/.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java`.

- [ ] **Step 1: Verify upstream cache is at the expected HEAD**

Run: `git -C /Users/rheninxy/Sourby/SourbyCraft/.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java log --oneline -1`

Expected: `e4a7af8 Add explicit flush support to Log4j AsyncAppender` (or whatever the latest Paper feature commit is). If the cache is dirty or has unexpected commits, run `git -C <cache> reset --hard HEAD && git -C <cache> clean -fd` to clean. (Only safe to do because this is a paperweight-regenerable cache.)

- [ ] **Step 2: Gate `particles.disableBlockBreakParticles` in `Level.java`**

In `<CACHE>/net/minecraft/world/level/Level.java`, find this block around line 1217-1219:

```java
            if (playEffect && !(blockState.getBlock() instanceof BaseFireBlock)) { // Paper - BlockDestroyEvent
                this.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(effectType)); // Paper - BlockDestroyEvent
            }
```

Replace with:

```java
            if (playEffect && !(blockState.getBlock() instanceof BaseFireBlock)) { // Paper - BlockDestroyEvent
                if (!dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("particles.disableBlockBreakParticles", false)) { // SourbyCraft - US import
                this.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(effectType)); // Paper - BlockDestroyEvent
                } // SourbyCraft - US import
            }
```

- [ ] **Step 3: Gate `particles.disableEffectParticles` in `LivingEntity.java`**

In `<CACHE>/net/minecraft/world/entity/LivingEntity.java`, find this block around lines 1018-1024:

```java
        List<ParticleOptions> list = this.activeEffects
            .values()
            .stream()
            .filter(MobEffectInstance::isVisible)
            .map(MobEffectInstance::getParticleOptions)
            .toList();
        this.entityData.set(DATA_EFFECT_PARTICLES, list);
```

Replace with:

```java
        List<ParticleOptions> list = this.activeEffects
            .values()
            .stream()
            .filter(MobEffectInstance::isVisible)
            .map(MobEffectInstance::getParticleOptions)
            .toList();
        if (dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("particles.disableEffectParticles", false)) list = java.util.List.of(); // SourbyCraft - US import
        this.entityData.set(DATA_EFFECT_PARTICLES, list);
```

The vanilla `entityData.set(...)` line stays unchanged. Only the `list` it receives is replaced when the gate fires.

- [ ] **Step 4: Gate `particles.disableWaterSplashParticles` in `Entity.java`**

In `<CACHE>/net/minecraft/world/entity/Entity.java`, find this block around lines 2105-2109:

```java
        for (int i = 0; i < 1.0F + this.dimensions.width() * 20.0F; i++) {
            double d = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            double d1 = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            this.level().addParticle(ParticleTypes.SPLASH, this.getX() + d, f1 + 1.0F, this.getZ() + d1, deltaMovement.x, deltaMovement.y, deltaMovement.z);
        }
```

Replace with (cache the bool at loop entry because the addParticle call is in a loop body):

```java
        final boolean waterSplashOff = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("particles.disableWaterSplashParticles", false); // SourbyCraft - US import
        for (int i = 0; i < 1.0F + this.dimensions.width() * 20.0F; i++) {
            double d = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            double d1 = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            if (!waterSplashOff) // SourbyCraft - US import
            this.level().addParticle(ParticleTypes.SPLASH, this.getX() + d, f1 + 1.0F, this.getZ() + d1, deltaMovement.x, deltaMovement.y, deltaMovement.z);
        }
```

- [ ] **Step 5: Gate `particles.disableNewCombatParticles` in `Player.java`**

In `<CACHE>/net/minecraft/world/entity/player/Player.java`, find this block around lines 1265-1268 (inside `doSweepAttack`):

```java
            double d = -Mth.sin(this.getYRot() * (float) (Math.PI / 180.0));
            double d1 = Mth.cos(this.getYRot() * (float) (Math.PI / 180.0));
            serverLevel.sendParticles(ParticleTypes.SWEEP_ATTACK, this.getX() + d, this.getY(0.5), this.getZ() + d1, 0, d, 0.0, d1, 0.0);
        }
```

Replace with:

```java
            double d = -Mth.sin(this.getYRot() * (float) (Math.PI / 180.0));
            double d1 = Mth.cos(this.getYRot() * (float) (Math.PI / 180.0));
            if (!dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("particles.disableNewCombatParticles", false)) // SourbyCraft - US import
            serverLevel.sendParticles(ParticleTypes.SWEEP_ATTACK, this.getX() + d, this.getY(0.5), this.getZ() + d1, 0, d, 0.0, d1, 0.0);
        }
```

- [ ] **Step 6: Commit the cache edits**

Run:

```bash
CACHE=/Users/rheninxy/Sourby/SourbyCraft/.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java
git -C $CACHE add net/minecraft/world/level/Level.java \
                 net/minecraft/world/entity/LivingEntity.java \
                 net/minecraft/world/entity/Entity.java \
                 net/minecraft/world/entity/player/Player.java
git -C $CACHE -c user.name=Yan -c user.email=Yan commit -m "SourbyCraft v12 US import particles batch-1 gated emit

Sub-project 01 of the UniverseSpigot config import. Gates 4 server-side
particle emit sites:
  - Level#destroyBlock (PARTICLES_DESTROY_BLOCK)
  - LivingEntity#updateInvisibilityStatus (DATA_EFFECT_PARTICLES via empty-list swap)
  - Entity#... (ParticleTypes.SPLASH water-entry loop, cached bool)
  - Player#doSweepAttack (ParticleTypes.SWEEP_ATTACK)
Defaults off; vanilla Paper preserved. Brand stays SourbyCraft (patch 0003
untouched)."
```

Expected: `[main <hash>] SourbyCraft v12 US import particles batch-1 gated emit`.

---

### Task 5: Generate the particles patch and place it in the repo

**Files:**
- Create: `patches/minecraft/0043-us-particles-batch1.patch`

- [ ] **Step 1: Generate the patch from the cache commit**

Run:

```bash
CACHE=/Users/rheninxy/Sourby/SourbyCraft/.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java
rm -rf /tmp/us-01-particles && mkdir /tmp/us-01-particles
git -C $CACHE format-patch -1 -o /tmp/us-01-particles HEAD
ls /tmp/us-01-particles/
```

Expected: one file `0001-SourbyCraft-v12-US-import-particles-batch-1-gated-em.patch` (or similar).

- [ ] **Step 2: Inspect the patch**

Run: `cat /tmp/us-01-particles/0001-*.patch | head -80`

Expected: a paperweight-format patch with `diff --git a/net/minecraft/...` hunks. Verify each of the 4 NMS files is present in the diff (`grep '^diff --git' /tmp/us-01-particles/0001-*.patch | wc -l` should be `4`).

- [ ] **Step 3: Verify size and marker count**

Run: `wc -l /tmp/us-01-particles/0001-*.patch`

Expected: under 200 lines.

Run: `grep -c 'SourbyCraft - US import' /tmp/us-01-particles/0001-*.patch`

Expected: `>= 4` (one per gate, plus close-brace comments for sites with explicit blocks).

- [ ] **Step 4: Copy the patch to the canonical location**

Run: `cp /tmp/us-01-particles/0001-*.patch patches/minecraft/0043-us-particles-batch1.patch`

- [ ] **Step 5: Reset the upstream cache to leave it clean**

Run:

```bash
CACHE=/Users/rheninxy/Sourby/SourbyCraft/.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java
git -C $CACHE reset --hard HEAD~1
git -C $CACHE log --oneline -1
```

Expected: HEAD is back to the original Paper commit (`e4a7af8 Add explicit flush support to Log4j AsyncAppender` or whatever it was at Step 1 of Task 4).

- [ ] **Step 6: Verify the patch file is in place and acceptance criteria are met**

Run: `ls patches/minecraft/0043-us-particles-batch1.patch`

Expected: file exists.

Run: `wc -l patches/minecraft/0043-us-particles-batch1.patch`

Expected: under 200 lines.

Run: `grep -c 'SourbyCraft - US import' patches/minecraft/0043-us-particles-batch1.patch`

Expected: `>= 4`.

- [ ] **Step 7: Commit**

```bash
git add patches/minecraft/0043-us-particles-batch1.patch
git commit -m "$(cat <<'EOF'
patch: us-particles-batch1 — block-break + effect + water-splash + sweep-attack gated

Sub-project 01 reference patch. Gates 4 server-side particle emit sites:
  - Level#destroyBlock (PARTICLES_DESTROY_BLOCK)
  - LivingEntity tickEffects DATA_EFFECT_PARTICLES sync (list → empty-list swap)
  - Entity water-entry SPLASH-loop (cached bool, per-iteration branch)
  - Player#doSweepAttack SWEEP_ATTACK particles
All defaults off so vanilla Paper preserved. Each gated line carries the
`// SourbyCraft - US import` marker per the gated-patch template.
EOF
)"
```

---

## Phase 3 — Sounds batch-1 patch (6 gates)

### Task 6: Edit upstream cache to apply the 6 sound gates

**Files (edited in the cache, NOT in the repo):**
- Modify (cache only): `<CACHE>/net/minecraft/world/entity/animal/parrot/Parrot.java`
- Modify (cache only): `<CACHE>/net/minecraft/world/entity/monster/piglin/Piglin.java`
- Modify (cache only): `<CACHE>/net/minecraft/world/entity/Entity.java`
- Modify (cache only): `<CACHE>/net/minecraft/world/entity/player/Player.java`
- Modify (cache only): `<CACHE>/net/minecraft/world/item/component/BlocksAttacks.java`
- Modify (cache only): `<CACHE>/net/minecraft/world/level/block/piston/PistonBaseBlock.java`

- [ ] **Step 1: Verify upstream cache is still clean from Task 5 Step 5**

Run: `git -C /Users/rheninxy/Sourby/SourbyCraft/.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java status --porcelain`

Expected: empty output (no uncommitted changes).

- [ ] **Step 2: Gate `sounds.disableShoulderEntityAmbientSound` in `Parrot.java`**

In `<CACHE>/net/minecraft/world/entity/animal/parrot/Parrot.java`, find line 241 inside `imitateNearbyMobs`:

```java
                if (!mob.isSilent()) {
                    SoundEvent imitatedSound = getImitatedSound(mob.getType());
                    level.playSound(null, parrot.getX(), parrot.getY(), parrot.getZ(), imitatedSound, parrot.getSoundSource(), 0.7F, getPitch(level.random));
                    return true;
                }
```

Replace with:

```java
                if (!mob.isSilent()) {
                    SoundEvent imitatedSound = getImitatedSound(mob.getType());
                    if (!dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disableShoulderEntityAmbientSound", false)) // SourbyCraft - US import
                    level.playSound(null, parrot.getX(), parrot.getY(), parrot.getZ(), imitatedSound, parrot.getSoundSource(), 0.7F, getPitch(level.random));
                    return true;
                }
```

- [ ] **Step 3: Gate `sounds.disablePiglinAngerSound` in `Piglin.java`**

In `<CACHE>/net/minecraft/world/entity/monster/piglin/Piglin.java`, find `getAmbientSound` around line 461-464:

```java
    @Override
    public @Nullable SoundEvent getAmbientSound() {
        return this.level().isClientSide() ? null : PiglinAi.getSoundForCurrentActivity(this).orElse(null);
    }
```

Replace with:

```java
    @Override
    public @Nullable SoundEvent getAmbientSound() {
        if (this.level().isClientSide()) return null;
        SoundEvent s = PiglinAi.getSoundForCurrentActivity(this).orElse(null); // SourbyCraft - US import
        if (s == SoundEvents.PIGLIN_ANGRY && dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disablePiglinAngerSound", false)) return null; // SourbyCraft - US import
        return s; // SourbyCraft - US import
    }
```

This filters only the anger variant; other ambient activity sounds (admiring, idle) still play.

- [ ] **Step 4: Gate `sounds.disableFootStepSounds` across three step variants in `Entity.java`**

In `<CACHE>/net/minecraft/world/entity/Entity.java`, find these three methods around lines 1840-1854 and replace each in turn.

(a) `playCombinationStepSounds` (around line 1840):

```java
    protected void playCombinationStepSounds(BlockState primaryState, BlockState secondaryState) {
        SoundType soundType = primaryState.getSoundType();
        this.playSound(soundType.getStepSound(), soundType.getVolume() * 0.15F, soundType.getPitch());
        this.playMuffledStepSound(secondaryState);
    }
```

Replace with:

```java
    protected void playCombinationStepSounds(BlockState primaryState, BlockState secondaryState) {
        if (dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disableFootStepSounds", false)) return; // SourbyCraft - US import
        SoundType soundType = primaryState.getSoundType();
        this.playSound(soundType.getStepSound(), soundType.getVolume() * 0.15F, soundType.getPitch());
        this.playMuffledStepSound(secondaryState);
    }
```

(b) `playMuffledStepSound` (around line 1846):

```java
    protected void playMuffledStepSound(BlockState state) {
        SoundType soundType = state.getSoundType();
        this.playSound(soundType.getStepSound(), soundType.getVolume() * 0.05F, soundType.getPitch() * 0.8F);
    }
```

Replace with:

```java
    protected void playMuffledStepSound(BlockState state) {
        if (dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disableFootStepSounds", false)) return; // SourbyCraft - US import
        SoundType soundType = state.getSoundType();
        this.playSound(soundType.getStepSound(), soundType.getVolume() * 0.05F, soundType.getPitch() * 0.8F);
    }
```

(c) `playStepSound` (around line 1851):

```java
    protected void playStepSound(BlockPos pos, BlockState state) {
        SoundType soundType = state.getSoundType();
        this.playSound(soundType.getStepSound(), soundType.getVolume() * 0.15F, soundType.getPitch());
    }
```

Replace with:

```java
    protected void playStepSound(BlockPos pos, BlockState state) {
        if (dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disableFootStepSounds", false)) return; // SourbyCraft - US import
        SoundType soundType = state.getSoundType();
        this.playSound(soundType.getStepSound(), soundType.getVolume() * 0.15F, soundType.getPitch());
    }
```

Note: each method is its own early-return — no shared cache. These methods are called per-tick per-entity, but each call is one bool lookup; caching across methods would require a field which is overkill.

- [ ] **Step 5: Gate `sounds.disableNewCombatSounds` across 4 methods in `Player.java`**

The 6 emit sites live in 4 methods. Apply the cache-bool pattern to each method individually.

(a) `attack` — gates sites at lines ~1044 and ~1079. Find around line 1042:

```java
                if (f > 0.0F || f1 > 0.0F) {
                    boolean flag = attackStrengthScale > 0.9F;
                    boolean flag1;
                    if (this.isSprinting() && flag) {
                        this.playServerSideSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK);
                        flag1 = true;
                    } else {
                        flag1 = false;
                    }
```

Replace with:

```java
                if (f > 0.0F || f1 > 0.0F) {
                    final boolean newCombatSoundsOff = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disableNewCombatSounds", false); // SourbyCraft - US import
                    boolean flag = attackStrengthScale > 0.9F;
                    boolean flag1;
                    if (this.isSprinting() && flag) {
                        if (!newCombatSoundsOff) this.playServerSideSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK); // SourbyCraft - US import
                        flag1 = true;
                    } else {
                        flag1 = false;
                    }
```

Then find around line 1078 (still inside `attack`):

```java
                    } else {
                        this.playServerSideSound(SoundEvents.PLAYER_ATTACK_NODAMAGE);
                    }
```

Replace with:

```java
                    } else {
                        if (!newCombatSoundsOff) this.playServerSideSound(SoundEvents.PLAYER_ATTACK_NODAMAGE); // SourbyCraft - US import
                    }
```

(`newCombatSoundsOff` is in scope here because it was declared at the top of the same enclosing `if (f > 0.0F || f1 > 0.0F) {` block.)

(b) `deflectProjectile` — gates the site at line 1105:

```java
            && projectile.deflect(ProjectileDeflection.AIM_DEFLECT, this, EntityReference.of(this), true)) {
            this.makeSound(SoundEvents.PLAYER_ATTACK_NODAMAGE); // Paper - Use makeSound to avoid duplicating client-side sound for source player
            return true;
```

Replace with:

```java
            && projectile.deflect(ProjectileDeflection.AIM_DEFLECT, this, EntityReference.of(this), true)) {
            if (!dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disableNewCombatSounds", false)) // SourbyCraft - US import
            this.makeSound(SoundEvents.PLAYER_ATTACK_NODAMAGE); // Paper - Use makeSound to avoid duplicating client-side sound for source player
            return true;
```

(One-shot site, inline check.)

(c) `attackVisualEffects` — gates sites at lines 1137 and 1142. Find around line 1135:

```java
    private void attackVisualEffects(Entity target, boolean isCritical, boolean isSweep, boolean isStrong, boolean isStab, float damageAmount) {
        if (isCritical) {
            this.playServerSideSound(SoundEvents.PLAYER_ATTACK_CRIT);
            this.crit(target);
        }

        if (!isCritical && !isSweep && !isStab) {
            this.playServerSideSound(isStrong ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK);
        }
```

Replace with:

```java
    private void attackVisualEffects(Entity target, boolean isCritical, boolean isSweep, boolean isStrong, boolean isStab, float damageAmount) {
        final boolean newCombatSoundsOff = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disableNewCombatSounds", false); // SourbyCraft - US import
        if (isCritical) {
            if (!newCombatSoundsOff) this.playServerSideSound(SoundEvents.PLAYER_ATTACK_CRIT); // SourbyCraft - US import
            this.crit(target);
        }

        if (!isCritical && !isSweep && !isStab) {
            if (!newCombatSoundsOff) this.playServerSideSound(isStrong ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK); // SourbyCraft - US import
        }
```

(d) `doSweepAttack` — gates site at line 1244 (already touched in Task 4 Step 5 for `disableNewCombatParticles`; the SOUND gate is separate). Find around line 1243:

```java
    private void doSweepAttack(Entity entity, float damageAmount, DamageSource damageSource, float strengthScale) {
        this.playServerSideSound(SoundEvents.PLAYER_ATTACK_SWEEP);
```

Replace with:

```java
    private void doSweepAttack(Entity entity, float damageAmount, DamageSource damageSource, float strengthScale) {
        if (!dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disableNewCombatSounds", false)) // SourbyCraft - US import
        this.playServerSideSound(SoundEvents.PLAYER_ATTACK_SWEEP);
```

Note: this method already has the particle gate from Task 4 Step 5 further down. Both gates coexist; the sound gate is at method entry, the particle gate is at the `sendParticles` line.

- [ ] **Step 6: Gate `sounds.disableShieldSounds` in `BlocksAttacks.java`**

In `<CACHE>/net/minecraft/world/item/component/BlocksAttacks.java`, find `onBlocked` around line 73:

```java
    public void onBlocked(ServerLevel level, LivingEntity entity) {
        this.blockSound
            .ifPresent(
                sound -> level.playSound(
                    null,
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    (Holder<SoundEvent>)sound,
                    entity.getSoundSource(),
                    1.0F,
                    0.8F + level.random.nextFloat() * 0.4F
                )
            );
    }
```

Replace with:

```java
    public void onBlocked(ServerLevel level, LivingEntity entity) {
        if (dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disableShieldSounds", false)) return; // SourbyCraft - US import
        this.blockSound
            .ifPresent(
                sound -> level.playSound(
                    null,
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    (Holder<SoundEvent>)sound,
                    entity.getSoundSource(),
                    1.0F,
                    0.8F + level.random.nextFloat() * 0.4F
                )
            );
    }
```

- [ ] **Step 7: Gate `sounds.disablePistonSounds` in `PistonBaseBlock.java`**

In `<CACHE>/net/minecraft/world/level/block/piston/PistonBaseBlock.java`, two sites.

(a) Around line 180 (extend):

```java
            level.setBlock(pos, blockState, Block.UPDATE_ALL | Block.UPDATE_MOVE_BY_PISTON);
            level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.5F, level.random.nextFloat() * 0.25F + 0.6F);
            level.gameEvent(GameEvent.BLOCK_ACTIVATE, pos, GameEvent.Context.of(blockState));
```

Replace with:

```java
            level.setBlock(pos, blockState, Block.UPDATE_ALL | Block.UPDATE_MOVE_BY_PISTON);
            if (!dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disablePistonSounds", false)) // SourbyCraft - US import
            level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.5F, level.random.nextFloat() * 0.25F + 0.6F);
            level.gameEvent(GameEvent.BLOCK_ACTIVATE, pos, GameEvent.Context.of(blockState));
```

(b) Around line 249 (contract):

```java
            }

            level.playSound(null, pos, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5F, level.random.nextFloat() * 0.15F + 0.6F);
            level.gameEvent(GameEvent.BLOCK_DEACTIVATE, pos, GameEvent.Context.of(blockState1));
```

Replace with:

```java
            }

            if (!dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool("sounds.disablePistonSounds", false)) // SourbyCraft - US import
            level.playSound(null, pos, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5F, level.random.nextFloat() * 0.15F + 0.6F);
            level.gameEvent(GameEvent.BLOCK_DEACTIVATE, pos, GameEvent.Context.of(blockState1));
```

Two separate `ymlBool` calls — they're in distinct code paths, and the cost is negligible compared to a piston tick.

- [ ] **Step 8: Commit the cache edits**

Run:

```bash
CACHE=/Users/rheninxy/Sourby/SourbyCraft/.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java
git -C $CACHE add net/minecraft/world/entity/animal/parrot/Parrot.java \
                 net/minecraft/world/entity/monster/piglin/Piglin.java \
                 net/minecraft/world/entity/Entity.java \
                 net/minecraft/world/entity/player/Player.java \
                 net/minecraft/world/item/component/BlocksAttacks.java \
                 net/minecraft/world/level/block/piston/PistonBaseBlock.java
git -C $CACHE -c user.name=Yan -c user.email=Yan commit -m "SourbyCraft v12 US import sounds batch-1 gated emit

Sub-project 01 of the UniverseSpigot config import. Gates 6 server-side
sound emit sites across 6 NMS files:
  - Parrot#imitateNearbyMobs (shoulder ambient mimicry)
  - Piglin#getAmbientSound (PIGLIN_ANGRY filter only)
  - Entity#play{Combination,Muffled,}StepSound (all step variants)
  - Player attack/deflect/visual-effects/sweep PLAYER_ATTACK_* sites
  - BlocksAttacks#onBlocked (shield block sound)
  - PistonBaseBlock extend + contract sounds
Defaults off; vanilla Paper preserved. Brand stays SourbyCraft."
```

Expected: `[main <hash>] SourbyCraft v12 US import sounds batch-1 gated emit`.

---

### Task 7: Generate the sounds patch and place it in the repo

**Files:**
- Create: `patches/minecraft/0044-us-sounds-batch1.patch`

- [ ] **Step 1: Generate the patch from the cache commit**

Run:

```bash
CACHE=/Users/rheninxy/Sourby/SourbyCraft/.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java
rm -rf /tmp/us-01-sounds && mkdir /tmp/us-01-sounds
git -C $CACHE format-patch -1 -o /tmp/us-01-sounds HEAD
ls /tmp/us-01-sounds/
```

Expected: one file `0001-SourbyCraft-v12-US-import-sounds-batch-1-gated-emit.patch`.

- [ ] **Step 2: Inspect the patch**

Run: `head -100 /tmp/us-01-sounds/0001-*.patch`

Expected: standard paperweight header, then 6 `diff --git a/net/minecraft/...` hunks (one per file).

- [ ] **Step 3: Verify size and marker count**

Run: `wc -l /tmp/us-01-sounds/0001-*.patch`

Expected: under 200 lines.

Run: `grep -c 'SourbyCraft - US import' /tmp/us-01-sounds/0001-*.patch`

Expected: `>= 6`.

Run: `grep '^diff --git' /tmp/us-01-sounds/0001-*.patch | wc -l`

Expected: `6` (one diff per NMS file).

- [ ] **Step 4: Copy the patch to the canonical location**

Run: `cp /tmp/us-01-sounds/0001-*.patch patches/minecraft/0044-us-sounds-batch1.patch`

- [ ] **Step 5: Reset the upstream cache to leave it clean**

Run:

```bash
CACHE=/Users/rheninxy/Sourby/SourbyCraft/.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java
git -C $CACHE reset --hard HEAD~1
git -C $CACHE log --oneline -1
```

Expected: HEAD is back to the original Paper commit.

- [ ] **Step 6: Verify acceptance criteria**

Run: `ls patches/minecraft/0044-us-sounds-batch1.patch`

Expected: exists.

Run: `wc -l patches/minecraft/0044-us-sounds-batch1.patch`

Expected: under 200 lines.

Run: `grep -c 'SourbyCraft - US import' patches/minecraft/0044-us-sounds-batch1.patch`

Expected: `>= 6`.

- [ ] **Step 7: Commit**

```bash
git add patches/minecraft/0044-us-sounds-batch1.patch
git commit -m "$(cat <<'EOF'
patch: us-sounds-batch1 — 6 NMS sound emit gates

Sub-project 01 reference patch. Gates 6 server-side sound emit sites:
  - Parrot#imitateNearbyMobs (shoulder ambient mimicry)
  - Piglin#getAmbientSound (PIGLIN_ANGRY filter; other ambient sounds pass through)
  - Entity#play{Combination,Muffled,}StepSound (all step variants)
  - Player attack / deflect / visual-effects / sweep PLAYER_ATTACK_* sites
  - BlocksAttacks#onBlocked (shield-block impact sound)
  - PistonBaseBlock PISTON_EXTEND / PISTON_CONTRACT
All defaults off so vanilla Paper preserved. Brand stays SourbyCraft;
patch 0003 untouched.
EOF
)"
```

---

## Phase 4 — Final verification

### Task 8: Run the full acceptance-criteria sweep

**Files:** none (verification only)

- [ ] **Step 1: Accessor tests still green**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. Confirm via the XML report:

Run: `head -3 sourbycraft-server/build/test-results/test/TEST-dev.iyanz.sourbycraft.SourbyCraftConfigAccessorsTest.xml`

Expected: `tests="19"`, `failures="0"`, `errors="0"`.

- [ ] **Step 2: yml schema checks**

Run: `grep -cE 'disable(BlockBreak|Effect|WaterSplash|NewCombat)Particles' sourbycraft-server/src/main/resources/sourbycraft.yml`

Expected: `4`.

Run: `grep -cE 'disable(ShoulderEntityAmbient|PiglinAnger|FootStep|NewCombat|Shield|Piston)Sound' sourbycraft-server/src/main/resources/sourbycraft.yml`

Expected: `6`.

Run: `grep -c '^sounds:' sourbycraft-server/src/main/resources/sourbycraft.yml`

Expected: `1`.

- [ ] **Step 3: Patch lint**

Run: `ls patches/minecraft/0043-us-particles-batch1.patch patches/minecraft/0044-us-sounds-batch1.patch`

Expected: both exist.

Run: `wc -l patches/minecraft/0043-us-particles-batch1.patch patches/minecraft/0044-us-sounds-batch1.patch`

Expected: each under 200 lines.

Run: `grep -c 'SourbyCraft - US import' patches/minecraft/0043-us-particles-batch1.patch`

Expected: `>= 4`.

Run: `grep -c 'SourbyCraft - US import' patches/minecraft/0044-us-sounds-batch1.patch`

Expected: `>= 6`.

- [ ] **Step 4: Brand identity preserved**

Run: `git diff 560d499..HEAD -- patches/server/0003-Changed-branding.patch`

(Where `560d499` is the spec-commit SHA — the immediate pre-01 base. Use `git log --oneline | grep '01 particles' | head -1` to locate it if needed.)

Expected: empty output. (`0003-Changed-branding.patch` MUST NOT be modified by sub-project 01.)

Run: `grep -c 'BRAND_PAPER_ID' patches/server/0003-Changed-branding.patch`

Expected: `>= 1` (the existing line that aliases Paper as compatible is still present, unchanged).

- [ ] **Step 5: Foundation patch untouched**

Run: `git diff 560d499..HEAD -- patches/minecraft/0042-us-particles-fall-death.patch`

Expected: empty output.

- [ ] **Step 6: Patches apply cleanly (optional smoke)**

Run: `./gradlew applyAllPatches 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. (Note: as documented in Foundation Task 10, the SourbyCraft minecraft patches are not visibly applied to the upstream cache git history despite the build reporting success. The patches will integrate into the build pipeline at the same stage as 0042. Treat success here as "apply did not error" rather than "all gates are now active in the cache".)

- [ ] **Step 7: No regression in other test classes**

Run: `./gradlew :sourbycraft-server:test 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`. All existing tests continue to pass (`SourbyCraftConfigYmlGetTest`, `SourbyCraftBannerTest`, `BuildInfoTest`, etc.).

- [ ] **Step 8: Final commit (verification log only — no functional changes)**

This step intentionally does not create a commit. Sub-project 01 ends at the Task 7 commit (sounds patch). If you discover any deviations during this verification, fix them and amend the relevant earlier commit (or add a small follow-up commit if amending would re-write a reviewed commit).

---

## Verification Summary

After all 8 tasks complete, every row below must be true:

| Check | Command | Expected |
|---|---|---|
| 4 new particle keys in yml | `grep -cE 'disable(BlockBreak\|Effect\|WaterSplash\|NewCombat)Particles' sourbycraft-server/src/main/resources/sourbycraft.yml` | `4` |
| 6 new sound keys in yml | `grep -cE 'disable(ShoulderEntityAmbient\|PiglinAnger\|FootStep\|NewCombat\|Shield\|Piston)Sound' sourbycraft-server/src/main/resources/sourbycraft.yml` | `6` |
| `sounds:` section present | `grep -c '^sounds:' sourbycraft-server/src/main/resources/sourbycraft.yml` | `1` |
| Particles batch-1 patch present | `ls patches/minecraft/0043-us-particles-batch1.patch` | exists |
| Sounds batch-1 patch present | `ls patches/minecraft/0044-us-sounds-batch1.patch` | exists |
| Both patches under 200 lines | `wc -l patches/minecraft/0043-*.patch patches/minecraft/0044-*.patch` | each `< 200` |
| Particles patch marker count | `grep -c 'SourbyCraft - US import' patches/minecraft/0043-*.patch` | `>= 4` |
| Sounds patch marker count | `grep -c 'SourbyCraft - US import' patches/minecraft/0044-*.patch` | `>= 6` |
| Sounds patch touches 6 files | `grep '^diff --git' patches/minecraft/0044-*.patch \| wc -l` | `6` |
| Particles patch touches 4 files | `grep '^diff --git' patches/minecraft/0043-*.patch \| wc -l` | `4` |
| Accessor tests green | `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest` | 19/19 |
| Full test suite green | `./gradlew :sourbycraft-server:test` | green |
| `applyAllPatches` succeeds | `./gradlew applyAllPatches` | exit 0 |
| Brand patch untouched | `git diff 560d499..HEAD -- patches/server/0003-Changed-branding.patch` | empty |
| Foundation patch untouched | `git diff 560d499..HEAD -- patches/minecraft/0042-us-particles-fall-death.patch` | empty |
| Upstream cache clean after Tasks 5+7 | `git -C .gradle/caches/.../paper/src/minecraft/java status --porcelain` | empty |

## Self-Review Notes

Spec coverage:

- **C1 yml schema extension** → Task 1.
- **C2 Particles batch-1 patch (4 keys)** → Tasks 4 + 5.
- **C3 Sounds batch-1 patch (6 keys)** → Tasks 6 + 7.
- **C4 Accessor unit tests** → Tasks 2 + 3.
- **C5 CI workflow (no change)** → confirmed via Task 8 Step 3 (workflow file untouched).
- **Data flow special cases (effect-particles list-swap, piglin-anger filter)** → Task 4 Step 3 (effect list-swap) + Task 6 Step 3 (piglin filter).
- **Hot-loop bool-caching rule** → Task 4 Step 4 (water-splash loop) + Task 6 Step 5 (Player attack cache).
- **Acceptance criteria table** → Verification Summary row-by-row mapping.
- **Branding constraint** (user-stated this session: "brand tetap SourbyCraft") → Task 8 Steps 4 + 5 verify `0003-Changed-branding.patch` is untouched.

Placeholder scan: no "TBD"/"TODO"/"similar to" patterns. Every code step shows the full vanilla-before and post-patch-after snippet.

Type consistency: every gate uses `dev.iyanz.sourbycraft.SourbyCraftConfig.ymlBool(String, boolean)` (the Foundation accessor). No new APIs introduced. Cache variable naming (`waterSplashOff`, `newCombatSoundsOff`) follows the Foundation reference patch convention (`particlesOff`).

Workflow deviation flagged: Foundation Task 10 documented that the minecraft patches don't visibly land in the upstream cache git history during `applyAllPatches` (the build reports success but the cache HEAD is Paper-only). This plan inherits that same operational reality — the patches are correctly authored in paperweight feature-patch format, but their build-time application status is the same as 0042's. Confirmed as out-of-scope for sub-project 01; a separate follow-up should validate the minecraft-patch apply pipeline holistically.
