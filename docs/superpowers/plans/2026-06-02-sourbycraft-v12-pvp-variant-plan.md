# SourbyCraft v12.0 Build-variant Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce two paperclip JARs (`SourbyCraft-12.0-REL.jar` and `SourbyCraft-PVP-12.0-REL.jar`) from one Gradle codebase via `-Pvariant=pvp|normal`. PVP variant adds 5 PvP-only NMS patches, proxy-backend defaults, plugin auto-installer manifest, "Lightning Fast Performance · Feature Rich" branding, and reformatted `/plugins`/`/pl` output.

**Architecture:** Variant selection at patch-apply time (PVP patches `9XXX-PVP-*` filtered out for normal build), variant-specific resources deep-merged from `variant-overlay/<variant>/` over baseline at packaging, runtime code reads ONLY the merged outputs — no variant branching in server code. Build metadata flows via `META-INF/sourbycraft-build.properties`.

**Tech Stack:** Gradle Kotlin DSL, paperweight-patcher 2.0 (Mojang mappings), Java 21 bytecode / Java 25 toolchain, JUnit 5 + Mockito, SnakeYAML.

**Spec:** `docs/superpowers/specs/2026-06-02-sourbycraft-v12-pvp-variant-design.md`

## Phase ordering (MUST follow)

Phases A → D → B → C → E → F → G → H.

Reason: Tasks D1+D2 create the `SourbyCraftConfig.ymlGet` loader + baseline
`sourbycraft.yml`. Every later task that reads config (C6, E4, F1, F2, F3, F4,
F5) depends on D2 existing. Phase B (Branding) only depends on BuildInfo, so it
can run between A and D, but doing D first gives you the config-read primitive
when you need it.

If executing inline, you may complete A1–A5 then jump to D1–D3 before doing B
(banner) and C (auto-install).

---

## Phase A — Build infrastructure (variant flag + Gradle wiring)

### Task A1: Add `variant` Gradle property + version bump

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Read current `gradle.properties`**

Run: `cat gradle.properties`
Note current values of `releaseVersion`, `codename`, `internalVersion`.

- [ ] **Step 2: Edit `gradle.properties`**

Replace lines:
```properties
releaseVersion = 10
codename = rel
internalVersion = v10-REL
```
With:
```properties
releaseVersion = 12
codename = rel
internalVersion = v12-REL

# Build variant: normal (general SMP) or pvp (PvP arena backend)
# Override on CLI: ./gradlew createMojmapPaperclipJar -Pvariant=pvp
variant = normal
```

- [ ] **Step 3: Verify property reads correctly**

Run: `./gradlew properties --no-daemon | grep -E '(variant|releaseVersion|internalVersion)'`
Expected output contains: `variant: normal`, `releaseVersion: 12`, `internalVersion: v12-REL`.

- [ ] **Step 4: Commit**

```bash
git add gradle.properties
git commit -m "build: v12.0 — add variant property, bump release to 12"
```

---

### Task A2: Wire variant property into `build.gradle.kts` patch filtering

**Files:**
- Modify: `build.gradle.kts` (PaperweightPatcherExtension block, near line 70)

- [ ] **Step 1: Read current `build.gradle.kts` PaperweightPatcherExtension block**

Run: `grep -n -A 20 'configure<PaperweightPatcherExtension>' build.gradle.kts`

- [ ] **Step 2: Add variant detection above the configure block**

Insert before `configure<PaperweightPatcherExtension> {` (around line 67):
```kotlin
// SourbyCraft v12 — build variant selection
val sourbycraftVariant = providers.gradleProperty("variant").getOrElse("normal")
val isPvpVariant = sourbycraftVariant == "pvp"

// Logged at configuration time so operators see which variant is building
logger.lifecycle("SourbyCraft variant: $sourbycraftVariant (PvP patches: ${if (isPvpVariant) "INCLUDED" else "EXCLUDED"})")
```

- [ ] **Step 3: Modify the `patchDir("paperApi")` and `patchDir("paperServer")` blocks**

Find the existing `patchDir("paperApi") { ... }` block and add `excludes` line:
```kotlin
patchDir("paperApi") {
    upstreamPath = "paper-api"
    patchesDir = file("patches/api")
    featurePatchDir = patchesDir.dir(".")
    outputDir = file("paper-api")
    excludes = if (isPvpVariant) setOf("build.gradle.kts") else setOf("build.gradle.kts", "9*.patch")
}
```

For the server patch dir (added via `listOf("api", "server").forEach { part -> patchDir("paper${capitalizedPart}Buildscript") { ... } }`) — that is for BUILDSCRIPT only. The actual server source patches live in a separate block. Locate the server `patchDir` block — if there is none distinct from buildscript (paperweight 2.0 may apply server patches via convention from `patches/server/`), add an explicit one OR confirm patches are applied via `applyAllPatches` task with a `paperweight.patcher.patchDir.server` directive.

For paperweight-patcher 2.0, server source patches in `patches/server/` are applied automatically by the `applyServerPatches` task. Filtering must happen via a `beforeApply` hook OR by overriding the patches set. The cleanest approach: register a `doFirst` action on the `applyServerPatches` task that moves filtered patches aside.

Add at end of `build.gradle.kts`:
```kotlin
// SourbyCraft v12 — filter PVP patches out of normal builds
tasks.matching { it.name == "applyServerPatches" || it.name == "applyApiPatches" }.configureEach {
    doFirst {
        if (!isPvpVariant) {
            val patchKind = if (name == "applyServerPatches") "server" else "api"
            val patchDir = file("patches/$patchKind")
            val pvpPatches = patchDir.listFiles { f -> f.name.matches(Regex("^9\\d{3}-.*\\.patch$")) } ?: emptyArray()
            val stashDir = file("build/sourbycraft-pvp-patches-stashed/$patchKind").apply { mkdirs() }
            pvpPatches.forEach { pf ->
                val dest = stashDir.resolve(pf.name)
                logger.lifecycle("  stash PVP patch (normal build): ${pf.name}")
                pf.renameTo(dest)
            }
        }
    }
    doLast {
        // Restore stashed PVP patches
        val patchKind = if (name == "applyServerPatches") "server" else "api"
        val stashDir = file("build/sourbycraft-pvp-patches-stashed/$patchKind")
        if (stashDir.exists()) {
            val patchDir = file("patches/$patchKind")
            stashDir.listFiles().orEmpty().forEach { sf ->
                sf.renameTo(patchDir.resolve(sf.name))
            }
        }
    }
}
```

- [ ] **Step 4: Verify Gradle configuration still loads**

Run: `./gradlew help --no-daemon -q 2>&1 | head -20`
Expected: no `Configuration cache` errors, no `Cannot find variant` errors. May see `SourbyCraft variant: normal` line.

- [ ] **Step 5: Dry-run patch apply with variant=pvp**

