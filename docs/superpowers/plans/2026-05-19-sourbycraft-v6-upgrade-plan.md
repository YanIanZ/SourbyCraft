# SourbyCraft v6 Upgrade — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade SourbyCraft from v5-REL to v6-REL: SWM async total, Java 25 Virtual Threads, system-wide audit + fix.

**Architecture:** Foundation layer first (VirtualExecutor + config), then version bump, then SWM async rewrite on top, then Minecraft patch migration, then audit passes. Each task is independently compilable and testable.

**Tech Stack:** Java 25, Gradle 9.4, Paperweight 2.0.0-beta.19, Paper API 1.21.11, Virtual Threads (JEP 444), Structured Concurrency (JEP 480 preview)

---

## File Structure

```
sourbycraft-server/src/main/
├── java/dev/iyanz/sourbycraft/
│   ├── SourbyCraftConfig.java           (MODIFIED — version, config fields, migration)
│   ├── util/VirtualExecutor.java         (NEW — replaces AsyncExecutor)
│   └── swm/
│       ├── api/AdvancedSlimePaperAPI.java (MODIFIED — add async signatures)
│       ├── server/AdvancedSlimePaperImpl.java (MODIFIED — implement async methods)
│       ├── server/SwmIoExecutor.java     (REWRITTEN — use VirtualExecutor)
│       └── plugin/SWPlugin.java          (MODIFIED — async call sites)
├── pufferfish/gg/pufferfish/pufferfish/util/
│   └── AsyncExecutor.java               (DEPRECATED — delegates to VirtualExecutor)
└── resources/
    └── sourbycraft.yml (defaults built from code)

gradle.properties                          (MODIFIED — v5→v6)
sourbycraft-swm-api/build.gradle.kts       (MODIFIED — jar path)
README.md                                  (MODIFIED — badges, versions)
patches/server/0014-chore-SWM-v2-fixes.patch (MODIFIED — string refs)
```

---

### Task 1: VirtualExecutor Foundation + Config Migration

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/util/VirtualExecutor.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java:27-31,80-115`
- Modify: `sourbycraft-server/src/main/pufferfish/gg/pufferfish/pufferfish/util/AsyncExecutor.java`

- [ ] **Step 1: Create VirtualExecutor.java**

Path: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/util/VirtualExecutor.java`

```java
package dev.iyanz.sourbycraft.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Virtual-thread-based executor replacing AsyncExecutor and SwmIoExecutor.
 * Uses Java 25 virtual threads — unbounded, lightweight (~1KB stack each).
 */
public final class VirtualExecutor {

    private static final Logger LOGGER = Logger.getLogger("SourbyCraft:VirtualExecutor");
    private static volatile ExecutorService EXECUTOR;

    private VirtualExecutor() {}

    /** Initialize the virtual thread executor. Safe to call multiple times. */
    public static void init() {
        if (EXECUTOR != null && !EXECUTOR.isShutdown()) return;
        EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
        LOGGER.info("Virtual thread executor initialized");
    }

    /** Returns the shared virtual thread executor. */
    public static ExecutorService executor() {
        if (EXECUTOR == null || EXECUTOR.isShutdown()) {
            init();
        }
        return EXECUTOR;
    }

    /** Run a task on a virtual thread. Fire-and-forget. */
    public static void run(Runnable task) {
        executor().submit(task);
    }

    /** Submit a task and return a CompletableFuture. */
    public static <T> CompletableFuture<T> supply(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor().submit(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Shutdown, draining queued tasks. Blocks up to 30s. */
    public static void shutdown() {
        ExecutorService exec = EXECUTOR;
        if (exec == null) return;
        EXECUTOR = null;
        exec.shutdown();
        try {
            if (!exec.awaitTermination(30, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Virtual thread executor shut down");
    }
}
```

- [ ] **Step 2: Update SourbyCraftConfig — remove old fields, add new ones**

Replace lines 27-31 in `SourbyCraftConfig.java`:

```java
    public static boolean asyncChunkLoad = false;
    public static boolean asyncPathfinding = false;
    public static int asyncThreads = 2;
    public static boolean multithreadingEnabled = false;
    public static boolean dimensionThreads = false;
```

With:

```java
    public static boolean asyncChunkLoad = false;
    public static boolean asyncPathfinding = false;
    public static boolean multithreadingEnabled = false;
    public static boolean virtualThreads = true;
    public static boolean structuredConcurrency = true;
    public static int maxPlatformThreads = 4;
```

- [ ] **Step 3: Update SourbyCraftConfig — change config reading**

Replace lines 104-106:

