# F0 — Folia Base Swap (SourbyPatcher + Luminol 26.2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hard-pivot `release/26.2` from a Paper fork to a Folia base by forking `hyacinthusweight`→**SourbyPatcher** (own patcher, SourbyLoader slimming built in) and forking **Luminol 26.2** wholesale into a SourbyCraft-branded, modloader-free, sub-35 MB Folia jar that boots.

**Architecture:** Approach C (wholesale fork). SourbyPatcher is a minimal rebrand of `hyacinthusweight` published to mavenLocal. The Luminol `dev/26.2` tree is vendored onto `release/26.2` (modules `luminol-*`→`sourbycraft-*`), built with SourbyPatcher against a pinned Folia ref. Legacy Paper patches are preserved by a git tag for later rebase (F1+). No SourbyCraft gameplay patches are applied in F0 — this produces the clean Folia base F1 rebases onto.

**Tech Stack:** Kotlin (patcher), paperweight/hyacinthusweight patcher model, Gradle 9.4.1, JDK 25, Folia (regionized MC 26.2), hyacinthusclip 3.0.18 (paperclip fork), MaplePile (leafpile fork).

## Global Constraints

- **Build JDK:** 25 (`options.release = 25`, `JavaLanguageVersion.of(25)`). Bytecode 25.
- **Gradle wrapper:** 9.4.1.
- **Version coords:** `mcVersion=26.2`, `apiVersion=26.2`, `releaseVersion=26.2`, `codename=metal`, `channel=STABLE`.
- **Groups:** server/api `dev.iyanz.sourbycraft`; patcher `dev.iyanz.sourbypatcher`.
- **Folia pin (frozen):** `foliaRef=62034198de6d034bed025f34f48e8950b57c77ee` (PaperMC/Folia 26.2). Comment it as archived-pin.
- **mache:** `io.papermc:mache:26.2+build.1` (standard repos — no mirror needed).
- **Clip:** `hyacinthusclip 3.0.18` (coord `moe.luminolmc:hyacinthusclip:3.0.18`) — mirror into repo-local maven; do NOT rename the `hyacinthusclip` Gradle configuration.
- **Patcher fork rules:** KEEP Kotlin package `io.papermc.paperweight` unchanged; KEEP `PAPERWEIGHT_EXTENSION = "paperweight"`; KEEP `PAPERCLIP_CONFIG = "hyacinthusclip"`. Rebrand only group/ids/root-name/printId/POM/publish-repo.
- **No modloader:** zero ML1 (`SourbyMod`, `ModLoader`, `ModuleRegistry`, `ModContext`, `ModDescriptor`, `mods/` loader, `sourbymod.yml`, bootstrap feature patch) in the F0 output tree.
- **Zero gameplay patches:** F0 applies NO SourbyCraft gameplay/perf patches. The 60 legacy Paper patches are preserved by tag `paper-26.2-pre-folia` for F1.
- **Jar target:** final slim jar **< 35 MB** via SourbyLoader; fat jar boots first, slim second.
- **Verification:** manual boot on `TestServer-mojmap` (no JUnit, no smoke harness — user boots + inspects). Each task's "verify" is a build/boot with stated expected output.
- **Docs are gitignored:** commit specs/plans with `git add -f` (`docs/superpowers/` is in `.gitignore`).
- **Scratchpad recon (already cloned, reuse — do NOT re-clone):**
  - `…/scratchpad/hyacinthusweight` (baseline `publishToMavenLocal` verified green here).
  - `…/scratchpad/Luminol` (branch `dev/26.2`, HEAD `b05ac50`; `MaplePile` submodule NOT yet init'd).
  - Scratchpad root: `/private/tmp/claude-501/-Users-rheninxy-Sourby-SourbyCraft/e8a0b532-6ec3-4c64-922d-5ff7dd33a215/scratchpad`

---

## File Structure

New / changed top-level layout after F0:

- `sourbypatcher/` — **new**. Vendored fork of hyacinthusweight (modules `sourbypatcher-core/`, `paperweight-lib/` [keep name], `sourbypatcher-userdev/`, `buildSrc/`). Publishes `dev.iyanz.sourbypatcher.{core,patcher,userdev}` to mavenLocal. Adds the `slimPaperclipJar` task + `externalLibs` DSL.
- `sourby-maven/` — **new**. Repo-local file-based Maven mirror holding `moe.luminolmc:hyacinthusclip:3.0.18` (and any other archived-only coord).
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` — **replaced** with the vendored-Luminol equivalents, rebranded.
- `sourbycraft-api/` — **replaced** by rebranded `luminol-api` (12 paper-patches, `paper-patches/`, `src/`).
- `sourbycraft-server/` — **replaced** by rebranded `luminol-server` (17 `paper-patches/` + 82 `minecraft-patches/`, `src/`). NO ML1 source.
- `MaplePile/` — **new** submodule (or vendored) from LuminolMC/MaplePile.
- `patches/` (old Paper patch tree), old `build.gradle.kts` SourbyLoader block, ML1 source — **removed from working tree**, preserved in tag `paper-26.2-pre-folia`.
- `docs/superpowers/plans/2026-07-10-f0-folia-base-swap.md` — this plan.

---

## Track A — SourbyPatcher (Tasks 1–2; slim task added in Task 8)

### Task 1: Vendor hyacinthusweight + baseline publish

**Files:**
- Create: `sourbypatcher/` (vendored from `…/scratchpad/hyacinthusweight`, minus `.git`)

**Interfaces:**
- Produces: a buildable, unmodified patcher fork installed to `~/.m2` under group `moe.luminolmc.hyacinthusweight:2.0.15` (baseline proof; rebranded in Task 2).

- [ ] **Step 1: Copy the vendored tree in (no upstream git history)**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
rsync -a --exclude '.git' --exclude 'build' --exclude '.gradle' \
  /private/tmp/claude-501/-Users-rheninxy-Sourby-SourbyCraft/e8a0b532-6ec3-4c64-922d-5ff7dd33a215/scratchpad/hyacinthusweight/ \
  sourbypatcher/
```

- [ ] **Step 2: Baseline build — prove the fork compiles + publishes here**

Run:
```bash
cd /Users/rheninxy/Sourby/SourbyCraft/sourbypatcher
./gradlew publishToMavenLocal --no-daemon --stacktrace -x test
```
Expected: `BUILD SUCCESSFUL`. Artifacts appear under `~/.m2/repository/moe/luminolmc/hyacinthusweight/`.

- [ ] **Step 3: Commit**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git add sourbypatcher
git commit -m "F0a: vendor hyacinthusweight as sourbypatcher base (unmodified baseline)"
```

---

### Task 2: Rebrand to SourbyPatcher (minimal, low-risk set)

**Files:**
- Modify: `sourbypatcher/gradle.properties:1`
- Modify: `sourbypatcher/settings.gradle.kts:5,7`
- Modify: `sourbypatcher/buildSrc/src/main/kotlin/utils.kt:18-21`
- Modify: `sourbypatcher/buildSrc/src/main/kotlin/config-publish.gradle.kts:55-56,104-116,128,131-132`
- Modify: `sourbypatcher/hyacinthusweight-core/src/main/kotlin/io/papermc/paperweight/core/PaperweightCore.kt:64`
- Modify: `sourbypatcher/hyacinthusweight-core/src/main/kotlin/io/papermc/paperweight/patcher/PaperweightPatcher.kt:40`
- Rename: dirs `sourbypatcher/hyacinthusweight-core/`→`sourbypatcher/sourbypatcher-core/`, `hyacinthusweight-userdev/`→`sourbypatcher-userdev/` (keep `paperweight-lib/`)

**Interfaces:**
- Produces: plugin ids `dev.iyanz.sourbypatcher.core` / `.patcher` / `.userdev` at version `2.0.15`, resolvable from mavenLocal. **DSL block name stays `paperweight { }`**; **`hyacinthusclip` config name stays**.

- [ ] **Step 1: Group + root name**

`sourbypatcher/gradle.properties:1`: `group = moe.luminolmc.hyacinthusweight` → `group = dev.iyanz.sourbypatcher`
`sourbypatcher/settings.gradle.kts:5`: `rootProject.name = "hyacinthusweight"` → `"sourbypatcher"`

- [ ] **Step 2: Rename module dirs + includes**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft/sourbypatcher
git mv hyacinthusweight-core sourbypatcher-core
git mv hyacinthusweight-userdev sourbypatcher-userdev
```
`settings.gradle.kts:7`: `include("hyacinthusweight-core", "paperweight-lib", "hyacinthusweight-userdev")` → `include("sourbypatcher-core", "paperweight-lib", "sourbypatcher-userdev")`
Update any `project(":hyacinthusweight-core")` / `project(":hyacinthusweight-userdev")` refs in the two module `build.gradle.kts` files to the new Gradle paths (grep `hyacinthusweight-` inside `sourbypatcher/`).

- [ ] **Step 3: Plugin id + display in the setupPlugin helper**

`sourbypatcher/buildSrc/src/main/kotlin/utils.kt`:
- `:18` `plugins.register("hyacinthusweight-$prefix")` → `plugins.register("sourbypatcher-$prefix")`
- `:19` `id = "moe.luminolmc.hyacinthusweight.$prefix"` → `id = "dev.iyanz.sourbypatcher.$prefix"`
- `:20` `displayName = "hyacinthusweight $prefix"` → `displayName = "sourbypatcher $prefix"`
- `:21` `tags.set(listOf("paper", "minecraft", "hyacinthus"))` → `tags.set(listOf("paper", "minecraft", "sourbypatcher"))`

- [ ] **Step 4: printId startup strings + POM/publish metadata**

`PaperweightCore.kt:64` `"hyacinthusweight-core"` → `"sourbypatcher-core"`
`PaperweightPatcher.kt:40` `"hyacinthusweight-patcher"` → `"sourbypatcher-patcher"`
`config-publish.gradle.kts`: `:55-56` website/vcsUrl → your GH slug (`https://github.com/iyanz/sourbycraft` or the SourbyCraft repo); `:128` `repoPath = "LuminolMC/hyacinthusweight"` → `"iyanz/sourbycraft"`; `:131` POM `name.set("hyacinthusweight")` → `"sourbypatcher"`; `:132` description → `"Gradle plugin for the SourbyCraft project"`.

- [ ] **Step 5: Drop bacteriawa remote publish — mavenLocal only**

In `config-publish.gradle.kts:101-116`, delete (or comment) the `repositories { maven("https://repo.bacteriawa.com/...") { name = "Bacteriawa" … } }` remote block so `publish` is a no-op and only `publishToMavenLocal` is used. Do NOT touch the `maven-publish` publication itself.

- [ ] **Step 6: Rebuild + publish rebranded plugin**

Run:
```bash
cd /Users/rheninxy/Sourby/SourbyCraft/sourbypatcher
./gradlew publishToMavenLocal --no-daemon --stacktrace -x test
```
Expected: `BUILD SUCCESSFUL`. Verify new markers exist:
```bash
ls ~/.m2/repository/dev/iyanz/sourbypatcher/dev.iyanz.sourbypatcher.patcher/2.0.15/
```
Expected: a `*.gradle.plugin` pom present.

- [ ] **Step 7: Commit**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git add sourbypatcher
git commit -m "F0a: rebrand hyacinthusweight -> SourbyPatcher (dev.iyanz.sourbypatcher, mavenLocal)"
```

---

## Track B — Luminol 26.2 base swap (Tasks 3–8)

### Task 3: Safeguard — tag the pre-Folia Paper tree (MUST run before any destructive step)

**Files:** none (git tag only).

**Interfaces:**
- Produces: tag `paper-26.2-pre-folia` preserving the 60 gameplay patches, perf-engine source, ML1, and the SourbyLoader/`SourbyBootstrap` implementation for F1/F2 rebase + Task 8 port.

- [ ] **Step 1: Confirm clean tree + tag**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git status --short          # expect only sourbypatcher/ committed above; nothing else dirty
git tag -a paper-26.2-pre-folia -m "Paper 26.2 survival fork state before Folia re-platform (60 patches, perf-engine, ML1, SourbyLoader)"
git push origin paper-26.2-pre-folia
git tag -l paper-26.2-pre-folia   # expect the tag listed
```
Expected: tag created + pushed. This is the recovery point; do not proceed until it exists.

---

### Task 4: Lay down the Luminol build skeleton (replace root build config)

**Files:**
- Replace: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` (root) with vendored-Luminol equivalents
- Create: `MaplePile/` (submodule), `sourby-maven/` (empty for now)
- Remove: old `patches/` tree, old SourbyLoader block (preserved in tag)

**Interfaces:**
- Consumes: SourbyPatcher plugin from mavenLocal (Task 2).
- Produces: a root build that resolves the SourbyPatcher plugin and declares the Folia upstream + `sourbycraft-api`/`sourbycraft-server` modules.

- [ ] **Step 1: Remove the Paper-era build files + patch tree from the working tree**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git rm -r patches
git rm build.gradle.kts settings.gradle.kts gradle.properties
# ML1 + old server/api modules are replaced wholesale in Task 5; remove now to avoid stale refs:
git rm -r sourbycraft-api sourbycraft-server sourbycraft-swm-api swm-plugin 2>/dev/null || true
```

- [ ] **Step 2: Copy Luminol root build files in**

```bash
L=/private/tmp/claude-501/-Users-rheninxy-Sourby-SourbyCraft/e8a0b532-6ec3-4c64-922d-5ff7dd33a215/scratchpad/Luminol
cp "$L/build.gradle.kts" "$L/settings.gradle.kts" "$L/gradle.properties" .
cp -r "$L/luminol-api" ./luminol-api
cp -r "$L/luminol-server" ./luminol-server
```

- [ ] **Step 3: Bring in MaplePile submodule**

```bash
git submodule add https://github.com/LuminolMC/MaplePile MaplePile
git submodule update --init --recursive
```
Expected: `MaplePile/` populated.

- [ ] **Step 4: Sanity — configuration resolves (patcher plugin found)**

Run:
```bash
./gradlew help --no-daemon --stacktrace
```
Expected: `BUILD SUCCESSFUL` (plugin `moe.luminolmc.hyacinthusweight.*` still referenced here — resolves because the vendored files are unmodified; Task 5 swaps ids). If it FAILS to find the hyacinthusweight plugin (bacteriawa offline), that is expected — proceed to Task 5 which swaps to SourbyPatcher, then re-run.

- [ ] **Step 5: Commit the skeleton**

```bash
git add -A
git commit -m "F0b: replace Paper build tree with vendored Luminol 26.2 skeleton (pre-rebrand)"
```

---

### Task 5: Swap patcher→SourbyPatcher, rename modules, pin refs, mirror clip

**Files:**
- Modify: `settings.gradle.kts` (plugin ids, repos, includes, root name)
- Modify: `build.gradle.kts` (patcher id, patchFile/patchDir/patchRepo paths, drop bacteriawa)
- Modify: `gradle.properties` (group, codename, add SourbyPatcher version, keep foliaRef)
- Rename: `luminol-api/`→`sourbycraft-api/`, `luminol-server/`→`sourbycraft-server/`
- Modify: `sourbycraft-server/build.gradle.kts.patch`, `sourbycraft-api/build.gradle.kts.patch` (ids, fork name, patch dirs, clip coord, manifest)
- Create: `sourby-maven/moe/luminolmc/hyacinthusclip/3.0.18/…` (mirrored clip)

**Interfaces:**
- Consumes: `dev.iyanz.sourbypatcher.patcher` (mavenLocal), `foliaRef` pin.
- Produces: `./gradlew applyPatches` green against the Folia upstream, producing generated `folia-api`/`folia-server`/`paper-api` sources.

- [ ] **Step 1: Rename module dirs**

```bash
git mv luminol-api sourbycraft-api
git mv luminol-server sourbycraft-server
```

- [ ] **Step 2: settings.gradle.kts**

- Plugin ids: `id("moe.luminolmc.hyacinthusweight.patcher")` → `id("dev.iyanz.sourbypatcher.patcher")`; same for `.core`.
- Repos: remove `maven("https://repo.bacteriawa.com/repository/maven-public/")`; add `mavenLocal()` (for SourbyPatcher) and `maven { url = uri("${rootDir}/sourby-maven") }` (for the mirrored clip).
- Module loop: `listOf("luminol-api", "luminol-server")` → `listOf("sourbycraft-api", "sourbycraft-server")`.
- `rootProject.name = "luminol"` → `"sourbycraft"`.
- Keep `include("MaplePile")`.

- [ ] **Step 3: build.gradle.kts (root)**

- `id("moe.luminolmc.hyacinthusweight.patcher")` → `id("dev.iyanz.sourbypatcher.patcher")`.
- `patchFile { outputFile=file("luminol-server/build.gradle.kts"); patchFile=file("luminol-server/build.gradle.kts.patch") }` → `sourbycraft-server/…`.
- `patchFile { outputFile=file("luminol-api/build.gradle.kts"); patchFile=file("luminol-api/build.gradle.kts.patch") }` → `sourbycraft-api/…`.
- `patchRepo("paperApi") { patchesDir=file("luminol-api/paper-patches") … }` → `sourbycraft-api/paper-patches`.
- `patchDir("foliaApi") { patchesDir=file("luminol-api/folia-patches") … }` → `sourbycraft-api/folia-patches`.
- Remove any bacteriawa repo/publish blocks.
- Keep `upstreams.register("folia") { repo = github("PaperMC","Folia"); ref = providers.gradleProperty("foliaRef") }` unchanged.

- [ ] **Step 4: gradle.properties**

Set:
```
group=dev.iyanz.sourbycraft
mcVersion=26.2
apiVersion=26.2
releaseVersion=26.2
codename=metal
channel=STABLE
clipVersion=3.0.18
weightVersion=2.0.15          # SourbyPatcher version (mavenLocal)
foliaRef=62034198de6d034bed025f34f48e8950b57c77ee   # PIN: Folia 26.2 (archived upstream, frozen)
org.gradle.configuration-cache=true
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.vfs.watch=false
```

- [ ] **Step 5: sourbycraft-server/build.gradle.kts.patch — ids, fork name, patch dirs, clip, manifest**

- `id("moe.luminolmc.hyacinthusweight.core")` → `id("dev.iyanz.sourbypatcher.core")`.
- `hyacinthusclip("moe.luminolmc:hyacinthusclip:${clipVersion}")` → keep the `hyacinthusclip(...)` config name; keep coord `moe.luminolmc:hyacinthusclip:3.0.18` (resolved from `sourby-maven` mirror in Step 8).
- `forks.register("luminol")` → `forks.register("sourbycraft")`; update the two later references (activeFork / project refs) from `luminol` → `sourbycraft`.
- `patchesDir = rootDirectory.dir("luminol-server/paper-patches")` → `sourbycraft-server/paper-patches`.
- **Reconcile the stale NMS dir:** `patchesDir = rootDirectory.dir("luminol-server/folia-patches")` → `sourbycraft-server/minecraft-patches` (the real 82-patch NMS dir on disk).
- `implementation(project(":folia-api"))` / `project(":luminol-api")` → `project(":sourbycraft-api")`; keep `project(":MaplePile")`.
- Manifest attrs: `Implementation-Title`/`Specification-Title` `"Luminol"` → `"SourbyCraft"`; `Specification-Vendor "LuminoLMC"` → `"dev.iyanz"`; `Brand-Id "luminolmc:luminol"` → `"sourbycraft:sourbycraft"`; `Brand-Name "Luminol"` → `"SourbyCraft"`.
- Remove bacteriawa from `libraryRepositories`.

- [ ] **Step 6: sourbycraft-api/build.gradle.kts.patch**

Path-rename only (no brand strings). Update any `luminol-api` self-references to `sourbycraft-api`.

- [ ] **Step 7: Mirror hyacinthusclip 3.0.18 into sourby-maven**

Try the archived Maven first; if reachable, vendor it durably:
```bash
mkdir -p sourby-maven
cd /Users/rheninxy/Sourby/SourbyCraft
for ext in jar pom; do
  curl -fSL "https://repo.bacteriawa.com/repository/maven-public/moe/luminolmc/hyacinthusclip/3.0.18/hyacinthusclip-3.0.18.$ext" \
    --create-dirs -o "sourby-maven/moe/luminolmc/hyacinthusclip/3.0.18/hyacinthusclip-3.0.18.$ext"
done
ls -la sourby-maven/moe/luminolmc/hyacinthusclip/3.0.18/
```
Expected: `hyacinthusclip-3.0.18.jar` + `.pom` present.
**Fallback (if bacteriawa is offline / 404):** clone + build the clip fork from source (`https://github.com/LuminolMC/hyacinthusclip` if present) with `publishToMavenLocal`, then add `mavenLocal()` resolution — record which path was used in the commit message. This is the known risk item.

- [ ] **Step 8: applyPatches**

Run:
```bash
./gradlew applyPatches --no-daemon --stacktrace
```
Expected: `BUILD SUCCESSFUL`; generated `sourbycraft-server/src` (patched Folia) + `paper-api`/`folia-api` appear. If a patch fails to apply, resolve per hyacinthusweight's reject workflow, re-run, and note the resolution in the commit.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "F0b: swap to SourbyPatcher, rename modules -> sourbycraft-*, pin foliaRef, mirror hyacinthusclip; applyPatches green"
```

---

### Task 6: Rebrand user-facing strings + regenerate rebrand patches

**Files:**
- Modify (via patch regen): `sourbycraft-server/paper-patches/features/0001-Rebrand-to-Luminol.patch` (→ SourbyCraft) touching `ServerBuildInfoImpl.java`, `ServerBuildInfo.java`, `PaperVersionFetcher.java`, `Metrics.java`, `WatchdogThread.java`, `resources/logo.png`
- Modify: `sourbycraft-api/paper-patches/features/0001-Rebrand-to-Luminol.patch`
- Modify (live source): `sourbycraft-server/src/main/java/me/earthme/luminol/config/modules/misc/ServerModNameConfig.java:11`, `config/ConfigManager.java:27`

**Interfaces:**
- Produces: `/version`, server-list brand, watchdog URL, bStats name, config namespace = SourbyCraft.

- [ ] **Step 1: Edit generated sources, then rebuild the rebrand patches**

Edit the generated tree (after `applyPatches`), replacing brand values:
- `ServerBuildInfoImpl.java`: `BRAND_LUMINOL_NAME = "Luminol"` → `"SourbyCraft"`.
- `ServerBuildInfo.java`: `Key.key("luminolmc", "luminol")` → `Key.key("sourbycraft", "sourbycraft")`.
- `PaperVersionFetcher.java`: `DOWNLOAD_PAGE`/`REPOSITORY` → the SourbyCraft GitHub slug.
- `Metrics.java`: `new Metrics("Luminol", …)` → `"SourbyCraft"`; `"git-Luminol-%s-%s"` → `"git-SourbyCraft-%s-%s"`; `SimplePie("luminol_version", …)` → `"sourbycraft_version"`.
- `WatchdogThread.java`: issues URL → SourbyCraft repo issues.
- `resources/logo.png`: replace with SourbyCraft splash (reuse `README` banner asset).
- `ServerModNameConfig.java:11`: `serverModName = "Luminol"` → `"SourbyCraft"`.
- `ConfigManager.java:27`: `registerConfig("luminol", …)` → `registerConfig("sourbycraft", …)` (changes config file to `sourbycraft_global_config.toml` + folder `sourbycraft_config`; update `KaiijuEntityLimits.java:41` `new File("luminol_config")` → `"sourbycraft_config"`).

Then rebuild patches:
```bash
./gradlew rebuildPatches --no-daemon --stacktrace
```
Expected: `BUILD SUCCESSFUL`; the `0001-Rebrand-*` patches now carry SourbyCraft strings. (Optionally rename the patch files from `-to-Luminol` to `-to-SourbyCraft`.)

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "F0b: rebrand user-facing strings + config namespace Luminol -> SourbyCraft"
```

---

### Task 7: Build the fat Folia jar + boot on TestServer-mojmap

**Files:** none (build + manual boot).

**Interfaces:**
- Consumes: everything from Tasks 4–6.
- Produces: a booting SourbyCraft-branded Folia server jar (fat; size not yet constrained).

- [ ] **Step 1: Build the server jar**

Run:
```bash
./gradlew createMojmapPaperclipJar --no-daemon --stacktrace
```
(Use the actual paperclip task name the server build exposes — from the vendored `build.gradle.kts.patch` `hyacinthusclip`/paperclip wiring; likely `createMojmapPaperclipJar` or `createReobfPaperclipJar`. List tasks with `./gradlew :sourbycraft-server:tasks --group paperweight` if unsure.)
Expected: `BUILD SUCCESSFUL`; a paperclip jar under `sourbycraft-server/build/libs/`.

- [ ] **Step 2: Boot on TestServer-mojmap (manual)**

Copy the jar into `TestServer/` (or `TestServer-mojmap/`), then:
```bash
cd /Users/rheninxy/Sourby/SourbyCraft/TestServer
java -Xmx2G -jar <sourbycraft-paperclip>.jar --nogui
```
Expected observations (user verifies):
- Startup shows `sourbypatcher-*` NOT emitted at runtime (that's build-time only); server brand shows SourbyCraft.
- Folia region scheduler active (Folia startup banner / "Regionised" threading log lines).
- `/version` → SourbyCraft 26.2 (Folia lineage).
- NO ML1 / `mods/` loader log lines.
- Reaches the `>` console prompt (fully booted).

- [ ] **Step 3: Commit any boot-fix deltas**

```bash
git add -A
git commit -m "F0b: fat Folia jar boots on TestServer (SourbyCraft brand, region scheduler, no modloader)"
```

---

### Task 8: SourbyLoader slim — build the slim task into SourbyPatcher, hit < 35 MB

**Files:**
- Create: `sourbypatcher/paperweight-lib/src/main/kotlin/io/papermc/paperweight/tasks/SlimPaperclipJar.kt` (ported slim task)
- Modify: `sourbypatcher/…/patcher/PaperweightPatcher.kt` (register `slimPaperclipJar` + `externalLibs` DSL on the extension)
- Reference (port from tag): `paper-26.2-pre-folia:build.gradle.kts` lines 11–430 (`LibSpec`, `externalLibs`, `createSlimPaperclipJar`, `SourbyBootstrap` extraction)
- Create: `sourbycraft-server` bootstrap source `dev/iyanz/sourbycraft/bootstrap/SourbyBootstrap.java` (extracted from tag) via a feature patch
- Modify: root `build.gradle.kts` (apply `slimPaperclipJar`, declare Folia-tuned `externalLibs`)

**Interfaces:**
- Consumes: the fat paperclip jar (Task 7) + `SourbyBootstrap` (extracted from tag).
- Produces: `slimPaperclipJar` task producing a **< 35 MB** jar that boots via SourbyLoader first-boot lib download.

- [ ] **Step 1: Extract the legacy SourbyLoader implementation from the tag**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git show paper-26.2-pre-folia:build.gradle.kts > /tmp/legacy-build.gradle.kts   # read lines 11-430
git show "paper-26.2-pre-folia:sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/SourbyBootstrap.java" > /tmp/SourbyBootstrap.java
```
Read both — these are the reference implementation for the slim task + bootstrap.

- [ ] **Step 2: Port the slim logic into a SourbyPatcher task**

Create `SlimPaperclipJar.kt` implementing the `createSlimPaperclipJar` behavior: take a fat paperclip jar input + an `externalLibs` list (`paperclipPath`/`downloadUrl` pairs), strip those entries, inject `META-INF/sourby-bootstrap-manifest.json`, rewrite `Main-Class` to `dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap`, and copy the bootstrap `.class` files to jar root. Expose `externalLibs` via the `paperweight { }` extension. (Straight port of legacy lines 11–430 into a task class.)

- [ ] **Step 3: Add SourbyBootstrap to the server module via a feature patch**

Place `SourbyBootstrap.java` (from Step 1) into the generated server tree, then `./gradlew rebuildPatches` to capture it as a `sourbycraft-server/minecraft-patches` feature patch. (This is the ONLY SourbyCraft source added in F0 — it is loader infra, not gameplay; gameplay stays zero.)

- [ ] **Step 4: Re-publish SourbyPatcher + declare Folia-tuned externalLibs**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft/sourbypatcher && ./gradlew publishToMavenLocal --no-daemon -x test
```
In root `build.gradle.kts`, declare `externalLibs` for Folia's actual heavy libs — start from the legacy set (zstd, adventure, configurate, snakeyaml, jline, sqlite, mysql, spark, Flare, protobuf, sentry) and adjust to the versions Folia 26.2 actually bundles (inspect the fat jar's `META-INF/libraries/` layout). Prune entries not present; keep only real, independently-downloadable libs.

- [ ] **Step 5: Build slim jar + verify size**

```bash
./gradlew slimPaperclipJar --no-daemon --stacktrace
ls -la sourbycraft-server/build/libs/*slim*.jar   # or the configured slim output
```
Expected: slim jar **< 35 MB** (`du -m` to confirm).

- [ ] **Step 6: Boot the slim jar (manual, SourbyLoader online path)**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft/TestServer
rm -rf libraries    # clean, force SourbyLoader to fetch
java -jar <sourbycraft-slim>.jar --nogui
```
Expected: SourbyLoader downloads the externalized libs on first boot, server reaches `>` prompt, Folia region scheduler live, `/version` = SourbyCraft 26.2.

- [ ] **Step 7: Commit**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git add -A
git commit -m "F0: SourbyLoader slim built into SourbyPatcher; Folia jar < 35MB boots via first-boot lib fetch"
```

---

## Self-Review

**Spec coverage:**
- SourbyPatcher (own patcher, hyacinthusweight fork) → Tasks 1–2. ✓
- SourbyLoader in patcher, < 35 MB → Task 8. ✓
- Luminol 26.2 wholesale fork + rebrand → Tasks 4–6. ✓
- Modloader total-strip → enforced by Global Constraints + Task 4 (`git rm` old modules, zero ML1 in vendored Luminol) + Task 7 boot check (no `mods/` lines). ✓
- Boots on Folia + `/version` brand + region scheduler → Task 7 + Task 8. ✓
- Zero gameplay patches + legacy preserved → Task 3 tag; F0 applies none. ✓
- All-upstreams-archived / bacteriawa risk → Task 5 Step 7 (clip mirror + fallback). ✓
- Approach C (wholesale fork) → whole Track B. ✓

**Placeholder scan:** No TBD/TODO. Two acknowledged unknowns are handled as explicit branches with fallbacks, not placeholders: (a) exact paperclip task name in Task 7 Step 1 (listed a discovery command); (b) hyacinthusclip availability in Task 5 Step 7 (fetch-then-fallback). Both are real-world discovery points, not hidden work.

**Type/name consistency:** plugin ids `dev.iyanz.sourbypatcher.{core,patcher,userdev}` consistent across Tasks 2/4/5. `foliaRef` SHA consistent. `hyacinthusclip` config name deliberately NOT renamed (Global Constraints + Task 5). `sourbycraft-api`/`sourbycraft-server` module names consistent Tasks 4–8. Tag name `paper-26.2-pre-folia` consistent Tasks 3/8.

---

## Execution Handoff

Recommended: **subagent-driven** — fresh opus subagent per task with review gates. Track A (Tasks 1–2) is a hard prerequisite for Track B. Tasks are largely sequential (each consumes the prior), so parallelism is limited within F0; the fleet parallelism pays off in F1 (60-patch triage). Per `subagent-dispatch-format.md`: file-handoff briefs, verbatim global constraints, strict return contract, per-task review, single fix-agent for Critical+Important, durable ledger, opus (4.8) model.
