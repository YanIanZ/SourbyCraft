# SourbyCraft configuration — effective 26.2 contract (Build 49 layout)

> **Role:** the effective configuration contract: every file, every key, its default, LIVE or
> RESTART_REQUIRED, the consumer, boot order, and the one-time Build 49 migration.
> **Status:** Active; checked against `config/UtilityKeys.java`, `config/AuroraKeys.java`,
> `config/ConfigMigration.java`, `config/ConfigFormatMigration.java`, `SourbyCraftConfig.java`,
> `aurora/engine/configuration/EngineConfigFiles.java`, `security/SecurityGuards.java` and their tests
> on 2026-10-25, with PR #62 applied. The key tables below are pinned by `UtilityKeysTest`,
> `AuroraKeysTest`, `ConfigExamplesTest` and `SecurityLimitPinTest`.
>
> **Build 50 candidate (PR #62, not in the published `v26.2-r50` prerelease):** the Build 50 changes
> described here (old names no longer read, unknown-key and duplicate-key handling, reload off the
> tick thread, wider pending-restart report, engine files rewritten only on a key change) are code
> written and statically reviewed; not built; not tested by the owner. `v26.2-r50` (`5f5a167`) still
> reads the old names. Owner checks: [Tester Owner](tester%20owner.md) TO-CFG-01…20; per-item
> status: [BUILD-50-PM-MATRIX.md](releases/BUILD-50-PM-MATRIX.md).
>
> Entry point: [docs/architecture/AURORA.md](architecture/AURORA.md). Examples:
> [config-examples/](config-examples/). Rename plan and history:
> [development/CONFIG-FILE-RENAME-PLAN.md](development/CONFIG-FILE-RENAME-PLAN.md).

This document describes **what branch `26.2` consumes with PR #62 applied**; where the published
`v26.2-r50` jar differs, the text says so. It is not a future-layout proposal. LIVE means
`/sourbycraft reload` applies the value; RESTART_REQUIRED means only a new process reads it; "read
early" means the consumer reads the file itself before the configuration stage of
`SourbyCraftBootstrap`, so neither a reload nor the migration of that boot can reach it.

## 1. The files

| File | Owns | Format / loader | Build 49 change |
| --- | --- | --- | --- |
| `sourbycraft_config/sourbycraft_global_config.yml` | console, players, messages, plugin provisioning, updater | YAML (nightconfig + SnakeYAML comments), `SourbyCraftConfig` + `UtilityKeys` | **relaid**: `[console]`, `[players]`, `[messages]`, `[plugins.viaversion]`, `[plugins.protocollib]`, `[updater]`; old names rewritten once at boot; not read since Build 50 (§7) |
| `sourbycraft_config/aurora.yml` | Aurora services: entity, diagnostics, CPU budget, bridge, scheduler budgets, network, world, AWF (with the Redis backend, MultiProfile and the Redis Fabric), AbyssLink | YAML (nightconfig + SnakeYAML comments), `SourbyCraftConfig` + `AuroraKeys`; early readers parse it directly | keys unchanged; header and section comments on a fresh file; Aurora keys left in the global file are moved here once |
| `config/aurora-server.yml` | region engine, server-wide (`GlobalConfiguration`) | YAML (engine `ConfigurationProvider`) | **renamed** from `config/canvas-server.yml`, migrated once |
| `config/aurora-worlds.yml` | region engine, world defaults (`WorldConfig`) | YAML (engine `ConfigurationProvider`) | **renamed** from `config/canvas-worlds.yml`, migrated once |
| `<dimension storage dir>/canvas-patch.yml` | per-world override of `aurora-worlds.yml` | YAML | not renamed (separate plan; see the rename plan §1) |
| `sourbycraft-security.yml` | SEC-1 decode guards, SEC-11 connection admission | YAML (Bukkit `YamlConfiguration`), `SecurityGuards` | keys unchanged; a fresh file now carries a header and per-group comments and is byte-identical to `build-data/sourbycraft-security.yml` |
| `config/paper-global.yml` `threaded-regions` | region scheduler sizing (`threads`, `grid-exponent`, `scheduler`) | Paper | unchanged; `threads: -1` resolves to `aurora.cpu.cores` or every processor |

`/sourbycraft config` prints these locations, the loaded values and the pending restart-only
changes.

### `/sourbycraft reload`

- **Off the tick thread.** The command replies `Reloading configuration...`, then reads and applies
  the files on a SourbyCraft I/O virtual thread (`VirtualExecutor`) and sends the report back through
  `SourbyReply` when it is done. A sender that collects output only while the command runs (an RCON
  client) can miss the report.
- **Refused** while the server is stopping or failed to start (`AuroraRuntime.stopping()`), and when
  the I/O workers are stopped or full; the sender is told why.
- **What it reads:** `sourbycraft_global_config.yml` (reopened if boot could not open it),
  `aurora.yml`, `sourbycraft-security.yml`, `config/aurora-server.yml`, `config/aurora-worlds.yml` and
  each loaded world's `canvas-patch.yml`, then publishes one snapshot.
- **What it writes:** the two `sourbycraft_config` files are never written by a reload (missing keys
  are seeded in memory only); `sourbycraft-security.yml` is written only when it does not exist. An
  engine file is rewritten only when the load adds or removes a key (each change logged, §4); a
  missing engine file or per-world patch file is created.
- **Failure:** when the utility file or `aurora.yml` cannot be parsed, the reload reports it (file and
  near-line), publishes nothing and does not go on to the security and engine files; the running
  values stay. The engine part itself is not all-or-nothing (§4).
- **Report:** `COMPLETE` or `PARTIAL / FAILED`, the number of Aurora LIVE changes applied, the line
  `RESTART_REQUIRED (changes since boot): …` (below) and every error.

**Pending-restart report.** Every RESTART_REQUIRED key of the two `sourbycraft_config` files is
compared against the **boot** snapshot, so a change stays listed after any number of reloads until a
new process starts (reload report and
`/sourbycraft config` → *Pending tracked changes*). Tracked: every RESTART_REQUIRED key of
`UtilityKeys` (`console.style`, `console.gc-advisor`, `players.*`, `plugins.*.auto-provision`,
`updater.enabled`, `updater.check-times`, `updater.check-interval-minutes`) plus `messages.motd`;
every RESTART_REQUIRED key of `AuroraKeys`, which covers chunk-generation metrics, every `aurora.awf.*`
key (MultiProfile and the Redis Fabric included) and AbyssLink; and the six unseeded
`aurora.awf.redis.*` keys. `aurora.cpu.cores`, `aurora.bridge.mode`, `aurora.bridge.thread-checks`,
`aurora.scheduler.*` and `aurora.diagnostics.stall-recorder.*` are compared on their parsed values
(a typo that falls back to the default is not a change; the last two are reported as one entry each);
the others on the value in the file. Not tracked: the engine files' keys (the report says so), an
`updater.apply-mode` change from `off`, and the security file's proxy detection. `/maxp` updates the
boot snapshot as well, so a value it writes is not listed. Before PR #62 only the utility keys,
`aurora.cpu.cores`, the bridge mode and thread checks, the scheduler budgets and the stall recorder
were compared.

### YAML files and the one-time conversion

The two `sourbycraft_config` files are YAML. On the first boot after the upgrade:

1. A `*.yml` file is read as-is; a leftover `*.toml` beside it is never read, and one WARN names it at
   boot and on every reload.
2. If only the `*.toml` exists it is parsed once and written as `*.yml` with every value and comment,
   then renamed `<name>.toml.migrated` (`.migrated.2`, ... when a backup already exists). This copy is
   the pre-49 original. A failed write deletes the partial YAML and leaves the TOML in place.
3. An unparseable `*.toml` is never touched: the boot log names the file and the near-line
   (`file (near line N: \`snippet\`): parser message`), the file keeps being opened (and failing), and
   every key of that file takes its default. Fix the line, or delete the file to have the defaults
   seeded on the next boot.

The format is chosen by extension, so a read-only directory keeps reading the TOML file and nothing
is renamed. Strings are always written double-quoted, and only `true`/`false` are read as booleans:
YAML 1.1 words such as `off`, `on` and `yes` stay strings, so `aurora.bridge.mode` cannot silently
become `false`.

Loading the two YAML files also follows these rules:

- **Duplicate keys are refused** at every load (boot, reload, the re-read before a `/maxp` write, and
  the early readers that use the YAML parser; `BootstrapPluginSettings` has its own line reader, where
  the first occurrence wins). A key written twice at any level makes the file unreadable, whatever the
  parsing mode. At boot that is a SEVERE naming the file and near-line, and that file's
  keys take their defaults: an unreadable utility file costs only its own keys (`aurora.yml`, the
  security limits and the updater settings still apply, and `/sourbycraft reload` opens it again once
  it is fixed); an unreadable `aurora.yml` leaves the Aurora keys to the utility file's layer and the
  defaults. A reload refuses the file and keeps the running values; `/maxp` does not write it.
- **A section deleted from the file is gone after the reload** (its keys take their defaults); a file
  that fails to parse changes nothing in memory.
- **Unknown keys:** one WARN per key and per load (boot and every reload) for a key no table documents,
  naming the file: `Config key '<key>' in <file> is not a setting SourbyCraft reads (misspelled or
  misplaced?) and is ignored.` Known keys are the `UtilityKeys` and `AuroraKeys` paths, the
  `messages.*` lists, the unseeded `aurora.awf.redis.*` keys and the Aurora keys' old paths (Aurora keys
  in the utility file are still read through the layering). Old utility key names have their own WARN
  (§7). A retired `perf.smart-swap.*` key left in a read-only directory is reported as unknown.

