# S2 — entity.* + item.* Config Enforcement

**Date:** 2026-07-05
**Status:** Approved (continuous execution authorized)
**Parent effort:** Placeholder-config remediation (S2 of S1–S6). 20+ dead keys
under `entity.*`, `item.*`, `server.idle-timeout`,
`settings.disable-communication-commands` get real logic. Per user direction:
wire, never delete; lightweight (no hot-path cost when at defaults).

## Enforcement strategy — three tiers

### Tier 1: Config bridges (zero hot-path cost)

Spigot/Paper already own mature engines for several keys. Our key becomes the
operator-facing master: at world load, when the SourbyCraft value **differs
from the SourbyCraft compiled default** (i.e. the operator actually changed
it), it is pushed into the per-world Spigot/Paper config field. Same-as-default
→ Spigot/Paper values stay untouched (no silent override).

| Key | Bridged to |
|---|---|
| `entity.item-despawn-rate` | `spigotConfig.itemDespawnRate` |
| `entity.item-merge-radius` | `spigotConfig.itemMerge` |
| `entity.item-merge-optimize` | false → `spigotConfig.itemMerge = 0` (vanilla merge off), true → leave |
| `entity.hopper-batch` | true → `spigotConfig.hopperCheck = max(existing, 4)` (batch every 4 ticks); false → leave |
| `entity.mob-tick-distance` | all 7 `spigotConfig.*ActivationRange` fields capped to the value |
| `entity.redstone-optimize` | true → `paperConfig().misc.redstoneImplementation = ALTERNATE_CURRENT` (unless operator already picked EIGENCRAFT); false → VANILLA |
| `server.idle-timeout` | >0 → `MinecraftServer.setPlayerIdleTimeout` at boot |

### Tier 2: Bukkit-event lag limits (src/main, no NMS)

New `dev.iyanz.sourbycraft.perf.LagLimits` listener + 1 Hz sweeper (idiom:
FallingBlockEntity sweeper from lag-machine P2):

- `entity.max-per-chunk` — CreatureSpawnEvent, NATURAL/SPAWNER reasons only
  (never blocks breeding/plugins/commands): chunk-box living count ≥ cap → cancel.
- `entity.max-specials-per-chunk` — specials = PrimedTnt, ExperienceOrb,
  AreaEffectCloud, EvokerFangs (lag-prone): EntitySpawnEvent gate, same box count.
- `item.max-per-chunk` — ItemSpawnEvent (runs after EntityStacker's LOWEST
  merge pass, priority HIGH): chunk item count ≥ cap → cancel spawn.
- `entity.max-arrows-per-world` — ProjectileLaunchEvent (AbstractArrow): world
  count from a 1 Hz cached sweep; over cap → cancel launch + sweep removes
  oldest grounded (inGround) arrows beyond cap.

All gates count via `world.getNearbyEntities(chunk box)` on the spawn event
only — zero per-tick cost, nothing runs when caps are ≤ 0 (documented as
"disabled" sentinel).

- `item.owner-protection-enabled/time` — PlayerDropItemEvent + death drops:
  `Item#setOwner(player)` (vanilla pickup-target field), scheduled clear after
  `time` seconds. Only the dropper can pick up during the window.
- `item.unlimited-drop-stack` + `item.drop-stack-cap` — become the
  EntityStacker cap source: effective cap =
  `unlimited ? maxStackSize×multiplier : min(dropStackCap, maxStackSize×multiplier)`.
  (Deliberate: NOT wired into ItemStack NBT — the v9 `dynamic-max-stack-size`
  count>99 serialization failure stays dead; stacker amounts are the proven
  safe mechanism in this tree.)

### Tier 3: NMS gates (nested, 1 feature patch)

| Key | Site | Gate |
|---|---|---|
| `entity.tick-rate-limit` + `entity.tick-rate` (Knobs.ENTITY_TICK_RATE finally consumed) | `io.papermc.paper.entity.activation.ActivationRange` (nested) inactive-entity path | when limit on and rate < 20, inactive entities tick 1-in-`(20/rate)` instead of Spigot's fixed cadence. Active entities never throttled (no visible jank) |
| `entity.mob-pathfind-interval` | `PathNavigation.recomputePath()` | min ticks between recomputes per navigator (default 20 = vanilla-ish; floor 1) |
| `item.no-durability-except` | `ItemStack.processDurabilityChange` | enabled → return 0 (no durability loss server-wide; name kept for compat) |
| `settings.disable-communication-commands` | `MsgCommand`/`TeamMsgCommand`/`EmoteCommands` execute lambdas | flag on → failure message "communication commands are disabled", count 0 |

## Explicitly reserved (documented, not silent)

- `item.pool-enabled/pool-size/pool-max-growth/pool-shrink-threshold` — the
  ItemEntityPool engine was removed for the levitation bug (config comment at
  `SourbyCraftConfig.java:262-264` already says so). Keys stay loaded +
  clamped; wiring returns with pool v2. A WARN at boot when `pool-enabled:
  true` states the engine is offline.
- `chunk.async-save-batch` — moved to S5 (async domain).
- `entity.max-redstone-updates-per-tick` — S5 (perf domain, needs redstone
  queue instrumentation).

## Verification (manual TestServer)

1. Boot → `[SourbyCraft] lag-limits active` line lists caps; bridge log lists
   applied per-world overrides.
2. `item-despawn-rate: 1200` → dropped item gone in 60 s.
3. `max-arrows-per-world: 10` → 11th arrow won't launch; grounded arrows culled.
4. `disable-communication-commands: true` → /msg /tell /w /me /teammsg refuse.
5. `no-durability-except: true` → tools take no damage.
6. `pool-enabled: true` → boot WARN "pool engine offline".
