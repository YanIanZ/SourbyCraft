# Auto-updater (`[updater]` in `sourbycraft_config/sourbycraft_global_config.toml`)

Key names below are the Build 49 ones (`updater.*`, kebab-case). The pre-49 names (`misc.auto_update.enabled`,
`repo`, `apply_mode`, `check_times`, `check_interval_minutes`, `restart_mode`, `restart_delay_seconds`,
`allow_prerelease`, `via_auto_update`) are rewritten once at boot by `config/ConfigMigration` (copy kept as
`*.migrated`) and still read until Build 50 with one WARN each.

State as of 2026-10-20 (security wave SEC-2, SEC-3, SEC-9, SEC-10; OBS-8 for `/update`).
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
| `stage` | download, verify, place in `auto_update/sourbycraft/`, write `auto_update/core.path` |
| `auto` | `stage`, then swap the launch jar and restart after `restart-delay-seconds` |
| `off` | no checks scheduled |

`/update` runs one check with the configured mode, `/update apply` forces one `auto` run,
`/update status` prints config, markers and the rollback-guard state, `/update resume` lifts a
rollback hold.

**Existing installs keep their stored value.** The TOML seed only writes keys that are absent
(`SourbyCraftConfig.seed`), so a file created under the old default still says `apply-mode = "auto"`
until the operator changes it. New files get `notify`.

## Settings and reloadability

Verified against the consumers: `/sourbycraft reload` → `SourbyCraftConfig.applyLiveConfig` →
`AutoUpdateSettings.loadFromToml()` refreshes the static fields; `SourbyUpdater.check()` reads
them on every check; `SourbyUpdater.start()` builds the schedule once at boot and is not re-run
by reload. A key that is missing, blank or of the wrong type reads as the default in the table
below, not as the previous live value: deleting `apply-mode` and reloading gives `notify`.

| Key | Default | Reloadability |
|---|---|---|
| `enabled` | `true` | RESTART_REQUIRED (decides whether `start()` runs) |
| `repository` | `YanIanZ/SourbyCraft` | LIVE (next check) |
| `channel` | `""` = from the build version | LIVE (next check) |
| `apply-mode` | `notify` | LIVE between `notify`/`stage`/`auto` (next check); switching from or to `off` is RESTART_REQUIRED because `off` skips the schedule in `start()` |
| `check-times`, `check-interval-minutes` | `["06:00"]`, `30` | RESTART_REQUIRED (schedule built in `start()`); `/update status` prints the interval the running schedule uses, plus `(pending restart: Nm)` when a reload changed it |
| `restart-mode`, `restart-delay-seconds` | `auto`, `60` | LIVE (read when an apply is scheduled) |
| `allow-prerelease` | `false` | LIVE (next check) |
| `via-auto-update` | `true` | LIVE: recover missing reviewed Via pins; installed jars require restart |

## Verification chain before a jar is staged

Before any download, a build that ships the release key first picks the release through the signed
release manifest (yank list + staged rollout; section below). Then, in order,
`SourbyUpdater.downloadAndStage` / `verifyOrThrow`:

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

Both were read from `build/libs/SourbyCraft-slim.jar` (clip 3.1.2) on 2026-10-20 and match
`clipVersion=3.1.2` in `gradle.properties`. The rules, all in code constants:

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

A newer release whose tag core differs from the running Minecraft version (`v26.3-r50` on a 26.2
server) is **announced but never staged or applied**: `Outcome.MC_VERSION_MISMATCH`. The running
version is `BuildInfo.mcVersion()` (stamped from `gradle.properties` `mcVersion`), falling back to
the core of the build version (`26.2-REL` → `26.2`). A build that can say neither ("unknown", e.g. a
bare dev classpath) stages nothing. Test: `aReleaseForAnotherMinecraftVersionIsAnnouncedButNeverStaged`,
`minecraftVersionComesFromTheBuildStampOrTheVersionCore`.

## Previous jar and rollback (SEC-3) — `UpdateRollbackGuard`

Files, all next to the server: `<launch jar>.prev`, `auto_update/rollback.state` (a properties
file with `phase`, `tag`, `launch`, `prev`, `applied_at`, `note`, and optional `prev_sha256`), plus the pre-existing
`auto_update/applied.tag`.

Sequence for `apply-mode=auto` (or `/update apply`):

1. **Apply** (`UpdateApplier.applyNow`): copy the launch jar to `<jar>.prev` (via a `.part` file,
   then an atomic rename). If that copy fails, the swap and the restart do **not** happen — an
   update without a way back is not applied automatically. Then the staged jar is renamed over
   the launch jar, `applied.tag` is written and the guard records `phase=APPLIED` with
   `prev_sha256`: the streamed SHA-256 of the completed `.prev`, stored as 64 lowercase hex
   characters. Copy, hash and state write run on the updater's Bukkit async scheduler callback
   (the updater has no private executor), away from region/tick/main-server threads. The hash is
   preserved on every later rewrite, including `BOOTING`, `OK`, `ROLLED_BACK` and `RESTORE_FAILED`.
   This state contract applies to future applies; existing states without a hash keep their legacy behavior.
