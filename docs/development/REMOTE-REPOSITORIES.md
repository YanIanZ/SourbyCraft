# Remote repositories and network endpoints

Every remote host the build (local and CI) and the first server boot reach, the order in which
alternatives are tried, and what happens without network. First verified 2026-10-20 against the
working tree of branch `26.2` (HEAD `4e859950` plus uncommitted round-3 edits), the private
toolchain at the revisions then pinned in `build-data/private-toolchain.lock.json`
(SourbyPatcher `1270d7e3`, SourbyClip `85b4dd70`, read from the local clones), and the local
`build/libs/SourbyCraft-slim.jar` of 2026-10-20 01:19. **Re-verified 2026-10-25** (static reading only)
against branch `build50/pm-completion` (PR #62) and the current lock: SourbyPatcher `1270d7e3` (unchanged, its
sources not re-read) and SourbyClip `e5de0f4b` (3.1.3, read from a local clone at exactly that revision). File
and line references below are the 2026-10-25 ones. The slim-jar inspection (packaged download contexts) was not
repeated.

Status column: **verified** = the URL/repository is written in the cited file;
**plugin behaviour** = done by a third-party Gradle plugin or upstream code whose source was not
inspected here; **unverified** = believed but not checked. Re-verify after a Paper, paperweight,
SourbyPatcher or SourbyClip bump.

Gradle tries the repositories of one block in declaration order and takes a module from the first
that has it. An `exclusiveContent` filter pins a group/module to one repository: it is never
looked up anywhere else, and that repository serves nothing else.

## 1. Build: Gradle itself and plugins

| Remote | Used for | Declared in | Status |
| --- | --- | --- | --- |
| `https://services.gradle.org/distributions/gradle-9.8.0-bin.zip` | Gradle wrapper distribution (first `./gradlew` on a host) | `gradle/wrapper/gradle-wrapper.properties:4` | verified; since PR #62 (`4053b44`) `distributionSha256Sum` is pinned (`gradle-wrapper.properties:3`, value from `services.gradle.org/.../gradle-9.8.0-bin.zip.sha256`), so the wrapper refuses a swapped distribution; CI also validates the wrapper JAR with `gradle/actions/wrapper-validation` (`build.yml:38`, `:115`, `:1059`). Not yet exercised by a wrapper download on CI |
| Gradle Plugin Portal (`gradlePluginPortal()`) | `io.papermc.paperweight.core` 2.0.0-beta.24, `org.gradle.toolchains.foojay-resolver-convention` 1.0.0, `io.papermc.fill.gradle` 1.0.12 | `settings.gradle.kts:37` (`pluginManagement`), `:49`, `:54`; generated `sourbycraft-server/build.gradle.kts` | verified (declaration) |
| Maven Local (`~/.m2/repository` or `-Dmaven.repo.local`) | `dev.iyanz.sourbypatcher.*` plugin, exclusively | `settings.gradle.kts:38-41` | verified; settings evaluation **fails** unless `paper-toolchain-${patcherVersion}.jar` **and its `.pom`/`.module` metadata** exist there with SHA-256 `patcherSha256` / `patcherPomSha256` / `patcherModuleSha256` (`gradle.properties`); `scripts/private_toolchain.py` additionally requires the metadata to declare `paperweightVersion` |
| `https://repo.papermc.io/repository/maven-public/` | remaining plugin dependencies | `settings.gradle.kts:42` | verified |

Plugin resolution order: Plugin Portal, then Maven Local (SourbyPatcher only), then PaperMC.
Plugin versions: `patcherVersion=3.0.0` (`gradle.properties:41`, plugin `settings.gradle.kts:46`),
`paperweightVersion=2.0.0-beta.24` (`gradle.properties:53`, `settings.gradle.kts:49`).

