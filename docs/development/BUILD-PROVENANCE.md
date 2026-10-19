# Build provenance (OBS-3)

`writeBuildInfo` and the server manifest share one configuration-time `scripts/build_identity.py` snapshot. Both embed the
full root `gitCommit`, SHA-256 `treeHash` and `dirty` state. A local modification adds
`-dirty` to the public build label (`Build 47-dirty`); numeric `buildNumber` stays `47`
for update comparison. Channel and Minecraft API version remain separate fields.

The hash covers relative paths, file bytes, executable bits and symlink targets of
tracked and non-ignored untracked files, including staged changes/deletions. It also
covers all four materialized Git working copies: `paper-api`, `paper-server`,
`sourbycraft-server/src/minecraft/java` and `sourbycraft-server/src/minecraft/resources`.
Dirty state includes these nested repos. Both generated build files,
`sourbyapi/build.gradle.kts` and `sourbycraft-server/build.gradle.kts`, are hashed.
Each is compared with the tracked module `build.gradle.kts.patch` applied strictly
to the upstream build file at `paperRef`, read from the Paper cache Git object database
(`.gradle/caches/paperweight/upstreams/paper`). Hand edits mark the build dirty even
when Git ignores the generated file. An unavailable upstream object or an unsupported
patch format cannot certify the generated input and produces unknown provenance.

Ignored build outputs and caches are otherwise excluded. This SHA-256 is a source
fingerprint, not Git's tree object ID or a reproducible binary checksum. Every helper
Git read uses `--no-optional-locks`, so status cannot refresh index stat entries.
Source changes during a build are unsupported. The manifest/properties check requires
matching snapshots; it is not a source freeze lock.

A root without Git metadata (including source exports), missing Git/Python, missing
nested Git metadata, unreadable input, or an unsupported directory entry (including
an untracked nested repository or submodule) falls back explicitly to
`gitCommit=unknown`, `treeHash=unknown`, `dirty=true`. Gradle reports unavailable
provenance; both identity surfaces share the same configuration-time fallback and
`-dirty` label. This supports local diagnostics only: other build prerequisites,
including the private toolchain and materialized sources, still apply.

At publication, use:

```sh
python3 scripts/verify_build_identity.py SERVER.jar 47 --channel REL --commit "$GITHUB_SHA"
```

`--commit` requires the exact full root commit and a clean tree. Missing provenance,
manifest/properties disagreement, malformed hashes and dirty builds fail. Without
`--commit`, dirty local builds, including explicit `unknown` provenance, can be inspected. Historical jars without provenance
remain inspectable without the publication check.

The existing `writeBuildInfo` task remains opted out of configuration caching. Its
source identity is declared as a Gradle input, so local content changes invalidate
UP-TO-DATE outputs; manifest generation reads the same helper during configuration.
Settings are build-time only and require a new JAR/restart.

Verification: `PYTHONPATH=scripts python3 -m unittest scripts/test_build_identity.py`.
The fixture tests include untracked/modified/deleted files, nested materialized edits,
index byte preservation, resource changes, generated API/server drift, missing Git,
untracked nested repositories, unknown local provenance, dirty labels, wrong commits and divergent hashes. Current build
and packaged-JAR evidence is recorded in `AGENT-COORDINATION.md`.
