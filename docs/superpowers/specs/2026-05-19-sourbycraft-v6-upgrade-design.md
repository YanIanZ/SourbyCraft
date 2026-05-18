# SourbyCraft v6 — Async SWM + Virtual Threads + Audit

2026-05-19 | `feat: v6 upgrade — SWM async total, Java 25 Virtual Threads, system-wide audit`

## §G — Goal

Upgrade SourbyCraft from v5-REL to v6-REL dengan 3 pilar:
1. **SWM async total** — semua operasi world I/O async via CompletableFuture + virtual threads
2. **Virtual Threads** — ganti semua custom thread pool dengan Java 25 virtual threads
3. **Audit + fix** — systematic audit race condition, memory leak, thread safety, resource leak

---

## §1 — Version Bump v5 → v6

| File | Change |
|------|--------|
| `gradle.properties` | `version = v6-REL`, `releaseVersion = 6` |
| `SourbyCraftConfig.java:24` | `currentVersion = 6` |
| `SourbyCraftConfig.java:39` | default `swmVersion = "v6-REL"` |
| `SourbyCraftConfig.java:133` | New migration block: `version < 6` → upgrade `swmVersion` from `"v5-REL"` to `"v6-REL"` |
| `README.md` | Badge `v6-REL`, jar filenames, API dependency version |
| `sourbycraft-swm-api/build.gradle.kts:30` | Jar path `v5-REL` → `v6-REL` |
| `patches/server/0014` | Update `v5-REL` string references |
| `swm/installer/PluginInstaller.java:12` | Update version string jika ada |
| Git tag | `v6-REL` |

**Config migration path:**
```
config-version: 5 → 6
  swm.version: "v5-REL" → "v6-REL"
```

---

## §2 — SWM Async Total

### Current State
- `SwmIoExecutor` = manual `ThreadPoolExecutor` (fixed thread count)
- `readWorld()` synchronous, main-thread call path
- `loadWorld()` guarded `AsyncCatcher.catchOp` (main thread only)
- `saveWorld()` semi-async via `server.execute(Runnable)`
- `readVanillaWorld()` synchronous (newly implemented)

### Design

**Threading:** `SwmIoExecutor` diganti dengan `Executors.newVirtualThreadPerTaskExecutor()` — zero-config, unbounded virtual threads, auto-scale. Tidak perlu pool sizing.

**API — tambah async variants (backward-compatible, sync methods tetap):**

```java
public interface AdvancedSlimePaperAPI {
    // Sync (backward compat, delegate ke async + .join)
    SlimeWorld readWorld(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap props) throws ...;
    
    // Async (new)
    CompletableFuture<SlimeWorld> readWorldAsync(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap props);
    CompletableFuture<SlimeWorldInstance> loadWorldAsync(SlimeWorld world, boolean callEvent);
    CompletableFuture<Void> saveWorldAsync(SlimeWorld world);
    CompletableFuture<SlimeWorld> readVanillaWorldAsync(File worldDir, String worldName, @Nullable SlimeLoader loader);
    CompletableFuture<Void> migrateWorldAsync(String name, SlimeLoader from, SlimeLoader to);
}
```

**Internal flow `readWorldAsync`:**
```
VirtualThread:
  1. loader.readWorld(worldName)        → byte[]   (disk I/O async)
  2. SlimeWorldReaderRegistry.readWorld  → SlimeWorld (CPU deserialize)
  3. SimpleDataFixerConverter.apply      → SlimeWorld (CPU datafix)
  4. if changed: loader.saveWorld()      → void      (disk I/O async)
  5. return SlimeWorld
```

**Internal flow `loadWorldAsync`:**
```
CompletableFuture.supplyAsync(VT):
  1. readWorld() jika belum cached       → SlimeWorld  (I/O + CPU)
  2. CompletableFuture + server.execute() → bridge ke main thread (NMS load)
  3. Call events, register world
  4. return SlimeWorldInstance
```

**Backpressure:** `StructuredTaskScope` (Java 25) untuk bounded concurrency per world name — maks 1 save concurrent per world.

**`AdvancedSlimePaperImpl` changes:**
- Semua sync methods → delegate ke async + `.join()` (MCP — migration compatible path)
- Sync methods tetap available untuk backward compatibility

**File changes:**
- `swm/api/AdvancedSlimePaperAPI.java` — tambah async method signatures
- `swm/server/AdvancedSlimePaperImpl.java` — implement async variants
- `swm/server/SwmIoExecutor.java` — rewrite with virtual threads
- `swm/plugin/SWPlugin.java` — update call sites ke async methods

---

## §3 — Virtual Threads Performance Migration

### Current State
- `AsyncExecutor.initPool(asyncThreads)` — fixed thread pool
- `SourbyCraftConfig.asyncThreads = 2` — hardcoded size
- `ForkJoinPool` used in some parallel operations
- Minecraft internal pools: networking, chunk I/O, entity ticking

### Design

**Prinsip:** All custom thread pools replaced by `Executors.newVirtualThreadPerTaskExecutor()`. Virtual threads are cheap (~1KB stack), zero pool sizing needed.