**Hash checks at settings time.** Settings evaluation fails closed, before any build task runs:
patcher JAR SHA-256 against `patcherSha256` (`settings.gradle.kts:7-15`, `gradle.properties:43`);
patcher POM and `.module` against `patcherPomSha256` / `patcherModuleSha256`
(`settings.gradle.kts:18-35`, `gradle.properties:47,50`). The pinned POM and `.module` must both
declare `paperweightVersion` (enforced by `scripts/private_toolchain.py:29-46,82-86`, run in CI
before SourbyCraft's Gradle steps; it builds the two private tools with Gradle first and checks their
metadata afterwards).

**Foojay toolchains.** `foojay-resolver-convention` lets Gradle download a JDK 25 through the
foojay Disco API (`api.foojay.io`, plugin behaviour, unverified here) only when no local JDK 25
matches the toolchain request in root `build.gradle.kts` (`JavaLanguageVersion.of(25)`). CI
installs Temurin with `actions/setup-java` first (`build.yml:48`, `:122`, `:1063`), so it normally does not
download one. The toolchain requests are at `build.gradle.kts:239` (the provenance `Jdk` value) and `:275-279`
(every subproject).

**Private toolchain pins.** SourbyPatcher and SourbyClip are private repositories pinned in
`build-data/private-toolchain.lock.json:3-4` (SourbyPatcher `1270d7e3…`) and `:7-8` (SourbyClip
`e5de0f4b…`, 3.1.3 since 2026-10-22; it was `85b4dd70…` on 2026-10-20). `scripts/private_toolchain.py --publish`
refuses a checkout at the wrong revision or with uncommitted changes (`:96-101`), runs
`gradlew test publishToMavenLocal` (`:102-104`), and verifies the installed JARs against `clipSha256` /
`patcherSha256` (`:54-67`). CI reads the revisions from the lock and checks out both repositories with read-only
deploy keys (`build.yml:133-162` in the build job, `:1074-1103` in the rebuild job). Neither coordinate has a
remote Maven fallback (`build.gradle.kts:15-20`, `settings.gradle.kts:38-41`).

## 2. Build: dependencies

| Remote | Used for | Declared in | Status |
| --- | --- | --- | --- |
| Maven Local | `dev.iyanz:sourbyclip` only (exclusive) | root `build.gradle.kts` `repositories` and `subprojects { repositories }` | verified; must hold `sourbyclip-${clipVersion}.jar` (pinned by `clipSha256`, checked by `scripts/private_toolchain.py`) |
| Maven Central (`mavenCentral()`) | all subprojects | root `build.gradle.kts` `subprojects`; `Metal/build.gradle.kts` | verified |
| `https://repo.papermc.io/repository/maven-public/` | Paper artifacts, `io.papermc:mache` | root `build.gradle.kts` (`paperMavenPublicUrl`, `:204`) | verified |
| `https://libraries.minecraft.net/` | Mojang libraries for the opt-in test plugins (`-PincludeTestPlugin=true`) | `test-plugin/build.gradle.kts:4`, `legacy-test-plugin/build.gradle.kts:4` | verified |

Subproject order (`build.gradle.kts:281-288`): Maven Local (sourbyclip only, `:282-285`), Maven Central
(`:286`), PaperMC (`:287`). The `file:${rootDir}/sourby-maven` repository that came last was removed in PR #62
(`4053b44`): the directory did not exist in the tree and served nothing.
`Metal/build.gradle.kts:7-9` adds Maven Central for the vendored Metal module.
`tasks.generateDevelopmentBundle` records `https://repo.maven.apache.org/maven2/` and the PaperMC
URL as library repositories for the dev bundle (generated `sourbycraft-server/build.gradle.kts`,
from `sourbycraft-server/build.gradle.kts.patch`); that is metadata for bundle consumers, not a
resolution step of this build. `Metal/build.gradle.kts` names PaperMC `maven-snapshots` /
`maven-releases` as publish targets only.

