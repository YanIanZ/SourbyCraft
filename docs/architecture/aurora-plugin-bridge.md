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

Failed also counts plugins that never loaded (IMPLEMENTED, LIVE, no setting). At `ServerLoadEvent STARTUP`,
`startup/UnloadedPlugins` compares the descriptors the startup index read from `plugins/*.jar` with the plugin
manager: a descriptor whose plugin the manager never created is recorded in `PluginLoadDiagnostics` with a reason
from the startup dependency graph (`Missing hard dependency: Citizens`, `Hard dependency did not load: B`, a
dependency cycle, invalid YAML, or "the server log has the cause"). No log line is parsed for this: Paper's modern
loader reports such a plugin only as `Could not load 'plugins/x.jar' in 'plugins'`, which the log capture does not
match, so `/plugins` used to say `0 failed` beside it. A bridge refusal (`aurora.bridge.mode = off`) is recorded
when it happens and is not counted twice. `/plugins`, `/plugins filter failed`, `/plugins <name|jar>`, `/perf
plugins` and `/sys` read the same record. Limits: a jar without `plugin.yml`/`paper-plugin.yml` is treated as a
library and not reported; the record is a bounded recent history (16 jars); a plugin missing from the index (added
after it ran) is not reported.

## Safety
Default mode is OFF. Operators can enable SAFE on a test server; unknown/unsafe world mutation is rejected and diagnosed rather than run on an arbitrary thread.

## Routing
Entity-owned -> entity owner. Location-owned -> region owner. Global-safe -> global region. I/O -> plugin/I/O lane. Unknown unsafe -> reject.

| Request (bridged plugin, Bukkit scheduler) | Route | Caller |
| --- | --- | --- |
| Sync callback wrapped in `EntityTask` | Entity owner (Folia entity scheduler); follows the entity; entity removed first -> cancelled, counted rejected, not a violation | `EntityTask` (patch 0011), IMPLEMENTED, unit-tested; boot fixture not yet run in CI |
| Sync callback wrapped in `RegionTask`, or the fingerprinted SSB2 spawn callback | Region owning that chunk | patch 0010 |
| Other sync callback scheduled while a **player's** event is dispatched or a player's command executes, or from a bridged body that carries that player (its async continuation, or a sync task already anchored to the player) | That player's owner (entity scheduler), following the player; player already gone when scheduled -> the caller-region/global route below; player leaves afterwards -> cancelled, counted rejected, not a violation | `SchedulingContext` (patch 0026, 2026-10-22), IMPLEMENTED, unit-tested; headless fixture `LEGACY_CONTEXT_*` |
| Other sync callback | Caller region (`sync-route = caller-region`) or global region | default |
| Explicit global request, async task | Global region / I/O lane; target metadata never consulted. An async body carries the scheduling context it was scheduled from; an explicit global body carries none | 0007, 0003 |

Precedence: explicit entity (`EntityTask`) > explicit region (`RegionTask`, adapter) > entity anchor
(the player of the scheduling context) > caller region > global.

### Entity anchor from the scheduling context (2026-10-22)

The two live failures that motivated it (Sourby Demo, v26.2-r48.2, Epic Skyblock, ~30 bridged
plugins): DeluxeMenus `Menu.openMenu` runs `runTaskAsynchronously` (build the items) and from that
body `runTask` (`player.openInventory`); EssentialsXSpawn's new-player teleport is
`PlayerJoinEvent -> Essentials runTaskAsynchronously -> callEvent(AsyncUserDataLoadEvent) ->
scheduleSyncDelayedTask(NewPlayerTeleport)`. Both inner sync tasks are scheduled from a bridge I/O
thread, which ticks no region, so the caller-region route put them on the global region, where
`openInventory` failed in `InventoryChangeTrigger` ("Cannot trigger criterion async") and the
teleport's `CraftWorld.getChunkAt` was refused (a violation; 3 quarantine Essentials).

The bridge now reads **why** a task was scheduled. `bridge/SchedulingContext` is a thread-confined
frame holding the player a server frame acts for; `bridge/EventAnchors` decides which events name one.
Paper feature patch 0026 publishes it from two sites, each one call into `AuroraBridge`:
`PaperEventManager#callEvent` around the listener loop (`enterEventContext`/`exitContext`) and
`BukkitCommandNode.BukkitBrigCommand#run` around `Command#execute` (`withCommandContext`, player
senders only). `BridgeRuntime.submit` takes the frame's player as the task's entity anchor when the
plugin named no `EntityTask`/`RegionTask` target, and routes it exactly like an `EntityTask`
(`executor.entity`, retired callback). Every bridged body runs under its own anchor: an entity-anchored
sync body publishes its player (a `runTaskLater` chain follows the player), an async body publishes
the frame it was scheduled from (the two cases above), a region or global body publishes nothing (and
never inherits a frame that happens to be open on the tick thread it runs on). Both hooks return at
once while no plugin has been admitted (one volatile read per event dispatch).

