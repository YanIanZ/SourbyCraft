# Sourby Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Slim the SourbyCraft release jar from ~57M to ~30M by externalizing 7 optional libs + the speedtest binary, downloaded on first boot with SHA-256 verification via a new `SourbyBootstrap` main-class wrapper.

**Architecture:** Build-time gradle task `createSlimPaperclipJar` post-processes the paperclip output — strips target lib entries from `META-INF/libraries/...`, rewrites `META-INF/libraries.list`, embeds `META-INF/sourby-bootstrap-manifest.json` with `{paperclipPath, downloadUrl, sha256, sizeBytes}` per entry, and sets `Main-Class` to `dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap`. Runtime: SourbyBootstrap reads the manifest, downloads missing libs sequentially via JDK HttpClient, verifies SHA-256, places each into `libraries/<paperclipPath>`, then delegates to `io.papermc.paperclip.Main`. Hard-fail on any failure with operator-actionable diagnostics.

**Tech Stack:** Java 25 (JDK HttpClient + MessageDigest only; no external HTTP libs since those are what we're externalizing), gradle Kotlin DSL, paperweight v2.0-beta.19, jar-manipulation via `java.util.jar.JarFile` + `JarOutputStream`.

**Spec:** `docs/superpowers/specs/2026-06-06-sourby-bootstrap-design.md` (committed `029eaa2`).

---

## Deviations / Adaptations From Spec (documented inline)

1. **SpeedtestCommand lives in `paper-server/src/main/java/...`** (added via patch `0013-consolidate-all-commands-in-single-patch.patch`). Editing it requires either editing that patch file or producing a new patch. Plan picks the second: add a new dedicated NMS patch `0046-SourbyCraft-sourby-bootstrap-speedtest-lazy-download.patch` that supersedes the relevant section of `0013` — same as how P1 added `0045` for the sensor tick hook. This avoids merge churn in `0013`.

2. **Bundled speedtest is single-OS** (`sourbycraft-server/src/main/resources/speedtest` is a Linux x86-64 ELF binary; verified via `file`). Existing SpeedtestCommand only works on Linux today. Phase 4 preserves Linux-only behavior — downloads Ookla Linux x86-64 tarball, extracts the `speedtest` binary into `libraries/speedtest/linux-x86_64/speedtest`, sets +x, runs. Multi-OS expansion deferred to a follow-on sub-spec (Section 8 spec item #10 implicitly).

3. **Slim jar size target** with single-OS speedtest cut + 7 maven libs = ~30M (close to spec target). Multi-OS would not change build-time jar size (binary not bundled anyway).

4. **`assembleReleaseArtifacts` task already exists** at root `build.gradle.kts:158`. Plan modifies this existing task rather than creating a new one. Change `mojmapOutputs` to depend on `:createSlimPaperclipJar` instead of `:sourbycraft-server:createMojmapPaperclipJar`.

5. **Gradle task implementation language**: `createSlimPaperclipJar` is plain Kotlin in `build.gradle.kts`. No new gradle plugin or buildSrc module needed.

---

## File Structure

**Created:**
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/BootstrapManifest.java` (record + nested Entry record)
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/Sha256Verifier.java` (package-private utility)
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/LibDownloader.java` (package-private utility)
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/SourbyBootstrap.java` (public; jar Main-Class)
- `patches/minecraft/0046-SourbyCraft-sourby-bootstrap-speedtest-lazy-download.patch` (Phase 4 — rewrites SpeedtestCommand to download from Ookla)

**Modified:**
- `build.gradle.kts` (root) — adds `data class LibSpec`, `externalLibs` list, `tasks.register("createSlimPaperclipJar")` block; rewires `assembleReleaseArtifacts` source
- `README.md` — adds "First Boot" section
- `release/RELEASE-NOTES-12.md` — adds first-boot migration note

**Deleted:**
- `sourbycraft-server/src/main/resources/speedtest` (the bundled Linux ELF binary)

---

## TDD adaptation

Per project policy (`feedback-no-smoke-harness` memory): NO automated test surface added. NO JUnit. NO bash smoke. Verification is operator-driven, recorded inline at each phase.

Each phase ends with a `unzip -p`/`grep`/manual boot verification sequence the engineer runs locally before committing.

---

## Task 1: Java skeleton — `BootstrapManifest` + `Sha256Verifier` + `LibDownloader`

**Goal:** Land the three pure-Java utility classes. Compile-only gate. No gradle wiring; the slim jar doesn't exist yet so these classes are unused at runtime.

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/BootstrapManifest.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/Sha256Verifier.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/LibDownloader.java`

- [ ] **Step 1: Create `BootstrapManifest.java`**

```java
package dev.iyanz.sourbycraft.bootstrap;

import java.util.List;

/**
 * Immutable manifest of libraries the SourbyBootstrap must materialize before
 * delegating to Paperclip. Baked into the slim jar at build time as
 * META-INF/sourby-bootstrap-manifest.json by the createSlimPaperclipJar gradle task.
 */
public record BootstrapManifest(List<Entry> entries) {

    /**
     * One downloadable library or resource bundle.
     *
     * @param paperclipPath  destination relative to the paperclip libraries/ dir
     *                       (e.g. "org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar")
     * @param downloadUrl    direct URL to fetch the file from
     * @param sha256         hex-encoded SHA-256 of the file (64 chars, lowercase)
     * @param sizeBytes      expected length in bytes
     */
    public record Entry(String paperclipPath, String downloadUrl, String sha256, long sizeBytes) {}
}
```

- [ ] **Step 2: Create `Sha256Verifier.java`**

```java
package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Sha256Verifier {

    private Sha256Verifier() {}

    static String ofFile(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable in this JDK", e);
        }
    }

    static boolean matches(Path file, String expected) throws IOException {
        return expected.equalsIgnoreCase(ofFile(file));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
```

- [ ] **Step 3: Create `LibDownloader.java`**

```java
package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

final class LibDownloader {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private LibDownloader() {}

    /**
     * Downloads an entry into {@code librariesDir} at its declared paperclipPath.
     * Returns false on cache-hit (existing file matches SHA-256), true after a fresh download.
     * Throws IOException on any network, size, or hash failure.
     */
    static boolean ensure(BootstrapManifest.Entry entry, Path librariesDir) throws IOException {
        Path dest = librariesDir.resolve(entry.paperclipPath());
        if (Files.exists(dest) && Sha256Verifier.matches(dest, entry.sha256())) {
            return false;
        }
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);

        HttpRequest req = HttpRequest.newBuilder(URI.create(entry.downloadUrl()))
            .timeout(Duration.ofMinutes(5))
            .GET().build();
        HttpResponse<Path> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during download of " + entry.downloadUrl(), e);
        }
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("HTTP " + resp.statusCode() + " for " + entry.downloadUrl());
        }
        if (Files.size(tmp) != entry.sizeBytes()) {
            Files.deleteIfExists(tmp);
            throw new IOException("size mismatch for " + entry.paperclipPath()
                + ": got " + Files.size(tmp) + ", expected " + entry.sizeBytes());
        }
        String actual = Sha256Verifier.ofFile(tmp);
        if (!entry.sha256().equalsIgnoreCase(actual)) {
            Files.deleteIfExists(tmp);
            throw new IOException("SHA-256 mismatch for " + entry.paperclipPath()
                + ": got " + actual + ", expected " + entry.sha256());
        }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
```

- [ ] **Step 4: Compile**

```bash
./gradlew :sourbycraft-server:classes
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/BootstrapManifest.java \
        sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/Sha256Verifier.java \
        sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/LibDownloader.java
git commit -m "feat: sourby-bootstrap — manifest + sha256 + downloader utilities"
```

---

## Task 2: `SourbyBootstrap` main class

**Goal:** Land the wrapper entry point. Compile-only gate. The Main-Class header is still paperclip's; SourbyBootstrap is unused at runtime until Task 3 lands gradle wiring.

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/SourbyBootstrap.java`

- [ ] **Step 1: Create `SourbyBootstrap.java`**

```java
package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry point for the slim SourbyCraft paperclip jar. Reads its bundled manifest,
 * downloads any missing libraries into the paperclip libraries/ dir with SHA-256
 * verification, then delegates to io.papermc.paperclip.Main.
 *
 * <p>Uses only JDK classes until the delegate call — every externalized library
 * is potentially missing at that point.
 *
 * <p>Hard-fails on any error: prints actionable diagnostics + exits non-zero.
 */
public final class SourbyBootstrap {

    public static void main(String[] args) throws Throwable {
        Path librariesDir = Paths.get("libraries");
        Files.createDirectories(librariesDir);

        BootstrapManifest manifest;
        try {
            manifest = loadManifest();
        } catch (IOException e) {
            System.err.println("[SourbyBootstrap] FATAL: cannot read bundled manifest: " + e.getMessage());
            System.exit(2);
            return;
        }

        long startNs = System.nanoTime();
        int downloaded = 0;
        long totalBytes = 0;
        for (BootstrapManifest.Entry entry : manifest.entries()) {
            try {
                if (LibDownloader.ensure(entry, librariesDir)) {
                    downloaded++;
                    totalBytes += entry.sizeBytes();
                    System.out.println("[SourbyBootstrap] downloaded "
                        + entry.paperclipPath()
                        + " (" + (entry.sizeBytes() / 1024 / 1024) + "M)");
                }
            } catch (IOException e) {
                System.err.println("[SourbyBootstrap] FATAL: cannot fetch "
                    + entry.paperclipPath() + " from " + entry.downloadUrl()
                    + ": " + e.getMessage());
                System.err.println("[SourbyBootstrap] If your server has no internet access on first boot,");
                System.err.println("[SourbyBootstrap] download the libraries manually and place them at:");
                for (BootstrapManifest.Entry e2 : manifest.entries()) {
                    System.err.println("[SourbyBootstrap]   libraries/" + e2.paperclipPath()
                        + "  <-  " + e2.downloadUrl());
                }
                System.exit(3);
                return;
            }
        }
        if (downloaded > 0) {
            long secs = (System.nanoTime() - startNs) / 1_000_000_000L;
            System.out.println("[SourbyBootstrap] downloaded " + downloaded + " libraries ("
                + (totalBytes / 1024 / 1024) + "M) in " + secs + "s");
        }

        Class<?> paperclipMain = Class.forName("io.papermc.paperclip.Main");
        paperclipMain.getMethod("main", String[].class).invoke(null, (Object) args);
    }

    static BootstrapManifest loadManifest() throws IOException {
        try (InputStream in = SourbyBootstrap.class
                .getResourceAsStream("/META-INF/sourby-bootstrap-manifest.json")) {
            if (in == null) throw new IOException("META-INF/sourby-bootstrap-manifest.json not found in jar");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(json);
        }
    }

    /** Minimal regex-based parser. Manifest shape is deterministic (gradle-generated). */
    static BootstrapManifest parse(String json) throws IOException {
        Pattern entryPat = Pattern.compile(
            "\\{\\s*\"paperclipPath\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*"
            + "\"downloadUrl\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*"
            + "\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"\\s*,\\s*"
            + "\"sizeBytes\"\\s*:\\s*(\\d+)\\s*\\}");
        List<BootstrapManifest.Entry> entries = new ArrayList<>();
        Matcher m = entryPat.matcher(json);
        while (m.find()) {
            entries.add(new BootstrapManifest.Entry(
                m.group(1), m.group(2), m.group(3).toLowerCase(),
                Long.parseLong(m.group(4))));
        }
        if (entries.isEmpty()) throw new IOException("manifest has no entries (parse failed?)");
        return new BootstrapManifest(List.copyOf(entries));
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :sourbycraft-server:classes
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/SourbyBootstrap.java
git commit -m "feat: sourby-bootstrap — SourbyBootstrap main shim with manifest parse + delegate"
```

---

## Task 3: Gradle slim task + `assembleReleaseArtifacts` rewire

**Goal:** Land `createSlimPaperclipJar` task in root `build.gradle.kts`. Rewire `assembleReleaseArtifacts` to use the slim jar. Build slim jar locally; verify shape with `unzip`.

**Files:**
- Modify: `build.gradle.kts` (root)

- [ ] **Step 1: Add `LibSpec` + `externalLibs` list at the top of `build.gradle.kts`**

Open `build.gradle.kts`. After the existing imports (around line 8-10) and BEFORE the `plugins { ... }` block, insert:

```kotlin
data class LibSpec(val paperclipPath: String, val downloadUrl: String)

val externalLibs = listOf(
    LibSpec(
        "org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar",
        "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar"
    ),
    LibSpec(
        "me/lucko/spark-paper/1.10.152/spark-paper-1.10.152.jar",
        "https://repo.lucko.me/me/lucko/spark-paper/1.10.152/spark-paper-1.10.152.jar"
    ),
    LibSpec(
        "com/mysql/mysql-connector-j/9.2.0/mysql-connector-j-9.2.0.jar",
        "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/9.2.0/mysql-connector-j-9.2.0.jar"
    ),
    LibSpec(
        "com/github/technove/Flare/34637f3f87/Flare-34637f3f87.jar",
        "https://jitpack.io/com/github/technove/Flare/34637f3f87/Flare-34637f3f87.jar"
    ),
    LibSpec(
        "com/google/protobuf/protobuf-java/4.29.0/protobuf-java-4.29.0.jar",
        "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/4.29.0/protobuf-java-4.29.0.jar"
    ),
    LibSpec(
        "io/papermc/parchment/data/parchment/1.21.11-pre3+build.2/parchment-1.21.11-pre3+build.2.jar",
        "https://maven.parchmentmc.org/io/papermc/parchment/data/parchment/1.21.11-pre3+build.2/parchment-1.21.11-pre3+build.2.jar"
    ),
    LibSpec(
        "io/sentry/sentry/7.15.0/sentry-7.15.0.jar",
        "https://repo1.maven.org/maven2/io/sentry/sentry/7.15.0/sentry-7.15.0.jar"
    )
)
```

NOTE: parchment version is hard-coded here. Verify by running `unzip -l release/SourbyCraft-12-REL.jar | grep parchment` against the current fat jar that the parchment paperclip path matches the LibSpec above. If parchment version differs at impl time, update the path + URL accordingly.

- [ ] **Step 2: Add `createSlimPaperclipJar` task block**

Find the existing `tasks.register("assembleReleaseArtifacts") { ... }` block (around line 158). Insert a new task block IMMEDIATELY BEFORE it:

```kotlin
tasks.register("createSlimPaperclipJar") {
    group = "build"
    description = "Strip optional libs from paperclip jar + generate bootstrap manifest"

    val fatJarTask = project(":sourbycraft-server").tasks.named("createMojmapPaperclipJar")
    dependsOn(fatJarTask)
    inputs.files(fatJarTask.map { it.outputs.files })

    val slimJar = layout.buildDirectory.file("libs/SourbyCraft-slim.jar")
    outputs.file(slimJar)

    doLast {
        val fatJarFile = fatJarTask.get().outputs.files.files
            .filter { it.name.endsWith(".jar") && it.exists() }
            .firstOrNull() ?: error("No jar output from createMojmapPaperclipJar")
        val out = slimJar.get().asFile
        out.parentFile.mkdirs()

        // Step A: enumerate externalized lib bytes + compute sha256 + size
        val externalizedPaths = externalLibs.map { it.paperclipPath }.toSet()
        val externalizedJarEntries = externalizedPaths.map { "META-INF/libraries/$it" }.toSet()
        val manifestEntries = mutableListOf<Map<String, Any>>()

        java.util.jar.JarFile(fatJarFile).use { jar ->
            for (lib in externalLibs) {
                val entryName = "META-INF/libraries/${lib.paperclipPath}"
                val entry = jar.getJarEntry(entryName)
                    ?: error("fat jar missing expected entry: $entryName " +
                        "(check externalLibs against current paperclip output)")
                val bytes = jar.getInputStream(entry).use { it.readAllBytes() }
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val sha256 = md.digest(bytes).joinToString("") { "%02x".format(it) }
                manifestEntries.add(linkedMapOf(
                    "paperclipPath" to lib.paperclipPath,
                    "downloadUrl"   to lib.downloadUrl,
                    "sha256"        to sha256,
                    "sizeBytes"     to bytes.size.toLong()
                ))
            }
        }

        // Step B: build manifest JSON string (deterministic order)
        val manifestJson = buildString {
            append("{\"entries\":[")
            manifestEntries.forEachIndexed { i, e ->
                if (i > 0) append(",")
                append("{")
                append("\"paperclipPath\":\"${e["paperclipPath"]}\",")
                append("\"downloadUrl\":\"${e["downloadUrl"]}\",")
                append("\"sha256\":\"${e["sha256"]}\",")
                append("\"sizeBytes\":${e["sizeBytes"]}")
                append("}")
            }
            append("]}")
        }

        // Step C: read + filter libraries.list
        val filteredLibrariesList: String = java.util.jar.JarFile(fatJarFile).use { jar ->
            val listEntry = jar.getJarEntry("META-INF/libraries.list")
                ?: error("fat jar missing META-INF/libraries.list")
            val original = jar.getInputStream(listEntry).use { String(it.readAllBytes(), Charsets.UTF_8) }
            original.lines()
                .filter { line ->
                    val parts = line.trim().split('\t')
                    // libraries.list format is "<sha> <maven-coords> <relative-path>" (3 tab-separated columns).
                    // Filter by the path column (index 2). Keep lines that don't match any externalized path.
                    if (parts.size < 3) true
                    else !externalizedPaths.contains(parts[2])
                }
                .joinToString("\n")
        }

        // Step D: read + rewrite MANIFEST.MF (replace Main-Class)
        val newManifestMf: String = java.util.jar.JarFile(fatJarFile).use { jar ->
            val mfEntry = jar.getJarEntry("META-INF/MANIFEST.MF")
                ?: error("fat jar missing META-INF/MANIFEST.MF")
            val original = jar.getInputStream(mfEntry).use { String(it.readAllBytes(), Charsets.UTF_8) }
            // Manifest format: lines like "Header-Name: value\r\n". Replace existing Main-Class or append.
            val mainClassRe = Regex("(?m)^Main-Class:.*\\r?\\n?")
            val replacement = "Main-Class: dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap\r\n"
            if (mainClassRe.containsMatchIn(original)) {
                original.replace(mainClassRe, replacement)
            } else {
                // MANIFEST.MF ends with a blank line; append before it
                original.trimEnd() + "\r\n" + replacement + "\r\n"
            }
        }

        // Step E: write slim jar
        java.util.jar.JarFile(fatJarFile).use { jar ->
            java.util.jar.JarOutputStream(out.outputStream().buffered()).use { jos ->
                for (entry in jar.entries()) {
                    val name = entry.name
                    when {
                        name in externalizedJarEntries -> continue                     // skip externalized libs
                        name == "META-INF/libraries.list" -> continue                  // replaced below
                        name == "META-INF/MANIFEST.MF" -> continue                     // replaced below
                        name == "speedtest" -> continue                                // bundled linux binary; Phase 4 replaces
                        else -> {
                            jos.putNextEntry(java.util.jar.JarEntry(name))
                            jar.getInputStream(entry).use { it.copyTo(jos) }
                            jos.closeEntry()
                        }
                    }
                }
                // libraries.list (filtered)
                jos.putNextEntry(java.util.jar.JarEntry("META-INF/libraries.list"))
                jos.write(filteredLibrariesList.toByteArray(Charsets.UTF_8))
                jos.closeEntry()
                // sourby-bootstrap-manifest.json
                jos.putNextEntry(java.util.jar.JarEntry("META-INF/sourby-bootstrap-manifest.json"))
                jos.write(manifestJson.toByteArray(Charsets.UTF_8))
                jos.closeEntry()
                // MANIFEST.MF (rewritten)
                jos.putNextEntry(java.util.jar.JarEntry("META-INF/MANIFEST.MF"))
                jos.write(newManifestMf.toByteArray(Charsets.UTF_8))
                jos.closeEntry()
            }
        }

        logger.lifecycle("createSlimPaperclipJar: wrote ${out.length() / 1024 / 1024}M to ${out.absolutePath}")
    }
}
```

NOTE: the `name == "speedtest"` skip handles Phase 4's deletion in advance — Phase 1-3 the resource still exists in the fat jar and would be copied through; this filter drops it. After Phase 4 deletes the resource, the filter is a no-op (resource no longer in the fat jar). Safe in both states.

- [ ] **Step 3: Rewire `assembleReleaseArtifacts` to use slim jar**

In the existing `tasks.register("assembleReleaseArtifacts") { ... }` block, find these two lines (around lines 165-168):

```kotlin
    val mojmapOutputs = project(":sourbycraft-server").tasks
        .named("createMojmapPaperclipJar").map { it.outputs.files }

    dependsOn(":sourbycraft-server:createMojmapPaperclipJar")
```

Replace with:

```kotlin
    val mojmapOutputs = tasks.named("createSlimPaperclipJar").map { it.outputs.files }

    dependsOn("createSlimPaperclipJar")
```

This makes `assembleReleaseArtifacts` consume the slim jar as its release input. The `firstJarFrom(...)` helper inside the task already picks the first `.jar` file in the outputs, which is now `SourbyCraft-slim.jar`.

- [ ] **Step 4: Verify gradle task is registered**

```bash
./gradlew tasks --all | grep createSlimPaperclipJar
```

Expected: line `createSlimPaperclipJar - Strip optional libs from paperclip jar + generate bootstrap manifest`.

- [ ] **Step 5: Build slim jar**

```bash
./gradlew assembleReleaseArtifacts 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. `createSlimPaperclipJar: wrote NNM to ...` line appears. Final line `SourbyCraft release: SourbyCraft-12-REL.jar`.

- [ ] **Step 6: Verify slim jar size + contents**

```bash
ls -lh release/SourbyCraft-12-REL.jar
```

Expected: size ~32-35M (slim minus speedtest cut not yet done in Phase 4; speedtest cut moves us to ~30M).

```bash
unzip -p release/SourbyCraft-12-REL.jar META-INF/MANIFEST.MF | grep Main-Class
```

Expected: `Main-Class: dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap`.

```bash
unzip -p release/SourbyCraft-12-REL.jar META-INF/sourby-bootstrap-manifest.json | python3 -m json.tool | head -30
```

Expected: valid JSON with 7 `entries`, each with `paperclipPath`, `downloadUrl`, `sha256` (64 hex chars), `sizeBytes` (number).

```bash
unzip -l release/SourbyCraft-12-REL.jar | grep -E "sqlite-jdbc|mysql-connector|spark-paper|Flare|protobuf-java|sentry/sentry|parchment-1"
```

Expected: empty output (all 7 externalized libs no longer in jar).

```bash
unzip -p release/SourbyCraft-12-REL.jar META-INF/libraries.list | grep -c sqlite-jdbc
```

Expected: `0`.

If any check fails: STOP and debug. The `name == "META-INF/libraries/${lib.paperclipPath}"` join must produce the exact entry name as in the fat jar. Common bug: paperclip's path uses forward slashes on Windows too (jar paths are POSIX), but Kotlin's string interpolation is fine.

If parchment path mismatch (Step 1 NOTE): the gradle task errors with `fat jar missing expected entry: META-INF/libraries/io/papermc/parchment/data/parchment/...`. Fix `externalLibs` LibSpec for parchment to match the actual paperclip-baked version.

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts release/SourbyCraft-12-REL.jar release/checksums.txt
git commit -m "build: sourby-bootstrap — createSlimPaperclipJar gradle task

Adds Kotlin DSL LibSpec list (7 externalized maven libs) + new
createSlimPaperclipJar task that post-processes paperclip output:
strips externalized lib entries from META-INF/libraries/, rewrites
META-INF/libraries.list, embeds META-INF/sourby-bootstrap-manifest.json
with {paperclipPath, downloadUrl, sha256, sizeBytes} per entry, and
sets Main-Class to dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap.
Rewires assembleReleaseArtifacts to consume the slim jar."
```

---

## Task 4: First-boot validation (operator-run)

**Goal:** Operator manually boots the slim jar in `test-harness/TestServer-mojmap/` and verifies first-boot download + second-boot cache-hit work end-to-end. NO code changes unless a bug is discovered. If a bug is found, fix `SourbyBootstrap.java` / `LibDownloader.java` / gradle task inline and re-test.

**Files:** none modified unless bug found.

- [ ] **Step 1: Stage the slim jar into TestServer**

```bash
cp release/SourbyCraft-12-REL.jar test-harness/TestServer-mojmap/server.jar
echo "eula=true" > test-harness/TestServer-mojmap/eula.txt
rm -rf test-harness/TestServer-mojmap/libraries  # force first-boot path
```

Note: deleting `libraries/` forces every entry into the download path; sane for first-boot validation.

- [ ] **Step 2: Boot online (network available)**

```bash
cd test-harness/TestServer-mojmap
java -Xmx2G -jar server.jar nogui 2>&1 | tee boot.log &
BOOT_PID=$!
```

Watch boot.log for `[SourbyBootstrap] downloaded <lib> (NM)` lines (one per externalized lib) followed by `[SourbyBootstrap] downloaded N libraries (XM) in Ys`, then `Done (`.

```bash
# In another shell or use timeout:
until grep -q "Done (" boot.log; do sleep 2; if ! kill -0 $BOOT_PID 2>/dev/null; then echo "DIED"; tail -50 boot.log; break; fi; done
echo "BOOT OK"
```

Expected: 7 download lines, total time 1-5 minutes depending on connection, `Done (` reached.

- [ ] **Step 3: Verify `libraries/` contains all 7 paths**

```bash
ls -lh libraries/org/xerial/sqlite-jdbc/3.49.1.0/
ls -lh libraries/me/lucko/spark-paper/1.10.152/
ls -lh libraries/com/mysql/mysql-connector-j/9.2.0/
ls -lh libraries/com/github/technove/Flare/34637f3f87/
ls -lh libraries/com/google/protobuf/protobuf-java/4.29.0/
ls -lh libraries/io/papermc/parchment/data/parchment/1.21.11-pre3+build.2/
ls -lh libraries/io/sentry/sentry/7.15.0/
```

Expected: each dir contains the appropriate `.jar` file.

- [ ] **Step 4: Shutdown server**

```bash
# Send 'stop' via console if interactive, OR:
kill -TERM $BOOT_PID; sleep 5; kill -KILL $BOOT_PID 2>/dev/null || true
```

- [ ] **Step 5: Reboot (cache-hit fast path)**

```bash
cd test-harness/TestServer-mojmap
rm -f boot.log
java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
BOOT_PID=$!
until grep -q "Done (" boot.log; do sleep 2; if ! kill -0 $BOOT_PID 2>/dev/null; then echo "DIED"; tail -50 boot.log; break; fi; done
```

- [ ] **Step 6: Verify silent fast path**

```bash
grep "\\[SourbyBootstrap\\]" boot.log
```

Expected: NO output. Bootstrap saw all libs in `libraries/` matching SHA-256, took the cache-hit branch, printed nothing. (If it printed `downloaded ...` lines: cache check is broken — investigate `Sha256Verifier.matches` against the on-disk file.)

- [ ] **Step 7: Shutdown + commit only if fixes needed**

```bash
kill -TERM $BOOT_PID; sleep 5; kill -KILL $BOOT_PID 2>/dev/null || true
```

If no bugs found: no commit, proceed to Task 5.

If bugs found: fix inline in `SourbyBootstrap.java` / `LibDownloader.java` / `build.gradle.kts`, rebuild `assembleReleaseArtifacts`, re-run steps 1-6, commit:

```bash
git add <fixed-files>
git commit -m "fix: sourby-bootstrap — <specific adjustment>"
```

---

## Task 5: Speedtest lazy download (Phase 4 of spec)

**Goal:** Delete the bundled Linux speedtest binary from jar resources. Edit `SpeedtestCommand.java` (via new NMS patch) to download from Ookla on first invocation, extract the binary into `libraries/speedtest/linux-x86_64/speedtest`, set executable bit, run. Re-build; slim jar drops another ~2.5M.

**Files:**
- Delete: `sourbycraft-server/src/main/resources/speedtest`
- Modify: `paper-server/src/main/java/dev/iyanz/sourbycraft/command/SpeedtestCommand.java` (via new patch — see below)
- Create: `patches/minecraft/0046-SourbyCraft-sourby-bootstrap-speedtest-lazy-download.patch`

- [ ] **Step 1: Delete the bundled binary**

```bash
git rm sourbycraft-server/src/main/resources/speedtest
```

- [ ] **Step 2: Inspect existing SpeedtestCommand patched form**

```bash
grep -n "speedtest" patches/server/0013-consolidate-all-commands-in-single-patch.patch | head -20
```

This confirms the file is currently introduced via patch 0013. Phase 5 doesn't edit 0013 — it produces a NEW patch on top that supersedes the relevant section.

- [ ] **Step 3: Apply existing patches to working tree**

```bash
./gradlew applyAllPatches --offline 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL. Verifies clean baseline before our edit.

- [ ] **Step 4: Locate the materialized SpeedtestCommand.java**

```bash
find . -name SpeedtestCommand.java -not -path '*/build/*' -not -path '*/.gradle/*' | head -3
```

Expected: `./paper-server/src/main/java/dev/iyanz/sourbycraft/command/SpeedtestCommand.java`.

- [ ] **Step 5: Replace the body of `SpeedtestCommand.java`**

Open `paper-server/src/main/java/dev/iyanz/sourbycraft/command/SpeedtestCommand.java`. Replace the ENTIRE file content with:

```java
package dev.iyanz.sourbycraft.command;
import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.BarUtil;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
import static net.kyori.adventure.text.Component.text;

public class SpeedtestCommand extends Command {
    // Linux-only for now; multi-OS deferred. Path mirrors the bootstrap libraries/ layout.
    private static final Path BIN = Paths.get("libraries/speedtest/linux-x86_64/speedtest");
    private static final String OOKLA_URL =
        "https://install.speedtest.net/app/cli/ookla-speedtest-1.2.0-linux-x86_64.tgz";

    public SpeedtestCommand(String n) {
        super(n);
        this.description = "Ookla speedtest";
        this.usageMessage = "/speedtest";
        this.setPermission("sourbycraft.command.speedtest");
    }

    public boolean execute(CommandSender s, String a, String[] args) {
        final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("SourbyCraft:Speedtest");
        LOG.info("Speedtest invoked. OS={} arch={} binPath={} binExists={}",
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            BIN.toAbsolutePath(),
            Files.exists(BIN));
        if (!testPermission(s)) return true;
        s.sendMessage(text("Running...", SourbyCraftColors.LABEL));
        VirtualExecutor.run(() -> {
            try {
                if (!Files.exists(BIN)) {
                    s.sendMessage(text("Downloading speedtest CLI (first run, ~1MB)...", SourbyCraftColors.LABEL));
                    downloadSpeedtestBinary(LOG);
                }
                if (!Files.exists(BIN)) {
                    s.sendMessage(text("Speedtest unavailable (download failed)", SourbyCraftColors.DANGER));
                    return;
                }
                Process proc = new ProcessBuilder(BIN.toString(),
                        "--format=json", "--accept-license", "--accept-gdpr")
                    .redirectErrorStream(true).start();
                String output = new String(proc.getInputStream().readAllBytes());
                proc.waitFor();
                var g = new com.google.gson.Gson();
                var r = g.fromJson(output, java.util.Map.class);
                var dl = (java.util.Map) r.get("download");
                var ul = (java.util.Map) r.get("upload");
                var ping = (java.util.Map) r.get("ping");
                var sv = (java.util.Map) r.get("server");
                double dm = ((Number) dl.get("bandwidth")).doubleValue() * 8 / 1000000;
                double um = ((Number) ul.get("bandwidth")).doubleValue() * 8 / 1000000;
                double pm = ((Number) ping.get("latency")).doubleValue();
                net.minecraft.server.MinecraftServer.getServer().execute(() -> {
                    s.sendMessage(text()
                        .append(text("DL: ", SourbyCraftColors.LABEL))
                        .append(BarUtil.coloredBar(Math.min(dm / 100, 100), 20))
                        .append(text(String.format(" %.1f Mbps", dm), SourbyCraftColors.SUCCESS))
                        .append(text("\nUL: ", SourbyCraftColors.LABEL))
                        .append(BarUtil.coloredBar(Math.min(um / 50, 100), 20))
                        .append(text(String.format(" %.1f Mbps", um), SourbyCraftColors.SUCCESS))
                        .append(text("\nPing: ", SourbyCraftColors.LABEL))
                        .append(text(String.format("%.0fms", pm),
                            pm < 30 ? SourbyCraftColors.SUCCESS : SourbyCraftColors.PRIMARY))
                        .append(text(" | " + sv.get("name") + ", " + sv.get("location"), SourbyCraftColors.DIM)));
                });
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("SourbyCraft:Speedtest")
                    .error("Speedtest process failed: {}", e.toString(), e);
                s.sendMessage(text("Speedtest failed: " + e.getMessage(), SourbyCraftColors.DANGER));
            }
        });
        return true;
    }

    /**
     * Downloads the Ookla speedtest CLI tarball, extracts the `speedtest` binary into BIN,
     * sets executable bit. Linux x86-64 only.
     */
    private static void downloadSpeedtestBinary(org.slf4j.Logger LOG) throws IOException {
        Files.createDirectories(BIN.getParent());
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(OOKLA_URL))
            .timeout(Duration.ofMinutes(2))
            .GET().build();
        Path tarball = BIN.getParent().resolve("speedtest.tgz.tmp");
        Files.deleteIfExists(tarball);
        HttpResponse<Path> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tarball));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during speedtest download", e);
        }
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tarball);
            throw new IOException("HTTP " + resp.statusCode() + " from " + OOKLA_URL);
        }
        // Tarball is gzipped tar with files: speedtest (binary), speedtest.md, speedtest.5
        // Extract only the `speedtest` entry into BIN.
        try (InputStream raw = Files.newInputStream(tarball);
             GZIPInputStream gz = new GZIPInputStream(raw)) {
            extractSpeedtestFromTar(gz, BIN);
        }
        Files.deleteIfExists(tarball);
        if (!Files.exists(BIN)) {
            throw new IOException("speedtest binary missing from extracted tarball");
        }
        try {
            BIN.toFile().setExecutable(true);
        } catch (SecurityException ignored) { }
        LOG.info("Speedtest binary downloaded to {}", BIN);
    }

    /**
     * Reads a USTAR/POSIX tar stream and extracts a file named "speedtest" into dest.
     * Skips other entries. Minimal tar reader — handles 512-byte block headers + EOF (two zero blocks).
     */
    private static void extractSpeedtestFromTar(InputStream tar, Path dest) throws IOException {
        byte[] header = new byte[512];
        while (true) {
            int r = tar.readNBytes(header, 0, 512);
            if (r < 512) return;
            if (isZeroBlock(header)) return;
            String name = readNulString(header, 0, 100);
            long size = parseTarOctal(header, 124, 12);
            if (name.equals("speedtest")) {
                try (OutputStream out = Files.newOutputStream(dest)) {
                    long remaining = size;
                    byte[] buf = new byte[64 * 1024];
                    while (remaining > 0) {
                        int n = tar.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (n < 0) throw new IOException("EOF inside tar entry");
                        out.write(buf, 0, n);
                        remaining -= n;
                    }
                }
                // Padding to next 512-byte boundary
                long pad = (512 - (size % 512)) % 512;
                if (pad > 0) tar.readNBytes((int) pad);
                return;
            } else {
                long toSkip = size + ((512 - (size % 512)) % 512);
                while (toSkip > 0) {
                    long skipped = tar.skip(toSkip);
                    if (skipped <= 0) throw new IOException("cannot skip past tar entry");
                    toSkip -= skipped;
                }
            }
        }
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String readNulString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off);
    }

    private static long parseTarOctal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') break;
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }
}
```

- [ ] **Step 6: Regenerate patch**

```bash
./gradlew rebuildPaperServerPatches 2>&1 | tail -10
```

(Task name may differ slightly — check `./gradlew tasks --group paperweight | grep -i rebuild` if `rebuildPaperServerPatches` doesn't exist; common alternatives: `rebuildPaperPatches`, `rebuildPatches`. Pick whichever matches and re-run.)

Expected: BUILD SUCCESSFUL. A NEW patch should appear at `patches/minecraft/0046-...patch` (or similar — paperweight names it from the most recent unstaged commit; you may need to commit-then-rebuild to get a proper name).

Pragmatic alternative if `rebuildPaperServerPatches` doesn't produce a separate patch but edits the existing `0013-consolidate-all-commands-in-single-patch.patch`: that's also acceptable. The change is small and the consolidate patch is the natural home for SpeedtestCommand edits.

- [ ] **Step 7: Verify the patch (or modified existing patch) is in place**

```bash
git status patches/
```

Either `patches/minecraft/0046-...patch` is new, OR `patches/server/0013-consolidate-all-commands-in-single-patch.patch` is modified. Either acceptable.

```bash
grep -c "libraries/speedtest/linux-x86_64" patches/server/0013-consolidate-all-commands-in-single-patch.patch patches/minecraft/0046-*.patch 2>/dev/null
```

Expected: ≥1 (the new BIN path is captured somewhere in the patches).

- [ ] **Step 8: Rebuild slim jar (no longer bundles speedtest)**

```bash
./gradlew assembleReleaseArtifacts 2>&1 | tail -5
ls -lh release/SourbyCraft-12-REL.jar
```

Expected: BUILD SUCCESSFUL. Jar size drops ~2.5M from Task 3's result (now ~30-32M).

```bash
unzip -l release/SourbyCraft-12-REL.jar | grep -E "speedtest$"
```

Expected: empty (no bundled `speedtest` resource in jar).

- [ ] **Step 9: Operator-test `/speedtest` cmd**

Stage jar into TestServer-mojmap (libraries/ has cached libs from Task 4, so boot is silent fast path):

```bash
cp release/SourbyCraft-12-REL.jar test-harness/TestServer-mojmap/server.jar
rm -f test-harness/TestServer-mojmap/libraries/speedtest/linux-x86_64/speedtest 2>/dev/null
cd test-harness/TestServer-mojmap
java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
BOOT_PID=$!
until grep -q "Done (" boot.log; do sleep 2; done
```

Then connect via RCON (port 25675, password `p1test` if you re-used the Task 4 setup, or whatever you set) and run `/speedtest`:

```bash
python3 -c "
import socket, struct
s = socket.create_connection(('127.0.0.1', 25675), timeout=5)
def send(req_id, kind, body):
    pkt = struct.pack('<ii', req_id, kind) + body.encode('utf-8') + b'\x00\x00'
    s.sendall(struct.pack('<i', len(pkt)) + pkt)
