# SourbyCraft v12 NMS Plugin Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship dual paperclip jars (mojmap + reobf), produce an investigation matrix of Citizens / NBTAPI / DecentHolograms / FAWE against both jar variants, fix each failure by either amending a SourbyCraft patch or documenting an upstream cause, and add a gradle smoke harness as a CI gate.

**Architecture:** Four phases — dual-jar build wiring, TestServer compat matrix, per-plugin fixes via a Phase-3 fix loop, smoke harness gradle task. An in-server `sanity-harness-plugin` runs alongside target plugins on enable, calls each plugin's sanity fixture, and writes results to `nms-compat-result.json` that a gradle JavaExec runner reads and converts to JUnit XML.

**Tech Stack:** paperweight 2.0 (Gradle Kotlin DSL), Paper 1.21.11, Bukkit/Spigot plugin API, SnakeYAML, JUnit, sha256 via `shasum`.

**Key file locations (verified during planning):**
- Root build script: `/Users/rheninxy/Sourby/SourbyCraft/build.gradle.kts`
- Server subproject build: `/Users/rheninxy/Sourby/SourbyCraft/sourbycraft-server/build.gradle.kts`
- Paperclip outputs: `sourbycraft-server/build/libs/sourbycraft-paperclip-<mc>-{mojmap,reobf}.jar`
- Release dir: `/Users/rheninxy/Sourby/SourbyCraft/release/` (currently has 1 jar `SourbyCraft-v12-REL.jar` + `checksums.txt`)
- `createReobfPaperclipJar` task already exists (paperweight-provided); no new task definition required, only wiring.
- Spec: `/Users/rheninxy/Sourby/SourbyCraft/docs/superpowers/specs/2026-06-03-nms-plugin-compat-design.md`

**Working tree precondition:** A `feat/pvp-server` branch with the unify-variants commits landed (commits `0c96424` through `4e9d1cc`). Boot of the existing mojmap jar verified in `/Users/rheninxy/Sourby/SourbyCraft/TestServer/`.

---

## Phase 1 — Dual-jar build

### Task 1: Add `assembleReleaseArtifacts` gradle task wiring both paperclip jars to `release/`

**Files:**
- Modify: `/Users/rheninxy/Sourby/SourbyCraft/build.gradle.kts` (append a new task at end of file)

- [ ] **Step 1: Append the new task definition**

Open `/Users/rheninxy/Sourby/SourbyCraft/build.gradle.kts`. At the end of the file (after line 152), append:

```kotlin

// SourbyCraft v12 — assemble both paperclip jars into release/ with checksums.
tasks.register("assembleReleaseArtifacts") {
    group = "release"
    description = "Copy mojmap + reobf paperclip jars into release/ and regenerate checksums.txt"

    val releaseDir = rootProject.layout.projectDirectory.dir("release")
    val internalVersion = providers.gradleProperty("internalVersion").getOrElse("dev")

    dependsOn(":sourbycraft-server:createMojmapPaperclipJar")
    dependsOn(":sourbycraft-server:createReobfPaperclipJar")

    doLast {
        val server = project(":sourbycraft-server")
        val mojmapJarTask = server.tasks.named("createMojmapPaperclipJar")
        val reobfJarTask = server.tasks.named("createReobfPaperclipJar")

        fun firstJarFrom(task: org.gradle.api.tasks.TaskProvider<Task>): java.io.File {
            return task.get().outputs.files.files
                .filter { it.name.endsWith(".jar") && it.exists() }
                .firstOrNull()
                ?: error("No jar output found for ${task.name}")
        }

        val mojmapSrc = firstJarFrom(mojmapJarTask)
        val reobfSrc = firstJarFrom(reobfJarTask)

        val mojmapDest = releaseDir.file("SourbyCraft-${internalVersion}.jar").asFile
        val reobfDest = releaseDir.file("SourbyCraft-${internalVersion}-reobf.jar").asFile

        releaseDir.asFile.mkdirs()
        mojmapSrc.copyTo(mojmapDest, overwrite = true)
        reobfSrc.copyTo(reobfDest, overwrite = true)

        val checksumsFile = releaseDir.file("checksums.txt").asFile
        fun sha256(f: java.io.File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        checksumsFile.writeText(
            "${sha256(mojmapDest)}  release/${mojmapDest.name}\n" +
            "${sha256(reobfDest)}  release/${reobfDest.name}\n"
        )

        logger.lifecycle("SourbyCraft release: ${mojmapDest.name} + ${reobfDest.name}")
    }
}
```

- [ ] **Step 2: Verify gradle parses the task graph**

Run from `/Users/rheninxy/Sourby/SourbyCraft`:
```bash
./gradlew help -q assembleReleaseArtifacts --dry-run 2>&1 | tail -10
```
Expected: lines listing `:sourbycraft-server:createMojmapPaperclipJar`, `:sourbycraft-server:createReobfPaperclipJar`, `:assembleReleaseArtifacts` SKIPPED (dry run).

- [ ] **Step 3: Execute the task**

```bash
./gradlew assembleReleaseArtifacts --offline 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`. Log line `SourbyCraft release: SourbyCraft-v12-REL.jar + SourbyCraft-v12-REL-reobf.jar`.

- [ ] **Step 4: Verify outputs**

```bash
ls -la /Users/rheninxy/Sourby/SourbyCraft/release/
cat /Users/rheninxy/Sourby/SourbyCraft/release/checksums.txt
```
Expected: two `.jar` files plus `checksums.txt` containing exactly 2 lines `<sha256>  release/SourbyCraft-v12-REL.jar` and `<sha256>  release/SourbyCraft-v12-REL-reobf.jar`. `wc -l checksums.txt` reports `2`.

- [ ] **Step 5: Commit**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git add build.gradle.kts release/
git commit -m "build: assembleReleaseArtifacts task — dual paperclip jars + checksums

Adds a gradle task that builds both Mojmap and Reobf paperclip jars
and copies them into release/ with a 2-line checksums.txt. Operators
choose the jar that matches their plugin set (mojmap for modern
plugins with Paper remapper, reobf for legacy NMS plugins built
against Spigot mappings)."
```

### Task 2: Standalone-boot smoke for both jars

**Files:**
- Test: `/Users/rheninxy/Sourby/SourbyCraft/TestServer-reobf/` (new directory, gitignored)

- [ ] **Step 1: Smoke-boot mojmap jar (already verified in earlier session, re-confirm)**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft/TestServer
cp /Users/rheninxy/Sourby/SourbyCraft/release/SourbyCraft-v12-REL.jar server.jar
rm -f boot-mojmap-smoke.log
java -Xmx2G -jar server.jar nogui > boot-mojmap-smoke.log 2>&1 &
SERVER_PID=$!
echo "PID: $SERVER_PID"
```

- [ ] **Step 2: Wait for Done and shut down**

Run (controller can use Monitor-style polling):
```bash
until grep -q "Done (" /Users/rheninxy/Sourby/SourbyCraft/TestServer/boot-mojmap-smoke.log; do sleep 3; done
echo "MOJMAP_OK"
kill -TERM $SERVER_PID
until ! ps -p $SERVER_PID > /dev/null 2>&1; do sleep 2; done
```
Expected: `MOJMAP_OK` printed. No `FATAL` or `Caused by:` in log.

- [ ] **Step 3: Smoke-boot reobf jar in a separate dir**

```bash
mkdir -p /Users/rheninxy/Sourby/SourbyCraft/TestServer-reobf
cd /Users/rheninxy/Sourby/SourbyCraft/TestServer-reobf
cp /Users/rheninxy/Sourby/SourbyCraft/release/SourbyCraft-v12-REL-reobf.jar server.jar
echo "eula=true" > eula.txt
java -Xmx2G -jar server.jar nogui > boot-reobf-smoke.log 2>&1 &
SERVER_PID=$!
echo "PID: $SERVER_PID"
until grep -q "Done (" /Users/rheninxy/Sourby/SourbyCraft/TestServer-reobf/boot-reobf-smoke.log; do sleep 3; done
echo "REOBF_OK"
kill -TERM $SERVER_PID
until ! ps -p $SERVER_PID > /dev/null 2>&1; do sleep 2; done
```
Expected: `REOBF_OK` printed. No `FATAL` or `Caused by:` in log.

- [ ] **Step 4: Add TestServer-reobf to .gitignore**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
grep -q "^TestServer-reobf/" .gitignore || echo "TestServer-reobf/" >> .gitignore
```

- [ ] **Step 5: Commit gitignore update**

```bash
git add .gitignore
git diff --cached --stat
git commit -m "gitignore: TestServer-reobf data dir"
```

---

## Phase 2 — TestServer compat matrix

### Task 3: Create `test-harness/test-plugins/manifest.yml` with sha-pinned plugin versions

**Files:**
- Create: `/Users/rheninxy/Sourby/SourbyCraft/test-harness/test-plugins/manifest.yml`

- [ ] **Step 1: Create the directory structure**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
mkdir -p test-harness/test-plugins test-harness/scripts test-harness/sanity-harness-plugin/src/main/java/dev/iyanz/sourbycraft/nms test-harness/sanity-harness-plugin/src/main/resources test-harness/sanity-harness-plugin/fixtures
```

- [ ] **Step 2: Write the manifest with placeholder sha256 values that get filled by download script**

Create `/Users/rheninxy/Sourby/SourbyCraft/test-harness/test-plugins/manifest.yml`:

