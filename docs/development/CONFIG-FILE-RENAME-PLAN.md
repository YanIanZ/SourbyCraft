# Config file rename plan — `canvas-server.yml` / `canvas-worlds.yml`

> **Role:** plan (design + migration contract) and, in this box, the implementation record.
> **Status: IMPLEMENTED in Build 49 (2026-10-22, owner decision reversing the 2026-10-21 keep).**
> The effective contract now lives in [docs/AURORA-CONFIG.md](../AURORA-CONFIG.md) §4 and §7; the
> sections below are the plan as written on 2026-10-20 and are kept as design history. Where the
> implementation differs from the plan, this box wins:
>
> - Names: the proposed `config/aurora-server.yml` / `config/aurora-worlds.yml` (§3). Per-world
>   `canvas-patch.yml` not renamed (§1).
> - Code: `aurora/engine/configuration/EngineConfigFiles` (not `ConfigFileLocation`), wired into
>   `GlobalConfiguration.reload()` / `WorldConfig.reload()` before every load; headers rewritten;
>   `TimeSpanValidation` names the new file; Spark entries are the new file names (the plan's "keep the
>   JSON keys" recommendation, Q2, was not followed: the group is already `aurora/` and the entries
>   are real file names); `/sourbycraft config` prints the new names.
> - **Old file is renamed, not left byte-identical** (owner instruction): after the copy under the new
>   name the old file becomes `<old>.migrated` (`.migrated.1` … when taken). Rule 2 and the R1–R3
>   downgrade story change accordingly: the `*.migrated` copy is the pre-rename snapshot; to run a pre-49
>   jar copy `aurora-*.yml` over the `canvas-*.yml` names first (AURORA-CONFIG.md §7).
> - Both present: new wins, one WARN naming the stale old file on every load (rule 3; no mtime
>   comparison). Corrupt old file: the load fails naming both paths, nothing written or renamed
>   (rule 5 as "no silent fallback"). Empty old file: defaults under the new name, old file still renamed.
>   The copy goes through an atomic `.migrating` sibling (rule 9). Rule 10 (alias refusal), the escape
>   hatch (§5.5) and the mtime warning were not implemented (owner chose fewer knobs).
> - Tests: `EngineConfigFilesTest` (13: T1–T3, T5, T7–T11, T14 staging leftover, numbered `.migrated`,
>   the real server and worlds key sets round-tripping value for value), `ConfigurationUpgradeTest`
>   fixtures renamed, `TimeSpanValidationTest` updated. Boot evidence: Build 49 release doc, migration
>   note.
> - Out of this change: the per-world `canvas-patch.yml` (Q3), Spark path mismatch (Q10), engine
>   pending-key tracking (Q11), making every ordinary load atomic (Q9). Deprecation end (Q4): the
>   old-name migration stays until the owner schedules its removal
>   ([26.2-build-50-aurora-nexus.md](../releases/26.2-build-50-aurora-nexus.md)).
>
> Original header: "PLANNED / PROPOSED — nothing in this document is implemented. Per
> [docs/DEVELOPMENT.md](../DEVELOPMENT.md#development-truthfulness-and-non-misleading-policy), every
> statement below is tagged with what was actually verified." Superseded in part on 2026-10-21 (names
> kept, `/canvas` subcommand tree deleted, `CanvasFileMigration` → `AuroraFileMigration`, Spark group
> `canvas/` → `aurora/`); superseded again on 2026-10-22 by the implementation above. Line numbers below
> are from before both changes.
>
> Source: [docs/TODO.md](../TODO.md) — *"Rencana migrasi nama file konfigurasi
> `config/canvas-server.yml` / `canvas-worlds.yml`"* (`docs/TODO.md:507`).
> Written 2026-10-20 by `oc-deepseek-flash`; read-only investigation, one Gradle test run
> (section 11). Revised 2026-10-20 after an independent read-only Codex review: all 11 findings
> applied (section 13).

---

## 1. Scope

In scope:

- `config/canvas-server.yml` — Aurora region-engine global settings (`GlobalConfiguration`).
- `config/canvas-worlds.yml` — Aurora world-default settings (`WorldConfig`).

Out of scope, called out so it is not forgotten:

- `<world/dimension storage directory>/canvas-patch.yml` — the per-world override file. The runtime
  resolves it beneath `storageSource.getDimensionPath(dimension)` (`WorldConfig.java:127`). The Spark
  reader resolves it beneath `world.getWorldFolder()` (`SourbyServerConfigProvider.java:298`). It is
  not under `config/`. The two resolutions agree for the overworld and can differ for other
  dimensions (section 13, note on F11). A rename would need a per-world migration plus a different
  Spark key. **Recommendation: leave it, and name that decision explicitly in the release notes**
  if the two global files are renamed.
- `sourbycraft_config/*.toml` and `sourbycraft-security.yml` — different surfaces, same names.
- The Java identifiers `GlobalConfiguration`, `WorldConfig`, `Level.canvasConfig()`,
  `reloadCanvasConfig()` (`minecraft-patches/sources/net/minecraft/world/level/Level.java.patch:74-82`).
  A file rename does not require an internal rename; the engine package is already
  `dev.iyanz.aurora.engine`.
- Deleting or no longer reading the legacy file. That is a *later* decision (section 5.6, P4), not
  part of this plan.

---

## 2. What the code does today (verified)

### 2.1 Paths and lifecycle