Run: `./gradlew applyServerPatches --no-daemon -Pvariant=pvp --dry-run 2>&1 | tail -10`
Expected: log line `SourbyCraft variant: pvp (PvP patches: INCLUDED)`.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -m "build: variant patch filter — stash 9XXX-PVP-*.patch on normal builds"
```

---

### Task A3: Add `processVariantResources` Gradle task

**Files:**
- Modify: `sourbycraft-server/build.gradle.kts` (or root if no per-module file)

- [ ] **Step 1: Locate the sourbycraft-server build script**

Run: `find sourbycraft-server -maxdepth 2 -name 'build.gradle*' -not -path '*/build/*'`
Note path (likely `sourbycraft-server/buildscript/build.gradle.kts` per paperweight pattern, or absent — in which case the patcher creates one).

If no build script exists for `sourbycraft-server` module yet (paperweight generates it), the variant-resource task must live in the root `build.gradle.kts` `subprojects {}` block.

- [ ] **Step 2: Add `processVariantResources` task in root `build.gradle.kts`**

Inside the `subprojects {}` block (around the existing `tasks.withType<ProcessResources>` line), add:

```kotlin
// SourbyCraft v12 — variant-specific resource overlay
val variantOverlayTask = tasks.register<Copy>("processVariantResources") {
    val variant = providers.gradleProperty("variant").getOrElse("normal")
    val rootProjectDir = rootProject.projectDir
    val baseline = file("$rootProjectDir/sourbycraft-server/src/main/resources")
    val overlay = file("$rootProjectDir/sourbycraft-server/src/main/resources/variant-overlay/$variant")

    onlyIf { project.name == "sourbycraft-server" && baseline.exists() }

    from(baseline) {
        exclude("variant-overlay/**")
    }
    if (overlay.exists()) {
        from(overlay) {
            // Overlay overrides baseline file-by-file (later 'from' wins)
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }
    into(layout.buildDirectory.dir("variant-resources"))

    inputs.property("variant", variant)
}

tasks.withType<ProcessResources>().configureEach {
    if (project.name == "sourbycraft-server") {
        dependsOn(variantOverlayTask)
        // Merged resources from variant overlay replace baseline resources
        from(layout.buildDirectory.dir("variant-resources")) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        exclude("variant-overlay/**")
    }
}
```

- [ ] **Step 3: Verify task is registered**

Run: `./gradlew :sourbycraft-server:tasks --all --no-daemon 2>&1 | grep -i variantResources`
Expected: line `processVariantResources` listed.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: processVariantResources — overlay variant-specific configs"
```

---

### Task A4: Add `writeBuildInfo` Gradle task (build.properties)

**Files:**
- Modify: `build.gradle.kts` (subprojects block)

- [ ] **Step 1: Add `writeBuildInfo` task**

Inside `subprojects {}` block, after the `processVariantResources` registration:

```kotlin
// SourbyCraft v12 — emit META-INF/sourbycraft-build.properties
val writeBuildInfoTask = tasks.register("writeBuildInfo") {
    val variant = providers.gradleProperty("variant").getOrElse("normal")
    val internalVersion = providers.gradleProperty("internalVersion").getOrElse("dev")
    val mcVersion = providers.gradleProperty("mcVersion").getOrElse("unknown")
    val outFile = layout.buildDirectory.file("variant-resources/META-INF/sourbycraft-build.properties")

    inputs.property("variant", variant)
    inputs.property("internalVersion", internalVersion)
    inputs.property("mcVersion", mcVersion)
    outputs.file(outFile)

    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText("""
            variant=$variant
            version=$internalVersion
            mcVersion=$mcVersion
            tagline=Lightning Fast Performance · Feature Rich
            buildTimestamp=${java.time.Instant.now()}
        """.trimIndent())
    }
}

tasks.named("processVariantResources").configure {
    finalizedBy(writeBuildInfoTask)
}

tasks.withType<ProcessResources>().configureEach {
    if (project.name == "sourbycraft-server") {
        dependsOn(writeBuildInfoTask)
    }
}
```

- [ ] **Step 2: Run task + verify output**

Run: `./gradlew :sourbycraft-server:writeBuildInfo --no-daemon -Pvariant=pvp`
Expected: file `sourbycraft-server/build/variant-resources/META-INF/sourbycraft-build.properties` exists.

Run: `cat sourbycraft-server/build/variant-resources/META-INF/sourbycraft-build.properties`
Expected contents include `variant=pvp`, `version=v12-REL`, `tagline=Lightning Fast Performance · Feature Rich`.

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build: writeBuildInfo — emit sourbycraft-build.properties per variant"
```

---

### Task A5: Wire variant suffix into paperclip JAR output name

**Files:**
- Modify: `build.gradle.kts` (end of file)

- [ ] **Step 1: Identify paperclip task naming**

Run: `./gradlew :sourbycraft-server:tasks --all --no-daemon 2>&1 | grep -i paperclip`
Expected: see `createMojmapPaperclipJar` and possibly `createReobfPaperclipJar`.

- [ ] **Step 2: Add archive-name suffix configuration in root `build.gradle.kts`**

Append at end of root `build.gradle.kts`:

```kotlin
// SourbyCraft v12 — suffix paperclip jar with variant
gradle.projectsEvaluated {
    val variant = providers.gradleProperty("variant").getOrElse("normal")
    val suffix = if (variant == "pvp") "-PVP" else ""
    val internalVersion = providers.gradleProperty("internalVersion").getOrElse("dev")

    subprojects.filter { it.name == "sourbycraft-server" }.forEach { sp ->
        sp.tasks.matching { it.name.endsWith("PaperclipJar") }.configureEach {
            doLast {
                val origJar = outputs.files.singleFile
                if (!origJar.exists()) return@doLast
                val newName = "SourbyCraft${suffix}-${internalVersion}.jar"
                val newFile = origJar.resolveSibling(newName)
                origJar.copyTo(newFile, overwrite = true)
                logger.lifecycle("SourbyCraft jar: ${newFile.name}")
            }
        }
    }
}
```

- [ ] **Step 3: Verify (skip actual build until patches in place)**

Run: `./gradlew :sourbycraft-server:tasks --all --no-daemon 2>&1 | tail -5`
Expected: no Gradle config errors.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: paperclip jar naming — SourbyCraft[-PVP]-v12-REL.jar"
```

---

## Phase B — Branding (banner + tagline)

### Task B1: Create `BuildInfo` reader class (TDD)

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/BuildInfo.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/brand/BuildInfoTest.java`

- [ ] **Step 1: Write the failing test**

Create `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/brand/BuildInfoTest.java`:
```java
package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class BuildInfoTest {

    @Test
    void readsVariantFromProperties() {
        var stream = new ByteArrayInputStream("""
            variant=pvp
            version=v12-REL
            mcVersion=1.21.11
            tagline=Lightning Fast Performance · Feature Rich
            buildTimestamp=2026-06-02T00:00:00Z
            """.getBytes());
        var info = BuildInfo.loadFrom(stream);
        assertEquals("pvp", info.variant());
        assertEquals("v12-REL", info.version());
        assertEquals("1.21.11", info.mcVersion());
        assertTrue(info.tagline().contains("Lightning Fast"));
    }

    @Test
    void fallsBackOnMissingResource() {
        var info = BuildInfo.loadFrom(null);
        assertEquals("normal", info.variant());
        assertEquals("dev", info.version());
    }

    @Test
    void isPvpReturnsTrueForPvpVariant() {
        var stream = new ByteArrayInputStream("variant=pvp\n".getBytes());
        assertTrue(BuildInfo.loadFrom(stream).isPvp());
    }

    @Test
    void isPvpReturnsFalseForNormalVariant() {
        var stream = new ByteArrayInputStream("variant=normal\n".getBytes());
        assertFalse(BuildInfo.loadFrom(stream).isPvp());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.brand.BuildInfoTest' --no-daemon`
Expected: COMPILE FAIL (`BuildInfo` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/BuildInfo.java`:
```java
package dev.iyanz.sourbycraft.brand;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record BuildInfo(
    String variant,
    String version,
    String mcVersion,
    String tagline,
    String buildTimestamp
) {
    private static final String RESOURCE = "/META-INF/sourbycraft-build.properties";

    public static BuildInfo load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            return loadFrom(in);
        } catch (IOException e) {
            return loadFrom(null);
        }
    }

    public static BuildInfo loadFrom(InputStream in) {
        Properties p = new Properties();
        if (in != null) {
            try {
                p.load(in);
            } catch (IOException e) {
                // fall through to defaults
            }
        }
        return new BuildInfo(
            p.getProperty("variant", "normal"),
            p.getProperty("version", "dev"),
            p.getProperty("mcVersion", "unknown"),
            p.getProperty("tagline", "Lightning Fast Performance · Feature Rich"),
            p.getProperty("buildTimestamp", "")
        );
    }

    public boolean isPvp() {
        return "pvp".equalsIgnoreCase(variant);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.brand.BuildInfoTest' --no-daemon`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/BuildInfo.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/brand/BuildInfoTest.java
git commit -m "feat(brand): BuildInfo reader for variant + version metadata"
```

---

### Task B2: Create `SourbyCraftBanner` class (TDD)

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/SourbyCraftBanner.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/brand/SourbyCraftBannerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourbyCraftBannerTest {

    @Test
    void pvpBannerContainsVariantLine() {
        var info = new BuildInfo("pvp", "v12.0-REL", "1.21.11",
            "Lightning Fast Performance · Feature Rich", "2026-06-02T00:00:00Z");
        String b = SourbyCraftBanner.render(info);
        assertTrue(b.contains("PVP"), "banner should mention PVP variant");
        assertTrue(b.contains("v12.0-REL"));
        assertTrue(b.contains("Lightning Fast Performance"));
        assertTrue(b.contains("Feature Rich"));
        assertTrue(b.contains("1.21.11"));
    }

    @Test
    void normalBannerOmitsPvpSummary() {
        var info = new BuildInfo("normal", "v12.0-REL", "1.21.11",
            "Lightning Fast Performance · Feature Rich", "");
        String b = SourbyCraftBanner.render(info);
        assertTrue(b.contains("NORMAL"));
        assertFalse(b.contains("pvp.enabled="), "normal banner should not show pvp summary");
    }

    @Test
    void bannerHasBoxFraming() {
        var info = new BuildInfo("normal", "v12.0-REL", "1.21.11", "tag", "");
        String b = SourbyCraftBanner.render(info);
        assertTrue(b.contains("╔"));
        assertTrue(b.contains("╚"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.brand.SourbyCraftBannerTest' --no-daemon`
Expected: COMPILE FAIL.

- [ ] **Step 3: Write implementation**

```java
package dev.iyanz.sourbycraft.brand;

public final class SourbyCraftBanner {

    private SourbyCraftBanner() {}

    public static String render(BuildInfo info) {
        String variant = info.isPvp() ? "PVP" : "NORMAL";
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("   ╔══════════════════════════════════════════════════════════╗\n");
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   ⚡  SOURBYCRAFT  ⚡   ·  %-7s  ·  %-7s         ║%n",
            info.version(), variant));
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   %-54s ║%n", info.tagline()));
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   Paper %s  ·  Java %s  ·  Variant: %-8s ║%n",
            info.mcVersion(),
            System.getProperty("java.specification.version"),
            variant));
        if (info.isPvp()) {
            sb.append("   ║   PvP-tuned defaults active                              ║\n");
        }
        sb.append("   ║                                                          ║\n");
        sb.append("   ╚══════════════════════════════════════════════════════════╝\n");
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.brand.SourbyCraftBannerTest' --no-daemon`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/SourbyCraftBanner.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/brand/SourbyCraftBannerTest.java
git commit -m "feat(brand): SourbyCraftBanner — boxed startup banner per variant"
```

---

### Task B3: Patch `MinecraftServer` to print banner on early init

**Files:**
- Create: `patches/server/0029-SourbyCraft-v12-startup-banner.patch`

- [ ] **Step 1: Locate `MinecraftServer.java` patch insertion point**

Run: `grep -n 'LOGGER.info.*Starting' paper-server/src/main/java/net/minecraft/server/MinecraftServer.java 2>/dev/null | head -5`
Or after applying current patches: `grep -n 'LOGGER.info.*Starting' build/sourbycraft-server/src/main/java/net/minecraft/server/MinecraftServer.java 2>/dev/null | head -5`

Identify a stable insertion point — recommend just after the `LOGGER.info("Starting minecraft server version ...")` line.

- [ ] **Step 2: Apply patches first (working state)**

Run: `./gradlew applyAllPatches --no-daemon`
Expected: clean apply, no rejects.

- [ ] **Step 3: Add banner print to MinecraftServer.java**

Edit `sourbycraft-server/src/main/java/net/minecraft/server/MinecraftServer.java` (the working-tree file after patch apply). Find the line near server startup where `LOGGER.info("Starting minecraft server")` is called. Add immediately after:

```java
// SourbyCraft v12 — startup banner
System.out.print(dev.iyanz.sourbycraft.brand.SourbyCraftBanner.render(
    dev.iyanz.sourbycraft.brand.BuildInfo.load()
));
```

- [ ] **Step 4: Rebuild patches**

Run: `./gradlew rebuildPatches --no-daemon`
Expected: new patch file generated `patches/server/00XX-SourbyCraft-v12-startup-banner.patch` (number = next available).

- [ ] **Step 5: Smoke-build the server jar (normal variant)**

Run: `./gradlew :sourbycraft-server:createMojmapPaperclipJar --no-daemon -Pvariant=normal`
Expected: BUILD SUCCESS, jar produced under `sourbycraft-server/build/libs/`.

- [ ] **Step 6: Commit**

```bash
git add patches/server/00*-SourbyCraft-v12-startup-banner.patch
git commit -m "feat(v12): startup banner — print SourbyCraftBanner early in MinecraftServer init"
```

---

### Task B4: Update `/ver` command to surface variant + tagline

**Files:**
- Modify: existing `/ver` command implementation (locate first)

- [ ] **Step 1: Locate /ver implementation**

Run: `grep -rn '"ver"\|/ver\|VerCommand\|version.*command' sourbycraft-server/src/main/java --include='*.java' | head -10`

Note the file path. Expected somewhere like `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/VerCommand.java`.

- [ ] **Step 2: Read current implementation**

Read the located VerCommand file. Identify the method that builds the response string.

- [ ] **Step 3: Modify response to include variant + tagline**

In the response builder, append after the existing version line:

```java
dev.iyanz.sourbycraft.brand.BuildInfo bi = dev.iyanz.sourbycraft.brand.BuildInfo.load();
sender.sendMessage(SourbyCraftColors.colorize(
    "&7Variant: " + (bi.isPvp() ? "&cPVP" : "&aNORMAL") +
    "&r &7· &f\"" + bi.tagline() + "\""
));
```

(Adapt `SourbyCraftColors.colorize` to whatever the existing utility name is — read nearby lines for the convention.)

- [ ] **Step 4: Run any existing /ver tests**

Run: `./gradlew :sourbycraft-server:test --tests '*Ver*' --no-daemon`
Expected: PASS (or no tests found, both OK).

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/VerCommand.java
git commit -m "feat(brand): /ver — show variant + tagline"
```

---

## Phase C — Plugin auto-installer

### Task C1: Create plugin manifest YAML files

**Files:**
- Create: `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/normal.yml`
- Create: `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/pvp.yml`

- [ ] **Step 1: Write normal.yml**

```yaml
# SourbyCraft v12 — first-boot plugin auto-installer manifest (NORMAL variant)
plugins:
  - name: SlimeWorldManager
    source: github
    repo: InfernalSuite/SlimeWorldManager
    asset-glob: "swm-*.jar"
  - name: spark
    source: ci
    url: "https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-bukkit/build/libs/spark-1.10.142-bukkit.jar"
```

- [ ] **Step 2: Write pvp.yml**

```yaml
# SourbyCraft v12 — first-boot plugin auto-installer manifest (PVP variant)
plugins:
  - name: SlimeWorldManager
    source: github
    repo: InfernalSuite/SlimeWorldManager
    asset-glob: "swm-*.jar"
  - name: ViaVersion
    source: github
    repo: ViaVersion/ViaVersion
    asset-glob: "ViaVersion-*.jar"
  - name: ViaBackwards
    source: github
    repo: ViaVersion/ViaBackwards
    asset-glob: "ViaBackwards-*.jar"
  - name: PacketEvents
    source: github
    repo: retrooper/packetevents
    asset-glob: "packetevents-spigot-*.jar"
  - name: spark
    source: ci
    url: "https://ci.lucko.me/job/spark/lastSuccessfulBuild/artifact/spark-bukkit/build/libs/spark-1.10.142-bukkit.jar"
```

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugins/
git commit -m "feat(install): plugin manifests for normal + pvp variants"
```

---

### Task C2: Create `PluginManifest` parser (TDD)

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginManifest.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginEntry.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/install/PluginManifestTest.java`

- [ ] **Step 1: Write failing test**

```java
package dev.iyanz.sourbycraft.install;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PluginManifestTest {

    @Test
    void parsesGithubEntry() {
        var yaml = """
            plugins:
              - name: SlimeWorldManager
                source: github
                repo: InfernalSuite/SlimeWorldManager
                asset-glob: "swm-*.jar"
            """;
        List<PluginEntry> entries = PluginManifest.parse(new ByteArrayInputStream(yaml.getBytes()));
        assertEquals(1, entries.size());
        PluginEntry e = entries.get(0);
        assertEquals("SlimeWorldManager", e.name());
        assertEquals("github", e.source());
        assertEquals("InfernalSuite/SlimeWorldManager", e.repo());
        assertEquals("swm-*.jar", e.assetGlob());
        assertNull(e.url());
    }

    @Test
    void parsesCiEntry() {
        var yaml = """
            plugins:
              - name: spark
                source: ci
                url: "https://example.com/spark.jar"
            """;
        List<PluginEntry> entries = PluginManifest.parse(new ByteArrayInputStream(yaml.getBytes()));
        assertEquals(1, entries.size());
        assertEquals("https://example.com/spark.jar", entries.get(0).url());
    }

    @Test
    void emptyManifestReturnsEmptyList() {
        var yaml = "plugins: []\n";
        assertTrue(PluginManifest.parse(new ByteArrayInputStream(yaml.getBytes())).isEmpty());
    }

    @Test
    void nullStreamReturnsEmptyList() {
        assertTrue(PluginManifest.parse(null).isEmpty());
    }
}
```

- [ ] **Step 2: Run test — verify FAIL**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.install.PluginManifestTest' --no-daemon`
Expected: COMPILE FAIL.

- [ ] **Step 3: Write `PluginEntry` record**

`sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginEntry.java`:
```java
package dev.iyanz.sourbycraft.install;

public record PluginEntry(
    String name,
    String source,
    String repo,
    String url,
    String assetGlob,
    String sha256
) {
    public boolean isGithub() { return "github".equalsIgnoreCase(source); }
    public boolean isCi() { return "ci".equalsIgnoreCase(source); }
}
```

- [ ] **Step 4: Write `PluginManifest` parser**

`sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginManifest.java`:
```java
package dev.iyanz.sourbycraft.install;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PluginManifest {

    private PluginManifest() {}

    @SuppressWarnings("unchecked")
    public static List<PluginEntry> parse(InputStream in) {
        if (in == null) return List.of();
        Map<String, Object> root = new Yaml().load(in);
        if (root == null) return List.of();
        Object pluginsObj = root.get("plugins");
        if (!(pluginsObj instanceof List<?> list)) return List.of();

        List<PluginEntry> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            out.add(new PluginEntry(
                asString(m.get("name")),
                asString(m.get("source")),
                asString(m.get("repo")),
                asString(m.get("url")),
                asString(m.get("asset-glob")),
                asString(m.get("sha256"))
            ));
        }
        return out;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
```

- [ ] **Step 5: Run tests — verify PASS**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.install.PluginManifestTest' --no-daemon`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginEntry.java \
        sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginManifest.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/install/PluginManifestTest.java
git commit -m "feat(install): PluginManifest YAML parser + PluginEntry record"
```

---

### Task C3: Create `PluginExistenceCheck` (TDD)

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginExistenceCheck.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/install/PluginExistenceCheckTest.java`

- [ ] **Step 1: Write failing test**

```java
package dev.iyanz.sourbycraft.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PluginExistenceCheckTest {

    @Test
    void detectsExistingPluginByNamePrefix(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("ViaVersion-5.2.1.jar"));
        assertTrue(PluginExistenceCheck.isInstalled(dir, "ViaVersion"));
    }

    @Test
    void caseInsensitiveMatch(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("viaversion-5.2.1.jar"));
        assertTrue(PluginExistenceCheck.isInstalled(dir, "ViaVersion"));
    }

    @Test
    void absentPluginReturnsFalse(@TempDir Path dir) {
        assertFalse(PluginExistenceCheck.isInstalled(dir, "ViaVersion"));
    }

    @Test
    void onlyJarFilesCount(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("ViaVersion-readme.txt"));
        assertFalse(PluginExistenceCheck.isInstalled(dir, "ViaVersion"));
    }
}
```

- [ ] **Step 2: Run test — verify FAIL**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.install.PluginExistenceCheckTest' --no-daemon`
Expected: COMPILE FAIL.

- [ ] **Step 3: Write implementation**

```java
package dev.iyanz.sourbycraft.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

public final class PluginExistenceCheck {

    private PluginExistenceCheck() {}

    public static boolean isInstalled(Path pluginsDir, String name) {
        if (!Files.isDirectory(pluginsDir)) return false;
        String prefix = name.toLowerCase(Locale.ROOT);
        try (Stream<Path> s = Files.list(pluginsDir)) {
            return s.anyMatch(p -> {
                String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return fn.endsWith(".jar") && fn.startsWith(prefix);
            });
        } catch (IOException e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Run test — PASS**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.install.PluginExistenceCheckTest' --no-daemon`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginExistenceCheck.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/install/PluginExistenceCheckTest.java
git commit -m "feat(install): PluginExistenceCheck — case-insensitive name-prefix scan"
```

---

### Task C4: Create `PluginDownloader` (downloads, GitHub Releases API + direct URL)

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginDownloader.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/install/PluginDownloaderTest.java`

- [ ] **Step 1: Write failing test (uses local HTTP server fixture)**

```java
package dev.iyanz.sourbycraft.install;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PluginDownloaderTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/spark.jar", ex -> {
            byte[] body = "fake-jar-bytes".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void downloadsCiUrlToFile(@TempDir Path dir) throws IOException {
        int port = server.getAddress().getPort();
        var entry = new PluginEntry("spark", "ci", null,
            "http://localhost:" + port + "/spark.jar", null, null);
        Path target = PluginDownloader.download(entry, dir);
        assertNotNull(target);
        assertTrue(Files.exists(target));
        assertEquals("fake-jar-bytes", Files.readString(target));
    }

    @Test
    void returnsNullOnHttpError(@TempDir Path dir) throws IOException {
        int port = server.getAddress().getPort();
        var entry = new PluginEntry("404", "ci", null,
            "http://localhost:" + port + "/missing.jar", null, null);
        assertNull(PluginDownloader.download(entry, dir));
    }
}
```

- [ ] **Step 2: Run test — verify FAIL**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.install.PluginDownloaderTest' --no-daemon`
Expected: COMPILE FAIL.

- [ ] **Step 3: Write implementation**

```java
package dev.iyanz.sourbycraft.install;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonReader;

public final class PluginDownloader {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private PluginDownloader() {}

    public static Path download(PluginEntry entry, Path pluginsDir) throws IOException {
        String url = entry.isGithub() ? resolveGithubAsset(entry) : entry.url();
        if (url == null) return null;
        return downloadToFile(url, pluginsDir, entry.name());
    }

    private static String resolveGithubAsset(PluginEntry entry) throws IOException {
        URI api = URI.create("https://api.github.com/repos/" + entry.repo() + "/releases/latest");
        HttpRequest req = HttpRequest.newBuilder(api)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .GET().build();
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return null;
            try (JsonReader jr = Json.createReader(new java.io.StringReader(resp.body()))) {
                JsonArray assets = jr.readObject().getJsonArray("assets");
                if (assets == null) return null;
                Pattern globRe = entry.assetGlob() == null ? null : globToRegex(entry.assetGlob());
                for (int i = 0; i < assets.size(); i++) {
                    String aname = assets.getJsonObject(i).getString("name", "");
                    String aurl = assets.getJsonObject(i).getString("browser_download_url", "");
                    if (globRe == null || globRe.matcher(aname).matches()) {
                        return aurl;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted resolving GitHub asset", e);
        }
        return null;
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '(', ')', '+', '|', '^', '$', '@', '%' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }

    private static Path downloadToFile(String url, Path pluginsDir, String pluginName) throws IOException {
        URI uri = URI.create(url);
        String fileName = extractFileName(uri, pluginName);
        Path target = pluginsDir.resolve(fileName);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build();
        try {
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) return null;
            try (InputStream in = resp.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e);
        }
    }

    private static String extractFileName(URI uri, String pluginName) {
        String path = uri.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        if (name.isBlank() || !name.endsWith(".jar")) {
            name = pluginName + ".jar";
        }
        return name;
    }
}
```

NOTE: This uses `javax.json` (Jakarta JSON-P). If the dep is not on classpath, swap to manual regex parse of the JSON. Verify dep first:

Run: `grep -r 'javax.json\|jakarta.json\|json-p' sourbycraft-server/build.gradle* 2>/dev/null && echo "OR" && grep -r 'jakarta.json' build.gradle.kts 2>/dev/null`

If absent, replace `javax.json` usage with a simple regex:
```java
// Replace JsonReader block with:
Pattern assetPat = Pattern.compile("\"name\":\"([^\"]+)\".{0,200}?\"browser_download_url\":\"([^\"]+)\"");
java.util.regex.Matcher m = assetPat.matcher(resp.body());
Pattern globRe = entry.assetGlob() == null ? null : globToRegex(entry.assetGlob());
while (m.find()) {
    String aname = m.group(1);
    String aurl = m.group(2);
    if (globRe == null || globRe.matcher(aname).matches()) {
        return aurl;
    }
}
return null;
```

- [ ] **Step 4: Run test — PASS**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.install.PluginDownloaderTest' --no-daemon`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginDownloader.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/install/PluginDownloaderTest.java
git commit -m "feat(install): PluginDownloader — GitHub Releases API + direct URL"
```

---

### Task C5: Create `PluginAutoInstaller` orchestrator

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginAutoInstaller.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/install/PluginAutoInstallerTest.java`

- [ ] **Step 1: Write failing test**

```java
package dev.iyanz.sourbycraft.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PluginAutoInstallerTest {

    @Test
    void skipsAlreadyInstalledPlugins(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("ViaVersion-5.2.1.jar"));
        var entry = new PluginEntry("ViaVersion", "ci", null, "http://nonexistent/x.jar", null, null);
        var result = PluginAutoInstaller.installAll(List.of(entry), dir);
        assertEquals(1, result.skippedCount());
        assertEquals(0, result.installedCount());
        assertEquals(0, result.failedCount());
    }

    @Test
    void countsFailedDownloads(@TempDir Path dir) {
        var entry = new PluginEntry("Nonexistent", "ci", null,
            "http://127.0.0.1:1/nope.jar", null, null);
        var result = PluginAutoInstaller.installAll(List.of(entry), dir);
        assertEquals(0, result.installedCount());
        assertEquals(1, result.failedCount());
    }

    @Test
    void emptyListProducesEmptyResult(@TempDir Path dir) {
        var result = PluginAutoInstaller.installAll(List.of(), dir);
        assertEquals(0, result.installedCount() + result.skippedCount() + result.failedCount());
    }
}
```

- [ ] **Step 2: Run test — verify FAIL**

Expected: COMPILE FAIL.

- [ ] **Step 3: Write implementation**

```java
package dev.iyanz.sourbycraft.install;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PluginAutoInstaller {

    public record Result(int installedCount, int skippedCount, int failedCount) {}

    private PluginAutoInstaller() {}

    public static Result installFromVariant(String variant, Path pluginsDir) {
        String resource = "/META-INF/sourbycraft-plugins/" + variant + ".yml";
        try (InputStream in = PluginAutoInstaller.class.getResourceAsStream(resource)) {
            List<PluginEntry> entries = PluginManifest.parse(in);
            return installAll(entries, pluginsDir);
        } catch (IOException e) {
            SourbyLogger.warn("[install] Failed to read manifest " + resource + ": " + e.getMessage());
            return new Result(0, 0, 0);
        }
    }

    public static Result installAll(List<PluginEntry> entries, Path pluginsDir) {
        int installed = 0, skipped = 0, failed = 0;
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            SourbyLogger.warn("[install] Cannot create plugins dir: " + e.getMessage());
            return new Result(0, 0, entries.size());
        }
        for (PluginEntry e : entries) {
            if (PluginExistenceCheck.isInstalled(pluginsDir, e.name())) {
                SourbyLogger.info("[install] " + e.name() + " already present, skipping");
                skipped++;
                continue;
            }
            try {
                Path out = PluginDownloader.download(e, pluginsDir);
                if (out != null) {
                    SourbyLogger.info("[install] downloaded " + e.name() + " -> " + out.getFileName());
                    installed++;
                } else {
                    SourbyLogger.warn("[install] " + e.name() + " download returned no file");
                    failed++;
                }
            } catch (Exception ex) {
                SourbyLogger.warn("[install] " + e.name() + " failed: " + ex.getMessage());
                failed++;
            }
        }
        return new Result(installed, skipped, failed);
    }
}
```

NOTE: Adapt `SourbyLogger.info`/`warn` to the existing logger utility. If `SourbyLogger` does not expose these methods, replace with `LoggerFactory.getLogger(PluginAutoInstaller.class).info(...)`.

- [ ] **Step 4: Run test — PASS**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.install.PluginAutoInstallerTest' --no-daemon`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/install/PluginAutoInstaller.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/install/PluginAutoInstallerTest.java
git commit -m "feat(install): PluginAutoInstaller — orchestrates skip/download/fail count"
```

---

### Task C6: Patch server pre-boot to invoke PluginAutoInstaller

**Files:**
- Create: `patches/server/00XX-SourbyCraft-v12-plugin-autoinstall.patch` (number = next available)

- [ ] **Step 1: Apply patches first**

Run: `./gradlew applyAllPatches --no-daemon`
Expected: clean.

- [ ] **Step 2: Locate the plugin-scan code path**

Run: `grep -rn 'enablePlugin\|PluginManager.*loadPlugins\|loadPlugins' sourbycraft-server/src/main/java/org/bukkit/craftbukkit | head -5`

Identify the call site for `CraftServer.loadPlugins()` — invoked from `MinecraftServer` startup.

- [ ] **Step 3: Insert installer call before plugin scan**

In `CraftServer.loadPlugins()` (top of method, before any plugin enumeration).
Uses `SourbyCraftConfig.ymlGet` from Task D2 — confirm that task is complete
first.

```java
// SourbyCraft v12 — auto-install variant plugins before scan
{
    dev.iyanz.sourbycraft.brand.BuildInfo bi = dev.iyanz.sourbycraft.brand.BuildInfo.load();
    boolean autoInstallEnabled = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
        "auto-install.enabled", Boolean.TRUE);
    if (autoInstallEnabled) {
        java.nio.file.Path pluginsDir = java.nio.file.Path.of("plugins");
        dev.iyanz.sourbycraft.install.PluginAutoInstaller.Result r =
            dev.iyanz.sourbycraft.install.PluginAutoInstaller.installFromVariant(bi.variant(), pluginsDir);
        org.slf4j.LoggerFactory.getLogger("SourbyCraft").info(
            "[install] variant={}: installed={} skipped={} failed={}",
            bi.variant(), r.installedCount(), r.skippedCount(), r.failedCount()
        );
    }
}
```

- [ ] **Step 4: Rebuild patches**

Run: `./gradlew rebuildPatches --no-daemon`
Expected: new patch file created.

- [ ] **Step 5: Smoke-test patch applies cleanly from scratch**

Run: `./gradlew clean applyAllPatches --no-daemon`
Expected: SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add patches/server/00*-SourbyCraft-v12-plugin-autoinstall.patch
git commit -m "feat(install): wire PluginAutoInstaller into CraftServer.loadPlugins pre-scan"
```

---

## Phase D — Variant config files

### Task D1: Create baseline sourbycraft.yml

**Files:**
- Create: `sourbycraft-server/src/main/resources/sourbycraft.yml`

- [ ] **Step 1: Locate existing config defaults**

Run: `grep -n 'getBoolean\|getInt\|getDouble\|getString' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java | head -30`

Capture all keys + their hardcoded defaults — those define the baseline.

- [ ] **Step 2: Write baseline sourbycraft.yml**

Construct from the key list captured. Example structure:

```yaml
# SourbyCraft configuration (baseline)
# Variant overlays in variant-overlay/<variant>/ override these at build time.

pvp:
  enabled: false
  knockback:
    friction-divisor: 2.0
    vertical: 0.4
    extra-horizontal: 0.5
  no-attack-cooldown: false
  view-distance-cap: 10
  simulation-distance-cap: 8
  max-mobs-per-chunk: 8
  mob-ai-activation-range: 32

network:
  proxy-mode: none
  netty:
    threads: auto
    snd-buf-kb: 64
    rcv-buf-kb: 64
    max-packets-per-tick: 100
  proxy-kick-grace-seconds: 0

entity-tracker:
  mob-range: 48
  item-range: 64
  xp-orb-range: 64
  player-update-interval: 2

combat:
  sweep-enabled: true
  hit-delay-ticks: 10
  hit-window-ms: 100
  fishing-rod-knockback: false
  reach-debug-command: false

branding:
  motd-suffix: false
  compact-plugin-list: true
  compact-plugin-log: false
  gc-advisor:
    enabled: true

auto-install:
  enabled: true

# Network knobs used by F5 proxy-kick patch
network:
  proxy-kick-fallback: lobby
```

NOTE: the `network:` block already opens earlier in this file (under
`network.proxy-mode`). Merge `proxy-kick-fallback` into that block — do NOT
create a second `network:` key (SnakeYAML treats duplicates as an error).

If `SourbyCraftConfig.java` does not currently load YAML — only reads `paper-global.yml` keys via `dev.iyanz.sourbycraft.SourbyCraftConfig.YAML` or similar — this file is the new spot. Task D2 wires the loader.

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/main/resources/sourbycraft.yml
git commit -m "feat(config): baseline sourbycraft.yml — all v12 keys with defaults"
```

---

### Task D2: Wire YAML loader into `SourbyCraftConfig`

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`

- [ ] **Step 1: Read current `SourbyCraftConfig.java`**

Run: `wc -l sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
Run: `head -60 sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`

Identify how config is currently loaded.

- [ ] **Step 2: Add YAML overlay loader**

At top of class, add:
```java
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.Map;
import java.util.HashMap;

// SourbyCraft v12 — sourbycraft.yml overlay loader
private static Map<String, Object> sourbycraftYml = loadSourbycraftYml();

private static Map<String, Object> loadSourbycraftYml() {
    try (InputStream in = SourbyCraftConfig.class.getResourceAsStream("/sourbycraft.yml")) {
        if (in == null) return Map.of();
        Map<String, Object> y = new Yaml().load(in);
        return y == null ? Map.of() : y;
    } catch (Exception e) {
        return Map.of();
    }
}

@SuppressWarnings("unchecked")
public static <T> T ymlGet(String dottedPath, T defaultValue) {
    Object cur = sourbycraftYml;
    for (String seg : dottedPath.split("\\.")) {
        if (!(cur instanceof Map<?, ?> m)) return defaultValue;
        cur = m.get(seg);
        if (cur == null) return defaultValue;
    }
    try {
        return (T) cur;
    } catch (ClassCastException e) {
        return defaultValue;
    }
}
```

- [ ] **Step 3: Run any existing config tests**

Run: `./gradlew :sourbycraft-server:test --tests '*Config*' --no-daemon`
Expected: PASS or no tests found.

- [ ] **Step 4: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java
git commit -m "feat(config): SourbyCraftConfig.ymlGet — read sourbycraft.yml overlay"
```

---

### Task D3: Create variant overlay files

**Files:**
- Create: `sourbycraft-server/src/main/resources/variant-overlay/normal/sourbycraft.yml`
- Create: `sourbycraft-server/src/main/resources/variant-overlay/normal/server.properties`
- Create: `sourbycraft-server/src/main/resources/variant-overlay/pvp/sourbycraft.yml`
- Create: `sourbycraft-server/src/main/resources/variant-overlay/pvp/server.properties`
- Create: `sourbycraft-server/src/main/resources/variant-overlay/pvp/paper-global.yml`
- Create: `sourbycraft-server/src/main/resources/variant-overlay/pvp/spigot.yml`
- Create: `sourbycraft-server/src/main/resources/variant-overlay/pvp/paper-world-defaults.yml`

- [ ] **Step 1: Write `variant-overlay/normal/sourbycraft.yml`**

```yaml
# Normal variant overlay — general SMP defaults
# (Baseline already matches normal — overlay is empty placeholder for future tuning.)
```

- [ ] **Step 2: Write `variant-overlay/normal/server.properties`**

```properties
allow-nether=true
online-mode=true
```

- [ ] **Step 3: Write `variant-overlay/pvp/sourbycraft.yml`**

```yaml
# PVP variant overlay — PvP arena defaults
pvp:
  enabled: true
  knockback:
    friction-divisor: 1.0
  no-attack-cooldown: true
  view-distance-cap: 6
  simulation-distance-cap: 5
  max-mobs-per-chunk: 4
  mob-ai-activation-range: 24

network:
  proxy-mode: velocity-modern
  netty:
    threads: auto-doubled
  proxy-kick-grace-seconds: 5

entity-tracker:
  mob-range: 32
  item-range: 16
  xp-orb-range: 16
  player-update-interval: 1

combat:
  sweep-enabled: false
  hit-delay-ticks: 8
  hit-window-ms: 150
  fishing-rod-knockback: true
  reach-debug-command: true

branding:
  compact-plugin-log: true
```

- [ ] **Step 4: Write `variant-overlay/pvp/server.properties`**

```properties
online-mode=false
prevent-proxy-connections=false
network-compression-threshold=-1
enforce-secure-profile=false
allow-nether=false
```

- [ ] **Step 5: Write `variant-overlay/pvp/paper-global.yml`**

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "CHANGE-ME-SEE-DOCS"
  bungee-cord:
    online-mode: true
```

- [ ] **Step 6: Write `variant-overlay/pvp/spigot.yml`**

```yaml
settings:
  bungeecord: false
```

- [ ] **Step 7: Write `variant-overlay/pvp/paper-world-defaults.yml`**

```yaml
chunks:
  prevent-moving-into-unloaded-chunks: true
entities:
  spawning:
    despawn-ranges:
      monster:
        hard: 64
        soft: 24
```

- [ ] **Step 8: Verify overlay applies — run processVariantResources for pvp**

Run: `./gradlew :sourbycraft-server:processVariantResources --no-daemon -Pvariant=pvp`
Run: `cat sourbycraft-server/build/variant-resources/sourbycraft.yml | head -10`
Expected: PVP-specific values present (e.g. `view-distance-cap: 6`).

- [ ] **Step 9: Commit**

```bash
git add sourbycraft-server/src/main/resources/variant-overlay/
git commit -m "feat(config): variant overlay files — normal + pvp config + server.properties"
```

---

## Phase E — /plugins, /pl reformat + startup log

### Task E1: Create plugin category map resource + loader (TDD)

**Files:**
- Create: `sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugin-categories.yml`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/PluginCategoryMap.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/brand/PluginCategoryMapTest.java`

- [ ] **Step 1: Write categories YAML**

```yaml
categories:
  Core:
    - LuckPerms
    - EssentialsX
    - Vault
    - PlaceholderAPI
  PvP:
    - CombatLogX
    - ViaVersion
    - ViaBackwards
    - PacketEvents
    - ProtocolLib
  World:
    - WorldEdit
    - WorldGuard
    - SlimeWorldManager
    - FastAsyncWorldEdit
  Util:
    - spark
    - Multiverse-Core
  Economy:
    - EconomyAPI
    - CMI
```

- [ ] **Step 2: Write failing test**

```java
package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class PluginCategoryMapTest {

    @Test
    void mapsKnownPlugin() {
        var yaml = """
            categories:
              Core: [LuckPerms]
              PvP:  [ViaVersion]
            """;
        var map = PluginCategoryMap.parse(new ByteArrayInputStream(yaml.getBytes()));
        assertEquals("Core", map.categoryOf("LuckPerms"));
        assertEquals("PvP", map.categoryOf("ViaVersion"));
    }

    @Test
    void unknownPluginReturnsOther() {
        var map = PluginCategoryMap.parse(new ByteArrayInputStream("categories: {}".getBytes()));
        assertEquals("Other", map.categoryOf("MyCustomPlugin"));
    }

    @Test
    void caseInsensitiveMatch() {
        var yaml = "categories:\n  Core: [LuckPerms]\n";
        var map = PluginCategoryMap.parse(new ByteArrayInputStream(yaml.getBytes()));
        assertEquals("Core", map.categoryOf("luckperms"));
    }
}
```

- [ ] **Step 3: Run test — FAIL**

Expected: COMPILE FAIL.

- [ ] **Step 4: Write implementation**

```java
package dev.iyanz.sourbycraft.brand;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PluginCategoryMap {

    private final Map<String, String> pluginToCategory;

    private PluginCategoryMap(Map<String, String> p2c) {
        this.pluginToCategory = p2c;
    }

    @SuppressWarnings("unchecked")
    public static PluginCategoryMap parse(InputStream in) {
        Map<String, String> p2c = new HashMap<>();
        if (in == null) return new PluginCategoryMap(p2c);
        Map<String, Object> root = new Yaml().load(in);
        if (root == null) return new PluginCategoryMap(p2c);
        Object catsObj = root.get("categories");
        if (!(catsObj instanceof Map<?, ?> cats)) return new PluginCategoryMap(p2c);
        for (Map.Entry<?, ?> e : cats.entrySet()) {
            String cat = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof List<?> list)) continue;
            for (Object plug : list) {
                p2c.put(String.valueOf(plug).toLowerCase(Locale.ROOT), cat);
            }
        }
        return new PluginCategoryMap(p2c);
    }

    public static PluginCategoryMap loadDefault() {
        try (InputStream in = PluginCategoryMap.class
            .getResourceAsStream("/META-INF/sourbycraft-plugin-categories.yml")) {
            return parse(in);
        } catch (Exception e) {
            return new PluginCategoryMap(new HashMap<>());
        }
    }

    public String categoryOf(String pluginName) {
        return pluginToCategory.getOrDefault(pluginName.toLowerCase(Locale.ROOT), "Other");
    }
}
```

- [ ] **Step 5: Run test — PASS**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.brand.PluginCategoryMapTest' --no-daemon`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add sourbycraft-server/src/main/resources/META-INF/sourbycraft-plugin-categories.yml \
        sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/PluginCategoryMap.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/brand/PluginCategoryMapTest.java
git commit -m "feat(brand): PluginCategoryMap — categorize plugins for /plugins layout"
```

---

### Task E2: Create `PluginsCommandFormatter` (TDD)

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/PluginsCommandFormatter.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/brand/PluginsCommandFormatterTest.java`

- [ ] **Step 1: Write failing test**

```java
package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PluginsCommandFormatterTest {

    record TestPlugin(String name, String version, boolean enabled) implements PluginsCommandFormatter.PluginInfo {}

    @Test
    void rendersBoxedHeader() {
        var out = PluginsCommandFormatter.render(
            List.of(new TestPlugin("LuckPerms", "5.4.130", true)),
            PluginCategoryMap.loadDefault());
        assertTrue(out.contains("Lightning Fast"));
        assertTrue(out.contains("┌"));
        assertTrue(out.contains("└"));
    }

    @Test
    void groupsByCategory() {
        var plugins = List.of(
            new TestPlugin("LuckPerms", "5.4", true),
            new TestPlugin("WorldEdit", "7.3", true),
            new TestPlugin("ViaVersion", "5.2", true)
        );
        String out = PluginsCommandFormatter.render(plugins, PluginCategoryMap.loadDefault());
        int coreIdx = out.indexOf("Core");
        int pvpIdx = out.indexOf("PvP");
        int worldIdx = out.indexOf("World");
        assertTrue(coreIdx > 0 && pvpIdx > 0 && worldIdx > 0);
    }

    @Test
    void showsDisabledStatus() {
        var p = new TestPlugin("Foo", "1.0", false);
        String out = PluginsCommandFormatter.render(List.of(p), PluginCategoryMap.loadDefault());
        assertTrue(out.contains("disabled"));
    }

    @Test
    void footerShowsCount() {
        var plugins = List.of(
            new TestPlugin("A", "1", true),
            new TestPlugin("B", "1", false));
        String out = PluginsCommandFormatter.render(plugins, PluginCategoryMap.loadDefault());
        assertTrue(out.contains("2 plugins"));
        assertTrue(out.contains("1 enabled"));
        assertTrue(out.contains("1 disabled"));
    }
}
```

- [ ] **Step 2: Run test — FAIL**

Expected: COMPILE FAIL.

- [ ] **Step 3: Write implementation**

```java
package dev.iyanz.sourbycraft.brand;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class PluginsCommandFormatter {

    public interface PluginInfo {
        String name();
        String version();
        boolean enabled();
    }

    private static final String[] CATEGORY_ORDER = {"Core", "PvP", "World", "Util", "Economy", "Other"};
    private static final String[] CATEGORY_COLORS = {"§6", "§c", "§a", "§e", "§b", "§8"};

    private PluginsCommandFormatter() {}

    public static String render(List<? extends PluginInfo> plugins, PluginCategoryMap cats) {
        Map<String, List<PluginInfo>> grouped = new LinkedHashMap<>();
        for (String c : CATEGORY_ORDER) grouped.put(c, new java.util.ArrayList<>());
        for (PluginInfo p : plugins) {
            grouped.get(cats.categoryOf(p.name())).add(p);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("┌─ ⚡ Lightning Fast · ✨ Feature Rich ─────────────────┐\n");
        for (int i = 0; i < CATEGORY_ORDER.length; i++) {
            String cat = CATEGORY_ORDER[i];
            String color = CATEGORY_COLORS[i];
            List<PluginInfo> list = grouped.get(cat);
            if (list.isEmpty()) continue;
            boolean first = true;
            for (PluginInfo p : list) {
                String catLabel = first ? String.format("%s%-10s§r", color, cat) : "          ";
                String status = p.enabled()
                    ? String.format("§av%s", p.version())
                    : String.format("§7v%s§8 (disabled)", p.version());
                sb.append(String.format("│ %s  %-18s %s%n", catLabel, p.name(), status));
                first = false;
            }
        }
        long enabled = plugins.stream().filter(PluginInfo::enabled).count();
        long disabled = plugins.size() - enabled;
        long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        sb.append(String.format("└─ %d plugins · %d enabled · %d disabled · %d MB heap ──┘%n",
            plugins.size(), enabled, disabled, heapMb));
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run test — PASS**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.brand.PluginsCommandFormatterTest' --no-daemon`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/PluginsCommandFormatter.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/brand/PluginsCommandFormatterTest.java
git commit -m "feat(brand): PluginsCommandFormatter — boxed categorized /plugins output"
```

---

### Task E3: Patch /plugins + /pl command to use new formatter

**Files:**
- Modify: existing `/plugins` handler (locate first)
- Create or extend: `patches/server/00XX-SourbyCraft-v12-plugins-command-formatter.patch`

- [ ] **Step 1: Locate /plugins handler**

Run: `grep -rn '"plugins"\|PluginsCommand' sourbycraft-server/src/main/java --include='*.java' | head -10`

Or in Bukkit's CraftBukkit dispatch:
Run: `grep -rn 'class.*PluginsCommand\|"plugins"' paper-server/src/main/java/org/bukkit/craftbukkit 2>/dev/null | head -5`

- [ ] **Step 2: Apply patches first**

Run: `./gradlew applyAllPatches --no-daemon`

- [ ] **Step 3: Patch the handler**

In the located `PluginsCommand.execute()` (or equivalent), replace the listing builder with:

```java
// SourbyCraft v12 — categorized formatter
List<dev.iyanz.sourbycraft.brand.PluginsCommandFormatter.PluginInfo> infos =
    java.util.Arrays.stream(Bukkit.getPluginManager().getPlugins())
        .map(p -> (dev.iyanz.sourbycraft.brand.PluginsCommandFormatter.PluginInfo) new dev.iyanz.sourbycraft.brand.PluginsCommandFormatter.PluginInfo() {
            public String name() { return p.getName(); }
            public String version() { return p.getDescription().getVersion(); }
            public boolean enabled() { return p.isEnabled(); }
        })
        .toList();
String rendered = dev.iyanz.sourbycraft.brand.PluginsCommandFormatter.render(
    infos, dev.iyanz.sourbycraft.brand.PluginCategoryMap.loadDefault());
for (String line : rendered.split("\\R")) {
    sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
        .legacySection().deserialize(line));
}
return true;
```

- [ ] **Step 4: Rebuild patches**

Run: `./gradlew rebuildPatches --no-daemon`

- [ ] **Step 5: Build + smoke**

Run: `./gradlew :sourbycraft-server:createMojmapPaperclipJar --no-daemon -Pvariant=normal`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add patches/server/00*-SourbyCraft-v12-plugins-command-formatter.patch
git commit -m "feat(brand): /plugins + /pl — boxed categorized output via PluginsCommandFormatter"
```

---

### Task E4: Compact startup plugin-load log

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/StartupPluginLog.java`
- Create: `patches/server/00XX-SourbyCraft-v12-startup-plugin-log.patch`

- [ ] **Step 1: Write `StartupPluginLog` listener**

```java
package dev.iyanz.sourbycraft.brand;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class StartupPluginLog implements Listener {

    private final long bootStartNanos = System.nanoTime();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent e) {
        if (!dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("branding.compact-plugin-log", Boolean.TRUE)) {
            return;
        }
        PluginCategoryMap cats = PluginCategoryMap.loadDefault();
        Plugin[] all = Bukkit.getPluginManager().getPlugins();
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Plugin p : all) {
            grouped.computeIfAbsent(cats.categoryOf(p.getName()), k -> new java.util.ArrayList<>())
                .add(p.getName());
        }
        long elapsedMs = (System.nanoTime() - bootStartNanos) / 1_000_000;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[SourbyCraft] Plugins ready (%.1fs):%n", elapsedMs / 1000.0));
        String[] order = {"Core", "PvP", "World", "Util", "Economy", "Other"};
        String[] icons = {"⚡", "⚔", "🌍", "🔧", "💰", "·"};
        for (int i = 0; i < order.length; i++) {
            List<String> list = grouped.get(order[i]);
            if (list == null || list.isEmpty()) continue;
            sb.append(String.format("  %s %s (%d)  %s%n",
                icons[i], order[i], list.size(), String.join(" · ", list)));
        }
        org.slf4j.LoggerFactory.getLogger("SourbyCraft").info("\n" + sb);
    }
}
```

- [ ] **Step 2: Apply patches first**

Run: `./gradlew applyAllPatches --no-daemon`

- [ ] **Step 3: Register listener at CraftServer init**

In `CraftServer.java` constructor (after `pluginManager = ...` assignment):

```java
// SourbyCraft v12 — compact startup plugin log
pluginManager.registerEvents(new dev.iyanz.sourbycraft.brand.StartupPluginLog(), /* placeholder plugin */ null);
```

NOTE: `registerEvents` needs a Plugin reference. Use the synthetic SourbyCraft plugin if one exists (search `grep -n 'class.*SourbyCraftPlugin\|new.*JavaPlugin' sourbycraft-server`) — otherwise wire via Paper's internal `ServerEventHandler`. If neither path is clean, defer registration to `ServerLoadEvent` from a synthetic listener attached via reflection — or skip the per-event approach and just call the formatter directly from `MinecraftServer.runServer()` after the load-event broadcast.

Simpler path: call directly from a patch in `MinecraftServer.java`, right after `Bukkit.getServer().getPluginManager().enablePlugins(PluginLoadOrder.POSTWORLD)` completes:

```java
// SourbyCraft v12 — compact startup plugin log (direct invocation)
new dev.iyanz.sourbycraft.brand.StartupPluginLog().onServerLoad(
    new org.bukkit.event.server.ServerLoadEvent(org.bukkit.event.server.ServerLoadEvent.LoadType.STARTUP)
);
```

Use this simpler path.

- [ ] **Step 4: Rebuild patches**

Run: `./gradlew rebuildPatches --no-daemon`

- [ ] **Step 5: Smoke build**

Run: `./gradlew :sourbycraft-server:createMojmapPaperclipJar --no-daemon -Pvariant=pvp`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/StartupPluginLog.java \
        patches/server/00*-SourbyCraft-v12-startup-plugin-log.patch
git commit -m "feat(brand): compact startup plugin log — grouped categorized one-liner per category"
```