**paperweight (inside SourbyPatcher `paper-toolchain`).** `applyAllPatches` and the jar tasks
download, at the locked SourbyPatcher revision:

| Remote | Used for | Source (SourbyPatcher `1270d7e3`) | Status |
| --- | --- | --- | --- |
| `https://github.com/PaperMC/Paper.git` | the Paper upstream checkout at `paperRef` (`gradle.properties`) | `sourbypatcher-core/.../PaperweightPatcherExtension.kt` (`repo.convention(github("PaperMC", "Paper"))`), `UpstreamConfig.kt` | verified |
| `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` and the URLs it lists | vanilla server jar and mappings | `paperweight-lib/.../constants.kt` (`MC_MANIFEST_URL`) | verified (URL); per-file hosts come from Mojang's manifest |
| `https://libraries.minecraft.net/`, `https://repo.papermc.io/repository/maven-public/`, `https://repo.maven.apache.org/maven2/` | Minecraft/Paper libraries | `constants.kt` (`MC_LIBRARY_URL`, `PAPER_MAVEN_REPO_URL`, `MAVEN_CENTRAL_URL`) | verified (URLs); the order paperweight uses them in was not traced |

`createPaperclipJar` writes `META-INF/download-context` (vanilla jar from
`https://piston-data.mojang.com/v1/objects/<sha1>/server.jar`) and, by code, a
`download-context-cn` variant (`https://bmclapi2.bangbang93.com/...`)
(`paperweight-lib/.../CreatePaperclipJar.kt`). The local slim jar contains only
`META-INF/download-context`; the `-cn` variant is not packaged (checked with `unzip -l`).

## 3. CI only (`.github/workflows/build.yml`)

| Remote | Used for | Status |
| --- | --- | --- |
| GitHub `YanIanZ/SourbyCraft` | checkout (`build.yml:33` in the PR job; `fetch-depth: 0` at `:102`, `:1041`, `:1195` in the build, rebuild and release jobs) | verified |
| GitHub `YanIanZ/SourbyPatcher`, `YanIanZ/SourbyClip` (private) | checkout at the lock-file revisions with deploy keys `SOURBYPATCHER_DEPLOY_KEY` / `SOURBYCLIP_DEPLOY_KEY` (`build.yml:133-162`, again `:1074-1103` in the rebuild job); `scripts/private_toolchain.py --publish` then builds them (`gradlew test publishToMavenLocal`) | verified; without the secrets the job stops before any Gradle step |
| Repositories of the private builds | SourbyPatcher `paper-toolchain`: Maven Central + Plugin Portal (`paper-toolchain/build.gradle.kts`, `settings.gradle.kts`); its `buildSrc` adds PaperMC, `maven.neoforged.net`, `maven.fabricmc.net`. SourbyClip `java25`: Maven Central, `repo.spongepowered.org/maven/`, `repo.leavesmc.org/releases/` and `/snapshots/` (`java25/build.gradle.kts:18-23`) | SourbyClip re-verified at `e5de0f4b` (2026-10-25); SourbyPatcher verified at `1270d7e3` on 2026-10-20 (unchanged pin); whether `buildSrc` repositories are reached for the `paper-toolchain` project alone was not traced |
| GitHub Actions marketplace (`actions/checkout`, `actions/setup-java`, `gradle/actions/wrapper-validation`, `actions/upload-artifact`, `actions/download-artifact`, `actions/attest-build-provenance`) | pinned by 40-hex commit SHA | verified |
| Ubuntu apt mirrors | `apt-get install redis-server` for the AWF Redis tests (`build.yml:171`) | verified |
| Temurin download via `actions/setup-java` | JDK 25 (`build.yml:48`, `:122`, `:1063`) | verified (declaration) |
| Docker Hub (`eclipse-temurin:25-jre@sha256:dbb43d81…`) | base image pulled by `docker build` in the build job (`build.yml:993-997`, `docker/Dockerfile:19`), pinned by digest | verified (declaration) |
| GitHub OIDC and the attestation store (Sigstore-backed) | `actions/attest-build-provenance` in the release job (`build.yml:1273`; `id-token: write`, `attestations: write`) | plugin behaviour; the endpoints the action contacts were not traced |
| GitHub Releases (`gh release create`) | publishing the tag and assets from the release job (`build.yml:1346`) | verified |
| `https://api.github.com/repos/dmulloy2/ProtocolLib/releases/...` and the pinned asset URL | the scheduled ProtocolLib pin probe (`.github/workflows/protocollib-pin-probe.yml:43-44`, `:61-62`, `:78-79`; daily 06:17 UTC and manual) | verified; read-only, compares asset name/ID/size/SHA-256 and the release id, never updates the pin |

