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

## Provenance block (TODO Inovasi "provenance manifest", 2026-10-20)

Next to the source identity above, every jar carries what it was built *from*. One Gradle provider
(`sourbycraftProvenance` in root `build.gradle.kts`) feeds both surfaces, so they stay in lockstep:
`writeBuildInfo` writes `provenance.<Name>=<value>` into `META-INF/sourbycraft-build.properties`
and the server manifest (`sourbycraft-server/build.gradle.kts.patch`) writes `Provenance-<Name>`.

| `<Name>` | Value | Source |
| --- | --- | --- |
| `Minecraft-Features` | SHA-256 over `sourbycraft-server/minecraft-patches/features` | `build_identity.directory_hash` |
| `Paper-Features` | SHA-256 over `sourbycraft-server/paper-patches/features` | same |
| `Api-Paper-Patches` | SHA-256 over `sourbyapi/paper-patches` (features + files) | same |
| `Engine-Baseline` | SHA-256 of `build-data/engine-baseline.sha256` | file bytes |
| `Toolchain-Lock` | SHA-256 of `build-data/private-toolchain.lock.json` | file bytes |
| `Patcher-Sha256`, `Patcher-Pom-Sha256`, `Patcher-Module-Sha256`, `Clip-Sha256` | the pinned toolchain hashes, verbatim | `gradle.properties` |
| `Jdk` | `<vendor> <runtime version>` of the Java 25 **compiler toolchain** (not the JVM running Gradle) | `JavaToolchainService.compilerFor` |

A directory hash is SHA-256 over one line per regular file, sorted by relative POSIX path:
`<path> NUL <hex SHA-256 of the bytes> LF`. It reads the working tree, so an uncommitted patch edit
changes it (and `dirty` says so); names starting with `.` are skipped, a symlink makes the value
`unknown`. A value that cannot be computed is `unknown` — allowed locally, refused at publication.
The `Engine-Baseline` value is the manifest file's hash, not a re-hash of the baseline patch tree
(`baseline_policy.py` already pins that tree to the manifest).

`/ver` prints the block under "Provenance" (hashes cut to 12 hex characters; full values in the
manifest/properties). `brand/BuildProvenance` reads it; a jar without the block shows
"not recorded in this build".

`verify_build_identity.py` checks that every `provenance.*` key equals its `Provenance-*` attribute
(and that the manifest has none the properties lack), that values are SHA-256 hex, and that `Jdk`
names Java 25. With `--commit` (publication) every key is required. With `--source-root <checkout>`
it recomputes every source-derived value from that checkout and requires equality; CI passes
`--source-root .` right after `slimServerJar`. Tests: `test_build_identity.BuildProvenanceTest` (10),
`BuildProvenanceTest` (4), `VerCommandTest.theProvenanceBlockIsPrinted`.

Not covered: this is embedded metadata, not a signed in-toto/SLSA attestation; the jar signature
(SEC-2, once the key exists) is what makes it tamper-evident after publication.

## SBOM (CycloneDX 1.6)

`scripts/sbom.py generate <slim jar> --server-jar <server jar> -o <file>.cdx.json` describes the
shipped jar: SourbyClip (manifest `Clip-Version`), the patched server jar (`versions.list`, whose
SHA-256 must equal `--server-jar`; its build identity and provenance become metadata properties),
the Mojang server jar (`download-context`) and one `library` component per `libraries.list` line
(purl `pkg:maven/...`, SHA-256 from the list, `sourbycraft:delivery` = `bundled` when the jar holds
it, `not-bundled` when slimServerJar stripped it or it comes from the Mojang jar). Deterministic
per jar (serial number from the jar hash, timestamp from `buildTimestamp`/`SOURCE_DATE_EPOCH`).

Why not the CycloneDX Gradle plugin: the release artifact is the paperclip/SourbyClip slim jar, not
a Gradle module; what runs is decided by `libraries.list` (written by paperweight from the resolved
runtime classpath) and by which jars slimServerJar stripped, which a Gradle-graph SBOM cannot see.
Reading the jar also adds no third-party plugin to a configuration-cache build that resolves its
toolchain from a pinned private Maven Local.

`scripts/sbom.py verify <jar> <sbom>` is the gate: every `libraries.list` line must be exactly one
component with the same purl, SHA-256, path and delivery, no other library may be listed, the root
hash must be the jar's and the server component must match `versions.list`. CI generates and
verifies it after `slimServerJar`, uploads it as the `SourbyCraft-26.2-sbom` artifact and attaches
it to the GitHub release. Tests: `scripts/test_sbom.py` (9, one against the locally built jar when
present). Not validated against the official CycloneDX JSON schema (no validator is vendored).
