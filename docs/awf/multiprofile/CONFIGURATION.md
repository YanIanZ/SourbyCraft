# Configuration

Both features live in `sourbycraft_config/aurora.yml` (Build 49 layout). Every key is
**RESTART_REQUIRED**: the repository, ownership provider, pools and consumers are created once at
startup and cannot be resized or moved by `/sourbycraft reload`. Keys are read early, before the
configuration system loads, through `ProfileConfig.readEarly` / `FabricConfig.readEarly`.

The seeded defaults are in `docs/config-examples/aurora.yml`, and `ConfigExamplesTest` fails if this
table and that file drift.

## `aurora.awf.multiprofile.*`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` | master switch; false creates nothing |
| `mode` | `standalone` | `standalone` or `distributed` (experimental) |
| `default-slots` | `3` | slots for a new account |
| `max-slots` | `8` | hard ceiling, 1–64 |
| `switching.enabled` | `true` | allow switches |
| `switching.timeout-ms` | `15000` | one switch's budget; enforced per phase and on world-ready |
| `switching.require-durable-save` | `true` | refuse a switch without a durable rollback point |
| `isolation.inventory` | `true` | inventory slots |
| `isolation.armor` | `true` | armor and off-hand |
| `isolation.ender-chest` | `true` | ender chest |
| `isolation.experience` | `true` | level and progress |
| `isolation.health` | `true` | health |
| `isolation.food` | `true` | food and saturation |
| `isolation.potion-effects` | `true` | potion effects |
| `isolation.location` | `true` | spawn and last location |
| `worlds.idle-unload-seconds` | `300` | save+unload an idle world; `0` disables (worlds are always loaded on activation, never at boot) |
| `coordination.provider` | `local` | `local` or `redis` |
| `coordination.fencing-enabled` | `true` | fence check before the commit write |
| `coordination.single-active-session` | `true` | reject a duplicate live session |
| `directory` | `awf/profiles` | relative path; no `..`, no absolute path |

## `aurora.awf.redis.fabric.*`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` | master switch; false opens no socket |
| `services.sessions` | `true` | session ownership |
| `services.coordination` | `true` | fenced claims |
| `services.events` | `false` | streams; off until a consumer is deployed (an unconsumed stream reaches retention and refuses publishes) |
| `services.cache` | `false` | metadata cache; off until a consumer uses it |
| `services.routing` | `true` | node directory; MultiProfile registers and heartbeats this node |
| `services.telemetry` | `true` | render aggregate counters in `/awf redis status` |
| `pools.storage` | `8` | connections shared with the AWF world backend |
| `pools.coordination` | `4` | connections for scripts |
| `pools.events` | `2` | connections for streams |
| `pools.control` | `2` | connections for probes/directory/cache versions |
| `cache.max-entries` | `10000` | per node |
| `cache.ttl-seconds` | `60` | bounds missed invalidations |
| `sessions.heartbeat-seconds` | `10` | claim renewal |
| `sessions.ttl-seconds` | `45` | claim lifetime; must exceed the heartbeat |
| `events.retention` | `10000` | approximate stream length |
| `events.batch-size` | `32` | entries per read |
| `events.max-deliveries` | `5` | retries before dead-lettering |
| `events.read-block-ms` | `500` | server-side block per read |
| `coordination.claim-seconds` | `45` | world/profile claim lease |

Connection settings are **not duplicated**. The fabric reuses `aurora.awf.redis.uri`, `key-prefix`,
`timeout-ms`, TLS (`rediss://`), ACL credentials and `SOURBYCRAFT_AWF_REDIS_URI`. When the fabric is
off, the AWF Redis world backend opens its own pool sized by `aurora.awf.redis.pool-size`; when it is
on, it shares `pools.storage`.

## Invalid values

An unusable value falls back to its default on its own and logs one warning naming the key. A fabric
whose every service is disabled is refused at startup. `mode: distributed` is refused unless the
fabric is enabled **and** `coordination.provider: redis`.

## Migration and rollback

There is nothing to migrate for existing AWF data: no world key, store format, template or registry
entry changes. New keys are additive and default to off.

Rollback: set `multiprofile.enabled: false` (and optionally `fabric.enabled: false`) and restart. The
`awf/profiles` directory is left untouched; existing profiles and snapshots stay readable by the
feature when it is re-enabled. Deleting that directory is the only destructive step and it is never
performed automatically.