| Item | Where | Verified behaviour |
|---|---|---|
| Global path constant | `GlobalConfiguration.java:49` | `Path.of("config/canvas-server.yml").toAbsolutePath().normalize()` — relative to the process working directory. |
| Global load | `GlobalConfiguration.java:65-69`, `75-111` | Static initializer calls `reload()`. `reload()` → `ConfigurationProvider.buildSolidConfiguration(CONFIG_PATH, …)`. This runs on first class use, i.e. at boot before a playable server, and again on `/sourbycraft reload`. |
| World defaults path | `WorldConfig.java:53` | `Path.of("config/canvas-worlds.yml").toAbsolutePath().normalize()`. |
| World defaults load | `WorldConfig.java:58-69`, `71-107` | Static initializer forces `GlobalConfiguration` first, then `reload()`. |
| Per-world base reference | `WorldConfig.java:121-153` (`buildForLevel`, `:127`) | Reads the same `BASE_FILE` and merges the world's `canvas-patch.yml` (resolved under the dimension storage directory, see section 1) on top (`buildPatchableConfiguration`). Called from `Level.reloadCanvasConfig()` at level construction and on reload. |
| Reload entry (supported) | `/sourbycraft reload` (`SourbyCraftCommand.java:41-46`) → `SourbyCraftConfig.reloadDetailed()` (`SourbyCraftConfig.java:214-254`) → `applyLiveConfig` → `CanvasConfigBridge.reload()` (`SourbyCraftConfig.java:281-292`, `CanvasConfigBridge.java:6,15-28`) | `GlobalConfiguration.reload()` then `WorldConfig.reload()`. Failures are collected into a list and not thrown. Each reload step keeps its own previous instance only when that step itself fails validation (section 2.3, "Reload-failure guarantee"). There is no rollback across steps. |
| Reload entry (legacy, unreachable) | `ReloadSubCommand.java:37-38` | Calls the same two `reload()` methods, but the `/canvas` command tree is no longer registered (`commands/CanvasCommands.java` build call removed; see the comment at `sourbycraft-server/src/minecraft/java/net/minecraft/commands/Commands.java:304-305`). Not a supported operator path; do not rely on it. |
| Spark config dump | `SourbyServerConfigProvider.java:57` (group `canvas/`), `:272-301` | Reads `config/canvas-server.yml` (`:295`) and `config/canvas-worlds.yml` (`:296`), plus each world's `canvas-patch.yml` under `world.getWorldFolder()` (`:298`) on every Spark report. JSON keys: `canvas-server.yml`, `world-defaults.yml`, `<world>.yml`. |
| Spark (duplicate provider) | ~~`aurora/engine/spark/provider/FoliaServerConfigProvider.java`~~ | **Deleted 2026-10-20** (never instantiated; it also lacked the live provider's secret hiding). The only Spark reader is `SourbyServerConfigProvider`. |
| Operator view | `SourbyCraftCommand.java:87` | `/sourbycraft config` prints `config/canvas-server.yml + config/canvas-worlds.yml`. |
| Validation message | `TimeSpanValidation.java:18,33` | Error text hard-codes `(config/canvas-server.yml)`; reachable only from `GlobalConfiguration` (`Part.java:183` → `logs.cleanerTimeSpan`, `autosave.autosaveFrequency`). |
| Docker | `docker/entrypoint.sh:10` (`cd "$DATA"`), `docker-compose.yml:23-25` (`./data:/data`; now `docker/docker-compose.yml`, `../data:/data`) | The files are `/data/config/*.yml` inside the container. No Docker change is required for a rename; only the relative path inside `config/` changes. |
| CI | `.github/workflows/build.yml:179-180, 365-366, 427-435, 629-636` | CI boot directories create only `sourbycraft_config/*.toml`; the engine YAML is created by the server on first boot. No CI step names the engine files today. |

### 2.2 Upgrade semantics (what a load does to the file)

`ConfigurationProvider.buildSolidConfiguration` (`ConfigurationProvider.java:128-189`):

1. File missing → `floodFill` (`:138-141`, `:339-368`) writes the full default document with the
   header comment.
2. File present → `composeFromFile` (`:151`), diff against the class's current key set
   (`NodeDiff.compute`, `:161`), apply adds/removes (`:162-164`), **write the file back**
   (`:172-176`, the write itself is the truncating `FileWriter` at `ConfigurationProvider.java:397`)
   and reload it (`:182`).
3. `null` node (truly empty file) → flood fill (`:154-158`). A *comment-only* file is not `null`,
   so it goes through the diff path. It crashed before the working-tree `NodeDiff` fix; see 11.4
   (historical).

Consequences that matter for a rename:

- **The file is rewritten on every load, not only when it changed.** So "writing" to a config
  file is not new behaviour; a read-only config directory already fails today. The rewrite is a
  truncate-then-write, so a crash mid-write can leave an empty or partial file (pre-existing; see
  section 10, Q9).
- Operator values survive; keys the class no longer has are **removed** (reported through
  `Resolver.onDiffRemove`); unknown keys are not preserved. Pinned by
  `ConfigurationUpgradeTest` (`sourbycraft-server/src/test/java/dev/iyanz/aurora/engine/configuration/ConfigurationUpgradeTest.java:28-43`, real-class cases at `:307` and `:353`; in the
  working tree at the time of writing, owned by `eng-sonnet`).
- The **header comment is written only on first creation** (`ConfigurationProvider.java:370-391`:
  the `alreadyExisted` guard). Comments of an existing file are preserved; only newly added keys
  get their documentation injected. A migrated file therefore keeps the *old* header text
  ("The historical `config/canvas-server.yml` path is retained …", `GlobalConfiguration.java:101-102`,
  `WorldConfig.java:101-102`) unless the migration writes a new header itself. This is the main
  non-obvious design detail (section 5.3).
- `Part.save` (`Part.java:77-105`) is the other writer and keeps unknown keys; it is used for
  in-place value writes, not for load upgrades.

### 2.3 Lifecycle per setting (RESTART_REQUIRED / NEWLY-CONSTRUCTED ONLY / LIVE)

Both `reload()` methods re-read the file and republish the instance. Whether a value takes effect
on `/sourbycraft reload` depends on how the consumer reads it:

- **LIVE**: the consumer reads the published instance at the moment it uses the value.
- **NEWLY-CONSTRUCTED ONLY**: the value is copied into a field or object at construction; existing
  objects keep the old value.
- **RESTART_REQUIRED**: the value is consumed during static initialisation or startup, so a reload
  has no effect until a new process starts.
- **unverified until traced**: no consumer was traced. Such a setting must not be documented as
  LIVE or as restart-only until it is traced.

Only the consumers named in the cells below were traced (by the author and by the independent
review). The old catch-all "anything else is LIVE" assumption is removed.

**`config/canvas-server.yml` (GlobalConfiguration)**

| Setting | Lifecycle | Evidence |
|---|---|---|
| `region-scheduler.default-tick-rate` | **RESTART_REQUIRED** | Captured in `private static final float DEFAULT_TICK_RATE` at `TickRegionScheduler.java:57` (the value is also used to derive `DEFAULT_TICK_TIME_NANOS` at `:58`). `GlobalConfiguration.java:207` still logs "Using X as default tick rate" on a reload although the running scheduler keeps the boot value — pre-existing, not caused by a rename. |
| `region-scheduler.affinity-scheduler.*` | **RESTART_REQUIRED** | Read when the scheduler thread pool is constructed (`AffinitySchedulerThreadPool.java:118`, `SchedulerUtil.java:124`). |
| `logs.enable-log-cleaner` / `logs.cleaner-time-span` | **RESTART_REQUIRED** | Read only in the startup branch (`GlobalConfiguration.java:121-175`: the `else` of `TickRegions.hasStarted()`; cleaner at `:171`). |
| SIMD / faster-random detection | **RESTART_REQUIRED** | Startup branch, `GlobalConfiguration.java:139-166` (`ENABLE_FASTER_RANDOM = false` at `:148`). |
| `vanilla-fixes.mc223153` | **RESTART_REQUIRED** | Read during static block registration: `Blocks.java:5987`, inside the static `register(...)` path. The plan previously called all of `vanilla-fixes.*` LIVE; that was wrong for this key. |
| `networking.player-info-send-interval` | **RESTART_REQUIRED** | `public static final int SEND_PLAYER_INFO_INTERVAL` at `PlayerList.java:113`, captured at class initialisation. |
| `region-scheduler.guard-severity` | LIVE | `TickGuard.java:34,49` reads `GlobalConfiguration.getInstance().regionScheduler.guardSeverity` per use (review-traced). |
| `autosave.autosave-frequency` | LIVE | `GlobalConfiguration.java:204` (`AUTOSAVE_SPAN.swapValue` in `postLoad`); read per scheduling at `RegionizedServer.java:342,406` (review-traced). |
| `autosave.autosave-players` | LIVE | Read through `getInstance()` at use, `MinecraftServer.java:1730` (author-traced). Other `autosave.*` keys: **unverified until traced**. |
| `chat.disableChatVerificationOrder` | LIVE | Read through `getInstance()` at use, `SignedMessageChain.java:52` (review-traced). |
| `displayWorldLoadScreenForCrossRegionTransfers` | LIVE | Read through `getInstance()` at use, `Entity.java:4921,5349` (review-traced). |
| `region-scheduler.prevent-excessive-velocity-move-out-of-region` | LIVE | Read through `getInstance()` at use, `Entity.java:1275` (author-traced; not in the review's list). |
| `region-scheduler.overloaded-log-millis` | **unverified until traced** | Cited by the author at `ScheduledHandleTickState.java:62` (`sourbycraft-server/src/minecraft/java/dev/iyanz/aurora/engine/threadedregions/`); not re-traced. |
| Other `vanilla-fixes.*` keys | **unverified until traced** | At least 13 source files read `vanillaFixes.*` (for example `LivingEntity.java`, `Mob.java`, `ArmorStand.java:361`, `Raid.java`, `Blocks.java`). None of the others was traced individually. |
| Every other key in `canvas-server.yml` | **unverified until traced** | — |

**`config/canvas-worlds.yml` (WorldConfig)**

| Setting | Lifecycle | Evidence |
|---|---|---|
| `blocks.spawner.minSpawnDelay`, `maxSpawnDelay`, `spawnCount`, `maxNearbyEntities`, `requiredPlayerRange`, `spawnRange` (the six integer options) | **NEWLY-CREATED SPAWNERS ONLY** (per the option doc; trace unverified) | Option doc `WorldConfig.java:501-507`: "only applied on creation of the spawner … Any already generated spawners will not get this configuration applied". This is the six-integer list, not the whole `blocks.spawner.*` group. |
| `blocks.spawner.disableMaxNearbyEntitiesCheck` | LIVE per world | Read at spawn time through `level.canvasConfig()`, `BaseSpawner.java:229` (review-traced). The per-level swap is `WorldConfig.java:110-118`. |
| `blocks.spawner.spawnedEntitiesHaveNoCollision` | LIVE per world | Read at collision time through `level().canvasConfig()`, `LivingEntity.java:4170` (review-traced). |
| Projectile chunk-load override (`canvas$loadsChunksOverride`) | **NEWLY-CONSTRUCTED ONLY** | Captured in a `final` field in the `Projectile` constructor, `Projectile.java:49` (review-traced). Reload affects projectiles constructed afterwards; existing projectiles keep their value. |
| Other per-world options (combat, collision mode, fast orbs, criterion triggers, region bars, sleep messages) | **unverified until traced** | The author cited readers (`Level.java:2067`, `LivingEntity.java:3871,3962`, `ExperienceOrb.java:261-400`, `ServerLevel.java:1286`, `RegionizedTpsBar.java:30,52`) as likely LIVE; none was traced by the review. Trace each option before documenting it as LIVE. |
| Malformed world config | load fails | `Validator.validateObject` in `onFinishLoad` (`WorldConfig.java:88-96`) and `onLoad` (`:160`). Whether the previous base instance survives a base-file validation failure is **unverified until traced**. |

**Reload-failure guarantee (what it actually covers)**

- *Global, single file*: `postLoad` validates before `INSTANCE = configuration`
  (`GlobalConfiguration.java:113-119`). An invalid `canvas-server.yml` therefore leaves the previous
  global instance live. This is the only guarantee the code gives in the strict sense.
- *Per level, worlds*: a failing per-level `reloadCanvasConfig()` keeps that level's previous
  configuration. This is the only per-level guarantee.
- *Not atomic across steps*: `CanvasConfigBridge.reload()` (`CanvasConfigBridge.java:15-28`) catches
  the errors and returns them as a list. It performs no rollback. The global instance is published
  before the world reload starts. The worlds are swapped sequentially (`WorldConfig.java:110-118`):
  if the override of level *k* fails validation, levels 1 to *k−1* already run the new
  configuration, and levels *k+1* onward are not reached and keep the old one. The result after
  `/sourbycraft reload` can therefore mix new global, new worlds and old worlds. The reload reports
  the failure; it does not roll anything back.
- The promise to make in documentation: "an invalid file never replaces a previous valid global
  instance; a reload is not all-or-nothing across global and world configuration." Nothing stronger.

**Implication for a rename:** the *filename* has no LIVE/RESTART semantics of its own — it is read
at class-init and on `/sourbycraft reload`, exactly like today. A rename changes **where the
operator must edit**, not *when* a value takes effect.

---

## 3. Proposed names (**PROPOSED** — owner decision required)

| Current | Proposed | Rationale |
|---|---|---|
| `config/canvas-server.yml` | `config/aurora-server.yml` | Matches the engine's public identity (`dev.iyanz.aurora.engine`, logger `Aurora`, `sourbycraft_config/aurora.yml`). Minimal rename, stays in the flat `config/` directory that Paper and Spark already scan. |
| `config/canvas-worlds.yml` | `config/aurora-worlds.yml` | Same; keeps the `-worlds` suffix that the Spark key already renders as `world-defaults.yml`. |

Alternatives considered:

| Option | Assessment |
|---|---|
| Keep the current names | Status quo. Zero migration risk, but the TODO's independence goal (Stage C, `docs/DEVELOPMENT.md` §8) stays open and operators keep seeing a third-party name for SourbyCraft-owned settings. |
| `config/aurora/server.yml`, `config/aurora/worlds.yml` | Cleaner namespace, but moves files into a new subdirectory: Spark path resolution, docs, operator muscle memory and every example change, for no functional gain. Defer. |
| `config/region-engine.yml` | Describes the subsystem, not the product; would need another rename when the product layer changes. Reject. |

Open naming decisions (section 10): Spark JSON group/key names (`canvas/`, `canvas-server.yml`)
and the per-world `canvas-patch.yml`.

---

## 4. Migration contract (non-negotiable rules)

1. **Read old once, publish new atomically.** On the first load of the rename build, if only
   `config/canvas-server.yml` exists, its *content* is read (never written) and a complete,
   validated copy is published as `config/aurora-server.yml` (rule 9). Same for the worlds file.
2. **Never delete, move, rename or write the old file.** It stays byte-identical after migration.
   It is the downgrade backup (section 8) and the proof that the migration was non-destructive.
3. **New name wins when both exist.** If both files exist, read the new one, warn once with both
   paths (and, when `legacy.lastModified() > current.lastModified()`, say so explicitly — that is
   the signature of "edited after a downgrade"). The legacy file is not opened in this case; only
   its metadata is read for the warning (section 4.1).
4. **Seeding is not migration.** A fresh server (neither file) creates only the new file, as today
   (`floodFill`).
5. **No silent fallback.** If the new file cannot be created while the old one exists, fail the
   load with a message naming both paths and the override (5.5), rather than starting on a path
   the operator cannot see in the docs. Writing is not new (2.2), so this does not fail a
   configuration that works today except in the rare "can write this file, cannot create a new
   one" ACL case — call it out in the release notes.
6. **The new file gets a correct header.** A copied file must not keep the old
   "historical `canvas-server.yml` path is retained" header (2.2).
7. **Idempotent.** Boot N+1 copies nothing, leaves the old file's mtime and bytes alone, and logs
   nothing beyond the usual load line. `/sourbycraft reload` never migrates again.
8. **Values and supported comments survive; formatting does not.** Values, supported per-key
   comments and the order of surviving keys are preserved under the loader's own serializer. The
   loader re-serialises the whole document (`ConfigurationProvider.java:52`: three-space indent,
   block style; `:403`: `YAML.serialize`), so operator-chosen formatting (spacing, quoting, flow
   style, extra blank lines) is not preserved, in the migrated file or on any ordinary load. The
   legacy file is never written, so it stays byte-identical.
9. **Atomic publish.** The migration never writes the final `aurora-*.yml` name directly. It writes
   a sibling temp file (`aurora-server.yml.migrating`, which is not a valid config name), validates
   it by running the ordinary loader's validation on that temp file, then publishes it with an
   atomic `Files.move(…, ATOMIC_MOVE)`. A crash, exception or failed validation before the move
   leaves no `aurora-*.yml` file, so the next boot retries from the untouched legacy file. A stale
   temp file is overwritten by the next attempt. (`ConfigurationProvider.write` opens a truncating
   `FileWriter` at `ConfigurationProvider.java:397`; calling it on the final name would let a
   crash leave an empty new file that wins over the legacy settings.)
10. **No aliases of the new path.** Before any read or write of the new name, the loader refuses
    (failing with a message naming both paths, writing nothing) when the new path is a symbolic
    link, or when it is the same file as the legacy path (`Files.isSameFile`, which also catches
    hard links). The loader never writes through an alias. The ordinary loader's `FileWriter`
    follows symlinks, so this rule applies to every load of the new path, not only to the
    migration. The legacy symlink case (below) is the separate, supported case of a legacy file
    that is a link.

### 4.1 Conflict / edge matrix

| Old file | New file | Action |
|---|---|---|
| absent | absent | Create new with defaults (`floodFill`). No warning. |
| present | absent | Read old (read-only) → write temp → validate temp → atomically publish as new → load new. Log one INFO migration line. Old untouched. |
| present | present | Read new only; never open or parse old. If old's mtime is newer, warn once naming both paths. A metadata failure on old only drops that warning (logged at WARN). Never merge, never delete. |
| absent | present | Normal load. |
| present, empty (`0` bytes) | absent | Current code flood-fills (node is `null`) → the migration should treat it as "no operator values" and create the new file with defaults; old left alone. |
| present, comment-only | absent | Migrates like any other file. This row used to crash (11.4, historical); the `NodeDiff` fix is in the working tree and is covered by `ConfigurationUpgradeTest.aFileOfOnlyCommentsGetsEveryKeyAddedAndKeepsItsComment` (`ConfigurationUpgradeTest.java:204`). Passing status still needs a fresh test run. |
| present, unreadable (permission) | absent | Migration needs the content, so fail with `AccessDenied` naming the legacy path. No new file is created. Old untouched. |
| present, unreadable | present | New wins; old is never opened, so the unreadable file causes no failure. Only the mtime warning is affected (see row above). |
| present, invalid YAML | absent | `composeFromFile` throws; fail with the path named, no new file, old untouched. Today the same file also fails on load. |
| present, invalid YAML | present | Old is never parsed; new wins. |
| any | new name is a symlink, or the same file as old (hard link) | Refuse; fail naming both paths; write nothing (rule 10). |
| present, symlink (old) | absent | `composeFromFile` follows the link (reads the target's content); the new file is a regular file; the link itself is untouched. Add a test. |

---

## 5. Design (PROPOSED)

### 5.1 New helper

New class (name PROPOSED) `dev.iyanz.aurora.engine.configuration.ConfigFileLocation`:

```java
/** Read/write location for one engine config file, including its pre-rename name. */
record ConfigFileLocation(Path directory, String currentName, String legacyName) {
    Path current();                 // directory.resolve(currentName)
    Path legacy();                  // directory.resolve(legacyName)

    /** Pure: current if it exists, else legacy if it exists, else current. */
    static Path choose(Path current, Path legacy);

    /** Publishes a validated copy of legacy as current (rules 8-10). Returns true if it created current. */
    static boolean migrateIfNeeded(Path legacy, Path current, String... header);
}
```

Location: next to `ConfigurationProvider` so it can reuse the package-private `composeFromFile`
and `write`, which keeps the migration in the same document model (comments, node tags) as the
normal load. `choose` is a pure function so tests can cover the matrix with `@TempDir` paths and
never touch the working directory's `config/`.

**Ownership note:** any test for this lands under
`sourbycraft-server/src/test/java/dev/iyanz/aurora/engine/` — the tree currently owned by
`eng-sonnet` (AGENT-COORDINATION, ROUND 4). Either they own these tests or the implementation
waits until that block is closed.

### 5.2 Wiring

`GlobalConfiguration`:

- Replace the constant at `:49` with a resolved path produced by `ConfigFileLocation`
  (`Path.of("config")`, `aurora-server.yml`, `canvas-server.yml`), evaluated before
  `buildSolidConfiguration` at `:77`. Keep the value in a private static field so a later reload
  uses the same resolution.
- `:76` log line → resolved path.
- Header (`:98-110`) → new text for the new file name; explicitly says the old file is read once
  and kept.
- `postLoad` and the startup branch (`:113-208`) are unchanged.

`WorldConfig`:

- `BASE_FILE` (`:53`) becomes a non-final (`volatile`) resolved field, set in `reload()` before
  `buildSolidConfiguration` (`:71-73`) and read by `buildForLevel` (`:127`). Non-final because a
  reload may resolve again (e.g. operator copied the new file in mid-run).
- Header text at `:101-102` and the patch-file header at `:141-143` (“for worlds defined in
  `/config/canvas-worlds.yml`”) → new name.
- `:110-118` (`reloadCanvasConfig` loop) and `:155-186` unchanged.

Ordering is deterministic: `GlobalConfiguration` resolves and migrates first; `WorldConfig`'s
static initializer already forces `GlobalConfiguration` first (`WorldConfig.java:60`).

### 5.3 New header injection and atomic publish (the subtle part)

The migration must write the new document itself, not `Files.copy`, because a copied file keeps
the old block-comment header forever (2.2). It must also never write the final name directly
(rule 9). Sketch:

```java
// rule 10: refuse aliases of the new name before touching anything
// (symlink, or same file as legacy: Files.isSameFile)

Path tmp = current.resolveSibling(current.getFileName() + ".migrating");

Node legacyNode = ConfigurationProvider.composeFromFile(legacy);     // read-only; legacy is never written
if (legacyNode != null) {                                            // null = empty file -> defaults
    legacyNode.setBlockComments(null);                               // drop the old header
    legacyNode.setTag(Tag.MAP);
    ConfigurationProvider.write(tmp, legacyNode, false, header);     // header written (alreadyExisted=false)
}
// ordinary validation and key diff, on the temp file only; this also writes defaults if tmp is absent
ConfigurationProvider.buildSolidConfiguration(tmp, …);
Files.move(tmp, current, StandardCopyOption.ATOMIC_MOVE);            // publish; a crash before this leaves no aurora-*.yml
```

`buildSolidConfiguration(tmp, …)` keeps operator values and per-key comments, and reports removed
and added keys exactly as an upgrade does today. The move is the publish point. Copy is **once**:
on the next boot `current` exists, so `choose` never looks at `legacy` again.

### 5.4 Readers that must follow the same resolution

- `SourbyServerConfigProvider.CanvasSplitParser.getNestedFiles` (`:293-301`) must use the resolved
  paths, otherwise Spark reports nothing after the rename. Recommended: keep the JSON keys stable
  (`canvas-server.yml`, `world-defaults.yml`) in this change so existing Spark dashboards/scripts
  do not break, and rename the keys in a separate, announced change.
- `SourbyCraftCommand.config` (`:87`) prints the resolved names (and, while the legacy file is
  still present, a hint that it is being kept as a downgrade copy).
- `TimeSpanValidation` (`:33`): either take the active path from a supplier, or (transitional,
  recommended) word the message as `config/aurora-server.yml (formerly
  config/canvas-server.yml)`, so an operator who still has the old file is not confused.
- `FoliaServerConfigProvider` was dead code and was deleted on 2026-10-20 with its unused
  import, so only one Spark path list remains (`spark/SourbyServerConfigProvider.java`).

### 5.5 Escape hatch (PROPOSED, optional)

`-Dsourbycraft.engine-config.legacy=true` → always resolve to the legacy names (read and write).
Purpose: an operator with an unusual immutable-config setup, or a support engineer reproducing an
old build's behaviour, can pin the old paths without downgrading. Must be logged loudly at boot
and must not migrate. If the owner prefers fewer knobs, drop this and rely on the hard failure in
rule 5.

### 5.6 Phasing

| Phase | Content | Reversible? |
|---|---|---|
| P0 | `ConfigFileLocation` + tests, no wiring (added but unused). | Delete the class. |
| P1 | Wire `GlobalConfiguration` only; keep `canvas-worlds.yml` as is. | Revert one file. |
| P2 | Wire `WorldConfig`, Spark reader, `/sourbycraft config`, validation text, headers. | Revert; old files were never touched. |
| P3 | Docs, CI boot assertion, release note. | Doc-only. |
| P4 (separate decision) | Stop *reading* the old file (deprecation end): delete `legacyName` handling. Only after a stated number of builds and a release note. | Not reversible for operators who never migrated — keep P4 out until the owner says so. |

---

## 6. Touch points (complete list, verified by search)

### 6.1 Java — main source

| File:line | What | Change |
|---|---|---|
| `sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/GlobalConfiguration.java:49` | path constant | resolve via helper |
| `…/GlobalConfiguration.java:65-69` | static init reload | unchanged, but now migrates |
| `…/GlobalConfiguration.java:76` | log line names the file | resolved path |
| `…/GlobalConfiguration.java:98-110` | header comment | new text |
| `…/GlobalConfiguration.java:101-102` | "historical … retained" sentence | new text |
| `…/GlobalConfiguration.java:113-119` | validate-before-publish | unchanged (this is the global reload guarantee, section 2.3) |
| `…/GlobalConfiguration.java:121-208` | startup-only branch, `AUTOSAVE_SPAN` at `:204` | unchanged |
| `…/WorldConfig.java:53` | `BASE_FILE` | resolved field |
| `…/WorldConfig.java:58-69` | static init | unchanged |
| `…/WorldConfig.java:71-107` | `reload()` | resolve before load |
| `…/WorldConfig.java:101-102` | header | new text |
| `…/WorldConfig.java:110-118` | per-level reload (sequential; partial on failure, section 2.3) | unchanged |
| `…/WorldConfig.java:127` | `<dimension storage dir>/canvas-patch.yml` | **out of scope**, document |
| `…/WorldConfig.java:141-143` | patch header names the worlds file | new name |
| `…/WorldConfig.java:215` | comment names the worlds file | new name |
| `…/configuration/ConfigurationProvider.java:128-189` | `buildSolidConfiguration` | unchanged; add package-private reuse for the helper |
| `…/configuration/ConfigurationProvider.java:370-398` | `write` (header only on create); truncating `FileWriter` at `:397` | unchanged for ordinary loads; migration must not call it on the final name (rule 9) |
| `…/configuration/NodeDiff.java:150-179` (`applyChange` at `:153`) | immutable tuple list on an empty mapping | **fixed in the working tree** (copies into an `ArrayList`); historical note 11.4 |
| `…/configuration/validation/TimeSpanValidation.java:18,33` | hard-coded `config/canvas-server.yml` | resolved/transitional text |
| `…/sourbycraft/config/upstream/CanvasConfigBridge.java:6,15-28` | reload bridge | unchanged (no rollback; section 2.3) |
| `…/sourbycraft/SourbyCraftConfig.java:214-254, 281-292` | reload report + bridge wiring | unchanged; optionally report the resolved file in reload output (`:249` mentions "legacy engine keys") |
| `…/sourbycraft/command/SourbyCraftCommand.java:87` | `/sourbycraft config` file row | resolved names |
| `…/sourbycraft/spark/SourbyServerConfigProvider.java:57, 293-301` | Spark group + paths | resolved paths; keys unchanged (recommended) |
| ~~`…/aurora/engine/spark/provider/FoliaServerConfigProvider.java`~~ | dead duplicate | deleted 2026-10-20 |
| `…/aurora/engine/spark/FoliaSparkPlugin.java:10` | unused import | delete with the class |
| `…/aurora/engine/subcommands/ReloadSubCommand.java:37-38` | dead reload path | optional cleanup; not required |
| `…/minecraft/java/net/minecraft/commands/Commands.java:304-305` | comment explaining `/canvas` removal | unchanged |
| `sourbycraft-server/minecraft-patches/sources/net/minecraft/world/level/Level.java.patch:74-82, 156` | `canvasConfig` accessor/`reloadCanvasConfig` | unchanged |

No Java file outside this list references either filename (verified: repository-wide search,
section 11.1). In particular nothing in `sourbyapi`, `paper-server`, `paper-api` or
`src/main/resources` does.

### 6.2 Java — tests

| File:line | What | Change |
|---|---|---|
| `sourbycraft-server/src/test/java/dev/iyanz/aurora/engine/configuration/ConfigurationUpgradeTest.java:30, 307, 353` | javadoc + `@TempDir` names | new cases for the resolution matrix; existing names may stay as legacy fixtures (**owned by `eng-sonnet`**). The comment-only case is `:204` and is already present. |
| `…/configuration/validation/TimeSpanValidationTest.java:42` | asserts the message contains `config/canvas-server.yml` | update to the new/transitional text (**owned by `eng-sonnet`**) |
| `…/configuration/TimeSpanOptionValidationTest.java:15` | javadoc mention | text only |
| `…/configuration/GlobalConfigurationDocumentationTest.java`, `WorldConfigurationDocumentationTest.java`, `CanvasConfigurationDocumentationTestSuite.java` | option-doc coverage | no path assertion (verified) — unchanged |
| New: `…/configuration/ConfigFileLocationTest.java` (PROPOSED) | resolution matrix, copy-once, header, idempotency, no-delete, atomic publish, aliases | new |

### 6.3 Docs / scripts

| File:line | Change |
|---|---|
| `AGENTS.md:54` | configuration-surface sentence names the new files and says the old names are read once, kept, then ignored |
| `README.md:201-202` | configuration list |
| `docs/architecture/AURORA.md:235-236` | "existing files remain supported" list |
| `docs/architecture/dependency-ledger.md:62` | the bridge row's "an existing deployment's `canvas-server.yml` keeps working" |
| `docs/architecture/independence.md:328` | "Keep Canvas engine config as compatibility layer" → say how long |
| `docs/AURORA-FULL-TRANSITION.md:66` | bridge description |
| `docs/MODERNIZATION-AUDIT.md:13` | "Existing format retained" row (historical doc — annotate, do not rewrite history) |
| `docs/AURORA-TASKS.md:170,174` | compatibility-name checklist |
| `docs/AURORA-CONFIG.md:87,137` | add a "file rename" row/paragraph next to the reload contract (no path is named there today); state that a reload is not all-or-nothing (section 2.3) |
| `docs/architecture/execution-contract.md:24` | engine-config row (generic wording today) |
| `scripts/test_patch_policy.py:130` | comment "Operators can restore THROW in config/canvas-server.yml" |
| `docs/development/TEST-COVERAGE-GAPS.md:37` | item 8 mentions `canvas-server.yml` (**PM-owned — do not edit**) |
| `docs/TODO.md:507` | tick when implemented (**PM-owned**) |
| `docs/README.md` | add the index line for this page (**PM-owned — not edited by this plan**) |

### 6.4 CI / packaging

| Item | Change |
|---|---|
| `.github/workflows/build.yml:179-180, 365-366, 427-435, 629-636` | no existing step names the engine files; add one boot assertion (section 9.3). CI owner only. |
| `upload-artifact` / release job | no change (the YAML files are runtime state, not artifacts). |
| `docker/Dockerfile`, `docker/docker-compose.yml`, `docker/entrypoint.sh` | no change (2.1). |
| `slimServerJar` / `applyAllPatches` / patches | **no change**: the file names live in plain engine source (`src/main/java`), not in a Minecraft/Paper patch. Verified by search across both patch trees. |

---

## 7. Behaviour visible to operators (if implemented)

1. First boot after upgrade: one INFO line, e.g.
   `Migrated config/canvas-server.yml -> config/aurora-server.yml (old file kept, no longer read)`,
   and the same for the worlds file.
2. `config/aurora-server.yml` contains the operator's values, the new header, and the current key
   set. Operator formatting is normalised by the loader (rule 8); values and supported comments
   survive.
3. `config/canvas-server.yml` stays on disk, byte-identical, and is ignored from then on.
4. `/sourbycraft config` lists the new names; `/sourbycraft reload` re-reads the new names and
   keeps the lifecycle behaviour of 2.3 (LIVE, newly-constructed-only and RESTART_REQUIRED settings
   behave as listed there; unverified settings are not promised).
5. Spark keeps its current JSON keys (if the recommended option is chosen), so existing reports
   stay readable.

---

## 8. Rollback / downgrade story

The migration never writes the legacy file, so an installation that **was migrated** can go back
to an older build with the legacy file as its snapshot. That is not true of every installation, and
the rollback guard does not prepare configuration. Three cases:

- **Migrated installation** (a legacy file existed at upgrade time). `config/canvas-*.yml` is the
  pre-rename snapshot. An older build reads it and ignores `aurora-*.yml`. Settings changed after the
  rename live only in `aurora-*.yml` until they are copied back (R2).
- **New-only installation** (fresh install, or no legacy file existed). No `canvas-*.yml` exists. An
  older build reads only its hard-coded legacy paths (`GlobalConfiguration.java:49`,
  `WorldConfig.java:53`), finds nothing, and **seeds defaults**. Its settings are lost to the old
  build unless the operator copies them (R2) before the old build starts.
- **Auto-updater rollback** (`<jar>.prev`, `docs/development/AUTO-UPDATER.md`). `UpdateRollbackGuard`
  copies the previous jar back over the launch jar (`UpdateRollbackGuard.java`, `restore`). It does
  not touch configuration. The running JVM keeps its open inode, so the restored jar runs only after
  **another process start**. The configuration for the old build must therefore be prepared before
  that start, and the guard does not do it.

**Restart sequence for a manual downgrade (PROPOSED wording for the release note):**

1. Stop the newer server (clean `stop`).
2. Back up both pairs, so no edit is lost: `cp -p config/aurora-server.yml config/aurora-server.yml.bak`
   and the same for `aurora-worlds.yml`.
3. For a new-only installation, or to keep the newest values, copy the new files over the old names
   (R2). Skip this step only for a migrated installation whose old snapshot is wanted.
4. Start the older jar. It reads `config/canvas-*.yml` and rewrites them with its own key set; keys it
   does not know are removed from the copied file. The `aurora-*.yml` files and the `.bak` backups
   still hold them.

**R1 — Downgrade a migrated installation to keep the old snapshot.** Steps 1, 2 and 4 above. The
old build runs the pre-rename values. Settings changed after the rename are not seen by the old
build. Nothing is lost on disk.

**R2 — Downgrade and keep the newest values.** Steps 1 to 4 above, with step 3 performed:

```sh
cp -p config/aurora-server.yml config/aurora-server.yml.bak
cp -p config/aurora-worlds.yml config/aurora-worlds.yml.bak
cp config/aurora-server.yml config/canvas-server.yml
cp config/aurora-worlds.yml config/canvas-worlds.yml
```

The old build then runs with the post-rename values. `aurora-*.yml` stays untouched. Caveat: the
old build removes keys it does not know from the copied `canvas-*.yml` (2.2); those keys still
exist in `aurora-*.yml` and in the `.bak` files, so information is not lost, but the files now
differ.

**R3 — Re-upgrade after a downgrade.** Because the new name wins (rule 3), edits made to
`canvas-*.yml` during the downgrade window are ignored. The boot warning names both files and their
mtimes. Recovery must not be a blind copy:

- Do **not** copy `canvas-*.yml` over `aurora-*.yml`, and do not delete `aurora-*.yml` to force a
  fresh copy. The old build removed the keys it does not know from `canvas-*.yml`, so either action
  erases keys that exist only in `aurora-*.yml` and any edits kept only there.
- Back up both pairs first, then reconcile the intended values explicitly, by hand or with a
  reviewed merge, and only then start the new build.
- **No automatic merge, no automatic deletion.**

**R4 — Automation that does not support rollback.** The auto-updater restores the previous jar when
a boot fails (section 8 above; `docs/development/AUTO-UPDATER.md`). The restored jar runs only after
another process start, and it reads the old files, which the migration did not write. A new-only
installation still needs the R2 copy before that start. If a future phase ever stops reading the old
file (P4), the auto-updater rollback story must be revisited **before** P4 ships.

**R5 — Release notes.** State explicitly: "this build creates `config/aurora-*.yml` on first
boot and keeps `config/canvas-*.yml` when they existed; to go back to an earlier build, back up the
`aurora-*.yml` files, then copy them over the `canvas-*.yml` names (R2). A fresh install has no
`canvas-*.yml` to fall back to." Also note the version in which reading the old name will stop (if
P4 is scheduled) so the guarantee has a visible end.

---

## 9. Test cases

Nothing below has been written or run; this is the acceptance list. Names are PROPOSED.

### 9.1 `ConfigFileLocationTest` (new, JUnit, `@TempDir`)

| ID | Case | Assertion |
|---|---|---|
| T1 | neither file | `choose()` returns the current path; no legacy file created |
| T2 | legacy only | resolves legacy for reading, `migrateIfNeeded` publishes current, returns `true` |
| T3 | T2 then second call | returns `false`; legacy bytes **and mtime** unchanged; current content byte-equal to the first migration result |
| T4 | current only | resolves current; no legacy open or parse (assert via a non-readable legacy path) |
| T5 | both, current newer | current wins; one warning at most; neither file rewritten by the resolver |
| T6 | both, legacy newer | current still wins; the "legacy is newer" warning is emitted (assert on the log capture) |
| T7 | legacy empty (0 bytes) | current created with defaults; legacy untouched |
| T8 | legacy comment-only | current created with header, defaults, the added key set, and the comment kept. **Not blocked any more**: the `NodeDiff` fix is in the working tree (11.4, historical). Do not mark `@Disabled`. Passing status requires a fresh run. |
| T9 | legacy has operator values (e.g. `guard-severity: THROW`, custom autosave span) | values present in current; new header present; old header text **absent** |
| T10 | legacy unknown key | behaves exactly like today's upgrade (removed, reported) — do not invent new preservation semantics |
| T11 | legacy unreadable / invalid YAML, current absent | exception names the legacy path; no current file created; legacy untouched |
| T12 | legacy is a symlink | target content read and written to a regular new file; link untouched (POSIX; skip on unsupported FS) |
| T13 | relative directory | paths resolve against the working directory like today (no absolute conversion inside the helper) |
| T14 | interrupted migration (exception injected between temp write and move) | no `aurora-*.yml` exists; legacy unchanged; the next call retries and publishes the file |
| T15 | validation of the temp file fails | no `aurora-*.yml` exists; legacy untouched; exception names the legacy path |
| T16 | new name is a symlink (to any file) | refused; nothing written; both paths named (POSIX; skip on unsupported FS) |
| T17 | new name is a hard link to the legacy file | refused; legacy bytes and mtime unchanged |

### 9.2 Wiring tests

| ID | Case | Assertion |
|---|---|---|
| W1 | `GlobalConfiguration` reload against a temp dir with legacy only | file created; no exception; no duplicate `onDiffAdd` on the next reload |
| W2 | `WorldConfig` base path follows the resolved value | `buildForLevel` merges `canvas-patch.yml` on top of the *new* base |
| W3 | validation failure on the migrated file | previous `INSTANCE` retained (mirror of `GlobalConfiguration.java:113-119`) |
| W4 | `/sourbycraft config` output | names the new files; no stale legacy text |
| W5 | Spark parser with legacy only / new only / both | same JSON shape; keys unchanged (recommended option) |
| W6 | `TimeSpanValidation` message | contains the effective path (or both names during the transition) |
| W7 | partial reload: the global file is valid, world 2 of 3 has an invalid override | global and world 1 run the new values; worlds 2 and 3 keep the old values; the bridge reports one failure; the test pins this partial state (it does not assert all-or-nothing) |

### 9.3 CI / manual boot gates (owner/integrator — not run by this plan)

| ID | Case | Assertion |
|---|---|---|
| B1 | fresh dir (today's CI behaviour) | only `config/aurora-server.yml` + `config/aurora-worlds.yml` exist; `Done (` reached |
| B2 | pre-seeded legacy file with a non-default value | `sha256sum config/canvas-server.yml` unchanged across the boot; new files exist; the value is live — use an observable LIVE key such as `autosave.autosave-frequency: 90s`, whose boot broadcast names the span (`GlobalConfiguration.java:206`), then restore the default |
| B3 | both files, legacy newer | warning in the log naming both paths |
| B4 | downgrade, two cases: (a) migrated installation, (b) new-only installation with the R2 copy | (a) old jar reads the legacy snapshot; (b) old jar reads the copied values, not defaults; section 8 steps 1-4 hold; the backups exist |
| B5 | `/sourbycraft reload` after changing keys in the new file | **Scope:** a LIVE key (e.g. `chat.disableChatVerificationOrder`) applies; a RESTART_REQUIRED key (e.g. `region-scheduler.default-tick-rate`) keeps its boot value and is covered only by the generic restart caveat. Engine YAML keys are **not** in the pending-key report: `SourbyCraftConfig.pendingRestartKeys()` (`SourbyCraftConfig.java:72-76`) compares only the utility/Aurora TOML snapshots (`restartChanges`), and the reload report says the engine keys are not all enumerated. Adding engine pending-key tracking is a separate PROPOSED change (section 10, Q11), not part of this gate. |
| B6 | Docker | `docker compose up` with an existing `./data` from a pre-rename build; files appear under `/data/config/` with the new names; old preserved |
| B7 | interrupted migration on a real boot (for example a `kill -9` during the copy, in a test directory) | next boot publishes the file from the untouched legacy snapshot; no empty `aurora-*.yml` is ever left |

Suggested CI insertion (CI owner): one boot directory modelled on the existing `run-awf` block
(`build.yml:629-636`) with a pre-seeded `config/canvas-server.yml`, then grep the migration INFO
line, compare the old file's SHA-256 before/after, and assert the new file exists.

Commands for the unit tests once they exist (through the shared lock, per AGENT-COORDINATION):

```sh
# AGENT_NAME = the name on the board
AGENT_NAME=<agent> /private/tmp/claude-501/-Volumes-Ext-Sourby-SourbyCraft/a9dedc8d-e562-4440-8e46-781542e8f43d/scratchpad/gradle_locked.sh \
  -PincludeTestPlugin=true :sourbycraft-server:test --tests '*ConfigFileLocationTest' --tests '*ConfigurationUpgradeTest'
```

Expect the known `*TestSuite > initializationError` filter artifacts (four suite classes) in any
`--tests`-filtered run; judge by the per-class XML, not the task exit code.

---

## 10. Risks and open questions

| # | Question / risk | Recommendation |
|---|---|---|
| Q1 | Are `aurora-server.yml` / `aurora-worlds.yml` the approved names? | Owner decision. Everything else in this plan is name-agnostic. |
| Q2 | Rename Spark's JSON keys (`canvas-server.yml`, group `canvas/`) too? | **No, not in this change.** Keys are a reporting contract; rename them separately with a note. |
| Q3 | Rename `canvas-patch.yml`? | Separate plan; it lives in dimension storage directories and needs per-world migration. State it in the release note if the global files are renamed. |
| Q4 | How long is the legacy name read? | Owner decision. Record the end build number in the docs when P4 is scheduled; R4 (auto-updater rollback) must be reviewed first. |
| Q5 | Escape-hatch property (5.5)? | Only if the owner wants one; otherwise the hard failure in rule 5 plus the error text is enough. |
| Q6 | Read-only config directories | Not a new risk: the loader already writes on every load (2.2). Mention in the release note. |
| Q7 | Dead `FoliaServerConfigProvider` | Resolved 2026-10-20: deleted. |
| Q8 | Concurrent-agent ownership | Todos: tests under `dev/iyanz/aurora/engine/` are `eng-sonnet`'s; `docs/README.md`, `docs/TODO.md` and `TEST-COVERAGE-GAPS.md` are PM's; `awf/perf` is `awf3-opus`'s. Sequence P1/P2 after those blocks close, or hand the tests to their owner. |
| Q9 | **Pre-existing:** every ordinary load truncates and rewrites the live file (`ConfigurationProvider.java:397`). A crash mid-write can leave an empty or partial `aurora-*.yml`, and an empty file is then flood-filled with defaults. This plan makes only the migration atomic (rule 9). | Owner decision: make every write atomic (temp file + `ATOMIC_MOVE`). Recommended as a separate change; it is not caused by the rename. |
| Q10 | **Spark path mismatch:** the runtime resolves the per-world override under the dimension storage directory (`WorldConfig.java:127`), while the Spark reader uses `world.getWorldFolder()` (`SourbyServerConfigProvider.java:298`). For non-overworld dimensions these can differ, so Spark can report a different file than the one applied. | Pre-existing; fix in the Spark change of P2 by resolving through the same helper as the runtime, or note it. Verify on a Nether/End world before choosing. |
| Q11 | Engine YAML keys have no pending-restart reporting (B5). | Owner decision: add engine pending-key tracking to the proposed scope, or keep the current generic caveat. This plan assumes the second. |

---

## 11. Evidence (what was actually run/read)

### 11.1 Search (read-only)

```
grep -rEn "canvas-server\.yml|canvas-worlds\.yml" .      → 33 matches in this repository
grep -rn "canvas-patch" .                                → WorldConfig + Spark readers only
```

(`rg` is not installed in this environment; the built-in content search and `grep` were used.)

Non-generated hits are exactly the rows in section 6. Everything else was
`sourbycraft-server/.gradle/caches/...` (Paperweight task cache) or `build/awf-bridge-verification*`
(boot outputs), i.e. not source. `paper-server/`, `paper-api/`, `sourbyapi/src` and
`sourbycraft-server/src/main/resources` contain **zero** matches.

### 11.2 Files read for this plan (source of the line references)

`GlobalConfiguration.java`, `WorldConfig.java`, `configuration/ConfigurationProvider.java`,
`configuration/Part.java`, `configuration/NodeDiff.java:150-185`,
`configuration/validation/TimeSpanValidation.java`, `config/upstream/CanvasConfigBridge.java`,
`config/upstream/UpstreamConfigBridge.java`, `SourbyCraftConfig.java` (reload section),
`command/SourbyCraftCommand.java`, `spark/SourbyServerConfigProvider.java`,
`aurora/engine/spark/FoliaSparkPlugin.java`,
`util/migration/{Migration,CanvasFileMigration}.java`, `subcommands/ReloadSubCommand.java`,
`src/minecraft/java/.../commands/Commands.java`, `.../Level.java.patch`,
`.../TickRegionScheduler.java`, `.../RegionizedServer.java`, `.../TickGuard.java`,
`src/test/.../ConfigurationUpgradeTest.java`, `TimeSpanValidationTest.java`,
`TimeSpanOptionValidationTest.java`, `GlobalConfigurationDocumentationTest.java`,
`.github/workflows/build.yml` (config-related lines), `docker/entrypoint.sh`, `docker/docker-compose.yml`,
plus the docs listed in 6.3. The 2026-10-20 review additionally traced the lifecycle cells marked
"review-traced" in section 2.3.

No source, doc, config or patch file was modified while investigating (Gradle wrote only its own
`build/` outputs). `docs/TODO.md`, `docs/README.md`,
`TEST-COVERAGE-GAPS.md`, `.github/workflows/build.yml`, build files, patch trees and all
`awf/`, `perf/` and `aurora/engine` tests were not touched.

### 11.3 Gradle run (one, through the shared lock; historical)

```
AGENT_NAME=oc-deepseek-flash \
  /private/tmp/claude-501/-Volumes-Ext-Sourby-SourbyCraft/a9dedc8d-e562-4440-8e46-781542e8f43d/scratchpad/gradle_locked.sh \
  -PincludeTestPlugin=true :sourbycraft-server:test --tests '*ConfigurationUpgradeTest'
```

Result (recorded 2026-10-20, before the `NodeDiff` fix in the working tree): `19 tests completed, 5 failed` → per-class XML:
`ConfigurationUpgradeTest` **15 tests, 1 failure**, and 4 × `*TestSuite > initializationError`
(`EntityOptimizationTestSuite`, `RuntimeModernizationTestSuite`, `SourbyMetricsTestSuite`,
`SparkConfigTestSuite`) which are the known `--tests`-filter artifacts (a filtered `@Suite`
discovers no members), not failures of this class. So the real count was 14/15 green at that time.
This run is not evidence of the current state; a fresh run is required.

### 11.4 Historical: comment-only defect (observed 2026-10-20; fixed in the working tree)

```
dev.iyanz.aurora.engine.configuration.ConfigurationUpgradeTest
  > anEmptiedOrCommentOnlyFileIsFloodFilledAgain(Path) FAILED
  java.lang.UnsupportedOperationException
    at java.util.AbstractList.add
    at dev.iyanz.aurora.engine.configuration.NodeDiff.applyChange(NodeDiff.java:173)
    at dev.iyanz.aurora.engine.configuration.ConfigurationProvider.buildSolidConfiguration(ConfigurationProvider.java:163)
```

This is the failure as it was observed when the plan was written. It is kept as dated evidence and is
**not** a statement about the current code. The current working tree does not have it:
`NodeDiff.applyChange` copies the mapping's tuple list into an `ArrayList` before add and remove
(`NodeDiff.java:153`), and `ConfigurationUpgradeTest.aFileOfOnlyCommentsGetsEveryKeyAddedAndKeepsItsComment`
(`ConfigurationUpgradeTest.java:204`) covers the comment-only file. The original failing method has
since been split. A truly empty file never reached this code: `compose` returns `null` and takes the
flood-fill branch (`ConfigurationProvider.java:154-158`). Passing status still requires fresh test
evidence. The earlier recommendation to disable T8 is withdrawn.

---

## 12. Not done by this document

- No code, test, header, command-text, Spark, CI, Docker or patch change.
- No file under `config/` was created, renamed, copied or deleted; no server was booted.
- No decision was made on the proposed names, the Spark key rename, the deprecation end (P4), the
  escape-hatch property, or the open questions in section 10 — all are marked PROPOSED / open.
- `docs/README.md`, `docs/TODO.md`, `TEST-COVERAGE-GAPS.md`, `.github/workflows/build.yml`, build
  files, patch trees, `awf/`, `perf/` and `aurora/engine` tests were not edited.
- The 15/15 green state of `ConfigurationUpgradeTest` is not claimed. Section 11.4 is historical.

---

## 13. Review notes (2026-10-20)

Independent read-only Codex review of this plan, checked against the current 26.2 working tree.
Each finding was re-checked with `grep`/`sed` before the text was changed.

- **F1 Interrupted migration:** applied. Rule 9 and section 5.3 publish through a temp file and an
  atomic move. Acceptance cases T14, T15, B7.
- **F2 Re-upgrade recovery:** applied. R3 forbids the blind copy and the blind delete; it requires
  backups and an explicit reconciliation.
- **F3 Downgrade without legacy files:** applied. Section 8 separates migrated and new-only
  installations and documents the restart sequence. The `.prev` restore runs on the next process
  start, not in the running JVM (`UpdateRollbackGuard.java`, `restore`).
- **F4 LIVE classifications:** applied. `vanilla-fixes.mc223153` (`Blocks.java:5987`),
  `player-info-send-interval` (`PlayerList.java:113`), the projectile override
  (`Projectile.java:49`) and the spawner split were verified. The catch-all is replaced by
  "unverified until traced". Two earlier LIVE labels (`overloaded-log-millis`, the other
  `vanilla-fixes.*` keys) were not traced and are now marked unverified.
- **F5 Reload guarantee:** applied. Section 2.3 limits the guarantee to the validation before global
  publication and the per-level previous configuration, and describes partial reloads.
- **F6 New-path aliases:** applied. Rule 10 refuses symlink and same-file aliases; T16 and T17.
- **F7 "No format change":** applied. Rule 8 now promises values and supported comments only.
- **F8 Unreadable-legacy matrix:** applied. Section 4.1 fails for an unreadable legacy file only when
  migration needs it, and makes the new-wins row consistent with T4.
- **F9 Comment-only blocker:** applied. The blocker is removed from the matrix, T8 and the touch
  points. The defect is kept as dated historical evidence (11.4). The `NodeDiff` fix and regression
  test were confirmed (`NodeDiff.java:153`, `ConfigurationUpgradeTest.java:204`).
- **F10 B5 scope:** applied. B5 now covers the TOML-based pending-key report only; engine YAML
  pending-key tracking is Q11, an owner decision.
- **F11 Per-world override location:** applied. The location is
  `<dimension storage directory>/canvas-patch.yml` (`WorldConfig.java:127`). The review's point that
  Spark uses `world.getWorldFolder()` (`SourbyServerConfigProvider.java:298`) is confirmed. For
  non-overworld dimensions the two paths can differ; this is recorded as Q10 and not fixed here.

**Source of the findings:** the copy at `codex-plan-findings.txt` starts partway through finding 2
and does not contain findings 1 and 2. The full text of findings 1 and 2 was taken from the complete
review output (`codex-plan.out`, its numbered list). No finding was rejected.