2. **First boot on the new jar**: the `auto updater` stage of `SourbyCraftBootstrap.init()` (runs
   in `DedicatedServer.initServer`, before worlds load) calls `guard.onBoot()`, which sees
   `APPLIED`, records `phase=BOOTING` and registers a `ServerLoadEvent` listener. **Ready** is
   `ServerLoadEvent STARTUP`: worlds loaded and plugins enabled, fired at the end of world loading
   immediately before `acceptConnections()` and shortly before the `Done (` line
   (`MinecraftServer.java` line ~665 in the materialized source). Reaching it records `phase=OK`.
3. **Boot that finds `BOOTING`**: the previous boot after the apply ended before ready. The guard
   first hashes `.prev` when `prev_sha256` is present. A mismatch (including an invalid/empty
   recorded hash) refuses restore, logs an ERROR naming `.prev`, expected and actual hashes,
   records `RESTORE_FAILED`, and leaves every jar file untouched. Comparison accepts uppercase
   hex; every state write normalizes it to lowercase. With no field, the server keeps its prior
   copy behavior. This restore validation runs during the existing server boot guard, before
   worlds/ticking; it is distinct from async apply hashing. With a matching hash, the guard
   copies `.prev` back over the launch jar (copy, not move; `.prev` stays), records
   `phase=ROLLED_BACK` and the updater is **held**: every `check()` returns `Outcome.HELD` without
   touching the network, and the boot log prints a framed `UPDATE ROLLED BACK` block. If `.prev`
   is missing or the copy fails: `phase=RESTORE_FAILED`, also held, with a `ROLLBACK FAILED` block.
4. **Every later boot** while held prints `AUTO-UPDATER HELD` and does nothing else.

The guard boot step runs even when `enabled=false` or `apply-mode=off`
(`AutoUpdateSettings.startUpdater()` calls it first).

Operator actions: `/update resume` (or delete `auto_update/rollback.state`) lifts the hold.
`applied.tag` is left alone so the rolled-back tag is not offered again; delete it too to retry
that exact release. Tests: `UpdateRollbackGuardTest` (one `onBoot()` per
simulated start), `SourbyUpdaterCheckTest.aRollbackHoldShortCircuitsTheCheckWithoutTouchingTheNetwork`.

**Launcher-side recovery — RESTART_REQUIRED, version-gated (not shipped by the current pin).**
`gradle.properties` currently pins `clipVersion=3.1.2`, below 3.1.4. SourbyClip 3.1.4's
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
- A jar that never reaches the SourbyCraft bootstrap `auto updater` stage (launcher, library
  download, class loading, early JVM abort) is **not restored automatically by the currently pinned launcher/server path**: the server restore code
  is inside the jar that is booted, so every later boot dies the same way and `rollback.state`
  stays `APPLIED`. Shown on a real boot with a release whose `Main-Class` exits 4 (fixture
  scenario `dead-launcher`, 2026-10-22); the operator copies `<jar>.prev` over the launch jar by
  hand. The launcher metadata gate (SEC-4) does not catch it: it reads declared metadata only.
- The in-server swap is a POSIX rename. On Windows the rename fails on the file lock and the old
  `core.path` fallback path is left as before; the guard records nothing in that case.
- Boot-tested locally on 2026-10-22 (apply → failed boot → restore → held, and the healthy apply
  that is confirmed and kept) with the fixture in
  [updater-rollback-fixture-2026-10-22.md](updater-rollback-fixture-2026-10-22.md): agent test server,
  fake release server on loopback, throwaway signing key, no clients. Not run on a panel, on Linux
  or Windows, against the real GitHub or with the real release key. The boot that performs the
  restore still runs the new bytes, so a rollback costs two failed boots (observed: boots 2 and 3
  of the fixture), not one.

## Local fixture overrides (test only)

Two JVM system properties exist for `scripts/updater_rollback_fixture.py` and
`agent_testserver.py --auto-update`. Both are unset by default, are not config keys and cannot be
set from the TOML; only whoever starts the JVM can use them. Class: `update/UpdateOverrides`.

| Property | Effect |
|---|---|
| `sourbycraft.update.api-base=http://127.0.0.1:PORT` | the releases listing comes from `<base>/repos/<repo>/releases` instead of `https://api.github.com`. Only `http`/`https` on `127.0.0.1`, `localhost` or `[::1]`, no user info, path, query or fragment; a value that is set but refused makes `check()` return `DISABLED` and log an ERROR, it never falls back to GitHub. Every check logs a WARN while it is active. |
| `sourbycraft.update.release-key-file=FILE` | PEM/base64 Ed25519 public key; turns jar-signature **and** signed-manifest enforcement on for a build that ships no `META-INF/sourbycraft-release.pub` (log: `release signatures ENFORCED (TEST key …)`). Ignored with an ERROR line when the build ships its own key, so it can only add enforcement. An unreadable, oversize (> 16 KiB) or unparseable file keeps enforcement on and failing closed. |

