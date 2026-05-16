# SWM Plugin Fix, Paperclip Cleanup, NMS Optimization — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Perbaiki duplikasi SWM plugin, hapus paperclip.conf statis, ganti NMS reflection dengan Access Transformer.

**Architecture:** Three independent changes: (1) perbaiki SWM plugin eksternal build + update README, (2) hapus paperclip.conf dan update referensi, (3) ganti reflection di NMSSlimeChunk dengan AT direct field access.

**Tech Stack:** Java 25, Paper/Pufferfish 1.21.11, Paperweight patcher, Access Transformers

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `swm-plugin/build.gradle.kts` | Modify | Update Paper API version |
| `swm-plugin/src/main/resources/plugin.yml` | Verify | Ensure main class correct |
| `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/swm/server/NMSSlimeChunk.java` | Modify | Replace reflection with direct access |
| `build-data/sourbycraft.at` | Modify | Add AT entry for `ChunkAccess.sections` |
| `paperclip.conf` | Delete | Remove static JVM flags file |
| `scripts/egg-sourbycraft.yaml` | Modify | Replace paperclip.conf startup with gc-tuner.sh |
| `README.md` | Modify | Add full SWM docs, remove paperclip.conf refs, update startup |

---

### Task 1: Perbaiki SWM Plugin Build

**Files:**
- Modify: `swm-plugin/build.gradle.kts`

- [ ] **Step 1: Update Paper API version in swm-plugin/build.gradle.kts**

Change line 15 from `1.21.3-R0.1-SNAPSHOT` to `1.21.4-R0.1-SNAPSHOT`:

```kotlin
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
```

- [ ] **Step 2: Build swm-plugin to verify**

```bash
cd swm-plugin && ./gradlew build 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add swm-plugin/build.gradle.kts
git commit -m "fix: update swm-plugin Paper API to 1.21.4"
```

---

### Task 2: Ganti NMS Reflection dengan Access Transformer

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/swm/server/NMSSlimeChunk.java`
- Modify: `build-data/sourbycraft.at`

- [ ] **Step 1: Add AT entry for ChunkAccess.sections**

Add to `build-data/sourbycraft.at` (after existing entries):

```
public-f net.minecraft.world.level.chunk.ChunkAccess sections
```

This makes the `sections` field in `ChunkAccess` publicly accessible without reflection.

- [ ] **Step 2: Replace reflection in NMSSlimeChunk.getSections()**

In `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/swm/server/NMSSlimeChunk.java`, replace lines 58-83:

Before:
```java
        try {
            java.lang.reflect.Field f = ChunkAccess.class.getDeclaredField("sections");
            f.setAccessible(true);
            LevelChunkSection[] arr = (LevelChunkSection[]) f.get(chunk);
            if (arr == null) return result;

            for (int i = 0; i < arr.length; i++) {
                LevelChunkSection section = arr[i];
                if (section == null) continue;

                byte[] blockLight = null;
                DataLayer blockLayer = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(chunk.getPos(), i));
                if (blockLayer != null) blockLight = blockLayer.getData().clone();

                byte[] skyLight = null;
                DataLayer skyLayer = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(chunk.getPos(), i));
                if (skyLayer != null) skyLight = skyLayer.getData().clone();

                result.add(SlimeChunkConverter.convertChunkSection(
                        chunk.level.palettedContainerFactory().biomeContainerCodec(),
                        chunk.level.palettedContainerFactory().blockStatesContainerCodec(),
                        section, blockLight, skyLight));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get sections from chunk", e);
        }
