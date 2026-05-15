# SourbyCraft Antixray Engine — Design Spec

**Date:** 2026-05-15  
**Status:** Approved  
**Approach:** C — hybrid wrapper, extend Paper engine via SourbyCraft controller

---

## §1 — Architecture

Paper's `ChunkPacketBlockControllerAntiXray` tetap utuh. SourbyCraft wrapper overlay dua layer: block obfuscation extension + entity obfuscation.

### Components

| Layer | Location | Role |
|-------|----------|------|
| Paper engine | `ChunkPacketBlockControllerAntiXray` | Base ore hiding logic, SourbyCraft extension points inserted directly |
| SourbyCraft extension | Same file, guarded by `SourbyCraftWorldConfig` fields | `solidGlobal[]` + `obfuscateGlobal[]` extended; entity filter |
| Config | `SourbyCraftWorldConfig` | Per-world antixray fields |

### Flow

```
Chunk packet:
  → Paper engine obfuscate ore (existing)
  → SourbyCraft extension: allBlocks → mark all blocks as obfuscate target
  → SourbyCraft extension: fluidObscures → mark static water/lava as solid
  → Packet sent

Entity packet:
  → Intercept at ServerEntity level
  → Ray-trace player→entity: if solid block in path → drop packet
  → If clear path → send normal
```

---

## §2 — Fluid Obscures

### 2.1 Behavior
Static water and lava become solid blockers. Ores/entities behind them stay hidden. Flowing water/lava remain transparent (same as existing `lavaObscures` behavior).

### 2.2 Implementation
In `ChunkPacketBlockControllerAntiXray` constructor — after existing solid logic, wrapped in `if (SourbyCraftWorldConfig...)`:
```java
if (worldConfig.fluidObscures) {
    solidGlobal[waterIndex] = true;
    solidGlobal[lavaIndex] = true;
}
```
Only `defaultBlockState()` (stationary), not flowing variants.

### 2.3 Config
`SourbyCraftWorldConfig.fluidObscures` — boolean, default `true`

---

## §3 — All Blocks Mode

### 3.1 Behavior
When enabled, ALL blocks except air variants become obfuscation targets (not just the 23 ore types). Underground appears as solid stone from x-ray perspective. Only blocks directly adjacent to air/transparency are visible.

### 3.2 Implementation
Override `obfuscateGlobal[]` population — when `allBlocks` is true:
- Mark EVERY block state (except `Blocks.AIR`, `Blocks.CAVE_AIR`, `Blocks.VOID_AIR`) as obfuscation target
- Same 3-layer flood-fill algorithm handles showing only exposed blocks

### 3.3 Config
`SourbyCraftWorldConfig.allBlocks` — boolean, default `false`

---

## §4 — Entity Obfuscation

### 4.1 Behavior
Entities behind solid blocks are not sent to the client. Entities with clear line-of-sight to the player are sent normally.

### 4.2 Line-of-Sight Check
```
Ray-trace from player eye position to entity position.
If any solid block intersects → entity hidden.
If clear path → entity visible.
```
Uses `Level.clip()` or `BlockGetter.clip()` with `CollisionContext.of(entity)`.

### 4.3 When to Check
| Trigger | Action |
|---------|--------|
| Entity spawns | Check before sending `AddEntity` packet |
| Entity moves to new chunk | Re-check |
| Player moves to new chunk | Re-check all entities in range |
| Entity is damaged by player | Always send (no check) — combat integrity |
| Tamed entity (owner == player) | Always send — owner sees pets |
| Entity > `entityObfuscationRange` away | Skip check, send normally |

### 4.4 Interception Point
In `ServerEntity` / entity tracking — before `sendPairingData()` or packet write. Permission `paper.antixray.bypass` also bypasses entity obfuscation. Spectator players see all entities.

### 4.5 Config
- `SourbyCraftWorldConfig.entityObfuscation` — boolean, default `true`
- `SourbyCraftWorldConfig.entityObfuscationRange` — int, default `64` (blocks)

---

## §5 — Config — sourbycraft-world.yml

```yaml
anticheat:
  anti-xray:
    fluid-obscures: true
    all-blocks: false
    entity-obfuscation: true
    entity-obfuscation-range: 64
```

---

## §6 — Files Summary

| File | Change |
|------|--------|
| `SourbyCraftWorldConfig.java` | New fields: `fluidObscures`, `allBlocks`, `entityObfuscation`, `entityObfuscationRange` |
| `ChunkPacketBlockControllerAntiXray.java` | Guarded blocks: extend `solidGlobal[]` (fluid obscures), extend `obfuscateGlobal[]` (all blocks) |
| `Level.java` | Pass `SourbyCraftWorldConfig` to controller constructor |
| `ServerEntity.java` / entity tracking | Intercept: check line-of-sight before sending entity packets |
| Patch file `patches/minecraft/0033-*.patch` | Minecraft source modifications |