**Phase 1 — SourbyCraft pools:**

| Existing | Migration |
|----------|-----------|
| `AsyncExecutor` (fixed `asyncThreads`) | `VirtualExecutor` — `newVirtualThreadPerTaskExecutor()` |
| `SwmIoExecutor` (§2) | Same `VirtualExecutor` singleton |
| `ForkJoinPool.commonPool()` calls | `StructuredTaskScope` (Java 25 preview) |

**Phase 2 — Minecraft internal (paperweight patches):**
- Chunk I/O workers → virtual threads
- Network encoder/decoder → virtual threads + `StructuredTaskScope` per connection
- Entity ticking → `StructuredTaskScope` per dimension

**Config changes (`sourbycraft.yml`):**
```yaml
performance:
  virtual-threads: true           # default on
  structured-concurrency: true    # Java 25 StructuredTaskScope
  max-platform-threads: 4         # blocking native calls (file I/O fallback)
```

**Removed config fields:**
- `asyncThreads` — irrelevant with virtual threads
- `dimensionThreads` — replaced by structured concurrency per dimension

**`AsyncExecutor` → `VirtualExecutor`:**
```java
public final class VirtualExecutor {
    private static final ExecutorService VT = Executors.newVirtualThreadPerTaskExecutor();
    
    public static void run(Runnable task) { VT.submit(task); }
    public static <T> CompletableFuture<T> supply(Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        VT.submit(() -> { try { f.complete(task.call()); } catch (Throwable t) { f.completeExceptionally(t); } });
        return f;
    }
    public static ExecutorService executor() { return VT; }
}
```

**Files changed:**
- `SourbyCraftConfig.java` — hapus `asyncThreads`, `dimensionThreads`; tambah `virtualThreads`, `structuredConcurrency`, `maxPlatformThreads`
- `AsyncExecutor.java` (Pufferfish util) — rewrite → `VirtualExecutor`
- `sourbycraft-server/.../SourbyCraftConfig.java:29-31` — remove old fields
- Chunk system patches — ganti worker threads dengan virtual threads
- Network patches — ganti encoder/decoder threads

---

## §4 — Audit + Fix All Known Issues

### Approach: 4-pass systematic audit

**Pass 1 — Static analysis:**
- `synchronized` blocks → deadlock potential
- `public static` mutable fields → race condition candidates
- `catch (Exception e) {}` empty → swallowed errors
- `new Thread()` tanpa cleanup → resource leaks
- `Lock` / `ReentrantLock` tanpa `try-finally` → deadlock risk

**Pass 2 — Concurrency hotspots:**
- `SWM loadedWorlds` ConcurrentHashMap access paths
- `SourbyCraftConfig` static mutable fields (no synchronization)
- Chunk loading pipeline multi-threaded access
- Entity tracker thread safety

**Pass 3 — Memory & resource:**
- `RegionFile` close in finally (all paths)
- `DataInputStream` / `FileInputStream` close
- `MongoLoader` / `RedisLoader` connection pool lifecycle
- Chunk data reference retained after unload detection

**Pass 4 — Edge cases:**
- World load failure → graceful degradation, not bulk shutdown
- Corrupted `.slime` file → skip world + log, don't crash
- Disk full → `saveWorld` IOException detection + admin alert
- Zero-chunk worlds → handle empty region directory

**Output format per finding:**
```
File:line | Category | Severity | Description | Fix
```

**Priority:**
- Critical (C): crash, data loss, item dupe
- High (H): memory leak, thread leak, deadlock
- Medium (M): swallowed error, missing close
- Low (L): code smell, naming, unused import

### Scope
- All `sourbycraft-server/src/main/java/dev/iyanz/` source
- SWM module: `swm/core/`, `swm/loader/`, `swm/plugin/`, `swm/server/`
- Config: `SourbyCraftConfig.java`
- Commands: `command/*.java`
- Patches: `patches/server/*.patch` (audit code blocks added by patches)

### Deliverables
- Audit report: list of all findings with severity + fix
- Fix commits: one commit per severity group (critical first)
- Prevention: invariants added to prevent recurrence (§V if spec already exists)

---

## §V — Invariants

1. **V-ASYNC**: Semua operasi SWM I/O tersedia sebagai async (`*Async` methods returning `CompletableFuture`). Sync methods tetap ada sebagai MCP backward-compat delegates.

2. **V-VT**: Semua custom thread pool (`AsyncExecutor`, `SwmIoExecutor`, `ForkJoinPool`) diganti dengan `VirtualExecutor` (virtual threads). `asyncThreads` config field dihapus.

3. **V-CLOSE**: Semua `Closeable` resources (RegionFile, DataInputStream, FileInputStream) di-close dalam `finally` atau try-with-resources — verified by Pass 3 audit.

4. **V-CONFIG**: `SourbyCraftConfig` tidak boleh ada `public static` mutable field yang ditulis dari luar `init()`. Semua config read-only setelah init.

5. **V-ERROR**: Semua catch-block harus log atau re-throw. Empty catch `catch (Exception e) {}` tidak diizinkan — verified by Pass 1 audit.