```yaml
# SourbyCraft v12 NMS-compat test plugin manifest.
# Versions are pinned for reproducibility. To bump a version:
#   1. Update version + url here.
#   2. Run scripts/download-test-plugins.sh; copy the printed sha256 into this file.
#   3. Re-run the smoke harness; record the result in the matrix note.
plugins:
  - name: Citizens
    version: "2.0.39-b3811"
    url: "https://ci.citizensnpcs.co/job/Citizens2/3811/artifact/dist/target/Citizens-2.0.39-b3811.jar"
    sha256: ""  # filled by download-test-plugins.sh on first run
    main-class: net.citizensnpcs.Citizens

  - name: NBTAPI
    version: "2.13.2"
    url: "https://github.com/tr7zw/Item-NBT-API/releases/download/2.13.2/item-nbt-api-bukkit-2.13.2.jar"
    sha256: ""
    main-class: de.tr7zw.nbtapi.NBTAPI

  - name: DecentHolograms
    version: "2.8.10"
    url: "https://github.com/DecentSoftware-eu/DecentHolograms/releases/download/2.8.10/DecentHolograms-2.8.10.jar"
    sha256: ""
    main-class: eu.decentsoftware.holograms.plugin.DecentHologramsPlugin

  - name: FastAsyncWorldEdit
    version: "2.13.3"
    url: "https://ci.athion.net/job/FastAsyncWorldEdit/lastSuccessfulBuild/artifact/artifacts/FastAsyncWorldEdit-Bukkit-2.13.3-SNAPSHOT-1056.jar"
    sha256: ""
    main-class: com.fastasyncworldedit.bukkit.FaweBukkit
```

- [ ] **Step 3: Commit**

```bash
git add test-harness/test-plugins/manifest.yml
git commit -m "test-harness: plugin manifest with pinned versions (sha256 filled on first fetch)"
```

### Task 4: Create `download-test-plugins.sh` (idempotent fetch with sha256 verify)

**Files:**
- Create: `/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/download-test-plugins.sh`

- [ ] **Step 1: Write the script**

Create `/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/download-test-plugins.sh`:

```bash
#!/usr/bin/env bash
# Idempotent fetch of NMS-compat test plugins. Reads manifest.yml, verifies sha256,
# fetches missing or mismatched files with 3x retry + exponential backoff.

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
HARNESS_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
MANIFEST="$HARNESS_DIR/test-plugins/manifest.yml"
PLUGINS_DIR="$HARNESS_DIR/test-plugins"

if [[ ! -f "$MANIFEST" ]]; then
    echo "ERROR: manifest not found at $MANIFEST" >&2
    exit 1
fi

sha256() {
    if command -v sha256sum > /dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

retry_fetch() {
    local url=$1
    local out=$2
    local attempt=1
    local delay=5
    while [[ $attempt -le 3 ]]; do
        if curl -fsSL --connect-timeout 30 --max-time 300 -o "$out" "$url"; then
            return 0
        fi
        echo "  fetch attempt $attempt failed; retrying in ${delay}s..." >&2
        sleep "$delay"
        delay=$((delay * 3))
        attempt=$((attempt + 1))
    done
    return 1
}

# Parse manifest with awk (avoids python/yaml dep).
# Expected structure: each plugin block has name:, version:, url:, sha256:, main-class:.
awk '
/^  - name:/    { name=$3 }
/^    version:/ { ver=$2; gsub(/"/,"",ver) }
/^    url:/     { url=$2; gsub(/"/,"",url) }
/^    sha256:/  { sha=$2; gsub(/"/,"",sha); print name "|" ver "|" url "|" sha }
' "$MANIFEST" | while IFS='|' read -r name version url declared_sha; do
    [[ -z "$name" ]] && continue
    out="$PLUGINS_DIR/${name}-${version}.jar"

    if [[ -f "$out" ]]; then
        actual=$(sha256 "$out")
        if [[ -z "$declared_sha" ]]; then
            echo "$name: present, computing initial sha256=$actual (paste into manifest)"
            continue
        fi
        if [[ "$actual" == "$declared_sha" ]]; then
            echo "$name: cached OK ($declared_sha)"
            continue
        fi
        echo "$name: sha256 mismatch (expected $declared_sha, got $actual); refetching" >&2
        rm -f "$out"
    fi

    echo "$name: fetching $url"
    if ! retry_fetch "$url" "$out"; then
        echo "ERROR: $name: download failed after 3 retries from $url" >&2
        exit 1
    fi

    actual=$(sha256 "$out")
    if [[ -n "$declared_sha" && "$actual" != "$declared_sha" ]]; then
        echo "ERROR: $name: sha256 mismatch (expected $declared_sha, got $actual)" >&2
        exit 1
    fi
    echo "$name: fetched OK, sha256=$actual"
done

echo "All plugins ready in $PLUGINS_DIR"
```

- [ ] **Step 2: Make executable**

```bash
chmod +x /Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/download-test-plugins.sh
```

- [ ] **Step 3: Run it (first run records sha256s)**

```bash
/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/download-test-plugins.sh 2>&1 | tee /tmp/download-out.log
```
Expected: each plugin line shows `fetched OK, sha256=<hex>` or, if URL becomes unavailable mid-run, `ERROR: download failed`. If a URL is 404, manually update the manifest URL to the current build (Citizens / FAWE often roll build numbers) and re-run.

- [ ] **Step 4: Paste captured sha256 values back into manifest**

For each "fetched OK, sha256=" line, copy the hex into the corresponding `sha256: ""` slot in `test-harness/test-plugins/manifest.yml`. Re-run the script; expect every plugin to report `cached OK`.

- [ ] **Step 5: Verify all 4 jars present**

```bash
ls /Users/rheninxy/Sourby/SourbyCraft/test-harness/test-plugins/*.jar | wc -l
```
Expected: `4`.

- [ ] **Step 6: Add gitignore exclusion**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
{
    echo ""
    echo "# NMS-compat test-harness jars (downloaded by scripts/download-test-plugins.sh)"
    echo "test-harness/test-plugins/*.jar"
    echo "test-harness/TestServer-mojmap/"
    echo "test-harness/TestServer-reobf/"
    echo "test-harness/sanity-harness-plugin/build/"
} >> .gitignore
```

- [ ] **Step 7: Commit script + manifest with filled sha256 + gitignore**

```bash
git add test-harness/scripts/download-test-plugins.sh test-harness/test-plugins/manifest.yml .gitignore
git commit -m "test-harness: download-test-plugins.sh — fetch + sha256 verify Citizens/NBTAPI/DecentHolograms/FAWE"
```

### Task 5: Create `boot-mojmap.sh` and `boot-reobf.sh`

**Files:**
- Create: `/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-mojmap.sh`
- Create: `/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-reobf.sh`

- [ ] **Step 1: Write `boot-mojmap.sh`**

Create `/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-mojmap.sh`:

```bash
#!/usr/bin/env bash
# Boot SourbyCraft mojmap paperclip jar with all 4 test plugins; wait for "Done (" or timeout 90s.
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
HARNESS_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
ROOT_DIR="$( cd "$HARNESS_DIR/.." && pwd )"

JAR_SRC="$ROOT_DIR/release/SourbyCraft-v12-REL.jar"
TS_DIR="$HARNESS_DIR/TestServer-mojmap"
PORT=25600

if [[ ! -f "$JAR_SRC" ]]; then
    echo "ERROR: $JAR_SRC missing. Run gradle assembleReleaseArtifacts first." >&2
    exit 1
fi

mkdir -p "$TS_DIR/plugins"
cp "$JAR_SRC" "$TS_DIR/server.jar"
echo "eula=true" > "$TS_DIR/eula.txt"

# Force port via server.properties seed
if [[ ! -f "$TS_DIR/server.properties" ]] || ! grep -q "^server-port=$PORT" "$TS_DIR/server.properties"; then
    printf "server-port=%s\nonline-mode=false\n" "$PORT" > "$TS_DIR/server.properties.seed"
    if [[ -f "$TS_DIR/server.properties" ]]; then
        grep -v "^server-port=\|^online-mode=" "$TS_DIR/server.properties" >> "$TS_DIR/server.properties.seed"
    fi
    mv "$TS_DIR/server.properties.seed" "$TS_DIR/server.properties"
fi

# Copy plugin jars (overwriting any older versions)
rm -f "$TS_DIR"/plugins/Citizens-*.jar "$TS_DIR"/plugins/NBTAPI-*.jar \
      "$TS_DIR"/plugins/DecentHolograms-*.jar "$TS_DIR"/plugins/FastAsyncWorldEdit-*.jar \
      "$TS_DIR"/plugins/sanity-harness-plugin*.jar