Rules, each pinned by `BridgeContextRoutingTest`, `EventAnchorsTest`, `AuroraBridgeContextHooksTest`:

- **Players only.** `PlayerEvent`, `PlayerDeathEvent`, an `EntityEvent` whose entity is a player,
  `InventoryEvent` (the viewer), `BlockBreakEvent`/`BlockPlaceEvent`/`SignChangeEvent`. A mob dies
  without notice, and a task anchored to it would be retired unrun; a dropped task is a worse surprise
  than a global run, so non-player entities never anchor (fixture `LEGACY_CONTEXT_MOB_EVENT_OK`).
- **Leaving or unplaced players anchor nothing**: `PlayerQuitEvent`, `PlayerKickEvent`,
  `PlayerLoginEvent`, `PlayerSpawnLocationEvent`. A cleanup task scheduled from a quit handler must
  run; the entity scheduler of a gone or not-yet-placed player would retire or hold it.
- **An event about no player keeps the enclosing frame** (`AsyncUserDataLoadEvent` fired inside the
  carried join frame), so plugin-to-plugin events do not erase the player.
- **Player already gone at scheduling time** (Folia's entity scheduler reports it synchronously): the
  inference is dropped and the task takes the route it would have taken without it (caller region or
  global). Nothing has run, so this is a routing decision, not a retry after a violation; it is not
  counted as rejected. **Player leaves after scheduling**: cancelled, rejected, last failure names it,
  never a violation, no per-task log line, same as `EntityTask`. A repeating task anchored this way
  therefore stops when its player leaves (Folia semantics; on Paper it would keep running until it
  cancelled itself).
- **Explicit targets and explicit global requests are untouched**; async tasks only carry the frame.
- **No retry.** A body that already ran and violated ownership is never re-dispatched to another owner.

What this does not cover is listed under *Not bridgeable* below.

**Evidence (2026-10-22, agent test server, not qualification).** Jar built from this branch, `mode = "safe"`,
real EssentialsX + EssentialsXSpawn 2.22.1-dev+24, DeluxeMenus 1.14.1, PlaceholderAPI 2.12.3; a headless
player (`scripts/baseline_client.py`) joined as a new player (EssentialsXSpawn newbie teleport) and ran
`/testmenu` (DeluxeMenus `open_command`). Run `fable-routing-real3` (two new players, one of them sending
the command 2 s after login): 7 menu opens, 7 DeluxeMenus sync bodies on the player's region, 0 violations;
Essentials 4 region bodies (the join chain), 0 violations, 0 rejected; both quit-time `cleanInventory`
tasks of DeluxeMenus (scheduled from `InventoryCloseEvent` as the player left) were retired as rejected.
Run `fable-routing-real2` (jar without the diagnostic below): 1 of 2 opens, the first after a new player's
join, took the "player already gone when scheduled" fallback and ran on the global region (violation,
`Cannot init menu async`, with the sweep hint); the second open and every later open (3 more after a
rejoin) ran on the region. Not reproduced in 7 further opens; `FoliaExecutor.entity` now logs one WARN
when an entity scheduler refuses a task for an entity that is still valid, and the fallback is recorded as
the plugin's last failure. The console form `dm open <menu> <player>` was not exercised with a player
online. Fixture run `fable-routing-fixture1`: 22 `LEGACY_*_OK`, 0 `_WRONG`, 36 redirects / 6 handoffs /
1 rejected (retired EntityTask) / 1 fatal (deliberate), not quarantined. Logs under the session scratchpad
(`logs/fable-real2.log`, `fable-real3.log`, `fable-fixture1.log`). No Sourby Demo run: owner-run.

## Telemetry
Per-plugin compatibility state, scheduler redirects, owner handoffs, rejected operations, fatal violations, quarantine, startup duration/cache state and last failure.

- **Task body time** (IMPLEMENTED, LIVE, no setting): per plugin and lane, `/plugins <name>` shows "Sync body time (region / global)" and "Async body time" (count, mean, p50, p99, max; percentiles over the last 256 bodies) and `/perf plugins` shows sync body total / p99. This is the wall time of the task body on the thread that ran it, including bodies that threw; queue wait is not included.

## Implementation status (26.2 branch)

Code: `dev.iyanz.sourbycraft.bridge` plus bridge feature patches under
`sourbycraft-server/paper-patches/features` (0003, 0006, LightingLuminol port 0007
and worker/query follow-up 0008, native-disable fix 0009, explicit callback owners 0010,
entity callback owners 0011, one-line OFF-mode refusal 0024, scheduling-context hooks 0026).
Feature patches are the source of truth for edits
to Paper classes. Since Build 50 (2026-10-22) every `CraftScheduler` site those patches add is one
call into `AuroraBridge` (`cancelTask`, `cancelTasks`, `currentlyRunning`, `knows`,
`pendingBukkitTasks`, `submitIfBridged`, `withBridgedWorkers`, `idInUse`, `runBukkitTask`,
`targetRegion`, `targetEntity`) or `FoliaSchedulerCompatibility` (`isGlobalCallback`,
`bridgeCancelled`); the only scheduler code left in the patch is 0003's `bridgeTask` adapter, whose
methods read package-private `CraftTask` state (`getPeriod`, `cancel0`, `CraftFuture`) or make one
such call. Behaviour is unchanged and pinned by `CraftSchedulerHooksTest`. The 0007/0008 materialized sources are present; build/testing of
this increment remains assigned to Claude.

- **Mode.** `aurora.bridge.mode` in `aurora.toml` (or the unified file): `off` (default) keeps the
  base's refusal; `safe` admits. Under `off`, Paper patch 0024 skips such a jar in `FileProviderSource`
  with one WARN (`Not loading <name> ... aurora.bridge.mode = off ...`, no stack trace) and records it
  for `/plugins` (FAILED, with the reason) and `/perf plugins`; the provider factories' refusal stays as backstop. It is read straight from the TOML files on first use, because
  plugin providers are built during Paper bootstrap, before `SourbyCraftBootstrap`; it is
  RESTART_REQUIRED. A malformed value means `off`. `off` is the default rather than SAFE because
  correctness outranks compatibility in the safety order: admitting unqualified code is the
  operator's decision.
- **Admission.** `PaperPluginProviderFactory`, `SpigotPluginProviderFactory` and
  `CraftMagicNumbers#checkSupported` call `AuroraBridge.admitLegacy(name)` before throwing.
- **Routing.** `CraftScheduler#handle` hands a bridged plugin's task to the bridge instead of
  throwing. Sync callbacks naming a target through `RegionTask` use the region owning that chunk.
  A fingerprinted SuperiorSkyblock2 2026.3 spawn initialization callback also supplies a target;
  see [source scope and pending tests](../development/SUPERIORSKYBLOCK-BRIDGE.md).
  Otherwise sync tasks scheduled from a region use that caller region by default; tasks from
  startup, console, global or async contexts use the global region. Async tasks are
  timed by Folia's async scheduler and run on the Resource Governor's bounded `BRIDGE_IO` lane.
  A region-owner task whose world is unloading (its unload ticket is set when the unload starts) is
  refused, checked before and after indexing, as a rejected operation (no violation, no log line); the
  unload's release of tasks already anchored there also runs when the unload fails
  (`WorldUnloadCompletion`, PR #18 review 2026-10-22).
  `cancelTask`/`cancelTasks` cancel bridged tasks; disable cancels them too, because Folia no
  longer does for the Bukkit scheduler. Sync callbacks wrapped in `EntityTask` take the
  entity-owner route: `entity.getScheduler().run/runDelayed/runAtFixedRate` with a retired
  callback that cancels the bridged task, frees both task indexes and counts one rejected
  operation (last failure names the retirement; no violation, no quarantine count, no per-task
  log line). The bridge never infers an entity from the callback object itself; since 2026-10-22
  it infers one from the **scheduling frame** (the player whose event or command is being handled,
  carried through bridged async continuations; see *Entity anchor from the scheduling context*
  above). Caller-region fallback does not establish ownership of other locations accessed by a task.
- **Violations.** A task body's `UnsupportedOperationException`/`IllegalStateException` from the
  base's region checks counts as a fatal violation. Other exceptions are recorded as the last
  failure, as the plain server would log them. After `aurora.bridge.quarantine-after` (LIVE,
  default 3) violations, the plugin's bridged tasks are cancelled and new ones are rejected. The
  plugin is not disabled: disabling would run plugin code on whichever thread noticed the
  violation.
- **Guard hits under `guard-severity: LOG` (2026-10-14, audit ENG-1; unit-tested, no boot
  fixture yet).** With the engine default `LOG`, a cross-region access the tick guard notices
  (`TickGuard`, 13 call sites: block placement, entity tracking, menus, potion effects, game
  mode, ...) is logged and proceeds; it never throws, so the rule above never saw it. While a
  bridged body runs, `BridgeRuntime` publishes its plugin to the running thread, and the guard
  reports the hit to `GuardViolations`: it counts as one fatal violation of that plugin per body
  run (however many hits the run makes) through the same quarantine path, and its log line is
  rate-limited to one per (plugin, reason) per 60 s with a suppressed count. The access itself is
  still allowed (LOG decides that). A hit outside any bridged body (native plugins, the server)
  keeps the engine's own log line, unchanged. Attribution is by thread: a hit inside a bridged
  body is charged to that body's plugin even when something it called made the access. An
  off-tick-thread refusal from `TickGuard` (an async body opening an inventory) is now classified
  as a violation too.
- **Full `BRIDGE_IO` lane (2026-10-14, ENG-6).** A one-shot async task the lane refuses is
  cancelled, as before. A repeating async task only skips that run: it stays scheduled, the run
  is counted (`skippedRuns` in the plugin's telemetry), and the log line is rate-limited. Before,
  one plugin filling the lane cancelled other plugins' timers for good.
- **Fair lane, not wired (2026-10-20).** `execution/FairLane` is a per-owner lane: one total
  queue plus a per-owner sub-queue limit, and queued work started in weighted round-robin over
  owners (an owner's turn starts up to `weight` of its tasks). `BRIDGE_IO` still runs on the FIFO
  `GovernedLane`, so one plugin's backlog still delays the others' tasks; switching it, the
  setting names (per-plugin queue limit, weights) and their defaults are open, and nothing is
  measured (`FairLaneTest` 8, unit only).
- **Quarantine outlives a reload (ENG-12).** Re-admitting a quarantined plugin by name (a reload
  after disable) still admits it and still rejects every task it schedules; the bridge now logs
  that once per admission instead of saying nothing.
- **Telemetry.** Per plugin: scheduler redirects, owner handoffs (region- and entity-owner task routing),
  rejected operations, fatal violations, quarantine, startup cache state and last
  failure. `/plugins <name>` shows them.
- **Not intercepted.** A bridged plugin calling world API directly from a bridged task on the
  global region is not rewritten; the base's own thread checks decide what happens. Async tasks
  run on the governed lane; the worker follow-up below exposes those threads to Bukkit callers.
- **`World#getChunkAt` from an async task (paper 0028, 2026-10-22).** Not a bridge rule but an engine
  one, for every thread that is not a `TickThread` (bridge I/O lane, Bukkit async task, plugin
  executor): the call follows Paper. A loaded chunk is returned at once; an unloaded one is loaded
  through the chunk system for its region while the caller waits, at most 30 s, then a plain
  `IllegalStateException` that is not counted as a violation (`guards/OffThreadChunkLoad`). Before,
  the base refused even a loaded chunk ("Async chunk retrieval") and the refusal counted toward the
  plugin's quarantine. Tick threads keep the refusal. The `Chunk` returned holds no chunk reference;
  block/entity access through it keeps its own ownership checks. Found with SuperiorSkyblock2 2026.3
  (`/is create` island preparation); boot marker `LEGACY_BRIDGE_ASYNC_GETCHUNK_OK`.
- **Sync route.** `aurora.bridge.sync-route` (LIVE), default `caller-region`: a legacy sync task
  scheduled while a region is ticking runs on that region, anchored on a chunk it owned
  (`execution/region/CurrentRegion`, implemented by the existing `FoliaRegionBackend` adapter; anchors cached per region id). From anywhere
  else it runs on the global region. `global` selects the fallback for unanchored tasks;
  an explicit target still takes precedence. Explicit global scheduler requests remain global.
  Tasks routed to
  a region count as owner handoffs.
- **Scheduler queries.** `isQueued` and `getPendingTasks` use the bridge task index.
  Follow-up patch 0008 makes `isCurrentlyRunning` consult invocation counts even after
  cancellation removes that index entry. `getActiveWorkers` merges actual bridge async
  worker snapshots once with Bukkit's own workers; sync and queued callbacks are excluded.
- **Operator guide:** [testing legacy plugins](../guides/testing-legacy-plugins.md).
- **CI evidence (run 440, `7c2bb0db`, 2026-09-27).** The Paper patches applied. A second CI boot
  with `aurora.bridge.mode = "safe"` loaded `legacy-test-plugin` (no `folia-supported`):
  - admission line logged; all 13 boot stages online;
  - `runTask` ran on `Folia Region Scheduler Thread #3` (the global region);
  - `runTaskAsynchronously` ran on `SourbyCraft-BridgeIO-1`;
  - a self-cancelling `runTaskTimer` ran 3 times and never after cancel;
  - a world access from a sync task was refused by the base (`IllegalStateException`) and
    counted as 1 fatal violation, below the quarantine threshold;
  - `/plugins LegacyBridgeTest` reported 4 scheduler redirects; `/perf governor` reported Bridge
    I/O 1 submitted, 1 completed, 0 rejected; clean exit.
- **CI evidence (run 445, `d919ea66`, 2026-09-27), caller-region route.** `runTask` called from a
  chunk-load callback ran on `Folia Region Scheduler Thread #0` and read the block there
  (`LEGACY_BRIDGE_REGION_SYNC_OK ... block=AIR`). The same kind of access from a task scheduled in
  `onEnable` ran on the global region and was refused (`WORLD_ACCESS_REFUSED`, 1 fatal
  violation). `isQueued` saw a bridged task (`QUEUED_OK`). `/plugins`: 6 scheduler redirects,
  1 owner handoff, not quarantined; 13 boot stages online.
- **CI run 450 (`52568fb0`).** `getPendingTasks` listed a bridged task (`PENDING_OK`). `/perf
  plugins` showed `bridged 1` and `LegacyBridgeTest redirects / handoffs / rejected /
  violations: 6 / 1 / 0 / 1`.
- **Panel staging (2026-09-29, `9a15f51`).** With `mode = "safe"`, real Vault
  `1.7.3-b131` and EssentialsX `2.22.1-dev+25-cfb6f12` enabled through the bridge.
  EconomyShopGUI-Premium `6.4.1` loaded and hooked into Vault/EssentialsX Economy. `/plugins`
  reported 6/6 active, 2 bridged, 0 failed; at the post-boot sample, Essentials had 5 scheduler
  redirects and Vault 2, with 0 rejected operations or fatal region violations for both.
  This is startup and economy-hook evidence on the demo panel, not gameplay or soak qualification.
- **Real legacy plugins, agent test server (2026-10-22, base `6176d517`, no player).** 19 local
  jars, `mode = "safe"`: [bridge-plugin-compatibility-2026-10-22.md](../development/bridge-plugin-compatibility-2026-10-22.md).
  Bridged and enabled with 0 rejected / 0 violations / not quarantined after their console commands:
  Vault 1.7.3-b131, EssentialsX 2.22.1-dev+25, LuckPerms 5.5.10, DeluxeHubReloaded 3.7.2, ntdLuckyBlock
  2.8.34, ChatGames 1.2.1, ReMinions 2.0.7. Every enable failure was plugin-side and would also happen on
  Paper 26.2: Skript 2.12.0 (Adventure 5), StrikePractice (CraftBukkit package version parsing),
  SimplePets (NBT-API), LibsDisguises/GadgetsMenu (their own version gates), and missing dependencies.
  ntdLuckyBlock `/lb place` from the console was refused by the engine. That is a direct world access in a
  console command body, not a scheduled task, so the bridge cannot route it. No adapter was added. Player
  flows are untested (owner-run).

## Qualification
A plugin cannot be presented as BRIDGED until load, enable and bridge initialization succeed and no fatal compatibility violation is present.

## What the bridge is not

A design limit, stated in [compat-vs-performance.md](compat-vs-performance.md) §3: the bridge
**admits, routes, counts and quarantines**. It cannot make a plugin region-aware. Routing picks a
thread for a task; it cannot supply the knowledge of which region owns the state the task touches
when the plugin's author never wrote it down. `RegionTask` and `EntityTask` are how an author
writes it down; an unwrapped callback still gets only the caller-region/global fallback.

The two fixes produced on 2026-10-06 for SuperiorSkyblock2's `SpawnIsland` violation show the two
possible endpoints:

- **Adapter inside the bridge:** a hash-pinned owner for one class of one plugin version
  (`bridge/SuperiorSpawnTaskOwner`, patch 0010;
  [SUPERIORSKYBLOCK-BRIDGE.md](../development/SUPERIORSKYBLOCK-BRIDGE.md)). It covers that one
  callback in the 2026.3 jar and nothing else.
- **Native port of the plugin:** SuperiorSkyblock2 rescheduled onto Folia's global, region, entity
  and async schedulers (local branch `folia` in `~/Sourby/ssb/SSB2`, outside this repository;
  `FOLIA.md` there). Its `plugin.yml` declares `folia-supported: true`, so after the port the plugin
  is NATIVE and **no longer passes through the bridge at all**; the adapter would never fire for
  the ported jar.

Full compatibility therefore means a native port; the bridge is the safety and diagnosis layer for
plugins that have not had one. BRIDGED is not a compatibility verdict (see *Qualification*).

### Not bridgeable (2026-10-22)

Patterns the owner precedence above cannot place, with what the bridge offers for each. None of them
weakens a guard; the engine's refusal stands and counts toward quarantine as before.

| Pattern | Example (live, Sourby Demo 2026-10-22) | Why routing cannot fix it | What the bridge does |
| --- | --- | --- | --- |
| **Whole-world sweep from one task**: a global-routed timer iterates `world.getEntities()` / every chunk and touches each | ClearLag 3.2.2 `ClearTask -> EntityManager.removeEntities -> ClearModule.getRemovables -> CraftItem.getItemStack` ("Accessing entity state off owning region's thread" on the global tick; quarantined after 3) | The body is one Runnable over entities owned by many regions. A task can be handed to one owner; a loop inside a plugin cannot be split per region by anyone but its author. A hash-pinned adapter would have to re-implement the sweep, which is a port of the plugin, not an adapter | The violation/quarantine line says so: an untargeted global task's violation carries "The task named no owner and ran on the global region ...; a body that sweeps entities or blocks across the world cannot be routed to one owner and needs a region-aware (folia-supported) plugin version." Decision: documented not-bridgeable; replace with a Folia-supported plugin (or a native port). |
| **Console command targeting a player** | `dm open <menu> <player>` from the console | The frame is the console's (global tick); the player is a command argument the bridge cannot read | Caller-region/global as before. Run the command as the player, or the plugin names the player (`EntityTask`). |
| **Per-player work scheduled from `onEnable` or a global timer** (iterate online players, open/teleport each) | typical "scoreboard refresh" timers | No single owner; same class as the sweep | As before: global; each cross-region access is a violation. |
| **A task that acts on a different player than the frame's** | trade GUI opened for both parties from one click | The anchor is the frame's player; the other party is usually in the same region, but not necessarily | Same as the caller-region route today (one hop, no retry). |

Considered and not done (2026-10-22): making `openInventory`/`teleport` hand themselves to the entity
scheduler when called from a bridged global body. `teleport` already has the owned sync/async route
(Paper 0017); `openInventory` returns an `InventoryView`, so a hand-off would have to return `null`
to callers that use it, and today's failure (the criterion check inside `initMenu`, after the menu
was assigned and the open packet sent) leaves half-applied state. A fail-closed ownership check at
the top of `CraftHumanEntity#openInventory` (refuse before any side effect, same violation count)
is the recommended follow-up; it is a guard decision for `inherited-guards.md`, not routing.

Bridge telemetry so far has been counts with no time dimension: it records how many tasks a plugin
had redirected, handed off, rejected or failed, never how long they ran, so it cannot attribute tick
time to a plugin. Per-plugin execution timing of bridged task bodies is being added on 2026-10-07
by another agent (in progress, untested); its description belongs in *Telemetry* above once it
lands.

## Multi-plugin task lifecycle (2026-10-06)

Each admitted plugin has its own task index. `pending(plugin)` reads that index;
`cancelTasks(plugin)` scans only that plugin's tasks. The global task-ID index remains for
Bukkit's task lookup and all-plugin pending-task listing. Per-plugin monitors cover admission,
index mutation and running-slot bookkeeping; scheduling, cancellation handles and task bodies
run outside them.
Completion, explicit cancellation and scheduler rejection remove both indexes. Running-state
queries count overlapping async invocations until the last active body finishes, including
bodies still draining after cancellation.

Plugin disable closes task admission before draining the plugin's index. A submission already
waiting for a scheduler handle is cancelled when that handle arrives, and subsequent submissions
are rejected. Ordinary `cancelTasks` keeps admission open. Fatal ownership quarantine remains
per plugin; one plugin's cancellation/quarantine does not drain another plugin's index.

If scheduling throws, the task is cancelled, removed and diagnosed instead of left pending.
When the existing bounded `BRIDGE_IO` lane rejects a timed async invocation, the task (including
its repeating timer, if any) is cancelled and the plugin receives a warning, a last-failure
message and a rejected-operation count. Saturation does not count as a fatal region violation.
Work is never run on a region thread as an overload fallback. This is fail-closed overload
handling, not a guarantee of fair capacity distribution between plugins.

SAFE remains opt-in and RESTART_REQUIRED; existing route and quarantine settings retain their
LIVE behavior. No extra executors or worker budgets are introduced. These changes improve task
bookkeeping and failure isolation; they do not infer an entity/world owner from an anonymous
Bukkit task or make arbitrary NMS/world access region-safe. Real-plugin gameplay and soak
qualification remains required. AWF integration: [plugin API guide](../guides/developing-awf.md).

Functional verification (2026-10-06, Temurin 25.0.4.1): BridgeRuntimeTest 24/24 passes,
including 100 simulated plugin task indexes, scheduler rejection, I/O saturation, disable racing
admission, duplicate IDs and overlapping async invocation state. The isolated full API/server
suites also pass; command/log/report provenance and skipped counts are in SPEC §155. This
is bookkeeping/failure-isolation evidence, not qualification of 100 real legacy plugins.

## LightingLuminol / Luminol port (source materialized; Claude testing pending)

Feature patch 0007 and `FoliaSchedulerCompatibility` adapt Bacteriawa's upstream
global scheduler compatibility and Bukkit task cleanup on disable. Explicit global
requests use the existing per-plugin bridge lifecycle while retaining global
ownership regardless of `sync-route`. The global backend has separate native entry
points to avoid recursion. Cancellation/rejection notifies the returned
`ScheduledTask` adapter; global cancellation drains only global-backed entries of
the target plugin, and the plugin manager closes admission before disable callbacks.

The upstream redirects of named region/entity requests are omitted so their native
ownership and entity retirement contract remain in place. SAFE stays opt-in and
RESTART_REQUIRED; no executor or configuration surface is added. Direct world API
access remains subject to the runtime's thread guards.

Credit: **Luminol**, **LightingLuminol**, **Bacteriawa**, and contributors. The derived
adapter and patch retain upstream GPL v3 licensing. Exact source revision, imported
files, adaptations, packaged notices and Claude's pending acceptance cases are in
[the port handoff](../development/LIGHTINGLUMINOL-BRIDGE-PORT.md). Patch 0007 is present
in the materialized Paper source at commit `425610c83`; no new build/test result is
claimed here. The earlier functional evidence above does not cover this increment.

## Per-plugin limits and async worker follow-up (Claude testing pending)

Two optional LIVE settings in `[aurora.bridge]` default to `0` (disabled):

| Setting | Admission point | On reaching the limit |
| --- | --- | --- |
| `max-pending-tasks-per-plugin` | Submission, under that plugin's index monitor | Cancel/reject the new task before scheduling or inserting either index |
| `max-running-async-tasks-per-plugin` | Async callback start, under that plugin's admission monitor | Cancel the rejected task and its timer; already-running bodies drain |

Values are integers in `0..100000`. Invalid values fall back to `0` and are diagnosed
as invalid config. Limits are independent per plugin. Pending means indexed tasks,
including running tasks that have not been cancelled/completed; one repeating timer
occupies one entry. Async running counts callback invocations, so overlaps of the
same task each consume a slot. Cancellation/disable removes the pending entries but
does not release running slots until each body actually finishes.

Capacity rejections update rejected operations, last failure and the warning log;
they do not increment fatal region violations or quarantine the plugin. Ordinary
completion/cancellation frees capacity for subsequent submissions. Lowering a LIVE
limit does not cancel existing pending work or interrupt running bodies; it changes
admission of new submissions/async callbacks. Sync work does not consume async slots.

These settings bound indexed tasks and running async callbacks, respectively. They
do not reserve the shared I/O queue or guarantee fair CPU time between plugins.
The Resource Governor's existing queue and worker budget still apply. There is no
extra executor, waiting admission queue, task coalescing or silent inline fallback.

`/plugins <name>` exposes pending/running async counts and their active limits.
Worker snapshots retain the actual plugin, Bukkit task ID and governed-lane thread
until the body drains, including after cancellation/quarantine/disable. They are
best-effort snapshots rather than an atomic view across every query. Task-ID
allocation excludes indexed and still-running bridge IDs when its counter wraps.

Source and patch 0008 are written/materialized. Unit/boundary tests ran on 2026-10-07 (see
*Review 2026-10-07* below); no boot, real-plugin or performance result is claimed. Acceptance
cases are in the linked port handoff.

## Review 2026-10-07 (uncommitted bridge work: 0007/0008/0010, limits, body timing)

Paper patch 0010's materialized change is now committed in `paper-server` (`a31e11842`, author and
date from the patch header); `patch_surface.py --check-rebuild` no longer predicts deleting
Paper 0010.

Defects found and fixed (unit-tested only; no boot or real-plugin run):

- **Disable closed admission permanently** (SPEC B86). `disable` set a per-plugin flag that
  nothing cleared, so a bridged plugin loaded again under the same name had every task rejected.
  `admit` now reopens it; the loader calls `admit` only for a (re)loaded instance.
  `BridgeReviewTest.aPluginLoadedAgainAfterDisableCanScheduleAgain`. The same instance enabled
  again after `disablePlugin`, without a reload, also stayed closed; fixed 2026-10-07 by Paper
  feature patch 0012 (`PaperPluginInstanceManager#enablePlugin` calls
  `AuroraBridge.onPluginEnabled` before `onEnable`, which runs `BridgeRuntime.reopen`). `reopen`
  only clears the disable flag of a plugin the bridge already admitted: a native or never-admitted
  plugin is not admitted by it, and quarantine is not lifted.
  `BridgeReviewTest.theSameInstanceEnabledAgainAfterDisableCanScheduleAgain`,
  `reopenNeverAdmitsAPluginTheBridgeDidNotAdmit`, `reopenDoesNotLiftQuarantine`,
  `theEnableHookIsANoOpWithoutABridgeOrPlugin` (unit tests; no boot or real-plugin re-enable run).
- **SuperiorSkyblock2 resolver NPE on a missing plugin meta** (SPEC B87). Now refused with the
  adapter's own `IllegalArgumentException` before admission, never counted as a violation.
  `BridgeReviewTest.superiorCallbackWithoutMetaIsRefusedBeforeAdmissionWithoutViolation`.

Checked and judged correct, with regression/boundary tests added in `BridgeReviewTest`:
pending limit admits exactly N and rejects N+1 before the executor, per plugin, freed by
completion, not fatal; `0` is unlimited; running-async limit rejects only the overlapping
invocation (cancelling the repeater, as documented) and releases its slot in `finally`; a cancelled
async body stays visible to `running`/`activeWorkers`/`runningAsync` until it finishes;
`RegionTask.at` floors negative coordinates and snapshots them. Also reviewed without new tests:
`FoliaSchedulerCompatibility` state machine against `bridgeCancelled` and `cancel()` (including
cancellation before `setBukkitTask`), quarantine classification (capacity and resolver failures
never reach `onFailure`), and body-time recording under concurrent completion. (2026-10-22: the state machine now has
tests, `FoliaSchedulerCompatibilityTest`, 23, including run/cancel races; see the LightingLuminol port doc.)

Correct but fragile:

- An entry leaves the indexes only when its body runs, it is cancelled through the bridge, or
  the plugin is disabled/quarantined. Work the backend drops without running it (Folia
  `AsyncScheduler#cancelTasks` called directly by the plugin) stays indexed and counts toward
  `max-pending-tasks-per-plugin` until disable. A region task for a world that unloads no longer
  does (2026-10-22): `WorldUnloadDrain` calls `AuroraBridge.worldUnloaded`, which releases the
  `REGION_OWNER` entries anchored in that world (rejected, never a violation; `BridgeWorldUnloadTest`,
  fixture `legacybridge unload` -> `LEGACY_UNLOAD_RELEASED_OK`).
- `GlobalRegionScheduler#cancelTasks` also drains ordinary Bukkit sync tasks that fell back to the
  global region, because the native cancellation already cancels them.
- Body-time count/total and the percentile ring are each consistent, not one atomic snapshot.
- `ViolationClassifier` matches on exception type plus message/stack shape; an unrelated
  `IllegalStateException` whose message contains "region threading" would count.

Entity-owner routing (added after this review, unit-tested in `BridgeTargetRoutingTest` `v39*`,
SPEC V39): entity bodies are timed in the existing region lane, so `/plugins <name>` rows are
unchanged. Fragile: a repeating `EntityTask` whose own body removes its entity is dropped by
Folia's entity scheduler without a retired call, so it stays indexed until disable (same class
as the dropped-work case above). Fixture markers `LEGACY_ENTITY_TASK_OK` and
`LEGACY_ENTITY_RETIRED_OK` are required by CI; the plugin cannot read bridge counters, so the
retired marker means "body did not run within 3 s, task cancelled and not queued". Not yet run
in CI.

The fixture now logs `LEGACY_REGION_TASK_OK|_WRONG` for `RegionTask.at` scheduled from
`onEnable`; CI requires `_OK`. Its first CI run has not happened yet. A pending-limit fixture
case was skipped: the limit is read only from `sourbycraft_config` TOML, not the plugin's config.