## 4. First boot (SourbyClip launcher, then the server)

Order on a fresh host, as the code runs it:

1. **Vanilla server jar.** SourbyClip reads `META-INF/download-context` and downloads
   `https://piston-data.mojang.com/v1/objects/823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar`
   into the cache unless a SHA-256-valid copy is already there (`DownloadContext.download`,
   SourbyClip `java25/.../DownloadContext.java`; the URL is the one the 2026-10-20 slim jar carried). The
   expected SHA-256 is checked before the cached copy is replaced; a failed HTTP, body or hash attempt keeps
   the previous file. At the pinned 3.1.3 (`e5de0f4b`, private repository, read from a local clone) this and
   the library downloads below go through `downloader/BoundedTransfer`: bytes go to a unique temporary file
   that is published only after the SHA-256 check; a whole-transfer deadline (size / 64 KiB/s clamped to
   120-1800 s, 600 s when the size is unknown), 30 s connect and idle timeouts, a byte cap (expected size plus
   1 %, at least 64 KiB; 256 MiB when unknown) and up to 2 retries of the same source with a doubling 1 s
   backoff. Those limits are RESTART_REQUIRED launcher properties (`sourbyclip.transferDeadlineSeconds`,
   `sourbyclip.maxTransferBytes`, `sourbyclip.transferRetries`, `sourbyclip.transferBackoffMillis`);
   [bootstrap-download-audit.md](../architecture/bootstrap-download-audit.md) has the earlier candidate
   revisions. `-Dsourbyclip.downloadContext=<name>` selects another packaged context; a name that is not
   packaged (e.g. `download-context-cn` on the 2026-10-20 jar) falls back to the default
   (`Sourbyclip.findDownloadContext`), and a selected context whose download fails falls back once to the
   default context (`Sourbyclip.setupClasspath`). No other mirror is tried.
2. **Libraries** from `META-INF/libraries.list` (`FileEntry.downloadFromMvnRepo`,
   `downloader/Downloader.java`), per entry: SHA-256-valid file in the cache, else the copy
   bundled in the jar or the vanilla bundle, else download by Maven coordinate trying, in order
   (`Sourbyclip.ALL_MAVEN_REPO_LINK_BASE`):
   1. `https://maven.aliyun.com/repository/central`
   2. `https://repo.papermc.io/repository/maven-public`
   3. `https://repo.menthamc.org/repository/maven-public`
   4. `https://repo.spongepowered.org/maven`

   The download is checked against the hash in `libraries.list` (`build.gradle.kts:53-68` comment).
   Which libraries need this is decided at build time by `slimServerJar`'s `externalizeArtifactDirs`
   (root `build.gradle.kts:76-96`; the task is at `:98-202`, and a strip count of 0 fails the build
   at `:191-196`). `MavenDependencyResolver.getDefaultRepositories()` (Maven Central,
   Sonatype snapshots) exists but is not called on this path (2026-10-20 reading; not re-traced at `e5de0f4b`).