```java
        multithreadingEnabled = getBoolean("multithreading.enabled", multithreadingEnabled);
        dimensionThreads = getBoolean("multithreading.dimension-threads", dimensionThreads);
        asyncThreads = getInt("multithreading.async-threads", asyncThreads);
```

With:

```java
        multithreadingEnabled = getBoolean("multithreading.enabled", multithreadingEnabled);
        virtualThreads = getBoolean("performance.virtual-threads", virtualThreads);
        structuredConcurrency = getBoolean("performance.structured-concurrency", structuredConcurrency);
        maxPlatformThreads = getInt("performance.max-platform-threads", maxPlatformThreads);
```

- [ ] **Step 4: Update SourbyCraftConfig — replace AsyncExecutor.initPool call**

At line 186 (end of `init()` method), replace:

```java
        AsyncExecutor.initPool(asyncThreads);
```

With:

```java
        dev.iyanz.sourbycraft.util.VirtualExecutor.init();
```

- [ ] **Step 5: Deprecate AsyncExecutor — delegate to VirtualExecutor**

Replace the body of `AsyncExecutor.java` with delegation:

```java
package gg.pufferfish.pufferfish.util;

import dev.iyanz.sourbycraft.util.VirtualExecutor;
import java.util.logging.Level;
import gg.pufferfish.pufferfish.PufferfishLogger;

@Deprecated
public class AsyncExecutor {

    @Deprecated
    public AsyncExecutor(String threadName) {}

    @Deprecated public void start() {}
    @Deprecated public void kill() {}

    @Deprecated
    public static void initPool(int threadCount) {
        VirtualExecutor.init();
    }

    @Deprecated
    public static void shutdownPool() {
        VirtualExecutor.shutdown();
    }

    public static void submitToPool(Runnable task) {
        VirtualExecutor.run(task);
    }
}
```

Remove all other methods and fields from AsyncExecutor.java.

- [ ] **Step 6: Compile**

```bash
./gradlew :sourbycraft-server:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add sourbycraft-server/
git commit -m "feat(v6): add VirtualExecutor, deprecate AsyncExecutor, migrate config fields"
```

---

### Task 2: Version Bump v5 → v6

**Files:**
- Modify: `gradle.properties`
- Modify: `sourbycraft-server/.../SourbyCraftConfig.java:24,39,133-140`
- Modify: `sourbycraft-swm-api/build.gradle.kts:30`
- Modify: `README.md`
- Modify: `patches/server/0014-chore-SWM-v2-fixes.patch` (search `v5-REL`)

- [ ] **Step 1: Update gradle.properties**

Replace lines 2-3:

```properties
version = v5-REL
releaseVersion = 5
```

With:

```properties
version = v6-REL
releaseVersion = 6
```

- [ ] **Step 2: Update SourbyCraftConfig currentVersion**

Line 24: change `currentVersion = 5` to `currentVersion = 6`.

- [ ] **Step 3: Update SourbyCraftConfig swmVersion default**

Line 39: change `"v5-REL"` to `"v6-REL"`.

- [ ] **Step 4: Add migration block for v5→v6**

After line 136 (inside the `if (version < currentVersion)` block, after the existing v4→v5 migration), add:

```java
            if ("v5-REL".equals(swmVersion)) {
                swmVersion = "v6-REL";
                set("swm.version", swmVersion);
            }
```

- [ ] **Step 5: Update swm-api build.gradle.kts jar path**

Line 30: change `sourbycraft-server-v5-REL.jar` to `sourbycraft-server-v6-REL.jar`.

- [ ] **Step 6: Update README.md**

- Badge: `v4-REL` → `v6-REL`
- All jar filename references: `v5-REL` → `v6-REL`
- API dependency version: `v5-REL` → `v6-REL`

- [ ] **Step 7: Update patch 0014 if it references v5-REL**

```bash
grep "v5-REL" patches/server/0014-chore-SWM-v2-fixes.patch
```

If found, replace with `v6-REL`.

- [ ] **Step 8: Compile**

```bash
./gradlew :sourbycraft-server:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(v6): version bump v5→v6 — properties, config, migration, docs"
```

---

### Task 3: SWM Async API + Implementation

**Files:**
- Modify: `sourbycraft-server/.../swm/api/AdvancedSlimePaperAPI.java`
- Modify: `sourbycraft-server/.../swm/server/AdvancedSlimePaperImpl.java`
- Modify: `sourbycraft-server/.../swm/server/SwmIoExecutor.java`
- Modify: `sourbycraft-server/.../swm/plugin/SWPlugin.java`