cp "$HARNESS_DIR"/test-plugins/*.jar "$TS_DIR/plugins/"
SANITY_JAR="$HARNESS_DIR/sanity-harness-plugin/build/libs/sanity-harness-plugin.jar"
if [[ -f "$SANITY_JAR" ]]; then
    cp "$SANITY_JAR" "$TS_DIR/plugins/"
fi

cd "$TS_DIR"
rm -f boot.log nms-compat-result.json
java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
SERVER_PID=$!
echo "$SERVER_PID" > .server.pid
echo "boot-mojmap: PID $SERVER_PID, log=$TS_DIR/boot.log"

# Wait for "Done (" with 90s deadline
deadline=$(($(date +%s) + 90))
while [[ $(date +%s) -lt $deadline ]]; do
    if grep -q "Done (" boot.log 2>/dev/null; then
        echo "boot-mojmap: server up after $(($(date +%s) - (deadline - 90)))s"
        exit 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "ERROR: boot-mojmap: server died before reaching Done (" >&2
        tail -50 boot.log >&2
        exit 2
    fi
    sleep 2
done

echo "ERROR: boot-mojmap: BOOT_TIMEOUT after 90s" >&2
tail -50 boot.log >&2
kill -TERM "$SERVER_PID" 2>/dev/null
sleep 5
kill -KILL "$SERVER_PID" 2>/dev/null || true
exit 3
```

- [ ] **Step 2: Write `boot-reobf.sh` (parallel structure, different paths + port)**

Create `/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-reobf.sh`:

```bash
#!/usr/bin/env bash
# Boot SourbyCraft reobf paperclip jar with all 4 test plugins; wait for "Done (" or timeout 90s.
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
HARNESS_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
ROOT_DIR="$( cd "$HARNESS_DIR/.." && pwd )"

JAR_SRC="$ROOT_DIR/release/SourbyCraft-v12-REL-reobf.jar"
TS_DIR="$HARNESS_DIR/TestServer-reobf"
PORT=25601

if [[ ! -f "$JAR_SRC" ]]; then
    echo "ERROR: $JAR_SRC missing. Run gradle assembleReleaseArtifacts first." >&2
    exit 1
fi

mkdir -p "$TS_DIR/plugins"
cp "$JAR_SRC" "$TS_DIR/server.jar"
echo "eula=true" > "$TS_DIR/eula.txt"

if [[ ! -f "$TS_DIR/server.properties" ]] || ! grep -q "^server-port=$PORT" "$TS_DIR/server.properties"; then
    printf "server-port=%s\nonline-mode=false\n" "$PORT" > "$TS_DIR/server.properties.seed"
    if [[ -f "$TS_DIR/server.properties" ]]; then
        grep -v "^server-port=\|^online-mode=" "$TS_DIR/server.properties" >> "$TS_DIR/server.properties.seed"
    fi
    mv "$TS_DIR/server.properties.seed" "$TS_DIR/server.properties"
fi

rm -f "$TS_DIR"/plugins/Citizens-*.jar "$TS_DIR"/plugins/NBTAPI-*.jar \
      "$TS_DIR"/plugins/DecentHolograms-*.jar "$TS_DIR"/plugins/FastAsyncWorldEdit-*.jar \
      "$TS_DIR"/plugins/sanity-harness-plugin*.jar
cp "$HARNESS_DIR"/test-plugins/*.jar "$TS_DIR/plugins/"
SANITY_JAR="$HARNESS_DIR/sanity-harness-plugin/build/libs/sanity-harness-plugin.jar"
if [[ -f "$SANITY_JAR" ]]; then
    cp "$SANITY_JAR" "$TS_DIR/plugins/"
fi

cd "$TS_DIR"
rm -f boot.log nms-compat-result.json
java -Xmx2G -jar server.jar nogui > boot.log 2>&1 &
SERVER_PID=$!
echo "$SERVER_PID" > .server.pid
echo "boot-reobf: PID $SERVER_PID, log=$TS_DIR/boot.log"

deadline=$(($(date +%s) + 90))
while [[ $(date +%s) -lt $deadline ]]; do
    if grep -q "Done (" boot.log 2>/dev/null; then
        echo "boot-reobf: server up"
        exit 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "ERROR: boot-reobf: server died before reaching Done (" >&2
        tail -50 boot.log >&2
        exit 2
    fi
    sleep 2
done

echo "ERROR: boot-reobf: BOOT_TIMEOUT after 90s" >&2
tail -50 boot.log >&2
kill -TERM "$SERVER_PID" 2>/dev/null
sleep 5
kill -KILL "$SERVER_PID" 2>/dev/null || true
exit 3
```

- [ ] **Step 3: Make both scripts executable**

```bash
chmod +x /Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-mojmap.sh
chmod +x /Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-reobf.sh
```

- [ ] **Step 4: Commit**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git add test-harness/scripts/boot-mojmap.sh test-harness/scripts/boot-reobf.sh
git commit -m "test-harness: boot-{mojmap,reobf}.sh — port 25600/25601 + 90s deadline + Done( poll"
```

### Task 6: Scaffold `sanity-harness-plugin` Bukkit plugin (Gradle subproject)

**Files:**
- Create: `/Users/rheninxy/Sourby/SourbyCraft/test-harness/sanity-harness-plugin/build.gradle.kts`
- Create: `/Users/rheninxy/Sourby/SourbyCraft/test-harness/sanity-harness-plugin/src/main/resources/plugin.yml`
- Create: `/Users/rheninxy/Sourby/SourbyCraft/test-harness/sanity-harness-plugin/src/main/java/dev/iyanz/sourbycraft/nms/SanityHarnessPlugin.java`
- Modify: `/Users/rheninxy/Sourby/SourbyCraft/settings.gradle.kts`

- [ ] **Step 1: Inspect existing `settings.gradle.kts`**

```bash
cat /Users/rheninxy/Sourby/SourbyCraft/settings.gradle.kts | head -40
```

Note the existing `include(...)` lines.

- [ ] **Step 2: Register the new subproject in `settings.gradle.kts`**

Append to `/Users/rheninxy/Sourby/SourbyCraft/settings.gradle.kts`:

```kotlin
// SourbyCraft v12 — NMS-compat smoke harness plugin (loaded into TestServer-{mojmap,reobf}/plugins/).
include("test-harness:sanity-harness-plugin")
project(":test-harness:sanity-harness-plugin").projectDir = file("test-harness/sanity-harness-plugin")
```

- [ ] **Step 3: Write the subproject build.gradle.kts**

Create `/Users/rheninxy/Sourby/SourbyCraft/test-harness/sanity-harness-plugin/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.yaml:snakeyaml:2.2")
}

tasks.jar {
    archiveBaseName.set("sanity-harness-plugin")
    archiveVersion.set("")  // produces sanity-harness-plugin.jar
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

- [ ] **Step 4: Write the plugin.yml**

Create `/Users/rheninxy/Sourby/SourbyCraft/test-harness/sanity-harness-plugin/src/main/resources/plugin.yml`:

```yaml
name: SanityHarnessPlugin
version: 1.0.0
main: dev.iyanz.sourbycraft.nms.SanityHarnessPlugin
api-version: "1.21"
author: SourbyCraft
description: NMS-compat sanity harness — invokes target plugins on enable, writes nms-compat-result.json
load: POSTWORLD
depend: []
softdepend:
  - Citizens
  - NBTAPI
  - DecentHolograms
  - FastAsyncWorldEdit
```

- [ ] **Step 5: Write the plugin main class (skeleton — fixtures wired in Task 7)**

Create `/Users/rheninxy/Sourby/SourbyCraft/test-harness/sanity-harness-plugin/src/main/java/dev/iyanz/sourbycraft/nms/SanityHarnessPlugin.java`:

```java
package dev.iyanz.sourbycraft.nms;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SanityHarnessPlugin extends JavaPlugin {

    private static final List<String> TARGETS = List.of(
        "Citizens", "NBTAPI", "DecentHolograms", "FastAsyncWorldEdit"
    );

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::runAndWriteResults, 20L);
    }

    private void runAndWriteResults() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : TARGETS) {
            rows.add(checkPlugin(name));
        }
        writeJson(rows);
        getLogger().info("[sanity-harness] wrote nms-compat-result.json with " + rows.size() + " rows");
    }

    private Map<String, Object> checkPlugin(String name) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        if (p == null) {
            return Map.of(
                "plugin", name,
                "enabled", false,
                "sanity_passed", false,
                "fail_reason", "NOT_LOADED",
                "stack_hash", "");
        }
        if (!p.isEnabled()) {
            return Map.of(
                "plugin", name,
                "enabled", false,
                "sanity_passed", false,
                "fail_reason", "LOADED_NOT_ENABLED",
                "stack_hash", "");
        }
        try {
            String result = SanityFixtures.invoke(name, this);
            return Map.of(
                "plugin", name,
                "enabled", true,
                "sanity_passed", true,
                "fail_reason", "",
                "stack_hash", "",
                "fixture_result", result);
        } catch (Throwable t) {
            return Map.of(
                "plugin", name,
                "enabled", true,
                "sanity_passed", false,
                "fail_reason", t.getClass().getSimpleName() + ": " + safeMessage(t),
                "stack_hash", stackHash(t));
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? "" : m.replace('"', '\'');
    }

    private static String stackHash(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String normalized = sw.toString()
            .replaceAll("\\d+", "N")
            .replaceAll("\\$\\d+", "\\$N")
            .replaceAll("@[0-9a-f]+", "@HEX")
            .toLowerCase(Locale.ROOT);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "nohash";
        }
    }

    private void writeJson(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            sb.append("  {");
            int j = 0;
            for (Map.Entry<String, Object> e : r.entrySet()) {
                if (j++ > 0) sb.append(", ");
                sb.append('"').append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v instanceof Boolean) {
                    sb.append(v);
                } else {
                    sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                }
            }
            sb.append("}");
            if (i < rows.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        Path out = getServer().getWorldContainer().toPath().resolve("nms-compat-result.json");
        try {
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("[sanity-harness] failed to write " + out + ": " + e.getMessage());
        }
    }
}
```

- [ ] **Step 6: Create a stub `SanityFixtures.java` (real fixtures added in Task 7)**

Create `/Users/rheninxy/Sourby/SourbyCraft/test-harness/sanity-harness-plugin/src/main/java/dev/iyanz/sourbycraft/nms/SanityFixtures.java`:

```java
package dev.iyanz.sourbycraft.nms;

import org.bukkit.plugin.java.JavaPlugin;

public final class SanityFixtures {
    private SanityFixtures() {}

    public static String invoke(String pluginName, JavaPlugin harness) throws Throwable {
        // Per-plugin fixtures filled in Task 7.
        return "stub";
    }
}
```

- [ ] **Step 7: Build the plugin jar**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
./gradlew :test-harness:sanity-harness-plugin:jar --offline 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`. File at `test-harness/sanity-harness-plugin/build/libs/sanity-harness-plugin.jar`.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts test-harness/sanity-harness-plugin/
git commit -m "test-harness: sanity-harness-plugin scaffold (stub fixtures, JSON writer, stack-hash)"
```

### Task 7: Wire per-plugin sanity fixtures into `SanityFixtures.invoke`

**Files:**
- Modify: `/Users/rheninxy/Sourby/SourbyCraft/test-harness/sanity-harness-plugin/src/main/java/dev/iyanz/sourbycraft/nms/SanityFixtures.java`

- [ ] **Step 1: Replace the stub with concrete per-plugin sanity calls**

Open `SanityFixtures.java`. Replace the entire contents with:

```java
package dev.iyanz.sourbycraft.nms;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/**
 * Per-plugin sanity invocations. Each branch uses reflection (avoids hard compile-time
 * deps on the target plugins, which are downloaded at test time). A throw propagates
 * up to the caller and is captured as a fail row.
 */
