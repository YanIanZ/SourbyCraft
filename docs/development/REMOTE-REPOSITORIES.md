# Remote repositories and network endpoints

Every remote host the build (local and CI) and the first server boot reach, the order in which
alternatives are tried, and what happens without network. Verified 2026-10-20 against the
working tree of branch `26.2` (HEAD `4e859950` plus uncommitted round-3 edits), the private
toolchain at the revisions pinned in `build-data/private-toolchain.lock.json`
(SourbyPatcher `1270d7e3`, SourbyClip `85b4dd70`, read from the local clones), and the local
`build/libs/SourbyCraft-slim.jar` of 2026-10-20 01:19.

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
| `https://services.gradle.org/distributions/gradle-9.8.0-bin.zip` | Gradle wrapper distribution (first `./gradlew` on a host) | `gradle/wrapper/gradle-wrapper.properties:3` | verified; no `distributionSha256Sum` is set, CI validates the wrapper JAR with `gradle/actions/wrapper-validation` (`build.yml:38-39`) |
| Gradle Plugin Portal (`gradlePluginPortal()`) | `io.papermc.paperweight.core` 2.0.0-beta.24, `org.gradle.toolchains.foojay-resolver-convention` 1.0.0, `io.papermc.fill.gradle` 1.0.12 | `settings.gradle.kts:37` (`pluginManagement`), `:49`, `:54`; generated `sourbycraft-server/build.gradle.kts` | verified (declaration) |
| Maven Local (`~/.m2/repository` or `-Dmaven.repo.local`) | `dev.iyanz.sourbypatcher.*` plugin, exclusively | `settings.gradle.kts:38-41` | verified; settings evaluation **fails** unless `paper-toolchain-${patcherVersion}.jar` **and its `.pom`/`.module` metadata** exist there with SHA-256 `patcherSha256` / `patcherPomSha256` / `patcherModuleSha256` (`gradle.properties`); `scripts/private_toolchain.py` additionally requires the metadata to declare `paperweightVersion` |
| `https://repo.papermc.io/repository/maven-public/` | remaining plugin dependencies | `settings.gradle.kts:42` | verified |

Plugin resolution order: Plugin Portal, then Maven Local (SourbyPatcher only), then PaperMC.
Plugin versions: `patcherVersion=3.0.0` (`gradle.properties:34`, plugin `settings.gradle.kts:46`),
`paperweightVersion=2.0.0-beta.24` (`gradle.properties:46`, `settings.gradle.kts:49`).

**Hash checks at settings time.** Settings evaluation fails closed, before any build task runs:
patcher JAR SHA-256 against `patcherSha256` (`settings.gradle.kts:7-15`, `gradle.properties:36`);
patcher POM and `.module` against `patcherPomSha256` / `patcherModuleSha256`
(`settings.gradle.kts:18-35`, `gradle.properties:40,43`). The pinned POM and `.module` must both
declare `paperweightVersion` (enforced by `scripts/private_toolchain.py:29-46,82-87`, run in CI
before SourbyCraft's Gradle steps; it builds the two private tools with Gradle first and checks their
metadata afterwards).

**Foojay toolchains.** `foojay-resolver-convention` lets Gradle download a JDK 25 through the
foojay Disco API (`api.foojay.io`, plugin behaviour, unverified here) only when no local JDK 25
matches the toolchain request in root `build.gradle.kts` (`JavaLanguageVersion.of(25)`). CI
installs Temurin 25 with `actions/setup-java` first (`build.yml:41-45`), so it normally does not download one.
The toolchain request is at `build.gradle.kts:208-212`.

**Private toolchain pins.** SourbyPatcher and SourbyClip are private repositories pinned in
`build-data/private-toolchain.lock.json:3-4` (SourbyPatcher `1270d7e3…`) and `:7-8` (SourbyClip
`85b4dd70…`). `scripts/private_toolchain.py --publish` refuses a checkout at the wrong revision or
with uncommitted changes (`:93-101`), runs `gradlew test publishToMavenLocal` (`:102-104`), and
verifies the installed JARs against `clipSha256` / `patcherSha256` (`:54-66`). CI uses read-only
deploy keys (`build.yml:63-82`). Neither coordinate has a remote Maven fallback
(`build.gradle.kts:13-18`, `settings.gradle.kts:38-41`).

## 2. Build: dependencies