3. **Packet/legacy-client plugins**, before Paper's plugin scan (feature patch 0024 calls
   `PluginProvisioner.provisionJars`), each pinned by exact SHA-256 and size, HTTPS-only,
   redirects followed (`LibDownloader.HTTPS`), no mirror:
   - ProtocolLib 5.4.0 (tagged release, not `dev-build`):
     `https://github.com/dmulloy2/ProtocolLib/releases/download/5.4.0/ProtocolLib.jar`, SHA-256
     `ee2e7ab9…` and 10,251,186 bytes (`bootstrap/ProtocolLibProvisioner.java:13-16`; the same pin is
     in `build-data/protocollib-pin.json:2-15`; toggle `plugins.protocollib.auto-provision`, read at
     `ProtocolLibProvisioner.java:21`).
   - ViaVersion 5.10.0 `https://cdn.modrinth.com/data/P1OZGk5p/versions/ruzmiBqe/ViaVersion-5.10.0.jar`
     (SHA-256 `e5a63f86…`, 6,434,343 bytes; `bootstrap/PluginProvisioner.java:61-65`) and ViaBackwards
     5.10.0 `https://cdn.modrinth.com/data/NpvuJQoq/versions/YjpKsm6j/ViaBackwards-5.10.0.jar`
     (SHA-256 `4699b6dd…`, 1,422,522 bytes; `:66-70`); toggle `plugins.viaversion.auto-provision`.
     The offline refusal is at `PluginProvisioner.java:234-235`; the https-only check at `:237-240`.

   The toggles are read before the configuration loads (`BootstrapPluginSettings`). Since Build 50 (PR #62) a
   file that sets only the pre-49 `[protocollib]`/`[viaversion]` key, an invalid or quoted value, a flow-style
   YAML value or an unreadable file **skips** that group for the boot: nothing is downloaded, installed or
   quarantined ([PROTOCOLLIB.md](PROTOCOLLIB.md)). A download failure is logged with source URL and target path
   and the boot continues without that plugin.
4. **Plugin libraries.** Plugins that declare `libraries:` are resolved by Paper's
   `MavenLibraryResolver` from `MAVEN_CENTRAL_DEFAULT_MIRROR`, default
   `https://repo.maven.apache.org/maven2` (materialized `paper-api/.../MavenLibraryResolver.java`,
   `LibraryLoader.java`; Paper behaviour, only when a plugin asks).

## 5. While running

| Remote | Trigger | Source | Fallback |
| --- | --- | --- | --- |
| `https://api.github.com/repos/<repo>/releases?per_page=100`, `<repo>` default `YanIanZ/SourbyCraft` (`updater.repository`), then the release asset's `browser_download_url`, its `.sig`, and with the release key (r48 on) `sourbycraft-release-manifest.json` and its `.sig` | auto-updater schedule (`updater.enabled`, default `true`) and `/update` | `update/SourbyUpdater.java`, `AutoUpdateSettings.java`; see [AUTO-UPDATER.md](AUTO-UPDATER.md) | none; ETag conditional requests and a rate-limit backoff window, a failed check just ends. Since PR #62 every asset URL must be `https`, the jar's declared size is at most 512 MiB and each asset download has a 15-minute total deadline |
| `https://api.modrinth.com/v2/project/<id>/version`, then the pinned `cdn.modrinth.com` URL | same cadence, after each updater check; runs only inside the updater's schedule (needs `updater.enabled: true` and an `apply-mode` other than `off` at boot), with `updater.via-auto-update` (default `true`) and `plugins.viaversion.auto-provision` absent or `true` | `update/ViaAutoUpdate.java` | none; reinstalls only a missing shipped pin, never upgrades |
| `https://download.db-ip.com/free/dbip-city-lite-YYYY-MM.mmdb.gz` | downloaded in the background on the first `/ping` geo lookup without a local database (current month, then the previous month) | `util/GeoUtil.java` | that lookup and later ones answer "unavailable" until the download finishes; a failed download is retried on the first lookup one hour later (not disabled until restart); no download starts while the server is stopping or after a failed startup (PR #62) |
| `https://install.speedtest.net/app/cli/` | `/speedtest` | `command/SpeedtestCommand.java` (SHA-256 pinned archives; since PR #62 the extracted binary's SHA-256 is recorded and re-checked before every run) | none |

Not inventoried here: Mojang session/authentication servers for `online-mode=true` and other
vanilla/Paper/plugin network use (Spark upload, plugin update checks). The `/version` output links
to `https://github.com/YanIanZ/SourbyCraft/commit/<commit>` (`AuroraVersionFetcher.java`, was
`CanvasVersionFetcher` linking to CraftCanvasMC/Canvas until 2026-10-21) but fetches nothing.
The bStats phone-home is removed by the Paper baseline patch of `Metrics.java`.

## 6. Without network

**Build.** No offline path is wired or tested. Settings evaluation needs the pinned SourbyPatcher
jar in Maven Local; dependency resolution needs a populated Gradle cache (`--offline` then relies
on it, unverified for `applyAllPatches`, which clones Paper and downloads Mojang files on a cold
cache). CI cannot run without GitHub, the deploy keys and the repositories above.

**First boot.** `-Dsourbyclip.offline=true` (see [PRIVATE-TOOLCHAIN.md](PRIVATE-TOOLCHAIN.md))
makes the launcher use only SHA-256-verified cache entries and bundled libraries: a missing vanilla
jar or library fails with its path/coordinate and source (`DownloadContext.download`,
`Downloader`, `MavenDependencyResolver`). The same flag makes plugin provisioning refuse to
download (`PluginProvisioner.ensureJar`: "Offline mode requires a verified or operator-installed
... jar", logged, boot continues) and skips `ViaAutoUpdate`. It does not stop the auto-updater,
`/ping` geo download, `/speedtest` or plugin/vanilla traffic; disable those by their own settings.
Without the flag and without network, the launcher fails on the first missing file, and plugin
provisioning logs the failure and boots without the plugin. Once the cache is populated,
subsequent boots need no network for the launcher.

## 7. Hash-verification summary

| What | Hash-verified? | Where |
| --- | --- | --- |
| SourbyPatcher JAR / POM / `.module` | Yes (fails configuration) | `settings.gradle.kts:7-35`, `gradle.properties:43,47,50` |
| SourbyPatcher metadata declares `paperweightVersion` | Yes (equality check) | `scripts/private_toolchain.py:82-86` |
| SourbyClip JAR | Yes | `gradle.properties:42`, `scripts/private_toolchain.py:57,65-66` |
| Gradle distribution zip | Yes since PR #62 (`distributionSha256Sum`; the wrapper JAR is also validated in CI) | `gradle/wrapper/gradle-wrapper.properties:3`, `build.yml:38` |
| Paper upstream checkout | Pinned by commit (`paperRef`); not a separate content hash | `gradle.properties:33` |
| Docker base image (CI image build) | Pinned by digest | `docker/Dockerfile:19` |
| Mojang server jar (first boot) | Yes (expected SHA-256 before the staged file is published) | SourbyClip `DownloadContext` / `BoundedTransfer` at pin `e5de0f4b` (private, read from a local clone) |
| Externalized slim-jar libraries (first boot) | Yes, against `libraries.list` | `build.gradle.kts:53-68` comment, SourbyClip `MavenDependencyResolver` / `BoundedTransfer` at `e5de0f4b` |
| ViaVersion / ViaBackwards | Yes (SHA-256 + size, https-only) | `bootstrap/PluginProvisioner.java:61-70,236-240` |
| ProtocolLib 5.4.0 | Yes (SHA-256 + size) | `bootstrap/ProtocolLibProvisioner.java:13-16`, `build-data/protocollib-pin.json:2-15` |
| Public Maven deps (Maven Central / PaperMC / plugin portal) | No (Gradle checksum-less resolution) | none |
