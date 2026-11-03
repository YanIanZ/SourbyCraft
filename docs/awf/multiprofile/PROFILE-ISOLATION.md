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
of the active profile is therefore also in vanilla playerdata, which the server saves at its own autosave and
quit; a crash after a switch's commit and before that save is not covered (PM-028, MP-COMMIT-VANILLA). Since
Build 53, with `login.authoritative: true`, a join restores the committed profile state rather than trusting
that file ([PROFILE-LIFECYCLE.md](PROFILE-LIFECYCLE.md#authoritative-login-build-53)); in a co-op the member head is
the only source of a member's state and vanilla playerdata is never read for it.

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
player, for players holding a session claim, and written as a snapshot of the active profile (Build 53: the
capture carries a ticket bound to the session generation, the active profile and, in a co-op, the membership
epoch, so it can only be saved to the profile and member it was taken from). Nothing is
written for a player who quits during a switch, a recovery or the join-return teleport (their live state
stays in vanilla playerdata) or for a player whose session claim was taken over by another process. At shutdown
Build 53 captures and saves the online players before the server removes them, bounded by
`shutdown.capture-deadline-ms` ([PROFILE-LIFECYCLE.md](PROFILE-LIFECYCLE.md#shutdown-capture-build-53)); in
Build 52 nothing was written at shutdown (feature patch 0081 closed MultiProfile first).

## Adapters

- Registered per plugin via `AuroraProfiles.registerProfileStateAdapter`. Refused with
  `IllegalArgumentException`: a malformed id, a version below 1, an `id:version` key over 64 characters,
  an id another enabled plugin holds, the reserved id `sourbycraft.required-adapters`, and a 32nd id (31 plugin
  adapters; the snapshot's 32nd entry is the server's record of which stored entries were REQUIRED); a plugin that is not enabled gets
  `ProfileOperationException(INVALID_REQUEST)`. A plugin may replace its own adapter; `version()` is
  read once at registration.
- Stored under `id:version`. Data stored under an *older* version than the registration is offered to
  `ProfileStateAdapter.migrate(fromVersion, data)` (Build 53; default: unsupported) and applied when it converts;
  otherwise, and for data under a *newer* version, it is not applied (one warning) and stays in the profile until
  the adapter stores data at its current version.
- Carry-forward: entries of adapters not registered on this boot, or registered at another version
  that stored nothing, are kept in new snapshots within the same limits (unknown adapter blobs are preserved
  verbatim, never decoded).
- The rest depends on the adapter's criticality, below. An adapter written before Build 53 is BEST_EFFORT.

### Adapter contract (Build 53)

Build 53 code written; not tested by the owner. `ProfileStateAdapter.descriptor()` returns a
`ProfileAdapterDescriptor(id, version, scope, criticality, maxBytes)`; the default is `BEST_EFFORT`,
`PROFILE_SCOPED`, the adapter's own id and version and the 2 MiB server cap, so an existing adapter compiles and
behaves as before (binary compatible: `descriptor()` is a `default` method). It is read once at registration; a
descriptor whose id or version differs from `id()` / `version()`, or that throws, refuses the registration with
`IllegalArgumentException`.

| | REQUIRED | BEST_EFFORT |
|---|---|---|
| capture throws, over `maxBytes`/2 MiB, or past the 6 MiB aggregate | the switch is refused or journaled for recovery (`ADAPTER_REQUIRED_FAILED`); never skipped | skipped for this capture with a warning; the bytes already stored for the adapter at that version are **preserved** |
| capture returns `null` or empty | nothing to store: the stored blob for this adapter is removed (an explicit answer, not a failure) | same |
| capture durable before the target is active | yes: the adapter's bytes are in the saved snapshot before `APPLYING_TARGET`; a save that did not confirm refuses the switch | best effort |
| apply throws | the switch is not reported successful; the journal is `RECOVERING` or the switch rolls back to the journal's rollback point | logged, the switch continues (the core state is applied) |
| adapter not registered here, registered with scope `UNSUPPORTED`, or registered at an *older* version than the data, while the profile holds its data | `ADAPTER_MISSING` naming the key and versions: the switch or join is refused and nothing is written; the data is kept untouched | the data is carried forward and not applied |
| adapter registered at a *newer* version than the data | `migrate(fromVersion, data)` is called before anything is applied; `null`/empty or a throw refuses the switch or join with `ADAPTER_REQUIRED_FAILED` naming adapter and both versions | `migrate` is tried; a failure is logged, the data is carried forward and not applied |
| criticality of stored data | recorded in the snapshot (the reserved `sourbycraft.required-adapters:1` entry, carried by Build 52 as an unknown blob): an entry stored as REQUIRED stays REQUIRED for every later apply, whether or not the plugin is still registered | an entry stored as BEST_EFFORT becomes REQUIRED when the registration or `adapters.required` says so now |
| result counted as success | only when capture was durable and apply returned | when the core state committed |

The aggregate budget (31 plugin adapters plus the reserved record, 2 MiB each, 6 MiB together, 8 MiB per snapshot,
64-character key) is otherwise unchanged. `capture()` and `apply()` run synchronously on the owning entity thread
(the region shutdown thread at shutdown) with no enforceable timeout; `migrate()` runs on a server I/O thread
without a player. If REQUIRED adapters and carried-forward unknown blobs together would exceed it, the capture is
refused (`ADAPTER_REQUIRED_FAILED`) instead of dropping required or preserved bytes. A registration change during a
switch invalidates the adapter set the capture used and the switch is retried or recovered, not completed.

`scope` is declared metadata: `PROFILE_SCOPED` data is captured and applied per profile; `ACCOUNT_WIDE` data is
the same in every profile and is not swapped by the server; `UNSUPPORTED` states that the plugin's state cannot be
isolated per profile. The server does not verify the declaration.

`adapters.required` (a list of adapter ids, RESTART_REQUIRED) raises a BEST_EFFORT adapter to REQUIRED; it cannot
lower a REQUIRED one. An id in the list that no plugin registers does nothing until a plugin registers it, but a
profile that holds data stored under that id then refuses a switch or join (`ADAPTER_MISSING`) while the plugin
is absent.

Adapters must be idempotent. A REQUIRED apply may run again during recovery or authoritative login; an adapter
whose apply has additive side effects (granting an item, paying out) cannot take part safely without its own
operation receipt, and should be BEST_EFFORT or `UNSUPPORTED`.