```

After:
```java
        LevelChunkSection[] arr = chunk.sections;
        if (arr == null) return result;

        for (int i = 0; i < arr.length; i++) {
            LevelChunkSection section = arr[i];
            if (section == null) continue;

            byte[] blockLight = null;
            DataLayer blockLayer = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(chunk.getPos(), i));
            if (blockLayer != null) blockLight = blockLayer.getData().clone();

            byte[] skyLight = null;
            DataLayer skyLayer = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(chunk.getPos(), i));
            if (skyLayer != null) skyLight = skyLayer.getData().clone();

            result.add(SlimeChunkConverter.convertChunkSection(
                    chunk.level.palettedContainerFactory().biomeContainerCodec(),
                    chunk.level.palettedContainerFactory().blockStatesContainerCodec(),
                    section, blockLight, skyLight));
        }
```

Key changes:
- Remove `try/catch` block (no reflection exception possible)
- Remove `java.lang.reflect.Field f = ...` and `f.setAccessible(true)`
- Replace `(LevelChunkSection[]) f.get(chunk)` with `chunk.sections`
- Remove `LOGGER.error("Failed to get sections from chunk", e)` (no longer needed)

Also remove the `import java.lang.reflect.Field` if present (check imports at top of file).

- [ ] **Step 3: Build server to verify AT and compilation**

```bash
./gradlew sourbycraft-server:build -x test 2>&1 | grep -E "error:|BUILD" | head -10
```

Expected: `BUILD SUCCESSFUL` with no errors.

Note: After modifying `build-data/sourbycraft.at`, you may need to re-apply patches for the AT to take effect:
```bash
./gradlew sourbycraft-server:applyMinecraftPatches --rerun-tasks
./gradlew sourbycraft-server:build -x test
```

- [ ] **Step 4: Verify no reflection remains in SWM codebase**

```bash
grep -rn "getDeclaredField\|setAccessible\|getDeclaredMethod" sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/swm/ | head -5
```

Expected: No output (all reflection removed).

- [ ] **Step 5: Commit**

```bash
git add build-data/sourbycraft.at sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/swm/server/NMSSlimeChunk.java
git commit -m "perf: replace NMSSlimeChunk reflection with AT direct field access"
```

---

### Task 3: Hapus paperclip.conf dan Update Referensi

**Files:**
- Delete: `paperclip.conf`
- Modify: `scripts/egg-sourbycraft.yaml`
- Modify: `README.md`

- [ ] **Step 1: Delete paperclip.conf**

```bash
rm paperclip.conf
```

- [ ] **Step 2: Update egg-sourbycraft.yaml startup command**

In `scripts/egg-sourbycraft.yaml`, replace the `Default` startup command (line 23) from the inline JVM flags to use gc-tuner.sh:

Before (line 23):
```yaml
  Default: 'java -Xms128M --add-modules=jdk.incubator.vector -XX:+UseZGC ... -jar {{SERVER_JARFILE}} --nogui'
```

After:
```yaml
  Default: './scripts/gc-tuner.sh --start'
```

Note: The full gc-tuner.sh already has `--start` flag that detects system specs, generates optimal JVM flags, and starts the server. The egg should call it directly.

Also add `scripts/gc-tuner.sh` to the installation script so it's available. After line 99 (`echo "SourbyCraft installation complete"`), add:

```bash
      # Copy gc-tuner.sh
      if [ ! -f gc-tuner.sh ]; then
        curl -sL "https://raw.githubusercontent.com/${REPO}/main/scripts/gc-tuner.sh" -o gc-tuner.sh
        chmod +x gc-tuner.sh
      fi
```

- [ ] **Step 3: Update README.md**

Replace the **Startup** section (lines 120-131) with:

```markdown
## Startup

```bash
# Auto-tune GC and start (recommended)
./scripts/gc-tuner.sh --start

# Or manually with custom JAR name
./scripts/gc-tuner.sh --start --jar my-server.jar

# Generate flags only (no start)
./scripts/gc-tuner.sh > start.flags
java @start.flags -jar sourbycraft-paperclip-v4-REL-mojmap.jar --nogui
```

The `gc-tuner.sh` script auto-detects system specs (CPU cores, RAM) and selects the optimal GC strategy (ZGC, Shenandoah, or G1) with tuned flags.

