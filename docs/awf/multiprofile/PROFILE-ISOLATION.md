# Profile isolation

## Captured categories

| Category | Key | What is stored |
|---|---|---|
| Inventory | `isolation.inventory` | main inventory slots, opaque item bytes |
| Armor/off-hand | `isolation.armor` | 4 armor slots and off-hand |
| Ender chest | `isolation.ender-chest` | 27 slots, opaque item bytes |
| Experience | `isolation.experience` | level and progress |
| Health | `isolation.health` | current health (applied as `min(stored, max health)`) |
| Food | `isolation.food` | food level and saturation |
| Potion effects | `isolation.potion-effects` | type key, duration, amplifier, ambient/particles/icon |
| Location | `isolation.location` | respawn location and last location |

A category that is disabled is neither captured nor applied. The data then stays in vanilla
`playerdata`; this is a documented limitation, not a silent half-isolation.

## Not captured

Advancements, statistics, recipe books, plugin-owned inventories and any plugin data outside the
record are **not** isolated by the core. A plugin registers a `ProfileStateAdapter` (see `API.md`) to
store its own versioned blob.

`playerdata/*.dat` is untouched. The engine does not modify vanilla player-data storage.

## Item encoding

- Items are encoded with Paper's `ItemStack.serializeAsBytes()` and decoded with
  `ItemStack.deserializeBytes(...)`. Data components survive; there is no lossy conversion through
  YAML or a legacy `ItemMeta` copy.
- Decoding happens only after the snapshot frame and the snapshot checksum verified, and only through
  that API; no Java object graph is deserialised from the payload.
- Bounds: 1 MiB per item, 8 MiB per snapshot, 256 items per section, 64 effects, 32 adapters, 2 MiB per
  adapter. The codec rejects out-of-range lengths before allocating.

## Freeze semantics

There is no "player frozen" flag. Capture and apply each run as **one** `EntityScheduler` task for the
player. Gameplay cannot interleave with the read or the write because both happen inside that single
task on the owning region. The world side is serialised by AWF's own operation gate.

When a player is removed, the entity scheduler retires: the capture/apply future completes
exceptionally, the switch rolls back, and nothing blocks. The retired callback only completes a future;
it never touches a world, a chunk or Redis.

## Adapters

- Registered per plugin via `AuroraProfiles.registerProfileStateAdapter`.
- Stored under `id:version`. A version bump invalidates old bytes instead of applying them to a new
  format; the adapter is skipped with one warning.
- A capture failure is logged and that adapter is skipped; it does not abort the switch.
- An apply failure is logged and the switch continues; the core state is already applied.
