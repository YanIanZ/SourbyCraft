# Aurora Bridge: real legacy plugins, 2026-10-22

Owner request 2026-10-22 ("tambahkan beberapa plugin bridge"): run more real legacy (non-Folia) plugins
through the Aurora Compatibility Bridge, find what fails and why, and add a targeted adapter where a failure
is a scheduler-ownership pattern the bridge can route.

**Result: no adapter added.** None of the failures seen is a scheduler-ownership pattern. Every plugin that
failed to enable fails on the plugin side: it is too old for 26.2, it checks the server version itself, or
a dependency is missing. The one region violation came from a console command body, not from a scheduled
task. The bridge, the guards and region ownership are unchanged. This is functional evidence from headless
boots with **no player connected**. It is not a qualification: see "What this does not cover" at the end.

## Setup

- Worktree `sc-worktrees/opus-bridge-plugins`, base `6176d517` (26.2), no source change. Slim jar built
  there with `slimServerJar`, SHA-256 `034a929e8fcb8116…` (`run.json` `jar.sha256` of every run below).
- Agent test server (`scripts/agent_testserver.py`), flat default world, view distance 4, 2 GiB heap,
  offline. `sourbycraft_config/aurora.toml` held only `[aurora.bridge] mode = "safe"`. Other bridge settings
  were at their defaults (`sync-route = caller-region`, `quarantine-after = 3`, no limits).
