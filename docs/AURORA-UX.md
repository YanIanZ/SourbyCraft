# Aurora UX — SourbyCraft operator experience

> **Role:** operator console and command surface. **Status:** Active; describes implemented UI only and makes no performance claim.
>
> Entry point: [docs/architecture/AURORA.md](architecture/AURORA.md).

This document describes the terminal and command UI implemented on branch `26.2`.
Build identity remains SourbyCraft `Build N` with its codename, backed by Aurora Engine.
The UI does not establish performance improvement or feature qualification.

## The Build 49 console

Owner request (2026-10-22): throw the old console look away and design a new one. The Build 49
console is one line shape from launch to ready, grouped into sections, with colour only on a
real terminal. Nothing moves the cursor, nothing is animated, and every machine-read marker is
unchanged: `Done (`, the `SOURBY_*` / `LEGACY_*` plugin markers, `Aurora startup index: ...`,
the `Aurora startup cache ...` warnings and `logs/latest.log`'s line shape.

### Line shape

Console (TerminalConsole appender, `log4j2.xml`, paper feature patch 0016):

```text
HH:mm:ss LEVEL Tag          message
04:45:35 INFO  Aurora       Autosave every 5m; default tick rate 20.0 TPS
04:45:36 WARN  Minecraft    **** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!
04:45:36 INFO  SourbyCraft-Test-Plugin SOURBY_METRICS_ONLOAD_OK state=WARMING sequence=0
```

* `LEVEL` is always five characters (`INFO `, `WARN `, `ERROR`, `FATAL`, `DEBUG`, `TRACE`;
  `%scLevel`), so the tag and message columns line up for every level.
* `Tag` is the logger name folded onto five short tags for server internals and padded to 12
  characters (`%scLogger{console}`). A longer tag (a plugin name) is never cut: the message
  column shifts for that line instead. The tags: `SourbyCraft` (`dev.iyanz.sourbycraft.*`),
  `Aurora` (`dev.iyanz.aurora.*` and the region-threading engine's
  `io.papermc.paper.threadedregions.*`), `Minecraft` (`net.minecraft.*`, `com.mojang.*`, the
  `Minecraft` logger, the root logger), `Paper` (`io.papermc.*`, `ca.spottedleaf.*`,
  `org.bukkit.craftbukkit.*`, `com.destroystokyo.paper.*`, `org.spigotmc.*`) and `OSHI`
  (`oshi.*`). Internal classes that log by their simple class name (Mojang's
  `LogUtils.getClassLogger()`: `PluginInitializerManager`, `MoonriseCommon`,
  `ChunkHolderManager`, `TickRegions`, `TickRegionScheduler`, `WorldShutdownThread`,
  `ConfigurationProvider`, ...) fold onto `Paper` or `Aurora` through a fixed table in the
  converter; `StartupLogLayoutTest` scans the materialized sources so the table cannot go stale
  silently after a `paperRef` bump. The owned area loggers `SourbyCraft/<Area>` fold onto
  `SourbyCraft`. `com.sk89q.*` and `ru.tehkode.*` (plugins that bypass the plugin logger) print
  their simple class name. Every other logger name, plugin names included, is printed
  unchanged. No line prints an internal package name.
* Colour appears only when TerminalConsoleAppender reports ANSI support (a real terminal): the
  time in grey (`%scDim`), the level by severity (`%scLevel{console}`: INFO blue, WARN yellow,
  ERROR/FATAL red, lower levels grey), the `SourbyCraft` tag cyan and the `Aurora` tag violet
  below WARN, and the whole message of a WARN (yellow) or ERROR (red) line (`%scHighlight`).
  Redirected output (a hosted panel such as Pterodactyl, `> server.log`, `docker logs`) gets the
  plain text above; `%ansiIfTerminal` additionally strips escape codes carried by a message
  itself (command replies such as `/ver`). The converters append constants only; no string is
  built per event.
* File (`logs/latest.log`) keeps the shape log analysers parse, with the same short tags and
  every escape code removed (`%plainText`):
  `[04:45:35] [Server thread/INFO]: [Aurora] Autosave every 5m; default tick rate 20.0 TPS`.
* `RepeatedMessageFilter` prints two known repeated startup lines once: OSHI's WARN
  `Unable to parse version ... to a codename` and the INFO `Region tick threads: ...`
  (configured by the engine package as a logger-name prefix, so the patch names no scheduler
  class). Only the configured level is considered; the same text at another level, any ERROR or
  FATAL, any line with a stack trace and any line with different text is always printed.

