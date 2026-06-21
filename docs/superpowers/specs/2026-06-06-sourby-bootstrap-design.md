# Sourby Bootstrap — Slim Jar + First-Boot Lib Downloader (design)

**Date:** 2026-06-06
**Scope:** Standalone mega-project (separate from the perf-engine roadmap). Reduces the SourbyCraft release jar from ~57M to ~30M by externalizing heavy optional libs and downloading them on first boot with SHA-256 verification. Hard-fails on offline first boot with operator-actionable diagnostics.
**Out-of-scope:** Parallel downloads, retry policy, CDN bundle, signed manifest, offline-bundle alternate jar, runtime download triggers. See Section 8.
**Status:** Draft for user review.

---

## 1. Background + Scope

Current release jar `SourbyCraft-12-REL.jar` is ~57M. Top bloat sources (jar inventory):

| Lib | Size | Optional? |
|---|---|---|
| `server-1.21.11.jar.patch` | 24.4M | Required — Paperclip core |
| `org.xerial:sqlite-jdbc:3.49.1.0` | 14.3M | Yes — DB driver |
| `me.lucko:spark-paper:1.10.152` | 3.0M | Yes — profiler |
| `sourbycraft-api` | 2.8M | Required at boot |
| `com.mysql:mysql-connector-j:9.2.0` | 2.6M | Yes — DB driver |
| `speedtest` binaries (5 OS/arch) | 2.5M | Yes — `/speedtest` cmd only |
| `com.github.technove:Flare:34637f3f87` | 2.0M | Yes — profiler engine |
| `com.google.protobuf:protobuf-java:4.29.0` | 1.9M | Yes — paired with profiler |
| `io.papermc.parchment:parchment-data` | 988K | Yes — dev mappings |
| `io.sentry:sentry:7.15.0` | 918K | Yes — opt-in error tracking |

**Goal:** target ~30M slim jar by externalizing ~28M of optional libs. First boot downloads missing libs from Maven Central / Jitpack / Lucko's repo / ParchmentMC / Ookla into the paperclip `libraries/` dir with SHA-256 verification. Subsequent boots are silent fast-path (cache hit).

**In scope:**
- New gradle task `createSlimPaperclipJar` that post-processes the paperclip output:
  - Removes target lib entries from `META-INF/libraries/...`.
  - Removes `speedtest/<os>/...` resource entries.
  - Removes matching lines from `META-INF/libraries.list`.
  - Embeds `META-INF/sourby-bootstrap-manifest.json` with `{paperclipPath, downloadUrl, sha256, sizeBytes}` per externalized lib.
  - Rewrites `META-INF/MANIFEST.MF` `Main-Class` → `dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap`.
- `SourbyBootstrap.java`: reads manifest, downloads + SHA-256-verifies missing libs, delegates to `io.papermc.paperclip.Main`.
- Sequential, single-threaded downloads via JDK `HttpClient` (no external HTTP dep — Apache HTTP itself is externalized).
- `speedtest` binaries removed from jar; `SpeedtestCommand` lazy-downloads from Ookla on first invocation.
- README + RELEASE-NOTES update describing first-boot network requirement.

**Out of scope** (deferred):
- Parallel downloads → follow-on sub-spec if first-boot time becomes a complaint.
- Retry policy → none; single attempt per lib.
- CDN-served tarball delta → none; direct upstream URLs.
- Signed manifest → SHA256 only; tampering protected by jar checksum at release-page level.
- Offline-bundle alternate jar variant → rejected; single slim jar.
- `sourbycraft-api` externalization → chicken-and-egg; bundled.
- Plugin auto-install manifest externalization → marginal savings; bundled.
- Variant-specific manifests → PvP inherits Normal's manifest.
- Hot-reload of manifest → manifest re-baked per build; first-boot only.
- Java HttpClient connection pool tuning → defaults only.
- Proxy/HTTPS-MITM custom config → JDK system proxy props honored.
- Mirror/repo fallback URLs → single URL per entry.
- Progress bar UI → log lines per lib only.