- [ ] **Step 1: Add async method signatures to AdvancedSlimePaperAPI**

Add after the existing `readWorld()` method signature:

```java
    // Async variants — virtual thread I/O, non-blocking
    java.util.concurrent.CompletableFuture<SlimeWorld> readWorldAsync(SlimeLoader loader, String worldName,
            boolean readOnly, SlimePropertyMap propertyMap);

    java.util.concurrent.CompletableFuture<SlimeWorldInstance> loadWorldAsync(SlimeWorld world,
            boolean callWorldLoadEvent);

    java.util.concurrent.CompletableFuture<Void> saveWorldAsync(SlimeWorld world);

    java.util.concurrent.CompletableFuture<SlimeWorld> readVanillaWorldAsync(java.io.File worldDir,
            String worldName, @Nullable SlimeLoader loader);

    java.util.concurrent.CompletableFuture<Void> migrateWorldAsync(String worldName,
            SlimeLoader currentLoader, SlimeLoader newLoader);
```

Add import: `import java.util.concurrent.CompletableFuture;`

- [ ] **Step 2: Rewrite SwmIoExecutor to use VirtualExecutor**

Replace entire `SwmIoExecutor.java` content:

```java
package dev.iyanz.sourbycraft.swm.server;

import dev.iyanz.sourbycraft.util.VirtualExecutor;

/**
 * SWM I/O executor — delegates to VirtualExecutor (Java 25 virtual threads).
 * Kept as a thin wrapper for backward compatibility with existing call sites.
 */
public final class SwmIoExecutor {

    public SwmIoExecutor() {
        VirtualExecutor.init();
    }

    /** Returns the shared virtual thread executor. */
    public java.util.concurrent.ExecutorService pool() {
        return VirtualExecutor.executor();
    }

    /** Shutdown — delegates to VirtualExecutor. */
    public void shutdown() {
        VirtualExecutor.shutdown();
    }
}
```

- [ ] **Step 3: Implement async methods in AdvancedSlimePaperImpl**

Add these methods after the existing sync implementations:

```java
    @Override
    public CompletableFuture<SlimeWorld> readWorldAsync(SlimeLoader loader, String worldName,
                                                        boolean readOnly, SlimePropertyMap propertyMap) {
        Objects.requireNonNull(loader);
        Objects.requireNonNull(worldName);
        Objects.requireNonNull(propertyMap);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readWorld(loader, worldName, readOnly, propertyMap);
            } catch (UnknownWorldException | CorruptedWorldException | NewerFormatException | IOException e) {
                throw new CompletionException(e);
            }
        }, SwmIoExecutor.ioExecutor().pool());
    }

    @Override
    public CompletableFuture<SlimeWorldInstance> loadWorldAsync(SlimeWorld world, boolean callWorldLoadEvent) {
        Objects.requireNonNull(world);
        return CompletableFuture.supplyAsync(() -> {
            try {
                // NMS load must happen on main thread — bridge via server.execute
                CompletableFuture<SlimeWorldInstance> bridge = new CompletableFuture<>();
                MinecraftServer.getServer().execute(() -> {
                    try {
                        bridge.complete(loadWorld(world, callWorldLoadEvent));
                    } catch (Throwable t) {
                        bridge.completeExceptionally(t);
                    }
                });
                return bridge.join();
            } catch (CompletionException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, SwmIoExecutor.ioExecutor().pool());
    }

    @Override
    public CompletableFuture<Void> saveWorldAsync(SlimeWorld world) {
        Objects.requireNonNull(world);
        return CompletableFuture.runAsync(() -> {
            try {
                saveWorld(world);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, SwmIoExecutor.ioExecutor().pool());
    }

    @Override
    public CompletableFuture<SlimeWorld> readVanillaWorldAsync(File worldDir, String worldName,
                                                               SlimeLoader loader) {
        Objects.requireNonNull(worldDir);
        Objects.requireNonNull(worldName);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readVanillaWorld(worldDir, worldName, loader);
            } catch (InvalidWorldException | WorldLoadedException | WorldTooBigException |
                     WorldAlreadyExistsException | IOException e) {
                throw new CompletionException(e);
            }
        }, SwmIoExecutor.ioExecutor().pool());
    }

    @Override
    public CompletableFuture<Void> migrateWorldAsync(String worldName, SlimeLoader currentLoader,
                                                     SlimeLoader newLoader) {
        Objects.requireNonNull(worldName);
        Objects.requireNonNull(currentLoader);
        Objects.requireNonNull(newLoader);
        return CompletableFuture.runAsync(() -> {
            try {
                migrateWorld(worldName, currentLoader, newLoader);
            } catch (WorldAlreadyExistsException | UnknownWorldException | IOException e) {
                throw new CompletionException(e);
            }
        }, SwmIoExecutor.ioExecutor().pool());
    }
```