---

Remove line 70 from the **Infrastructure** section:

```markdown
- **paperclip.conf** — full JVM flags via `@file` format
```

Replace with:

```markdown
- **GC Auto-Tuner** — `scripts/gc-tuner.sh` selects optimal GC + generates flags
```

(Note: line 67 already has this. So just delete line 70.)

Add a full SWM section after the existing `### 🌍 SlimeWorldManager (SWM v2)` section (after line 62). Replace lines 55-62 with an expanded section:

```markdown
### 🌍 SlimeWorldManager (SWM v2)
Built-in SlimeWorldManager for `.slime` world format. SRF v13 binary format with Zstd compression.

**Two deployment modes:**

| Mode | Description | When to use |
|------|-------------|-------------|
| **Built-in** | Server-internal `SWPlugin` auto-starts with `swm.enabled: true` | Default — worlds load from `slime_worlds/` at startup |
| **External plugin** | Standalone `SourbyCraftSWM.jar` plugin for external plugins | When third-party plugins need SWM API access |

**Commands:**
- `/swm list` — shows `.slime` worlds with `[LOADED]` status
- `/swm load <world>` — loads a slime world at runtime
- `/swm save <world>` — serializes and persists a loaded world
- `/swm info` — loaded/found world counts

**Configuration** (`sourbycraft.yml`):
```yaml
swm:
  enabled: true           # Enable built-in SWM bootstrap at startup
  auto-install: true       # Auto-download external plugin JAR
  version: "v4-REL"        # Plugin version to download
  file-dir: slime_worlds   # Directory for .slime world files
```

**API usage** (for plugin developers):
```java
AdvancedSlimePaperAPI swm = AdvancedSlimePaperAPI.instance();
SlimeWorld world = swm.readWorld(new FileLoader("slime_worlds"), "myworld", false, new SlimePropertyMap());
swm.loadWorld(world, true);
```
```

(This replaces the existing brief SWM section and adds the two-mode explanation, config details, and API example.)

- [ ] **Step 4: Commit**

```bash
git add paperclip.conf scripts/egg-sourbycraft.yaml README.md
git commit -m "chore: remove paperclip.conf, use gc-tuner.sh, update README with SWM docs"
```

---

### Task 4: Verification

- [ ] **Step 1: Full clean build**

```bash
./gradlew sourbycraft-server:applyAllPatches --rerun-tasks 2>&1 | tail -5
./gradlew sourbycraft-server:build -x test 2>&1 | tail -5
cd swm-plugin && ./gradlew build 2>&1 | tail -5
```

Expected: All three commands return `BUILD SUCCESSFUL`.

- [ ] **Step 2: Verify no reflection in SWM**

```bash
grep -rn "getDeclaredField\|setAccessible" sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/swm/
```

Expected: No output.

- [ ] **Step 3: Verify AT is effective**

```bash
javap -p sourbycraft-server/build/classes/java/main/net/minecraft/world/level/chunk/ChunkAccess.class 2>/dev/null | grep "sections"
```

Expected: Field `sections` is accessible (should show `public` or `public-f` modifier).

- [ ] **Step 4: Verify paperclip.conf is gone**

```bash
test -f paperclip.conf && echo "FAIL: file still exists" || echo "OK: file deleted"
```

Expected: `OK: file deleted`

- [ ] **Step 5: Push**

```bash
git push origin ver/1.21.11
```

---

## Self-Review

1. **Spec coverage:** T.1 (SWM plugin fix) → Task 1. T.2 (Delete paperclip.conf) → Task 3. T.3 (Replace reflection) → Task 2. README docs → Task 3. ✅
2. **Placeholder scan:** No TBD/TODO. All code blocks have actual implementation. ✅
3. **Type consistency:** `ChunkAccess.sections` is `LevelChunkSection[]` — consistent across AT entry and usage. ✅
4. **File paths:** All paths verified against project structure. ✅