**Constraints inherited:**
- Paperclip jar produced by paperweight v2.0-beta.19 `createMojmapPaperclipJar` task.
- Single jar (no slim/offline duplicate variants).
- No JUnit and no smoke harness added (per `feedback-no-smoke-harness` memory).
- Hard-fail on offline first boot.
- Single mojmap jar.
- Build artifact path: `release/SourbyCraft-12-REL.jar`.

## 2. Architecture

```
build time (gradle):
  createMojmapPaperclipJar (existing paperweight task)
    └─ produces fat paperclip jar at sourbycraft-server/build/libs/...-paperclip.jar (~57M)
        ↓
  createSlimPaperclipJar (NEW gradle task; depends on createMojmapPaperclipJar)
    ├─ reads externalize-list (root build.gradle.kts DSL): List<LibSpec> with (paperclipPath, downloadUrl)
    ├─ for each LibSpec:
    │    ├─ extract bytes from fat jar's META-INF/libraries/<paperclipPath>
    │    ├─ compute SHA-256 of bytes
    │    └─ append Entry(paperclipPath, downloadUrl, sha256, sizeBytes) to manifest
    ├─ build new META-INF/libraries.list (drops lines matching externalized paperclipPaths)
    ├─ build new META-INF/MANIFEST.MF (copies existing headers + replaces Main-Class)
    └─ write slim jar:
         ├─ copy every fat-jar entry EXCEPT externalized paperclipPaths and speedtest/<os>/* entries
         ├─ + new META-INF/libraries.list
         ├─ + META-INF/sourby-bootstrap-manifest.json
         └─ + new META-INF/MANIFEST.MF (Main-Class = dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap)
        ↓
  assembleReleaseArtifacts (modified — depends on createSlimPaperclipJar instead of createMojmapPaperclipJar)
    ├─ copies slim jar → release/SourbyCraft-12-REL.jar
    └─ regenerates release/checksums.txt

runtime (first boot, online):
  java -jar SourbyCraft-12-REL.jar
    └─ JVM enters SourbyBootstrap.main(args)
         ├─ mkdirs ./libraries/
         ├─ loadManifest() ← reads META-INF/sourby-bootstrap-manifest.json from jar
         ├─ for each Entry IN SEQUENCE:
         │    ├─ if libraries/<paperclipPath> exists AND SHA-256 matches: skip (cache hit)
         │    └─ else LibDownloader.ensure: HTTP GET → temp file → size + SHA check → atomic move
         ├─ println "[SourbyBootstrap] downloaded N libraries (XM) in Ys"  (when N > 0)
         └─ delegate: Class.forName("io.papermc.paperclip.Main").main(args)
              └─ paperclip's normal flow: finds all libs in libraries/, skips bundled-jar extract

runtime (second + boot):
  same flow. All cache-hit. Silent fast path. Delegate.

runtime (offline first boot):
  HTTP fails → IOException caught in SourbyBootstrap.main
    → stderr: [SourbyBootstrap] FATAL: cannot fetch <path> from <url>: <reason>
    → stderr: for each Entry: "  libraries/<paperclipPath>  ←  <downloadUrl>"
    → System.exit(3)
  Operator side-loads files manually + restarts → cache-hit path.

new package layout:
  dev.iyanz.sourbycraft.bootstrap/   ← NEW
    ├─ SourbyBootstrap.java     (public; jar Main-Class)
    ├─ BootstrapManifest.java   (record + nested Entry record)
    ├─ LibDownloader.java       (package-private)
    └─ Sha256Verifier.java      (package-private)
```

**Invariants:**
- Slim jar produces byte-for-byte identical runtime to fat jar after bootstrap completes — paperclip sees the same `libraries/` dir state.
- Manifest is the only source-of-truth for what is externalized. Build-time gradle and runtime bootstrap read identical fields.
- SourbyBootstrap uses ONLY JDK classes until `paperclip.Main` is invoked — every externalized lib is potentially missing at that point.
- Sequential single-threaded download. No parallel HTTP. No retry.
- `libraries/` dir mirrors paperclip's path layout: `<group>/<artifact>/<version>/<file>.jar`. Speedtest lands at `libraries/speedtest/<os>-<arch>/speedtest`.
- No version drift between `libraries.list` and `sourby-bootstrap-manifest.json` — both regenerated atomically per build.

