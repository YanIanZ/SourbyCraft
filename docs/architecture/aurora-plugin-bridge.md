# Aurora Compatibility Bridge

Aurora Bridge adapts supported classes of legacy Paper/Spigot plugin behavior to SourbyCraft's region-threaded runtime.

> **Status 2026-10-25 (Build 50 branch, PR #62).** Changes marked *PR #62* on this page are code written and
> statically reviewed; not built; not tested by the owner. They are in no published build: tag `v26.2-r50`
> (prerelease) predates them, and how they ship is owner decision PM-011. No new test was written for them (owner
> rule for that PR). `aurora.bridge.thread-checks` keeps its `relaxed` default (owner decision 2026-10-22,
> unqualified). Per-item status: [Build 50 PM matrix](../releases/BUILD-50-PM-MATRIX.md).

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
Default mode is OFF. Operators can enable SAFE on a test server. The bridge routes a bridged plugin's **scheduler tasks** only; it does not intercept the plugin's direct world access, which the engine's own checks decide. With `aurora.bridge.thread-checks = enforced` a cross-region access is refused, or, at a `TickGuard` site under `region-scheduler.guard-severity: LOG`, allowed, logged and (inside a bridged body) counted toward quarantine. With `relaxed` (owner decision 2026-10-22, the default since then; see *Thread checks* below) it runs on the wrong thread and is only counted. `BridgeRouter.Kind.UNKNOWN_WORLD_MUTATION` routes to REJECT but is reserved: nothing produces it at runtime.

## Thread checks (owner decision 2026-10-22)

Owner decision 2026-10-22, recorded verbatim after the risks (data races, chunk corruption persisted to disk, item
dupes, deadlocks) were stated: **"Tiru penuh LightingLuminol"** -- make the bridge behave like LightingLuminol:
legacy (non-folia-supported) plugins work because thread-ownership checks are relaxed, as LightingLuminol does. It
overrides `DEVELOPMENT.md` 2.3 ("never weaken region ownership") for this feature only; the truthfulness policy
still applies, so this section states plainly that a relaxed server is **unsafe and unqualified**.

`aurora.bridge.thread-checks` (`sourbycraft_config/aurora.yml`, RESTART_REQUIRED, read once when the checks first
run; `-Daurora.bridge.thread-checks=` overrides the file):

| value | what a failed region-ownership check does | scope |
| --- | --- | --- |
| `relaxed` (**default**, the owner's choice) | counts and logs, never throws | every caller, as LightingLuminol (paper patch 0002 and minecraft patch 0001 "Base Dirty Patches" at revision 72d51c4d comment the throws out unconditionally) |
| `bridged` | throws, except while a bridged plugin's task body is running on the calling thread | the cheap narrower scope: a legacy plugin's scheduler tasks (DeluxeMenus menu opens, ClearLag sweeps, SSB2 chunk preparation on the bridge I/O lane, Essentials spawn teleports from tasks) are relaxed; its event handlers and commands, and every native plugin and the server itself, stay enforced |
| `enforced` | throws, as the region-threading base does | SourbyCraft's behaviour before 2026-10-22 |

Whatever is configured only takes effect while `aurora.bridge.mode = safe`: with the bridge off nothing is admitted
that the relaxation is for, so the checks stay enforced and the boot log says so (INFO). When relaxed, the boot
log carries one WARN (`Aurora Bridge: region-ownership checks are RELAXED ... This server is NOT qualified`), `/ver`
shows `Thread checks: relaxed (region-ownership safety off; unqualified)`, `/sourbycraft config` shows the configured
and effective value, and `/perf plugins` shows the relaxed-hit counters (total, and outside bridged bodies).

What relaxing changes, each site owned by SourbyCraft and asking `guards/ThreadChecks.refuse(reason)` instead of
being commented out as upstream did:

- `TickThread.ensureTickThread` (all overloads, Paper feature patch "relaxed thread checks"), `RegionizedServer.ensureGlobalTickThread` and the six `RegionizedWorldData` "is not under this region's control" checks (MC feature patch "relaxed region-ownership checks"), `TickGuard`'s off-tick-thread refusal and startup/hard-throw helpers, and (*PR #62*) `guards/WorldSettingsOwner`'s refusal of a foreign thread changing world time, weather or game rules before regions tick. The access then proceeds on the wrong thread.
- Bridge accounting: a failed check inside a bridged body is counted as a **relaxed ownership hit** (per plugin, `/plugins <name>`, `/perf plugins`); it is **not** a fatal violation and never quarantines (`BridgeRuntime.relaxedViolation`). Since *PR #62* a relaxed hit becomes the plugin's last failure only while none is recorded, so it no longer hides the last exception the plugin threw. `quarantine-after` still applies to exceptions a body throws.
- Log lines (*PR #62*, `guards/LogWindows`): one line per key per 60 s, keyed by the reason without TickThread's per-hit `, context=...` suffix, so a sweep over many positions logs once rather than once per position. Inside bridged bodies the key is (plugin, reason), at most 1024 windows; outside, the reason, logged on the hitting thread, at most 256 windows. Past the cap, new keys share one overflow window. A line that opens a window ends with `(N more suppressed in the last 60 s)` when the previous window suppressed any. `-Daurora.bridge.thread-checks.trace=true` adds a stack trace to the lines logged outside bridged bodies.
- Supporting pieces LightingLuminol needs so a relaxed server does not fail at once, all gated on the `static final` `ThreadChecks.RELAXED_SCOPE` (folded away on an enforced server): the cross-region world-data pool (`compat/luminol/RegionizedWorldDataPool`, LightingLuminol's `ReferenceCountedRegionizedWorldDataPool`; `WorldDataAccess` leases at ~50 Minecraft sites route an off-thread entity/chunk/block-event/neighbour/scheduled-tick write to the region that owns the chunk, holding a ticket while it runs), Moonrise's entity-section reentrancy guards skipped only for a caller that does not own the entity (the owning thread is still refused a reentrant add, remove or section-status change, as upstream; MC 0084, *PR #62*) and entity slices locked during add/remove, `NearbyPlayers`, `ReferenceList` and `IteratorSafeOrderedReferenceSet` locked, the `RegionizedWorldData` block-entity ticker hand-off, block events, broadcast set and explosion cache locked at their cross-thread touch points (LightingLuminol swapped the containers for synchronized or concurrent ones; the upstream containers stay, so the policy that pins upstream defaults is satisfied and the capture maps keep the insertion order Paper's replay needs -- they are **not** locked: a foreign thread writing into another region's capture map while that region captures is one of the accepted races), the global tick allowed to load a chunk synchronously while pumping global chunk tasks (a non-owning *region* thread is still refused, as in LightingLuminol: its wait could need its own tick, Folia#280).
- `Entity#teleport` on the `ASYNC` route waits for the move (LightingLuminol's `teleportAsync(location, cause).join()`), bounded (30 s on a tick thread, 60 s elsewhere): threads that tick nothing always wait; a tick thread waits only when it owns the entity and the destination is loaded in a region it does not own -- an unloaded destination is scheduled and returns `false` as before, because its region may be merged into the blocked one. The global tick thread owns no entity, so it schedules and returns `false` instead of waiting; LightingLuminol's global tick waits (`TeleportRoute.joinsWhenRelaxed`, pinned by `TeleportRouteTest`: "a tick thread that does not own the entity never blocks").

Independent of the setting (safe techniques, all modes): `Bukkit.getCurrentTick()`/`MinecraftServer.getTickCount()` return the global tick counter off every region instead of throwing; `Entity#teleportAsync` fires `PlayerTeleportEvent` for players (cancel aborts, `getTo()` is honoured, never twice for one call) and the portal route fires `PlayerPortalEvent` once the exit is known (cancel places the player back where it stood) -- `entity/teleport/AsyncTeleportEvents`, escape hatch `-Daurora.compat.async-teleport-events=false`.

Not ported, deliberately: running every Bukkit sync task on the global tick (the bridge routes by owner instead), redirecting a legacy plugin's Folia region/entity scheduler calls to Bukkit, admitting every plugin (`mode = off` stays the default), `AsyncCatcher.catchOp` (LightingLuminol leaves it too), the `CraftScoreboard` changes.

**Honesty.** Nothing here makes a legacy plugin region-safe; it removes the refusal. The owner accepted: data races on chunk sections, entity lists and player containers (item duplication); writes that reach disk corrupted; a write into a region's data that is merged into another region before it ticks is lost; deadlocks where a blocked thread's wait needs its own tick. A relaxed server is **not qualified** and must not be called stable. Evidence so far is unit tests and isolated agent test-server boots on a Build 48 tree ([2026-10-23 verification](../development/LIGHTINGLUMINOL-VERIFICATION-2026-10-23.md)), before the *PR #62* changes; the owner runs real-player testing.

## Routing
Entity-owned -> entity owner. Location-owned -> region owner. Global-safe -> global region. I/O -> bridge I/O lane. Only scheduler tasks are routed: a bridged plugin's direct world access is not intercepted (see *Safety*).

| Request (bridged plugin, Bukkit scheduler) | Route | Caller |
| --- | --- | --- |
| Sync callback wrapped in `EntityTask` | Entity owner (Folia entity scheduler); follows the entity; entity removed first -> cancelled, counted rejected, not a violation | `EntityTask` (patch 0011), IMPLEMENTED, unit-tested; boot markers `LEGACY_ENTITY_TASK_OK` / `LEGACY_ENTITY_RETIRED_OK` pass in CI since run 37515867230 (2026-10-07) |
| Sync callback wrapped in `RegionTask`, or the fingerprinted SSB2 spawn callback | Region owning that chunk | patch 0010 |
| Other sync callback scheduled while a **player's** event is dispatched or a player's command executes, or from a bridged body that carries that player (its one-shot async continuation, or a sync task already anchored to the player) | That player's owner (entity scheduler), following the player; player already gone when scheduled -> the caller-region/global route below; player leaves afterwards -> cancelled, counted rejected, not a violation | `SchedulingContext` (patch 0026, 2026-10-22), IMPLEMENTED, unit-tested; headless fixture `LEGACY_CONTEXT_*` |
| Other sync callback | Caller region (`sync-route = caller-region`) or global region | default |
| Explicit global request, async task | Global region / I/O lane; target metadata never consulted. A one-shot async body carries the scheduling context it was scheduled from; a repeating async body (*PR #62*) and an explicit global body carry none | 0007, 0003 |

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
sync body publishes its entity (a `runTaskLater` chain follows the player; an explicit `EntityTask` body
publishes the entity it names, player or not), a one-shot async body publishes the frame it was
scheduled from (the two cases above), a repeating async body publishes nothing (*PR #62*: a timer
started from a player's command is not that player's work, and holding the player would route every
later run's sync tasks to them and keep them reachable after they leave), and a region or global body
publishes nothing (and never inherits a frame that happens to be open on the tick thread it runs on). Both hooks return at
once while no plugin has been admitted (one volatile read per event dispatch).

Rules, each pinned by `BridgeContextRoutingTest`, `EventAnchorsTest`, `AuroraBridgeContextHooksTest`, except the
*PR #62* changes, which have no test yet:

- **Players only.** `PlayerEvent`, `PlayerDeathEvent`, an `EntityEvent` whose entity is a player,
  `InventoryEvent` (the viewer), `BlockBreakEvent`/`BlockPlaceEvent`/`SignChangeEvent`. A mob dies
  without notice, and a task anchored to it would be retired unrun; a dropped task is a worse surprise
  than a global run, so non-player entities never anchor (fixture `LEGACY_CONTEXT_MOB_EVENT_OK`).
- **Leaving or unplaced players anchor nothing**: `PlayerQuitEvent`, `PlayerKickEvent`,
  `PlayerLoginEvent`, `PlayerSpawnLocationEvent`. A cleanup task scheduled from a quit handler must
  run; the entity scheduler of a gone or not-yet-placed player would retire or hold it. Since *PR #62*
  these events also hide the enclosing frame's player (`EventAnchors.CLEAR`): a `PlayerKickEvent` fired
  inside another player's command (`/kick X`) no longer ties the handler's cleanup to the kicker, whose
  leaving would cancel it.
- **An event about no player keeps the enclosing frame** (`AsyncUserDataLoadEvent` fired inside the
  carried join frame), so plugin-to-plugin events do not erase the player.
- **Player already gone at scheduling time** (Folia's entity scheduler reports it synchronously): the
  inference is dropped and the task takes the route it would have taken without it (caller region or
  global). Nothing has run, so this is a routing decision, not a retry after a violation; it is not
  counted as rejected. Only that synchronous report, on the scheduling thread inside the scheduling call,
  counts as "already gone" (*PR #62*); a retirement on another thread that races the call cancels the task
  like a later leave and never re-routes it. **Player leaves after scheduling**: cancelled, rejected, last failure names it,
  never a violation, no per-task log line, same as `EntityTask`. A repeating task anchored this way
  therefore stops when its player leaves (Folia semantics; on Paper it would keep running until it
  cancelled itself).
- **Explicit targets and explicit global requests are untouched**; one-shot async tasks only carry the frame,
  repeating async tasks carry none (*PR #62*). An explicit `EntityTask` body publishes its entity even when it
  is not a player, so its continuations follow that entity (CI marker `LEGACY_CONTEXT_CHAIN_OK` pins this with
  an armor stand); whether explicit anchors should follow the players-only rule is owner decision PM-234.
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
Per-plugin compatibility state, scheduler redirects, owner handoffs, rejected operations, fatal violations, quarantine, startup duration/cache state and last failure. Relaxed ownership hits are shown by `/plugins <name>` while the checks are relaxed and by `/perf plugins`. Skipped runs of repeating async tasks are counted (`skippedRuns` in `BridgeTelemetry`) but neither `/plugins` nor `/perf plugins` displays them yet; each skip becomes the plugin's last failure and is logged through a WARN rate-limited per (plugin, reason).

- **Task body time** (IMPLEMENTED, LIVE, no setting): per plugin and lane, `/plugins <name>` shows "Sync body time (region / global)" and "Async body time" (count, mean, p50, p99, max; percentiles over the last 256 bodies) and `/perf plugins` shows sync body total / p99. This is the wall time of the task body on the thread that ran it, including bodies that threw; queue wait is not included.

## Implementation status (26.2 branch)

Code: `dev.iyanz.sourbycraft.bridge` plus bridge feature patches under
`sourbycraft-server/paper-patches/features` (0003, 0006, LightingLuminol port 0007
and worker/query follow-up 0008, native-disable fix 0009, explicit callback owners 0010,
entity callback owners 0011, admission reopened on re-enable 0012, per-plugin onLoad/onEnable
wall time 0013, retired entity scheduler runs the retired callback 0021, one-line OFF-mode refusal
0024, scheduling-context hooks 0026, `World#getChunkAt` off a tick thread 0028, relaxed thread
checks 0029).
Feature patches are the source of truth for edits
to Paper classes. Since Build 50 (2026-10-22) every `CraftScheduler` site those patches add is one
call into `AuroraBridge` (`cancelTask`, `cancelTasks`, `currentlyRunning`, `knows`,
`pendingBukkitTasks`, `submitIfBridged`, `withBridgedWorkers`, `idInUse`, `runBukkitTask`,
`targetRegion`, `targetEntity`) or `FoliaSchedulerCompatibility` (`isGlobalCallback`,
`bridgeCancelled`); the only scheduler code left in the patch is 0003's `bridgeTask` adapter, whose
methods read package-private `CraftTask` state (`getPeriod`, `cancel0`, `CraftFuture`) or make one
such call. Behaviour is unchanged and pinned by `CraftSchedulerHooksTest`. The boot markers of these
patches (`LEGACY_GLOBAL_*`, `LEGACY_REGION_TASK_OK`, `LEGACY_ENTITY_*`, `LEGACY_DISABLE_*`) pass in CI
since run 37515867230 (2026-10-07).

- **Mode.** `aurora.bridge.mode` in `sourbycraft_config/aurora.yml` (a value in
  `sourbycraft_global_config.yml` is read too, and `aurora.yml` wins; a legacy `*.toml` is read only
  where its `*.yml` does not exist): `off` (default) keeps the
  base's refusal; `safe` admits. Under `off`, Paper patch 0024 skips such a jar in `FileProviderSource`
  with one WARN (`Not loading <name> ... aurora.bridge.mode = off ...`, no stack trace) and records it
  for `/plugins` (FAILED, with the reason) and `/perf plugins`; the provider factories' refusal stays as backstop. It is read straight from those files on first use, because
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
- **Violations.** A task body's exception counts as a fatal violation when `ViolationClassifier`
  recognises a region-ownership refusal: SourbyCraft's own refusals (`guards/RegionRefusal`, matched
  by exception type), the base's refusal messages matched whole (`Unsupported in region threading`,
  `Not on any region`, the teleport-async messages, `Use region threading hooks`), TickThread's
  "off owning region" reason, or an `UnsupportedOperationException`/`IllegalStateException` thrown
  from a `TickThread.ensureTickThread` or `TickGuard` frame. Since *PR #62* (PM-233) an unrelated
  exception whose message merely mentions region threading no longer counts. Other exceptions are
  recorded as the last failure, as the plain server would log them. After
  `aurora.bridge.quarantine-after` (LIVE, default 3) violations, the plugin's bridged tasks are
  cancelled and new ones are rejected. The plugin is not disabled: disabling would run plugin code
  on whichever thread noticed the violation. Mode matters: under `thread-checks = relaxed` (the
  default with `mode = safe`) the ownership checks that ask `ThreadChecks` do not throw, so those
  accesses never reach this rule; refusals that are not ownership checks (`RegionRefusal`'s
  unsupported operations, for example) still do.
- **Guard hits under `guard-severity: LOG` (2026-10-14, audit ENG-1; changed in *PR #62*).** With
  the engine default `LOG`, a cross-region access the tick guard notices on a tick thread
  (`TickGuard.guard`, 13 call sites in the engine baseline: block placement, entity tracking, menus,
  potion effects, game mode, ...) is allowed and logged; it never throws, so the rule above never
  sees it. While a bridged body runs, `BridgeRuntime` publishes its plugin to the running thread and
  the guard reports the hit to `GuardViolations`. With `thread-checks = enforced` the hit counts as
  one fatal violation of that plugin per body run (however many hits the run makes) through the
  same quarantine path, logged once per (plugin, reason) per 60 s with a suppressed count; with
  `relaxed` or `bridged` it is a relaxed ownership hit and never quarantines (*Thread checks*). A
  hit on a tick thread outside any bridged body (native plugins, the server) keeps the engine's own
  WARN with its stack trace; since *PR #62* that line is logged once per (reason, innermost plugin
  on the stack, or `server`) per 60 s with a suppressed count (`GuardViolations.admitEngineLog`, at
  most 256 keys, then one shared window; the stack walk that finds the plugin runs on every hit and
  its cost is not measured). Attribution is by thread: a hit inside a bridged body is charged to
  that body's plugin even when something it called made the access. A hit off any tick thread (an
  async body opening an inventory) is decided by `TickGuard`'s off-tick-thread check through
  `ThreadChecks`: refused with an `IllegalStateException` that the classifier counts as a violation
  when enforced (or `bridged` outside a bridged body), allowed and counted once as a relaxed hit
  otherwise (*PR #62* removed a second report that counted it twice). Unit-tested before
  *PR #62*; no CI boot runs an enforced LOG-mode bridge (PM-103).
- **Full `BRIDGE_IO` lane (2026-10-14, ENG-6).** A one-shot async task the lane refuses is
  cancelled, as before; since *PR #62* its WARN is rate-limited per (plugin, reason) and carries
  no stack trace. A repeating async task only skips that run: it stays scheduled, the run is
  counted as a skipped run, and the log line is rate-limited. Before, one plugin filling the lane
  cancelled other plugins' timers for good. Since *PR #62* (PM-100) a repeating async task also
  keeps at most one run waiting in the lane's queue: a period that fires while the previous run has
  not started is skipped the same way (`... its previous run is still queued on the BRIDGE_IO lane;
  the task stays scheduled`) instead of queued behind it, so a slow timer cannot fill the shared
  queue and get other plugins' one-shot tasks refused. Runs that have started may still overlap,
  as on Paper.
- **Fair lane, not wired (2026-10-20).** `execution/FairLane` is a per-owner lane: one total
  queue plus a per-owner sub-queue limit, and queued work started in weighted round-robin over
  owners (an owner's turn starts up to `weight` of its tasks). `BRIDGE_IO` still runs on the FIFO
  `GovernedLane`, so one plugin's backlog still delays the others' tasks; switching it, the
  setting names (per-plugin queue limit, weights) and their defaults are an open owner decision
  (PM-178), and nothing is measured (`FairLaneTest` 8, unit only).
- **Quarantine outlives a reload (ENG-12).** Re-admitting a quarantined plugin by name (a reload
  after disable) still admits it and still rejects every task it schedules; the bridge now logs
  that once per admission instead of saying nothing.
- **Telemetry.** Per plugin: scheduler redirects, owner handoffs (region- and entity-owner task routing),
  rejected operations, fatal violations, quarantine, relaxed ownership hits, startup cache state and
  last failure. `/plugins <name>` shows them (relaxed hits only while the checks are relaxed); skipped
  runs are counted but not shown (see *Telemetry* above).
- **Not intercepted.** A bridged plugin calling world API directly from a bridged task on the
  global region is not rewritten; the base's own thread checks, as `aurora.bridge.thread-checks`
  configures them, decide what happens. Async tasks
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
A plugin is presented as BRIDGED when load, enable and bridge initialization succeeded and the bridge
has not quarantined it (`CompatibilityClassifier`, fed by `AuroraBridge.bridgeInitialized` and
`AuroraBridge.fatalViolation`, which both read the quarantine flag). A quarantined plugin is FAILED.
Fatal violations below `quarantine-after`, and relaxed ownership hits, do not change the state: the
plugin stays green while `/plugins <name>` shows "Rejected / fatal operations" and "Relaxed ownership
hits". BRIDGED therefore does not mean that no violation happened, and it is not a compatibility verdict.

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

Patterns the owner precedence above cannot place, with what the bridge offers for each. The bridge
weakens no guard for them. With `thread-checks = enforced` the engine's refusal stands and counts toward
quarantine (the live examples below were seen that way on Sourby Demo on 2026-10-22, before the checks
became a setting; `guards/ThreadChecks` first shipped in `v26.2-r49`). Under the default `relaxed`, and under `bridged` for these task bodies, the same access
proceeds on the wrong thread and is counted as a relaxed ownership hit: no refusal, no quarantine, and
not qualified.

| Pattern | Example (live, Sourby Demo 2026-10-22) | Why routing cannot fix it | What the bridge does |
| --- | --- | --- | --- |
| **Whole-world sweep from one task**: a global-routed timer iterates `world.getEntities()` / every chunk and touches each | ClearLag 3.2.2 `ClearTask -> EntityManager.removeEntities -> ClearModule.getRemovables -> CraftItem.getItemStack` ("Accessing entity state off owning region's thread" on the global tick; quarantined after 3) | The body is one Runnable over entities owned by many regions. A task can be handed to one owner; a loop inside a plugin cannot be split per region by anyone but its author. A hash-pinned adapter would have to re-implement the sweep, which is a port of the plugin, not an adapter | With enforced checks, the violation/quarantine line says so: an untargeted global task's violation carries "The task named no owner and ran on the global region ...; a body that sweeps entities or blocks across the world cannot be routed to one owner and needs a region-aware (folia-supported) plugin version." Decision: documented not-bridgeable; replace with a Folia-supported plugin (or a native port). |
| **Console command targeting a player** | `dm open <menu> <player>` from the console | The frame is the console's (global tick); the player is a command argument the bridge cannot read | Caller-region/global as before. Run the command as the player, or the plugin names the player (`EntityTask`). |
| **Per-player work scheduled from `onEnable` or a global timer** (iterate online players, open/teleport each) | typical "scoreboard refresh" timers | No single owner; same class as the sweep | As before: global; each cross-region access is a violation with enforced checks, a relaxed ownership hit otherwise. |
| **A task that acts on a different player than the frame's** | trade GUI opened for both parties from one click | The anchor is the frame's player; the other party is usually in the same region, but not necessarily | Same as the caller-region route today (one hop, no retry). |

Considered and not done (2026-10-22): making `openInventory`/`teleport` hand themselves to the entity
scheduler when called from a bridged global body. `teleport` already has the owned sync/async route
(Paper 0017); `openInventory` returns an `InventoryView`, so a hand-off would have to return `null`
to callers that use it, and today's failure (the criterion check inside `initMenu`, after the menu
was assigned and the open packet sent) leaves half-applied state. A fail-closed ownership check at
the top of `CraftHumanEntity#openInventory` (refuse before any side effect, same violation count)
is the recommended follow-up; it is a guard decision for `inherited-guards.md`, not routing.

Per-plugin execution timing of bridged task bodies, planned here on 2026-10-07, has landed: see
*Telemetry* above. Event-listener and command time per plugin is still not measured (PM-458, owner decision).

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
message and a rejected-operation count. (Superseded for repeating tasks since 2026-10-14, ENG-6:
only a one-shot task is cancelled, a repeating one skips that run; see *Full `BRIDGE_IO` lane*.) Saturation does not count as a fatal region violation.
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

## LightingLuminol / Luminol port (global scheduler and disable cleanup)

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
access remains subject to the runtime's thread guards -- until 2026-10-22, when the
owner chose LightingLuminol's relaxed thread checks as the default (*Thread checks* above;
the second LightingLuminol port, from its "Base Dirty Patches").

Credit: **Luminol**, **LightingLuminol**, **Bacteriawa**, and contributors. The derived
adapter and patch retain upstream GPL v3 licensing. Exact source revision, imported
files, adaptations, packaged notices and the acceptance cases are in
[the port handoff](../development/LIGHTINGLUMINOL-BRIDGE-PORT.md). The `LEGACY_GLOBAL_*` boot
markers pass in CI since run 37515867230 (2026-10-07); local acceptance cases 1-3 and 5-7 passed on
2026-10-22 (case 4 at unit level only) and the three fixes found there are merged (`dae9d99`, in every tag since `v26.2-r48`).
Case 8 (real plugins, players, soak, AWF interaction) is the owner's.

## Per-plugin limits and async worker follow-up

Two optional LIVE settings in the `aurora.bridge` section of `sourbycraft_config/aurora.yml` default to `0` (disabled):

| Setting | Admission point | On reaching the limit |
| --- | --- | --- |
| `max-pending-tasks-per-plugin` | Submission, under that plugin's index monitor | Cancel/reject the new task before scheduling or inserting either index |
| `max-running-async-tasks-per-plugin` | Async callback start, under that plugin's admission monitor | One-shot callback: cancelled. Repeating task (*PR #62*): that run is skipped (skipped runs, last failure, rate-limited WARN) and the timer stays scheduled. Already-running bodies drain |

Values are integers in `0..100000`. Invalid values fall back to `0` and are diagnosed
as invalid config. Limits are independent per plugin. Pending means indexed tasks,
including running tasks that have not been cancelled/completed; one repeating timer
occupies one entry. Async running counts callback invocations, so overlaps of the
same task each consume a slot. Cancellation/disable removes the pending entries but
does not release running slots until each body actually finishes.

Capacity rejections update rejected operations, last failure and the warning log;
a skipped repeating run (*PR #62*) updates skipped runs instead of rejected operations;
neither increments fatal region violations or quarantines the plugin. Ordinary
completion/cancellation frees capacity for subsequent submissions. Lowering a LIVE
limit does not cancel existing pending work or interrupt running bodies; it changes
admission of new submissions/async callbacks. Sync work does not consume async slots.

These settings bound indexed tasks and running async callbacks, respectively. They
do not reserve the shared I/O queue or guarantee fair CPU time between plugins.
The Resource Governor's existing queue and worker budget still apply. There is no
extra executor, waiting admission queue or silent inline fallback; the only coalescing is
that a repeating async task keeps at most one run queued (*PR #62*, see *Full `BRIDGE_IO` lane*).

`/plugins <name>` exposes pending/running async counts and their active limits.
Worker snapshots retain the actual plugin, Bukkit task ID and governed-lane thread
until the body drains, including after cancellation/quarantine/disable. They are
best-effort snapshots rather than an atomic view across every query. Task-ID
allocation excludes indexed and still-running bridge IDs when its counter wraps.

Unit/boundary tests ran on 2026-10-07 (see *Review 2026-10-07* below) and local runtime probes
(`LEGACY_LIMIT_*_OK`) passed on 2026-10-22 ([port doc](../development/LIGHTINGLUMINOL-BRIDGE-PORT.md),
case F3). *PR #62* changed the repeating-timer outcome; the console-only probe `legacybridge
async-limit` now expects skip-not-cancel and NEEDS RETEST. No real-plugin or performance result is
claimed.

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
invocation (cancelling the repeater, as documented then; since *PR #62* it skips only the overlapping
run and keeps the repeater, and the `BridgeReviewTest` assertion was edited to match) and releases its slot in `finally`; a cancelled
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
  fixture `legacybridge unload` -> `LEGACY_UNLOAD_RELEASED_OK`). *Status 2026-10-25 (PR #62):* a
  repeating `EntityTask` whose own body removes its entity is now released too: `AuroraBridge.FoliaExecutor`
  checks `CraftEntity.isPurged()` on the owner right after the body and calls the retired callback
  (rejected, never a violation; no test or fixture yet). A plugin calling Folia `AsyncScheduler#cancelTasks`
  directly still leaves its entries until disable: `AuroraBridge.cancelAsync` exists, but its call site needs a
  Paper feature patch made from the materialized sources (PM-099).
- `GlobalRegionScheduler#cancelTasks` also drains ordinary Bukkit sync tasks that fell back to the
  global region, because the native cancellation already cancels them.
- Body-time count/total and the percentile ring are each consistent, not one atomic snapshot.
- `ViolationClassifier` matches on exception type plus message/stack shape; an unrelated
  `IllegalStateException` whose message contains "region threading" would count. *Fixed in PR #62*
  (PM-233): base messages are matched whole and `RegionRefusal` by type (see *Violations*).

Entity-owner routing (added after this review, unit-tested in `BridgeTargetRoutingTest` `v39*`,
SPEC V39): entity bodies are timed in the existing region lane, so `/plugins <name>` rows are
unchanged. Fragile: a repeating `EntityTask` whose own body removes its entity is dropped by
Folia's entity scheduler without a retired call, so it stays indexed until disable (same class
as the dropped-work case above; released since *PR #62*, see above). Fixture markers `LEGACY_ENTITY_TASK_OK` and
`LEGACY_ENTITY_RETIRED_OK` are required by CI; the plugin cannot read bridge counters, so the
retired marker means "body did not run within 3 s, task cancelled and not queued". Not yet run
in CI (they pass since CI run 37515867230, 2026-10-07).

The fixture now logs `LEGACY_REGION_TASK_OK|_WRONG` for `RegionTask.at` scheduled from
`onEnable`; CI requires `_OK`. Its first CI run has not happened yet (it passes since CI run
37515867230). A pending-limit fixture case was skipped: the limit is read only from
`sourbycraft_config` (since Build 49 `aurora.yml`), not the plugin's config.
