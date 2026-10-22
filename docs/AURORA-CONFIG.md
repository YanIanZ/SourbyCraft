# SourbyCraft configuration — effective 26.2 contract (Build 49 layout)

> **Role:** the effective configuration contract: every file, every key, its default, LIVE or
> RESTART_REQUIRED, the consumer, boot order, and the one-time Build 49 migration.
> **Status:** Active; checked against `config/UtilityKeys.java`, `config/AuroraKeys.java`,
> `config/ConfigMigration.java`, `aurora/engine/configuration/EngineConfigFiles.java`,
> `security/SecurityGuards.java` and their tests on 2026-10-22. The key tables below are pinned by
> `UtilityKeysTest`, `AuroraKeysTest`, `ConfigExamplesTest` and `SecurityLimitPinTest`.
>
> Entry point: [docs/architecture/AURORA.md](architecture/AURORA.md). Examples:
> [config-examples/](config-examples/). Rename plan and history:
> [development/CONFIG-FILE-RENAME-PLAN.md](development/CONFIG-FILE-RENAME-PLAN.md).

This document describes **what branch `26.2` actually consumes now**. It is not a future-layout
proposal. LIVE means `/sourbycraft reload` applies the value; RESTART_REQUIRED means only a new
process reads it; "read early" means the consumer reads the file itself before the configuration
stage of `SourbyCraftBootstrap`, so neither a reload nor the migration of that boot can reach it.

## 1. The files

| File | Owns | Format / loader | Build 49 change |
| --- | --- | --- | --- |
| `sourbycraft_config/sourbycraft_global_config.toml` | console, players, messages, plugin provisioning, updater | TOML (nightconfig), `SourbyCraftConfig` + `UtilityKeys` | **relaid**: `[console]`, `[players]`, `[messages]`, `[plugins.viaversion]`, `[plugins.protocollib]`, `[updater]`; old names migrated once, read until Build 50 |
| `sourbycraft_config/aurora.toml` | Aurora services: entity, diagnostics, CPU budget, bridge, scheduler budgets, network, world, AWF | TOML (nightconfig), `SourbyCraftConfig` + `AuroraKeys`; early readers parse it directly | keys unchanged; header and section comments on a fresh file; Aurora keys left in the global TOML are moved here once |
| `config/aurora-server.yml` | region engine, server-wide (`GlobalConfiguration`) | YAML (engine `ConfigurationProvider`) | **renamed** from `config/canvas-server.yml`, migrated once |
| `config/aurora-worlds.yml` | region engine, world defaults (`WorldConfig`) | YAML (engine `ConfigurationProvider`) | **renamed** from `config/canvas-worlds.yml`, migrated once |
| `<dimension storage dir>/canvas-patch.yml` | per-world override of `aurora-worlds.yml` | YAML | not renamed (separate plan; see the rename plan §1) |
| `sourbycraft-security.yml` | SEC-1 decode guards, SEC-11 connection admission | YAML (Bukkit `YamlConfiguration`), `SecurityGuards` | keys unchanged; a fresh file now carries a header and per-group comments and is byte-identical to `build-data/sourbycraft-security.yml` |
| `config/paper-global.yml` `threaded-regions` | region scheduler sizing (`threads`, `grid-exponent`, `scheduler`) | Paper | unchanged; `threads: -1` resolves to `aurora.cpu.cores` or every processor |

`/sourbycraft config` prints these locations and the loaded values; `/sourbycraft reload` re-reads
the TOMLs, the security file and both engine files (never rewriting any of them).

## 2. `sourbycraft_config/sourbycraft_global_config.toml`

Table of record: `config/UtilityKeys.java`. A fresh server writes the file with a header, section
comments and one comment per key stating lifecycle and default
([example](config-examples/sourbycraft_global_config.toml)).

