# Profile isolation

The Build 50 PR #62 changes on this page (storage-only inventory, per-profile respawn point,
destination order, join return, effect and adapter limits, the freeze listener, the quit snapshot) are
code written and statically reviewed; not built; not tested by the owner, and not in the published
`v26.2-r50` prerelease.

## Captured categories

| Category | Key | What is stored |
|---|---|---|
| Inventory | `isolation.inventory` | the 36 hotbar and storage slots, opaque item bytes |
| Armor/off-hand | `isolation.armor` | 4 armor slots and off-hand; with `isolation.armor: false`, armor and off-hand stay with the player across switches |
| Ender chest | `isolation.ender-chest` | 27 slots, opaque item bytes |
| Experience | `isolation.experience` | level and progress |
| Health | `isolation.health` | current health (applied as `min(stored, max health)`) |
| Food | `isolation.food` | food level and saturation |
| Potion effects | `isolation.potion-effects` | type key, duration, amplifier, ambient/particles/icon |
| Location | `isolation.location` | respawn point and last location |

A category that is disabled is neither captured nor applied. The data then stays in vanilla
`playerdata`; this is a documented limitation, not a silent half-isolation.

Snapshots written by earlier Build 50 code (the `v26.2-r50` prerelease and other trees before PR #62;
the code calls them pre-Build-50) also hold armor in inventory slots 36–39 and the off-hand in slot 40.
With `isolation.armor` on, those slots serve as armor only when the snapshot has no armor or off-hand
of its own. With it off (and `isolation.inventory` on), they go into free storage slots and the live
armor stays; items that do not fit are reported with a WARN and not restored (they are lost at that
profile's next save).

**Potion effects.** Durations may be infinite (`-1`) or arbitrarily long. An effect with an amplifier
outside 0–255, or an unknown key, is skipped with a warning, and more than 64 effects are truncated
with a warning; none of these fails the capture or the switch. Earlier Build 50 code (the `v26.2-r50`
prerelease) cannot decode a snapshot that holds an infinite effect or one longer than 1,000,000 ticks,
so switching into such a profile fails after a downgrade until it is saved again without that effect.
(MultiProfile does not exist in Build 49.)

**Location.** The respawn point is captured as stored, without validating the bed or anchor block (no
off-region read). On switch-in it is restored as a **forced** respawn point, or cleared when the profile
has none or its world is not loaded (WARN). Forced means that, until the player sets a new respawn
point, a bed or anchor point also works on a free spot after its block is gone, and an anchor needs and
uses no charge. Reason: the snapshot does not record vanilla's forced flag, and a non-forced restore
would break `/spawnpoint`, plugin-forced points and the respawn points earlier Build 50 snapshots
stored (the stand-up position next to the bed); exact fidelity is an open owner decision. Respawn points in worlds whose names are not AWF names (`[a-z0-9_-]{1,48}`) cannot be
stored. The switch moves the player to the last location, then the respawn point, then the spawn of the
profile's PRIMARY bound world, which is also where a profile without a stored location (a new one)
goes. For such a profile, when that spawn is not known, the player stays where they are with a warning
and the switch still succeeds; when a profile's stored destinations all fail and no PRIMARY spawn is
left to try, the switch fails and is rolled back. A fallback to the respawn point teleports to the
stored block position, which is not validated. On join, a player whose active profile has bindings gets those worlds loaded or created;
when the join placed them outside all bound worlds and one of them was unloaded, they are returned to
the profile's last location in them (or its PRIMARY spawn when none is stored), bounded by
`switching.timeout-ms` and only while no journal is pending (details in
[API.md](API.md#build-50-world-binding-api-owner-testing-pending)).

## Not isolated

Advancements, statistics and the recipe book are **not** isolated and are shared by every profile of a
player, whatever the isolation flags say: they stay in the vanilla player files. No built-in adapter
exists, because restoring advancements through Bukkit awards their criteria again, which grants their
rewards and announces them on every switch. Built-in isolation is waiting for the owner's scope decision
(PM-456, MP-ISO-SCOPE-DOC). Also not captured: game mode, attributes (such as maximum health), flight,
the persistent data container, plugin-owned inventories and any plugin data outside the record. A
plugin registers a `ProfileStateAdapter` (see [API.md](API.md)) to store its own versioned data and
decides itself how to restore it.

`playerdata/*.dat` is untouched: the engine does not modify vanilla player-data storage. The live state
of the active profile is therefore in vanilla playerdata, which the server saves at its own autosave and
quit; a crash after a switch's commit and before that save is not covered (PM-028, MP-COMMIT-VANILLA).

## Item encoding

- Items are encoded with Paper's `ItemStack.serializeAsBytes()` and decoded with
  `ItemStack.deserializeBytes(...)`. Data components survive; there is no lossy conversion through
  YAML or a legacy `ItemMeta` copy.
- Decoding happens only after the snapshot frame and the snapshot checksum verified, and only through
  that API; no Java object graph is deserialised from the payload.
- Bounds: 1 MiB per item, 8 MiB per snapshot (`STATE_TOO_LARGE` beyond it), 256 items per section, 64
  effects, 32 adapters, 2 MiB per adapter and 6 MiB for all adapters of a snapshot, and an adapter key
  (`id:version`) of at most 64 characters. The codec rejects out-of-range lengths before allocating.

## Freeze semantics

From the capture until the switch commits or rolls back, the player is frozen: the runtime's freeze
listener cancels item drop, item and experience pickup, inventory click and drag, interact,
interact-entity, block place, consume and hand swap. Damage, death drops, hunger, block breaking,
commands and plugins editing inventories directly fire none of those events and are not held back. The
player is not frozen while the target's world is created or loaded. Capture and apply each still run as
**one** `EntityScheduler` task on the owning region; capture, world readiness, apply and teleport are
bounded by the remaining `switching.timeout-ms`, and an apply that has not started when the switch fails
does nothing.

When a player is removed during a switch, the entity scheduler retires and the capture or apply future
fails; once the target may be applied, the journal stays `RECOVERING` and the next join rolls back. The
retired callback only completes a future; it never touches a world, a chunk or Redis.

**Quit.** The state is captured synchronously inside `PlayerQuitEvent`, on the thread that owns the
player, for players holding a session claim, and written as a snapshot of the active profile. Nothing is
written for a player who quits during a switch, a recovery or the join-return teleport (their live state
stays in vanilla playerdata), for a player whose session claim was taken over by another process, or at
shutdown (feature patch 0081 closes MultiProfile before players are saved and removed).

## Adapters

- Registered per plugin via `AuroraProfiles.registerProfileStateAdapter`. Refused with
  `IllegalArgumentException`: a malformed id, a version below 1, an `id:version` key over 64 characters,
  an id another enabled plugin holds, and a 33rd id; a plugin that is not enabled gets
  `ProfileOperationException(INVALID_REQUEST)`. A plugin may replace its own adapter; `version()` is
  read once at registration.
- Stored under `id:version`. Data stored under another version is not applied (one warning) and stays
  in the profile.
- A capture skips an adapter that throws, returns more than 2 MiB, or would push the adapters of the
  snapshot past 6 MiB, with a warning; the switch continues.
- Carry-forward: entries of adapters not registered on this boot, or registered at another version
  that stored nothing, are kept in new snapshots within the same limits. Data of an adapter registered
  at the version it was stored with is not kept when that adapter returns nothing, throws or exceeds a
  limit in this capture.
- An apply failure is logged and the switch continues; the core state is already applied.
