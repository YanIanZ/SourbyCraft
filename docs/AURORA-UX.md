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

Aurora prints a `START` line before each of its 14 bootstrap service stages (`TOTAL_STAGES` in
`core/SourbyCraftBootstrap`; the sample below was captured when there were 13), followed by a
16-cell progress gauge, stage result and monotonic elapsed time. Each completion is logged
immediately; a blocked stage therefore leaves its name visible. Output is append-only, with
no cursor movement, artificial waits or guessed time remaining.

```text
START 8/13  commands
 8/13  62% [==========......]  OK    commands  (4ms)
...
COMPLETE | 13/13 stages completed without error | 0 failed | 137ms
Minecraft continues loading; wait for the server-ready message before connecting.
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
failures include their filename and reason in details. Captured failures are a bounded recent
history, captured from both JUL and Log4j and deduplicated per jar, rather than a complete lifetime failure ledger.

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
entry in `TODO.md`. These are functional checks; release qualification remains separate.

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