**Externalize list (locked at build time, ~28M total):**

| Lib | Source | Approx size |
|---|---|---|
| `org.xerial:sqlite-jdbc:3.49.1.0` | Maven Central | 14.3M |
| `me.lucko:spark-paper:1.10.152` | Lucko Maven (`repo.lucko.me`) | 3.0M |
| `com.mysql:mysql-connector-j:9.2.0` | Maven Central | 2.6M |
| `com.github.technove:Flare:34637f3f87` | Jitpack | 2.0M |
| `com.google.protobuf:protobuf-java:4.29.0` | Maven Central | 1.9M |
| `io.papermc.parchment:parchment-data:1.21.11-pre3+build.2` | ParchmentMC Maven | 988K |
| `io.sentry:sentry:7.15.0` | Maven Central | 918K |
| `speedtest` binaries (lazy; not in boot manifest) | Ookla CDN | 2.5M |

**Variant compatibility:** PvP variant (`-Pvariant=pvp`) inherits same manifest. Bootstrap is variant-agnostic.

## 3. Components

### C1. `BootstrapManifest` + nested `Entry` record

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/BootstrapManifest.java` (new)

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

### C2. `Sha256Verifier` (package-private)

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/Sha256Verifier.java` (new)

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

### C3. `LibDownloader` (package-private)

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/LibDownloader.java` (new)

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

### C4. `SourbyBootstrap` (public; jar Main-Class)

**File:** `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/SourbyBootstrap.java` (new)

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
                        + "  ←  " + e2.downloadUrl());
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

### C5. `createSlimPaperclipJar` gradle task

**File:** `build.gradle.kts` (root; edit existing — near the `assembleReleaseArtifacts` block, currently around line 163)

Add a Kotlin DSL list above the task, then register the task itself:

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

tasks.register("createSlimPaperclipJar") {
    group = "build"
    description = "Strip optional libs from paperclip jar + generate bootstrap manifest"

    val fatJarTask = project(":sourbycraft-server").tasks.named("createMojmapPaperclipJar")
    dependsOn(fatJarTask)
    inputs.files(fatJarTask.map { it.outputs.files })

    val slimJar = layout.buildDirectory.file("libs/SourbyCraft-slim.jar")
    outputs.file(slimJar)

    doLast {
        // Implementation procedure (plan writes the actual Kotlin):
        //  1. Open fat jar as java.util.jar.JarFile.
        //  2. For each LibSpec: extract bytes from "META-INF/libraries/<paperclipPath>",
        //     compute SHA-256 and size, append to manifest list.
        //  3. Read META-INF/libraries.list, drop lines matching externalized paperclipPaths.
        //  4. Build sourby-bootstrap-manifest.json string (single-line JSON, deterministic order).
        //  5. Build new META-INF/MANIFEST.MF (clone fat-jar MANIFEST.MF headers, replace Main-Class
        //     with dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap).
        //  6. Write slim jar via JarOutputStream:
        //     - copy every fat-jar entry EXCEPT externalized META-INF/libraries/<path>, speedtest/<os>/*,
        //       META-INF/libraries.list, META-INF/MANIFEST.MF.
        //     - + filtered libraries.list, manifest JSON, new MANIFEST.MF.
    }
}
```

### C6. Speedtest binary externalization

**Files:**
- Delete: `sourbycraft-server/src/main/resources/speedtest/` (`git rm -r`)
- Edit: `paper-server/src/main/java/dev/iyanz/sourbycraft/command/SpeedtestCommand.java`

`SpeedtestCommand` reads the binary from `libraries/speedtest/<os>-<arch>/speedtest` on disk. On first `/speedtest` invocation, if the binary is missing, downloads the appropriate Ookla archive, extracts the binary, sets the executable bit, then runs.

Ookla URLs (per OS/arch):
- `https://install.speedtest.net/app/cli/ookla-speedtest-1.2.0-linux-x86_64.tgz`
- `https://install.speedtest.net/app/cli/ookla-speedtest-1.2.0-linux-aarch64.tgz`
- `https://install.speedtest.net/app/cli/ookla-speedtest-1.2.0-macosx-universal.tgz`
- `https://install.speedtest.net/app/cli/ookla-speedtest-1.2.0-win64.zip`
- `https://install.speedtest.net/app/cli/ookla-speedtest-1.2.0-freebsd-x86_64.tgz`

