# F0 — Folia Base Swap (SourbyPatcher + Luminol 26.2)

**Date:** 2026-07-10
**Branch:** `release/26.2` (hard pivot — replaces the Paper survival fork)
**Status:** design, pending user review
**Parent:** `project_26_2_folia_replatform` (memory) — full Folia re-platform roadmap

---

## 1. Context

`release/26.2` is currently a **Paper** fork (stock `io.papermc.paperweight.patcher 2.0.0-beta.21`,
`upstreams.paper`, `paperRef` pinned to a Paper 26.2 commit). It carries 60 gameplay/perf patches,
a self-tuning perf-engine, a native modloader (ML1 SourbyMod), anti-xray, stacker hardening, etc.

The re-platform pivots this branch to a **Folia** base (regionized multithreading) via **Luminol 26.2**
(a Folia downstream). F0 is the load-bearing foundation: nothing else validates until a Luminol 26.2
base builds and boots under SourbyCraft's toolchain.

### Upstream facts (all frozen)
- **Luminol** builds with a custom paperweight fork **`hyacinthusweight`** (Kotlin), not stock paperweight.
  Its upstream is **Folia** (`upstreams.register("folia")` + `foliaRef`) with a separate Paper-API patch repo.
  Modules `luminol-api` / `luminol-server`. Java 25. Publishes to `repo.bacteriawa.com`.
- **Luminol**, **LightingLuminol**, and **hyacinthusweight** are **all archived 2026-07-09/07-10** — read-only, frozen.
- Consequence: SourbyCraft becomes the **sole maintainer** of the entire chain (patcher + Folia base).
  No upstream MC/security patches ever again. This is an accepted cost of the pivot.

### Chain change
- Today: `SourbyCraft (paperweight) → Paper`
- Target: `SourbyCraft (SourbyPatcher) → Luminol patches → Folia → Paper`

---

## 2. Goals / Non-goals

### Goals (F0)
1. **SourbyPatcher** — own patcher plugin (fork of hyacinthusweight), rebranded, published to mavenLocal,
   **with SourbyLoader slimming built in** so the produced jar ships **< 35 MB**.
2. **Luminol 26.2 base** — forked wholesale, modules renamed `luminol-*` → `sourbycraft-*`, built with SourbyPatcher.
3. **Branded, zero-gameplay-patch Folia jar** that **boots** on TestServer-mojmap **and is < 35 MB**.
4. **Modloader total-strip** — ML1 SourbyMod loader + `mods/` scaffolding not carried into the new base.

### Non-goals (deferred)
- Rebasing the 60 gameplay patches → **F1**.
- Perf-engine per-region rewrite → **F2**.
- SWM/ASP → Folia world tech + skyblock core → **F3**.
- CI / TestServer harness refresh / release plumbing → **F5**.
- LightingLuminol approach port → **removed** (Luminol wholesale already carries compat + TpsBar).

> **Note:** SourbyLoader slimming was originally deferred to F5. Per user, it moves **into SourbyPatcher (F0a)**
> so the Folia base jar is < 35 MB from the start (matching the prior ~31 MB Paper baseline), not re-plumbed later.

---

## 3. Approach — C: Fork Luminol 26.2 wholesale + rebrand

Rejected alternatives:
- **A. Chain-downstream** (SourbyPatcher consumes Luminol as an upstream ref): fragile — depends on the
  archived third-party `bacteriawa.com` Maven and mixed patcher metadata. ✗
- **B. Mirror Luminol's build by hand** (re-register Folia upstream + re-vendor Luminol patch set from
  scratch): correct chain but error-prone hand-rebuild. ✗

**C (chosen):** take the archived Luminol 26.2 repo as the new base. Its build already wires the Folia
upstream and produces a working Folia jar. Because every upstream is frozen, vendoring the whole Luminol
build is safe and reproducible — no moving target. We swap its patcher (hyacinthusweight → SourbyPatcher),
rebrand modules, and pin refs. Least interop fighting; start from a known-good Folia build.

---

## 4. F0a — SourbyPatcher

**What:** fork of `hyacinthusweight` (which forks stock paperweight). Kotlin Gradle plugin. Keeps
hyacinthusweight's non-Paper upstream support (`upstreams.register("folia")`) — that capability is why we
fork hyacinthusweight and **not** stock paperweight.

**Rebrand:**
- Plugin ids `moe.luminolmc.hyacinthusweight.patcher` / `.core` → `dev.iyanz.sourbypatcher.patcher` / `.core`
  (and `.userdev` if present).
- Group/coords → `dev.iyanz.sourbypatcher:*`.
- Internal package rename `moe.luminolmc.hyacinthusweight` → `dev.iyanz.sourbypatcher`.
- Task-name prefixes / extension names carrying the `hyacinthusweight` brand → `sourbypatcher`.

**Publish:** `./gradlew publishToMavenLocal` → consumed by F0b via `mavenLocal()` in `pluginManagement`.
(Own Maven repo publish deferred; mavenLocal is enough to unblock F0b and CI later.)

**Source of truth:** vendor the hyacinthusweight source into a new top-level module/repo dir
(`sourbypatcher/`) so the fork is versioned inside SourbyCraft — no dependency on the archived upstream repo.

### SourbyLoader slimming (built into SourbyPatcher)

The existing Paper build slims the jar via a `createSlimPaperclipJar` task + an `externalLibs` list
(zstd, adventure, configurate, snakeyaml, jline) that SourbyLoader downloads lazily at first boot
(prior result: ~40 MB → ~31 MB). F0 **promotes this into the patcher** so any SourbyPatcher-built fork is
slim by construction.

