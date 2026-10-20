# Auto-updater (`misc.auto_update.*`)

State as of 2026-10-20 (security wave SEC-2, SEC-3, SEC-9, SEC-10; OBS-8 for `/update`).
Code: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/update/`, command
`command/UpdateCommand.java`. Every claim below names the class that implements it; the test
column names the regression test that pins it.

## What it does

`SourbyUpdater` polls `https://api.github.com/repos/<repo>/releases` for a newer release **on the
same channel** (REL/DEV/EXP, from this build's version suffix) and acts according to
`apply_mode`:

| `apply_mode` | Behaviour |
|---|---|
| `notify` (**default** since this change; was `auto`) | log + operator banner only |
| `stage` | download, verify, place in `auto_update/sourbycraft/`, write `auto_update/core.path` |
| `auto` | `stage`, then swap the launch jar and restart after `restart_delay_seconds` |
| `off` | no checks scheduled |

`/update` runs one check with the configured mode, `/update apply` forces one `auto` run,
`/update status` prints config, markers and the rollback-guard state, `/update resume` lifts a
rollback hold.

**Existing installs keep their stored value.** The TOML seed only writes keys that are absent
(`SourbyCraftConfig.seed`), so a file created under the old default still says `apply_mode = "auto"`
until the operator changes it. New files get `notify`.

## Settings and reloadability

Verified against the consumers: `/sourbycraft reload` → `SourbyCraftConfig.applyLiveConfig` →
`AutoUpdateSettings.loadFromToml()` refreshes the static fields; `SourbyUpdater.check()` reads
them on every check; `SourbyUpdater.start()` builds the schedule once at boot and is not re-run
by reload. A key that is missing, blank or of the wrong type reads as the default in the table
below, not as the previous live value: deleting `apply_mode` and reloading gives `notify`.

| Key | Default | Reloadability |
|---|---|---|
| `enabled` | `true` | RESTART_REQUIRED (decides whether `start()` runs) |
| `repo` | `YanIanZ/SourbyCraft` | LIVE (next check) |
| `channel` | `""` = from the build version | LIVE (next check) |
| `apply_mode` | `notify` | LIVE between `notify`/`stage`/`auto` (next check); switching from or to `off` is RESTART_REQUIRED because `off` skips the schedule in `start()` |
| `check_times`, `check_interval_minutes` | `["06:00"]`, `30` | RESTART_REQUIRED (schedule built in `start()`) |
| `restart_mode`, `restart_delay_seconds` | `auto`, `60` | LIVE (read when an apply is scheduled) |
| `allow_prerelease` | `false` | LIVE (next check) |
| `via_auto_update` | `true` | LIVE: recover missing reviewed Via pins; installed jars require restart |

## Verification chain before a jar is staged

In order, `SourbyUpdater.downloadAndStage` / `verifyOrThrow`:

1. **Bound before reading** (SEC-10). The bound is the asset's declared `size`, else
   `MAX_JAR_BYTES` (512 MiB). A `Content-Length` above the bound is refused without reading the
   body; a stream that runs past the bound is cut off and the `.part` file deleted
   (`copyBounded`). Test: `SourbyUpdaterCheckTest.oversizeContentLengthIsRefusedBeforeAnyByteIsRead`,
   `aStreamRunningPastTheDeclaredSizeIsCutOffAndDeleted`, `copyBoundedStopsExactlyPastTheLimit`.
2. Non-empty, equals the declared size.
3. **SHA-256 equals the GitHub asset `digest`** — mandatory; a release without a digest is refused.
4. Opens as a ZIP.
5. **Ed25519 signature** — only when this build ships a public key (next section).

A failing download is deleted and never staged (`Outcome.STAGE_FAILED`).

## Same-Minecraft-version gate (SEC-3)

A newer release whose tag core differs from the running Minecraft version (`v26.3-r50` on a 26.2
server) is **announced but never staged or applied**: `Outcome.MC_VERSION_MISMATCH`. The running
version is `BuildInfo.mcVersion()` (stamped from `gradle.properties` `mcVersion`), falling back to
the core of the build version (`26.2-REL` → `26.2`). A build that can say neither ("unknown", e.g. a
bare dev classpath) stages nothing. Test: `aReleaseForAnotherMinecraftVersionIsAnnouncedButNeverStaged`,
`minecraftVersionComesFromTheBuildStampOrTheVersionCore`.

## Previous jar and rollback (SEC-3) — `UpdateRollbackGuard`

Files, all next to the server: `<launch jar>.prev`, `auto_update/rollback.state` (a properties
file with `phase`, `tag`, `launch`, `prev`, `applied_at`, `note`), plus the pre-existing
`auto_update/applied.tag`.

Sequence for `apply_mode=auto` (or `/update apply`):

1. **Apply** (`UpdateApplier.applyNow`): copy the launch jar to `<jar>.prev` (via a `.part` file,
   then an atomic rename). If that copy fails, the swap and the restart do **not** happen — an
   update without a way back is not applied automatically. Then the staged jar is renamed over
   the launch jar, `applied.tag` is written and the guard records `phase=APPLIED`.
2. **First boot on the new jar**: the `auto updater` stage of `SourbyCraftBootstrap.init()` (runs
   in `DedicatedServer.initServer`, before worlds load) calls `guard.onBoot()`, which sees
   `APPLIED`, records `phase=BOOTING` and registers a `ServerLoadEvent` listener. **Ready** is
   `ServerLoadEvent STARTUP`: worlds loaded and plugins enabled, fired at the end of world loading
   immediately before `acceptConnections()` and shortly before the `Done (` line
   (`MinecraftServer.java` line ~665 in the materialized source). Reaching it records `phase=OK`.
3. **Boot that finds `BOOTING`**: the previous boot after the apply ended before ready. The guard
   copies `.prev` back over the launch jar (copy, not move; `.prev` stays), records
   `phase=ROLLED_BACK` and the updater is **held**: every `check()` returns `Outcome.HELD` without
   touching the network, and the boot log prints a framed `UPDATE ROLLED BACK` block. If `.prev`
   is missing or the copy fails: `phase=RESTORE_FAILED`, also held, with a `ROLLBACK FAILED` block.
4. **Every later boot** while held prints `AUTO-UPDATER HELD` and does nothing else.

The guard boot step runs even when `enabled=false` or `apply_mode=off`
(`AutoUpdateSettings.startUpdater()` calls it first).

Operator actions: `/update resume` (or delete `auto_update/rollback.state`) lifts the hold.
`applied.tag` is left alone so the rolled-back tag is not offered again; delete it too to retry
that exact release. Tests: `UpdateRollbackGuardTest` (eight scenarios, one `onBoot()` per
simulated start), `SourbyUpdaterCheckTest.aRollbackHoldShortCircuitsTheCheckWithoutTouchingTheNetwork`.

**Limits, stated plainly:**

- A running JVM cannot replace its own jar. The restore rewrites the file on disk; the boot that
  performs it is still running the updated bytes, and the restored build runs from the boot
  after that. With a panel that restarts on exit this costs one extra cycle.
- "Did not reach ready" is inferred from the phase file alone. A boot the operator stopped
  before ready, a host kill, or a crash unrelated to the update all look the same and trigger
  the restore. It is conservative, not a diagnosis; if the restored-from boot comes up fine the
  operator can `/update resume`, delete `applied.tag` and re-apply.
- A crash before the SourbyCraft bootstrap stage (launcher, library download, class loading)
  leaves `APPLIED`; the next boot treats itself as the first one after the apply, so the restore
  happens one boot later than ideal.
- The in-server swap is a POSIX rename. On Windows the rename fails on the file lock and the old
  `core.path` fallback path is left as before; the guard records nothing in that case.
- Not boot-tested in this wave (agents may not start a server). The state machine is unit-tested;
  the Bukkit glue (`ServerLoadEvent` registration, the loud log blocks) compiled and is read-verified
  against `MinecraftServer.java` only.

## Restart modes (SEC-9)

`restart_mode`: `auto` = `exit` inside a Pterodactyl/Pelican container (`P_SERVER_UUID`), else
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
`META-INF/sourbycraft-release.pub`. **This repository ships no key and CI signs nothing**, so on
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
4. Add the signing step to `.github/workflows/build.yml` before `Create GitHub release` and
   upload the `.sig` next to the jar (the workflow is owned by another party in this wave; the
   step text is below). Release one build with the key in the jar **before** the first signed
   release is required, otherwise older servers that already enforce would refuse an unsigned
   release — in practice: ship the key and the signing step in the same release.

Step text for `build.yml` (same `if:` as the publish step):

```yaml
      - name: Sign release jar (Ed25519)
        if: (github.event_name == 'push' || github.event_name == 'workflow_dispatch') && github.ref == 'refs/heads/release/26.2-canvas'
        env:
          SOURBYCRAFT_RELEASE_SIGNING_KEY: ${{ secrets.SOURBYCRAFT_RELEASE_SIGNING_KEY }}
        run: |
          set -eu
          test -n "$SOURBYCRAFT_RELEASE_SIGNING_KEY" || { echo "SOURBYCRAFT_RELEASE_SIGNING_KEY secret is not set" >&2; exit 1; }
          umask 077
          printf '%s\n' "$SOURBYCRAFT_RELEASE_SIGNING_KEY" > "$RUNNER_TEMP/release.key"
          jar=release/SourbyCraft-26.2-${{ steps.relnum.outputs.channel }}.jar
          openssl pkeyutl -sign -rawin -inkey "$RUNNER_TEMP/release.key" -in "$jar" -out "$jar.sig"
          openssl pkeyutl -verify -rawin -pubin \
            -inkey sourbycraft-server/src/main/resources/META-INF/sourbycraft-release.pub \
            -in "$jar" -sigfile "$jar.sig"
          rm -f "$RUNNER_TEMP/release.key"
          test "$(wc -c < "$jar.sig")" -eq 64
```

and in `Create GitHub release`, append `"$jar.sig"` to the `gh release create ... "$jar" "$jar.sha256"`
argument list. The asset name must be exactly `<jar name>.sig`; `SourbyUpdater.parseRelease`
matches on that.

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