- Plugin jars came from this machine only, nothing was downloaded. Sources: `/Volumes/Ext/Backup Download/13-oct-2026/`
  (the owner's downloads), `/Volumes/Ext/Sourby/Setup/Lobby/plugins/`, `/Volumes/Ext/Sourby/TestServer/plugins/`. Each was
  installed with `--config-file plugins/<name>.jar=<file>`. Their hashes are in `run.json` `config_files`.
- Run directories: `/Volumes/Ext/Sourby/agent-testservers/opus-bridge-plugins-{1,2,3,4}/` (`server.log`, `run.json`).
  Runs 2–4 stopped with exit 0 (`stop_reason` requested). Run 1 is explained below.
- Console features were run with `cmd --raw`, and output was read from `server.log`. See the harness notes.
- The host went into macOS idle/maintenance sleep during runs 2 and 3 (`pmset -g log`: sleeps 06:42–06:48,
  06:51–07:06, 07:06–07:21). That explains wall-clock jumps in the logs, such as `Done (969.919s)` in run 3
  and ntdLuckyBlock's own "Enabled (took 928622 ms)", whose monotonic `/perf plugins` enable time is 31.7 s.
  These are not server stalls. The later runs used `caffeinate`. No timing in this document is a measurement.

## Results

Counters are `/perf plugins` "redirects / handoffs / rejected / violations" at the end of the run.
"Bridged" means `/plugins` showed the plugin as Bridged (green) and enabled. It does not mean qualified.

| Plugin | Version | SHA-256 (jar) | Source | Run | Result | Bridge counters | Console features exercised | Adapter |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Vault | 1.7.3-b131 | `a6b5ed97f43a5cf5…` | Backup Download `Vault.jar` | 2, 3, 4 | Bridged, enabled, no ERROR | 3 / 0 / 0 / 0 (run 2) | Economy provider for Essentials ("Vault Compatibility Layer"), update check (async) | none needed |
| EssentialsX | 2.22.1-dev+25-cfb6f12 | `092d610f0b6a6d50…` | Backup Download | 1, 2 | Bridged, enabled, hooked Vault economy | 12 / 0 / 0 / 0; 78 global sync bodies, 5 async | `eco`, `balance`, `baltop`, `seen`, `kit`, `warp`, `broadcast`, `essentials debug` (replies correct; no player exists) | none needed |
| LuckPerms | 5.5.10 | `93b41b00077143c0…` | `Setup/Lobby/plugins` | 1, 2 | Bridged, enabled, H2 storage | 5 / 0 / 0 / 0 | `lp info`, `lp creategroup`, `lp group … permission set`, `lp sync` (async update task completed), `lp verbose on` | none needed (note: LuckPerms 5.5.60, also on this machine, declares `folia-supported: true` and runs NATIVE, without the bridge) |
| PlaceholderAPI | 2.12.2 | `ff76af20c7acf327…` | Backup Download | 2, 3, 4 | NATIVE (declares Folia support), included as a dependency only | — | `papi ecloud status` | — |
| DeluxeHubReloaded | 3.7.2 | `6d788bf1c4c93458…` | Backup Download | 3 | Bridged, enabled, hooked PlaceholderAPI, 18 modules | 10 / 0 / 0 / 0; 156 global sync bodies (a repeating timer) | Commands are ACF-registered and player-only from the console | none needed so far |
| ntdLuckyBlock | 2.8.34 | `5aca2bc3616c2865…` | Backup Download | 3 | Bridged, enabled | 2 / 0 / 0 / 0 | `lb list`, `lb destroy`, `lb reload` OK; **`lb place world 0 80 0 YELLOW` refused** (below) | not routable (below) |
| ChatGames | 1.2.1 | `316fdd163662bae5…` | Backup Download | 4 | Bridged, enabled | 3 / 0 / 0 / 0 | `chatgames math` (game started and broadcast), `top`, `reload` | none needed |
| ReMinions | 2.0.7 | `25bad088f529f84c…` | `TestServer/plugins` | 4 | Bridged, enabled | 2 / 0 / 0 / 0; 969 global sync bodies (repeating) | `minions` help, usage errors | none needed |
| Skript | 2.12.0 | `2f04a120ba3bc733…` | `Setup/Lobby/plugins` | 1 | **Enable failed**: `NoSuchMethodError GsonComponentSerializer$Builder.downsampleColors()` in its shaded `BungeeComponentSerializer` | — | — | Needs a newer plugin: 26.2 ships Adventure 5.2.0, which no longer has that method (`javap` on the server's `adventure-text-serializer-gson-5.2.0`). Would fail the same way on Paper 26.2. Its log says 2.17.1 exists. |
| skript-reflect | 2.6 | `8c472149d64148ab…` | `Setup/Lobby/plugins` | 1 | Disabled itself because Skript was disabled | — | — | depends on Skript |
| LibsDisguises | 11.0.18 (Free) | `6ca1c91d4f620785…` | Backup Download | 3 | Disabled itself: "wrong version of Lib's Disguises for your server" | 0 | — | Needs a plugin update (its own version gate) |
| GadgetsMenu | 5.27.0 | `e9c8cbbf7948e1d7…` | `Setup/Lobby/plugins` | 3 | Disabled itself: "only works on spigot versions from 1.8 to 26.1" | 0 | — | Needs a plugin update (its own version gate) |
| MythicDungeons | 1.4.1F | `5ac1176b68dbdb38…` | Backup Download | 3 | Disabled itself: requires ProtocolLib | 0 | — | Not tested further: ProtocolLib (10.5 MB) is over the harness's 8 MiB `--config-file` jar limit, and the harness turns auto-provisioning off |
| StrikePractice | 3.13.0-SNAPSHOT | `ac94587a8e463913…` | Backup Download | 4 | **Enable failed**: `StringIndexOutOfBoundsException` in `VersionUtil` (`Range [24, 22)` of a 22-char string, i.e. `org.bukkit.craftbukkit` without the version suffix that Paper dropped in 1.20.5) | — | — | Needs a plugin update. Not bridge-related; would fail on Paper 26.2. |
| SimplePets | R5-B297 | `bee4d9774233cd5c…` | Backup Download | 4 | **Enable failed**: shaded NBT-API 2.15.7 does not support 26.2, the plugin disables itself, then `zip file closed` | — | — | Needs a plugin update |
| AlonsoLevels | 2.4-BETA-PRO | `065bfe9aec3da384…` | Backup Download | 4 | Disabled itself: needs a JS engine for its exp formula (its own message) | 0 | — | Needs a config change or dependency (out of scope) |
| AlonsoTags / BetterProfiles | 2.3-BETA / 1.6-BETA | `61795058…` / `a2abb0d8…` | Backup Download | 4 | Disabled themselves: NBTAPI missing. BetterProfiles also NPEs in its own `onDisable` | 0 | — | Missing dependency (NBTAPI) |
| AlonsoChat | 0.2-BETA-DEV-140 | `ad612a73c866087c…` | Backup Download | 4 | Disabled itself: AlonsoLib missing | 0 | — | Missing dependency |

Every plugin that got as far as scheduling work had **0 rejected operations, 0 fatal violations, not
quarantined** at every sample. `/perf governor` in run 2 showed Bridge I/O 10 submitted / 10 completed / 0 failed /
0 rejected. Owner handoffs are 0 everywhere because no region was ticking: with no player, every task was
scheduled from startup, the console or the global region, so it ran on the global region, as designed.

### The one region violation: ntdLuckyBlock `/lb place` from the console

```
[TickThread] Thread failed main thread check: Cannot getEntities asynchronously, context=[thread=Folia Region Scheduler Thread #3,...region={god-tick}]
  at org.bukkit.craftbukkit.CraftWorld.getNearbyEntities(...)
  at ntdLuckyBlock.jar//me.DenBeKKer.ntdLuckyBlock.api.LuckyBlockAPI.clearBlock(Unknown Source)
  at ntdLuckyBlock.jar//me.DenBeKKer.ntdLuckyBlock.a.b.onCommand(Unknown Source)
  at ... DedicatedServer.handleConsoleInputs -> RegionizedServer.globalTick
```

A console command runs on the global tick. Its body is the plugin's `onCommand`, which reads entities at a
world location directly. **No Bukkit scheduler task is involved**, so the bridge never sees it, and there is
nothing for an owner adapter to route. The engine refused the access and the command printed the plugin's own
error message. It is not counted as a bridge violation because it did not happen inside a bridged task body;
it is the base's thread check, the same as on Folia. A player running `/lb place` would run on the player's
region, and the result depends on whether the target is in that region. That is untested here (player flow).
Classification: needs a native port (or command dispatch to the target region) in the plugin.

## Why no adapter

The SSB2 adapter (`SuperiorSpawnTaskOwner`) exists because one **scheduled callback** whose target location
can be read from its captured state (hash-pinned bytes) ran on the wrong owner. In these runs:

- no bridged task body threw a region-ownership exception or tripped `GuardViolations`,
- no task was rejected or quarantined,
- every enable failure happened before or apart from the bridge (Adventure 5 API, server-version parsing,
  self version gates, missing dependencies).

Writing an owner adapter with nothing failing would mean guessing at callbacks that may never run on the wrong
owner, which is what the adapter rules say not to do (fingerprint a callback only after it is audited as
failing). Candidates for a later adapter would show up in **player flows**, which these runs cannot reach. The
repeating global timers of DeluxeHubReloaded (156 bodies) and ReMinions (969 bodies) are where to look first.
With players online, a global timer that touches players or their blocks is refused by the engine and counted
as a violation. An adapter can only help there when the callback's target can be read from its captured state.
A loop over `Bukkit.getOnlinePlayers()` has no single owner, so it needs a native port.

**Status 2026-10-25 (PR #62).** These runs used Build 48 base `6176d517`, whose region-ownership checks behave
like today's `aurora.bridge.thread-checks = enforced`. Since Build 49 the checks are a setting, and with
`aurora.bridge.mode = safe` the default is `relaxed` (owner decision 2026-10-22). The refusals above and in this
section then apply only to `enforced` (or to the bridge off): under `relaxed`, a global timer touching players or
their blocks, and the ntdLuckyBlock console `onCommand`, proceed on the wrong thread and are counted as relaxed
ownership hits, not refused and not violations; under `bridged`, the timer body is relaxed and the console command
(not a bridged body) is still refused. Relaxed is unsafe and unqualified. The PR #62 bridge changes (code written and
statically reviewed; not built; not tested by the owner) are not covered by these runs: NEEDS RETEST.

## Harness notes (for the harness owner)

- **EssentialsX breaks the harness's console probes.** Essentials registers `/time` and `/help`. The readiness
  probe (`time query gametime`) gets Essentials' usage text, so `start` failed with "console commands still
  failing 90s after Done" (run 1; the server itself had booted, `Done (57.319s)`). The capture markers use
  `help <token>`, so bracketed `cmd` reports "delivery uncertain". Workaround used in run 2: seed
  `plugins/Essentials/config.yml` with `disabled-commands: [time, help]`. This let `start` succeed, but
  disabling `help` turns the marker line into "Unknown command", so `cmd` still needs `--raw`.
- `--config-file` jars are capped at 8 MiB, so FastAsyncWorldEdit 2.15.1 (15.3 MB, no `folia-supported`) and
  ProtocolLib (10.5 MB) could not be installed. FAWE is the most common non-Folia plugin left untested here.
  The `--plugin-file` option being added by opus-harness-modules would remove this limit.

## What this does not cover

- **No player joined.** Joins, chat, inventories and menus (DeluxeHub selector, Essentials homes/teleports,
  LuckPerms contexts on a player, ChatGames answers, ReMinions placement, LuckyBlock break) are untested. Under
  the owner-handles-testing rule these are owner-run.
- No load, soak, region split/merge, world unload or reload (`/reload`, PlugMan) with these plugins.
- One boot per group on a shared, partly sleeping laptop. No timing in this document is evidence.
- Bridged means "loaded, enabled, no fatal violation counted yet". See
  [aurora-plugin-bridge.md, Qualification](../architecture/aurora-plugin-bridge.md#qualification). (Corrected
  2026-10-25: the code shows Bridged while the plugin is not quarantined; fatal violations below
  `quarantine-after` and relaxed ownership hits do not change the state.)
