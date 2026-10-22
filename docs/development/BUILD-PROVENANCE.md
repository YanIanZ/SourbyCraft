# Build provenance (OBS-3)

`writeBuildInfo` and the server manifest share one configuration-time `scripts/build_identity.py` snapshot. Both embed the
full root `gitCommit`, SHA-256 `treeHash` and `dirty` state. A local modification adds
`-dirty` to the public build label (`Build 47-dirty`); numeric `buildNumber` stays `47`
for update comparison. Channel and Minecraft API version remain separate fields.

The hash covers relative paths, file bytes, executable bits and symlink targets of
tracked and non-ignored untracked files, including staged changes/deletions. It also
covers all four materialized Git working copies: `paper-api`, `paper-server`,
`sourbycraft-server/src/minecraft/java` and `sourbycraft-server/src/minecraft/resources`.
The `dirty` flag counts tracked changes only: worktree or index differing from `HEAD`
(`git status --porcelain --untracked-files=no`). An untracked file in the root, such as
`AGENT-COORDINATION.md`, does not add `-dirty`, although it still enters `treeHash`, so a
tree with untracked files has a different hash from a clean checkout of the same commit.
Because of that, an untracked source file that compiles into the jar is not flagged dirty;
the publication gate is unaffected (CI builds a clean checkout). Dirty state includes the
nested repos, and there untracked files do count (the nested repos are not root-tracked
state; a file no patch commit holds is compiled into the jar). Both generated build files,
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
The fixture tests include untracked (hash changes, dirty does not)/modified/staged/deleted files, nested materialized edits,
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

## Reproducible slim jar, independent rebuild and attestation (Build 49)

**What is wired (IMPLEMENTED in the workflow and build script; CI result NOT YET OBSERVED, see below):**

- `slimServerJar` (root `build.gradle.kts`) writes every entry with one fixed time stamp and in a fixed
  order (`META-INF/MANIFEST.MF` first, then by name). The time is `SOURCE_DATE_EPOCH` clamped to the ZIP
  range (1980-01-01 to 2107), or exactly 1980-01-01T00:00:00 when it is unset; it is written with
  `ZipEntry.setTimeLocal`, so it does not depend on the machine's time zone. `SOURCE_DATE_EPOCH` is a task
  input, so changing it re-runs the task. Before this, entries took the clock.
- `slimServerJar` also writes every file of `sourbycraft-server/src/main/resources/META-INF/licenses/`
  (GPL text `LICENSE_SOURBYCRAFT`, source notice `NOTICE_SOURBYCRAFT`, upstream `LICENSE_*`/`NOTICE_*`)
  into the slim jar under `META-INF/licenses/` (SEC-13), merged into the same sorted entry list with the
  same fixed time; the directory is a task input. The `build` job checks them with
  `verify_build_identity.py build/libs/SourbyCraft-slim.jar --api-only ... --licences --source-root .`
  (GPL text hash pinned, notice markers, byte equality with the checkout, no extra files). Local
  evidence 2026-10-22: two `slimServerJar -PsourbyBuild=49` runs (second with `--rerun-tasks`) at
  `SOURCE_DATE_EPOCH=1761091200` gave the same SHA-256 `1a3bc879…b2c0`; not yet observed on CI.
- The `build` job pins `SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)` for every later step (so `buildTimestamp`
  in `sourbycraft-build.properties` and the server manifest `Build-Time` also derive from the commit, not
  `now()`), and pins the exact Temurin build (`java-version: 25.0.4+1`) instead of the floating `25`. Bump the
  pin in the `build` and `rebuild` jobs together; `scripts/test_reproducible_build.py` fails when they differ
  or when the pin becomes a bare major. The provenance `Jdk` string (vendor + runtime version) and the deflate
  output depend on that exact build.
- The `build` job exposes `slim_sha256` and `source_epoch` as job outputs and uploads the slim jar for one day.
- The `rebuild` job (`needs: build`, `contents: read`, push and dispatch only, never `pull_request`) checks the
  same commit out fresh, restores the branch name (the jar records `gitBranch`), publishes the same pinned
  private tools with the same deploy keys, applies patches, runs `slimServerJar` with the same `-P` flags and
  compares SHA-256. A difference prints `unzip -l` / `unzip -v` diffs and `scripts/jar_diff.py` (nested jars,
  properties and manifests, entry order and header fields) and fails the job. The `release` job `needs` the
  rebuild, so a non-reproducible jar is not published, and re-checks that the jar it publishes has the
  hash the build job recorded.