The converters live in `sourbycraft-server/src/log4jPlugins/java/dev/iyanz/sourbycraft/console/`
(`SourbyCraftLoggerConverter`, `LevelConverter`, `HighlightConverter`, `DimConverter`,
`TerminalAnsiConverter`, `PlainTextConverter`, `RepeatedMessageFilter`, palette in
`ConsolePalette`); `StartupLogLayoutTest` renders the shipped patterns.

### Sections, launch to ready

1. **Launch.** SourbyClip prints its own launcher lines (library download progress). Then
   Paper's bootstrap logs two lines rendered by `brand/LaunchLines` (paper feature patch 0002)
   in place of its "Running Java ..." / "Loading <brand> <version>" pair:
   `SourbyCraft Build 48 - Aurora Nexus (DEV, <branch>@<commit>) for Minecraft 26.2, built 2026-10-22 03:36 Asia/Jakarta`
   and `Java 25 (<VM> <version>, <vendor>) on <OS> <version> <arch> | 8 processors | 2048 MiB max heap | ZGC`.
   A release build shows no channel suffix; a REL build made while `release=pre` shows
   `Build N@<commit>PR!` instead of the codename (`BuildInfo#releaseIdentity`). Any field that
   cannot be read falls back to `?`.
2. **`-- Engine ----`** (`brand/ConsoleSections`, printed by `core/SourbyCraftBootstrap#init`).
   The engine's service stages (19 on the current source, `TOTAL_STAGES` in
   `core/SourbyCraftBootstrap`; Build 48 had 15, as in the samples below) no longer print a line
   each: a stage that finishes is a log4j
   DEBUG event on the `Aurora` logger (`  3/19  configuration  20 ms  ok`, next to the existing
   `START n/19 <name>` event; show both with a copy of `log4j2.xml` that adds
   `<Logger name="Aurora" level="debug"/>` and `-Dlog4j.configurationFile=<file>`). A stage that
   throws prints one WARN, `Stage n/19 "<name>" FAILED after <ms> ms - see the error above; the
   engine continues without it`, after the stack trace; a stage still running after 10 seconds
   prints one WARN naming it. The section closes with one line (WARN when a stage failed):
   `Engine COMPLETE | 19/19 stages ok | 0 failed | 106 ms | slowest: startup index 23 ms`, or
   `Engine DEGRADED | 17/19 stages ok | 2 failed: configuration, commands | ...`
   (`scripts/run_baseline.py` `BOOT_SUMMARY` reads this line). The thread ledger
   (`execution/ThreadLedger`, configured ceilings per pool, not measured load) follows it. The
   stages run in this fixed order (`SourbyCraftBootstrap#init`): metrics runtime, network
   counters, configuration, sourby mods, stall recorder, aurora redis fabric, aurora worlds, awf
   multiprofile, plugin provisioning, jvm advisor, plugin diagnostics, startup index, commands,
   messages, player slots, virtual executor, abysslink, auto updater, gc tracker. The update
   rollback guard runs before the first stage and is not counted as one. `/aurora status`
   keeps the per-stage results after boot. "ok" means the stage returned without an exception;
   it does not prove the service it started is healthy. A stage whose feature is off (AWF
   MultiProfile, the Redis fabric, AbyssLink are off by default) is "ok", and so is a stage that
   logs its own failure instead of throwing (the Redis fabric when Redis is unreachable, AbyssLink
   with an unreadable settings file).
3. **`-- Plugins ----`** before Paper's `loadPlugins()` and the STARTUP enable pass, and
   **`-- Worlds ----`** before `Preparing level "<name>"` (both one-line calls in
   `DedicatedServer#initServer`, minecraft feature patch 0078); **`-- Plugins (post-world) ----`**
   before the POSTWORLD enable pass (`MinecraftServer#loadLevel`, same patch), where most plugins
   come up. Lines from other threads can still interleave with a section; the rules are a reading
   aid, not a lock.