def recv():
    ln = struct.unpack('<i', s.recv(4))[0]
    return s.recv(ln)[8:-2].decode('utf-8', errors='replace')
send(1, 3, 'p1test'); recv()
send(2, 2, 'speedtest'); print(recv())
s.close()
"
```

Expected: `Running...` then (after some seconds) speedtest result with DL/UL/Ping. First invocation downloads ~1MB tarball; subsequent invocations skip the download.

Verify binary now exists:

```bash
ls -lh test-harness/TestServer-mojmap/libraries/speedtest/linux-x86_64/speedtest
```

Expected: ~2.5M executable.

Shutdown server.

- [ ] **Step 10: Commit**

```bash
git add sourbycraft-server/src/main/resources/speedtest \
        paper-server/src/main/java/dev/iyanz/sourbycraft/command/SpeedtestCommand.java \
        patches/server/0013-consolidate-all-commands-in-single-patch.patch \
        patches/minecraft/0046-*.patch \
        release/SourbyCraft-12-REL.jar release/checksums.txt
```

(Only some of these paths actually changed — the `git add` will silently skip non-existent ones. Inspect `git status` first to confirm the intended set.)

```bash
git commit -m "feat: sourby-bootstrap — lazy speedtest binary download

Removes the 2.5M Linux x86-64 ELF speedtest binary from jar resources
(sourbycraft-server/src/main/resources/speedtest). SpeedtestCommand
now downloads the Ookla CLI tarball on first /speedtest invocation,
extracts the speedtest binary into libraries/speedtest/linux-x86_64/,
sets +x, runs. Subsequent invocations reuse the cached binary.