- The `release` job attests the jar and the SBOM with `actions/attest-build-provenance` (pinned by SHA, v4.2.2).
  `id-token: write` and `attestations: write` are granted to that job only.

- A build stamped with `SOURCE_DATE_EPOCH` compiles with `-g:source,lines` (root `build.gradle.kts`): javac names
  a synthetic local in a lambda after an identity hash, so two clean builds of one commit differed in
  `io/papermc/paper/command/subcommands/EntityCommand.class` (`$1022440802` vs `$2023237490`) and so in the
  server jar, the paperclip patch and the slim jar. The cost is that **stamped (CI/release) jars carry no
  LocalVariableTable**: line numbers and stack traces are unchanged, but a debugger sees no local-variable
  names and anything that resolves locals by name from bytecode (for example a mixin local capture by name
  against a server class) has none to find. Builds without `SOURCE_DATE_EPOCH` keep the default `-g`.

**Local proof (2026-10-22, worktree on `2c66444b`, macOS arm64, OpenJDK 25.0.2, `SOURCE_DATE_EPOCH=1792600571`,
`-PsourbyBuild=49`, `build/` removed and `--no-build-cache` between runs):**

| Run | Time zone | `-g` | `SourbyCraft-slim.jar` SHA-256 |
|---|---|---|---|
| 2 | UTC | default | `5e1dd2783206d3c3c8064afd52475e4ad847668b059dba971b04b8aadad41da8` |
| 3 | UTC | default | `830c7c501cdc398dcb766cd0da16c54e0727475e906f94bc401be38d4fb624b0` |
| 4 | UTC | `source,lines` | `7e0708bbd415f1bea8d9550508810b677038ff64026a2aa46162ea714f8b096f` |
| 5 | Asia/Jakarta | `source,lines` | `7e0708bbd415f1bea8d9550508810b677038ff64026a2aa46162ea714f8b096f` |

Runs 2 and 3 (identical inputs, entry times already fixed) differed in `META-INF/patches.list`,
`META-INF/versions.list` and the paperclip patch, all following from the one differing class; runs 4 and 5 are
byte-identical, in two time zones. Not covered: a different checkout path, a different machine or JDK build
(CI uses Temurin 25.0.4+1, this laptop 25.0.2), and the CI `rebuild` job itself, which has not run yet.

**Verify a release** (needs the `gh` CLI; `<jar>` is the downloaded `SourbyCraft-26.2-REL.jar`):

```bash
gh attestation verify <jar> --repo YanIanZ/SourbyCraft           # signature, repository, workflow, commit
sha256sum <jar>                                                    # equals the sha256 in the release notes and <jar>.sha256
python3 scripts/sbom.py verify <jar> SourbyCraft-26.2-REL.cdx.json
```

The rebuild comparison is in the `Rebuild slim jar and compare` job of the run that published the release
(job summary lists both hashes). To redo it yourself you need the private toolchain (`PRIVATE-TOOLCHAIN.md`):
check out the tag commit, `export SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)`, use Temurin 25.0.4+1, then
`./gradlew applyAllPatches` and `./gradlew slimServerJar -PsourbyBuild=<N> -PsourbyChannel=REL`, and compare
`build/libs/SourbyCraft-slim.jar` with the published asset.

**Limits, stated plainly:**

- The rebuild job is a second run on the same GitHub infrastructure with the same two deploy keys and the same
  pinned SourbyPatcher/SourbyClip revisions. It shows the jar is a function of the commit and the pinned
  inputs. It does not show that the private toolchain or GitHub is trustworthy.
- The attestation says this workflow run, at this commit, produced these bytes. It does not attest the private
  toolchain's source and says nothing about the jar signature (SEC-2); the Ed25519 signature remains a separate check.
- Reproducibility is only claimed for the slim jar on the pinned JDK. A different Temurin build, a different
  `SOURCE_DATE_EPOCH`, an untracked non-ignored file in the checkout (`treeHash` covers those) or a different
  branch name changes the bytes. The build job writes its release-number scratch files to `$RUNNER_TEMP` for
  that reason.
