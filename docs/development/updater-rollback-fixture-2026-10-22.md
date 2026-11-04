# Auto-updater rollback fixture (apply → failed boot → `.prev` restore)

Status: **Active** (2026-10-22). Build 49 Security gate item: "`.prev` rollback exercised on a real boot
(apply → failed boot → restore)" ([26.2-build-49.md](../releases/26.2-build-49.md), runbook
[G11](../releases/runbooks/build-49-real-test.md#g11-security)). This page describes the local fixture that exercises
`SourbyUpdater` → `UpdateApplier` → `UpdateRollbackGuard` on real JVM boots, and the evidence from its first runs.

**Status 2026-10-25 (PR #62).** Two facts this page states as of 2026-10-22 have moved: release signing is enabled
since Build 48 (the real key ships in the jar and CI signs every release; see
[AUTO-UPDATER.md](AUTO-UPDATER.md#signed-releases-sec-2--enabled-since-build-48)), which is why the fixture needs the
keyless jar described under *Run it*; and the SourbyClip pin is 3.1.3 (below 3.1.4, so the launcher-restore
expectation stays skipped). PR #62 then changed the code this fixture drives (code written and statically reviewed;
not built; not tested by the owner): the apply preflights and copies, re-hashes and atomically renames the jar, a
failed apply no longer restarts, the rollback guard runs before every bootstrap stage, and `http` asset URLs are
accepted only on loopback while `sourbycraft.update.api-base` is set (the fixture's setup). The log lines the fixture
matches (`staged`, `applying ... (mode=exit)`, `swapped ... (previous build kept at ...)`, `restarting now
(plan=EXIT_70)`, `first boot after applying`, `reached ready; update confirmed`, `UPDATE ROLLED BACK`,
`AUTO-UPDATER HELD`, `already applied`) are unchanged in the code. **The runs below are NEEDS RETEST** on a build with
PR #62; their results stay as recorded for the 2026-10-22 tree.

**What this is and is not.** Functional evidence from an agent test server on a shared macOS laptop: the code path
runs end to end and logs each step. It is **not** a panel/Linux/Windows run, **not** the real GitHub release API,
**not** the real release signing key, and says nothing about performance. The gate stays OPEN for the parts it cannot
cover (see [Not covered](#not-covered)).

## How it is built

| Piece | What | Where |
| --- | --- | --- |
| Fake release server | `ThreadingHTTPServer` on `127.0.0.1:<random>` answering `GET /repos/YanIanZ/SourbyCraft/releases` (GitHub shape: `tag_name`, `assets[].browser_download_url/size/digest`) and `/assets/<tag>/<name>`. Records every request. | `scripts/updater_rollback_fixture.py` (`FakeReleaseServer`) |
| Endpoint override | JVM property `sourbycraft.update.api-base=http://127.0.0.1:PORT`. **Test-only, unset by default, not a config key.** Only `http(s)` on `127.0.0.1`/`localhost`/`[::1]`, no user info/path/query; a value that is set but refused makes the check do nothing (`Outcome.DISABLED`), it never falls back to GitHub. When active every check logs a WARN naming it. | `update/UpdateOverrides.java`, wired in `SourbyUpdater.check` |
| SEC-2 stays on | A throwaway Ed25519 pair is generated per run with OpenSSL 3 (`release_signing_key.generate`, refuses a path inside the repository, mode 0600). JVM property `sourbycraft.update.release-key-file=<public key>` turns signature **and** signed-manifest enforcement on for a build that ships no `META-INF/sourbycraft-release.pub`. It can only add enforcement: if the build ships its own key the property is ignored with an ERROR line. The private key is deleted when the run ends (`private_key_deleted: true` in `summary.json`); only the public key stays with the evidence. | `ReleaseSignatureVerifier.resolve`, `Signer` |
| Release assets | jar, `<jar>.sig`, `sourbycraft-release-manifest.json` (from `release_manifest.build`, rollout 100, no yanks, bound to the jar's SHA-256) and its `.sig`, signed with the `openssl pkeyutl -sign -rawin` call CI uses. The updater therefore runs its full chain: size bound, GitHub `digest`, ZIP, Ed25519 jar signature, manifest selection/binding/freshness, SourbyClip launcher gate. | `release_assets` |
| Jar variants | The slim jar is repacked entry by entry; the variants differ only by one entry, `META-INF/sourbycraft-fixture.properties` (`role`, `boot`, `exit-code`). The test plugin's `UpdaterFixtureProbe` (active only with `-Dsourby.testplugin.updater-fixture=true`) reads it from the **running** jar through the system class loader (the open inode, not the path), logs `SOURBY_UPDATER_FIXTURE jar=… role=… disk-sha256=… boot=…` and, for `boot=fail`, halts the JVM with the marker's exit code from `onLoad`: after the SourbyCraft boot stage that runs the rollback guard, before worlds load and before `ServerLoadEvent STARTUP`. | `test-plugin/.../UpdaterFixtureProbe.java` |
| Harness | `agent_testserver.py start --auto-update …` (refused unless the loopback endpoint and a key file are also given) and the new `restart --name N`, which boots a finished run **in its own directory** so the jar swapped by the updater and `auto_update/` carry over; every boot is archived under `boots/NN/`. | [AGENT-TEST-SERVER.md](AGENT-TEST-SERVER.md) |

Updater settings in the fixture: `apply_mode=auto`, `restart_mode=exit` (clean shutdown, exit code 70 like a panel
restart), `restart_delay_seconds=5`, `channel=REL` (the worktree build reports `DEV`), `check_interval_minutes=0`,
`via_auto_update=false`. The apply is triggered by `/update apply` (the 2-minute post-boot check would do the same).
Fake tag: `v26.2-r9001` (above any real build number).

## Run it

```sh
bash scripts/gradle_locked.sh slimServerJar
bash scripts/gradle_locked.sh -PincludeTestPlugin=true :test-plugin:jar
python3 scripts/updater_rollback_fixture.py run --scenario all --name myrun      # rollback + healthy + dead-launcher
python3 scripts/updater_rollback_fixture.py run --scenario rollback --name myrun # one scenario
```

Needs one free test-server slot, OpenSSL 3 (Homebrew on macOS) and JDK 25 (`javac` for the dead-launcher stub). A
refusal because both slots are busy, RAM is below 3 GiB or more than two Gradle builds run is waited out (20 s steps,
30 tries). Evidence goes to `/Volumes/Ext/Sourby/agent-testservers/_updater-fixture-<name>/<scenario>/`
(`summary.json` with every check, `evidence/boot-N.log` per boot, `jars/`, the public key); the harness run directories
are `<name>-<scenario>` next to it. Exit 0 only when every check passed.

**Jar without the shipped key.** Since Build 48 (`764ba622`) every build ships
`META-INF/sourbycraft-release.pub`; the shipped key alone decides and `sourbycraft.update.release-key-file` is
ignored with an ERROR, so a normal jar cannot verify the fixture's throwaway-key releases (the
`release signatures ENFORCED (TEST key` check fails). Build the fixture's input jar without the resource, in a
worktree, and never ship it:

```sh
mv sourbycraft-server/src/main/resources/META-INF/sourbycraft-release.pub /tmp/release.pub.bak
bash scripts/gradle_locked.sh -PincludeTestPlugin=true slimServerJar :test-plugin:jar
cp build/libs/SourbyCraft-slim.jar /tmp/SourbyCraft-slim-keyless.jar
mv /tmp/release.pub.bak sourbycraft-server/src/main/resources/META-INF/sourbycraft-release.pub
python3 scripts/updater_rollback_fixture.py run --scenario all --name myrun --jar /tmp/SourbyCraft-slim-keyless.jar
```

The verification path is the same code with a different key source; what the fixture cannot show is the shipped
key itself verifying a release (that needs a release signed with the real key).

Rerun 2026-10-22 on `26.2` after #35 (agent test server, keyless jar built as above, test plugin only, no clients,
shared laptop; runs `opus-integ-rb2-*`): rollback, healthy and dead-launcher all PASS (86 checks; dead-launcher
with the launcher-restore check skipped at the pinned SourbyClip 3.1.3, manual recovery checked instead).

## Current expectations (version gate; historical runs below are unchanged)

**LIVE state contract for new applies:** `rollback.state` records `prev_sha256` as the
64-character lowercase SHA-256 of the completed `.prev`, from the async updater apply callback.
The fixture checks it at `APPLIED` and its preservation at `OK`/`ROLLED_BACK`; the server also
preserves it at `BOOTING` and `RESTORE_FAILED`. A present hash must match before server restore;
a mismatch logs ERROR with file/expected/actual, refuses restore and leaves jar files untouched.
Missing hashes retain the server's old copy behavior; the 3.1.4 launcher instead requires a
`Main-Class` manifest fallback when no hash is supplied.

**RESTART_REQUIRED launcher capability:** the runtime gate reads `clipVersion` from this
worktree's `gradle.properties` each run. The pin is **3.1.3** since 2026-10-22 (this paragraph said 3.1.2 when it
was written); both are below 3.1.4. For <=3.1.3 the launcher-restore expectation is recorded as `[SKIP]` with a reason
in the check list (also `skipped: true` in JSON), while existing legacy/manual recovery checks
still run. For >=3.1.4, restore is required: boot 2 exits 4 leaving `APPLIED`, boot 3 invokes the
embedded launcher guard, restores `.prev`, records `ROLLED_BACK` with the launcher note and
retained hash, and exits **71** before server code. Boot 4 must reach ready on the original and
hold the updater; `/update apply` must make no release requests while held.

The dead-launcher stub now invokes the embedded `BootRollbackGuard` through reflection before
its deliberate exit. Only absence of that class is accepted for older clips; other guard errors
fail the boot/expectations. This exercises the guard in the candidate jar, without copying its
implementation into the fixture. It simulates failure **after** the launcher guard but before
any server code; replacing Main-Class without this call bypasses recovery even in 3.1.4.
For the `rollback` scenario, >=3.1.4 also expects boot 3 to restore and exit 71 without running
the broken test plugin; older pins retain the observed server-side restore/exit-3 sequence.
The pin gate assumes the supplied fixture jar was built with that pin; stale jars should fail
the required expectations. These new expectations have no new real-boot evidence yet.

## Scenarios and evidence (2026-10-22)

Jar: `slimServerJar` built in worktree `claude-rollback` (base `05c7230d` plus the main tree's uncommitted state at
creation, plus this change), SourbyClip 3.1.2, SHA-256 `7a3763989f19…`; test plugin `1fc80af7e7c5…`. Repacked
`original` variant `fc42d41c8110…`. Single local server, loopback, no clients.

### `rollback`: broken jar, restore on the next boot (32 checks, PASS, 60 s; run `claude-rollback-all-rollback`)

| Boot | Jar that is booted | What happened (log lines in `evidence/boot-N.log`) | `rollback.state` after |
| --- | --- | --- | --- |
| 1 | original | `release signatures ENFORCED (TEST key …)`; `/update apply` → `SHA-256 verified`, `Ed25519 release signature verified`, `SourbyClip 3.1.2 (protocol 1) … is compatible`, `staged v26.2-r9001`, `applying v26.2-r9001 — restart in 5s (mode=exit)`, `swapped <run>/SourbyCraft-slim.jar -> v26.2-r9001 (previous build kept at …/SourbyCraft-slim.jar.prev)`, `restarting now (plan=EXIT_70)`; **exit code 70** | `APPLIED` |
| 2 | broken (`6026174d88f1…`) | `first boot after applying v26.2-r9001; the previous jar is kept at …`, then the probe: `role=new-broken … boot=fail`, `SOURBY_UPDATER_FIXTURE_FAIL … exit=3`; **exit code 3, no `Done (`** | `BOOTING` |
| 3 | broken again (it is what is on disk) | the guard finds `BOOTING`, copies `.prev` back, logs the framed block `UPDATE ROLLED BACK: the boot after applying v26.2-r9001 did not reach ready.` / `THIS process still runs the updated jar … the restored previous build runs from the NEXT restart.` The probe shows the JVM still **running** `role=new-broken` while `disk-sha256` is already the original; exit 3 again | `ROLLED_BACK` |
| 4 | original (`fc42d41c8110…`, byte-identical to before the apply) | `AUTO-UPDATER HELD: update v26.2-r9001 was rolled back: the boot after applying it did not reach ready; SourbyCraft-slim.jar.prev was copied back over SourbyCraft-slim.jar`; **`Done (8.653s)!`** | `ROLLED_BACK` (held) |

Also checked on boot 4: `/update apply` while held makes **no** request to the release server and prints
`Updater is HELD after a rollback`; `/update resume` removes `rollback.state`; the next `/update apply` logs
`v26.2-r9001 already applied; nothing to do.` (the rolled-back tag is not re-offered because `applied.tag` stays);
`<jar>.prev` is still the original after the restore (copy, not move); the clean stop exits 0 with the lock released.
All 8 requests the fake server saw were the listing, manifest + signature, jar + signature (no other host exists).

Cost of the design, now observed: **two** failed boots on the new jar happen before the old jar runs (boot 2 sets
`BOOTING`, boot 3 restores but still executes the new bytes).

### `healthy`: good jar is applied and kept (24 checks, PASS, 33 s; run `claude-rollback-h-healthy`)

Boot 1 as above (exit 70, `APPLIED`, `.prev` = original). Boot 2 runs the release jar (`role=new-good`,
`60feb8f4e7ad…`): `first boot after applying …`, then at `ServerLoadEvent STARTUP`
`auto-updater: v26.2-r9001 reached ready; update confirmed (… .prev stays on disk until the next apply)`;
`Done (4.901s)!`; `rollback.state` phase `OK`; no rollback or hold block; the new jar stays on disk, `.prev` stays;
`/update apply` again → `already applied; nothing to do.`; clean stop exit 0.

### `dead-launcher`: a jar that dies before any SourbyCraft code (21 checks, PASS, 28 s; run `claude-rollback-d-dead-launcher`)

The release jar's `Main-Class` is replaced by a stub that prints `SOURBY_UPDATER_FIXTURE_DEAD_LAUNCHER … no
SourbyCraft code has run` and exits 4; `Clip-Version`/`SourbyClip-Protocol` are kept, so SEC-4's launcher gate accepts
and the updater applies it like any release. Boots 2 and 3 both exit 4 with `rollback.state` still `APPLIED`; the launch
jar is still the dead one; `.prev` is intact. **No automatic recovery happens.** After the fixture copies `.prev` back
by hand (the operator step) boot 4 reaches `Done (5.004s)!` on the original (its guard treats it as a first boot after
the apply and records `OK`).

## Findings

1. **The restore lives in the jar that is booted.** `UpdateRollbackGuard.onBoot()` runs inside the new jar's
   `SourbyCraftBootstrap`. If that jar cannot reach the `auto updater` boot stage (launcher/class-loading failure, early
   JVM abort, a corrupt jar that passes the zip/digest/signature/launcher-metadata checks), nothing ever restores
   `.prev`. The `dead-launcher` run shows it. The earlier text in this repo ("the restore happens one boot later than
   ideal", AUTO-UPDATER.md, runbook procedure F) was wrong for that shape and is corrected there. A SourbyCraft boot stage before `auto updater`
   that *throws* does not matter (`SourbyCraftBootstrap.stage` catches it and continues), but one that hangs or kills
   the JVM does; the fixture's broken jar dies later (plugin load), which the guard covers. At the time of that run the launcher had no recovery check. SourbyClip 3.1.4 adds a pre-server
   check inside the launcher, but the current 3.1.2 pin does not ship it. Failure before that guard
   is reached still requires the panel/operator. The revised fixture calls that guard before its
   deliberate failure; the historical Main-Class stub bypassed it entirely.
2. The boot that performs the restore still *runs* the new bytes (observed, boot 3: the probe reports
   `role=new-broken` while the file on disk is already the original), so the rest of that boot executes the suspect
   server code.
3. With the real key absent, nothing in the repository activates SEC-2; the fixture's test key proves the verification
   and manifest code on a real boot, not that CI signs a release.

## Not covered

* A real GitHub release, the real release key, CI signing (`SOURBYCRAFT_RELEASE_SIGNING_KEY` did not exist yet on
  2026-10-22; it does since Build 48, but the fixture still uses a throwaway key).
* Panel/Pterodactyl restart behaviour (`P_SERVER_UUID`, exit code 70 handling by a panel), Linux, Windows (the in-place
  swap is a POSIX rename; the 2026-10-22 text "Windows keeps the `core.path` fallback" was wrong: nothing reads
  `core.path` at boot, and since PR #62 a failed rename on Windows ends as a failed apply without a restart).
* Failure causes other than the marker-driven halt: an OOM, a real plugin crash, a hung boot that never reaches ready
  (the guard infers "not ready" from the phase file alone; a hung boot only counts once someone restarts it).
* Players, worlds with data worth keeping, or the extra-restart cost with a real panel's crash-loop limits.
* SEC-1/SEC-11 proxy checks (the other half of the Security row).

## Tests

`scripts/test_updater_rollback_fixture.py` (jar repacking, manifest/signature round trip with real OpenSSL, listing shape,
fake server routes and loopback binding, log-pattern contract against the Java sources, resource retry) and
`scripts/test_agent_testserver.py` (`RestartTest`, `AutoUpdateTomlTest`); Java `UpdateOverridesTest` (9) and the test
plugin's `UpdaterFixtureProbeTest` (7).