public final class SanityFixtures {
    private SanityFixtures() {}

    public static String invoke(String pluginName, JavaPlugin harness) throws Throwable {
        switch (pluginName) {
            case "NBTAPI":            return invokeNbtApi();
            case "Citizens":          return invokeCitizens(harness);
            case "DecentHolograms":   return invokeDecentHolograms(harness);
            case "FastAsyncWorldEdit": return invokeFawe();
            default:                  return "unknown plugin: " + pluginName;
        }
    }

    private static String invokeNbtApi() throws Throwable {
        // de.tr7zw.changeme.nbtapi.NBT.parseNBT("{Foo:1b}") -> NBTContainer
        Class<?> nbtClass = Class.forName("de.tr7zw.changeme.nbtapi.NBT");
        Method parseNBT = nbtClass.getMethod("parseNBT", String.class);
        Object container = parseNBT.invoke(null, "{Foo:1b}");
        if (container == null) {
            throw new IllegalStateException("NBT.parseNBT returned null");
        }
        return "NBTContainer: " + container.toString();
    }

    private static String invokeCitizens(JavaPlugin harness) throws Throwable {
        // net.citizensnpcs.api.CitizensAPI.getNPCRegistry().createNPC(EntityType.VILLAGER, "TestNPC")
        Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
        Object registry = citizensApi.getMethod("getNPCRegistry").invoke(null);
        if (registry == null) {
            throw new IllegalStateException("CitizensAPI.getNPCRegistry returned null");
        }
        Class<?> entityType = Class.forName("org.bukkit.entity.EntityType");
        Object villager = entityType.getMethod("valueOf", String.class).invoke(null, "VILLAGER");
        Method createNPC = registry.getClass().getMethod("createNPC", entityType, String.class);
        Object npc = createNPC.invoke(registry, villager, "TestNPC");
        if (npc == null) {
            throw new IllegalStateException("createNPC returned null");
        }
        // Despawn immediately to avoid leaking state across runs
        try {
            Method despawn = npc.getClass().getMethod("destroy");
            despawn.invoke(npc);
        } catch (NoSuchMethodException ignored) {}
        return "NPC created + destroyed";
    }

    private static String invokeDecentHolograms(JavaPlugin harness) throws Throwable {
        // eu.decentsoftware.holograms.api.DHAPI.createHologram(name, loc, lines)
        World world = Bukkit.getWorlds().get(0);
        Location loc = new Location(world, 0.5, 100.0, 0.5);
        Class<?> dhApi = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
        // Different DH versions expose createHologram with String, Location, List<String>
        // We pass a singletonList("test") to keep API surface minimal.
        Method create = dhApi.getMethod("createHologram", String.class, Location.class, java.util.List.class);
        Object holo = create.invoke(null, "sanity-test-" + System.nanoTime(), loc, java.util.List.of("sanity"));
        if (holo == null) {
            throw new IllegalStateException("DHAPI.createHologram returned null");
        }
        // Best-effort delete to avoid state leak
        try {
            Method delete = holo.getClass().getMethod("delete");
            delete.invoke(holo);
        } catch (NoSuchMethodException ignored) {}
        return "Hologram created + deleted";
    }

    private static String invokeFawe() throws Throwable {
        // com.sk89q.worldedit.WorldEdit.getInstance() reachable when FAWE is loaded
        Class<?> we = Class.forName("com.sk89q.worldedit.WorldEdit");
        Object instance = we.getMethod("getInstance").invoke(null);
        if (instance == null) {
            throw new IllegalStateException("WorldEdit.getInstance returned null");
        }
        Method getVersion = instance.getClass().getMethod("getVersion");
        Object version = getVersion.invoke(instance);
        return "WorldEdit version: " + version;
    }
}
```

- [ ] **Step 2: Rebuild the jar**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
./gradlew :test-harness:sanity-harness-plugin:jar --offline 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add test-harness/sanity-harness-plugin/src/main/java/dev/iyanz/sourbycraft/nms/SanityFixtures.java
git commit -m "test-harness: per-plugin sanity fixtures (NBT parse, NPC create+destroy, hologram create+delete, FAWE version probe)"
```

### Task 8: Create `capture-matrix.sh` (reads boot logs + nms-compat-result.json, writes matrix note)

**Files:**
- Create: `/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/capture-matrix.sh`

- [ ] **Step 1: Write the script**

Create `/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/capture-matrix.sh`:

```bash
#!/usr/bin/env bash
# Aggregate boot logs + nms-compat-result.json from both TestServer-{mojmap,reobf}/
# into docs/superpowers/notes/<date>-nms-compat-matrix-r<N>.md
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
HARNESS_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"
ROOT_DIR="$( cd "$HARNESS_DIR/.." && pwd )"
NOTES_DIR="$ROOT_DIR/docs/superpowers/notes"
DATE_TODAY="$(date +%Y-%m-%d)"

mkdir -p "$NOTES_DIR"

# Determine next round number
round=1
while [[ -f "$NOTES_DIR/${DATE_TODAY}-nms-compat-matrix-r${round}.md" ]]; do
    round=$((round + 1))
done
OUT="$NOTES_DIR/${DATE_TODAY}-nms-compat-matrix-r${round}.md"

emit_row() {
    local variant=$1 plugin=$2 enabled=$3 sanity=$4 reason=$5 hash=$6
    printf "| %-7s | %-18s | %-7s | %-7s | %-40s | %s |\n" \
        "$variant" "$plugin" "$enabled" "$sanity" "${reason:0:40}" "${hash:0:8}"
}

emit_variant() {
    local variant=$1 ts_dir=$2
    local json="$ts_dir/nms-compat-result.json"
    if [[ ! -f "$json" ]]; then
        for p in Citizens NBTAPI DecentHolograms FastAsyncWorldEdit; do
            emit_row "$variant" "$p" "?" "?" "NO_RESULT_FILE" ""
        done
        return
    fi
    python3 -c "
import json, sys
data = json.load(open('$json'))
for row in data:
    print('|', '$variant', '|', row.get('plugin','?'), '|',
          'yes' if row.get('enabled') else 'no', '|',
          'yes' if row.get('sanity_passed') else 'no', '|',
          (row.get('fail_reason','') or '')[:40], '|',
          (row.get('stack_hash','') or '')[:8], '|')
"
}

cat > "$OUT" <<EOF
# NMS-compat matrix r${round} — ${DATE_TODAY}

| Variant | Plugin             | Enabled | Sanity  | Fail reason                              | Stack    |
| ------- | ------------------ | ------- | ------- | ---------------------------------------- | -------- |
EOF

emit_variant "mojmap" "$HARNESS_DIR/TestServer-mojmap" >> "$OUT"
emit_variant "reobf"  "$HARNESS_DIR/TestServer-reobf"  >> "$OUT"

cat >> "$OUT" <<EOF

## Legend

- **Enabled**: \`Bukkit.getPluginManager().getPlugin(name)\` non-null + \`isEnabled() == true\`.
- **Sanity**: per-plugin fixture executed without exception. See
  \`test-harness/sanity-harness-plugin/src/main/java/dev/iyanz/sourbycraft/nms/SanityFixtures.java\`.
- **Fail reason**: exception class + first 40 chars of message. Truncated; full trace in boot.log.
- **Stack**: first 8 hex chars of sha1(normalized stack trace). Stable across runs for the same bug.

## Plugin sources

Pin versions in \`test-harness/test-plugins/manifest.yml\`. Latest fetch timestamp:
\`$(stat -f '%Sm' "$HARNESS_DIR/test-plugins/manifest.yml" 2>/dev/null || stat -c '%y' "$HARNESS_DIR/test-plugins/manifest.yml" 2>/dev/null)\`

## Investigation notes

(populate per row during Phase 3 fixes)
EOF

echo "Matrix written to $OUT"
```

- [ ] **Step 2: Make executable**

```bash
chmod +x /Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/capture-matrix.sh
```

- [ ] **Step 3: Commit**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git add test-harness/scripts/capture-matrix.sh
git commit -m "test-harness: capture-matrix.sh — aggregate boot results into docs/superpowers/notes/<date>-nms-compat-matrix-r<N>.md"
```

### Task 9: Run the matrix end-to-end (boot both variants, capture round 1)

- [ ] **Step 1: Ensure plugins downloaded**

```bash
/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/download-test-plugins.sh
```
Expected: `All plugins ready in test-harness/test-plugins`.

- [ ] **Step 2: Rebuild sanity-harness-plugin (in case sources changed)**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
./gradlew :test-harness:sanity-harness-plugin:jar --offline 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Build dual release jars if not already**

```bash
./gradlew assembleReleaseArtifacts --offline 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`. Two jars in `release/`.

- [ ] **Step 4: Boot mojmap variant**

```bash
/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-mojmap.sh
```
Expected: `boot-mojmap: server up` within ~60s. After it prints success, the script returns control while the server stays alive; the sanity-harness plugin's 20-tick (~1s) delayed task writes the result file.

- [ ] **Step 5: Wait briefly + verify result file exists**

```bash
sleep 3
ls /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/nms-compat-result.json
cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/nms-compat-result.json
```
Expected: a JSON array with 4 objects.

- [ ] **Step 6: Shut down mojmap server cleanly**

```bash
kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid)
until ! ps -p $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid) > /dev/null 2>&1; do sleep 2; done
echo "mojmap server stopped"
```

- [ ] **Step 7: Boot reobf variant + verify + shutdown**

```bash
/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-reobf.sh
sleep 3
cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-reobf/nms-compat-result.json
kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-reobf/.server.pid)
until ! ps -p $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-reobf/.server.pid) > /dev/null 2>&1; do sleep 2; done
echo "reobf server stopped"
```

- [ ] **Step 8: Capture the matrix**

```bash
/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/capture-matrix.sh
```
Expected: `Matrix written to docs/superpowers/notes/2026-06-03-nms-compat-matrix-r1.md`. Open the file; verify 8 rows present (4 plugins × 2 variants).

- [ ] **Step 9: Commit the round-1 matrix**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git add -f docs/superpowers/notes/
git commit -m "notes: NMS-compat matrix r1 — initial Citizens/NBTAPI/DecentHolograms/FAWE result against mojmap+reobf jars"
```

