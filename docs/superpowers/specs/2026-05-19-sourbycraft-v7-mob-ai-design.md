# SourbyCraft v7 — DAB Tuning & Mob AI Fix

2026-05-19 | `feat: v7 — configurable DAB, wire dead mob config, per-entity AI tuning`

## §G — Goal

Upgrade SourbyCraft dari v6-REL ke v7-REL: fix mob AI delay, mobs jarang spawn, natural movement. Focus pada DAB (Dynamic Activation of Brains) config system + wire dead config fields.

## §1 — DAB Config System

**Config di `sourbycraft.yml`:**

```yaml
dab:
  enabled: true
  max-tick-freq: 20             # max brain ticks per second
  activation-dist-mod: 8         # distance divisor: tickFreq = min(max, dist >> mod)
  start-distance: 12             # blocks before DAB activation kicks in
  
  entity-overrides:
    minecraft:zombie:
      max-tick-freq: 20
      activation-dist-mod: 4
    minecraft:skeleton:
      max-tick-freq: 20
      activation-dist-mod: 6
    minecraft:creeper:
      max-tick-freq: 15
      activation-dist-mod: 8
    minecraft:villager:
      max-tick-freq: 20
      activation-dist-mod: 4
    minecraft:spider:
      max-tick-freq: 20
      activation-dist-mod: 5
    minecraft:enderman:
      max-tick-freq: 20
      activation-dist-mod: 6
    minecraft:witch:
      max-tick-freq: 20
      activation-dist-mod: 6
    minecraft:iron_golem:
      max-tick-freq: 10
      activation-dist-mod: 8
```

**`SourbyCraftConfig.java` — new fields:**
```java
public static boolean dabEnabled = true;
public static int dabMaxTickFreq = 20;
public static int dabActivationDistMod = 8;
public static int dabStartDistance = 12;
public static final Map<String, int[]> dabEntityOverrides = new ConcurrentHashMap<>();
// key: entity type ID (e.g. "minecraft:zombie"), value: [maxTickFreq, activationDistMod]
```

**Config reading in `init()`:**
```java
dabEnabled = getBoolean("dab.enabled", dabEnabled);
dabMaxTickFreq = getInt("dab.max-tick-freq", dabMaxTickFreq);
dabActivationDistMod = getInt("dab.activation-dist-mod", dabActivationDistMod);
dabStartDistance = getInt("dab.start-distance", dabStartDistance);
// Entity overrides loaded from config section:
ConfigurationSection overrides = config.getConfigurationSection("dab.entity-overrides");
if (overrides != null) {
    for (String key : overrides.getKeys(false)) {
        int freq = overrides.getInt(key + ".max-tick-freq", dabMaxTickFreq);
        int mod = overrides.getInt(key + ".activation-dist-mod", dabActivationDistMod);
        dabEntityOverrides.put(key, new int[]{freq, mod});
    }
}
```

## §2 — Wire DAB Config ke Game Logic

**Patch modifications:**
- `Mob.java` `checkBrainTick()`: baca `dabMaxTickFreq` + override per entity type dari `SourbyCraftConfig`
- `ActivationRange.java`: priority calculation pakai config values, bukan hardcoded
- `Entity.java`: `activatedPriority` init value dari config `dabMaxTickFreq`

**Logic:**
```
checkBrainTick():
  baseFreq = getEntityTypeOverride() ?? SourbyCraftConfig.dabMaxTickFreq
  distMod = getEntityTypeOverride() ?? SourbyCraftConfig.dabActivationDistMod
  priority = max(1, min(squaredDistance >> distMod, baseFreq))
  return gameTime >= pufferfish$brainTick
```

## §3 — Wire Dead Config Fields

**`mobTickDistance`** (default 32):
- Wire ke `ServerChunkCache` — batasi entity ticking hanya dalam radius dari player
- Wire ke `NaturalSpawner` — batasi spawn attempts dalam radius

**`mobPathfindInterval`** (default 20):
- Wire ke `GoalSelector.inactiveTick()` — ganti hardcoded 20 dengan config value

## §4 — Version Bump v6→v7

| File | Change |
|------|--------|
| `gradle.properties` | `version = v7-REL`, `releaseVersion = 7` |
| `SourbyCraftConfig.java:24` | `currentVersion = 7` |
| `SourbyCraftConfig.java:39` | `swmVersion = "v7-REL"` |
| `SourbyCraftConfig.java:133-140` | New migration: `v6-REL` → `v7-REL` |
| `sourbycraft-swm-api/build.gradle.kts:30` | `v6-REL.jar` → `v7-REL.jar` |
| `README.md` | Badge, versions |
| Git tag | `v7-REL` |

## §V — Invariants

1. **V-DAB-CONFIG**: Semua DAB parameter (maxTickFreq, activationDistMod, startDistance) via `sourbycraft.yml`. Per-entity overrides supported.
2. **V-WIRED**: `mobTickDistance` wired ke entity ticking radius. `mobPathfindInterval` wired ke goal selector throttle.
3. **V-SPAWN**: Async mob spawning (Pufferfish) tidak broken oleh perubahan DAB. Spawn rate tetap normal.
4. **V-FALLBACK**: Entity tanpa override pakai global default. Unknown entity types tidak crash.
5. **V-UPGRADE**: Config migration v6→v7 updates `swmVersion` key, backward compatible.