Linux-only for now. Multi-OS download (macOS, Windows, FreeBSD,
linux-aarch64) deferred to a follow-on sub-spec — the bundled binary
was Linux x86-64 only, so this preserves current behavior."
```

---

## Task 6: Docs — README + RELEASE-NOTES first-boot section

**Goal:** Operator documentation: explain first-boot network requirement and side-load procedure for offline environments.

**Files:**
- Modify: `README.md`
- Modify: `release/RELEASE-NOTES-12.md`

- [ ] **Step 1: Edit `README.md`**

Find the existing `## Variants` section (around line 16). Add a new section AFTER the `Build: ./gradlew createMojmapPaperclipJar -Pvariant=normal|pvp` line and before the next `---`:

```markdown
## First Boot

The release jar is slim (~30M) and downloads ~28M of optional libraries on first
boot — SQLite/MySQL drivers, Spark profiler, Sentry, Parchment mappings, and
(on `/speedtest`) the Ookla CLI.

Requires outbound HTTPS to:
- `repo1.maven.org` (sqlite-jdbc, mysql-connector-j, protobuf, sentry)
- `repo.lucko.me` (spark-paper)
- `jitpack.io` (Flare)
- `maven.parchmentmc.org` (parchment)
- `install.speedtest.net` (Ookla CLI, on `/speedtest` only)

If first boot has no internet access: the `[SourbyBootstrap] FATAL` error in the
console lists every file to download manually and its destination under
`libraries/`. Side-load the files, restart, server boots normally.

Subsequent boots use the cached libraries — no further downloads, silent fast path.
```