---

## Phase 3 — Per-plugin fixes

Phase 3 is iterative. The number of tasks depends on what Phase 2's matrix shows. Tasks 10-13 are the "default" templates — one per plugin. Skip any plugin that already showed `OK` on both variants in round 1.

### Task 10: Triage + fix Citizens (only if matrix r1 shows non-OK)

**Files:** (determined during triage; below documents the workflow)

- [ ] **Step 1: Read the matrix row for Citizens**

```bash
grep -E "^\| (mojmap|reobf) +\| Citizens" /Users/rheninxy/Sourby/SourbyCraft/docs/superpowers/notes/2026-06-03-nms-compat-matrix-r1.md
```
- If both rows show `yes | yes`, skip this task (nothing to fix).
- Otherwise note: which variant fails, fail reason, stack hash.

- [ ] **Step 2: Classify root cause**

Use this decision tree:
- Reason contains `ClassNotFoundException: net.minecraft.server.v1_21_R`: **Class E** (packageVersion mismatch). Plugin built for a different `v1_21_R*` than ours (`v1_21_R7`). No SourbyCraft fix possible; in Step 5, document the upstream cause + recommend operator wait for Citizens release matching `v1_21_R7`.
- Reason contains `NoSuchMethodError`: **Class D** (NMS class moved) — usually no SourbyCraft fix. Confirm by reading the trace; if the missing method belongs to a `dev.iyanz.sourbycraft.*` class, it's actually **Class C**.
- Reason contains the name of a SourbyCraft class (`dev.iyanz.sourbycraft.*`): **Class C** (SourbyCraft patch conflict). Continue to Step 3.
- Reason contains `NoClassDefFoundError: net.minecraft.*` on the mojmap variant only: **Class B** (Paper remapper miss). Confirm by checking that the same plugin works on the reobf variant. If yes, document the workaround (use reobf jar) — no SourbyCraft change.
- Reason mentions plugin version out of date or unsupported Paper API: **Class A**. Bump the plugin version in `test-harness/test-plugins/manifest.yml`, re-run download, re-run matrix.

- [ ] **Step 3: For Class C (SourbyCraft patch conflict) — bisect**

Disable PvP-style patches one at a time and rebuild. After each rebuild, re-run boot-mojmap.sh and check if Citizens passes.

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
# Suspect order: most NMS-invasive first
for patch in patches/minecraft/0040-SourbyCraft-v12-PVP-9004-combat-completion-sweep-gat.patch \
             patches/minecraft/0038-SourbyCraft-v12-PVP-9002-entity-tracker-tightening.patch \
             patches/server/0028-SourbyCraft-v9.25-BossBarTicker-refactor-hologram-he.patch \
             patches/minecraft/0037-SourbyCraft-v12-PVP-9001-netty-tuning.patch; do
    echo "=== Disabling $patch ==="
    mv "$patch" "${patch}.disabled"
    ./gradlew applyAllPatches --offline 2>&1 | tail -5
    ./gradlew :sourbycraft-server:createMojmapPaperclipJar --offline 2>&1 | tail -3
    ./gradlew assembleReleaseArtifacts --offline 2>&1 | tail -3
    /Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-mojmap.sh || true
    sleep 3
    if grep -q "\"plugin\":\"Citizens\".*\"sanity_passed\":true" \
         /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/nms-compat-result.json; then
        echo "CULPRIT: $patch — Citizens passes with it disabled"
        kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid) || true
        break
    fi
    kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid) || true
    sleep 5
    # Restore before testing the next patch
    mv "${patch}.disabled" "$patch"
done
```

- [ ] **Step 4: Apply the fix or document**

- **If a culprit patch was found**: open the patch, identify the specific code change interfering with Citizens. Common fixes:
  - The patch already has `if (SourbyCraftConfig.pvpEnabled)` gate; the gate's default value is correct. Re-enable the patch (rename `.disabled` back), then verify Citizens passes when `pvp.enabled` stays `false` in the operator yml. If yes, the conflict only manifests in PvP mode — document this. Operators with Citizens should keep `pvp.enabled: false`.
  - The patch unconditionally alters NMS class behavior in a way Citizens relies on. Amend the patch to add a `pvpEnabled` gate (or a narrower per-feature gate) following the pattern from previous patches (see `patches/minecraft/0037-…-netty-tuning.patch:25` for the gate idiom).
  - After amending, rebuild: `./gradlew :sourbycraft-server:createMojmapPaperclipJar --offline && ./gradlew assembleReleaseArtifacts --offline`. Re-run boot-mojmap.sh + capture-matrix.sh. New matrix round Rn+1 should show Citizens green.
- **If no culprit found (all patches disabled, still broken)**: this is Class B or D. Document in the matrix notes section. No SourbyCraft change.

- [ ] **Step 5: Update the round-1 matrix with investigation notes + add new round file if patches were changed**

Edit `docs/superpowers/notes/2026-06-03-nms-compat-matrix-r1.md`. Under "## Investigation notes", add:

```markdown
### Citizens

- Root-cause class: <A|B|C|D|E>
- Affected variant(s): <mojmap | reobf | both>
- Diagnosis: <one-paragraph summary>
- Resolution: <commit SHA of fix, or UPSTREAM if external, or WORKAROUND with operator instruction>
```

- [ ] **Step 6: If a SourbyCraft patch was amended, commit**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git add patches/  # or paper-server/... if it was edited and rebuilt via paperweight
git commit -m "patch: gate <name> behavior to fix Citizens NMS compat

Citizens failed sanity check on the <mojmap|reobf> variant with
<exception class>. Root cause: <SourbyCraft patch> applied <change>
unconditionally, which conflicts with Citizens' assumption that
<vanilla behavior>. Fix wraps the change in if(pvpEnabled) so
non-PvP servers see vanilla NMS behavior. Verified by matrix
round r<N+1>."
```

- [ ] **Step 7: Re-run matrix capture if anything changed**

```bash
/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-mojmap.sh
sleep 3
kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid)
until ! ps -p $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid) > /dev/null 2>&1; do sleep 2; done
/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-reobf.sh
sleep 3
kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-reobf/.server.pid)
until ! ps -p $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-reobf/.server.pid) > /dev/null 2>&1; do sleep 2; done
/Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/capture-matrix.sh
git add -f docs/superpowers/notes/
git commit -m "notes: NMS-compat matrix r2 — Citizens fix verified"
```

### Task 11: Triage + fix NBTAPI

Identical workflow as Task 10, substituting `NBTAPI` for `Citizens`. Use these adapted Step 1 grep, Step 3 suspect order, and Step 5 section header.

- [ ] **Step 1: Read matrix row**

```bash
grep -E "^\| (mojmap|reobf) +\| NBTAPI" /Users/rheninxy/Sourby/SourbyCraft/docs/superpowers/notes/2026-06-03-nms-compat-matrix-r1.md
```

- [ ] **Step 2: Classify root cause** (same decision tree as Task 10 Step 2)

- [ ] **Step 3: For Class C — bisect**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
# NBTAPI is sensitive to NMS class moves + reflection; the same suspect list as Citizens applies.
for patch in patches/minecraft/0040-SourbyCraft-v12-PVP-9004-combat-completion-sweep-gat.patch \
             patches/minecraft/0038-SourbyCraft-v12-PVP-9002-entity-tracker-tightening.patch \
             patches/minecraft/0037-SourbyCraft-v12-PVP-9001-netty-tuning.patch; do
    echo "=== Disabling $patch ==="
    mv "$patch" "${patch}.disabled"
    ./gradlew applyAllPatches --offline 2>&1 | tail -5
    ./gradlew :sourbycraft-server:createMojmapPaperclipJar --offline 2>&1 | tail -3
    ./gradlew assembleReleaseArtifacts --offline 2>&1 | tail -3
    /Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-mojmap.sh || true
    sleep 3
    if grep -q "\"plugin\":\"NBTAPI\".*\"sanity_passed\":true" \
         /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/nms-compat-result.json; then
        echo "CULPRIT: $patch"
        kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid) || true
        break
    fi
    kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid) || true
    sleep 5
    mv "${patch}.disabled" "$patch"
done
```

- [ ] **Step 4: Apply fix or document** (same as Task 10 Step 4)

- [ ] **Step 5: Update matrix notes**

Edit `docs/superpowers/notes/2026-06-03-nms-compat-matrix-r1.md`. Under "## Investigation notes", add:

```markdown
### NBTAPI

