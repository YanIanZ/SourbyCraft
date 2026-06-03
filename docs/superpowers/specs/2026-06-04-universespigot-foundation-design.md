# UniverseSpigot config import — 00 Foundation (design)

**Date**: 2026-06-04
**Scope**: Sub-project 00 of the UniverseSpigot config import series. Delivers the runtime + config infrastructure that subsequent sub-projects (01 particles → 08 experimental/developer) all build on. Ships one end-to-end reference implementation: 2 keys from the `particles` category.
**Out-of-scope**: Categories 01-08 (each gets its own spec → plan → impl cycle). See Section 6.
**Status**: Draft for user review.

---

## Background

UniverseSpigot is a Paper fork with ~250 tunable config keys across 12 top-level categories (`asynchronous`, `behavior`, `alternative-farms`, `combat`, `experimental`, `developer`, `misc`, `fixes`, `limiters`, `particles`, `sounds`, `performance`). SourbyCraft has tracked importing these keys as a deferred follow-up across the v12 PvP-variant, unify-variants, and NMS-plugin-compat brainstorms.

Importing all 250 keys in a single spec is unworkable. The brainstorm decomposed the work into 9 sub-projects:

```
00 — Foundation                                  ← this spec
01 — particles + sounds + misc                   (warm-up: pure toggles, low risk)
02 — limiters                                    (item/projectile/remove-excess caps)
03 — behavior toggles                            (~60 keys; NMS conditionals)
04 — fixes                                       (exploit/bug toggles)
05 — performance                                 (hoppers, ticking, dynamic-brain, virtual-threads)
06 — async                                       (tracker/pathfinder/world-ticking/data-saving)
07 — combat                                      (intersects existing PvP patches)
08 — experimental + developer                    (netty transport, palette, niche)
```

Foundation must come first because every other sub-project depends on its config plumbing and patch conventions.

## Constraints (from user)

- **Bootstrap-style slim jar**: don't bloat the jar with feature code that may be off. Solution: feature flags gate runtime code paths; no separate module jars.
- **Polish — features must not feel messy**: clean separation, one consistent pattern across all gated patches.
- **Paper-detection preserved**: plugins that check `ServerBuildInfo.isBrandCompatible(papermc:paper)` keep working. NMS plugins (Citizens, FAWE, NBTAPI, DecentHolograms) require no shim or bridge.
- **No new NMS shim API**: rejected in prior brainstorm. Patches access NMS directly.

## Architecture

```
sourbycraft.yml (operator file at plugins/SourbyCraft/sourbycraft.yml)
  ├─ existing sections: pvp/network/entity-tracker/combat/branding/auto-install
  └─ new sections (added by sub-projects 01-08): particles/sounds/misc/limiters/behavior/fixes/performance/async/experimental/developer
        ↓
SourbyCraftConfig (sourbycraft-server)
  ├─ ymlBool(path, default)         ← existing
  ├─ ymlInt(path, default)          ← existing
  ├─ ymlGet(path)                   ← existing (generic)
  ├─ ymlDouble(path, default)       ← NEW (this spec)
  ├─ ymlStringList(path)            ← NEW (this spec)
  └─ ymlEntityTypeMap(path)         ← NEW (this spec)
        ↓
Gated patch (patches/server/NNNN-us-<group>.patch)
  └─ inserts: `if (SourbyCraftConfig.ymlBool("category.key", false)) { newBehavior(); } else { vanilla; }`
        ↓
Runtime: all defaults off → vanilla Paper behavior preserved on first boot
```

**Invariants:**

- Brand: existing patch `0003-Changed-branding.patch` already maps `isBrandCompatible(papermc:paper) → true`. Foundation does not touch branding.
- No new classloader, module loader, or jar split.
- Single mojmap jar (per 2026-06-04 spec revision).
- Defaults match Paper vanilla. Operator opt-in only.

## Components

### C1. `SourbyCraftConfig` accessor expansion

**File**: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` (currently 483 lines).

Add three accessors. Existing `ymlBool`, `ymlInt`, `ymlGet` are unchanged.

- `public static double ymlDouble(String path, double fallback)` — reads `Number`, casts via `.doubleValue()`. Logs WARN once on type mismatch and returns fallback.
- `public static java.util.List<String> ymlStringList(String path)` — reads `List<?>`, filters non-strings out (logged WARN per filtered entry, once per key). Returns empty list when key missing.
- `public static java.util.Map<org.bukkit.entity.EntityType, Integer> ymlEntityTypeMap(String path)` — parses entries shaped `"TYPE:N"` (e.g. `"PLAYER:2"`, `"ARROW:48"`). Bad entries logged WARN, skipped; valid entries collected.

All three new methods are null-safe and use the same once-per-startup dedupe `Set<String>` for warning emission, added as a private static field on `SourbyCraftConfig` (direct Java edit, no patch — `SourbyCraftConfig.java` is part of the `sourbycraft-server` subproject, not a Paper patch).

### C2. `sourbycraft.yml` schema growth

**File**: `sourbycraft-server/src/main/resources/sourbycraft.yml` (currently 50 lines).

Append a new top-level section for the reference impl only:

```yaml
particles:
  # Disable entity fall particles (UniverseSpigot: particles.disableFallParticles).
  disableFallParticles: false
  # Disable entity death particles (UniverseSpigot: particles.disableDeathParticles).
  disableDeathParticles: false
