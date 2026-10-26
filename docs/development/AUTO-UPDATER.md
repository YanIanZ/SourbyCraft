# Auto-updater (`updater:` in `sourbycraft_config/sourbycraft_global_config.yml`)

Key names below are the Build 49 ones (`updater.*`, kebab-case). The pre-49 names (`misc.auto_update.enabled`,
`repo`, `apply_mode`, `check_times`, `check_interval_minutes`, `restart_mode`, `restart_delay_seconds`,
`allow_prerelease`, `via_auto_update`) are not read since Build 50. The one-time boot rewrite of
`config/ConfigMigration` still renames them (copy kept as `*.migrated`); an old name left in the file (a
read-only directory, or added back by hand) is ignored with one WARN per load (`ConfigMigration.stripLegacyNames`).

State as of 2026-10-20 (security wave SEC-2, SEC-3, SEC-9, SEC-10; OBS-8 for `/update`), updated 2026-10-25 for
the Build 50 work in PR #62: apply preflight and atomic swap, `apply-failed.tag`, releases for another Minecraft
version announced only, asset download limits, the boot guard moved before every bootstrap stage, several trusted
release keys. The PR #62 changes are **code written and statically reviewed; not built; not tested by the
owner**, and no published build contains them: tag `v26.2-r50` (`5f5a167`) predates them, and how they ship is
owner decision PM-011 ([PM matrix](../releases/BUILD-50-PM-MATRIX.md)). Test names below that predate PR #62
are existing tests; PR #62 wrote no new tests and only adjusted `UpdateApplierRestartPlanTest` and
`UpdateApplierSwapTest` to the changed behaviour.
Code: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/update/`, command
`command/UpdateCommand.java`. Every claim below names the class that implements it; the test
column names the regression test that pins it.

## What it does

`SourbyUpdater` polls `https://api.github.com/repos/<repo>/releases` for a newer release **on the
same channel** (REL/DEV/EXP, from this build's version suffix) and acts according to
`apply-mode`:

| `apply-mode` | Behaviour |
|---|---|
| `notify` (**default** since this change; was `auto`) | log + operator banner only |
| `stage` | download, verify, place in `auto_update/sourbycraft/`, write the pointer `auto_update/core.path`. **A restart does not install it** (nothing in this server reads `core.path` at boot): `/update apply`, which reuses the verified copy, or a manual copy over the launch jar does. A stage that already holds a file with the release digest is not downloaded again (the signature check, when enforced, and the launcher gate still run). PR #62 made the stage log line, `/update` and `/update status` say this |
| `auto` | `stage`, then swap the launch jar and restart after `restart-delay-seconds` (see [Previous jar and rollback](#previous-jar-and-rollback-sec-3--updaterollbackguard)) |
| `off` | no checks scheduled |

`/update` (`/update check`) runs one check with the configured mode, `/update apply` forces one `auto` run
(also for a release whose earlier apply failed), `/update status` prints config, markers (`applied.tag`,
`apply-failed.tag`, an older build's `pending.tag`, a staged jar) and the rollback-guard state, `/update resume`
lifts a rollback hold. `/update check` and `/update apply` start nothing while the server is stopping or after a
failed startup (`AuroraRuntime.stopping()`); `status` and `resume` are not gated (PR #62; `status` still needs
the I/O executor and answers "Status unavailable" once it has stopped).

**One check at a time** (PR #62). Scheduled checks and `/update` share one guard (`SourbyUpdater.tryCheck`): a
check that finds another one running is skipped with a log line, and `/update` answers that a check is already
running instead of starting a second download. While an apply counts down (`UpdateApplier.inFlight()`), a `stage`
or `auto` check returns `APPLYING` without downloading or staging again.

**Existing installs keep their stored value.** The YAML seed only writes keys that are absent
(`SourbyCraftConfig.seed`), so a file created under the old default still says `apply-mode: auto`
until the operator changes it. New files get `notify`.

## Settings and reloadability

Verified against the consumers: `/sourbycraft reload` → `SourbyCraftConfig.reloadDetailed` →
`applyLiveConfig` → `AutoUpdateSettings.loadFromToml()` (the name is historical; it reads the YAML
snapshot) refreshes the static fields; `SourbyUpdater.check()` reads them on every check;
`SourbyUpdater.start()` builds the schedule once at boot and is not re-run by reload. A key that is
missing, blank or of the wrong type reads as the default in the table below, not as the previous live
value: deleting `apply-mode` and reloading gives `notify`.

| Key | Default | Reloadability |
|---|---|---|
| `enabled` | `true` | RESTART_REQUIRED (decides whether `start()` runs) |
| `repository` | `YanIanZ/SourbyCraft` | LIVE (next check) |
| `channel` | `""` = from the build version | LIVE (next check) |
| `apply-mode` | `notify` | LIVE between `notify`/`stage`/`auto` (next check). Switching **to** `off` is effectively LIVE: every later check ends at once with `DISABLED`, although the schedule keeps firing (and so does the Via pin recovery of `via-auto-update`, which `checkSafely` runs after each check). Switching back **from** `off` needs a restart when the server booted with `off`, because then `start()` built no schedule |
| `check-times`, `check-interval-minutes` | `["06:00"]`, `30` | RESTART_REQUIRED (schedule built in `start()`); `/update status` prints the interval the running schedule uses, plus `(pending restart: Nm)` when a reload changed it |
| `restart-mode`, `restart-delay-seconds` | `auto`, `60` | LIVE (read when an apply is scheduled); `restart-delay-seconds` is clamped to at least 5 |
| `allow-prerelease` | `false` | LIVE (next check) |
| `via-auto-update` | `true` | LIVE, but it runs only inside the updater's schedule: it needs `updater.enabled: true`, an `apply-mode` other than `off` at boot, and `plugins.viaversion.auto-provision` absent or `true` (a quoted or misspelt value disables it). It reinstalls a missing reviewed Via pin and never upgrades; an installed jar takes effect on restart (section [Via plugin trust policy](#via-plugin-trust-policy-sec-6)) |

## Verification chain before a jar is staged

Before any download, a build that ships the release key first picks the release through the signed
release manifest (yank list + staged rollout; section below). Then, in order,
`SourbyUpdater.downloadAndStage` / `verifyOrThrow`:

0. **Asset checks before any request** (PR #62, `stagedPath`, `getAsset`, `UpdateOverrides.assetUrlAllowed`).
   The jar asset name must be a plain file name of letters, digits, `.`, `_` and `-` ending in `.jar`
   (`[A-Za-z0-9][A-Za-z0-9._-]*\.jar`) and resolve inside `auto_update/sourbycraft/`; a declared size above
   `MAX_JAR_BYTES` (512 MiB) is refused; every asset URL (jar, `.sig`, manifest, manifest `.sig`) must be
   `https`, plain `http` only on a loopback host while the test override `sourbycraft.update.api-base` is set.
   Every asset body has a **15-minute total deadline** (`MAX_DOWNLOAD_TIME`, `DeadlineInputStream`) on top of
   the transport's per-read timeout, and each download goes to its own fresh temporary file. No test was
   written for these checks in PR #62.
1. **Bound before reading** (SEC-10). The bound is the asset's declared `size`, else
   `MAX_JAR_BYTES` (512 MiB). A `Content-Length` above the bound is refused without reading the
   body; a stream that runs past the bound is cut off and the `.part` file deleted
   (`copyBounded`). Test: `SourbyUpdaterCheckTest.oversizeContentLengthIsRefusedBeforeAnyByteIsRead`,
   `aStreamRunningPastTheDeclaredSizeIsCutOffAndDeleted`, `copyBoundedStopsExactlyPastTheLimit`.
2. Non-empty, equals the declared size.
3. **SHA-256 equals the GitHub asset `digest`** — mandatory; a release without a digest is refused.
4. Opens as a ZIP.
5. **Ed25519 signature** — only when this build ships a public key (next section).
6. **Embedded launcher** (SEC-4) — see "Launcher gate" below. Runs last, so only authentic bytes are inspected.

A failing download is deleted and never staged (`Outcome.STAGE_FAILED`); a jar whose launcher this
server cannot hand over to is deleted and answered with `Outcome.CLIP_INCOMPATIBLE`.

## Launcher gate (SEC-4) — `LauncherCompatibility`

The staged jar becomes the `-jar` argument of the next boot, so the SourbyClip launcher **inside it**
decides how that boot starts, while the operator's start command stays unchanged. `SourbyUpdater`
therefore reads two small entries (each bounded to 16 KiB) of the verified jar and refuses it
unless they describe a launcher this server can hand over to:

| Source | Keys | Written by |
| --- | --- | --- |
| `META-INF/MANIFEST.MF` | `Clip-Version`, `SourbyClip-Protocol` | SourbyClip's `build.gradle.kts` (3.1.2: `3.1.2`, `1`) |
| `META-INF/sourbyclip.properties` | `version`, `protocol` | SourbyClip's `build.gradle.kts` identity task; what `--sourbyclip-info` prints |

Both were read from `build/libs/SourbyCraft-slim.jar` (clip 3.1.2) on 2026-10-20 and matched
`clipVersion=3.1.2` in `gradle.properties` then. The pin is now `clipVersion=3.1.3` (protocol 1, unchanged;
released in tags `v26.2-r48` through `v26.2-r50`); the table was not re-read from a 3.1.3 jar. The rules, all in
code constants:

- **Protocol must equal `SUPPORTED_PROTOCOL = 1`.** SourbyClip's `AGENTS.md` ties a protocol bump to a
  SourbyPatcher release (`VerifySourbyClip` hard-codes `"1"`), i.e. it is a launch-contract change, not a
  routine update.
- **Version must be at least `MIN_VERSION = 3.0.23`**, the first SourbyClip release with the manifest
  protocol (SourbyClip `readme.md`). Compared numerically (`3.10.0 > 3.9.0`); anything that is not a
  dotted number (`3.1.2-SNAPSHOT`, `latest`) is refused as unreadable.
- **Manifest and properties must agree** (same build wrote both); a disagreement means a hand-made repack.
- **Missing metadata fails closed.** `release/SourbyCraft-26.2-REL.jar` (Clip-Version 3.0.21) has only the
  manifest version and no protocol and no properties file; that shape is refused. Reason: a staged jar is
  executed unattended, the updater already refuses an unverifiable digest the same way, and a wrong refusal
  costs one WARN line plus a manual jar swap while a wrong acceptance costs a restart into an unchecked
  launcher (the rollback guard only helps after that boot has already failed). Every jar built from this
  tree carries both entries.

A refusal logs `auto-updater: NOT staging <tag>: the release's launcher is not compatible [<REASON>]: ...`,
deletes the download, writes no `core.path`/`latest.path`, and returns `Outcome.CLIP_INCOMPATIBLE`
(`/update` prints a distinct line; `UpdateCommand.describe`). Reasons: `METADATA_MISSING`,
`METADATA_UNREADABLE`, `METADATA_INCONSISTENT`, `PROTOCOL_UNSUPPORTED`, `VERSION_TOO_OLD`.

Limits: the gate reads declared metadata; it does not execute the launcher (`--sourbyclip-info` is not
run) and cannot detect a launcher whose class files disagree with its metadata. It also does not compare
against the *running* launcher, so a later minimum bump needs a code change here. Tests:
`LauncherCompatibilityTest`, and in `SourbyUpdaterCheckTest` `aReleaseWithACompatibleLauncherIsStaged`,
`aReleaseWhoseLauncherSpeaksAnotherProtocolIsRefusedAndDeleted`, `aReleaseWithNoLauncherMetadataFailsClosed`
and the others in the "SEC-4: launcher gate" section.

## Hotfix releases (`v26.2-r<N>.<M>`)

A hotfix publishes a fix for an already released build without the later 26.2 work. It is cut as a
branch `hotfix/26.2-r<N>.<M>` from the tag `v26.2-r<N>`, carries only the fix commits plus
`sourbyBuild=<N>.<M>` in `gradle.properties`, and is published by a push to that branch through the
same CI jobs as a 26.2 release (build and tests, boot test, rebuild compare, signature, signed
manifest, provenance attestation). `scripts/release_version.py --hotfix-branch` refuses the publish
when the hotfix tag already exists, when `v26.2-r<N>` was never published, when `<M>` does not exceed
an earlier hotfix of `<N>`, or when `sourbyBuild` does not equal `<N>.<M>`; any later push to the
branch is a development build. The release notes list the commits since `v26.2-r<N>`, and a hotfix
of an older build than the newest release is published without the "Latest" mark.

Identity in the jar: `buildNumber`/`build` in `sourbycraft-build.properties` and the manifest
`Implementation-Version` say `<N>.<M>` (`/ver`: `Build 48.1 — Aurora Nexus`); the manifest
`Build-Number` stays the integer `<N>` because Paper parses it with `Integer.parseInt`
(`ServerBuildInfo.buildNumber()` is an `OptionalInt`), so `Bukkit.getVersion()` still reads
`26.2-<N>-...`. `scripts/verify_build_identity.py` checks both. The updater compares
`BuildVersion` tokens: `48 < 48.1 < 49`, so a Build 48 server is offered `v26.2-r48.1`, a 48.1 server
is offered `v26.2-r49`, and a 49 server is never offered `v26.2-r48.1` (`BuildVersion`,
`SourbyUpdater.releaseVersionOf`).

## Same-Minecraft-version gate (SEC-3)

A newer release whose tag core differs from the running Minecraft version (`v26.3-r100` on a 26.2
server) is **announced but never staged or applied**: one WARN calls it a manual upgrade (worlds, plugins,
data formats). The running version is `BuildInfo.mcVersion()` (stamped from `gradle.properties` `mcVersion`),
falling back to the core of the build version (`26.2-REL` → `26.2`). A build that can say neither ("unknown",
e.g. a bare dev classpath) stages nothing. Test: `aReleaseForAnotherMinecraftVersionIsAnnouncedButNeverStaged`,
`minecraftVersionComesFromTheBuildStampOrTheVersionCore`.

**Since PR #62 the two groups are kept apart** (`SourbyUpdater.check`, `latestForChannel(..., sameMc)`). The
newest newer release for the running Minecraft version is selected, staged and applied on its own; the newest
newer release for another Minecraft version is only announced and no longer hides it. Before, a newer-Minecraft
release ranked above every same-version tag and blocked same-version updates. When only another Minecraft
version is newer, the outcome stays `MC_VERSION_MISMATCH` (`stage`/`auto`) or `NOTIFIED` (`notify`). With a
release key, newer-Minecraft manifests are inspected first, at most 5 (`MAX_MANIFEST_CANDIDATES`, counted per
group), so their signed yanks count; they are never selected. A missing or broken newer-Minecraft manifest still
fails closed with `MANIFEST_REJECTED`, on purpose: its yanks cannot be learned. No scripted test for the
two-group case was written in PR #62; the owner scenario is in the PM matrix (PM-109).

## Previous jar and rollback (SEC-3) — `UpdateRollbackGuard`

Files, all next to the server: `<launch jar>.prev`, `auto_update/rollback.state` (a properties
file with `phase`, `tag`, `launch`, `prev`, `applied_at`, `note`, and optional `prev_sha256`), plus the pre-existing
`auto_update/applied.tag` and, since PR #62, `auto_update/apply-failed.tag`.

Sequence for `apply-mode=auto` (or `/update apply`):

0. **Preflight, before any broadcast** (PR #62, `UpdateApplier.preflight`). The launch jar (the `-jar`
   argument of this JVM, `launchJarPath()`) must be known and must not be the staged jar itself, the staged
   jar must exist, the launch jar's directory must be writable, and its file store must have free space of
   at least size(launch jar) + size(staged jar) (the kept `.prev` plus one temporary copy). A failed preflight
   announces nothing and swaps nothing; it ends as a failed apply (below).
1. **Apply** (`UpdateApplier.applyNow` → `swapInPlace`): copy the launch jar to `<jar>.prev` (via a `.part`
   file, then an atomic rename). If that copy fails, the swap and the restart do **not** happen — an
   update without a way back is not applied automatically. Since PR #62 the staged jar is then **copied** to a
   temporary file in the launch jar's own directory (`<jar>.update-*.part`), forced to disk
   (`FileChannel.force`), and its SHA-256 is checked again against the release digest; it gets the launch jar's
   POSIX mode. Next the guard records `phase=APPLIED` with `prev_sha256`: the streamed SHA-256 of the completed
   `.prev`, stored as 64 lowercase hex characters. This record is written **before** the swap: if it cannot be
   written, the swap and the restart do not happen either, so a restart never boots a new jar the guard
   does not track. Only then are the verified bytes renamed over the launch jar with `ATOMIC_MOVE` (one
   `rename(2)` in one directory: the launch path holds either the whole old jar or the whole new one) and
   `applied.tag` written; the staged copy and its pointers are deleted after that. If the rename fails, or the
   file system cannot rename atomically (`AtomicMoveNotSupportedException`; there is no delete-and-copy
   fallback), the state file is put back as it was before the apply and the old jar is untouched (a crash
   between record and rename leaves `APPLIED` over the unchanged old jar, which the next boot confirms or
   restores to the same bytes). Copy, hash and state write run on the updater's Bukkit async scheduler callback
   (the updater has no private executor), away from region/tick/main-server threads. The hash is
   preserved on every later rewrite, including `BOOTING`, `OK`, `ROLLED_BACK` and `RESTORE_FAILED`.
   This state contract applies to future applies; existing states without a hash keep their legacy behavior.
   The directory entry is not fsynced after the rename, so whether the rename survives a power loss depends on
   the file system's journal; a `<jar>.update-*.part` left by a kill during the copy is not cleaned up
   automatically.
   **A failed apply never restarts** (PR #62). A preflight failure, or any swap result other than success,
   logs one ERROR with the manual steps (stop the server, copy the staged jar over the launch jar, keep the old
   one, start again; or fix the cause and run `/update apply`), broadcasts that the server is **not**
   restarting when a countdown was already announced, writes the tag to `auto_update/apply-failed.tag` and keeps
   the server running. While that marker names the newest release, scheduled checks and `/update check` only
   announce it (no download, no broadcast) until `/update apply` succeeds or the file is deleted; a `pending.tag`
   written by an older build is read the same way and is no longer written. Before PR #62 a failed swap still
   restarted the server (a restart loop on the unchanged old jar, PM-034).
2. **First boot on the new jar**: `SourbyCraftBootstrap.init()` (runs in `DedicatedServer.initServer`, before
   worlds load) calls `AutoUpdateSettings.runBootGuard()` **before its first stage** (PR #62; before, the guard ran
   in the late `auto updater` stage, so a new jar that hung or exited in an earlier stage left `APPLIED` behind).
   `guard.onBoot()` sees `APPLIED`, records `phase=BOOTING` and registers a `ServerLoadEvent` listener. **Ready** is
   `ServerLoadEvent STARTUP`: worlds loaded and plugins enabled, fired at the end of world loading
   immediately before `acceptConnections()` and shortly before the `Done (` line
   (`MinecraftServer.java` line ~665 in the materialized source). Reaching it records `phase=OK`, except when a
   stop was requested during startup and is being held until startup ends (MC patch 0076): such a startup never
   opens the server, so it confirms nothing (WARN) and the phase stays `BOOTING` (PR #62).
3. **Boot that finds `BOOTING`**: the previous boot after the apply ended before ready. The guard
   refuses a `.prev` that is a symbolic link (`RESTORE_FAILED`, jar files untouched). It copies
   `.prev` once into a fresh temporary file beside the launch jar and, when `prev_sha256` is
   present, hashes that temporary file, so the bytes renamed over the launch jar are exactly the
   bytes verified even if `.prev` changes meanwhile. A mismatch (including an invalid/empty
   recorded hash) refuses restore, logs an ERROR naming `.prev`, expected and actual hashes,
   records `RESTORE_FAILED`, and leaves every jar file untouched. Comparison accepts uppercase
   hex; every state write normalizes it to lowercase. With no field, the copy is restored
   unverified, as before. This restore validation runs during the existing server boot guard, before
   worlds/ticking; it is distinct from async apply hashing. With a matching hash, the guard
   renames the verified temporary copy over the launch jar (`.prev` stays), records
   `phase=ROLLED_BACK` and the updater is **held**: every `check()` returns `Outcome.HELD` without
   touching the network, and the boot log prints a framed `UPDATE ROLLED BACK` block. If `.prev`
   is missing or the copy fails: `phase=RESTORE_FAILED`, also held, with a `ROLLBACK FAILED` block.
4. **Every later boot** while held prints `AUTO-UPDATER HELD` and does nothing else.

The guard boot step runs even when `enabled=false` or `apply-mode=off`:
`SourbyCraftBootstrap.init()` calls `AutoUpdateSettings.runBootGuard()` before its first stage, once per
process (a second call does nothing).

**Restoring a jar older than Build 49** (PR #62, `UpdateRollbackGuard.restorePre49Configs`). The guard reads
`buildNumber` from the restored copy (`META-INF/sourbycraft-build.properties` at the top level, or inside the
server jar a paperclip jar names in `META-INF/versions.list`). When it is below 49, the newest `*.migrated`
(or `*.migrated.N`) copy of each of `sourbycraft_config/sourbycraft_global_config.toml`,
`sourbycraft_config/aurora.toml`, `config/canvas-server.yml` and `config/canvas-worlds.yml` is copied back to its
old name, never over an existing file, and the result is noted in `rollback.state` (WARN, or ERROR naming the file
when a copy fails). A restored jar whose build number cannot be read gets no configuration back, and the note says
so. The nested-jar read was not checked against a built slim jar.

Operator actions: `/update resume` (or delete `auto_update/rollback.state`) lifts the hold.
`applied.tag` is left alone so the rolled-back tag is not offered again; delete it too to retry
that exact release. One exception since PR #62 (`SourbyUpdater.appliedButReplaced`): when the guard
confirmed that tag (`phase=OK` for it), the server nevertheless runs an older build, and the launch jar the
guard recorded no longer has the release's SHA-256, the launch jar was replaced outside the updater (for
example a Docker container recreated from an older image with the same `/data`); the check then WARNs and
offers the release again. Any other state, including no state after `/update resume`, keeps `applied.tag` in
force. Tests: `UpdateRollbackGuardTest` (one `onBoot()` per
simulated start), `SourbyUpdaterCheckTest.aRollbackHoldShortCircuitsTheCheckWithoutTouchingTheNetwork`.

**Launcher-side recovery — RESTART_REQUIRED, version-gated (not shipped by the current pin).**
`gradle.properties` currently pins `clipVersion=3.1.3`, below 3.1.4. SourbyClip 3.1.4's
`BootRollbackGuard` can restore before loading server classes: an `APPLIED` boot records a
`rollback.attempt` tied to tag/applied time; a later matching failed attempt, or `BOOTING`,
triggers validation and restore. It checks paths and the previous jar; when `prev_sha256`
exists it must be 64 hex characters and match `.prev`. Without it the launcher falls back to
requiring a jar manifest with `Main-Class`. A hash mismatch refuses the launcher restore too.
On success it preserves the hash, writes `ROLLED_BACK`, and sets the note to
`launcher: the boot after applying <tag> did not reach the updater stage or ready; <prev> copied back over <launch>`.
It logs `UPDATE ROLLED BACK by launcher` and exits **71** before loading suspect server code;
the panel/service must start the restored jar on the next cycle. This differs from apply's
clean shutdown exit **70**. The server holds **all** `ROLLED_BACK` states, including this exact
launcher state, with the same reporting and no checks/reapply until `/update resume`;
`applied.tag` still prevents re-offering that tag after resume. No note substring matching is used.
The fixture reads the pin at runtime: >=3.1.4 requires launcher restore/71; older pins explicitly
skip that expectation and check manual recovery. A broken launcher that cannot reach even its
own guard still needs manual recovery. No launcher-version bump is included here.

**Limits, stated plainly:**

- A running JVM cannot replace its own jar. The restore rewrites the file on disk; the boot that
  performs it is still running the updated bytes, and the restored build runs from the boot
  after that. With a panel that restarts on exit this costs one extra cycle.
- "Did not reach ready" is inferred from the phase file alone. A boot the operator stopped
  before ready, a host kill, or a crash unrelated to the update all look the same and trigger
  the restore. It is conservative, not a diagnosis; if the restored-from boot comes up fine the
  operator can `/update resume`, delete `applied.tag` and re-apply.
- A jar that never reaches `SourbyCraftBootstrap.init()` (launcher, library download, class loading,
  Minecraft/Paper startup up to the SourbyCraft hook in `DedicatedServer#initServer`, early JVM abort) is
  **not restored automatically by the currently pinned launcher/server path**: the server restore code
  is inside the jar that is booted, so every later boot dies the same way and `rollback.state`
  stays `APPLIED`. Shown on a real boot with a release whose `Main-Class` exits 4 (fixture
  scenario `dead-launcher`, 2026-10-22); the operator copies `<jar>.prev` over the launch jar by
  hand. The launcher metadata gate (SEC-4) does not catch it: it reads declared metadata only.
- The in-server swap is a same-directory rename (`ATOMIC_MOVE`). Not tested on Windows. There the JDK may not
  report the process arguments, so the launch jar is unknown and the preflight refuses; where it is known,
  renaming over the jar the JVM holds open is expected to fail. Either way the apply ends as a failed apply: old
  jar unchanged, earlier guard record put back, no restart, `apply-failed.tag` written (PR #62; the old
  `core.path` fallback text was wrong, nothing reads `core.path` at boot).
- Configuration put back for a restored pre-Build-49 jar is the copy the migration kept: a value changed in the
  YAML files during the failed boots is not carried back, the YAML files stay where they are and win at the next
  upgrade, and a restore done by the launcher gets no configuration back.
- Boot-tested locally on 2026-10-22 (apply → failed boot → restore → held, and the healthy apply
  that is confirmed and kept) with the fixture in
  [updater-rollback-fixture-2026-10-22.md](updater-rollback-fixture-2026-10-22.md): agent test server,
  fake release server on loopback, throwaway signing key, no clients. Not run on a panel, on Linux
  or Windows, against the real GitHub or with the real release key. The boot that performs the
  restore still runs the new bytes, so a rollback costs two failed boots (observed: boots 2 and 3
  of the fixture), not one. **NEEDS RETEST:** that run used the 2026-10-22 tree; PR #62 changed the swap
  (copy, re-hash, atomic rename), the guard's position in the boot and the failure path, so the result does not
  carry over to a build with PR #62.

## Local fixture overrides (test only)

Two JVM system properties exist for `scripts/updater_rollback_fixture.py` and
`agent_testserver.py --auto-update`. Both are unset by default, are not config keys and cannot be
set from the configuration files; only whoever starts the JVM can use them. Class: `update/UpdateOverrides`.

| Property | Effect |
|---|---|
| `sourbycraft.update.api-base=http://127.0.0.1:PORT` | the releases listing comes from `<base>/repos/<repo>/releases` instead of `https://api.github.com`. Only `http`/`https` on `127.0.0.1`, `localhost` or `[::1]`, no user info, path, query or fragment; a value that is set but refused makes `check()` return `DISABLED` and log an ERROR, it never falls back to GitHub. Every check logs a WARN while it is active. While it is set, release assets may also be fetched over plain `http` from a loopback host (`assetUrlAllowed`, PR #62); otherwise every asset URL must be `https`. |
| `sourbycraft.update.release-key-file=FILE` | PEM/base64 Ed25519 public key; turns jar-signature **and** signed-manifest enforcement on for a build that ships no `META-INF/sourbycraft-release.pub` (log: `release signatures ENFORCED (TEST key …)`). Ignored with an ERROR line when the build ships its own key, so it can only add enforcement. An unreadable, oversize (> 16 KiB) or unparseable file keeps enforcement on and failing closed. |

Tests: `UpdateOverridesTest` (9). The harness refuses `--auto-update` unless both properties are given.
Since Build 48 builds ship the key, so the fixture needs a jar built without it (procedure in
[updater-rollback-fixture-2026-10-22.md](updater-rollback-fixture-2026-10-22.md#run-it)).

## Restart modes (SEC-9)

`restart-mode` (`UpdateApplier.RestartPlan.resolve`, PR #62):

| `restart-mode` | What happens after a successful swap | Exit code |
|---|---|---|
| `auto` (default) | inside a Pterodactyl/Pelican container (`P_SERVER_UUID` set): as `exit`; elsewhere: as `stop` | 70 or 0 |
| `exit` | clean shutdown | **70**, so a panel's crash detection starts the server again |
| `stop` | clean shutdown | 0; systemd, a loop script or a panel must start the server again, or it stays down |
| `spigot` | clean shutdown, as `stop`, with one WARN: Spigot's restart command, and with it `restart-script`, is disabled under region threading | 0 |
| anything else | as `stop` | 0 |

Before PR #62 `auto` outside a panel and `spigot` called `RestartCommand.restart()`, which always threw under
region threading and fell back to a clean stop; the runtime result is the same, the log now says so. Every
`stop` plan logs that the server exits 0 and needs an external supervisor. The log prefix
`auto-updater: restarting now (plan=` that the rollback fixture matches is unchanged.

`exit` no longer registers a shutdown hook that calls `Runtime.halt(70)`. `UpdateApplier` sets
`MinecraftServer.abnormalExit = true` and calls `Bukkit.shutdown()`; the server's own path then
delivers the code: Folia's `RegionShutdownThread` → `stopServer()` (worlds, players, plugins,
region-file I/O flush, `AwfEngine.closeAll()`) → `onServerExit()` → `System.exit(abnormalExit ?
70 : 0)` (`DedicatedServer.java:550`). Test: `UpdateApplierRestartPlanTest` pins the mode → plan mapping
(PR #62 adjusted it: `auto` outside a panel, `null`, `spigot` and unknown values map to a stop); the shutdown
itself is not unit-testable and was not boot-tested here.

**Exit codes an operator can see after an update.** 0: clean stop (`stop`, `spigot`, `auto` outside a panel).
**70**: `exit`, or `auto` inside a panel; 70 can **also** mean that Aurora World Fabric could not confirm a
storage at stop (MC patch 0071 sets the same `abnormalExit` when `AwfEngine.closeAll()` reports an unconfirmed
storage), so read the log before assuming an update restart. **71**: only from a SourbyClip 3.1.4+ launcher
restore (not shipped by the current 3.1.3 pin; see above). A failed apply exits nothing: the server keeps
running.

## Polling etiquette (SEC-10)

The releases listing is a conditional request: the `ETag` of the last 200 is sent back as
`If-None-Match`; a `304` answers from the cached listing. A `429`, or a `403` carrying `Retry-After`
or `X-RateLimit-Remaining: 0` + `X-RateLimit-Reset`, arms a backoff window (clamped to 1 min – 6 h;
`429` without a hint waits 5 min) during which `check()` returns `Outcome.RATE_LIMITED` without a
request. Tests: `etagIsSentBackAndA304AnswersFromTheCachedListing`,
`rateLimitArmsABackoffWindowThatSkipsChecksWithoutRequests`, `retryAfterAcceptsSecondsAndHttpDatesAndIsClamped`.
Polling is still unauthenticated (60 requests/h per IP on GitHub); the default cadence (daily +
every 30 min) stays well under that.

## Signed releases (SEC-2) — **enabled since Build 48**

`ReleaseSignatureVerifier` (JDK `java.security`, Ed25519) is active **iff** the jar contains
`META-INF/sourbycraft-release.pub`. The key ships since Build 48 (resource added in `764ba62`,
fingerprint `sha256:a9643e8b3dcdaae50d0acd5a93f4ef1fff593ee7c8bf26199ca32a0b7232f9c4` over the DER
`SubjectPublicKeyInfo`), so builds from r48 on log
`release signatures ENFORCED (Ed25519 key from /META-INF/sourbycraft-release.pub)` and enforce both the jar
signature and the signed release manifest below. Tags `v26.2-r48`, `v26.2-r49`, `v26.2-r49.1` and `v26.2-r50`
carry the key; the release job creates the tag, and with the key in the tree its `Check release signing setup`
step fails unless the signing secret is set, so those publishes ran the signing step
([Build 48 release notes](../releases/26.2-build-48-aurora-nexus.md): first signed release). The CI logs were not
inspected for this page and no run id is recorded in the repository. A build without the resource (a local
keyless fixture jar) still logs `release signatures NOT enforced (no /META-INF/sourbycraft-release.pub in this build)`.
`ReleaseSignatureVerifierTest.thisBuildShipsTheReleaseKeySoTheVerifierIsActive` pins the fingerprint, so it fails
the day the key changes without this page changing.

When a key is present: the release must publish `<jar asset name>.sig` (the 64 raw bytes from
`openssl pkeyutl -sign -rawin`, or that value base64-encoded) and it must verify over the
downloaded bytes, after the SHA-256 check; otherwise `STAGE_FAILED`. A key resource that is present
but unparseable keeps enforcement on and failing closed. Tests: `ReleaseSignatureVerifierTest`
(key pair generated in the test), `SourbyUpdaterCheckTest.withAKeyInTheBuild*`,
`withoutAKeyTheSignatureAssetIsNeverEvenFetched`. Not yet exercised by the owner: verifying the published
r50 `<jar>.sig` and manifest signature against the shipped key, and one real apply on a test server (PM-112).

### Several trusted keys and key rotation (PR #62; rotation needs owner approval)

The key resource may hold several PEM `-----BEGIN PUBLIC KEY-----` blocks (`ReleaseSignatureVerifier.fromKeyText`).
A signature is accepted when **any** of them verifies it, for the jar (one read, one verifier per key) and for the
manifest. Every block must parse, or the whole resource counts as unusable and enforcement fails closed; a single
bare base64 key without armour is still accepted. With more than one key the boot line adds `, N trusted keys`.
In the release job, `Sign release jar and manifest (Ed25519)` splits the resource into its PEM blocks and passes
when any block verifies each new signature (`build.yml`, since PR #62), matching the server. Unit tests for the
multi-key path were not written in PR #62.

What a rotation would look like (code makes it possible; the procedure is **not approved**, owner decision):
`python3 scripts/release_signing_key.py --add-pub` appends a new public key as an additional trusted block
instead of refusing because a different key is already there; the release keeps being signed with the old key
until at least one release that trusts both keys has shipped; only then is the CI secret switched to the new key.
Moving the new block first in the resource is optional, because the release job and servers accept a signature
from any block. A server that never ran a build trusting the new key needs a manual update after the switch.
`ReleaseSignatureVerifierTest.thisBuildShipsTheReleaseKeySoTheVerifierIsActive` hashes the whole resource as one
key, so it has to be adapted in the same change that adds a second block. No compromise procedure exists yet.

### Turning it on (owner actions, in this order)

**Status 2026-10-22:** done. The key pair was generated (fingerprint
`sha256:a9643e8b3dcdaae50d0acd5a93f4ef1fff593ee7c8bf26199ca32a0b7232f9c4`), the secret is set and the public key
ships from Build 48 on, so Build 48 is the first signed release. The steps below are the history of that first
setup. They are **not** a rotation procedure: replacing the key would make every enforcing server (r48 on) refuse
the next release; a rotation goes through the additional-key path in the section above, and needs owner approval.

1. Generate the pair offline; the private key never enters the repository:
   ```bash
   python3 scripts/release_signing_key.py --install-pub
   ```
   It needs OpenSSL 3+ (macOS's LibreSSL lacks `pkeyutl -rawin`; Homebrew's is found
   automatically), writes the pair to `~/.sourbycraft/release-signing/` (key mode 0600, refuses a
   path inside the repository and refuses to overwrite an existing key), signs and verifies a probe
   with the same `openssl pkeyutl -rawin` commands as the CI step below, prints the SHA-256
   fingerprint of the public key, copies the public key to the resource in step 2, and prints the
   `gh secret set` command for step 3. It uploads nothing. By hand, the equivalent is
   `openssl genpkey -algorithm Ed25519 -out sourbycraft-release.key` then
   `openssl pkey -in sourbycraft-release.key -pubout -out sourbycraft-release.pub`.
2. Add the **public** key as `sourbycraft-server/src/main/resources/META-INF/sourbycraft-release.pub`
   (PEM or bare base64 both parse) and update the test above to assert the opposite (done: the test is now
   `thisBuildShipsTheReleaseKeySoTheVerifierIsActive`).
3. Store the PEM private key as the GitHub Actions secret `SOURBYCRAFT_RELEASE_SIGNING_KEY`.
4. Nothing to add to the workflow any more: `.github/workflows/build.yml` (release job) already has
   `Check release signing setup` and `Sign release jar and manifest (Ed25519)` (2026-10-20). The
   signing step runs only when the `SOURBYCRAFT_RELEASE_SIGNING_KEY` secret exists (a secret cannot
   be named in a step `if:`, so the job turns its presence into `env.HAS_RELEASE_SIGNING_KEY`), and the
   check step fails the release when exactly one of {secret, public-key resource} exists. So the key,
   the jar signature and the manifest signature can only ship together, in the same release: a key
   without signing would publish an unsigned release that enforcing servers refuse, a secret without
   the key would sign for no server. Until the owner adds both, releases publish exactly as before.

The signing step signs the jar and `sourbycraft-release-manifest.json` with
`openssl pkeyutl -sign -rawin`, verifies each with `openssl pkeyutl -verify -rawin -pubin` against the
resource in the tree (since PR #62 against every PEM block in it, passing when any block verifies; `openssl`
reads only the first block of a file, so the step splits them), checks each signature is 64 bytes, and deletes
the key file on exit. `Create GitHub release` then uploads `<jar>.sig`, `sourbycraft-release-manifest.json` and
`sourbycraft-release-manifest.json.sig` next to the jar, `.sha256` and the SBOM. The asset names
must be exactly these; `SourbyUpdater.parseRelease` matches on them. The step has run for every publish since
r48 (see the start of this section); the per-block verification of PR #62 has not run on a runner yet.

## Signed release manifest: staged rollout and yank list (2026-10-20)

Code: `update/ReleaseManifest.java`, `update/RolloutBucket.java`, `SourbyUpdater.selectByManifest`;
writer `scripts/release_manifest.py`; yank list `build-data/yanked-releases.txt`; rollout
`rolloutPercent` in `gradle.properties` (default 100).

**Activation: the same rule as SEC-2.** Enforced iff the build ships
`META-INF/sourbycraft-release.pub` (`ReleaseSignatureVerifier.isActive()`). Without a key the updater
never fetches a manifest and behaves exactly as before (`SourbyUpdaterManifestTest.withoutAKeyTheManifestIsNeverFetched`).
An unsigned manifest proves nothing, and honouring one would let anyone able to edit release assets
hold updates back.

**Format** — one JSON asset per release, `sourbycraft-release-manifest.json`, plus its detached
Ed25519 signature `sourbycraft-release-manifest.json.sig` (64 raw bytes or base64, same key and
formats as the jar signature). The signature covers the exact bytes, so there is no canonical-JSON
step: what was signed is what is parsed.

```json
{
  "schema": 1,
  "tag": "v26.2-r48",
  "asset": "SourbyCraft-26.2-REL.jar",
  "sha256": "<hex SHA-256 of that jar asset>",
  "rollout_percent": 25,
  "yanked": ["v26.2-r47"],
  "policy_revision": 1792454400,
  "issued_at": "2026-10-20T00:00:00Z",
  "expires_at": "2027-01-18T00:00:00Z"
}
```

Parsing is strict (`ReleaseManifest.parse`): `schema` must be 1 (a newer format is not read with old
rules), `tag`/`asset` non-blank strings, `sha256` 64 hex, `rollout_percent` an integer 0..100,
`yanked` an array of non-blank strings (at most 4096), `policy_revision` a positive integer,
`expires_at` an ISO-8601 instant; at most 64 KiB, UTF-8, **strict JSON with no duplicate member name
in any object**, compared after escape decoding (`"rollout\u005fpercent"` duplicates
`"rollout_percent"`) — a signed but ambiguous document is refused instead of resolved by "last one
wins" (`ReleaseManifest.rejectAmbiguousJson`; `release_manifest.py` uses an `object_pairs_hook` with
the same rule). Unknown fields are ignored. `tag`, `asset` and `sha256` must equal the release's tag, its jar asset name and that
asset's published digest (`requireBoundTo`), so a validly signed manifest of another release, or of
another build of this release, is refused.

**Selection** (only with a key; runs before the announcement, so a yanked or deferred release is
neither announced nor staged): the releases on this channel that are newer than the running build
are inspected newest first — since PR #62 first those for a newer Minecraft version (only their yanks and the
newest announceable one matter; they are never selected), then those for the running one, at most
`MAX_MANIFEST_CANDIDATES = 5` of each group — **including releases whose jar
asset is missing** (or prereleases this server ignores), because their signed yanks must still count:
deleting r49's jar must not hide that r49's manifest yanks r48 (`deletingTheJarOfTheYankingReleaseDoesNotHideItsYank`).
For each, the manifest and its signature are downloaded (bounded), verified, parsed and bound (tag
always; asset name and digest when there is a jar). A missing or unverifiable manifest stops the
check (`MANIFEST_REJECTED`). The yank set is the persisted yanks plus every manifest read so far, so
the newest release's manifest can yank older ones. Then, per candidate: yanked → skipped (WARN,
never staged); no installable jar → skipped (its yanks counted); stale (below) → skipped; outside
the rollout → skipped; otherwise it is the update. Outcomes when nothing is chosen, in this order:
`MANIFEST_REJECTED` (a candidate was stale), `ROLLOUT_DEFERRED`, `NO_RELEASE` (only jarless
candidates were left), `YANKED`. After a selection the existing gates (applied tag, Minecraft
version, size, digest, jar signature, launcher) run unchanged.

**Freshness and persisted policy** (`ReleasePolicyState`, `auto_update/release-policy.json`). Without
it, anyone able to edit release assets could put back an older signed manifest (empty yank list,
wider rollout) without the key. Each manifest carries `policy_revision` (the signer raises it every
time it signs a policy for a tag; `release_manifest.py` defaults to the signing time in epoch
seconds) and `expires_at` (default 90 days after signing). The server persists, per tag, the highest
revision it accepted, and every yank any authentic manifest ever carried. A manifest is **fresh**
when it has not expired and its revision is at least the stored one; only a fresh manifest can make
its release installable (and only a fresh one raises the stored revision). Every authentic manifest —
fresh, expired or rolled back — adds its yanks, since yanks only restrict. Persisted yanks keep
applying after the yanking release is deleted. The state is saved after every policy pass; if it
cannot be saved nothing is installed. Saves are serialised, re-read and merge (max revision, union
of yanks) and go through a unique temporary file plus an atomic move, so concurrent checks cannot
lower each other's knowledge.

- **State file missing** (first enforcing check, or the operator deleted it): start empty — trust
  on first use. There is nothing earlier to protect, refusing would block every fresh install
  forever, and the replay window for a new server is bounded by `expires_at`. Deleting it needs
  write access to the server directory, which already allows replacing the jar.
- **State file corrupt** (unparseable, wrong schema/shape, non-positive revision, over 4096
  entries): fail closed with `MANIFEST_REJECTED` before any manifest download, ERROR naming the file.
  Reading it as empty would silently forget yanks and revisions, which is exactly what a replay
  needs; deleting the file is the operator's explicit reset to trust on first use.

Operational consequence of expiry: a release whose manifest has expired can no longer be installed
by a server that is behind (`MANIFEST_REJECTED` until re-signed). Servers already on it are not
affected (only newer releases are inspected). Re-sign with a new revision before `expires_at` (next
paragraph) for any release that should stay installable.

**Rollout bucketing** (`RolloutBucket`): a 128-bit random id is written once to
`auto_update/rollout.id` (first use is race-free: callers are serialised, the candidate goes to a
unique temporary file and is published with a hard link that fails if the file exists, falling back
to a move without replace; every caller returns the id re-read from disk). It is read locally only — never logged, sent, or derived from host, IP,
world or players; deleting the file re-buckets the server. `bucket = SHA-256("sourbycraft-rollout:v1:"
+ id + ":" + tag)`, first 8 bytes unsigned, mod 100. The server takes the release iff `bucket <
rollout_percent`: 0 reaches nobody, 100 everybody, and raising the percentage for one tag only adds
servers. Because the tag is in the hash, the first wave differs between releases. A server whose id
cannot be read or written gets bucket 99 (only full rollouts reach it).

**Missing manifest, with a key present: fail closed (`MANIFEST_REJECTED`).** Every release made
after the key exists gets its manifest from the same CI step that signs the jar, so a signed
release without one is an anomaly. If "missing" meant "100 %, nothing yanked", deleting the asset —
by mistake or by anyone with write access to release assets — would turn a yanked release back on
and bypass a staged rollout; the manifest exists precisely to prevent that. A broken newest manifest
also stops the check instead of falling through to an older release, because the unreadable manifest
may be the one that yanks it. The cost of a wrong refusal is a WARN per check and a delayed update.
A release published before the key existed has no `.sig` either and is already refused by SEC-2.

**Changing rollout or yanking after publication** (owner, offline): write a new manifest for that
release with `scripts/release_manifest.py generate --tag <tag> --jar <the release jar> --rollout <N>`
(a later signing time gives a higher `policy_revision`; `--revision`/`--valid-days` override),
sign it with `openssl pkeyutl -sign -rawin -inkey <key> -in sourbycraft-release-manifest.json -out
sourbycraft-release-manifest.json.sig`, then `gh release upload <tag> sourbycraft-release-manifest.json
sourbycraft-release-manifest.json.sig --clobber`. To yank the newest release, list its own tag in
`build-data/yanked-releases.txt` and re-sign its manifest the same way; future releases carry the
whole list automatically. Between the two uploads a server can read a new manifest with the old
signature: that check returns `MANIFEST_REJECTED` and the next one succeeds.

**Limits, stated plainly:** replay protection is per server and starts at its first enforcing check:
a server that never saw the newer policy can be served an older, unexpired signed manifest (bounded
by `expires_at`), and deleting `release-policy.json` resets it to trust on first use. Revisions are
per tag; a newer release's policy does not lower an older tag's stored revision. Expired manifests
need a re-sign (no CI job re-signs them on a schedule). A running build that is itself yanked is not
told so. `/update apply` does not bypass the rollout. Rollout and yank are evaluated per check, so a
staged-but-not-applied jar is not withdrawn by a later yank. Releases from r48 on publish a signed manifest
(the signing step writes it); the selection path has not been exercised by the owner against them (PM-112).

Tests: `ReleaseManifestTest` (7: well-formed, 15 malformed shapes + non-UTF-8 + oversize, 0/100
bounds, binding, the exact `release_manifest.py` output layout, revision/expiry, duplicate and
escaped-duplicate names), `RolloutBucketTest` (7: id persistence, fallback, determinism/spread,
boundary `bucket == percent` excluded / `bucket + 1` included, ~25 % of 10 000 ids, 20 rounds of 8
concurrent first uses agreeing on the persisted id, an existing id never replaced),
`ReleasePolicyStateTest` (3: round trip, merge of stale concurrent saves, corrupt shapes refused),
`SourbyUpdaterManifestTest` (25: the 17 earlier scenarios plus jarless yanking release, jarless
release without manifest, yank persisting after the yanking release is deleted, replayed older
revision, expired manifest (yanks still count), stale newest falls back to an older fresh one,
missing state = first use / corrupt state = fail closed, no key = no state written),
`scripts/test_release_manifest.py` (8). The two SEC-2 tests in `SourbyUpdaterCheckTest` now also
script a valid manifest, since a key makes the manifest mandatory.

## `/update` and command blocks (OBS-8)

The check runs off-thread; its result is delivered with `SourbyReply.run(sender, ...)`, the
Folia-correct hop for the sender (a command block's reply must run on its region thread).
`UpdateCommand` has an injectable constructor for the updater, executor and hop;
`UpdateCommandReplyTest` proves a `BlockCommandSender` receives the result only through the hop.

## Evidence for this wave

`./gradlew -PincludeTestPlugin=true :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.update.*' --tests '*UpdateCommand*'`
on 2026-10-20: `ReleaseSignatureVerifierTest` 6/6, `SourbyUpdaterCheckTest` 14/14,
`UpdateRollbackGuardTest` 8/8, `UpdateApplierRestartPlanTest` 3/3, `UpdateCommandReplyTest` 2/2,
`SourbyUpdaterLifecycleTest` 1/1 (pre-existing). Three `*TestSuite` wrapper classes (`entity`, `perf`,
`spark`) report `NoTestsDiscoveredException` under this `--tests` filter because the filter
excludes their members; this change does not touch them and the unfiltered run was not repeated
here. No server boot was performed.

**NEEDS RETEST (2026-10-25):** the results above are for the 2026-10-20 tree. PR #62 changed `SourbyUpdater`,
`UpdateApplier`, `UpdateRollbackGuard`, `ReleaseSignatureVerifier`, `AutoUpdateSettings`, `UpdateOverrides` and
`UpdateCommand` and adjusted `UpdateApplierRestartPlanTest` and `UpdateApplierSwapTest`; the update suite has not
been run on that code. The owner scenarios for each change (preflight refusal, atomic swap under `kill -9`, two
concurrent `/update apply`, cross-Minecraft listing, asset refusals, Docker recreate and `applied.tag`, held stop
during the first boot, pre-49 rollback) are in the [PM matrix](../releases/BUILD-50-PM-MATRIX.md) rows PM-034,
PM-035, PM-081, PM-083, PM-109, PM-215, PM-229 and PM-254.

## Via plugin trust policy (SEC-6)

The asynchronous Via check recovers a missing reviewed bootstrap pin; it does not track
the latest upstream release. Installed Via jars, including renamed operator builds, skip
all metadata and artifact requests. If a jar is absent, the check only accepts stable
metadata explicitly listing the running Minecraft version and a Bukkit-family loader.
It may install only the reviewed URL,
filename, size and SHA-256 from `PluginProvisioner.viaPins()`. An API-provided hash
cannot replace that trust anchor. New upstream versions require a reviewed pin update;
there is no fallback to versions listing other Minecraft releases. If the reviewed pin
does not explicitly list the running Minecraft version, runtime recovery installs nothing.

Existing Via jars, including renamed operator builds and conflicting destination files,
are preserved; the updater deletes no installed plugin jar. An old provisioned version
also remains until the operator replaces it. Downloads stop at the pinned size (with a
32 MiB hard ceiling), use private temporary files and install without replacement only
after SHA-256 validation. Metadata is bounded to 4 MiB. The active `--plugins` directory
is used, and SourbyClip offline mode prevents the check. The enable flag remains LIVE;
new jar contents take effect on restart. Startup provisioning retains its separately
reviewed behavior; metadata gating here is the runtime updater policy.

`plugins.viaversion.auto-provision` is read strictly here (PR #62, `ViaAutoUpdate.provisioningAllowed`): absent
means on, only a YAML boolean `true` enables the download; a quoted or misspelt value disables it, as it skips
provisioning at boot ([PROTOCOLLIB.md](PROTOCOLLIB.md)). The check runs only on the updater's schedule (see the
settings table).
