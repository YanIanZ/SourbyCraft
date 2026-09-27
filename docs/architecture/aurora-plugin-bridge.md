# Aurora Compatibility Bridge

Aurora Bridge adapts supported classes of legacy Paper/Spigot plugin behavior to SourbyCraft's region-threaded runtime.

## States
- NATIVE — declares Folia or SourbyCraft support.
- BRIDGED — legacy plugin successfully enabled through supported mappings.
- FAILED — load/enable/fatal bridge failure.
- DISABLED — operator-disabled.

## `/plugins` color contract
- Native: `#4DA3FF`
- Bridged: `#57D68D`
- Failed: `#FF5C70`
- Disabled: `#8B949E`

Green means “running through Aurora Bridge”; it never means official Folia support.

## Safety
Default mode is SAFE. Unknown/unsafe world mutation is rejected and diagnosed rather than run on an arbitrary thread.

## Routing
Entity-owned -> entity owner. Location-owned -> region owner. Global-safe -> global region. I/O -> plugin/I/O lane. Unknown unsafe -> reject.

## Telemetry
Per-plugin compatibility state, scheduler redirects, owner handoffs, rejected operations, fatal violations, quarantine, startup duration/cache state and last failure.

## Implementation status (26.2 branch)

Code: `dev.iyanz.sourbycraft.bridge` plus four Paper patches under
`sourbycraft-server/paper-patches/files`.

- **Mode.** `aurora.bridge.mode` in `aurora.toml` (or the unified file): `off` (default) keeps the
  base's refusal; `safe` admits. It is read straight from the TOML files on first use, because
  plugin providers are built during Paper bootstrap, before `SourbyCraftBootstrap`; it is
  RESTART_REQUIRED. A malformed value means `off`. `off` is the default rather than SAFE because
  correctness outranks compatibility in the safety order: admitting unqualified code is the
  operator's decision.
- **Admission.** `PaperPluginProviderFactory`, `SpigotPluginProviderFactory` and
  `CraftMagicNumbers#checkSupported` call `AuroraBridge.admitLegacy(name)` before throwing.
- **Routing.** `CraftScheduler#handle` hands a bridged plugin's task to the bridge instead of
  throwing. Sync tasks name no entity or location, so they go to the global region; async tasks are
  timed by Folia's async scheduler and run on the Resource Governor's bounded `BRIDGE_IO` lane.
  `cancelTask`/`cancelTasks` cancel bridged tasks; disable cancels them too, because Folia no
  longer does for the Bukkit scheduler. `BridgeRouter` also defines the entity-owner and
  region-owner routes. No caller supplies them yet, because a Bukkit task names neither.
- **Violations.** A task body's `UnsupportedOperationException`/`IllegalStateException` from the
  base's region checks counts as a fatal violation. Other exceptions are recorded as the last
  failure, as the plain server would log them. After `aurora.bridge.quarantine-after` (LIVE,
  default 3) violations, the plugin's bridged tasks are cancelled and new ones are rejected. The
  plugin is not disabled: disabling would run plugin code on whichever thread noticed the
  violation.
- **Telemetry.** Per plugin: scheduler redirects, owner handoffs (0 until owner routes have
  callers), rejected operations, fatal violations, quarantine, startup cache state and last
  failure. `/plugins <name>` shows them.
- **Not intercepted.** A bridged plugin calling world API directly from a bridged task on the
  global region is not rewritten; the base's own thread checks decide what happens. Async tasks
  run as `CraftAsyncTask` bodies without its worker bookkeeping, so `getActiveWorkers` does not
  list them.
- **Sync route.** `aurora.bridge.sync-route` (LIVE), default `caller-region`: a legacy sync task
  scheduled while a region is ticking runs on that region, anchored on a chunk it owned
  (`execution/region/FoliaCurrentRegion`, ledgered; anchors cached per region id). From anywhere
  else it runs on the global region. `global` restores the previous behaviour. Tasks routed to
  a region count as owner handoffs.
- **`isQueued` / `isCurrentlyRunning`** answer for bridged tasks (CraftScheduler patch).
  `getPendingTasks` / `getActiveWorkers` still do not list them.
- **Operator guide:** [testing legacy plugins](../guides/testing-legacy-plugins.md).
- **CI evidence (run 440, `9c933375`, 2026-09-27).** The Paper patches applied. A second CI boot
  with `aurora.bridge.mode = "safe"` loaded `legacy-test-plugin` (no `folia-supported`):
  - admission line logged; all 13 boot stages online;
  - `runTask` ran on `Folia Region Scheduler Thread #3` (the global region);
  - `runTaskAsynchronously` ran on `SourbyCraft-BridgeIO-1`;
  - a self-cancelling `runTaskTimer` ran 3 times and never after cancel;
  - a world access from a sync task was refused by the base (`IllegalStateException`) and
    counted as 1 fatal violation, below the quarantine threshold;
  - `/plugins LegacyBridgeTest` reported 4 scheduler redirects; `/perf governor` reported Bridge
    I/O 1 submitted, 1 completed, 0 rejected; clean exit.
- **Still unverified.** That fixture is synthetic. No real legacy plugin has been run through the
  bridge, and nothing here qualifies one.

## Qualification
A plugin cannot be presented as BRIDGED until load, enable and bridge initialization succeed and no fatal compatibility violation is present.