| Remote | Used for | Declared in | Status |
| --- | --- | --- | --- |
| Maven Local | `dev.iyanz:sourbyclip` only (exclusive) | root `build.gradle.kts` `repositories` and `subprojects { repositories }` | verified; must hold `sourbyclip-${clipVersion}.jar` (pinned by `clipSha256`, checked by `scripts/private_toolchain.py`) |
| Maven Central (`mavenCentral()`) | all subprojects | root `build.gradle.kts` `subprojects`; `Metal/build.gradle.kts` | verified |
| `https://repo.papermc.io/repository/maven-public/` | Paper artifacts, `io.papermc:mache` | root `build.gradle.kts` (`paperMavenPublicUrl`) | verified |
| `file:${rootDir}/sourby-maven` | local file repository | root `build.gradle.kts` `subprojects` | verified; the directory does not exist in the tree, so it serves nothing |
| `https://libraries.minecraft.net/` | Mojang libraries for the opt-in test plugins (`-PincludeTestPlugin=true`) | `test-plugin/build.gradle.kts:4`, `legacy-test-plugin/build.gradle.kts:4` | verified |

Subproject order (`build.gradle.kts:214-222`): Maven Local (sourbyclip only, `:215-218`), Maven Central
(`:219`), PaperMC (`:220`), `sourby-maven` (`:221`; the directory does not exist, so it serves nothing).
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
| GitHub `YanIanZ/SourbyCraft` | checkout, `fetch-depth: 0` (`build.yml:36`) | verified |
| GitHub `YanIanZ/SourbyPatcher`, `YanIanZ/SourbyClip` (private) | checkout at the lock-file revisions with deploy keys `SOURBYPATCHER_DEPLOY_KEY` / `SOURBYCLIP_DEPLOY_KEY` (`build.yml:63-82`); `scripts/private_toolchain.py --publish` then builds them (`gradlew test publishToMavenLocal`) | verified; without the secrets the job stops before any Gradle step |
| Repositories of the private builds | SourbyPatcher `paper-toolchain`: Maven Central + Plugin Portal (`paper-toolchain/build.gradle.kts`, `settings.gradle.kts`); its `buildSrc` adds PaperMC, `maven.neoforged.net`, `maven.fabricmc.net`. SourbyClip `java25`: Maven Central, `repo.spongepowered.org/maven/`, `repo.leavesmc.org/releases/` and `/snapshots/` (`java25/build.gradle.kts`) | verified at the locked revisions; whether `buildSrc` repositories are reached for the `paper-toolchain` project alone was not traced |
| GitHub Actions marketplace (`actions/checkout`, `actions/setup-java`, `gradle/actions/wrapper-validation`, `actions/upload-artifact`, `actions/download-artifact`) | pinned by 40-hex commit SHA | verified |
| Ubuntu apt mirrors | `apt-get install redis-server` for the AWF Redis tests | verified |
| Temurin download via `actions/setup-java` | JDK 25 | verified (declaration) |

## 4. First boot (SourbyClip launcher, then the server)

Order on a fresh host, as the code runs it:

1. **Vanilla server jar.** SourbyClip reads `META-INF/download-context` and downloads
   `https://piston-data.mojang.com/v1/objects/823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar`
   into the cache unless a SHA-256-valid copy is already there (`DownloadContext.download`,
   SourbyClip `java25/.../DownloadContext.java`). The expected SHA-256 is checked before the cached
   copy is replaced; a failed HTTP, body or hash attempt keeps the previous file, and the 30 s
   connect/read timeouts apply (SourbyClip `DownloadContext` at the pinned revision `85b4dd70`,
   private repository; `docs/architecture/bootstrap-download-audit.md:59` describes the same behaviour for
   earlier, unpinned candidate revisions). `-Dsourbyclip.downloadContext=<name>` selects
   another packaged context; a name that is not packaged (e.g. `download-context-cn` today) falls
   back to the default (`Sourbyclip.findDownloadContext`). No mirror is tried after a failure.
2. **Libraries** from `META-INF/libraries.list` (`FileEntry.downloadFromMvnRepo`,
   `downloader/Downloader.java`), per entry: SHA-256-valid file in the cache, else the copy
   bundled in the jar or the vanilla bundle, else download by Maven coordinate trying, in order
   (`Sourbyclip.ALL_MAVEN_REPO_LINK_BASE`):
   1. `https://maven.aliyun.com/repository/central`
   2. `https://repo.papermc.io/repository/maven-public`
   3. `https://repo.menthamc.org/repository/maven-public`
   4. `https://repo.spongepowered.org/maven`

   The download is checked against the hash in `libraries.list` (`build.gradle.kts:52-65` comment).
   Which libraries need this is decided at build time by `slimServerJar`'s `externalizeArtifactDirs`
   (root `build.gradle.kts:74-94`; the strip is at `:96-156`, and a strip count of 0 fails the build
   at `:145-150`). `MavenDependencyResolver.getDefaultRepositories()` (Maven Central,
   Sonatype snapshots) exists but is not called on this path.