---

## Phase F — PvP perf bundle (5 PVP-only patches, 9001-9005)

### Task F1: `9001-PVP-netty-tuning.patch`

**Files:**
- Create: `patches/server/9001-PVP-netty-tuning.patch`

- [ ] **Step 1: Apply patches with variant=pvp (so existing PVP patches if any are present)**

Run: `./gradlew applyAllPatches --no-daemon -Pvariant=pvp`

- [ ] **Step 2: Edit `ServerConnection.java`**

Locate: `sourbycraft-server/src/main/java/net/minecraft/server/network/ServerConnection.java` (or whatever paperweight names it).
Find Netty bootstrap — `bossGroup` / `workerGroup` instantiation. Modify:

```java
// SourbyCraft v12 PVP — netty thread + buffer tuning
int nettyThreads;
String threadsCfg = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("network.netty.threads", "auto");
if ("auto-doubled".equalsIgnoreCase(threadsCfg)) {
    nettyThreads = Runtime.getRuntime().availableProcessors() * 2;
} else if ("auto".equalsIgnoreCase(threadsCfg)) {
    nettyThreads = Runtime.getRuntime().availableProcessors();
} else {
    try { nettyThreads = Integer.parseInt(threadsCfg); } catch (Exception e) { nettyThreads = Runtime.getRuntime().availableProcessors(); }
}
// ... use nettyThreads when constructing EventLoopGroup

int sndKb = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("network.netty.snd-buf-kb", 64);
int rcvKb = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("network.netty.rcv-buf-kb", 64);
// ... .childOption(ChannelOption.SO_SNDBUF, sndKb * 1024)
//     .childOption(ChannelOption.SO_RCVBUF, rcvKb * 1024)
//     .childOption(ChannelOption.TCP_NODELAY, true)
```