4. **`-- Ready ----`** plus one summary line (`brand/ReadySummary`, logged by
   `startup/StartupTimeline` on `ServerLoadEvent(STARTUP)`, which precedes Minecraft's `Done (`):
   `SourbyCraft Build 48 - Aurora Nexus (DEV, ...) | Minecraft 26.2 | ready 16.5 s after JVM start (cold start; no previous profile) | 3 worlds | 1/1 plugin enabled | 22 configured threads on 8 processors`.
   The time is JVM start to the server-load event (the startup profile's total, compared with the
   previous boot of the same start class); a count that cannot be read prints as `unavailable`.
   `Done (` remains the server-ready marker.

The section rules use box-drawing characters only when `ConsoleStyle.unicode()` is true (an
interactive console, or `console.style = rich`); otherwise plain dashes, so a server started
under the C locale prints no `?`. The same switch picks `-` over the em dash in the build identity.
`console.style` (`auto` / `plain` / `rich`, `-Dsourbycraft.console=` overrides; RESTART_REQUIRED)
chooses that character set and whether the build identity in the launch line and the ready
summary is coloured by stage (`ConsoleStyle.color()`: `rich`, or `auto` on an interactive console;
`NO_COLOR` or `TERM=dumb` turn it off). The level, tag and message colours of log lines are
decided by the terminal's ANSI support, not by the setting. The launch lines and the Engine rule
print before the configuration stage, so since PR #62 `ConsoleStyle` reads `console.style` once
from `sourbycraft_config/sourbycraft_global_config.yml` for them (an unreadable file reads as
`auto`); before, they followed only `-Dsourbycraft.console`. PR #62 code is written and statically
reviewed; not built; not tested by the owner.

Removed with this design: the mid-boot banner written straight to stdout (`StartupBanner`,
`SourbyCraftBanner`; it bypassed `logs/latest.log` and broke the line shape), the 15 per-stage
progress-bar lines and the "Minecraft continues loading" hint, `%highlightError`, the
`LoggerNamePatternSelector` with its prefix-less vanilla pattern, and the `[tag]` brackets and
`[HH:mm:ss LEVEL]:` prefix on the console. The JVM flag advisor stays (`brand/JvmAdvisor`, WARN
lines on the `SourbyCraft` logger).

### Before / after (first lines after SourbyClip, local agent test server, redirected stdout)

Before (Build 48 stack, 2026-10-22):

```text
[03:45:31 INFO]: [bootstrap] Running Java 25 (OpenJDK 64-Bit Server VM 25.0.2+10-69; Oracle Corporation null) on Mac OS X 27.0.1 (aarch64)
[03:45:31 INFO]: [bootstrap] Loading SourbyCraft Build 48-dirty Thursday, 22 October 2026 03:36 for Minecraft 26.2
[03:45:31 INFO]: [SourbyCraft] ViaVersion auto-provision disabled (viaversion.auto-provision=false) — old clients will not be bridged.
[03:45:31 INFO]: [PluginInitializerManager] Initializing plugins...
[03:45:31 INFO]: [Aurora] Loading Aurora region configuration (config/canvas-server.yml)
[03:45:31 INFO]: [ConfigurationProvider] canvas-server.yml not found; writing the default configuration
[03:45:31 WARN]: [Aurora] SIMD is off: add "--add-modules=jdk.incubator.vector" to the startup flags, before "-jar", to enable it (if the flag is already there, this JVM or CPU does not support SIMD).
[03:45:32 WARN]: [OSHI] Unable to parse version 27.0 to a codename.
[03:45:34 INFO]: [DataConverter] Initialising converters for DataConverter...
[03:45:35 INFO]: [MoonriseCommon] SourbyCraft is using 2 worker threads, 1 I/O threads
[03:45:35 INFO]: [TickRegions] Region tick threads: 8 (aurora.cpu.cores unset; using all 8 available processor(s))
[03:45:35 INFO]: [Aurora]  1/15   7% [=...............]  OK    metrics runtime  (7ms)
[03:45:35 INFO]: [Aurora]  2/15  13% [==..............]  OK    network counters  (0ms)
...
[03:45:35 INFO]: [Aurora] 15/15 100% [================]  OK    gc tracker  (1ms)
[03:45:35 INFO]: [Aurora] COMPLETE | 15/15 stages completed without error | 0 failed | 106ms
[03:45:35 INFO]: [Aurora] Minecraft continues loading; wait for the server-ready message before connecting.
[03:45:35 INFO]: [Aurora] Thread ledger: 22 configured platform thread(s) in 8 pool(s) for 8 available processor(s) (2.8x); configured ceilings, not measured load
[03:45:36 INFO]: [SourbyCraft-Test-Plugin] Loading server plugin SourbyCraft-Test-Plugin v1.0.0
[03:45:36 WARN]: **** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!
[03:45:36 INFO]: Preparing level "world"
[03:45:41 INFO]: Done preparing level "world" (5.336s)
[03:45:45 INFO]: [SourbyCraft] Aurora startup: 16480 ms to ready, cold start; no previous profile
[03:45:45 INFO]: Done (14.337s)! For help, type "help"
```

After (this worktree, agent test server `fable-logdesign-2`, 2026-10-22, test plugin installed;
abridged to the matching lines, the full console is the run's `server.log`):

```text
05:17:32 INFO  bootstrap    SourbyCraft Build 48-dirty - Aurora Nexus (DEV, HEAD@432d141) for Minecraft 26.2, built 2026-10-22 05:15 Asia/Jakarta
05:17:32 INFO  bootstrap    Java 25 (OpenJDK 64-Bit Server VM 25.0.2+10-69, Oracle Corporation) on Mac OS X 27.0.1 aarch64 | 8 processors | 2048 MiB max heap | G1
05:17:32 INFO  SourbyCraft  ViaVersion auto-provision disabled (viaversion.auto-provision=false) — old clients will not be bridged.
05:17:32 INFO  Paper        Initializing plugins...
05:17:33 INFO  Aurora       Loading Aurora region configuration (config/canvas-server.yml)
05:17:33 INFO  Aurora       canvas-server.yml not found; writing the default configuration
05:17:33 WARN  OSHI         Unable to parse version 27.0 to a codename.
05:17:33 WARN  Aurora       SIMD is off: add "--add-modules=jdk.incubator.vector" to the startup flags, before "-jar", to enable it (if the flag is already there, this JVM or CPU does not support SIMD
05:17:36 INFO  Paper        Initialising converters for DataConverter...
05:17:39 INFO  Aurora       Region tick threads: 8 (aurora.cpu.cores unset; using all 8 available processor(s))
05:17:39 INFO  SourbyCraft  -- Engine ----------------------------------------------
05:17:39 INFO  SourbyCraft  Aurora startup index: 1 jar(s), cold start (hit 0, miss 1, discarded 0) in 24.5 ms
05:17:39 INFO  SourbyCraft  Registered 18 SourbyCraft commands (bare names; /minecraft:<name> or /paper:<name> still reach the built-ins). Native /spark is provided by the server integration.
05:17:39 INFO  Aurora       Engine COMPLETE | 15/15 stages ok | 0 failed | 127 ms | slowest: configuration 31 ms
05:17:39 INFO  Aurora       Thread ledger: 22 configured platform thread(s) in 8 pool(s) for 8 available processor(s) (2.8x); configured ceilings, not measured load
05:17:39 INFO  Aurora         Region tick             8  (paper-global.yml threaded-regions.threads)
05:17:40 INFO  SourbyCraft  -- Plugins ---------------------------------------------
05:17:40 INFO  SourbyCraft-Test-Plugin Loading server plugin SourbyCraft-Test-Plugin v1.0.0
05:17:40 INFO  SourbyCraft-Test-Plugin SOURBY_METRICS_ONLOAD_OK state=WARMING sequence=0
05:17:40 WARN  Minecraft    **** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!
05:17:40 INFO  SourbyCraft  -- Worlds ----------------------------------------------
05:17:40 INFO  Minecraft    Preparing level "world"
05:17:40 INFO  Aurora       Loaded Aurora world settings for level minecraft:overworld
05:17:46 INFO  Minecraft    Done preparing level "world" (6.133s)
05:17:46 INFO  SourbyCraft  -- Plugins (post-world) --------------------------------
05:17:48 INFO  SourbyCraft  -- Ready -----------------------------------------------
05:17:48 INFO  SourbyCraft  SourbyCraft Build 48-dirty - Aurora Nexus (DEV, HEAD@432d141) | Minecraft 26.2 | ready 20.0 s after JVM start (cold start; no previous profile) | 3 worlds | 1/1 plugin enab
05:17:48 INFO  Minecraft    Done (16.822s)! For help, type "help"
```

## Commands

Shared panels separate a title, sections, labelled values, navigation suggestions and footer.
Suggested commands also appear as literal text, so they remain usable from the console.
Clicking an action in Minecraft fills the command input; it does not execute it.

| Command | Information |
| --- | --- |
| `/sourbycraft` | Operator hub and command guide |
| `/aurora` or `/aurora status` | Aurora service state, active bridge mode and boot stages |
| `/sourbycraft config` or `/aurora config` | File locations, loaded settings and lifecycle groups |
| `/sourbycraft reload` | Explicit reload with errors and restart requirements. Since PR #62 (Build 50 source; not in the published `v26.2-r50` prerelease; code written and statically reviewed, not built, not tested by the owner) it reads and applies the files on the SourbyCraft I/O executor, not on the tick thread that ran the command: the sender gets `Reloading configuration...` at once and the report panel when the reload finishes. Refused while the runtime is stopping or failed to start, and when the I/O executor is stopped or full |
| `/ver`, `/version`, `/about` | Release, build time, runtime, API and source identity |
| `/sys` | System summary, measured region health and plugin counts |
| `/spec` | Hardware and JVM facts; unreadable fields say unavailable |
| `/perf [view]` | Existing diagnostics with sectioned overview and navigation |
| `/tps`, `/mspt` | Region metrics, tick budget and freshness |
| `/ping [player]` | Latency, client and asynchronous location lookup |

Existing command permissions still apply. `/aurora` uses `sourbycraft.command.admin`.
The `/canvas` dispatcher was removed by the existing feature patch; `/aurora` provides the operator hub. Historical configuration paths remain in use.
Namespaced built-ins are preserved when SourbyCraft claims bare names. Command replies sent to
the console carry Adventure colour; on a redirected console `%ansiIfTerminal` strips it.

## Plugins

`/pl` and `/plugins` resolve to the same command instance and share permissions, output,
search, filters and tab completion. Names are sorted without putting versions in the roster.
Default page size is 12; `console.plugins-page-size` is clamped to 1–40 and changes after reload
(LIVE). Its pre-Build-49 name `ui.plugins-page-size` is no longer read since PR #62 (not in any published build);
the one-time boot migration renames it.

```text
/plugins                  first page
/pl 2                     second page
/plugins search vault     case-insensitive name search
/plugins filter failed    failures observed during load, enable or bridge operation
/plugins Vault            version, enabled state, support declaration and bridge telemetry
```

Search and filter accept an optional page number. Previous/next suggestions retain the query
or filter. Hover shows the status explanation; clicking suggests the detail command. Jar-load
failures include their filename and reason in details. A plugin whose jar is in `plugins/` but that
the plugin manager never created (missing hard dependency, dependency cycle, invalid descriptor, Aurora
Bridge refusal) counts as failed with its reason; see `docs/architecture/aurora-plugin-bridge.md`.
Failures are a bounded recent history (16 jars), deduplicated per jar, rather than a complete lifetime
failure ledger.

| Status | Meaning |
| --- | --- |
| NATIVE / blue | Declares region-threading support |
| BRIDGED / green | Admitted through Aurora Bridge |
| FAILED / red | A load/enable failure or fatal bridge violation was observed |
| DISABLED / grey | Loaded but disabled; this alone does not establish a failure |

Neither NATIVE nor BRIDGED certifies safety. `/sys` reports disabled plugins separately from
recent jar-load failures and points to `/plugins` for details.

## Gauges and unavailable data

Command/HUD gauges use a compact bracketed `█`/`·` style. TPS shows progress against the
configured target in the region diagnostics; MSPT shows tick-budget use; memory shows use of
the reported maximum. Ping's gauge is full at 0ms and empty at 500ms or more. Unknown numeric
measurements are displayed as unavailable rather than fabricated zero or full values.
Performance command consumers retain the immutable-snapshot and freshness contract.

## Configuration and verification

See [Aurora configuration](AURORA-CONFIG.md) and the annotated
[UI section](config-examples/utility-ui.yml) / [Aurora defaults](config-examples/aurora.yml).
Both files are YAML since Build 49 (`sourbycraft_config/sourbycraft_global_config.yml`,
`sourbycraft_config/aurora.yml`). A reload never rewrites them. Boot rewrites a file only once, to
convert an old `*.toml` or bring a pre-49 layout to the Build 49 one (copy kept as `*.migrated`);
otherwise defaults and comments are written only into a new (or just migrated) file and an
existing file is seeded in memory only. The one write outside boot is explicit: `/maxp <n>`
persists that single key into the global file (re-read first, so other operator edits are kept).
Message variants are not replaced in existing deployments.

UI regression tests: `console/StartupLogLayoutTest` (shipped patterns, tags, level column,
no escapes off a terminal, repeat filter), `brand/AuroraBootTest` (engine summary and the
`run_baseline.py` regex), `brand/ConsolePresentationTest` (launch lines, section rules, ready
summary, unavailable counts), `core/SourbyCraftBootstrapTest` (a failed stage is the only
visible stage line), `scripts/test_baseline.py` and `scripts/test_agent_testserver.py` (the
summary the scripts parse). These are functional checks; release qualification remains separate.

Animated consoles, predicted remaining time, profiler controls and automatically suggested
hardware tuning are not implemented by this UI change.
