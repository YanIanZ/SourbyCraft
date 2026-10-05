# Private build toolchain — 26.2

Official server downloads are GitHub Releases. Maintainer builds require private checkouts:

- `YanIanZ/SourbyPatcher`: active `paper-toolchain` 3.0.0; archived `canvas-toolchain` 2.0.x and Folia patcher.
- `YanIanZ/SourbyClip`: launcher 3.0.26, protocol 1.

The public repository no longer vendors these sources or SourbyClip Maven binaries.
Previously published Git history and releases still contain earlier versions. Private access
controls the official toolchain; it cannot prevent independent builds or alternative implementations.
Upstream license and attribution obligations remain applicable; repository privacy changes none of them.

## Maintainer setup

Use Java 25 and Python 3.11+ for the full test suite. Check out both repositories under `.private-toolchain/` using your authorized
GitHub account. Check out the exact SHAs in `build-data/private-toolchain.lock.json`, then run:

```sh
python3 scripts/private_toolchain.py --publish .private-toolchain
./gradlew verifySourbyClip applyAllPatches
./gradlew :sourbycraft-server:test slimServerJar
```

Both tools publish only to Maven Local (`~/.m2/repository`). For another Maven Local directory,
pass `--maven-local PATH` to the script and `-Dmaven.repo.local=PATH` to Gradle.
The root build resolves private coordinates exclusively there, with no remote Maven fallback.
The standalone check `python3 scripts/private_toolchain.py` validates installed JAR hashes.

SourbyPatcher's active plugin applies paperweight's patcher (2.0.0-beta.24) with Paper as the only
upstream. Canvas and Weaver were dropped on 2026-10-05; the former Canvas/Folia changes are
SourbyCraft's own patches and sources. `canvas-toolchain` is kept only to rebuild older revisions.
Before patching or packaging, SourbyClip must match the approved SHA-256, version, main class,
and protocol. Settings also verify the SourbyPatcher JAR hash before loading its plugin.
This detects accidental or unapproved substitutions; it is not a DRM boundary.

When changing a tool, bump its version, test and publish it privately, then update both the
revision lock and artifact hashes in `gradle.properties`. Never silently republish different
bytes under the same approved version. Artifact generation must remain reproducible across CI hosts.

## Offline bootstrap

```sh
java -Dsourbyclip.offline=true -jar SourbyCraft-26.2-REL.jar --nogui
```

The launcher uses hash-verified cache entries and embedded libraries. It refuses network
bootstrap downloads and IP lookup; missing/corrupt dependencies fail with a coordinate or path.
It does not disable network activity initiated by Minecraft, plugins, or SourbyCraft services.
Use `java -jar JAR --sourbyclip-info` to inspect launcher identity without starting the server.

## Local terminal/toolchain upgrade candidates — 2026-10-05

The official pins above still select Patcher 3.0.0 and Clip 3.0.26. The following upgrades
are implemented in local private commits and published to Maven Local for review/testing.
Their private commits have not been pushed or selected by the public revision lock.

| Tool | Candidate | Local revision | JAR SHA-256 |
| --- | --- | --- | --- |
| SourbyClip | 3.1.1, protocol 1 | `5b6249c2d85e5f52a7c2687ef44eb92bde47d065` | `ca9f54d291af8d2c0afc9b71259342668146993067cd33bb7ba92f59d4045070` |
| SourbyPatcher | 3.1.0, paper-toolchain | `34e2161686d1f599a7d4efe9a5168d7a91192c75` | `90ee7c25228541ca478049d1cbe462bfc0c6a016499b6b5f26fab6d58ea3f14d` |

Clip adds four-stage terminal output, progress and elapsed classpath preparation time,
`--sourbyclip-help` and `--sourbyclip-prepare`. New console/worker properties are
**RESTART_REQUIRED**. Download workers have a fixed per-batch limit and explicit closure;
original-JAR replacement uses verified staging with connect/read timeouts. Patcher adds
`sourbyToolchainDoctor` and checks the loaded paperweight manifest version against an
expectation embedded inside its hash-pinned JAR. This detects version drift through a
substituted POM; it does not authenticate the complete POM/dependency contents.

Inspect launcher controls without booting:

```sh
java -jar ../SourbyClip/build/libs/sourbyclip-3.1.1.jar --sourbyclip-help
java -jar ../SourbyClip/build/libs/sourbyclip-3.1.1.jar --sourbyclip-info
```

From SourbyCraft, use the tested override command to review the active diagnostic UI:

```sh
./gradlew \
  -PpatcherVersion=3.1.0 \
  -PpatcherSha256=90ee7c25228541ca478049d1cbe462bfc0c6a016499b6b5f26fab6d58ea3f14d \
  -PclipVersion=3.1.1 \
  -PclipSha256=ca9f54d291af8d2c0afc9b71259342668146993067cd33bb7ba92f59d4045070 \
  sourbyToolchainDoctor verifySourbyClip --configuration-cache --console=plain
```

The same command succeeds again with configuration-cache reuse. Private builds pass on
Temurin 25.0.4.1+1/Gradle 9.8.0/macOS arm64: 29 Clip Java tests, 11 Patcher Java tests and
8 SourbyCraft Python launcher/toolchain tests using candidate artifact overrides. Each
JAR hash is identical across two local clean builds; cross-host reproduction is unverified.
Source audit, remaining transport limits and repository fallback order are recorded in
[bootstrap-download-audit.md](../architecture/bootstrap-download-audit.md).

Before rollout, push the private commits and update the public revision lock/version/hash
pins together, then run the full patch/compile/package/Python and server cold/offline boot
gates. No server boot or release qualification is inferred from diagnostic or fixture tests.

## CI and distribution

Trusted pushes and manual runs check out private sources using separate read-only deploy keys
(`SOURBYPATCHER_DEPLOY_KEY` and `SOURBYCLIP_DEPLOY_KEY`). Pull-request events do not execute the
private build. Do not switch this workflow to `pull_request_target` or run untrusted PR code
with these keys. Credentials are not persisted in checkouts.

Private build inputs, Gradle caches and intermediate server/test-plugin JARs are not uploaded
to public workflow artifacts. Build, tests, boot/JFR and Docker checks share one runner. Only
the existing release branch publishes a server JAR after those checks pass. Diagnostic logs/JFR
remain available as workflow artifacts. Removing old artifacts/history is outside this migration.

## Bootstrap qualification

`python3.12 scripts/verify_bootstrap.py build/libs/SourbyCraft-slim.jar --output build/bootstrap-new`

The output directory must not exist. The check boots once without copied caches, stops cleanly,
then boots the same runtime with launcher offline mode. It writes a JSON report, separate logs
and JFR recordings for both phases. Server/plugin network access is outside the offline flag's scope.

The pre-26.2 `nms-compat.yml` harness was removed (it was never ported and was not a release
gate). A replacement must be designed around the private checkouts from the start; do not reuse
its old public caches or offline build invocation with private credentials.