Tests: `UpdateOverridesTest` (9). The harness refuses `--auto-update` unless both properties are given.
Since Build 48 builds ship the key, so the fixture needs a jar built without it (procedure in
[updater-rollback-fixture-2026-10-22.md](updater-rollback-fixture-2026-10-22.md#run-it)).

## Restart modes (SEC-9)

`restart-mode`: `auto` = `exit` inside a Pterodactyl/Pelican container (`P_SERVER_UUID`), else
`spigot`; `spigot` = `RestartCommand.restart()` (falls back to a clean stop); `stop` = clean stop;
`exit` = clean stop **and exit code 70**.

`exit` no longer registers a shutdown hook that calls `Runtime.halt(70)`. `UpdateApplier` sets
`MinecraftServer.abnormalExit = true` and calls `Bukkit.shutdown()`; the server's own path then
delivers the code: Folia's `RegionShutdownThread` → `stopServer()` (worlds, players, plugins,
region-file I/O flush, `AwfEngine.closeAll()`) → `onServerExit()` → `System.exit(abnormalExit ?
70 : 0)` (`DedicatedServer.java:550`). `abnormalExit` has no other reader in the materialized
source. Test: `UpdateApplierRestartPlanTest` pins the mode → plan mapping; the shutdown itself is
not unit-testable and was not boot-tested here.

## Polling etiquette (SEC-10)

The releases listing is a conditional request: the `ETag` of the last 200 is sent back as
`If-None-Match`; a `304` answers from the cached listing. A `429`, or a `403` carrying `Retry-After`
or `X-RateLimit-Remaining: 0` + `X-RateLimit-Reset`, arms a backoff window (clamped to 1 min – 6 h;
`429` without a hint waits 5 min) during which `check()` returns `Outcome.RATE_LIMITED` without a
request. Tests: `etagIsSentBackAndA304AnswersFromTheCachedListing`,
`rateLimitArmsABackoffWindowThatSkipsChecksWithoutRequests`, `retryAfterAcceptsSecondsAndHttpDatesAndIsClamped`.
Polling is still unauthenticated (60 requests/h per IP on GitHub); the default cadence (daily +
every 30 min) stays well under that.

## Signed releases (SEC-2) — implemented, **not enabled**

`ReleaseSignatureVerifier` (JDK `java.security`, Ed25519) is active **iff** the jar contains
`META-INF/sourbycraft-release.pub`. **This repository ships no key and CI signs nothing** (the release
job has the signing step since 2026-10-20, but it only runs once the secret exists), so on
every current build the verifier is inactive and the boot log says
`release signatures NOT enforced (no /META-INF/sourbycraft-release.pub in this build)`.
`ReleaseSignatureVerifierTest.thisBuildShipsNoKeySoTheVerifierIsInactive` fails the day a key
appears without this page changing.

When a key is present: the release must publish `<jar asset name>.sig` (the 64 raw bytes from
`openssl pkeyutl -sign -rawin`, or that value base64-encoded) and it must verify over the
downloaded bytes, after the SHA-256 check; otherwise `STAGE_FAILED`. A key resource that is present
but unparseable keeps enforcement on and failing closed. Tests: `ReleaseSignatureVerifierTest`
(key pair generated in the test), `SourbyUpdaterCheckTest.withAKeyInTheBuild*`,
`withoutAKeyTheSignatureAssetIsNeverEvenFetched`.

### Turning it on (owner actions, in this order)

**Status 2026-10-22:** done. The key pair was generated (fingerprint
`sha256:a9643e8b3dcdaae50d0acd5a93f4ef1fff593ee7c8bf26199ca32a0b7232f9c4`), the secret is set and the public key
ships from Build 48 on, so Build 48 is the first signed release. The steps below stay as the procedure for a key
rotation.

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
   (PEM or bare base64 both parse) and update the test above to assert the opposite.
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
resource in the tree, checks each signature is 64 bytes, and deletes the key file on exit.
`Create GitHub release` then uploads `<jar>.sig`, `sourbycraft-release-manifest.json` and
`sourbycraft-release-manifest.json.sig` next to the jar, `.sha256` and the SBOM. The asset names
must be exactly these; `SourbyUpdater.parseRelease` matches on them. **Not run yet**: no secret and
no public key exist, so the step has never executed on a runner.

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
are inspected newest first, at most `MAX_MANIFEST_CANDIDATES = 5` — **including releases whose jar
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
staged-but-not-applied jar is not withdrawn by a later yank. Not boot-tested; no real GitHub release
with a manifest exists yet.

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