Add import: `import java.util.concurrent.CompletionException;`

- [ ] **Step 4: Update SWPlugin to use async methods**

Replace `onDisable()` save loop (lines 97-106):

```java
    @Override
    public void onDisable() {
        for (SlimeWorld world : ASP.getLoadedWorlds()) {
            if (!world.isReadOnly()) {
                try {
                    ASP.saveWorld(world);
                } catch (Exception ex) {
                    getLogger().severe("Failed to save world " + world.getName() + ": " + ex.getMessage());
                }
            }
        }
        // Unload all worlds synchronously after saves complete
        for (SlimeWorld world : ASP.getLoadedWorlds()) {
            try {
                Bukkit.unloadWorld(world.getName(), false);
            } catch (Exception ignored) {}
        }

        if (ioExecutor != null) {
            ioExecutor.shutdown();
            ioExecutor = null;
        }
    }
```

For loadWorlds() and onEnable(), keep existing sync calls — they run on main thread during startup and don't benefit from async.

- [ ] **Step 5: Compile**

```bash
./gradlew :sourbycraft-server:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add sourbycraft-server/
git commit -m "feat(v6): SWM async API — CompletableFuture variants, VirtualThread I/O"
```

---

### Task 4: Minecraft Internal Virtual Thread Migration (Patches)

**Files:**
- Create/modify patches for chunk I/O, network, entity ticking

- [ ] **Step 1: Audit current thread usage in server patches**

```bash
grep -rn "newFixedThreadPool\|newCachedThreadPool\|newThread\|ForkJoinPool\|Executors\." patches/server/ | grep -v ".gradle"
```

Document all findings — these are candidates for virtual thread migration.

- [ ] **Step 2: Create patch for chunk I/O virtual threads**

Create new patch `patches/server/0020-feat-v6-chunk-io-virtual-threads.patch`:

Search for chunk I/O worker creation in the server source (typically in `net.minecraft.world.level.chunk.storage` or similar). Replace `Executors.newFixedThreadPool(...)` with `Executors.newVirtualThreadPerTaskExecutor()`.

- [ ] **Step 3: Create patch for network virtual threads**

Create new patch `patches/server/0021-feat-v6-network-virtual-threads.patch`:

Replace network worker threads with virtual threads. Target `net.minecraft.network.Connection` or Paper's networking patches.

- [ ] **Step 4: Create patch for entity ticking StructuredTaskScope**

Create new patch `patches/server/0022-feat-v6-entity-structured-concurrency.patch`:

Wrap per-dimension entity ticking in `StructuredTaskScope` (Java 25 preview). Requires `--enable-preview` JVM flag in launch scripts.

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    for (ServerLevel level : server.getAllLevels()) {
        scope.fork(() -> { level.tickEntities(); return null; });
    }
    scope.join();
    scope.throwIfFailed();
}
```

- [ ] **Step 5: Re-apply patches and compile**

```bash
./gradlew :sourbycraft-server:applyPatches :sourbycraft-server:compileJava
```

Expected: patches apply cleanly, BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add patches/server/
git commit -m "feat(v6): migrate chunk/network/entity threads to virtual threads"
```

---

### Task 5: Audit Pass 1-2 — Static Analysis + Concurrency

**Files:**
- Scan: all `sourbycraft-server/src/main/java/dev/iyanz/` + `patches/server/`

- [ ] **Step 1: Static analysis scan**

Run search patterns and document findings:

```bash
# Empty catch blocks
grep -rn "catch.*Exception.*)\s*{" sourbycraft-server/src/main/java/dev/iyanz/ --include="*.java"

# public static mutable fields
grep -rn "public static.*=" sourbycraft-server/src/main/java/dev/iyanz/ --include="*.java" | grep -v "final"

# Lock without try-finally
grep -rn "\.lock()" sourbycraft-server/src/main/java/dev/iyanz/ --include="*.java"
grep -rn "\.unlock()" sourbycraft-server/src/main/java/dev/iyanz/ --include="*.java"

# new Thread() without cleanup
grep -rn "new Thread" sourbycraft-server/src/main/java/dev/iyanz/ --include="*.java"
```

- [ ] **Step 2: Fix findings — empty catch blocks**

For each empty catch, add at minimum a LOGGER.warn with the exception. Example fix:

```java
// Before
} catch (IOException ignored) {}

// After
} catch (IOException e) {
    LOGGER.warn("I/O error while reading world data: {}", e.getMessage());
}
```