Speedtest is NOT in the boot-time manifest — lazy on cmd invocation. Bootstrap doesn't deal with tarball/zip extraction.

### C7. `assembleReleaseArtifacts` rewire

**File:** `build.gradle.kts` (root; edit existing block)

Change `mojmapOutputs` source from `:sourbycraft-server:createMojmapPaperclipJar` to `:createSlimPaperclipJar`. The release jar is now the slim jar.

### C8. README + RELEASE-NOTES update

**Files:** `README.md`, `release/RELEASE-NOTES-12.md` (edit)

Add a "First Boot" section:

```markdown
## First Boot

The slim jar (~30M) downloads ~28M of optional libraries on first boot:
SQLite/MySQL drivers, Spark profiler, Sentry, Parchment mappings.
Requires outbound HTTPS to Maven Central, Jitpack, Lucko's repo,
and ParchmentMC. The `/speedtest` command additionally downloads
the Ookla CLI from `install.speedtest.net` on first invocation.

If first boot has no network access: see `[SourbyBootstrap] FATAL`
log for the list of files to download manually and their destination
paths under `libraries/`.

Subsequent boots use the cached libraries — no further downloads.
```

## 4. Data flow

```
build time:
  ./gradlew assembleReleaseArtifacts
    └─ createSlimPaperclipJar
         └─ :sourbycraft-server:createMojmapPaperclipJar (existing; ~57M fat jar)
              ↓
  createSlimPaperclipJar.doLast:
    fat jar
      ├─ FOR each LibSpec:
      │    ├─ read META-INF/libraries/<paperclipPath> bytes
      │    ├─ sha256 = SHA-256(bytes); size = bytes.length
      │    └─ manifest.entries += Entry(paperclipPath, downloadUrl, sha256, size)
      ├─ libraries.list filtered (lines matching externalized paths dropped)
      ├─ manifest JSON (deterministic order matching externalLibs declaration)
      ├─ MANIFEST.MF clone + replace Main-Class
      └─ slim jar (~30M) written via JarOutputStream:
           copy entries except (externalized paths, speedtest/*, libraries.list, MANIFEST.MF)
           + new libraries.list, manifest.json, MANIFEST.MF

  assembleReleaseArtifacts.doLast:
    slim jar → release/SourbyCraft-12-REL.jar
    sha256sum → release/checksums.txt

runtime (first boot, online):
  java -jar SourbyCraft-12-REL.jar
    └─ JVM reads MANIFEST.MF Main-Class = dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap
    └─ SourbyBootstrap.main(args)
         ├─ mkdirs ./libraries/
         ├─ loadManifest() ← getResourceAsStream("/META-INF/sourby-bootstrap-manifest.json")
         ├─ parse(json) ← regex extractor
         ├─ FOR each entry IN SEQUENCE:
         │    ├─ dest = libraries/<paperclipPath>
         │    ├─ if exists(dest) && SHA256(dest) == entry.sha256: skip (silent cache hit)
         │    ├─ else:
         │    │    ├─ mkdirs dest.parent
         │    │    ├─ HTTP GET entry.downloadUrl → dest.tmp (5min timeout)
         │    │    ├─ verify size(.tmp) == entry.sizeBytes
         │    │    ├─ verify SHA256(.tmp) == entry.sha256
         │    │    ├─ atomic-move .tmp → dest
         │    │    └─ println "[SourbyBootstrap] downloaded <path> (NM)"
         │    └─ any failure → stderr operator-facing error + manual-download URLs + exit 3
         ├─ println "[SourbyBootstrap] downloaded N libraries (XM) in Ys" (when N > 0)
         └─ Class.forName("io.papermc.paperclip.Main").main(args)

runtime (second + boot):
  same flow; all entries cache-hit (zero downloads, silent fast path), delegate.

runtime (offline first boot):
  download fails on HTTP send → IOException → exit 3 with manual-download instructions.

runtime (corrupted cache):
  existing libraries/<path> has wrong content → re-download attempt → if still bad → exit 3.

speedtest (lazy):
  operator runs /speedtest
    └─ SpeedtestCommand: resolve OS+arch → check libraries/speedtest/<os>-<arch>/speedtest
         ├─ if exists + executable: run, return
         └─ else: download Ookla tarball/zip → extract binary → set +x → run
              → on failure: cmd prints error, NO server shutdown
```