```

The remaining 11 yml categories (and the remaining 6 keys of `particles` itself) are appended by their respective sub-specs.

Each key comment includes: (a) one-line description of the behavior, (b) the UniverseSpigot key path it mirrors.

The bundled jar resource is copied to `plugins/SourbyCraft/sourbycraft.yml` on first boot by the existing config-load path (no change to that path).

### C3. Patch template document

**File**: `docs/superpowers/notes/2026-06-04-us-gated-patch-template.md` (new).

Contains:

- Anatomy of the gated check: `if (SourbyCraftConfig.ymlBool("category.subgroup.key", false)) { newBehavior(); } else { vanilla; }`.
- Where to place the check: identify vanilla call site (entity tick, particle emit, etc.), wrap with check, ensure the `else` branch is bit-identical to pre-patch vanilla code.
- Bool-cache pattern for hot loops (cache config value into a local var at method entry; do not call `ymlBool` per-iteration).
- Commit naming convention: `patch: us-<category>-<subgroup> — gated <feature>`.
- Diff hygiene: each gated insertion gets a trailing `// SourbyCraft - US import` comment so future readers can grep for them.

This document is the source-of-truth referenced by every subsequent sub-spec.

### C4. Two reference patches

**File**: `patches/server/0034-us-particles-fall-death.patch` (new).

Gates two particle emission sites:

- Fall particles: vanilla emits when entity hits ground after fall. Wrap the emit call with `ymlBool("particles.disableFallParticles", false)`.
- Death particles: vanilla emits on entity death (`net.minecraft.world.entity.LivingEntity#die` → particle event). Wrap with `ymlBool("particles.disableDeathParticles", false)`.

The exact NMS call sites are identified during plan execution; the spec only fixes the pattern.

The patch follows C3 conventions: trailing `// SourbyCraft - US import` comment, bool cached at method entry where the call is in a loop.

### C5. Smoke test extension

**File**: `sourbycraft-server/build.gradle.kts` (existing `nmsCompatTest` task).

Add one integration case to the existing test harness:

1. Boot test server with `plugins/SourbyCraft/sourbycraft.yml` containing `particles.disableFallParticles: true` and `particles.disableDeathParticles: true`.
2. Programmatically drop a test zombie from y=64 onto stone, then kill it.
3. Capture outgoing `ClientboundLevelParticlesPacket` via Paper's test harness or a netty inspector hook.
4. Assert: zero fall-particle and zero death-particle packets emitted for the test entity.
5. Repeat with both toggles `false` → assert non-zero packets (vanilla preserved).

If Paper's test harness cannot capture packets directly, fall back to log-grep on `[DEBUG] particle emit` (added behind a test-only flag).

The test is opt-in via the existing `-PrunNmsCompat=true` gate.

### C6. Unit tests for accessors

**File**: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigTest.java` (extend or create).

Cases for each new accessor:

- Valid value returns parsed.
- Missing key returns default (or empty for list/map).
- Wrong type returns default and emits exactly one WARN.
- Repeat call with the same wrong key emits no additional WARN.
- `ymlEntityTypeMap` with mixed valid + invalid entries returns map of valid only, WARNs once per invalid.

Pure JUnit, no server boot. Runs in `<1s` on CI.

## Data flow

```
boot:
  CraftServer.enable()
    └─ load plugins/SourbyCraft/sourbycraft.yml (Bukkit YAML loader; existing)
    └─ SourbyCraftConfig.load() caches existing static fields (existing); new ymlDouble/ymlStringList/ymlEntityTypeMap read on demand via the existing yml backing map

per-event in gated patch (e.g., fall-particle emit site):
  vanilla NMS call site
    └─ if (SourbyCraftConfig.ymlBool("particles.disableFallParticles", false))
       ├─ true  → skip particle emit (new path; SourbyCraft branch)
       └─ false → vanilla particle emit (unchanged byte-for-byte)

reload:
  Foundation does NOT support hot-reload. Config read at boot only.
  Header comment in sourbycraft.yml documents: "Changes require server restart."
```

**Hot path cost.** `ymlBool` resolves to `HashMap<String, Object>` lookup + cast — roughly 50 ns per call. Acceptable at tick-rate or particle-emit-rate. Hot loops (entity ticking, chunk gen) must cache the bool into a local at method entry; the patch template (C3) documents this.

**Bool-cache pattern in patch:**

```java
// vanilla:
public void tick() {
    for (Entity e : entities) { ... emitFallParticle(e); ... }
}

