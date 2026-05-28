# SourbyCraft v6 — Item Entity Pooling

2026-05-19 | `feat: ItemEntity pooling — GC-free item drop system`

## §G — Goal

Rewrite item drop system dengan ItemEntity pooling: pre-allocate entities, reuse instead of create/destroy, zero GC pressure, integrate dengan existing merge/anti-snatch/despawn system.

## §1 — ItemEntityPool Core

**Class:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/item/ItemEntityPool.java`

**Design:**

```java
public final class ItemEntityPool {
    private final Map<ResourceKey<Level>, ConcurrentLinkedDeque<ItemEntity>> freeLists;
    private final Map<ResourceKey<Level>, AtomicInteger> activeCounts;
    private int poolSize;
    private int maxGrowth;

    // Acquire from pool or create new
    public ItemEntity acquire(ServerLevel level, Vec3 pos, ItemStack stack, UUID owner);

    // Return to pool: hide entity, reset state, add to free list
    public void release(ItemEntity entity);

    // Pre-allocate pool for a level
    void preallocate(ServerLevel level, int count);

    // Shrink idle entities beyond threshold
    void shrink(ServerLevel level);
}
```

**State machine:** `FREE (hidden, in pool) → ACTIVE (in world, ticking) → EXPIRED (returning) → FREE`

**Thread safety:** `ConcurrentLinkedDeque` per free list. Lock-free acquire/release.

**release() actions:**
1. `entity.setRemoved(RemovalReason.DISCARDED)`
2. `entity.unRide()`
3. Reset position, motion, item stack, owner, pickup delay, age
4. Clear passengers
5. Push to free list

**acquire() actions:**
1. Pop from free list, or `new ItemEntity(level)` if empty
2. `entity.setPos(pos)`, `entity.setItem(stack)`
3. Set owner UUID, pickup delay from config
4. `entity.setRemoved(null)` — un-remove
5. `level.addFreshEntity(entity)`
6. If activeCount exceeds maxGrowth, log warning and create new anyway

## §2 — Integration Patches

**Patch:** `patches/minecraft/0025-item-entity-pooling.patch`

| Minecraft Class | Method | Change |
|----------------|--------|--------|
| `ServerPlayer.java` | `drop(ItemStack, all)` | `new ItemEntity` → `ItemEntityPool.instance().acquire(level, vec3, stack, uuid)` |
| `ServerPlayer.java` | `createItemStackToDrop` | death drops → pool.acquire |
| `ServerPlayerGameMode.java` | `destroyBlock` | block break → pool.acquire |
| `LivingEntity.java` | `dropEquipment` | entity death → pool.acquire |
| `AbstractContainerMenu.java` | `removed` | container drop → pool.acquire |
| `FishingHook.java` | `retrieve` | fishing loot → pool.acquire |
| `ItemEntity.java` | `tick()` | despawn path: `this.discard()` → `ItemEntityPool.instance().release(this)` |
| `ServerLevel.java` | constructor | `ItemEntityPool.instance().preallocate(this, config.itemPoolSize)` |

**Existing features preserved:**
- Anti-snatch (`ownerUUID`, `pickupDelay`) — set during acquire
- Unlimited stack (`unlimitedDropStack`) — unchanged
- Merge logic (`isMergable`, `areMergable`, `merge`) — unchanged
- Despawn timer (`despawnRate`) — unchanged
- Anti-clip — unchanged

## §3 — Config

**File:** `SourbyCraftConfig.java`

New config fields:
```java
public static boolean itemPoolEnabled = true;
public static int itemPoolSize = 256;
public static int itemPoolMaxGrowth = 1024;
public static double itemPoolShrinkThreshold = 0.5;
public static int itemMaxPerChunk = 50;
public static int itemMaxPerPlayer = 100;
```

Config reads in `init()`:
```java
itemPoolEnabled = getBoolean("item.pool-enabled", itemPoolEnabled);
itemPoolSize = getInt("item.pool-size", itemPoolSize);
itemPoolMaxGrowth = getInt("item.pool-max-growth", itemPoolMaxGrowth);
itemPoolShrinkThreshold = getDouble("item.pool-shrink-threshold", itemPoolShrinkThreshold);
itemMaxPerChunk = getInt("item.max-per-chunk", itemMaxPerChunk);
itemMaxPerPlayer = getInt("item.max-per-player", itemMaxPerPlayer);
```

**sourbycraft.yml defaults:**
```yaml
item:
  pool-enabled: true
  pool-size: 256
  pool-max-growth: 1024
  pool-shrink-threshold: 0.5
  max-per-chunk: 50
  max-per-player: 100
```

## §V — Invariants

1. **V-POOL**: Semua ItemEntity creation melalui `ItemEntityPool.acquire()`. Tidak ada `new ItemEntity()` langsung.
2. **V-RELEASE**: Semua ItemEntity destruction melalui `ItemEntityPool.release()`. Tidak ada `discard()` langsung.
3. **V-STATE**: ItemEntity dari pool tidak boleh reference stale world/position. Release harus reset semua state.
4. **V-OWNER**: Anti-snatch owner UUID tetap bekerja — set saat acquire, tidak di-reset oleh pool recycle.
5. **V-LIMIT**: `max-per-chunk` dan `max-per-player` di-enforce saat acquire. Oldest item didespawn jika limit exceeded.
