# Server commands

Operator commands SourbyCraft adds or replaces. `SourbyCraftCommands` claims the bare names after Paper's
built-ins are registered; `/minecraft:<name>` and `/paper:<name>` still reach the built-ins. Checked against the
code of the Build 50 candidate (PR #62) on 2026-10-25: code written and statically reviewed; not built; not
tested by the owner. Items marked *(Build 50 candidate)* are not in the published `v26.2-r49` or `v26.2-r50`
jars.

The performance views read the SourbyCraft metrics runtime. Whether `/tps`, `/mspt`, `/perf`, the HUD bars and
Spark agree is still open owner evidence: Build 49 runbook check
[R05](../releases/runbooks/build-49-real-test.md#r05-hud) (HUD bars need a real client) and the TO-TEL cases
in [Tester Owner](../tester%20owner.md).

## Commands and permission nodes

No node below is registered with a default, so Bukkit treats each one as operator-only until a permissions
plugin or `permissions.yml` grants it. `/ping` for yourself has no node. Permissions are checked on every
execution (LIVE).

| Command | Permission | Purpose |
|---|---|---|
| `/tps` · `/lag` | `sourbycraft.command.tps` | TPS overview |
| `/mspt` | `sourbycraft.command.mspt` | tick-duration / MSPT overview |
| `/perf [view]` | `sourbycraft.command.perf` | primary performance overview; views [below](#perf-views) |
| `/ram` | `sourbycraft.command.ram` | the `/perf memory` view under its own name (takes no arguments) |
| `/tpsbar [on\|off\|bossbar\|actionbar]` | `sourbycraft.command.tpsbar` | TPS/MSPT HUD bar |
| `/rambar [on\|off\|bossbar\|actionbar]` | `sourbycraft.command.rambar` | RAM HUD bar |
| `/perfbar [on\|off\|bossbar\|actionbar]` | `sourbycraft.command.perfbar` | combined TPS/MSPT/RAM/CPU HUD |
| `/sys` | `sourbycraft.command.sys` | server/JVM/host diagnostics |
| `/spec` | `sourbycraft.command.spec` | full machine specification: processor, clock, cores, memory and heap allocation |
| `/ping [player]` | none for yourself; `sourbycraft.command.ping.other` for another player | latency + offline GeoIP where enabled |
| `/ver` · `/version` · `/about` | `sourbycraft.command.ver` | build/runtime identity, including the **Thread checks** row |
| `/plugins` · `/pl` `[page\|plugin\|search <name> [page]\|filter <state> [page]]` | `sourbycraft.command.plugins` | plugin roster coloured by Aurora compatibility state (native / bridged / failed / disabled) |
| `/plugins <plugin>` | `sourbycraft.command.plugins` | one plugin's state and, when bridged, its Aurora Bridge telemetry (redirects, handoffs, rejections, fatal violations, quarantine, relaxed ownership hits, pending/running limits, body times) |
| `/maxp [n]` | `sourbycraft.command.maxp` | view or set the persisted max-player count |
| `/speedtest` | `sourbycraft.command.speedtest` | runs the pinned, SHA-256-verified Ookla Speedtest CLI (downloaded on first use; needs internet) |
| `/awf …` | `sourbycraft.command.awf`, plus the nodes [below](#awf-subcommands-and-permissions) | Aurora World Fabric worlds, MultiProfile and Redis fabric diagnostics |
| `/update [check\|apply\|status\|resume]` | `sourbycraft.command.update` | updater check with the configured mode, forced apply, status, lift a rollback hold |
| `/sourbycraft` · `/aurora` `[status\|config\|reload\|version\|help]` | `sourbycraft.command.admin` | operator hub: status, configuration files and setting lifecycles, reload, identity |

Other nodes: a player with `sourbycraft.hud.auto` gets the TPS and RAM bars on join unless they turned a bar off
(the opt-out is stored with the player). Engine configuration broadcasts go to holders of
`aurora.broadcasting.receiver`. The Canvas-era `canvas.broadcasting.receiver` grants nothing in the Build 50
candidate and logs one WARN per player who still has it set; the published `v26.2-r50` jar still honours it with
a deprecation WARN.

Behaviour notes for the Build 50 candidate:

- `/update`: a check and a forced apply share the scheduled checks' guard ("An update check is already running").
  `updater.apply-mode: stage` downloads and verifies only; a restart does **not** install the staged jar,
  `/update apply` does. A failed apply preflight or swap never restarts the server and writes `auto_update/apply-failed.tag`;
  later scheduled checks only announce that tag until a successful `/update apply` or the file is deleted, and
  `/update status` shows the marker. Check and apply start nothing while the server is stopping or after a failed
  startup. Details: [AUTO-UPDATER.md](../development/AUTO-UPDATER.md).
- `/sourbycraft reload` runs off the tick thread: "Reloading configuration..." at once, the report later; refused
  while the server is stopping or failed to start.
- `/maxp` re-reads the file before writing, keeps operator edits and replies asynchronously on tick threads.
- `/speedtest` re-checks the installed binary against its recorded SHA-256 before every run and downloads it again
  when the record is missing or does not match (existing installs re-download once). `/awf` and `/speedtest` start
  no new work once shutdown has begun or startup failed.
- `/ver`: a DEV or EXP jar built while `release=pre` shows `Build N — Aurora Nexus (DEV|EXP, <branch>@<sha>)`
  instead of `Build N@<sha>PR!`.

## `/perf` views

| View | Shows |
|---|---|
| `/perf` | overview: tick, CPU, memory, region and health rows |
| `/perf tick`, `cpu`, `memory`, `gc` | tick timing; CPU (*Build 50 candidate*: row "Available processors / live Java threads"); heap, RSS and container memory; GC |
| `/perf lanes` | where the machine's time went, by execution lane |
| `/perf async` | async pathfinding (off unless `aurora.entity.async-pathfinding: true`): admitted/outstanding solves, solve time, queue wait and depth; *Build 50 candidate*: declined and refused solves (both then ran synchronously) and results applied, discarded and retired before delivery |
| `/perf network` | wire bytes and packets per second in each direction, totals, connections (`aurora.network.counters`) |
| `/perf governor` | Resource Governor lanes: budget, queue, submitted/completed/failed/rejected, and the thread ledger |
| `/perf scheduler`, `/perf incidents` | scheduler and task-wait rows; recent incidents |
| `/perf storage` | chunk-system storage reads/writes/deletes since start, each world's pending storage I/O tasks (chunk/POI/entity), region-file save/load latency |
| `/perf awf` | Aurora World Fabric storages: resident/dirty chunks, pending commits, commit p50/p95/p99, reads vs region-file fall-throughs, failures; *Build 50 candidate*: oldest dirty age per storage, AWF loaded worlds, refused writes, AWF Redis leases |
| `/perf plugins` | plugin counts by compatibility state, bridge mode, thread-checks mode with relaxed hits, and each bridged plugin's redirects/handoffs/rejections/violations |
| `/perf history` | the last hour, one line per minute: worst region TPS and MSPT, process CPU, heap (60-sample ring) |
| `/perf player <name>` | a player's world, block, chunk, ping, view/simulation distance, read on the thread that owns the player |
| `/perf chunks`, `/perf entities` | loaded chunks and regions, entities and players per world, summed from each region's own counters |
| `/perf region` | region count and MSPT spread, plus the slowest active region (world/region/generation ids) |
| `/perf region <world> <x> <z>` | the region that owns the chunk at that block position |
| `/perf health` | tick health from the metrics snapshot (or its freshness state when the snapshot is not available) |

*Build 50 candidate:* `/perf storage` (save/load latency) and `/perf entities` (entity-loop counters) drop a world
that is unloaded and idle for 15 minutes; a world that comes back starts new totals. Sizes of 1 GiB and more print
with two decimals in `/perf memory`, `/sys`, `/spec` and the HUD.

`/perf profile` is planned, not implemented.

## `/awf` subcommands and permissions

`sourbycraft.command.awf` gates the whole command. World subcommands also need the AWF world service; `multiprofile`
and `redis` work without it.

| Subcommands | Extra permission |
|---|---|
| `list`, `info <world>`, `create …`, `load <world>`, `save <world>`, `unload <world>`, `autoload <world> on\|off`, `template list`, `template save <world> <template>`, `compact <world\|all>` | none |
| `delete <world> confirm`, `import …`, `export …`, `convert …`, `clone …`, `set …`, `template delete <template> confirm`, `unload <world> nosave` | `sourbycraft.command.awf.admin` *(Build 50 candidate)* |
| `multiprofile [status]`, `list <player>`, `inspect <profile>`, `owner <profile>` | `sourbycraft.command.awf.multiprofile` |
| `multiprofile create`, `switch`, `bind`, `unbind`, `recover`, `lock`, `unlock`, `slots`, `reclaim`, `migrate-repository` | `sourbycraft.command.awf.multiprofile` and `sourbycraft.command.awf.multiprofile.admin` |
| `redis [status]`, `health`, `pools`, `events`, `sessions <profile>`, `ownership <uuid>` | `sourbycraft.command.awf.redis` |

The two admin nodes behave differently. `sourbycraft.command.awf.admin` is implied by `sourbycraft.command.awf`
unless a value is set on the sender for it (by a permissions plugin, or as a child of a `permissions.yml` node the
sender holds; a `default:` alone does not take it away): setting it to `false` gives a role that can list, inspect,
load and save worlds but not delete them or read and write host files. It does not apply to `multiprofile` or
`redis`. `sourbycraft.command.awf.multiprofile.admin` is an ordinary node, operator-only by default and implied by
nothing.

MultiProfile subcommands added in the Build 50 candidate (all but `owner` need the admin node):

- `lock <profile>` / `unlock <profile>`: an inactive, non-deleted profile only; a switch to a locked profile is
  refused; refused while a switch runs or a journal is pending.
- `slots <player> <n>`: the account's slot limit, 1 to `aurora.awf.multiprofile.max-slots`; profiles above a lowered
  limit are kept.
- `owner <profile>`: the ownership provider's holder (node, epoch, expiry), marked `(session fabric)` with
  `coordination.provider: redis` or `(this process)`.
- `reclaim <profile> <epoch>`: for a holder that is gone (a crashed server whose claim has not expired). Takes the
  claim over only at exactly the epoch `owner` shows and releases it at once, so writes under the old claim are
  refused. Refused (`INVALID_REQUEST`) when no other process holds the profile, `STALE_STATE` when the epoch moved.
- `migrate-repository confirm`: copies the file repository (`aurora.awf.multiprofile.directory`) into the Redis
  repository once, verifies every record and leaves the directory unchanged. Refused when the running repository is
  not `repository: redis`, when Redis already holds profiles, or with players online. While Redis is empty and the
  directory holds profiles, profile writes are refused until this has run.

Distributed MultiProfile (`mode: distributed`, only with `repository: redis`) is EXPERIMENTAL and unqualified: live
player state does not follow a player between servers, and the proxy/portal handoff and quit snapshots at shutdown
remain open. Operations: [OPERATIONS.md](../awf/multiprofile/OPERATIONS.md),
[DISTRIBUTED-OWNERSHIP.md](../awf/multiprofile/DISTRIBUTED-OWNERSHIP.md).

`/awf redis` *(Build 50 candidate)*: the diagnostics run on the administrative I/O executor and answer
`Busy: …; try again shortly` when it refuses them. `ownership <uuid>` reads the coordination fabric, which no runtime
code writes for profiles; its answer after the UUID starts with
`coordination claim (API-only; profiles: /awf multiprofile owner): `.
Profile claims live in the session fabric: `/awf redis sessions <profile>` or `/awf multiprofile owner <profile>`.

`/awf unload <world>` saves durably first *(Build 50 candidate)*: if that save fails the world stays loaded and the
reply names the failure; "Unloaded <world>, but AWF could not confirm its last commit" means the world is gone but its
final commit is unconfirmed (see the log).

## Vanilla commands on the region-threaded engine

`/tp`, `/clone` and `/summon` schedule their work on the owning region and return before it is done, so a chained
`execute … run`, `execute store result` or a comparator sees the state before the move, copy or spawn. Details and
practical advice: [region-threaded-commands.md](region-threaded-commands.md). Vanilla `/item`, `/reload`, `/tick`,
`/debug`, `/test` and the vanilla `/perf` are not registered (engine baseline `Commands.java.patch`); `/schedule`,
`/function` and `/return` are, through feature patches 0065 and 0066.