- Root-cause class: <A|B|C|D|E>
- Affected variant(s): <mojmap | reobf | both>
- Diagnosis: <one-paragraph summary>
- Resolution: <commit SHA of fix, or UPSTREAM, or WORKAROUND>
```

- [ ] **Step 6: Commit + re-capture matrix if anything changed** (same as Task 10 Step 6-7, substitute "NBTAPI" in commit message)

### Task 12: Triage + fix DecentHolograms

DecentHolograms is most likely to interact with `patches/server/0028-SourbyCraft-v9.25-BossBarTicker-refactor-hologram-he.patch` (BossBar holography) and `patches/minecraft/0038-…-entity-tracker-tightening.patch` (armor stand visibility).

- [ ] **Step 1: Read matrix row**

```bash
grep -E "^\| (mojmap|reobf) +\| DecentHolograms" /Users/rheninxy/Sourby/SourbyCraft/docs/superpowers/notes/2026-06-03-nms-compat-matrix-r1.md
```

- [ ] **Step 2: Classify root cause** (same decision tree as Task 10 Step 2)

- [ ] **Step 3: For Class C — bisect with DecentHolograms-specific suspect order**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
for patch in patches/server/0028-SourbyCraft-v9.25-BossBarTicker-refactor-hologram-he.patch \
             patches/minecraft/0038-SourbyCraft-v12-PVP-9002-entity-tracker-tightening.patch \
             patches/minecraft/0040-SourbyCraft-v12-PVP-9004-combat-completion-sweep-gat.patch; do
    echo "=== Disabling $patch ==="
    mv "$patch" "${patch}.disabled"
    ./gradlew applyAllPatches --offline 2>&1 | tail -5
    ./gradlew :sourbycraft-server:createMojmapPaperclipJar --offline 2>&1 | tail -3
    ./gradlew assembleReleaseArtifacts --offline 2>&1 | tail -3
    /Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-mojmap.sh || true
    sleep 3
    if grep -q "\"plugin\":\"DecentHolograms\".*\"sanity_passed\":true" \
         /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/nms-compat-result.json; then
        echo "CULPRIT: $patch"
        kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid) || true
        break
    fi
    kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid) || true
    sleep 5
    mv "${patch}.disabled" "$patch"
done
```

- [ ] **Step 4: Apply fix or document** (same as Task 10 Step 4)

- [ ] **Step 5: Update matrix notes**

```markdown
### DecentHolograms

- Root-cause class: <A|B|C|D|E>
- Affected variant(s): <mojmap | reobf | both>
- Diagnosis: <one-paragraph summary>
- Resolution: <commit SHA of fix, or UPSTREAM, or WORKAROUND>
```

- [ ] **Step 6: Commit + re-capture matrix** (substitute "DecentHolograms" in commit message)

### Task 13: Triage + fix FastAsyncWorldEdit

FAWE primarily interacts with chunk save / world data persistence patches.

- [ ] **Step 1: Read matrix row**

```bash
grep -E "^\| (mojmap|reobf) +\| FastAsyncWorldEdit" /Users/rheninxy/Sourby/SourbyCraft/docs/superpowers/notes/2026-06-03-nms-compat-matrix-r1.md
```

- [ ] **Step 2: Classify root cause** (same decision tree as Task 10 Step 2)

- [ ] **Step 3: For Class C — bisect with FAWE-specific suspect order**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
# FAWE touches chunk save async + block-set hot path
for patch in patches/server/0014-chore-SWM-v2-fixes.patch \
             patches/minecraft/0040-SourbyCraft-v12-PVP-9004-combat-completion-sweep-gat.patch \
             patches/minecraft/0038-SourbyCraft-v12-PVP-9002-entity-tracker-tightening.patch; do
    echo "=== Disabling $patch ==="
    mv "$patch" "${patch}.disabled"
    ./gradlew applyAllPatches --offline 2>&1 | tail -5
    ./gradlew :sourbycraft-server:createMojmapPaperclipJar --offline 2>&1 | tail -3
    ./gradlew assembleReleaseArtifacts --offline 2>&1 | tail -3
    /Users/rheninxy/Sourby/SourbyCraft/test-harness/scripts/boot-mojmap.sh || true
    sleep 3
    if grep -q "\"plugin\":\"FastAsyncWorldEdit\".*\"sanity_passed\":true" \
         /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/nms-compat-result.json; then
        echo "CULPRIT: $patch"
        kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid) || true
        break
    fi
    kill -TERM $(cat /Users/rheninxy/Sourby/SourbyCraft/test-harness/TestServer-mojmap/.server.pid) || true
    sleep 5
    mv "${patch}.disabled" "$patch"
done
```

- [ ] **Step 4: Apply fix or document** (same as Task 10 Step 4)

- [ ] **Step 5: Update matrix notes**

```markdown
### FastAsyncWorldEdit

- Root-cause class: <A|B|C|D|E>
- Affected variant(s): <mojmap | reobf | both>
- Diagnosis: <one-paragraph summary>
- Resolution: <commit SHA of fix, or UPSTREAM, or WORKAROUND>
```

- [ ] **Step 6: Commit + re-capture matrix** (substitute "FastAsyncWorldEdit" in commit message)

### Task 14: Phase 3 invariant check — every plugin green on at least one variant

- [ ] **Step 1: Read the latest matrix file**

```bash
ls -t /Users/rheninxy/Sourby/SourbyCraft/docs/superpowers/notes/*-nms-compat-matrix-r*.md | head -1
LATEST_MATRIX=$(ls -t /Users/rheninxy/Sourby/SourbyCraft/docs/superpowers/notes/*-nms-compat-matrix-r*.md | head -1)
echo "Reading: $LATEST_MATRIX"
cat "$LATEST_MATRIX"
```

- [ ] **Step 2: Verify each plugin has at least one row with `enabled=yes sanity=yes`**

For each plugin in {Citizens, NBTAPI, DecentHolograms, FastAsyncWorldEdit}, both the mojmap row and the reobf row should be readable. Confirm at least one says `yes | yes`. If any plugin has both rows failing AND no documented WORKAROUND/UPSTREAM in the investigation notes, return to Tasks 10-13 to triage further.

- [ ] **Step 3: Commit the final matrix state**

If the latest matrix file is uncommitted:

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git add -f docs/superpowers/notes/
git status --short docs/superpowers/notes/
git commit -m "notes: NMS-compat matrix — final round; invariant met (each plugin green on at least one variant)" || echo "(nothing new to commit)"
```

---

## Phase 4 — Smoke harness gradle task + CI gate

### Task 15: Implement `CompatHarness.java` runner

**Files:**
- Create: `/Users/rheninxy/Sourby/SourbyCraft/sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/nms/CompatHarness.java`

- [ ] **Step 1: Write the runner**

Create `/Users/rheninxy/Sourby/SourbyCraft/sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/nms/CompatHarness.java`:

```java
package dev.iyanz.sourbycraft.nms;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads nms-compat-result.json produced by sanity-harness-plugin and emits JUnit XML.
 * Args: <variantLabel> <resultJsonPath> <junitXmlPath>
 */
