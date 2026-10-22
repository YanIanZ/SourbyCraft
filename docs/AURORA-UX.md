# Aurora UX — SourbyCraft operator experience

> **Role:** operator console and command surface. **Status:** Active; describes implemented UI only and makes no performance claim.
>
> Entry point: [docs/architecture/AURORA.md](architecture/AURORA.md).

This document describes the terminal and command UI implemented on branch `26.2`.
Build identity remains SourbyCraft `Build N` with its codename, backed by Aurora Engine.
The UI does not establish performance improvement or feature qualification.

## Startup console

The new banner separates release, Minecraft/channel, Java, JVM-visible processors and
maximum heap into labelled rows. Long build identities are preserved.

Aurora prints one line per bootstrap service stage when the stage finishes (15 stages,
`TOTAL_STAGES` in `core/SourbyCraftBootstrap`): a 16-cell progress gauge, `OK` or `FAIL`, the
stage name and its elapsed time. A stage still running after 10 seconds also gets one WARN,
`Stage N/15 "<name>" is still running after 10000 ms`, so a hang names its stage without extra
logging. The `START N/15 <name>` line before each stage is a log4j DEBUG event on the `Aurora`
logger and is not printed by default; to see it, start the server with a copy of `log4j2.xml`
that adds `<Logger name="Aurora" level="debug"/>` inside `<Loggers>`
(`-Dlog4j.configurationFile=<file>`). The stages run in this fixed order: metrics runtime,
network counters, configuration, stall recorder, aurora worlds, plugin provisioning, banner, plugin diagnostics,
startup index, commands, messages, player slots, virtual executor, auto updater, gc tracker.
Output is append-only, with no cursor movement, artificial waits or guessed time remaining.

```text
[Aurora] 10/15  67% [==========......]  OK    commands  (14ms)
...
[Aurora] COMPLETE | 15/15 stages completed without error | 0 failed | 61ms
[Aurora] Minecraft continues loading; wait for the server-ready message before connecting.
```

A caught stage exception prints `FAIL`, keeps the accumulated failure count visible on later
stages and yields `DEGRADED` or `FAILED` in the summary. A 100% gauge means all stage attempts
finished. A stage returning without an exception does not prove its internally managed services
are ready. Minecraft, worlds and plugins finish loading afterwards; `Done (` remains the
server-ready marker. `/aurora status` retains those stage results for inspection after boot.

`ui.console-style` controls the banner, Aurora progress and JVM advisor renderers. In the utility TOML it selects `auto`, `plain` or `rich`. `auto` uses rich output
when `System.console()` is available, otherwise plain. These renderers emit ASCII progress bars and no ANSI in plain mode. Other loggers retain their own formatting. `-Dsourbycraft.console=rich` is available for panels whose terminal support is not
reported to the JVM. `NO_COLOR` and `TERM=dumb` disable ANSI even in rich mode. The startup
panel and JVM advisor use a UTF-8 stream. Changes affect the next startup.

## Log format

`log4j2.xml` (paper feature patch 0016) prints the logger through `%scLogger`
(`sourbycraft-server/src/log4jPlugins`). Only a fixed list of server-internal namespaces is
shortened: `dev.iyanz.sourbycraft.*` prints as `SourbyCraft/<Class>`, `dev.iyanz.aurora.*` as
`Aurora/<Class>`, `ca.spottedleaf.dataconverter.*` as `DataConverter`,
`oshi.*` as `OSHI`, and `io.papermc.*`, `ca.spottedleaf.*`, `net.minecraft.*`,
`org.bukkit.craftbukkit.*`, `com.mojang.*`, `com.destroystokyo.paper.*` as the simple class name.
Every other logger name, including plugin and library names with dots, is printed unchanged.

Escape codes: in `logs/latest.log` the whole rendered message and exception text go through
`%plainText`, which removes ANSI escape sequences (`ESC [ ... letter`). On the console the same
part goes through `%ansiIfTerminal`, which keeps colours only when TerminalConsoleAppender reports
ANSI support (a real terminal) and strips them otherwise (panels, `> server.log`, `docker logs`);
`%scLogger` colours the `SourbyCraft`/`Aurora` tags only on such a terminal and below WARN. This
covers lines written through log4j. It does not cover text written straight to stdout: the startup
banner (console only, by design; plain unless `ui.console-style` resolves to rich), SourbyClip's
launcher lines before log4j starts, and JVM warnings such as the `sun.misc.Unsafe` notice from
joml.

