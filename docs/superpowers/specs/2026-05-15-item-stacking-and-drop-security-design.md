# Item Stacking & Drop Security — Design Spec

**Date:** 2026-05-15  
**Status:** Approved  
**Approach:** A — simple patch with SourbyCraftConfig

---

## §1 — Max Stack Size 64 → 99

### 1.1 Scope
Only items that currently default to `MAX_STACK_SIZE = 64` are affected.
Items with other limits remain unchanged:

| Category | Current | New |
|----------|---------|-----|
| Default blocks/items (dirt, cobble, wood...) | 64 | 99 |
| 16-stack items (ender pearl, snowball, egg, bucket) | 16 | 16 |
| 1-stack non-tools (bed, minecart, boat, potion) | 1 | 1 |
| Durability items (tools, armor, weapons) | 1 | 1 |

### 1.2 Implementation
- **File:** `sourbycraft-server/src/minecraft/java/net/minecraft/world/item/Item.java`
  - Change `DEFAULT_MAX_STACK_SIZE` from `64` to `99` (line 109)
- **File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
  - Add field: `itemMaxStackSize` (int, default 99, range 1–99)
  - On config load: set `Item.DEFAULT_MAX_STACK_SIZE` via reflection or public setter

### 1.3 Constraints
- `ABSOLUTE_MAX_STACK_SIZE = 99` already exists in Item.java (line 110) — no change needed
- `DataComponents.MAX_STACK_SIZE` range is already `ExtraCodecs.intRange(1, 99)` — compatible
- No data migration needed — existing inventories unaffected (count stays same)

---

## §2 — Drop Stack Unlimited

### 2.1 Target
Items dropped on the ground merge without the `maxStackSize` constraint.
Example: 10 Dirt x99 dropped together → one ItemEntity with Dirt x990.

### 2.2 Implementation
**File:** `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/item/ItemEntity.java`

| Method | Line(s) | Change |
|--------|---------|--------|
| `isMergable()` | 283–286 | Remove `item.getCount() < item.getMaxStackSize()` check. Always return true for the stack-size part. |
| `areMergable()` | 300–303 | Replace `count1 + count2 <= maxStackSize` with `count1 + count2 <= dropStackCap` (config cap, default no cap). |
| `merge(ItemEntity, ItemStack, ItemStack)` | 305–307 | Replace hardcoded `64` with `item.getMaxStackSize()` or remove cap entirely for drop context. |

### 2.3 Config
**File:** `SourbyCraftConfig.java`
- `unlimitedDropStack` (boolean, default `true`) — enable unlimited merging on ground
- `dropStackCap` (int, default `Integer.MAX_VALUE`) — optional hard cap if needed

### 2.4 Edge Case: Inventory Pickup
When player picks up item entity with count > 99:
- Fill inventory slots up to maxStackSize per slot
- Remaining overflow stays on ground in same ItemEntity
- Standard Minecraft pickup overflow behavior — no special handling needed beyond existing

---

## §3 — Anti-Snatch (Owner-Only Pickup Timer)

### 3.1 Target
When a player drops an item, only that player can pick it up for a configurable grace period (default: 10 seconds). After the timer expires, the item becomes public.

### 3.2 Implementation
**File:** `sourbycraft-server/src/minecraft/java/net/minecraft/world/entity/item/ItemEntity.java`

**New field:**
```java
@Nullable
private UUID ownerUUID;
```

**Drop path** — in `ServerPlayer.drop(ItemStack, boolean, boolean)` (primary item drop method), right after `ItemEntity` is created and before `level().addFreshEntity()`:
- Set `itemEntity.ownerUUID = player.getUUID()`
- Set `itemEntity.pickupDelay = ownerProtectionTime * 20` (converted to ticks)

**Pickup check** — in `ItemEntity.playerTouch(Player)` before the item transfer logic:
```
if (ownerProtectionEnabled && pickupDelay > 0 && ownerUUID != null && !player.getUUID().equals(ownerUUID)):
    return  // block pickup silently
```

**Merge behavior:**
- When two ItemEntities merge, the destination entity inherits the source's `ownerUUID` and `pickupDelay`

### 3.3 Config
**File:** `SourbyCraftConfig.java`
- `ownerProtectionEnabled` (boolean, default `true`) — enable/disable anti-snatch
- `ownerProtectionTime` (int, default `10`) — grace period in seconds

### 3.4 Edge Cases

| Scenario | Behavior |
|----------|----------|
| Owner disconnects | Item stays protected until timer expires |
| Owner reconnects | Can pick up item immediately (same UUID) |
| Owner dies (`/kill`) | Item stays protected until timer expires |
| Non-owner tries pickup during protection | Blocked silently |
| Protected timer expires | Item becomes public, anyone can pick up |
| Item merge (2 items from same owner) | Destination keeps ownerUUID, timer = max(both timers) |
| Item merge (2 items from different owners) | Destination keeps owner of the larger stack; if equal count, keeps destination's owner |
| Item merge (both have null owner) | Normal merge, no owner change |
| Naturally spawned item (mob drop, chest loot) | ownerUUID = null, no protection applies |

---

## §4 — Files Changed Summary

| File | Module | Change |
|------|--------|--------|
| `Item.java` | sourcycraft-server | `DEFAULT_MAX_STACK_SIZE` 64 → 99 |
| `ItemEntity.java` | sourcycraft-server | `isMergable()`, `areMergable()`, `merge()` — remove maxStackSize cap; add `ownerUUID` field; add pickup gate |
| `SourbyCraftConfig.java` | sourcycraft-server | Add fields: `itemMaxStackSize`, `unlimitedDropStack`, `dropStackCap`, `ownerProtectionEnabled`, `ownerProtectionTime` |
| Drop spawn logic | sourcycraft-server | Set `ownerUUID` on ItemEntity when player drops item |
| Patch file | patches/server/ | New or updated patch for ItemEntity changes |

---

## §5 — Config Defaults (sourbycraft.yml)

```yaml
item:
  max-stack-size: 99
  unlimited-drop-stack: true
  drop-stack-cap: 2147483647  # Integer.MAX_VALUE
  owner-protection-enabled: true
  owner-protection-time: 10   # seconds
```