**Hot-path cost analysis:** None. Bootstrap runs ONCE per JVM startup. Paperclip then takes over. SourbyBootstrap classes are loaded but never re-invoked. P0/P1 hot paths unaffected.

**Cache-hit cost (every subsequent boot):** N entries × SHA256(file on disk) ≈ 30M total ≈ ~80ms on SSD. One-time per process start. Negligible.

**Concurrency:** Single-threaded.

**Atomicity:** Downloads use `.tmp` + atomic rename. Partial downloads cannot leave half-written files in the final path. Crash mid-download leaves `.tmp` only; next boot overwrites.

**Idempotency:** Same final state on every run given same manifest + cache. Crashloop boots are safe.

**`libraries.list` vs manifest separation:** No overlap. `libraries.list` covers libs paperclip extracts from the jar; manifest covers externalized libs. Both regenerated atomically per build.

## 5. Error handling

| Case | Behavior |
|---|---|
| Manifest missing from jar (build bug) | `loadManifest()` throws `IOException("META-INF/sourby-bootstrap-manifest.json not found in jar")`. Exit 2. |
| Manifest unparseable / no entries match regex | `parse()` throws `IOException("manifest has no entries (parse failed?)")`. Exit 2. |
| `libraries/` dir not writable | `Files.createDirectories` throws. Exit 3. Operator: fix perms. |
| Network unreachable (DNS, refused, timeout) | HttpClient throws `IOException`. Caught in main loop. Stderr: diagnostic + manual-download URLs for ALL entries. Exit 3. |
| HTTP non-200 response | `IOException("HTTP <code> for <url>")`. Same handler. |
| Size mismatch | `IOException("size mismatch ...")`. `.tmp` deleted. Same handler. |
| SHA-256 mismatch | `IOException("SHA-256 mismatch ...")`. `.tmp` deleted. No automatic retry (corrupted upstream = security signal). Same handler. |
| Existing `libraries/<path>` with wrong SHA-256 | Cache-check fails → fall through to re-download. Atomic-move overwrites. Second mismatch → exit 3. |
| Existing `.tmp` from prior crash | Overwritten by next download. `Files.deleteIfExists` before each write. |
| Atomic-move fails (cross-filesystem) | `IOException`. Exit 3. Operator: ensure `libraries/` is on same filesystem as cwd. |
| `Class.forName("io.papermc.paperclip.Main")` throws | `ClassNotFoundException` propagates as `Throwable` from `main`. JVM prints stack + non-zero exit. Indicates broken slim jar. |
| Paperclip Main throws | Paperclip's own exit code path. Bootstrap done. |
| `InterruptedException` during download | Re-set thread interrupt flag, wrap as `IOException("interrupted during download of ...")`. Same handler. |
| `MessageDigest.getInstance("SHA-256")` throws | Wrapped as `IOException("SHA-256 unavailable in this JDK")`. Exit 3. Effectively impossible on modern JDK. |

**Operator-facing error format:**

```
[SourbyBootstrap] FATAL: cannot fetch org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar
                  from https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar:
                  java.net.UnknownHostException: repo1.maven.org

[SourbyBootstrap] If your server has no internet access on first boot,
[SourbyBootstrap] download the libraries manually and place them at:
[SourbyBootstrap]   libraries/org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar  ←  https://repo1.maven.org/maven2/...
[SourbyBootstrap]   libraries/me/lucko/spark-paper/1.10.152/spark-paper-1.10.152.jar     ←  https://repo.lucko.me/...
[SourbyBootstrap]   ... (every manifest entry) ...
```

**Exit codes:**

```
0 = normal (delegate to paperclip Main was invoked)
2 = bundled manifest broken (build bug; non-operator-fixable)
3 = runtime fetch / verify / fs failure (operator-fixable: network, side-load, perms)
```

**No automatic retry.** No new exception types.

## 6. Testing