public final class CompatHarness {

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: CompatHarness <variantLabel> <resultJsonPath> <junitXmlPath>");
            System.exit(2);
        }
        String variant = args[0];
        Path resultJson = Paths.get(args[1]);
        Path junitXml = Paths.get(args[2]);

        if (!Files.exists(resultJson)) {
            System.err.println("CompatHarness: missing " + resultJson);
            System.exit(3);
        }

        String json = Files.readString(resultJson, StandardCharsets.UTF_8);
        List<TestCase> cases = parseRows(variant, json);

        Files.createDirectories(junitXml.getParent());
        try (OutputStream os = Files.newOutputStream(junitXml)) {
            writeJunit(os, variant, cases);
        }

        int failures = (int) cases.stream().filter(c -> !c.passed).count();
        System.out.println("CompatHarness[" + variant + "]: " + cases.size()
            + " tests, " + failures + " failures");
        // exit 0 even if individual cases fail; gradle aggregates per variant
        System.exit(0);
    }

    static List<TestCase> parseRows(String variant, String json) {
        List<TestCase> out = new ArrayList<>();
        // Lightweight per-row regex extraction — avoids pulling a json lib into the test runner.
        Pattern row = Pattern.compile(
            "\\{[^}]*\"plugin\"\\s*:\\s*\"([^\"]+)\"" +
            "[^}]*\"enabled\"\\s*:\\s*(true|false)" +
            "[^}]*\"sanity_passed\"\\s*:\\s*(true|false)" +
            "[^}]*\"fail_reason\"\\s*:\\s*\"([^\"]*)\"" +
            "[^}]*\"stack_hash\"\\s*:\\s*\"([^\"]*)\"",
            Pattern.DOTALL);
        Matcher m = row.matcher(json);
        while (m.find()) {
            TestCase tc = new TestCase();
            tc.plugin = m.group(1);
            tc.enabled = Boolean.parseBoolean(m.group(2));
            tc.sanityPassed = Boolean.parseBoolean(m.group(3));
            tc.failReason = m.group(4);
            tc.stackHash = m.group(5);
            tc.passed = tc.enabled && tc.sanityPassed;
            out.add(tc);
        }
        return out;
    }

    static void writeJunit(OutputStream os, String variant, List<TestCase> cases) throws IOException {
        StringBuilder sb = new StringBuilder();
        long failures = cases.stream().filter(c -> !c.passed).count();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"nms-compat-").append(esc(variant))
          .append("\" tests=\"").append(cases.size())
          .append("\" failures=\"").append(failures).append("\">\n");
        for (TestCase tc : cases) {
            sb.append("  <testcase classname=\"NmsCompat.").append(esc(variant))
              .append("\" name=\"").append(esc(tc.plugin)).append("\">\n");
            if (!tc.passed) {
                sb.append("    <failure message=\"")
                  .append(esc(tc.failReason.isEmpty() ? "sanity_failed" : tc.failReason))
                  .append("\">stack_hash=").append(esc(tc.stackHash)).append("</failure>\n");
            }
            sb.append("  </testcase>\n");
        }
        sb.append("</testsuite>\n");
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    static final class TestCase {
        String plugin;
        boolean enabled;
        boolean sanityPassed;
        String failReason;
        String stackHash;
        boolean passed;
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
./gradlew :sourbycraft-server:compileTestJava --offline 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/nms/CompatHarness.java
git commit -m "test: CompatHarness — read sanity-harness JSON, emit JUnit XML"
```

### Task 16: Unit test for `CompatHarness.parseRows` + stack-hash determinism

**Files:**
- Create: `/Users/rheninxy/Sourby/SourbyCraft/sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/nms/CompatHarnessTest.java`

- [ ] **Step 1: Write the failing test**

Create `/Users/rheninxy/Sourby/SourbyCraft/sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/nms/CompatHarnessTest.java`:

```java
package dev.iyanz.sourbycraft.nms;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompatHarnessTest {

    @Test
    void parsesTwoRowsFromJson() {
        String json = "[\n" +
            "  {\"plugin\":\"Citizens\",\"enabled\":true,\"sanity_passed\":true,\"fail_reason\":\"\",\"stack_hash\":\"\"},\n" +
            "  {\"plugin\":\"NBTAPI\",\"enabled\":true,\"sanity_passed\":false,\"fail_reason\":\"NoSuchMethodError\",\"stack_hash\":\"abc123\"}\n" +
            "]\n";
        List<CompatHarness.TestCase> cases = CompatHarness.parseRows("mojmap", json);

        assertEquals(2, cases.size());
        assertEquals("Citizens", cases.get(0).plugin);
        assertTrue(cases.get(0).passed);
        assertEquals("NBTAPI", cases.get(1).plugin);
        assertFalse(cases.get(1).passed);
        assertEquals("NoSuchMethodError", cases.get(1).failReason);
        assertEquals("abc123", cases.get(1).stackHash);
    }

    @Test
    void emitsJunitWithFailureNode() throws Exception {
        String json = "[" +
            "{\"plugin\":\"X\",\"enabled\":true,\"sanity_passed\":false,\"fail_reason\":\"oops\",\"stack_hash\":\"ff\"}" +
            "]";
        List<CompatHarness.TestCase> cases = CompatHarness.parseRows("reobf", json);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CompatHarness.writeJunit(baos, "reobf", cases);
        String xml = baos.toString();
        assertTrue(xml.contains("<testsuite name=\"nms-compat-reobf\""), "header present: " + xml);
        assertTrue(xml.contains("<failure message=\"oops\""), "failure node present: " + xml);
        assertTrue(xml.contains("stack_hash=ff"), "stack hash present: " + xml);
    }

    @Test
    void emptyResultProducesEmptySuite() throws Exception {
        List<CompatHarness.TestCase> cases = CompatHarness.parseRows("mojmap", "[]");
        assertEquals(0, cases.size());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CompatHarness.writeJunit(baos, "mojmap", cases);
        assertTrue(baos.toString().contains("tests=\"0\""));
        assertTrue(baos.toString().contains("failures=\"0\""));
    }
}
```

- [ ] **Step 2: Run the test**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
./gradlew :sourbycraft-server:test --tests "dev.iyanz.sourbycraft.nms.CompatHarnessTest" --offline 2>&1 | tail -15
```
Expected: PASS (3/3 tests).

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/nms/CompatHarnessTest.java
git commit -m "test: CompatHarnessTest — parseRows + writeJunit coverage"
```

### Task 17: Add gradle `:sourbycraft-server:nmsCompatTest` task (opt-in via `-PrunNmsCompat=true`)

**Files:**
- Modify: `/Users/rheninxy/Sourby/SourbyCraft/sourbycraft-server/build.gradle.kts`

- [ ] **Step 1: Append the task definition at end of `sourbycraft-server/build.gradle.kts`**

Open `/Users/rheninxy/Sourby/SourbyCraft/sourbycraft-server/build.gradle.kts`. Append at end of file:

```kotlin

// SourbyCraft v12 — NMS-compat smoke harness task. Opt-in: `-PrunNmsCompat=true`.
val runNmsCompat = providers.gradleProperty("runNmsCompat").map { it.toBoolean() }.getOrElse(false)

if (runNmsCompat) {
    val nmsCompatMojmap = tasks.register<JavaExec>("nmsCompatTestMojmap") {
        group = "verification"
        description = "Run NMS-compat smoke harness against the mojmap paperclip jar"
        dependsOn(":assembleReleaseArtifacts", ":test-harness:sanity-harness-plugin:jar")
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.iyanz.sourbycraft.nms.CompatHarness")
        doFirst {
            val script = rootProject.file("test-harness/scripts/boot-mojmap.sh")
            val proc = ProcessBuilder(script.absolutePath).inheritIO().start()
            val exit = proc.waitFor()
            if (exit != 0) {
                throw GradleException("boot-mojmap.sh failed with exit $exit")
            }
            // give sanity-harness-plugin's 20-tick (~1s) delayed task time to write the JSON
            Thread.sleep(3000)
        }
        args(
            "mojmap",
            rootProject.file("test-harness/TestServer-mojmap/nms-compat-result.json").absolutePath,
            layout.buildDirectory.file("test-results/nms-compat/mojmap.xml").get().asFile.absolutePath
        )
        doLast {
            // Send SIGTERM to the running server
            val pidFile = rootProject.file("test-harness/TestServer-mojmap/.server.pid")
            if (pidFile.exists()) {
                val pid = pidFile.readText().trim()
                ProcessBuilder("kill", "-TERM", pid).start().waitFor()
                // Wait until process is gone (max 60s)
                var elapsed = 0L
                while (elapsed < 60_000L) {
                    val check = ProcessBuilder("ps", "-p", pid).start()
                    if (check.waitFor() != 0) break
                    Thread.sleep(2000); elapsed += 2000
                }
            }
        }
    }

    val nmsCompatReobf = tasks.register<JavaExec>("nmsCompatTestReobf") {
        group = "verification"
        description = "Run NMS-compat smoke harness against the reobf paperclip jar"
        dependsOn(":assembleReleaseArtifacts", ":test-harness:sanity-harness-plugin:jar", nmsCompatMojmap)
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.iyanz.sourbycraft.nms.CompatHarness")
        doFirst {
            val script = rootProject.file("test-harness/scripts/boot-reobf.sh")
            val proc = ProcessBuilder(script.absolutePath).inheritIO().start()
            val exit = proc.waitFor()
            if (exit != 0) {
                throw GradleException("boot-reobf.sh failed with exit $exit")
            }
            Thread.sleep(3000)
        }
        args(
            "reobf",
            rootProject.file("test-harness/TestServer-reobf/nms-compat-result.json").absolutePath,
            layout.buildDirectory.file("test-results/nms-compat/reobf.xml").get().asFile.absolutePath
        )
        doLast {
            val pidFile = rootProject.file("test-harness/TestServer-reobf/.server.pid")
            if (pidFile.exists()) {
                val pid = pidFile.readText().trim()
                ProcessBuilder("kill", "-TERM", pid).start().waitFor()
                var elapsed = 0L
                while (elapsed < 60_000L) {
                    val check = ProcessBuilder("ps", "-p", pid).start()
                    if (check.waitFor() != 0) break
                    Thread.sleep(2000); elapsed += 2000
                }
            }
        }
    }

    val nmsCompatTest = tasks.register("nmsCompatTest") {
        group = "verification"
        description = "Run NMS-compat smoke harness against both paperclip jars"
        dependsOn(nmsCompatMojmap, nmsCompatReobf)
        doLast {
            // Assert invariant: each plugin green on at least ONE variant
            val mojmapResult = rootProject.file("test-harness/TestServer-mojmap/nms-compat-result.json")
            val reobfResult = rootProject.file("test-harness/TestServer-reobf/nms-compat-result.json")
            if (!mojmapResult.exists() || !reobfResult.exists()) {
                throw GradleException("nmsCompatTest: missing result file(s)")
            }
            val plugins = listOf("Citizens", "NBTAPI", "DecentHolograms", "FastAsyncWorldEdit")
            val mojJson = mojmapResult.readText()
            val reoJson = reobfResult.readText()
            val failed = mutableListOf<String>()
            for (p in plugins) {
                val mojOk = Regex("\"plugin\"\\s*:\\s*\"$p\"[^}]*\"sanity_passed\"\\s*:\\s*true").containsMatchIn(mojJson)
                val reoOk = Regex("\"plugin\"\\s*:\\s*\"$p\"[^}]*\"sanity_passed\"\\s*:\\s*true").containsMatchIn(reoJson)
                if (!mojOk && !reoOk) failed.add(p)
            }
            if (failed.isNotEmpty()) {
                throw GradleException("nmsCompatTest: invariant violated — plugins failed on both variants: $failed")
            }
            logger.lifecycle("nmsCompatTest: invariant met — every plugin green on at least one variant")
        }
    }
}
```

- [ ] **Step 2: Verify the task graph loads with the flag**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
./gradlew :sourbycraft-server:nmsCompatTest --dry-run -PrunNmsCompat=true --offline 2>&1 | tail -15
```
Expected: lines listing `:assembleReleaseArtifacts`, `:test-harness:sanity-harness-plugin:jar`, `:sourbycraft-server:nmsCompatTestMojmap`, `:sourbycraft-server:nmsCompatTestReobf`, `:sourbycraft-server:nmsCompatTest` SKIPPED.

- [ ] **Step 3: Verify the task is hidden without the flag**

```bash
./gradlew :sourbycraft-server:tasks --group=verification --offline 2>&1 | grep -i nmsCompat
```
Expected: empty output (task only registered when `-PrunNmsCompat=true`).

- [ ] **Step 4: Run the full smoke**

```bash
./gradlew :sourbycraft-server:nmsCompatTest -PrunNmsCompat=true --offline 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`. Logs show `boot-mojmap: server up`, harness writes XML, mojmap server SIGTERMed, reobf boot, harness writes XML, reobf SIGTERMed, final line `nmsCompatTest: invariant met …`.

- [ ] **Step 5: Inspect emitted JUnit XML**

```bash
ls /Users/rheninxy/Sourby/SourbyCraft/sourbycraft-server/build/test-results/nms-compat/
cat /Users/rheninxy/Sourby/SourbyCraft/sourbycraft-server/build/test-results/nms-compat/mojmap.xml
cat /Users/rheninxy/Sourby/SourbyCraft/sourbycraft-server/build/test-results/nms-compat/reobf.xml
```
Expected: 2 XML files, each with 4 `<testcase>` elements (one per plugin).

- [ ] **Step 6: Commit**

```bash
git add sourbycraft-server/build.gradle.kts
git commit -m "build: nmsCompatTest gradle task — boot both variants + assert invariant

Opt-in via -PrunNmsCompat=true. Wraps boot-{mojmap,reobf}.sh in JavaExec
tasks that drive CompatHarness, emit JUnit XML to
build/test-results/nms-compat/, and validate that every target plugin
is green on at least one variant."
```

### Task 18: Document operator smoke checklist

**Files:**
- Create: `/Users/rheninxy/Sourby/SourbyCraft/docs/superpowers/notes/2026-06-03-nms-compat-operator-checklist.md`

- [ ] **Step 1: Write the checklist**

Create `/Users/rheninxy/Sourby/SourbyCraft/docs/superpowers/notes/2026-06-03-nms-compat-operator-checklist.md`:

```markdown
# SourbyCraft v12 — NMS plugin operator smoke checklist

This is a manual checklist for verifying Citizens, NBTAPI, DecentHolograms, and
FastAsyncWorldEdit against a fresh SourbyCraft install. Use it after each
SourbyCraft release. For automated coverage, run
`./gradlew :sourbycraft-server:nmsCompatTest -PrunNmsCompat=true`.

## Setup

1. Download both jars from the release page:
   - `SourbyCraft-v12-REL.jar` (mojmap — recommended for modern plugins)
   - `SourbyCraft-v12-REL-reobf.jar` (reobf — legacy NMS plugin compat)
2. For each jar, create a fresh server directory (`Smoke-mojmap/`, `Smoke-reobf/`).
3. In each directory:
   - Copy the jar as `server.jar`.
   - Create `eula.txt` containing `eula=true`.
   - Create `plugins/`.
   - Download the latest 1.21.11-compatible builds of:
     - Citizens — https://ci.citizensnpcs.co/job/Citizens2/lastSuccessfulBuild/
     - NBTAPI — https://www.spigotmc.org/resources/nbtapi.7939/
     - DecentHolograms — https://www.spigotmc.org/resources/decentholograms.96927/
     - FastAsyncWorldEdit — https://ci.athion.net/job/FastAsyncWorldEdit/lastSuccessfulBuild/
   - Place all four jars in `plugins/`.

## Boot + verify

1. Start the server: `java -Xmx2G -jar server.jar nogui`.
2. Wait for `Done (XX.Xs)!` (expect under 60s on modern hardware).
3. Confirm no `FATAL` or `Caused by:` lines in the boot log.
4. In the console, run:

```text
/version Citizens
/version NBTAPI
/version DecentHolograms
/version FastAsyncWorldEdit
```
Each should print the plugin version + author + website. If any reports
"plugin not found" or shows an error, that variant of SourbyCraft is broken
for that plugin — try the other variant.

5. Smoke-test commands:

```text
/npc create TestNPC               # Citizens — expect: "Created NPC ..." and a villager near you
/dh create test_holo Hello World  # DecentHolograms — expect: "Hologram created"
/fawe schem list                  # FAWE — expect: schematic list or "no schematics"
```

6. Shut down: `stop` in the console. Expect clean shutdown (no exception in log
   after `Stopping server`).

## What to report on failure

If any step fails, file an issue (or run `./gradlew :sourbycraft-server:nmsCompatTest -PrunNmsCompat=true`
to capture machine-readable output). Include:

- Jar variant (mojmap or reobf).
- Plugin name + version (`/version <name>`).
- Boot log excerpt (last 100 lines around the failure).
- `nms-compat-result.json` if present in the server's working directory.
```

- [ ] **Step 2: Commit**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git add -f docs/superpowers/notes/2026-06-03-nms-compat-operator-checklist.md
git commit -m "notes: operator smoke checklist — manual Citizens/NBTAPI/DH/FAWE verification"
```

### Task 19: Add CI gate config

**Files:**
- Create: `/Users/rheninxy/Sourby/SourbyCraft/.github/workflows/nms-compat.yml`

- [ ] **Step 1: Confirm the workflows directory pattern**

```bash
ls /Users/rheninxy/Sourby/SourbyCraft/.github/workflows/ 2>/dev/null || echo "no workflows yet"
```

If the directory does not exist, create it: `mkdir -p /Users/rheninxy/Sourby/SourbyCraft/.github/workflows/`.

- [ ] **Step 2: Write the workflow**

Create `/Users/rheninxy/Sourby/SourbyCraft/.github/workflows/nms-compat.yml`:

```yaml
name: NMS Compat

on:
  pull_request:
    paths:
      - 'patches/**'
      - 'release/**'
      - 'gradle.properties'
      - 'sourbycraft-server/build.gradle.kts'
      - 'build.gradle.kts'
      - 'test-harness/**'
  workflow_dispatch:

jobs:
  smoke:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'

      - name: Cache Gradle + paperweight
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
            .gradle/caches/paperweight
          key: ${{ runner.os }}-gradle-${{ hashFiles('gradle.properties', '**/*.gradle.kts') }}

      - name: Cache test-plugin jars
        uses: actions/cache@v4
        with:
          path: test-harness/test-plugins/*.jar
          key: ${{ runner.os }}-test-plugins-${{ hashFiles('test-harness/test-plugins/manifest.yml') }}

      - name: Apply paperweight patches
        run: ./gradlew applyAllPatches --offline 2>&1 | tail -30

      - name: Download test plugins
        run: test-harness/scripts/download-test-plugins.sh

      - name: Run NMS-compat smoke harness
        run: ./gradlew :sourbycraft-server:nmsCompatTest -PrunNmsCompat=true

      - name: Upload JUnit XML
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: nms-compat-junit
          path: sourbycraft-server/build/test-results/nms-compat/

      - name: Upload boot logs
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: nms-compat-boot-logs
          path: |
            test-harness/TestServer-mojmap/boot.log
            test-harness/TestServer-reobf/boot.log
            test-harness/TestServer-mojmap/nms-compat-result.json
            test-harness/TestServer-reobf/nms-compat-result.json
```

- [ ] **Step 3: Commit**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git add .github/workflows/nms-compat.yml
git commit -m "ci: NMS-compat gate on PRs touching patches/, release/, paperRef, or test-harness/"
```

### Task 20: Final sweep + commit clean state

- [ ] **Step 1: Confirm zero residual TestServer dirty state in git**

```bash
cd /Users/rheninxy/Sourby/SourbyCraft
git status --short
```
Expected: only pre-existing pending changes (`PluginDownloader.java`, `PluginEntry.java`) from before this plan. No new working-tree changes.

- [ ] **Step 2: Run the full smoke once more as a closing gate**

```bash
./gradlew clean
./gradlew applyAllPatches --offline
./gradlew :sourbycraft-server:nmsCompatTest -PrunNmsCompat=true --offline 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`. Final line `nmsCompatTest: invariant met — every plugin green on at least one variant`.

- [ ] **Step 3: Verify the release artifacts are still intact**

```bash
ls -la /Users/rheninxy/Sourby/SourbyCraft/release/
cat /Users/rheninxy/Sourby/SourbyCraft/release/checksums.txt
wc -l /Users/rheninxy/Sourby/SourbyCraft/release/checksums.txt
```
Expected: 2 jars + 1 `checksums.txt` with 2 lines.

- [ ] **Step 4: If the closing smoke produced new commits (matrix updates), confirm log**

```bash
git log --oneline -30
```
Expected: all NMS-compat plan tasks reflected as commits, terminating with the closing smoke (if it produced output deltas).

---

## Out-of-Scope Reminders

These follow-up specs explicitly NOT covered by this plan (carried forward from the design spec):

1. **UniverseSpigot config import (~200 keys)** — tracked from earlier brainstorm.
2. **Generic NMS shim API** — rejected during this brainstorm.
3. **WorldGuard, EssentialsX, LuckPerms** explicit testing — covered transitively via FAWE/WorldEdit, or unnecessary (permissions plugins do not touch NMS).
4. **Velocity proxy-side compat** — proxy-kick patch (former 9005) is runtime-gated; out of scope.
5. **Multi-plugin interaction tests** — separate follow-up spec.
6. **Performance benchmarks under plugin load** — separate follow-up spec.

---

## Verification Summary

After all phases complete, the following should be true:

| Check | Command | Expected |
|---|---|---|
| Two release jars present | `ls release/*.jar \| wc -l` | `2` |
| Single checksums.txt with 2 lines | `wc -l release/checksums.txt` | `2` |
| Sanity harness plugin built | `ls test-harness/sanity-harness-plugin/build/libs/sanity-harness-plugin.jar` | file exists |
| Test plugins cached | `ls test-harness/test-plugins/*.jar \| wc -l` | `4` |
| Matrix file(s) committed | `ls docs/superpowers/notes/*-nms-compat-matrix-r*.md` | at least 1 file |
| Operator checklist | `ls docs/superpowers/notes/*-nms-compat-operator-checklist.md` | 1 file |
| CI workflow | `ls .github/workflows/nms-compat.yml` | file exists |
| Smoke task succeeds | `./gradlew :sourbycraft-server:nmsCompatTest -PrunNmsCompat=true` | `BUILD SUCCESSFUL` |
| Invariant met | last log line of above | `nmsCompatTest: invariant met …` |
| Default test fast-path preserved | `./gradlew test \| grep -c nmsCompat` | `0` |
