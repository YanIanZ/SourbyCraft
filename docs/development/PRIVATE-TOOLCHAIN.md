# Private build toolchain — 26.2

Official server downloads are GitHub Releases. Maintainer builds require private checkouts:

- `YanIanZ/SourbyPatcher`: active `canvas-toolchain` adapter 2.0.20 and archived Folia patcher.
- `YanIanZ/SourbyClip`: launcher 3.0.23, protocol 1.

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

SourbyPatcher's active plugin delegates patch sequencing to Weaver 2.4.5. This retains the
working PR #12 Canvas integration; the old Folia implementation is not reactivated.
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

## CI and distribution

Trusted pushes and manual runs check out private sources using separate read-only deploy keys
(`SOURBYPATCHER_DEPLOY_KEY` and `SOURBYCLIP_DEPLOY_KEY`). Pull-request events do not execute the
private build. Do not switch this workflow to `pull_request_target` or run untrusted PR code
with these keys. Credentials are not persisted in checkouts.

Private build inputs, Gradle caches and intermediate server/test-plugin JARs are not uploaded
to public workflow artifacts. Build, tests, boot/JFR and Docker checks share one runner. Only
the existing release branch publishes a server JAR after those checks pass. Diagnostic logs/JFR
remain available as workflow artifacts. Removing old artifacts/history is outside this migration.