Per project policy (`feedback-no-smoke-harness` memory): NO automated test surface. NO JUnit. NO bash smoke harness. Verification is operator-driven.

**Verification by operator (manual):**

| Check | How |
|---|---|
| Slim jar size ~30M | `ls -lh release/SourbyCraft-12-REL.jar` |
| Manifest baked into jar | `unzip -p release/SourbyCraft-12-REL.jar META-INF/sourby-bootstrap-manifest.json` returns valid JSON with all 7+ entries |
| `Main-Class` correct | `unzip -p release/SourbyCraft-12-REL.jar META-INF/MANIFEST.MF \| grep Main-Class` returns `dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap` |
| Externalized libs removed | `unzip -l release/SourbyCraft-12-REL.jar \| grep sqlite-jdbc` returns empty |
| `libraries.list` filtered | `unzip -p release/SourbyCraft-12-REL.jar META-INF/libraries.list \| grep sqlite-jdbc` returns empty |
| First-boot online | Boot in `test-harness/TestServer-mojmap/` with empty `libraries/`; observe `[SourbyBootstrap] downloaded ...` lines, then `Done (` |
| Second-boot cache hit | Restart; observe NO bootstrap log lines; `Done (` reached in baseline time |
| Offline first-boot fails clearly | Disable network, empty `libraries/`, boot; observe `[SourbyBootstrap] FATAL` + manual-download list; exit 3 |
| SHA-256 tamper detection | Append byte to a cached lib, reboot; re-download attempted, correct file restored |
| `/speedtest` first invocation | Boot, run `/speedtest`; observe one-time download log + result |

**Inherited CI gates stay:**
- `.github/workflows/nms-compat.yml` already runs `nmsCompatTest` + `particleSmokeTest` + `p0KnobSmokeTest`. Slim jar must still pass these — same release jar path.
- No new CI step added.

**No JUnit added. No bash smoke harness added.**

## 7. Acceptance criteria

| # | Check | Command | Expected |
|---|---|---|---|
| 1 | Bootstrap package created | `ls sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap/` | `BootstrapManifest.java LibDownloader.java Sha256Verifier.java SourbyBootstrap.java` |
| 2 | Slim gradle task registered | `./gradlew tasks --all \| grep createSlimPaperclipJar` | listed under `build` group |
| 3 | externalLibs non-empty | `grep -c "LibSpec(" build.gradle.kts` | ≥7 |
| 4 | Slim jar produced | `./gradlew assembleReleaseArtifacts && ls -l release/SourbyCraft-12-REL.jar` | file exists |
| 5 | Slim jar size 28–35M | `du -h release/SourbyCraft-12-REL.jar` | within range |
| 6 | Externalized libs not in jar | `unzip -l release/SourbyCraft-12-REL.jar \| grep -E "sqlite-jdbc\|mysql-connector\|spark-paper\|Flare\|protobuf-java\|sentry/sentry\|parchment-1"` | empty |
| 7 | Manifest entries ≥7 | `unzip -p ... META-INF/sourby-bootstrap-manifest.json \| python3 -c '...len(entries)...'` | ≥7 |
| 8 | Manifest hashes 64-hex | python3 assertion on each entry | passes |
| 9 | Main-Class set | `unzip -p ... META-INF/MANIFEST.MF \| grep Main-Class` | `dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap` |
| 10 | libraries.list filtered | `unzip -p ... META-INF/libraries.list \| grep -c sqlite-jdbc` | `0` |
| 11 | First-boot online completes | Empty `libraries/`, boot | `Done (` within ~3 min |
| 12 | Second-boot silent fast path | Reboot after #11 | no `[SourbyBootstrap]` log lines |
| 13 | Offline first-boot exits 3 | Disable network, empty `libraries/`, boot | exit 3, stderr lists all manual URLs |
| 14 | SHA-256 tamper detection | Append byte, reboot | re-download, file restored |
| 15 | Bundled speedtest removed | `unzip -l ... \| grep speedtest` | empty |
| 16 | `/speedtest` lazy download | Boot, `/speedtest` | binary lands at `libraries/speedtest/<os>-<arch>/speedtest` + result |
| 17 | No new JUnit | `git diff <pre-Bootstrap-sha>..HEAD --stat sourbycraft-server/src/test/` | empty |
| 18 | No new smoke harness | `ls test-harness/scripts/ \| grep bootstrap` | empty |
| 19 | Existing nms-compat CI passes against slim jar | `.github/workflows/nms-compat.yml` runs against `release/SourbyCraft-12-REL.jar` | green |