3. **Packet/legacy-client plugins**, before Paper's plugin scan (feature patch 0024 calls
   `PluginProvisioner.provisionJars`), each pinned by exact SHA-256 and size, HTTPS-only,
   redirects followed (`LibDownloader.HTTPS`), no mirror:
   - ProtocolLib 5.4.0 (tagged release, not `dev-build`):
     `https://github.com/dmulloy2/ProtocolLib/releases/download/5.4.0/ProtocolLib.jar`, SHA-256
     `ee2e7ab9…` and 10,251,186 bytes (`bootstrap/ProtocolLibProvisioner.java:13-16`; the same pin is
     in `build-data/protocollib-pin.json:2-15`; toggle `[protocollib] auto-provision`, read at
     `ProtocolLibProvisioner.java:21`).
   - ViaVersion 5.10.0 `https://cdn.modrinth.com/data/P1OZGk5p/versions/ruzmiBqe/ViaVersion-5.10.0.jar`
     (SHA-256 `e5a63f86…`, 6,434,343 bytes; `bootstrap/PluginProvisioner.java:57-62`) and ViaBackwards
     5.10.0 `https://cdn.modrinth.com/data/NpvuJQoq/versions/YjpKsm6j/ViaBackwards-5.10.0.jar`
     (SHA-256 `4699b6dd…`, 1,422,522 bytes; `:63-67`); toggle `[viaversion] auto-provision`.
     The https-only check is at `PluginProvisioner.java:207-210`; the offline refusal at `:205-206`.

   A failure is logged with source URL and target path and the boot continues without that plugin.
4. **Plugin libraries.** Plugins that declare `libraries:` are resolved by Paper's
   `MavenLibraryResolver` from `MAVEN_CENTRAL_DEFAULT_MIRROR`, default
   `https://repo.maven.apache.org/maven2` (materialized `paper-api/.../MavenLibraryResolver.java`,
   `LibraryLoader.java`; Paper behaviour, only when a plugin asks).

## 5. While running

| Remote | Trigger | Source | Fallback |
| --- | --- | --- | --- |
| `https://api.github.com/repos/<repo>/releases?per_page=100`, `<repo>` default `YanIanZ/SourbyCraft` (`misc.auto_update.repo`), then the release asset's `browser_download_url` and signature asset | auto-updater schedule (`misc.auto_update.enabled`, default `true`) and `/update` | `update/SourbyUpdater.java`, `AutoUpdateSettings.java`; see [AUTO-UPDATER.md](AUTO-UPDATER.md) | none; ETag conditional requests and a rate-limit backoff window, a failed check just ends |
| `https://api.modrinth.com/v2/project/<id>/version`, then the pinned `cdn.modrinth.com` URL | same cadence, after each updater check (`misc.auto_update.via_auto_update`, default `true`, and `viaversion.auto-provision`) | `update/ViaAutoUpdate.java` (being edited in round 3; re-check) | none; installs only the shipped pin |
| `https://download.db-ip.com/free/dbip-city-lite-YYYY-MM.mmdb.gz` | first `/ping` geo lookup without a local DB | `util/GeoUtil.java` | current month, then previous month; lookups degrade to unknown if both fail |
| `https://install.speedtest.net/app/cli/` | `/speedtest` | `command/SpeedtestCommand.java` (SHA-256 pinned archives) | none |

Not inventoried here: Mojang session/authentication servers for `online-mode=true` and other
vanilla/Paper/plugin network use (Spark upload, plugin update checks). The `/version` output links
to `https://github.com/CraftCanvasMC/Canvas/` (`CanvasVersionFetcher.java`) but fetches nothing.
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
| SourbyPatcher JAR / POM / `.module` | Yes (fails configuration) | `settings.gradle.kts:7-35`, `gradle.properties:36,40,43` |
| SourbyPatcher metadata declares `paperweightVersion` | Yes (equality check) | `scripts/private_toolchain.py:82-87` |
| SourbyClip JAR | Yes | `gradle.properties:35`, `scripts/private_toolchain.py:57,64-66` |
| Gradle distribution zip | No (`distributionSha256Sum` unset; wrapper JAR validated in CI) | `gradle/wrapper/gradle-wrapper.properties:3`, `build.yml:38-39` |
| Paper upstream checkout | Pinned by commit (`paperRef`); not a separate content hash | `gradle.properties:26` |
| Mojang server jar (first boot) | Yes (expected SHA-256 before replacing the cache) | SourbyClip `DownloadContext` at pin `85b4dd70` (private); audit line 59 covers earlier candidates |
| Externalized slim-jar libraries (first boot) | Yes, against `libraries.list` | `build.gradle.kts:52-65`, `AGENTS.md:65` |
| ViaVersion / ViaBackwards | Yes (SHA-256 + size, https-only) | `bootstrap/PluginProvisioner.java:57-67,207-210` |
| ProtocolLib 5.4.0 | Yes (SHA-256 + size) | `bootstrap/ProtocolLibProvisioner.java:13-16`, `build-data/protocollib-pin.json:2-15` |
| Public Maven deps (Maven Central / PaperMC / plugin portal) | No (Gradle checksum-less resolution) | none |