- [ ] **Step 3: Edit `Connection.java` — maxPacketsPerTick**

Find `maxPacketsPerTick` (or `MAX_PACKETS_PER_TICK`). Replace constant with config read:

```java
int maxPacketsPerTick = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("network.netty.max-packets-per-tick", 100);
```

- [ ] **Step 4: Rebuild patches, rename to 9001 prefix**

Run: `./gradlew rebuildPatches --no-daemon`
The new patch is auto-numbered (e.g. `00XX-...`). Rename:
```bash
mv patches/server/00*-SourbyCraft-*netty*.patch patches/server/9001-PVP-netty-tuning.patch
```
Verify by re-running `./gradlew clean applyAllPatches -Pvariant=pvp --no-daemon`. If apply fails due to renumber, regenerate from scratch (rebuild after rename).

- [ ] **Step 5: Verify filter excludes patch on normal build**

Run: `./gradlew clean applyAllPatches --no-daemon -Pvariant=normal 2>&1 | grep -i 9001`
Expected: log line `stash PVP patch (normal build): 9001-PVP-netty-tuning.patch`.

- [ ] **Step 6: Commit**

```bash
git add patches/server/9001-PVP-netty-tuning.patch
git commit -m "feat(pvp): 9001 netty tuning — threads, SO_SNDBUF/SO_RCVBUF, max-packets-per-tick"
```