## 8. Out of scope

Sourby Bootstrap explicitly does NOT cover:

1. **Parallel downloads** — sequential only.
2. **Retry policy** — single attempt per lib per boot.
3. **CDN/tarball delta** — direct upstream URLs only.
4. **Signed manifest** — SHA-256 only.
5. **Offline-bundle alternate jar variant** — single slim jar.
6. **`/sourby bootstrap status` / `refresh` cmds** — operator uses `ls libraries/` + restart.
7. **Plugin Auto-Install manifest externalization** — bundled.
8. **`sourbycraft-api` externalization** — chicken-and-egg; bundled.
9. **Variant-specific manifests** — PvP inherits Normal.
10. **Speedtest pre-warm at boot** — lazy on `/speedtest`.
11. **HttpClient pool tuning** — JDK defaults.
12. **Proxy/HTTPS-MITM custom config** — JDK system proxy props only.
13. **Mirror/repo fallback URLs** — single URL per entry.
14. **Bootstrap progress bar** — log lines per lib only.
15. **`-Dsourby.bootstrap.skip=true` escape hatch** — deferred to Phase 6 polish; not in core scope.

## 9. Phases (handed to writing-plans)

Suggested phase breakdown. `writing-plans` owns detailed step decomposition.

### Phase 1 — Java skeleton
- Create package `dev.iyanz.sourbycraft.bootstrap`.
- Land `BootstrapManifest`, `Sha256Verifier`, `LibDownloader`, `SourbyBootstrap`.
- Compile-only gate; no jar wiring yet.
- One commit: `feat: sourby-bootstrap — package skeleton (manifest + downloader + main shim)`.

### Phase 2 — Gradle slim task + assembleReleaseArtifacts rewire
- Add `externalLibs` Kotlin DSL list to root `build.gradle.kts` (7 maven libs; speedtest excluded — handled in Phase 4).
- Register `createSlimPaperclipJar` task: read fat jar, extract target lib bytes, compute SHA-256, write slim jar with filtered libraries.list, new MANIFEST.MF (Main-Class=SourbyBootstrap), embedded manifest JSON.
- Rewire `assembleReleaseArtifacts` source from `createMojmapPaperclipJar` to `createSlimPaperclipJar`.
- Build slim jar locally; verify size + jar entries + manifest presence via `unzip -p`.
- One commit: `build: sourby-bootstrap — createSlimPaperclipJar gradle task`.

### Phase 3 — First-boot validation (operator-run)
- Boot `release/SourbyCraft-12-REL.jar` in `test-harness/TestServer-mojmap/` with empty `libraries/`.
- Observe `[SourbyBootstrap]` log lines, downloads complete, `Done (` reached.
- Verify `libraries/` contains all 7 paperclip paths with correct SHA-256.
- Reboot, verify silent fast path.
- Fix `SourbyBootstrap.java` inline if logic bug found.
- One commit (only if fixes needed): `fix: sourby-bootstrap — <specific adjustment>`.

### Phase 4 — Speedtest externalization
- Delete `sourbycraft-server/src/main/resources/speedtest/` (`git rm -r`).
- Edit `SpeedtestCommand.java`: read binary from `libraries/speedtest/<os>-<arch>/speedtest`; lazy-download from Ookla on first invocation.
- Re-build, verify slim jar dropped another ~2.5M.
- One commit: `feat: sourby-bootstrap — lazy speedtest binary download`.

### Phase 5 — Docs + first-boot warning
- README + RELEASE-NOTES "First Boot" section per Section 3 C8.
- One commit: `docs: sourby-bootstrap — first-boot requirements + side-load instructions`.

### Phase 6 — Optional polish (deferred unless reviewer flags)
- HttpClient pool / timeouts.
- `-Dsourby.bootstrap.skip=true` emergency escape hatch.
- Skip unless explicit ask.