`RepeatedMessageFilter` prints two known repeated startup lines once: OSHI's WARN
`Unable to parse version ... to a codename` and the INFO `Region tick threads: ...` (configured as logger prefix `Tick`, so the patch names no scheduler class). Only the
configured level is considered; the same text at another level, any ERROR or FATAL, any line with
a stack trace, and any line with different text is always printed. SourbyCraft's own loggers are
named `SourbyCraft`, `SourbyCraft/<area>` (for example `SourbyCraft/AsyncPath`, `SourbyCraft/AWF`)
and `Aurora`.

## Commands

Shared panels separate a title, sections, labelled values, navigation suggestions and footer.
Suggested commands also appear as literal text, so they remain usable from the console.
Clicking an action in Minecraft fills the command input; it does not execute it.

| Command | Information |
| --- | --- |
| `/sourbycraft` | Operator hub and command guide |
| `/aurora` or `/aurora status` | Aurora service state, active bridge mode and boot stages |
| `/sourbycraft config` or `/aurora config` | File locations, loaded settings and lifecycle groups |
| `/sourbycraft reload` | Explicit reload with errors and restart requirements |
| `/ver`, `/version`, `/about` | Release, build time, runtime, API and source identity |
| `/sys` | System summary, measured region health and plugin counts |
| `/spec` | Hardware and JVM facts; unreadable fields say unavailable |
| `/perf [view]` | Existing diagnostics with sectioned overview and navigation |
| `/tps`, `/mspt` | Region metrics, tick budget and freshness |
| `/ping [player]` | Latency, client and asynchronous location lookup |

Existing command permissions still apply. `/aurora` uses `sourbycraft.command.admin`.
The `/canvas` dispatcher was removed by the existing feature patch; `/aurora` provides the operator hub. Historical configuration paths remain in use.
Namespaced built-ins are preserved when SourbyCraft claims bare names.

## Plugins

`/pl` and `/plugins` resolve to the same command instance and share permissions, output,
search, filters and tab completion. Names are sorted without putting versions in the roster.
Default page size is 12; `ui.plugins-page-size` is clamped to 1–40 and changes after reload.

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
[UI section](config-examples/utility-ui.toml) / [Aurora defaults](config-examples/aurora.toml).
Boot/reload preserves existing utility and Aurora TOML files. New defaults and comments are written only for
new files; message variants are not replaced in existing deployments.

UI regression tests cover plain/rich rendering, failure summaries, alias registration,
search/filter/page boundaries, typed defaults, file preservation and restart comparison.
Compile, server-suite, policy and isolated boot evidence is recorded with this change's
entry in `docs/TODO.md`. These are functional checks; release qualification remains separate.

Animated consoles, predicted remaining time, profiler controls and automatically suggested
hardware tuning are not implemented by this UI change.

## Local validation 2026-10-06

- [x] Compile and complete server test task with Temurin 25.0.4.1 / Gradle 9.8.0 passed.
  The final reload-report change was also checked through RuntimeModernizationTestSuite and
  SourbyMetricsTestSuite, then packaged again with `slimServerJar`.
- [x] `python3.12 -m unittest discover -s scripts -p 'test_*.py'`: 243 tests passed.
- [x] Server JAR manifest and properties agree on Build 47 / DEV; slim API metadata is
  `26.2-R0.1-SNAPSHOT` with `currentApiVersion=26.2`.
- [x] Isolated console boot reached the actual ready event and stopped with exit code 0.
  All 13 Aurora stages completed without an exception. `/pl` and `/plugins` panels were
  identical; sorted pages, search, failed/disabled filters, operator/status/config/version
  and system/hardware/performance/tick views were exercised.
- [x] A real missing-main-class plugin fixture appeared as a load failure. UI page size changed
  after reload. A CPU change remained restart-required across repeated reloads, without a
  contradictory restart summary. Malformed Aurora TOML reported failure and preserved the
  loaded snapshot. Existing utility/Aurora TOML bytes remained unchanged during boot/reload.

Local evidence lives under ignored `build/ui-refresh/`: `verify_ui.py`,
`run-accepted/server.log` and `run-accepted/command-panels.json`. This probe used a fresh world,
loopback binding, an offline-mode fixture server and disabled update/provisioning settings.
It checks console behavior; Minecraft-client click/hover rendering was not visually tested.

Local artifact: `build/libs/SourbyCraft-slim.jar` (DEV, Build 47).
SHA-256: `3b01edfa88b85641669cc54d714cd154859f1ed33b95f85f8048446684726a2f`.
Official toolchain pins and `release=pre` were not changed; this is local functional evidence.
The existing `writeBuildInfo` configuration-cache opt-out still produces a cache-serialization
warning and discards that cache entry; the compile/test/package tasks complete successfully.