---

### Task F2: `9002-PVP-entity-tracker-tightening.patch`

**Files:**
- Create: `patches/server/9002-PVP-entity-tracker-tightening.patch`

- [ ] **Step 1: Apply existing patches with variant=pvp**

Run: `./gradlew applyAllPatches --no-daemon -Pvariant=pvp`

- [ ] **Step 2: Edit `ServerEntity.java` + `ChunkMap.java`**

In `ChunkMap.java`, find the tracking-range computation for entities. Replace literal max range with:

```java
// SourbyCraft v12 PVP — entity tracker tighter ranges
int mobRange = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("entity-tracker.mob-range", 48);
int itemRange = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("entity-tracker.item-range", 64);
int xpRange = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("entity-tracker.xp-orb-range", 64);

int range;
if (entity instanceof net.minecraft.world.entity.item.ItemEntity) {
    range = Math.min(originalRange, itemRange);
} else if (entity instanceof net.minecraft.world.entity.ExperienceOrb) {
    range = Math.min(originalRange, xpRange);
} else if (entity instanceof net.minecraft.world.entity.Mob) {
    range = Math.min(originalRange, mobRange);
} else {
    range = originalRange;
}
```

In `ServerEntity.java`, find the player update interval. Replace constant 2 with:

```java
int playerInterval = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("entity-tracker.player-update-interval", 2);
// ... use playerInterval in update gate
```