// gated:
public void tick() {
    final boolean particlesOff = SourbyCraftConfig.ymlBool("particles.disableFallParticles", false); // SourbyCraft - US import
    for (Entity e : entities) {
        if (!particlesOff) { ... emitFallParticle(e); ... }
    }
}
```

## Error handling

| Case | Behavior |
|---|---|
| Missing `sourbycraft.yml` | Jar-bundled resource copied to plugins dir (existing path; no change). |
| Malformed YAML | SnakeYAML throws; existing handler logs error and falls back to bundled defaults. No change. |
| Missing key | Accessor returns supplied default silently (`false`/`0`/empty list). |
| Wrong type | Accessor returns default; WARN logged once (dedupe via `Set<String>`). Format: `[SourbyCraft] config key '<path>' invalid type '<actual>', expected <expected> — using default <fallback>`. |
| `ymlEntityTypeMap` bad entry | WARN per invalid entry (first occurrence only), skip entry, continue parsing rest. |
| `EntityType.valueOf` fails | Treated as bad entry (above). |
| Hot-reload | Not supported. Yml header documents restart requirement. |

No new exception types. All logging routed through the existing `SourbyCraft.LOGGER` channel.

## Testing

| Layer | Coverage |
|---|---|
| Unit (C6) | Each new accessor: happy path, missing key, wrong type, dedupe, list/map filtering. |
| Integration (C5) | Reference patches gated by config toggles, end-to-end packet observation. |
| CI gate | Existing `.github/workflows/` gates on `patches/`, `release/`, `paperRef`, `test-harness/`. Add `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` and `patches/server/0034-*` to the gate. |
| Boot smoke | After patches apply, full clean boot of single mojmap jar with default config must reach `Done` within the same window as the pre-Foundation baseline (no boot regression). |

## Out of scope

Foundation explicitly does NOT cover:

1. Categories 01-08 — each is a separate spec → plan → impl cycle. Foundation ships only 2 reference keys from `particles`.
2. Hot-reload command (`/sc reload`). Restart required for config changes.
3. Config schema versioning / migration. Future sub-spec that breaks layout owns its own migration.
4. Per-world overrides. UniverseSpigot has per-world variants for some keys; Foundation treats all keys as global. Per-world support is deferred to whichever sub-spec needs it.
5. In-game config viewer (`/sc config <key>`). Operator reads the yml directly.
6. Multi-profile presets (safe / balanced / aggressive). Rejected in prior brainstorm. Single yml only.
7. Generic NMS shim API. Rejected. Patches access NMS directly per the existing pattern.
8. Variant jars (mojmap + reobf). Single mojmap jar per 2026-06-04 revision.
9. `ymlGet` deprecation or replacement. The existing API is preserved. Foundation only adds new accessors.
10. Async / parallel config load. Synchronous boot-time load only.

## Acceptance criteria

After Foundation lands, all of the following hold:

| Check | Command | Expected |
|---|---|---|
| New accessors exist | `grep -c 'public static.*yml\(Double\|StringList\|EntityTypeMap\)' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` | `3` |
| Reference patch present | `ls patches/server/0034-us-particles-fall-death.patch` | file exists |
| Yml schema has particles section | `grep -c '^particles:' sourbycraft-server/src/main/resources/sourbycraft.yml` | `1` |
| Patch template doc present | `ls docs/superpowers/notes/2026-06-04-us-gated-patch-template.md` | file exists |
| Unit tests pass | `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigTest` | green |
| Integration test passes | `./gradlew :sourbycraft-server:nmsCompatTest -PrunNmsCompat=true` | green |
| Plugin compat regression: r1 baseline preserved | Re-run compat matrix with default config | no new failures vs r1 |
| Brand identity preserved | `grep 'isBrandCompatible' patches/server/0003-Changed-branding.patch` | unchanged from current |
| No new patch above ~150 lines | `wc -l patches/server/0034-*.patch` | `< 150` |

## Phases (handed to writing-plans)

Suggested phase breakdown for the implementation plan:

1. **Phase 1 — Accessor expansion + tests**: add `ymlDouble`, `ymlStringList`, `ymlEntityTypeMap` to `SourbyCraftConfig`. Write unit tests. Land as one commit.
2. **Phase 2 — Patch template doc**: write `docs/superpowers/notes/2026-06-04-us-gated-patch-template.md`. One commit.
3. **Phase 3 — Yml schema + reference patch**: append `particles:` section to `sourbycraft.yml`. Write `patches/server/0034-us-particles-fall-death.patch`. One or two commits.
4. **Phase 4 — Smoke test extension**: extend `:nmsCompatTest` with the particle-suppression case. One commit.
5. **Phase 5 — CI gate update**: add accessor file path and `patches/server/0034-*` glob to the existing CI gate. One commit.

writing-plans owns the detailed step decomposition.

## Out-of-scope reminders (next sub-spec candidates)

After Foundation lands, the natural next sub-spec is **01 — particles + sounds + misc** (lowest risk, pure toggles). It uses Foundation's accessors and patch template, validates the pattern at category scope (~30 keys), then unblocks 02-08.
