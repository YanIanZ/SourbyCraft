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
| `https://services.gradle.org/distributions/gradle-9.8.0-bin.zip` | Gradle wrapper distribution (first `./gradlew` on a host) | `gradle/wrapper/gradle-wrapper.properties` | verified; no `distributionSha256Sum` is set, CI validates the wrapper JAR with `gradle/actions/wrapper-validation` |
| Gradle Plugin Portal (`gradlePluginPortal()`) | `io.papermc.paperweight.core` 2.0.0-beta.24, `org.gradle.toolchains.foojay-resolver-convention` 1.0.0, `io.papermc.fill.gradle` 1.0.12 | `settings.gradle.kts` `pluginManagement`/`plugins`; generated `sourbycraft-server/build.gradle.kts` | verified (declaration) |
| Maven Local (`~/.m2/repository` or `-Dmaven.repo.local`) | `dev.iyanz.sourbypatcher.*` plugin, exclusively | `settings.gradle.kts` | verified; settings evaluation **fails** unless `paper-toolchain-${patcherVersion}.jar` **and its `.pom`/`.module` metadata** exist there with SHA-256 `patcherSha256` / `patcherPomSha256` / `patcherModuleSha256` (`gradle.properties`); `scripts/private_toolchain.py` additionally requires the metadata to declare `paperweightVersion` |
| `https://repo.papermc.io/repository/maven-public/` | remaining plugin dependencies | `settings.gradle.kts` | verified |

Plugin resolution order: Plugin Portal, then Maven Local (SourbyPatcher only), then PaperMC.

**Foojay toolchains.** `foojay-resolver-convention` lets Gradle download a JDK 25 through the
foojay Disco API (`api.foojay.io`, plugin behaviour, unverified here) only when no local JDK 25
matches the toolchain request in root `build.gradle.kts` (`JavaLanguageVersion.of(25)`). CI
installs Temurin 25 with `actions/setup-java` first, so it normally does not download one.

## 2. Build: dependencies

| Remote | Used for | Declared in | Status |
| --- | --- | --- | --- |
| Maven Local | `dev.iyanz:sourbyclip` only (exclusive) | root `build.gradle.kts` `repositories` and `subprojects { repositories }` | verified; must hold `sourbyclip-${clipVersion}.jar` (pinned by `clipSha256`, checked by `scripts/private_toolchain.py`) |
| Maven Central (`mavenCentral()`) | all subprojects | root `build.gradle.kts` `subprojects`; `Metal/build.gradle.kts` | verified |
| `https://repo.papermc.io/repository/maven-public/` | Paper artifacts, `io.papermc:mache` | root `build.gradle.kts` (`paperMavenPublicUrl`) | verified |
| `file:${rootDir}/sourby-maven` | local file repository | root `build.gradle.kts` `subprojects` | verified; the directory does not exist in the tree, so it serves nothing |
| `https://libraries.minecraft.net/` | Mojang libraries for the opt-in test plugins (`-PincludeTestPlugin=true`) | `test-plugin/build.gradle.kts`, `legacy-test-plugin/build.gradle.kts` | verified |

Subproject order: Maven Local (sourbyclip only), Maven Central, PaperMC, `sourby-maven`.
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
| GitHub `YanIanZ/SourbyCraft` | checkout, `fetch-depth: 0` | verified |
| GitHub `YanIanZ/SourbyPatcher`, `YanIanZ/SourbyClip` (private) | checkout at the lock-file revisions with deploy keys `SOURBYPATCHER_DEPLOY_KEY` / `SOURBYCLIP_DEPLOY_KEY`; `scripts/private_toolchain.py --publish` then builds them (`gradlew test publishToMavenLocal`) | verified; without the secrets the job stops before any Gradle step |
| Repositories of the private builds | SourbyPatcher `paper-toolchain`: Maven Central + Plugin Portal (`paper-toolchain/build.gradle.kts`, `settings.gradle.kts`); its `buildSrc` adds PaperMC, `maven.neoforged.net`, `maven.fabricmc.net`. SourbyClip `java25`: Maven Central, `repo.spongepowered.org/maven/`, `repo.leavesmc.org/releases/` and `/snapshots/` (`java25/build.gradle.kts`) | verified at the locked revisions; whether `buildSrc` repositories are reached for the `paper-toolchain` project alone was not traced |
| GitHub Actions marketplace (`actions/checkout`, `actions/setup-java`, `gradle/actions/wrapper-validation`, `actions/upload-artifact`, `actions/download-artifact`) | pinned by 40-hex commit SHA | verified |
| Ubuntu apt mirrors | `apt-get install redis-server` for the AWF Redis tests | verified |
| Temurin download via `actions/setup-java` | JDK 25 | verified (declaration) |

## 4. First boot (SourbyClip launcher, then the server)

Order on a fresh host, as the code runs it:

1. **Vanilla server jar.** SourbyClip reads `META-INF/download-context` and downloads
   `https://piston-data.mojang.com/v1/objects/823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar`
   into the cache unless a SHA-256-valid copy is already there (`DownloadContext.download`,
   SourbyClip `java25/.../DownloadContext.java`). `-Dsourbyclip.downloadContext=<name>` selects
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

   The download is checked against the hash in `libraries.list`. Which libraries need this is
   decided at build time by `slimServerJar`'s `externalizeArtifactDirs` (root
   `build.gradle.kts`). `MavenDependencyResolver.getDefaultRepositories()` (Maven Central,
   Sonatype snapshots) exists but is not called on this path.
3. **Packet/legacy-client plugins**, before Paper's plugin scan (feature patch 0024 calls
   `PluginProvisioner.provisionJars`), each pinned by exact SHA-256 and size, HTTPS-only,
   redirects followed (`LibDownloader.HTTPS`), no mirror:
   - ProtocolLib: `https://github.com/dmulloy2/ProtocolLib/releases/download/dev-build/ProtocolLib.jar`
     (`ProtocolLibProvisioner.java`; toggle `[protocollib] auto-provision`). The `dev-build`
     tag is mutable upstream: once the asset changes, the pin rejects it until it is updated.
   - ViaVersion / ViaBackwards 5.10.0: `https://cdn.modrinth.com/data/P1OZGk5p/...` and
     `https://cdn.modrinth.com/data/NpvuJQoq/...` (`PluginProvisioner.java`; toggle
     `[viaversion] auto-provision`).

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