- [ ] **Step 3: Rebuild + rename to 9002**

Run: `./gradlew rebuildPatches --no-daemon`
```bash
mv patches/server/00*-SourbyCraft-*tracker*.patch patches/server/9002-PVP-entity-tracker-tightening.patch
```

- [ ] **Step 4: Verify filter — normal build skips 9002**

Run: `./gradlew clean applyAllPatches --no-daemon -Pvariant=normal 2>&1 | grep 9002`
Expected: `stash PVP patch ... 9002-PVP-entity-tracker-tightening.patch`.

- [ ] **Step 5: Commit**

```bash
git add patches/server/9002-PVP-entity-tracker-tightening.patch
git commit -m "feat(pvp): 9002 entity tracker tightening — mob/item/xp range + player interval"
```

---

### Task F3: `9003-PVP-cpu-pin-gc-banner.patch` (GC advisory only)

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/GcAdvisor.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/brand/GcAdvisorTest.java`
- Create: `patches/server/9003-PVP-cpu-pin-gc-banner.patch`

- [ ] **Step 1: Write failing test for GcAdvisor**

```java
package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GcAdvisorTest {

    @Test
    void zgcAccepted() {
        var r = GcAdvisor.evaluate(List.of("ZGC Cycles", "ZGC Pauses"), List.of(), 4096, 4096);
        assertTrue(r.acceptable());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void g1Accepted() {
        var r = GcAdvisor.evaluate(List.of("G1 Young Generation", "G1 Old Generation"), List.of(), 4096, 4096);
        assertTrue(r.acceptable());
    }

    @Test
    void parallelGcWarns() {
        var r = GcAdvisor.evaluate(List.of("PS Scavenge", "PS MarkSweep"), List.of(), 4096, 4096);
        assertFalse(r.acceptable());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("ParallelGC") || w.contains("non-generational") || w.contains("not")));
    }

    @Test
    void mismatchedXmsXmxWarns() {
        var r = GcAdvisor.evaluate(List.of("G1 Young Generation"), List.of(), 2048, 4096);
        assertFalse(r.acceptable());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("Xms") || w.contains("Xmx")));
    }

    @Test
    void missingAlwaysPreTouchWarns() {
        var r = GcAdvisor.evaluate(List.of("G1 Young Generation"), List.of(), 4096, 4096);
        // Without AlwaysPreTouch in jvm args, should warn
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("PreTouch")));
    }
}
```

- [ ] **Step 2: Run test — FAIL**

Expected: COMPILE FAIL.

- [ ] **Step 3: Write `GcAdvisor`**

```java
package dev.iyanz.sourbycraft.brand;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class GcAdvisor {

    public record Result(boolean acceptable, List<String> warnings) {}

    private GcAdvisor() {}

    public static Result run() {
        List<String> gcNames = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(b -> b.getName()).toList();
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        long xms = parseMemArg(jvmArgs, "-Xms");
        long xmx = parseMemArg(jvmArgs, "-Xmx");
        return evaluate(gcNames, jvmArgs, xms, xmx);
    }

    public static Result evaluate(List<String> gcNames, List<String> jvmArgs, long xms, long xmx) {
        List<String> warns = new ArrayList<>();
        boolean isZgc = gcNames.stream().anyMatch(n -> n.contains("ZGC"));
        boolean isG1 = gcNames.stream().anyMatch(n -> n.contains("G1"));
        if (!isZgc && !isG1) {
            warns.add("GC is not ZGC or G1 — detected: " + gcNames + ". Recommended: -XX:+UseZGC -XX:+ZGenerational");
        }
        if (xms > 0 && xmx > 0 && xms != xmx) {
            warns.add("Xms != Xmx (Xms=" + xms + "MB, Xmx=" + xmx + "MB). Set them equal to avoid heap resize pauses.");
        }
        if (jvmArgs.stream().noneMatch(a -> a.contains("AlwaysPreTouch"))) {
            warns.add("Missing -XX:+AlwaysPreTouch — recommended for predictable PvP latency.");
        }
        return new Result(warns.isEmpty(), warns);
    }

    private static long parseMemArg(List<String> args, String prefix) {
        for (String a : args) {
            if (a.startsWith(prefix)) {
                String v = a.substring(prefix.length()).toLowerCase();
                try {
                    if (v.endsWith("g")) return Long.parseLong(v.substring(0, v.length() - 1)) * 1024;
                    if (v.endsWith("m")) return Long.parseLong(v.substring(0, v.length() - 1));
                    if (v.endsWith("k")) return Long.parseLong(v.substring(0, v.length() - 1)) / 1024;
                    return Long.parseLong(v) / (1024 * 1024);
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    public static String renderWarningBanner(Result r) {
        if (r.acceptable()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════╗\n");
        sb.append("║  ⚠  PVP variant tuned for ZGC generational       ║\n");
        sb.append("╠══════════════════════════════════════════════════╣\n");
        for (String w : r.warnings()) {
            sb.append("║  ").append(String.format("%-46s", w.length() > 46 ? w.substring(0, 43) + "..." : w)).append("║\n");
        }
        sb.append("║                                                  ║\n");
        sb.append("║  Recommended JVM args:                           ║\n");
        sb.append("║    -XX:+UseZGC -XX:+ZGenerational                ║\n");
        sb.append("║    -XX:+AlwaysPreTouch                           ║\n");
        sb.append("║    -XX:+UseLargePages                            ║\n");
        sb.append("║    -Xms=Xmx (same value)                         ║\n");
        sb.append("╚══════════════════════════════════════════════════╝\n");
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run test — PASS**

Run: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.brand.GcAdvisorTest' --no-daemon`
Expected: PASS, 5 tests.

- [ ] **Step 5: Apply existing patches, then patch `MinecraftServer.java` to call advisor**

Run: `./gradlew applyAllPatches --no-daemon -Pvariant=pvp`

In `MinecraftServer.java`, near the startup banner call from Task B3, add right after:

```java
// SourbyCraft v12 PVP — GC advisor
if (dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("branding.gc-advisor.enabled", Boolean.TRUE)) {
    var r = dev.iyanz.sourbycraft.brand.GcAdvisor.run();
    if (!r.acceptable()) {
        System.out.print(dev.iyanz.sourbycraft.brand.GcAdvisor.renderWarningBanner(r));
    }
}
```

- [ ] **Step 6: Rebuild + rename to 9003**

```bash
./gradlew rebuildPatches --no-daemon
mv patches/server/00*-SourbyCraft-*gc*.patch patches/server/9003-PVP-cpu-pin-gc-banner.patch
```

- [ ] **Step 7: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/brand/GcAdvisor.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/brand/GcAdvisorTest.java \
        patches/server/9003-PVP-cpu-pin-gc-banner.patch
git commit -m "feat(pvp): 9003 GC advisory banner — warn on non-ZGC/G1 + Xms!=Xmx + missing AlwaysPreTouch"
```

---

### Task F4: `9004-PVP-combat-completion.patch`

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/combat/ReachTracker.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/ReachCommand.java`
- Create: `patches/server/9004-PVP-combat-completion.patch`

- [ ] **Step 1: Write `ReachTracker` (TDD)**

Create `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/combat/ReachTrackerTest.java`:
```java
package dev.iyanz.sourbycraft.combat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReachTrackerTest {

    @Test
    void recordsLastHit() {
        ReachTracker.record("PlayerA", "PlayerB", 3.42, 85, 150);
        var last = ReachTracker.last();
        assertNotNull(last);
        assertEquals("PlayerA", last.attacker());
        assertEquals("PlayerB", last.target());
        assertEquals(3.42, last.distance(), 0.01);
        assertEquals(85, last.latencyMs());
    }

    @Test
    void overwritesPreviousHit() {
        ReachTracker.record("X", "Y", 1.0, 10, 100);
        ReachTracker.record("PlayerA", "PlayerB", 3.42, 85, 150);
        var last = ReachTracker.last();
        assertEquals("PlayerA", last.attacker());
        assertEquals(85, last.latencyMs());
    }
}
```

`sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/combat/ReachTracker.java`:
```java
package dev.iyanz.sourbycraft.combat;

public final class ReachTracker {

    public record Hit(String attacker, String target, double distance, int latencyMs, int windowMs) {}

    private static volatile Hit last;

    private ReachTracker() {}

    public static void record(String attacker, String target, double distance, int latencyMs, int windowMs) {
        last = new Hit(attacker, target, distance, latencyMs, windowMs);
    }

    public static Hit last() {
        return last;
    }
}
```

Run test: `./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.combat.ReachTrackerTest' --no-daemon`
Expected: PASS.

- [ ] **Step 2: Write `ReachCommand`**

```java
package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.combat.ReachTracker;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;

public class ReachCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        var hit = ReachTracker.last();
        if (hit == null) {
            sender.sendMessage("§7[reach] no hits recorded yet");
            return true;
        }
        sender.sendMessage(String.format(
            "§7[reach] last hit: §f%s §7→ §f%s §7· §a%.2f §7blocks · §e%dms §7latency · §bwindow=%dms §a✓",
            hit.attacker(), hit.target(), hit.distance(), hit.latencyMs(), hit.windowMs()));
        return true;
    }
}
```

- [ ] **Step 3: Apply patches, edit `LivingEntity.java` + `Player.java` + register command**

Run: `./gradlew applyAllPatches --no-daemon -Pvariant=pvp`

In `LivingEntity.java` `sweepAttack()` (or wherever sweep KB is applied):
```java
if (!dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("combat.sweep-enabled", Boolean.TRUE)) {
    return; // PVP variant disables sweep
}
```

In `LivingEntity.java` `invulnerableTime` assignment after damage:
```java
this.invulnerableTime = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("combat.hit-delay-ticks", 10);
```

In `Player.attack()` after damage applied, record reach:
```java
if (dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("combat.reach-debug-command", Boolean.FALSE)) {
    double dist = this.distanceTo(targetEntity);
    int ping = (this instanceof net.minecraft.server.level.ServerPlayer sp) ? sp.connection.latency() : 0;
    int window = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("combat.hit-window-ms", 100);
    dev.iyanz.sourbycraft.combat.ReachTracker.record(
        this.getName().getString(), targetEntity.getName().getString(), dist, ping, window);
}
```

In `FishingHook.retrieve()` (or whichever method applies the pull-impulse to the
hooked entity), find the existing `setDeltaMovement(...)` call. Wrap the pull
with a configurable KB scaled by the PvP friction divisor:

```java
if (hookedEntity instanceof net.minecraft.world.entity.LivingEntity hooked
    && dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("combat.fishing-rod-knockback", Boolean.FALSE)) {
    double frictionDivisor = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
        "pvp.knockback.friction-divisor", 2.0)).doubleValue();
    net.minecraft.world.phys.Vec3 delta = hooked.position().subtract(this.position()).normalize();
    double mag = 0.4 / Math.max(0.1, frictionDivisor);
    hooked.setDeltaMovement(
        hooked.getDeltaMovement().add(-delta.x * mag, 0.1, -delta.z * mag));
    hooked.hurtMarked = true;
}
```

In `CraftServer.loadPlugins()` (or wherever commands register), add:
```java
if (dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet("combat.reach-debug-command", Boolean.FALSE)) {
    commandMap.register("sourbycraft", new org.bukkit.command.defaults.BukkitCommand("reach") {
        private final dev.iyanz.sourbycraft.command.ReachCommand executor = new dev.iyanz.sourbycraft.command.ReachCommand();
        @Override
        public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
            return executor.onCommand(sender, this, label, args);
        }
    });
}
```

- [ ] **Step 4: Rebuild + rename to 9004**

```bash
./gradlew rebuildPatches --no-daemon
mv patches/server/00*-SourbyCraft-*combat*.patch patches/server/9004-PVP-combat-completion.patch
```

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/combat/ReachTracker.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/combat/ReachTrackerTest.java \
        sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/ReachCommand.java \
        patches/server/9004-PVP-combat-completion.patch
git commit -m "feat(pvp): 9004 combat completion — sweep gate, hit-delay, /reach, fishing-rod KB"
```

---

### Task F5: `9005-PVP-proxy-kick.patch`

**Files:**
- Create: `patches/server/9005-PVP-proxy-kick.patch`

- [ ] **Step 1: Apply patches**

Run: `./gradlew applyAllPatches --no-daemon -Pvariant=pvp`

- [ ] **Step 2: Edit `MinecraftServer.halt()` or shutdown hook**

Find shutdown method that fires before player disconnect (Paper convention:
`MinecraftServer.stopServer()` calls `playerList.removeAll()` near the end —
inject before that). Use `ServerGamePacketListenerImpl.send(...)` to push a
raw plugin-message packet directly via NMS — bypasses needing a Bukkit Plugin
handle:

```java
// SourbyCraft v12 PVP — proxy-aware kick grace
int graceSec = ((Number) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
    "network.proxy-kick-grace-seconds", 0)).intValue();
String proxyMode = (String) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
    "network.proxy-mode", "none");
if (graceSec > 0 && proxyMode.contains("velocity")) {
    String fallbackServer = (String) dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(
        "network.proxy-kick-fallback", "lobby");
    io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
    net.minecraft.network.FriendlyByteBuf out = new net.minecraft.network.FriendlyByteBuf(buf);
    try {
        // BungeeCord 'Connect' subchannel format: UTF8 subchannel, UTF8 server
        java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dout = new java.io.DataOutputStream(bout);
        dout.writeUTF("Connect");
        dout.writeUTF(fallbackServer);
        net.minecraft.resources.ResourceLocation channel =
            net.minecraft.resources.ResourceLocation.parse("bungeecord:main");
        for (net.minecraft.server.level.ServerPlayer sp : this.getPlayerList().getPlayers()) {
            sp.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new net.minecraft.network.protocol.common.custom.DiscardedPayload(
                    channel, io.netty.buffer.Unpooled.wrappedBuffer(bout.toByteArray())
                )
            ));
        }
        org.slf4j.LoggerFactory.getLogger("SourbyCraft").info(
            "[proxy] transferred {} players to fallback '{}', waiting {}s before stop",
            this.getPlayerList().getPlayers().size(), fallbackServer, graceSec);
        Thread.sleep(graceSec * 1000L);
    } catch (java.io.IOException | InterruptedException ignored) {
        if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
    } finally {
        buf.release();
    }
}
```

Add `network.proxy-kick-fallback: lobby` to the baseline `sourbycraft.yml`
(retroactively edit Task D1 if missed).

- [ ] **Step 3: Add IP-forward strict guard**

In `ServerLoginPacketListenerImpl.java` (or `LoginListener.handlePacket`) where Velocity forward header is validated, raise log level on missing header from WARN to ERROR when `proxies.velocity.enabled=true`.

- [ ] **Step 4: Rebuild + rename**

```bash
./gradlew rebuildPatches --no-daemon
mv patches/server/00*-SourbyCraft-*proxy*.patch patches/server/9005-PVP-proxy-kick.patch
```

- [ ] **Step 5: Smoke build BOTH variants**

```bash
./gradlew clean createMojmapPaperclipJar --no-daemon -Pvariant=normal
./gradlew clean createMojmapPaperclipJar --no-daemon -Pvariant=pvp
```
Expected: both succeed; output jars named `SourbyCraft-v12-REL.jar` and `SourbyCraft-PVP-v12-REL.jar`.

- [ ] **Step 6: Commit**

```bash
git add patches/server/9005-PVP-proxy-kick.patch
git commit -m "feat(pvp): 9005 proxy-kick grace + strict IP-forward header (PVP only)"
```

---

## Phase G — CI matrix + smoke tests

### Task G1: Update CI workflow for variant matrix

**Files:**
- Modify: `.github/workflows/build.yml`

- [ ] **Step 1: Read current `build.yml`**

Run: `cat .github/workflows/build.yml`

- [ ] **Step 2: Edit to add matrix**

Replace the `build:` job with:
```yaml
jobs:
  build:
    runs-on: ubuntu-24.04
    if: "!contains(github.event.head_commit.message, 'skip ci') && !contains(github.event.head_commit.message, 'ci skip')"
    strategy:
      fail-fast: false
      matrix:
        variant: [normal, pvp]
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Validate gradle wrapper
        uses: gradle/actions/wrapper-validation@v4

      - name: Setup java 21
        uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin
          cache: gradle

      - name: Set git identity
        run: |
          git config --global user.email "no-reply@github.com"
          git config --global user.name "Github Actions"

      - name: Apply patches (variant=${{ matrix.variant }})
        run: ./gradlew applyAllPatches --no-daemon -Pvariant=${{ matrix.variant }}

      - name: Run unit tests
        run: ./gradlew :sourbycraft-server:test --no-daemon -Pvariant=${{ matrix.variant }}

      - name: Build mojang-mapped paperclip jar (variant=${{ matrix.variant }})
        run: ./gradlew createMojmapPaperclipJar --no-daemon -Pvariant=${{ matrix.variant }}

      - name: Verify variant divergence
        run: |
          jar=$(ls sourbycraft-server/build/libs/SourbyCraft*-v12-REL.jar | head -1)
          unzip -p "$jar" META-INF/sourbycraft-build.properties | tee /tmp/build.props
          grep -q "variant=${{ matrix.variant }}" /tmp/build.props

      - name: Upload jar
        uses: actions/upload-artifact@v4
        with:
          name: SourbyCraft-${{ matrix.variant }}
          path: sourbycraft-server/build/libs/SourbyCraft*-v12-REL.jar
          if-no-files-found: error
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/build.yml
git commit -m "ci: matrix [normal, pvp] — build + test + verify divergence per variant"
```

---

### Task G2: Patch parity check script

**Files:**
- Create: `scripts/verify-patch-parity.sh`

- [ ] **Step 1: Write script**

```bash
#!/usr/bin/env bash
# SourbyCraft v12 — verify PVP patch filtering works as designed.
# Run from repo root: bash scripts/verify-patch-parity.sh

set -euo pipefail

shared_count=$(ls patches/server/[0-8]*-*.patch 2>/dev/null | wc -l)
pvp_count=$(ls patches/server/9*-*.patch 2>/dev/null | wc -l)

echo "Shared patches: $shared_count"
echo "PVP patches:    $pvp_count"

if [ "$pvp_count" -lt 5 ]; then
  echo "ERROR: expected at least 5 PVP patches (9001-9005), got $pvp_count" >&2
  exit 1
fi

for f in patches/server/9*-*.patch; do
  name=$(basename "$f")
  if [[ ! "$name" =~ ^9[0-9]{3}-PVP-.+\.patch$ ]]; then
    echo "ERROR: $name does not match 9XXX-PVP-*.patch convention" >&2
    exit 1
  fi
done

echo "OK"
```

- [ ] **Step 2: Make executable + run**

```bash
chmod +x scripts/verify-patch-parity.sh
bash scripts/verify-patch-parity.sh
```
Expected output:
```
Shared patches: 28
PVP patches:    5
OK
```

- [ ] **Step 3: Wire into CI (modify build.yml again)**

Add step before "Apply patches":
```yaml
      - name: Verify patch parity
        run: bash scripts/verify-patch-parity.sh
```

- [ ] **Step 4: Commit**

```bash
git add scripts/verify-patch-parity.sh .github/workflows/build.yml
git commit -m "ci: verify-patch-parity — assert PVP naming convention"
```

---

### Task G3: Operator smoke checklist doc

**Files:**
- Create: `docs/superpowers/specs/2026-06-02-sourbycraft-v12-smoke-checklist.md`

- [ ] **Step 1: Write the smoke checklist**

```markdown
# SourbyCraft v12.0 Smoke Checklist

## Normal jar
1. Drop `SourbyCraft-v12-REL.jar` into fresh server dir
2. Boot. Console expects:
   - Banner shows `Variant: NORMAL`
   - Log: `Loaded variant: NORMAL`
   - No GC advisor warning unless JVM args bad
3. `/ver` shows `Variant: NORMAL · "Lightning Fast Performance · Feature Rich"`
4. `/plugins` shows boxed format with `Lightning Fast · Feature Rich` header
5. `sourbycraft.yml` seeded with `pvp.enabled: false`
6. `server.properties` seeded with `allow-nether=true`, `online-mode=true`
7. `/stop` exits clean

## PVP jar
1. Drop `SourbyCraft-PVP-v12-REL.jar` into fresh server dir
2. Boot. Console expects:
   - Banner shows `Variant: PVP`
   - Log: `Loaded variant: PVP (pvp.enabled=true, view-dist=6, sim-dist=5)`
   - GC advisor warns if not ZGC/G1
   - WARN if `velocity.secret=CHANGE-ME-SEE-DOCS`
3. `/ver` shows `Variant: PVP`
4. `/reach` registered. After hitting dummy: `[reach] last hit: ... · 3.42 blocks · ...ms latency · window=150ms ✓`
5. `/sys` shows `Proxy: Velocity (modern forwarding) · secret OK` (or WARN if unchanged)
6. `sourbycraft.yml` seeded with `pvp.enabled: true`, `view-distance-cap: 6`
7. `server.properties` seeded with `allow-nether=false`, `online-mode=false`
8. `/stop`: 5s proxy-kick grace observed
9. `paper-global.yml` shows `velocity.enabled: true`

## Patch parity
- `bash scripts/verify-patch-parity.sh` → `OK`

## Build artifacts
- `release/SourbyCraft-v12-REL.jar` present
- `release/SourbyCraft-PVP-v12-REL.jar` present
- `release/checksums.txt` lists both
```

- [ ] **Step 2: Commit**

```bash
git add -f docs/superpowers/specs/2026-06-02-sourbycraft-v12-smoke-checklist.md
git commit -m "docs: v12 smoke checklist for operator verification"
```

---

## Phase H — Docs + release prep

### Task H1: Update README with tagline + variant docs

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Read current README header**

Run: `head -20 README.md`

- [ ] **Step 2: Replace top header with tagline-first layout**

Replace lines 1-12 of README.md with:
```markdown
<h1 align="center">⚡ SourbyCraft</h1>

<p align="center"><strong>Lightning Fast Performance · Feature Rich</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/minecraft-1.21.11-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/version-v12--REL-orange?style=flat-square">
  <img src="https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square">
</p>

<p align="center"><em>High-performance Paper fork with built-in security, anti-xray, dynamic scaling, SWM, NeoForge mod support, and a PvP-arena variant. Fork of <a href="https://github.com/PaperMC/Paper">Paper</a> and <a href="https://github.com/pufferfish-gg/Pufferfish">Pufferfish</a>.</em></p>

---

## Variants

| Variant | JAR | When to use |
|---|---|---|
| **Normal** | `SourbyCraft-v12-REL.jar` | General-purpose SMP. Default. |
| **PVP** | `SourbyCraft-PVP-v12-REL.jar` | PvP arena backend. 1.8-style KB, no-cooldown, Velocity-tuned, allow-nether=false. |

Build: `./gradlew createMojmapPaperclipJar -Pvariant=normal|pvp`

---
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: README v12 — tagline header + variant table"
```

---

### Task H2: Final integration smoke

- [ ] **Step 1: Clean build both variants from scratch**

```bash
./gradlew clean
./gradlew applyAllPatches createMojmapPaperclipJar --no-daemon -Pvariant=normal
ls sourbycraft-server/build/libs/SourbyCraft-v12-REL.jar
./gradlew clean
./gradlew applyAllPatches createMojmapPaperclipJar --no-daemon -Pvariant=pvp
ls sourbycraft-server/build/libs/SourbyCraft-PVP-v12-REL.jar
```
Expected: both files exist.

- [ ] **Step 2: Verify build.properties divergence**

```bash
unzip -p sourbycraft-server/build/libs/SourbyCraft-v12-REL.jar META-INF/sourbycraft-build.properties
unzip -p sourbycraft-server/build/libs/SourbyCraft-PVP-v12-REL.jar META-INF/sourbycraft-build.properties
```
Expected: first shows `variant=normal`, second shows `variant=pvp`.

- [ ] **Step 3: Run all tests once more**

Run: `./gradlew :sourbycraft-server:test --no-daemon`
Expected: ALL PASS.

- [ ] **Step 4: Compute checksums + write release notes**

```bash
mkdir -p release
cp sourbycraft-server/build/libs/SourbyCraft-v12-REL.jar release/
cp sourbycraft-server/build/libs/SourbyCraft-PVP-v12-REL.jar release/
( cd release && sha256sum SourbyCraft*.jar > checksums.txt )
cat release/checksums.txt
```

- [ ] **Step 5: Write `release/RELEASE-NOTES-v12.md`**

```markdown
# SourbyCraft v12.0-REL

**Tagline:** Lightning Fast Performance · Feature Rich

## Two JARs

- `SourbyCraft-v12-REL.jar` — general-purpose SMP (default)
- `SourbyCraft-PVP-v12-REL.jar` — PvP arena backend (Velocity-tuned, 1.8-style KB, allow-nether=false)

Both built from same codebase via `-Pvariant=normal|pvp`. PVP variant adds 5 PvP-only NMS patches (9001–9005), proxy backend defaults, plugin auto-installer manifest tuned for PvP, and distinct branding.

## Highlights
- Build-time variant split (`-Pvariant=pvp`)
- New PvP patches: netty tuning, entity-tracker tightening, GC advisory banner, combat completion (sweep gate, hit-delay, /reach, fishing-rod KB), proxy-kick grace
- Plugin auto-installer (variant-specific manifest)
- Reformatted `/plugins` + `/pl` — boxed, categorized, colored
- Compact startup plugin log
- Velocity/Bungee backend pre-tuning (PVP only)

## Migration v11 → v12
- Single-jar users: download normal variant. Existing `sourbycraft.yml` preserved (variant overlay seeds only on fresh install).
- PvP-tuned users: download PVP variant. Operator may merge new defaults manually.

See `docs/superpowers/specs/2026-06-02-sourbycraft-v12-smoke-checklist.md` for operator smoke checklist.
```

- [ ] **Step 6: Commit**

```bash
git add -f release/RELEASE-NOTES-v12.md release/checksums.txt
git commit -m "release: v12.0-REL artifacts + release notes + checksums"
```

---

## Spec-coverage cross-reference

| Spec section | Implementing tasks |
|---|---|
| §1 Build system | A1, A2, A5 |
| §2 Variant config | A3, A4, D1, D2, D3 |
| §3 Plugin bundling | C1, C2, C3, C4, C5, C6 |
| §4 Branding | A4, B1, B2, B3, B4, H1 |
| §5 /plugins, /pl, startup log | E1, E2, E3, E4 |
| §6 Sub-server method | D3 (PVP overlays), F5 |
| §7.1 Netty tuning | F1 |
| §7.2 Entity tracker | F2 |
| §7.3 GC advisor | F3 |
| §7.4 Combat completion | F4 |
| §7.5 Proxy kick | F5 |
| §8 Testing + rollout | G1, G2, G3, H2 |