- Port the slimming logic (`createSlimPaperclipJar` strip + bootstrap-manifest injection + `externalLibs`
  paperclipPath/downloadUrl model) from `build.gradle.kts` into SourbyPatcher as a first-class task
  (e.g. `slimPaperclipJar`) plus a DSL to declare the external-lib set.
- SourbyLoader bootstrap classes (first-boot lazy resolver) ship with the patcher / are injected into the
  slim jar as today (top-level `.class` so the JVM resolves `Main-Class`).
- Folia's own lib set may differ from Paper's — the external-lib list is **configurable per fork**, tuned
  in F0b against the actual Luminol/Folia dependency tree to hit the < 35 MB target.

**Acceptance:** `publishToMavenLocal` succeeds; a throwaway consumer project applying
`dev.iyanz.sourbypatcher.patcher` resolves, its `applyPatches` / `rebuildPatches` **and `slimPaperclipJar`**
tasks register.

---

## 5. F0b — Luminol 26.2 base swap

**Vendor Luminol 26.2:** import the archived `dev/26.2` tree (build scripts, `luminol-*` modules,
Luminol patch set, Folia upstream config, gradle wrapper as needed).

**Rebrand:**
- Modules `luminol-api` / `luminol-server` → `sourbycraft-api` / `sourbycraft-server`
  (align with existing SourbyCraft module names).
- Plugin application `hyacinthusweight.*` → `sourbypatcher.*` (from F0a).
- Group `dev.iyanz.sourbycraft`, version coordinates 26.2 (reuse existing `gradle.properties` scheme:
  `mcVersion` / `apiVersion` / `releaseVersion` = 26.2, `codename` = `metal`).
- Branding strings / brand-info patch (server list, `/version`, startup) → SourbyCraft.

**Pin refs (all frozen):**
- `foliaRef` → the Folia 26.2 commit Luminol's `dev/26.2` pinned.
- Paper-API ref → whatever Luminol pinned.
- Record all pinned SHAs in `gradle.properties` with a comment noting the archive date.

**Modloader total-strip:** do **not** port ML1 (SourbyMod loader, `ModuleRegistry`, `ModContext`,
`ModDescriptor`, `SourbyMod`, `mods/` loader, bootstrap feature patch, `sourbymod.yml`). Under approach C
this is free — the base starts clean; strip means "exclude from the survivor set + remove any
scaffolding/config references." Confirm no dangling refs in `SourbyCraftConfig` / settings.

**Repositories:** replace archived `bacteriawa.com` upstream artifacts with local/vendored equivalents
where possible; if a runtime artifact is only on `bacteriawa.com`, mirror it into the repo or `libraries/`.

**Acceptance:**
- `./gradlew applyPatches` (SourbyPatcher) succeeds against the Folia upstream.
- Full build produces a SourbyCraft-branded Folia paperclip jar via `slimPaperclipJar`, **< 35 MB**.
- Jar **boots on TestServer-mojmap** to a running server prompt; SourbyLoader resolves external libs on
  first boot; `/version` shows SourbyCraft 26.2 (Folia); no ML1 / `mods/` log lines; regionized threading
  active (Folia startup banner / region scheduler present).

---

## 6. Risks

| Risk | Mitigation |
|---|---|
| hyacinthusweight rebrand misses an internal id → plugin fails to resolve | Grep-driven rename; throwaway-consumer smoke before F0b |
| Folia/Paper artifacts only on archived `bacteriawa.com` | Mirror needed jars into repo `libraries/`; pin exact SHAs |
| Luminol build assumes hyacinthusweight-specific task wiring | SourbyPatcher keeps task names/behavior identical — rebrand only, no logic change |
| Boot fails on TestServer (Folia ≠ Paper runtime) | TestServer harness may need Folia-aware run args; treat as F0b acceptance blocker, not silent skip |
| Version-scheme drift (Luminol vs SourbyCraft gradle.properties) | Reuse SourbyCraft's existing property names; map Luminol props onto them |

---

## 7. Execution (parallel agents — implementation phase only)

Per `subagent-dispatch-format.md`, dispatch on the most capable model (opus). F0a and F0b have a hard
dependency (F0b consumes F0a's published plugin), so the top level is largely sequential, but sub-tasks
fan out:

- **F0a track:** (1) vendor hyacinthusweight source, (2) rename plugin ids/packages, (3) publish + smoke.
- **F0b track (after F0a):** parallelizable sub-agents — (a) module rename + gradle wiring,
  (b) branding patches, (c) ref pinning + artifact mirroring, (d) modloader-strip verification.
- Each task returns per the strict return contract; single fix-agent handles Critical+Important; durable ledger.

Note: the harness Agent tool exposes `opus` (= Opus 4.8) as the capable tier; Opus 4.7 is not separately
selectable, so the fleet runs on Opus 4.8.

---

## 8. Definition of done (F0)

1. `sourbypatcher/` builds + `publishToMavenLocal` green; `slimPaperclipJar` task present.
2. `release/26.2` builds a SourbyCraft-branded **Folia** jar via SourbyPatcher, **< 35 MB**.
3. Jar boots on TestServer-mojmap; SourbyLoader resolves libs first-boot; `/version` = SourbyCraft 26.2 Folia;
   Folia region scheduler live.
4. No modloader (ML1 / `mods/`) code or config in the tree.
5. Zero SourbyCraft gameplay patches applied (F1 starts from this clean Folia base).