- [ ] **Step 3: Fix findings — public static mutable fields**

For config fields already moving to getString pattern (Task 1), no further action. For other public static mutable fields, assess if they need synchronization or should be instance fields.

- [ ] **Step 4: Concurrency hotspot audit**

Review these specific areas:
- `AdvancedSlimePaperImpl.loadedWorlds` — verify all access is thread-safe (ConcurrentHashMap already used — audit for compound operations)
- `SWPlugin.worldsToLoad` — HashMap not thread-safe but only used on main thread — verify
- `SourbyCraftConfig` static fields written during init — verify init is single-threaded

- [ ] **Step 5: Fix concurrency findings**

Document and fix each finding. At minimum:
- Add `volatile` to any static field read from multiple threads
- Replace `HashMap` with `ConcurrentHashMap` where multi-threaded access possible
- Add synchronized blocks for compound operations on ConcurrentHashMap

- [ ] **Step 6: Compile and commit**

```bash
./gradlew :sourbycraft-server:compileJava
git add sourbycraft-server/
git commit -m "fix(v6): audit pass 1-2 — static analysis + concurrency fixes"
```

---

### Task 6: Audit Pass 3-4 — Memory/Resource + Edge Cases

**Files:**
- SWM loaders: `swm/loader/FileLoader.java`, `MongoLoader.java`, `RedisLoader.java`, `MysqlLoader.java`

- [ ] **Step 1: Resource leak audit**

Check all Closeable resource usage:

```bash
grep -rn "new.*Stream\|new.*Reader\|new.*Writer\|new.*RegionFile\|new.*Connection" sourbycraft-server/src/main/java/dev/iyanz/swm/ --include="*.java"
```

For each, verify close in finally or try-with-resources.

- [ ] **Step 2: Fix resource leaks**

Example: ensure `RegionFile` always closed:

```java
// Before (potential leak on exception between new and close)
RegionFile region = new RegionFile(...);
// ... use region ...
region.close();

// After
RegionFile region = new RegionFile(...);
try {
    // ... use region ...
} finally {
    region.close();
}
```

- [ ] **Step 3: Connection pool audit**

Check `MongoLoader`, `RedisLoader`, `MysqlLoader` for:
- Connection creation lifecycle
- Pool configuration
- Close/shutdown in plugin disable

- [ ] **Step 4: Edge case fixes**

- World load failure → skip world, log error, continue (don't call `Bukkit.shutdown()`)
- Corrupted `.slime` file → specific error message, skip, don't crash
- Disk full → `saveWorld` IOException must propagate + admin alert via logger.severe
- Zero-chunk world → handle empty region directory (already handled in readVanillaWorld)

- [ ] **Step 5: Compile and commit**

```bash
./gradlew :sourbycraft-server:compileJava
git add sourbycraft-server/
git commit -m "fix(v6): audit pass 3-4 — resource leaks + edge case hardening"
```

---

### Task 7: Final Build Verification

- [ ] **Step 1: Clean build**

```bash
./gradlew clean :sourbycraft-server:jar :sourbycraft-swm-api:extractApi
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Verify version strings**

```bash
jar xf sourbycraft-server/build/libs/sourbycraft-server-v6-REL.jar META-INF/MANIFEST.MF
grep "Implementation-Version" META-INF/MANIFEST.MF
```

Expected: `Implementation-Version: v6-REL`

- [ ] **Step 3: Verify SWM API JAR**

```bash
jar tf sourbycraft-swm-api/build/libs/sourbycraft-swm-api-v6-REL.jar | grep ".class$" | wc -l
```

Expected: 20 (same count as v5).

- [ ] **Step 4: Run tests (if available)**

```bash
./gradlew :sourbycraft-server:test --no-build-cache 2>&1 | tail -10
```

- [ ] **Step 5: Git tag**

```bash
git tag v6-REL
git commit -m "release: v6-REL — async SWM, virtual threads, system audit"
```

---

## Implementation Order

Tasks must execute in this order:

1. **Task 1** — VirtualExecutor foundation (no dependencies)
2. **Task 2** — Version bump (independent, can run after Task 1)
3. **Task 3** — SWM async API (depends on Task 1 for VirtualExecutor)
4. **Task 4** — Minecraft patches (depends on Task 1, independent of Tasks 2-3)
5. **Task 5** — Audit pass 1-2 (can run anytime after Task 1-4 compile)
6. **Task 6** — Audit pass 3-4 (can run anytime)
7. **Task 7** — Final verification (after all tasks)