- [ ] **Step 2: Edit `release/RELEASE-NOTES-12.md`**

Add a new top-level section at the END of the file:

```markdown
## Migration: slim jar (12-REL → bootstrap)

The 12-REL jar is now slim. First boot downloads ~28M of optional
libraries into `libraries/`. See `## First Boot` in `README.md` for the
exact URL list. Offline first-boot deployments must side-load files
(jar prints the URLs + destination paths on FATAL).

Operators upgrading from a prior 12-REL jar: existing `libraries/`
directory contents may be reused if their SHA-256 matches the new
manifest. Mismatched files trigger re-download.
```

- [ ] **Step 3: Verify edits**

```bash
grep -A 3 "First Boot" README.md | head -10
grep -A 3 "Migration: slim jar" release/RELEASE-NOTES-12.md
```

Expected: both sections present.

- [ ] **Step 4: Commit**

```bash
git add README.md release/RELEASE-NOTES-12.md
git commit -m "docs: sourby-bootstrap — first-boot requirements + side-load instructions"
```

---

## Final Verification (after all 6 tasks merged)

Walk through spec Section 7 acceptance criteria. Run each:

- [ ] **A1. Bootstrap package created**: `ls sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/` returns `BootstrapManifest.java LibDownloader.java Sha256Verifier.java SourbyBootstrap.java`.
- [ ] **A2. Slim gradle task registered**: `./gradlew tasks --all | grep createSlimPaperclipJar` lists it.
- [ ] **A3. externalLibs non-empty**: `grep -c "LibSpec(" build.gradle.kts` ≥7.
- [ ] **A4. Slim jar produced**: `ls -l release/SourbyCraft-12-REL.jar` shows the file.
- [ ] **A5. Slim jar size 28–35M**: `du -h release/SourbyCraft-12-REL.jar` within range (target ~30-32M post-Task-5).
- [ ] **A6. Externalized libs not in jar**: `unzip -l release/SourbyCraft-12-REL.jar | grep -E "sqlite-jdbc|mysql-connector|spark-paper|Flare|protobuf-java|sentry/sentry|parchment-1"` returns empty.
- [ ] **A7. Manifest entries ≥7**: `unzip -p release/SourbyCraft-12-REL.jar META-INF/sourby-bootstrap-manifest.json | python3 -c "import sys,json; print(len(json.load(sys.stdin)['entries']))"` returns ≥7.
- [ ] **A8. Manifest hashes 64-hex**: `unzip -p ... | python3 -c "import sys,json; entries=json.load(sys.stdin)['entries']; assert all(len(e['sha256'])==64 for e in entries); print('OK')"` returns `OK`.
- [ ] **A9. Main-Class set**: `unzip -p release/SourbyCraft-12-REL.jar META-INF/MANIFEST.MF | grep Main-Class` returns `Main-Class: dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap`.
- [ ] **A10. libraries.list filtered**: `unzip -p release/SourbyCraft-12-REL.jar META-INF/libraries.list | grep -c sqlite-jdbc` returns `0`.
- [ ] **A11. First-boot online completes** (already verified in Task 4 Step 2).
- [ ] **A12. Second-boot silent fast path** (already verified in Task 4 Step 6).
- [ ] **A13. Offline first-boot exits 3** — operator manually verifies by disconnecting network, deleting `libraries/`, booting; observes `[SourbyBootstrap] FATAL` with side-load instructions; exit code 3.
- [ ] **A14. SHA-256 tamper detection** — operator manually appends a byte to a cached lib, reboots; observes re-download + restoration.
- [ ] **A15. Bundled speedtest removed**: `unzip -l release/SourbyCraft-12-REL.jar | grep speedtest` returns empty.
- [ ] **A16. /speedtest lazy download** (already verified in Task 5 Step 9).
- [ ] **A17. No new JUnit**: `git diff <pre-Bootstrap-sha>..HEAD --stat sourbycraft-server/src/test/` returns empty.
- [ ] **A18. No new smoke harness**: `ls test-harness/scripts/ | grep bootstrap` returns empty.
- [ ] **A19. nms-compat CI passes against slim jar** — verified when next PR opens (CI runs `nmsCompatTest` against `release/SourbyCraft-12-REL.jar`, the slim jar).

---

## Self-Review

**Spec coverage:**
- Spec §3 C1 (`BootstrapManifest`) → Task 1 Step 1.
- Spec §3 C2 (`Sha256Verifier`) → Task 1 Step 2.
- Spec §3 C3 (`LibDownloader`) → Task 1 Step 3.
- Spec §3 C4 (`SourbyBootstrap`) → Task 2 Step 1.
- Spec §3 C5 (`createSlimPaperclipJar`) → Task 3 Steps 1+2.
- Spec §3 C6 (speedtest externalization) → Task 5 Steps 1, 5.
- Spec §3 C7 (`assembleReleaseArtifacts` rewire) → Task 3 Step 3.
- Spec §3 C8 (README + RELEASE-NOTES) → Task 6.
- Spec §9 Phase 1 (Java skeleton) → Tasks 1+2.
- Spec §9 Phase 2 (gradle slim task) → Task 3.
- Spec §9 Phase 3 (first-boot validation) → Task 4.
- Spec §9 Phase 4 (speedtest lazy) → Task 5.
- Spec §9 Phase 5 (docs) → Task 6.
- Spec §9 Phase 6 (optional polish) — skipped; not in core scope.

**Placeholder scan:** Zero `TBD` / `TODO` / `implement later` in this plan. Two callouts about implementation-time decisions (parchment version verification in Task 3 Step 1 NOTE; `rebuildPaperServerPatches` task name verification in Task 5 Step 6) are explicit operational guidance, not placeholders.

**Type consistency:**
- `BootstrapManifest.Entry(paperclipPath, downloadUrl, sha256, sizeBytes)` — identical signature in Task 1 Step 1, Task 1 Step 3 (`LibDownloader.ensure(entry, librariesDir)`), Task 2 Step 1 (parse + main loop), Task 3 Step 2 (manifest JSON keys).
- `LibDownloader.ensure(Entry, Path) -> boolean` — same signature in `SourbyBootstrap.main` and Task 4 Step 6 expectations.
- `Sha256Verifier.matches(Path, String) -> boolean` and `.ofFile(Path) -> String` — consistent across `LibDownloader.ensure` calls.
- `BIN = Paths.get("libraries/speedtest/linux-x86_64/speedtest")` in Task 5 Step 5 matches the path tested in Task 5 Step 9 (`test-harness/TestServer-mojmap/libraries/speedtest/linux-x86_64/speedtest`).
- yml path / manifest key naming (`paperclipPath`, `downloadUrl`, `sha256`, `sizeBytes`) consistent everywhere.
- Exit codes 2 (manifest broken) and 3 (runtime failure) consistent in spec + plan.