| Key (Build 49) | Was (until Build 48) | Type / default | Lifecycle | Consumer |
| --- | --- | --- | --- | --- |
| `console.style` | `ui.console-style` | `auto` \| `plain` \| `rich` / `"auto"` | RESTART_REQUIRED | `ConsoleStyle.current()`; `-Dsourbycraft.console` overrides, `NO_COLOR` disables color |
| `console.plugins-page-size` | `ui.plugins-page-size` | integer / `12`, clamped 1–40 | LIVE | `/plugins`, `/pl` |
| `console.gc-advisor` | `branding.gc-advisor.enabled` | boolean / `true` | RESTART_REQUIRED | `GcAdvisor` startup advisory |
| `players.max-players` | `sourbycraft.max-players` | integer / `0` (= `server.properties`) | RESTART_REQUIRED when edited in the file; `/maxp <n>` applies live and writes this key | `MaxPlayersConfig.applyAtBoot` |
| `players.full-server-bypass` | `sourbycraft.maxplayers.bypass-enabled` | boolean / `false` | RESTART_REQUIRED | `MaxPlayersBypass` (registers a `PlayerLoginEvent` listener that slows every join) |
| `messages.server-full`, `messages.motd`, `messages.join`, `messages.leave` | unchanged | list of MiniMessage variants / built-in variants | `motd` RESTART_REQUIRED (one variant per boot); the others LIVE | `SourbyMessages`; `[]` disables an optional message, a removed key restores the built-ins |
| `plugins.viaversion.auto-provision` | `viaversion.auto-provision` | boolean / `true` | RESTART_REQUIRED, read early (`BootstrapPluginSettings`, before the plugin scan) | `PluginProvisioner`, `ViaAutoUpdate` |
| `plugins.protocollib.auto-provision` | `protocollib.auto-provision` | boolean / `true` | RESTART_REQUIRED, read early | `ProtocolLibProvisioner` |
| `updater.enabled` | `misc.auto_update.enabled` | boolean / `true` | RESTART_REQUIRED (schedule built once) | `AutoUpdateSettings`, `SourbyUpdater.start` |
| `updater.repository` | `misc.auto_update.repo` | string / `"YanIanZ/SourbyCraft"` | LIVE (next check) | `SourbyUpdater.check` |
| `updater.channel` | `misc.auto_update.channel` | `REL` \| `DEV` \| `EXP` \| `""` / `""` (this build's channel) | LIVE (next check) | same |
| `updater.apply-mode` | `misc.auto_update.apply_mode` | `notify` \| `stage` \| `auto` \| `off` / `"notify"` | LIVE between `notify`/`stage`/`auto`; from or to `off` RESTART_REQUIRED | same; [AUTO-UPDATER.md](development/AUTO-UPDATER.md) |
| `updater.check-times` | `misc.auto_update.check_times` | list of `HH:mm` / `["06:00"]` | RESTART_REQUIRED | schedule |
| `updater.check-interval-minutes` | `misc.auto_update.check_interval_minutes` | integer / `30` (0 = daily only) | RESTART_REQUIRED; `/update status` shows the pending value | schedule |
| `updater.restart-mode` | `misc.auto_update.restart_mode` | `auto` \| `spigot` \| `exit` \| `stop` / `"auto"` | LIVE (next apply) | `UpdateApplier` |
| `updater.restart-delay-seconds` | `misc.auto_update.restart_delay_seconds` | integer ≥ 5 / `60` | LIVE (next apply) | `UpdateApplier` |
| `updater.allow-prerelease` | `misc.auto_update.allow_prerelease` | boolean / `false` | LIVE (next check) | `SourbyUpdater.check` |
| `updater.via-auto-update` | `misc.auto_update.via_auto_update` | boolean / `true` | LIVE (next check) | `ViaAutoUpdate` |

Retired keys removed by the migration (nothing has read them since Build 44): `perf.smart-swap.*`,
`swap.auto-create.*`. Aurora keys found in this file (`aurora.*`, `perf.ai.async-pathfinding`) belong
to `aurora.toml` and are moved there (§3).

Restart tracking: `/sourbycraft reload` compares `console.style`, `console.gc-advisor`,
`players.*`, `plugins.*`, `messages.motd`, `updater.enabled`, `updater.check-times`,
`updater.check-interval-minutes` and the Aurora restart keys against the **boot** snapshot, so a
pending restart is reported until a new process starts, not until the next reload.

## 3. `sourbycraft_config/aurora.toml`

Table of record: `config/AuroraKeys.java`; typed runtime snapshot `AuroraConfig`, AWF `AwfSettings`.
The paths are unchanged since Build 48 ([example](config-examples/aurora.toml)).

| Key | Type / default | Lifecycle | Consumer |
| --- | --- | --- | --- |
| `aurora.entity.async-pathfinding` | boolean / `false` | LIVE | `AsyncPathProcessor` admission; EXPERIMENTAL, default-off; legacy `perf.ai.async-pathfinding` read until Build 50 |
| `aurora.diagnostics.lane-sampling` | boolean / `true` | LIVE | execution-lane CPU attribution (`/perf lanes`) |
| `aurora.diagnostics.chunk-generation-metrics` | boolean / `false` | RESTART_REQUIRED, read early (class init) | `/perf chunks`; `-Dsourbycraft.chunk-generation-metrics.enabled=true` also turns it on |
| `aurora.diagnostics.stall-recorder.enabled` | boolean / `true` | RESTART_REQUIRED | `StallRecorder`, started by `SourbyCraftBootstrap` after the configuration stage; `false` = no thread, no hook ([STALL-RECORDER.md](development/STALL-RECORDER.md)) |
| `aurora.diagnostics.stall-recorder.after-seconds` | 1–3600 / `10` | RESTART_REQUIRED | seconds without a completed tick before the first dump |
| `aurora.diagnostics.stall-recorder.repeat-seconds` | 1–3600 / `10` | RESTART_REQUIRED | seconds between dumps |
| `aurora.diagnostics.stall-recorder.max-dumps-per-stall` | 1–100 / `6` | RESTART_REQUIRED | dumps per stall (+1 if the JVM stops during it) |
| `aurora.diagnostics.stall-recorder.keep-files` | 1–1000 / `20` | RESTART_REQUIRED | newest `logs/stalls/stall-*.txt` kept |
| `aurora.cpu.cores` | integer / `0` (AUTO) | RESTART_REQUIRED, read early (`AuroraCpu`, region scheduler sizing) | precedence: `threaded-regions.threads` in `paper-global.yml`, then this, then every JVM-visible processor; clamped to the JVM-visible count |
| `aurora.bridge.mode` | `off` \| `safe` / `"off"` | RESTART_REQUIRED, read early (`AuroraBridge.readEarly`, before the plugin scan) | plugin admission ([aurora-plugin-bridge.md](architecture/aurora-plugin-bridge.md)) |
| `aurora.bridge.quarantine-after` | integer ≥ 1 / `3` | LIVE | bridge quarantine threshold |
| `aurora.bridge.sync-route` | `caller-region` \| `global` / `"caller-region"` | LIVE | `BridgeRuntime.submit`, read per task |
| `aurora.bridge.max-pending-tasks-per-plugin` | 0 (unlimited) or 1..100000 / `0` | LIVE | pending-task admission per plugin |
| `aurora.bridge.max-running-async-tasks-per-plugin` | 0 (unlimited) or 1..100000 / `0` | LIVE | running async callbacks per plugin |
| `aurora.scheduler.bridge-io-threads` | integer ≥ 0 / `0` (= max(2, processors/4)) | RESTART_REQUIRED | Resource Governor `BRIDGE_IO` lane |
| `aurora.scheduler.bridge-io-queue` | integer ≥ 1 / `256` | RESTART_REQUIRED | same |
| `aurora.scheduler.storage-threads` | integer ≥ 0 / `1` (0 = 1) | RESTART_REQUIRED | Resource Governor `STORAGE` lane |
| `aurora.scheduler.storage-queue` | integer ≥ 1 / `64` | RESTART_REQUIRED | same |
| `aurora.network.counters` | boolean / `true` | LIVE | `NetworkCounters` (`/perf network`) |
| `aurora.world.command-blocks` | boolean / `false` | LIVE (a repeating block that stopped while off needs re-arming) | `CommandBlockPolicy` ([command-blocks.md](architecture/command-blocks.md)); gamerule `command_blocks_work` must also be true |
| `aurora.world.tick-functions` | boolean / `false` | LIVE (switching on never re-runs `#minecraft:load`) | `DatapackFunctions.globalTick` ([datapack-functions.md](architecture/datapack-functions.md)) |
| `aurora.awf.worlds` | list of world folder names / `[]` | RESTART_REQUIRED, read early (`AwfSettings.readEarly` when the engine opens a world) | `AwfEngine.attach` |
| `aurora.awf.export` | list / `[]` | RESTART_REQUIRED, read early | export to region files at the next load |
| `aurora.awf.backend` | name / `"file"` | RESTART_REQUIRED, read early | unregistered + listed world = load failure |
| `aurora.awf.persistence` | `incremental` \| `checkpoint` \| `full` / `"incremental"` | RESTART_REQUIRED, read early | commit mode |
| `aurora.awf.commit-interval-seconds` | 1–86400 / `30` | RESTART_REQUIRED, read early | age at which a commit starts |
| `aurora.awf.resident-chunks` | ≥ 1 / `1024` | RESTART_REQUIRED, read early | clean chunks kept per storage |
| `aurora.awf.retained-generations` | ≥ 1 / `3` | RESTART_REQUIRED, read early | generations kept for recovery |
| `aurora.awf.commit-attempts` | ≥ 1 / `3` | RESTART_REQUIRED, read early | attempts before a commit fails |
| `aurora.awf.delta-generations` | boolean / `false` | RESTART_REQUIRED, read early | AWF-7 delta index; see the comment in the example before enabling |
| `aurora.awf.commit-dirty-bytes` | 0..2147483647 / `0` (off) | RESTART_REQUIRED, read early | size trigger for a commit |
| `aurora.awf.shutdown-deadline-ms` | 1..2147483647 / `30000` | RESTART_REQUIRED, read early | one budget for every AWF wait at shutdown; past it the stop ends `AWF-SHUTDOWN-INCOMPLETE`, exit 70 ([aurora-world-fabric.md](architecture/aurora-world-fabric.md) D6) |

An invalid value is reported with one WARN and the key falls back to its own default; only a
malformed namespace container (`aurora = 1`) discards the whole file's Aurora values. The utility
file is still layered **under** `aurora.toml` for Aurora keys, so a server whose migration could not
write the files keeps the value it had; where both files set the same Aurora key, `aurora.toml`
wins and one WARN names the copy that decides nothing.

Hot-path audit: every `cfgGet/cfgBool/cfgInt` lookup runs at boot, at explicit reload, on an
operator command or on a join/leave event, never per tick; each is one `get` on the immutable
`ConfigSnapshot`. Tick code consumes `AuroraConfig` or published primitives.

## 4. `config/aurora-server.yml` and `config/aurora-worlds.yml` (region engine)

Loaded by `GlobalConfiguration` / `WorldConfig` through the engine's `ConfigurationProvider` at
class init and on `/sourbycraft reload`. Every ordinary load rewrites the file with the current key
set (added keys get their documentation, removed keys are reported and dropped; the header is
written only when the file is created). Defaults SourbyCraft ships differently from Canvas are
pinned in `scripts/test_patch_policy.py` (`region-scheduler.guard-severity: LOG`,
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

The Spark config export lists them under the group `aurora/` as `aurora-server.yml`,
`aurora-worlds.yml` and `<world>.yml` (the per-world `canvas-patch.yml`).

## 5. `sourbycraft-security.yml`

Keys and defaults: `SecurityGuards.Limit` and `build-data/sourbycraft-security.yml` (byte-identical
to what a server without the file writes; `SecurityLimitPinTest`). Groups `crash-prevention.*`,
`packet-guard.*`, `connection-guard.*`, each with a `mode` (`LOG` default, `KICK`, `OFF`). Every
limit, mode and `connection-guard.per-address.ipv6-prefix-length` is **LIVE** on
`/sourbycraft reload`; the proxy detection (BungeeCord / Velocity / PROXY protocol) is read when the
TCP listener starts and is RESTART_REQUIRED. An unknown key or a non-positive limit fails the load
and keeps the previous values. [SECURITY-GUARDS.md](development/SECURITY-GUARDS.md).

## 6. Boot order

1. SourbyClip, Paper bootstrap. `AuroraCpu` reads `aurora.cpu.cores` from `aurora.toml` during
   Paper's global configuration load (region scheduler sizing). `ChunkGenerationMetrics` reads its
   key at class init.
2. `GlobalConfiguration` / `WorldConfig` class init: `EngineConfigFiles.migrate` (§7), then the
   engine files load.
3. Before the plugin scan: `BootstrapPluginSettings` reads `plugins.*.auto-provision` (old spelling
   accepted) for the provisioners; `AuroraBridge.readEarly` reads the bridge keys from both TOMLs;
   `AwfSettings.readEarly` reads the AWF keys when the engine opens a world.
4. `SourbyCraftBootstrap` "configuration" stage: `SourbyCraftConfig.init()` = open both TOMLs,
   `ConfigMigration` (§7), seed missing keys, publish the snapshot, apply LIVE consumers, load the
   security file. Then the stall recorder, AuroraWorlds, plugin configs, plugins.

Because of steps 1–3, a first boot after upgrading reads the early keys from wherever they were
(the layering still applies) and the migration of step 4 moves them; the second boot reads them
from `aurora.toml` only. The effective values are the same on both boots.

## 7. The Build 49 migration (one-time, automatic)

Owner decision 2026-10-22. Everything below is done by the server itself at boot; nothing is
deleted and every rewritten or renamed file keeps a copy of what it was.

| Surface | Trigger | What happens | Log line | Idempotent? |
| --- | --- | --- | --- | --- |
| `config/canvas-server.yml` → `config/aurora-server.yml`, `config/canvas-worlds.yml` → `config/aurora-worlds.yml` | old name exists, new one does not | the document is copied under the new name (values and per-key comments kept, old header replaced by the new one) through an atomic `.migrating` sibling; the old file is renamed `<old>.migrated` (`.migrated.1`, … if taken) | INFO `Migrated config/canvas-server.yml -> config/aurora-server.yml (values and comments kept; old file renamed to config/canvas-server.yml.migrated, not read again)` | yes: the next load finds the new file alone |
| same | both names exist | the new file is read; the old one is not opened or merged | WARN `Both config/aurora-server.yml and config/canvas-server.yml exist; config/aurora-server.yml is read and config/canvas-server.yml is ignored. Rename or remove the old file ...` on every load until the operator moves it | — |
| same | old file is empty | the new file is created with the defaults; the old file is still renamed | INFO `... was empty; config/aurora-server.yml is created with the defaults` | yes |
| same | old file cannot be parsed | the load fails: `config/canvas-server.yml could not be migrated to config/aurora-server.yml: <cause>. Fix the old file (or move it away to start from defaults); nothing was written or renamed.` | ERROR (boot stops, as an invalid file always did) | retried next boot |
| `sourbycraft_global_config.toml` old key names | any `ui.*`, `branding.*`, `sourbycraft.*`, `viaversion.*`, `protocollib.*`, `misc.auto_update.*` key present | the file is copied to `sourbycraft_global_config.toml.migrated`, each key moves to its Build 49 name with its value and the current comment, emptied tables disappear, the header and section comments and any missing key are written | INFO `Migrated the configuration files to the Build 49 layout: N key(s) renamed; ... values unchanged. Previous file kept as ...` | yes |
| same, stale duplicate | old and new name both set | the old one is dropped from the file (the new one already decided) | listed in the same INFO (`stale duplicate(s) dropped`) | yes |
| same, retired keys | `perf.smart-swap.*`, `swap.auto-create.*` | removed (nothing reads them) | listed in the same INFO | yes |
| Aurora keys in the global TOML | `aurora.*` or `perf.ai.async-pathfinding` present | moved into `aurora.toml` (copied to `aurora.toml.migrated` first) under the current name with its comment; if `aurora.toml` already sets the key, the global copy is dropped (`aurora.toml` always won) | same INFO (`Aurora key(s) moved to sourbycraft_config/aurora.toml`) | yes |
| `perf.ai.async-pathfinding` inside `aurora.toml` | present without `aurora.entity.async-pathfinding` | renamed in place | same INFO | yes |
| `sourbycraft-security.yml` | — | no key changed; nothing is rewritten | — | — |

**Reading old names until Build 50.** Independently of the rewrite, every read resolves the old
utility names to the new ones (`ConfigMigration.normalize`), new name winning, with one WARN per
old key per load (`Deprecated config key 'misc.auto_update.enabled' ... is read as 'updater.enabled'
until Build 50`). `BootstrapPluginSettings` accepts both spellings before the configuration loads.
So a read-only `sourbycraft_config/` keeps working (the INFO becomes an ERROR
`configuration layout migration failed; the files were not changed and the old key names are still
read`), and CI directories written with the old layout boot unchanged. Build 50 removes the
old-name reads; the migration of the engine file names stays until the owner decides otherwise
([26.2-build-50.md](releases/26.2-build-50.md)).

**Downgrade.** A pre-49 jar reads `config/canvas-*.yml` and the old TOML names. Before starting
it: stop the server, then `cp -p config/aurora-server.yml config/canvas-server.yml`,
`cp -p config/aurora-worlds.yml config/canvas-worlds.yml`, and restore
`sourbycraft_global_config.toml.migrated` over `sourbycraft_global_config.toml` (and
`aurora.toml.migrated` over `aurora.toml` if it exists). Values edited after the migration live
only in the new files; merge them by hand. The `*.migrated` copies are never touched again by any
build, so they stay a faithful pre-49 snapshot.

**Verification on a real server copy** is a Build 49 runbook step
([runbook, configuration migration](releases/runbooks/build-49-real-test.md#configuration-migration-build-49-layout)).

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
example file and this table; the golden tests fail otherwise.

## 9. Canvas-era names (renamed in Build 48, old names removed in Build 50)

Owner decision 2026-10-21. The new name wins when both are present; the old one is read with one
`[Aurora]` WARN line naming its replacement. All RESTART_REQUIRED except the permission (LIVE).

| New name | Old name (read until Build 50) | Kind | Read at |
| --- | --- | --- | --- |
| `-DAurora.AggressiveChunkSystem` | `-DCanvas.AggressiveChunkSystem` | JVM flag | `MoonriseCommon` class init |
| `-DAurora.DisableRegionProfiler` | `-DCanvas.DisableRegionProfiler` | JVM flag, only `=true` counts | each region-profiler support check |
| `-DAurora.isRunningInIDE` | `-DCanvas.isRunningInIDE` | JVM flag | `SharedConstants` class init |
| permission `aurora.broadcasting.receiver` | `canvas.broadcasting.receiver` | permission | each `GlobalConfiguration.broadcast` |
| `Aurora.Distance` in `paper:level_overrides` | `Canvas.Distance` | saved world data | world load |
| `data/aurora/pearls.dat` | `data/canvas/pearls.dat` | saved server data | moved once before load |

The engine configuration **file names** were renamed in Build 49 with the migration of §7 (they
were kept in Build 48). The per-world `canvas-patch.yml` is not renamed.