## 2. `sourbycraft_config/sourbycraft_global_config.yml`

Table of record: `config/UtilityKeys.java`. A fresh server writes the file with a header, section
comments and one comment per key stating lifecycle and default
([example](config-examples/sourbycraft_global_config.yml)).

| Key (Build 49) | Old name (rewritten once at boot; not read since Build 50) | Type / default | Lifecycle | Consumer |
| --- | --- | --- | --- | --- |
| `console.style` | `ui.console-style` | `auto` \| `plain` \| `rich` / `"auto"` | RESTART_REQUIRED | `ConsoleStyle.current()`: character set only (ASCII vs Unicode rules/dashes); `-Dsourbycraft.console` overrides. Before the configuration stage (launch lines, Engine rule) the key is read once from the file and cached. Colour is decided by terminal ANSI support (TerminalConsoleAppender, `-Dterminal.ansi`), not by this key |
| `console.plugins-page-size` | `ui.plugins-page-size` | integer / `12`, clamped 1–40 | LIVE | `/plugins`, `/pl` |
| `console.gc-advisor` | `branding.gc-advisor.enabled` | boolean / `true` | RESTART_REQUIRED | `GcAdvisor` startup advisory |
| `players.max-players` | `sourbycraft.max-players` | integer / `0` (= `server.properties`) | RESTART_REQUIRED when edited in the file; `/maxp <n>` applies live and writes this key | `MaxPlayersConfig.applyAtBoot`. `/maxp` persists off the tick thread (I/O executor), re-reads the file first, keeps operator edits, writes no seeded defaults, and is not shown as a pending restart afterwards; a file it cannot read is not written |
| `players.full-server-bypass` | `sourbycraft.maxplayers.bypass-enabled` | boolean / `false` | RESTART_REQUIRED | `MaxPlayersBypass` (registers a `PlayerLoginEvent` listener that slows every join) |
| `messages.server-full`, `messages.motd`, `messages.join`, `messages.leave` | unchanged | list of MiniMessage variants / built-in variants | `motd` RESTART_REQUIRED (one variant per boot); the others LIVE | `SourbyMessages`; `[]` disables an optional message, a removed key restores the built-ins. `server-full` is used only while `players.full-server-bypass` is true; otherwise the vanilla full-server message is shown |
| `plugins.viaversion.auto-provision` | `viaversion.auto-provision` | boolean / `true` | RESTART_REQUIRED, read early (`BootstrapPluginSettings`, before the plugin scan) | `PluginProvisioner`, `ViaAutoUpdate`. A file that sets only the old spelling, an invalid or flow-style value, or an unreadable file skips this group's provisioning for the boot (nothing downloaded, installed or quarantined); the old spelling is detected, never read |
| `plugins.protocollib.auto-provision` | `protocollib.auto-provision` | boolean / `true` | RESTART_REQUIRED, read early | `ProtocolLibProvisioner`; same skip rule |
| `updater.enabled` | `misc.auto_update.enabled` | boolean / `true` | RESTART_REQUIRED (schedule built once; `false` builds none) | `AutoUpdateSettings`, `SourbyUpdater.start` |
| `updater.repository` | `misc.auto_update.repo` | string / `"YanIanZ/SourbyCraft"` | LIVE (next check) | `SourbyUpdater.check` |
| `updater.channel` | `misc.auto_update.channel` | `REL` \| `DEV` \| `EXP` \| `""` / `""` (this build's channel) | LIVE (next check) | same |
| `updater.apply-mode` | `misc.auto_update.apply_mode` | `notify` \| `stage` \| `auto` \| `off` / `"notify"` | LIVE between `notify`/`stage`/`auto`; to `off` effectively LIVE (the next scheduled check does nothing); from `off` RESTART_REQUIRED (no schedule was built) | same; `stage` downloads and verifies only (a restart does not install it; `/update apply` does); [AUTO-UPDATER.md](development/AUTO-UPDATER.md) |
| `updater.check-times` | `misc.auto_update.check_times` | list of `HH:mm` / `["06:00"]` | RESTART_REQUIRED | schedule |
| `updater.check-interval-minutes` | `misc.auto_update.check_interval_minutes` | integer / `30` (0 = daily only) | RESTART_REQUIRED; `/update status` shows the pending value | schedule |
| `updater.restart-mode` | `misc.auto_update.restart_mode` | `auto` \| `exit` \| `stop` / `"auto"`; `spigot` is treated as `stop` with a WARN | LIVE (next apply) | `UpdateApplier`: `auto` = exit 70 in a Pterodactyl/Pelican container (`P_SERVER_UUID`), else `stop`; `exit` = exit 70; `stop` = exit 0, a supervisor must start the server again |
| `updater.restart-delay-seconds` | `misc.auto_update.restart_delay_seconds` | integer ≥ 5 / `60` | LIVE (next apply) | `UpdateApplier` |
| `updater.allow-prerelease` | `misc.auto_update.allow_prerelease` | boolean / `false` | LIVE (next check) | `SourbyUpdater.check` |
| `updater.via-auto-update` | `misc.auto_update.via_auto_update` | boolean / `true` | LIVE (next check); runs only within the updater's schedule | `ViaAutoUpdate`, after each scheduled check: reinstalls a missing reviewed Via pin, never upgrades; needs `plugins.viaversion.auto-provision` and a schedule (`updater.enabled` and `apply-mode` not `off` at boot) |

Retired keys removed by the migration (nothing has read them since Build 44): `perf.smart-swap.*`,
`swap.auto-create.*`. Aurora keys found in this file (`aurora.*`, `perf.ai.async-pathfinding`) belong
to `aurora.yml` and are moved there by the same migration (§7); until then the `aurora.*` ones are
still read through the layering (§3), while `perf.ai.async-pathfinding` is not read since Build 50.

Restart tracking: see *Pending-restart report* in §1.

## 3. `sourbycraft_config/aurora.yml`

Table of record: `config/AuroraKeys.java`; typed runtime snapshot `AuroraConfig`, AWF `AwfSettings`.
The paths are unchanged since Build 48 ([example](config-examples/aurora.yml)).

Only a newly created `aurora.yml` is seeded. Keys added after a file was created (for example
AbyssLink, MultiProfile and the Redis Fabric, or MultiProfile's `repository` and `redis.*` in PR #62)
are never written into an existing file: it keeps running on their defaults until the operator copies
them from the [example](config-examples/aurora.yml).

| Key | Type / default | Lifecycle | Consumer |
| --- | --- | --- | --- |
| `aurora.entity.async-pathfinding` | boolean / `false` | LIVE | `AsyncPathProcessor` admission; EXPERIMENTAL, default-off. The pre-49 name `perf.ai.async-pathfinding` is rewritten to this key once at boot and not read since Build 50 (a leftover gets one WARN per load and the default applies) |
| `aurora.diagnostics.lane-sampling` | boolean / `true` | LIVE | execution-lane CPU attribution (`/perf lanes`) |
| `aurora.diagnostics.chunk-generation-metrics` | boolean / `false` | RESTART_REQUIRED, read early (class init) | `/perf chunks`; `-Dsourbycraft.chunk-generation-metrics.enabled=true` also turns it on |
| `aurora.diagnostics.stall-recorder.enabled` | boolean / `true` | RESTART_REQUIRED | `StallRecorder`, started by `SourbyCraftBootstrap` after the configuration stage; `false` = no thread, no hook ([STALL-RECORDER.md](development/STALL-RECORDER.md)) |
| `aurora.diagnostics.stall-recorder.after-seconds` | 1–3600 / `10` | RESTART_REQUIRED | seconds without a completed tick before the first dump |
| `aurora.diagnostics.stall-recorder.repeat-seconds` | 1–3600 / `10` | RESTART_REQUIRED | seconds between dumps |
| `aurora.diagnostics.stall-recorder.max-dumps-per-stall` | 1–100 / `6` | RESTART_REQUIRED | dumps per stall (+1 if the JVM stops during it) |
| `aurora.diagnostics.stall-recorder.keep-files` | 1–1000 / `20` | RESTART_REQUIRED | newest `logs/stalls/stall-*.txt` kept |
| `aurora.cpu.cores` | integer / `0` (AUTO) | RESTART_REQUIRED, read early (`AuroraCpu`, region scheduler sizing) | precedence: `threaded-regions.threads` in `paper-global.yml`, then this, then every JVM-visible processor; clamped to the JVM-visible count. The early read layers `aurora.yml` over `sourbycraft_global_config.yml` (or their `.toml` predecessors until converted), as the snapshot does; a file that cannot be read is skipped and costs only its own keys |
| `aurora.bridge.mode` | `off` \| `safe` / `"off"` | RESTART_REQUIRED, read early (`AuroraBridge.readEarly`, before the plugin scan) | plugin admission ([aurora-plugin-bridge.md](architecture/aurora-plugin-bridge.md)) |
| `aurora.bridge.quarantine-after` | integer ≥ 1 / `3` | LIVE | bridge quarantine threshold |
| `aurora.bridge.sync-route` | `caller-region` \| `global` / `"caller-region"` | LIVE | `BridgeRuntime.submit`, read per task |
| `aurora.bridge.thread-checks` | `relaxed` \| `bridged` \| `enforced` / `"relaxed"` (owner decision 2026-10-22) | RESTART_REQUIRED, read early (`guards/ThreadChecks` class init; `-Daurora.bridge.thread-checks=` overrides); effective only while `mode = safe` | what a failed region-ownership check does ([aurora-plugin-bridge.md, Thread checks](architecture/aurora-plugin-bridge.md#thread-checks-owner-decision-2026-10-22)); relaxed = unsafe, unqualified |
| `aurora.bridge.max-pending-tasks-per-plugin` | 0 (unlimited) or 1..100000 / `0` | LIVE | pending-task admission per plugin |
| `aurora.bridge.max-running-async-tasks-per-plugin` | 0 (unlimited) or 1..100000 / `0` | LIVE | running async callbacks per plugin; over the limit a one-shot callback is cancelled and a repeating task skips that run and stays scheduled (both diagnosed, rate-limited). Rejection, not a queue |
| `aurora.scheduler.bridge-io-threads` | integer ≥ 0 / `0` (= max(2, processors/4)) | RESTART_REQUIRED | Resource Governor `BRIDGE_IO` lane |
| `aurora.scheduler.bridge-io-queue` | integer ≥ 1 / `256` | RESTART_REQUIRED | same |
| `aurora.scheduler.storage-threads` | integer ≥ 0 / `1` (0 = 1) | RESTART_REQUIRED | Resource Governor `STORAGE` lane |
| `aurora.scheduler.storage-queue` | integer ≥ 1 / `64` | RESTART_REQUIRED | same |
| `aurora.network.counters` | boolean / `true` | LIVE | `NetworkCounters` (`/perf network`) |
| `aurora.world.command-blocks` | boolean / `false` | LIVE (a repeating block that stopped while off needs re-arming) | `CommandBlockPolicy` ([command-blocks.md](architecture/command-blocks.md)); gamerule `command_blocks_work` must also be true |
| `aurora.world.tick-functions` | boolean / `false` | LIVE (switching on never re-runs `#minecraft:load`) | `DatapackFunctions.globalTick` ([datapack-functions.md](architecture/datapack-functions.md)) |
| `aurora.awf.worlds` | list of world folder names / `[]` | RESTART_REQUIRED, read early (`AwfSettings.readEarly` when the engine opens a world) | `AwfEngine.attach` |
| `aurora.awf.export` | list / `[]` | RESTART_REQUIRED, read early | export to region files at the next load |
| `aurora.awf.backend` | `file` \| `redis` \| a registered name / `"file"` | RESTART_REQUIRED, read early | `redis` = the built-in Redis backend, configured by the unseeded `aurora.awf.redis.*` keys (below); another name selects a backend registered by server-side code; unregistered + listed world = load failure, no fallback |
| `aurora.awf.persistence` | `incremental` \| `checkpoint` \| `full` / `"incremental"` | RESTART_REQUIRED, read early | commit mode |
| `aurora.awf.commit-interval-seconds` | 1–86400 / `30` | RESTART_REQUIRED, read early | age at which a commit starts |
| `aurora.awf.resident-chunks` | ≥ 1 / `1024` | RESTART_REQUIRED, read early | clean chunks kept per storage |
| `aurora.awf.retained-generations` | ≥ 1 / `3` | RESTART_REQUIRED, read early | generations kept for recovery |
| `aurora.awf.commit-attempts` | ≥ 1 / `3` | RESTART_REQUIRED, read early | attempts before a commit fails |
| `aurora.awf.delta-generations` | boolean / `false` | RESTART_REQUIRED, read early | AWF-7 delta index; see the comment in the example before enabling |
| `aurora.awf.commit-dirty-bytes` | 0..2147483647 / `0` (off) | RESTART_REQUIRED, read early | size trigger for a commit |
| `aurora.awf.shutdown-deadline-ms` | 1..2147483647 / `30000` | RESTART_REQUIRED, read early | one budget, from the start of shutdown, for the AWF waits at shutdown (lock waits, the start of each commit attempt, Redis calls, lease release); past it no further commit is attempted, Redis connections are cut and the stop ends `AWF-SHUTDOWN-INCOMPLETE`, exit 70. A FILE commit already writing (files plus fsync) is not interrupted and can hold the stop past the deadline. Also bounds how long an AuroraWorlds save (`/awf save`, the save before an unload) waits for its world's chunk I/O to drain ([aurora-world-fabric.md](architecture/aurora-world-fabric.md) D6) |

**`aurora.awf.redis.*` (Redis backend connection; read, never seeded).** `RedisSettings` reads these
six keys from `aurora.yml` over the utility file once, when the backend (or the Redis Fabric) is set
up before worlds load, so all are RESTART_REQUIRED and read early. They are not in `AuroraKeys.KEYS` or the example
(`AuroraKeys.UNSEEDED_PATHS` lists them for the unknown-key check and the pending-restart report): a
seeded `uri` would take precedence over the `SOURBYCRAFT_AWF_REDIS_URI` environment variable, which is
used **only when the key is absent** and keeps a password out of the file. The Aurora Redis Fabric
reuses `uri`, `key-prefix` and `timeout-ms`.

| Key | Type / default (`RedisSettings.DEFAULT`) | Meaning |
| --- | --- | --- |
| `aurora.awf.redis.uri` | `redis://` or `rediss://` URI / `"redis://127.0.0.1:6379/0"` | Redis server. An unusable URI (key or environment value) refuses the backend and the fabric instead of falling back to the default server; the log names the key, never the value |
| `aurora.awf.redis.key-prefix` | 1–128 characters, no braces / `"sourbycraft:awf:"` | prepended to every key; servers that must not see each other's worlds use different prefixes |
| `aurora.awf.redis.pool-size` | 1–256 / `8` | connections; used only while the fabric is off (with the fabric, its `pools.storage` is shared) |
| `aurora.awf.redis.timeout-ms` | 100–600000 / `5000` | connect, read and pool-wait timeout |
| `aurora.awf.redis.lease-seconds` | 5–3600 / `60` | how long a server's claim on a writable world lasts without renewal |
| `aurora.awf.redis.wait-for-aof` | boolean / `true` | after each world commit, wait for `WAITAOF`; without it a commit is acknowledged from Redis's memory. Not the same key as `aurora.awf.multiprofile.redis.wait-for-aof` |

An invalid value is reported with one WARN and the key falls back to its own default; only a
malformed namespace container (a value where a table belongs, such as `aurora: 1`, or a value in
place of the `entity`, `diagnostics`, `cpu`, `bridge`, `scheduler`, `network` or `world` table)
discards every `AuroraConfig` value (entity, diagnostics, CPU, bridge, scheduler, network, world) at
once. The utility file is still layered **under** `aurora.yml` for Aurora keys, so a server whose
migration could not write the files keeps the value it had; where both files set the same Aurora key
to different values, `aurora.yml` wins and one WARN names the copy that decides nothing.

Hot-path audit: every `cfgGet/cfgBool/cfgInt` lookup runs at boot, at explicit reload, on an
operator command or on a join/leave event, never per tick; each is one `get` on the immutable
`ConfigSnapshot`. Tick code consumes `AuroraConfig` or published primitives.

## 4. `config/aurora-server.yml` and `config/aurora-worlds.yml` (region engine)

Loaded by `GlobalConfiguration` / `WorldConfig` through the engine's `ConfigurationProvider` at
class init and on `/sourbycraft reload`. A load writes the file only when the key set changed: an
added key gets its documentation, a removed key is dropped, and each change is logged. A load that
changes no key leaves the file byte-identical, operator formatting and comments included (PR #62; the
published `v26.2-r50` rewrote it on every load). The header is written only when the file is created;
a missing file is written with every default, and a missing per-world `canvas-patch.yml` is created
holding only the header. When a write happens it is still a truncate-then-write, not an atomic
replace. Defaults SourbyCraft ships differently from Canvas are pinned in
`scripts/test_patch_policy.py` (`region-scheduler.guard-severity: LOG`,
`logs.log-ender-pearl-rewrite-actions: false`, TPS/RAM bars off).

Lifecycle per setting is the one traced in the rename plan §2.3 and unchanged by the rename:
LIVE `region-scheduler.guard-severity`, `autosave.autosave-frequency`, `autosave.autosave-players`,
`chat.disableChatVerificationOrder`, `displayWorldLoadScreenForCrossRegionTransfers`,
`region-scheduler.prevent-excessive-velocity-move-out-of-region`; RESTART_REQUIRED
`region-scheduler.default-tick-rate`, `region-scheduler.affinity-scheduler.*`, `logs.*`,
`networking.player-info-send-interval`, `vanilla-fixes.mc223153`; per-world spawner integers and
the projectile chunk-load override apply to newly created objects only. Anything not listed there
is **unverified until traced** and must not be documented as LIVE. A reload is not all-or-nothing:
an invalid server file keeps the previous global instance; worlds are swapped one by one.

Engine keys have no pending-restart tracking; the reload report says they are not enumerated.
The engine reload runs on the reload's I/O thread (§1).

The Spark config export lists them under the group `aurora/` as `aurora-server.yml`,
`aurora-worlds.yml` and `<world>.yml`: the per-world `canvas-patch.yml` from the world's dimension
storage folder, the file the engine merges (same path as `WorldConfig.patchFile`; before PR #62 Spark
read the world's Bukkit folder, which can differ for a non-overworld dimension).

## 5. `sourbycraft-security.yml`

Keys and defaults: `SecurityGuards.Limit` and `build-data/sourbycraft-security.yml` (byte-identical
to what a server without the file writes; `SecurityLimitPinTest`). Groups `crash-prevention.*`,
`packet-guard.*`, `connection-guard.*`, each with a `mode` (`LOG` default, `KICK`, `OFF`). Every
limit, mode and `connection-guard.per-address.ipv6-prefix-length` is **LIVE** on
`/sourbycraft reload`; the proxy detection (BungeeCord / Velocity / PROXY protocol) is read when the
TCP listener starts and is RESTART_REQUIRED. An unknown key or a non-positive limit fails the load
and keeps the previous values. The file is written only when it does not exist (at boot or by a
reload), never rewritten. [SECURITY-GUARDS.md](development/SECURITY-GUARDS.md).

## 6. Boot order

Every early reader of Aurora keys below reads `sourbycraft_global_config.yml` first and `aurora.yml`
over it (each file, or its `.toml` predecessor until step 4 converts it), as the snapshot layers
them; the early readers of utility keys (`ConsoleStyle`, `BootstrapPluginSettings`) read the global
file only.

1. SourbyClip, Paper bootstrap. `AuroraCpu` reads `aurora.cpu.cores` during Paper's global
   configuration load (region scheduler sizing); a file it cannot read is skipped.
   `ChunkGenerationMetrics` reads its key at class init. Unless `-Dsourbycraft.console` is set, the
   launch lines and the Engine rule read `console.style` once from the global file (`ConsoleStyle`).
2. `GlobalConfiguration` / `WorldConfig` class init: `EngineConfigFiles.migrate` (§7), then the
   engine files load.
3. Before the plugin scan: `BootstrapPluginSettings` reads `plugins.*.auto-provision` for the
   provisioners (a file with only the pre-49 spelling skips that group this boot); `AuroraBridge.readEarly`
   reads the bridge keys; `AwfSettings.readEarly` and `RedisSettings.readEarly` read the AWF and Redis
   backend keys when the engine opens a world. MultiProfile (`ProfileConfig`) and the Redis Fabric
   (`FabricConfig`, `RedisSettings`) read theirs from the files when their runtimes start.
4. `SourbyCraftBootstrap` "configuration" stage: `SourbyCraftConfig.init()` = open both files
   (converting a lone `*.toml` once, §1), `ConfigMigration` (§7), seed missing keys (utility keys in
   memory, written only into a new or just-migrated file; Aurora keys only into a new `aurora.yml`),
   publish the snapshot, apply LIVE consumers, load the security file. A
   utility file that cannot be opened costs only its own keys: `aurora.yml`, the security limits and
   the updater settings are still applied. Then the stall recorder, AuroraWorlds, plugin configs,
   plugins.

Because of steps 1–3, a first boot after upgrading reads the early keys from wherever they were
(the layering still applies) and the migration of step 4 moves them; the second boot reads them
from `aurora.yml` only. The effective values are the same on both boots for every key whose path did
not change. A renamed key is the exception since Build 50: its old name is read by no reader, so a
directory the migration cannot write runs on the default (and provisioning skips the group).

## 7. The Build 49 migration (one-time, automatic)

Owner decision 2026-10-22. Everything below is done by the server itself at boot; nothing is
deleted and every rewritten or renamed file keeps a copy of what it was.

| Surface | Trigger | What happens | Log line | Idempotent? |
| --- | --- | --- | --- | --- |
| `config/canvas-server.yml` → `config/aurora-server.yml`, `config/canvas-worlds.yml` → `config/aurora-worlds.yml` | old name exists, new one does not | the document is copied under the new name (values and per-key comments kept, old header replaced by the new one) through an atomic `.migrating` sibling; the old file is renamed `<old>.migrated` (`.migrated.1`, … if taken) | INFO `Migrated config/canvas-server.yml -> config/aurora-server.yml (values and comments kept; old file renamed to config/canvas-server.yml.migrated, not read again)` | yes: the next load finds the new file alone |
| same | both names exist | the new file is read; the old one is not opened or merged | WARN `Both config/aurora-server.yml and config/canvas-server.yml exist; config/aurora-server.yml is read and config/canvas-server.yml is ignored. Rename or remove the old file ...` on every load until the operator moves it | — |
| same | old file is empty | the new file is created with the defaults; the old file is still renamed | INFO `... was empty; config/aurora-server.yml is created with the defaults` | yes |
| same | old file cannot be parsed | the load fails: `config/canvas-server.yml could not be migrated to config/aurora-server.yml: <cause>. Fix the old file (or move it away to start from defaults); nothing was written or renamed.` | ERROR (boot stops, as an invalid file always did) | retried next boot |
| `sourbycraft_global_config.yml` old key names (after the format conversion of §1) | any `ui.*`, `branding.*`, `sourbycraft.*`, `viaversion.*`, `protocollib.*`, `misc.auto_update.*` key present | the file is copied to `sourbycraft_global_config.yml.migrated` (`.migrated.1`, … if taken), each key moves to its Build 49 name with its value and the current comment, emptied tables disappear, the header and section comments and any missing key are written | INFO `Migrated the configuration files to the Build 49 layout: N key(s) renamed; ... values unchanged. Previous file kept as sourbycraft_global_config.yml.migrated.` | yes |
| same, stale duplicate | old and new name both set | the old one is dropped from the file (the new one already decided) | listed in the same INFO (`stale duplicate(s) dropped`) | yes |
| same, retired keys | `perf.smart-swap.*`, `swap.auto-create.*` | removed (nothing reads them) | listed in the same INFO | yes |
| Aurora keys in the global file | `aurora.*` or `perf.ai.async-pathfinding` present | moved into `aurora.yml` (copied to `aurora.yml.migrated` first) under the current name with its comment; if `aurora.yml` already sets the key, the global copy is dropped (`aurora.yml` always won) | same INFO (`Aurora key(s) moved to sourbycraft_config/aurora.yml`; `Previous Aurora file kept as aurora.yml.migrated.`) | yes |
| `perf.ai.async-pathfinding` inside `aurora.yml` | present without `aurora.entity.async-pathfinding` | renamed in place | same INFO | yes |
| `sourbycraft-security.yml` | — | no key changed; nothing is rewritten | — | — |

**Two kinds of backup.** A server upgrading from a pre-49 TOML gets both: `<name>.toml.migrated` is
the pre-49 original, kept by the format conversion (§1); `<name>.yml.migrated` is the converted YAML
as it was just before the layout rewrite. A server that was already on YAML gets only the second.

**Old names since Build 50.** The rewrite above is kept (migrate before remove), but no reader
resolves an old name any more (`ConfigMigration.stripLegacyNames` leaves them out of the snapshot).
An old utility name still in the file (the rewrite could not run, or the key was added back by hand)
is ignored with one WARN per key per load: `Config key 'misc.auto_update.enabled' in
sourbycraft_config/sourbycraft_global_config.yml is ignored since Build 50; only its current name
'updater.enabled' is read (its default applies when the file does not set it). Rename the key; the
configuration migration does this at boot when it can write the file.` A leftover
`perf.ai.async-pathfinding` gets the matching WARN naming `aurora.entity.async-pathfinding`.
`BootstrapPluginSettings` only detects the old `<group>.auto-provision` spelling and skips that
group's provisioning for the boot. So a read-only `sourbycraft_config/` still boots (the INFO becomes
an ERROR `configuration layout migration failed; the files were not changed, so old key names in them
are ignored (one WARN each)`), but every setting kept under an old name runs on its default until the
file is renamed by hand or the directory is made writable. `v26.2-r50` and earlier still read the old
names. The engine file-name migration and the readers of persisted Canvas-era data stay pending owner
decision PM-179 (§9, [26.2-build-50-aurora-nexus.md](releases/26.2-build-50-aurora-nexus.md)).

**Downgrade.** A pre-49 jar reads `config/canvas-*.yml` and the old TOML names. Before starting
it: stop the server, then `cp -p config/aurora-server.yml config/canvas-server.yml`,
`cp -p config/aurora-worlds.yml config/canvas-worlds.yml`, and restore
`sourbycraft_global_config.toml.migrated` over `sourbycraft_global_config.toml` (and
`aurora.toml.migrated` over `aurora.toml` if it exists). Values edited after the migration live
only in the new files; merge them by hand. The `*.migrated` copies are never touched again by any
build, so they stay a faithful pre-49 snapshot. On the candidate, the auto-updater's own rollback to
a pre-49 jar (a failed first boot after an `auto` apply) copies the newest `*.migrated` copy of
`sourbycraft_global_config.toml`, `aurora.toml`, `config/canvas-server.yml` and
`config/canvas-worlds.yml` back when that name is free (`UpdateRollbackGuard`); it never overwrites a
file.

**Verification on a real server copy** is a Build 49 runbook step
([runbook, configuration migration](releases/runbooks/build-49-real-test.md#configuration-migration-build-49-layout));
the Build 50 candidate's checks are [Tester Owner](tester%20owner.md) TO-CFG-01…20.

## 8. Development rules for configuration claims

Documentation and release notes must distinguish:

- **implemented key** — parser + consumer exist (every key above);
- **live key** — changing it affects the running server without restart;
- **restart-required key** — the parser may reload it, the active subsystem cannot;
- **read early** — read from the file before the configuration stage; a reload never reaches it;
- **planned namespace** — architecture only, not a usable setting (none in this document).

Do not say a config file is the single source of truth if an early-boot consumer bypasses it. Do
not say a reload "applied" a value whose consumer was already constructed. When this document
conflicts with code on branch `26.2`, verify the active consumer and boot order, then update this
document in the same change. Adding a key means adding it to `UtilityKeys` / `AuroraKeys`, the
example file and this table; the golden tests fail otherwise. `ConfigExamplesTest` compares every
key's value, the section names and (for the utility file) the lifecycle names, not the comment text:
when a seeded comment changes, update the example's comment by review. A key read from a file but
deliberately not seeded belongs in `AuroraKeys.UNSEEDED_PATHS` (unknown-key check, pending-restart
report), not in the example.

## 9. Canvas-era names (renamed in Build 48; non-persisted old names not read since Build 50)

Owner decision 2026-10-21: the old names were read, with a WARN, through Build 49 (and still are by
the published `v26.2-r50`). On the Build 50 candidate (PR #62) only the new name decides. An old
JVM flag that is set while its new name is not, or an explicit grant of the old permission, logs one
`ignored since Build 50` WARN naming the replacement (once per flag, once per player for the
permission; `LegacyNames`, `BroadcastPermission`; the engine-baseline call sites are unchanged). The
readers of persisted data are kept, pending owner decision **PM-179**. All RESTART_REQUIRED except the
permission (checked per broadcast).

| New name | Old name | Kind | Read at | Old name on the candidate |
| --- | --- | --- | --- | --- |
| `-DAurora.AggressiveChunkSystem` | `-DCanvas.AggressiveChunkSystem` | JVM flag | `MoonriseCommon` class init | ignored, one WARN |
| `-DAurora.DisableRegionProfiler` | `-DCanvas.DisableRegionProfiler` | JVM flag, only `=true` counts | each region-profiler support check | ignored, one WARN |
| `-DAurora.isRunningInIDE` | `-DCanvas.isRunningInIDE` | JVM flag | `SharedConstants` class init | ignored, one WARN |
| permission `aurora.broadcasting.receiver` | `canvas.broadcasting.receiver` | permission | each `GlobalConfiguration.broadcast` | grants nothing; an explicit grant logs one WARN per player |
| `Aurora.Distance` in `paper:level_overrides` | `Canvas.Distance` | saved world data | world load | **kept** (PM-179): read when the new key is absent, with a one-time line, and saved under the new key |
| `data/aurora/pearls.dat` | `data/canvas/pearls.dat` | saved server data | moved once before load | **kept** (PM-179) |

The engine configuration **file names** were renamed in Build 49 with the migration of §7 (they
were kept in Build 48); that migration is kept on the candidate (PM-179). The per-world
`canvas-patch.yml` is not renamed. The pre-49 utility key names and `perf.ai.async-pathfinding` are
covered in §7.

## AbyssLink YAML (new native service)

The optional AbyssLink client uses `sourbycraft_config/aurora.yml`, under `aurora.abysslink`, default disabled; all keys require restart and are in the pending-restart report. `AuroraKeys.ABYSSLINK_KEYS` is part of the YAML key table, so the seeder writes those keys and their comments into a newly created `aurora.yml` only; an existing file runs on the defaults until the keys are copied from the [example](config-examples/aurora.yml). See [AbyssLink setup](abysslink/INTEGRATION.md).

## AWF MultiProfile and Aurora Redis Fabric (new, default off)

Two namespaces in `aurora.yml`, both RESTART_REQUIRED and read early:
`aurora.awf.multiprofile.*` (23 keys, including `repository`, `redis.require-aof` and
`redis.wait-for-aof` added in PR #62) and `aurora.awf.redis.fabric.*` (20 keys). `ProfileSettings` /
`FabricSettings` parse them; `AuroraKeys` seeds them into a newly created `aurora.yml` only, and
`docs/config-examples/aurora.yml` is the golden copy. The fabric reuses `aurora.awf.redis.uri`,
`key-prefix`, `timeout-ms`, TLS/ACL and `SOURBYCRAFT_AWF_REDIS_URI`, so no credential is duplicated.

Notable defaults: both features off; `mode: standalone`; `repository: file`; ownership `local`; 3
default / 8 maximum profile slots; every isolation category on; `redis.require-aof` and
`redis.wait-for-aof` on (used only with `repository: redis`); `services.events` and `services.cache`
off until a consumer ships; four bounded pools (8/4/2/2). Full table, lifecycle and rollback:
[awf/multiprofile/CONFIGURATION.md](awf/multiprofile/CONFIGURATION.md).

`repository: file` keeps profiles in `awf/profiles` (the directory's lock refuses a second server).
`repository: redis` keeps them in Redis through the fabric: each write is one Lua script that checks the
writer's session claim (when the write carries one) and the record revision (when the record has one)
(`RedisProfileRepository`); profile creation, slot limits and journal phases written before a switch
holds a claim are checked by revision or not at all. Both `repository: redis`
and `mode: distributed` are refused at startup unless `aurora.awf.redis.fabric.enabled: true` with a
reachable Redis, `coordination.provider: redis` and `coordination.fencing-enabled: true` are set;
`mode: distributed` with `repository: file` is refused. With the Redis repository (distributed, or
standalone, which nothing limits to one server) the feature is **EXPERIMENTAL and unqualified**: a
player's live state does not follow them to another server, players online at shutdown get no quit
snapshot (feature patch 0081 order), and no proxy or portal hands players between servers. Code
written and statically reviewed; not built; not tested by the owner.
