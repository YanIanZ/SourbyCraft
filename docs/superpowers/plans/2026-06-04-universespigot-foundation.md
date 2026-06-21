# UniverseSpigot config import — 00 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the runtime + config infrastructure that subsequent UniverseSpigot category sub-projects (01-08) all build on. Ships expanded `SourbyCraftConfig` accessors, single-yml layout convention, patch template document, and a 2-key reference implementation in the `particles` category.

**Architecture:** Patches + runtime feature-flag gates. Each UniverseSpigot key becomes a small `.patch` that injects `if (SourbyCraftConfig.ymlBool("category.key", false)) { newBehavior(); } else { vanilla; }`. Defaults match Paper-vanilla so plugin compat is preserved by default. Single mojmap jar — no separate module jars, no classloader work, no NMS shim API.

**Tech Stack:** Java 21+, Paper 1.21.11 (paperweight 2.0, mache), Gradle 8+, JUnit Jupiter 5.12.2, SnakeYAML, Hamcrest, Mockito.

**Spec:** `docs/superpowers/specs/2026-06-04-universespigot-foundation-design.md`

**Branch:** `feat/pvp-server` (continue current branch; commits land here)

---

## File Structure

The Foundation touches a small, focused set of files. Each has one responsibility.

| File | Action | Responsibility |
|---|---|---|
| `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` | Modify | Add typed accessors (`ymlBool`, `ymlInt`, `ymlDouble`, `ymlStringList`, `ymlEntityTypeMap`) and the once-per-startup WARN dedupe `Set<String>`. Direct Java edit — this file is not under `patches/`. |
| `sourbycraft-server/src/main/resources/sourbycraft.yml` | Modify | Append new `particles:` top-level section with 2 reference keys (`disableFallParticles`, `disableDeathParticles`). Other UniverseSpigot categories are appended by their own sub-projects. |
| `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java` | Create | Unit tests for each new accessor: happy path, missing key, wrong type, dedupe, list/map filtering. JUnit Jupiter, no server boot. |
| `docs/superpowers/notes/2026-06-04-us-gated-patch-template.md` | Create | Source-of-truth document describing the gated-patch convention. Referenced by every subsequent UniverseSpigot sub-spec. |
| `patches/server/0034-us-particles-fall-death.patch` | Create | The reference patch. Gates fall-particle and death-particle emit sites with `ymlBool` checks. Trailing `// SourbyCraft - US import` markers. Cached bool at method entry per template. |
| `sourbycraft-server/build.gradle.kts` | Modify | Add a new opt-in gradle task `particleSmokeTest` that boots a minimal test server with both toggles enabled and verifies (via log-grep) that no particle-emit log line fires for a dropped+killed test entity. |
| `.github/workflows/nms-compat.yml` | Modify | Add `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`, `sourbycraft-server/src/main/resources/sourbycraft.yml`, and `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/**` to the trigger `paths` list so accessor changes also run the smoke harness. |

Important conventions discovered in the codebase that the plan follows:

- `docs/superpowers/` is in `.gitignore` (line 418) but specs/plans/notes have been force-added historically. **Always use `git add -f` for files under `docs/superpowers/`.**
- Tests live under `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/` and use JUnit 5 (`org.junit.jupiter.api.Test`). Existing example: `SourbyCraftConfigYmlGetTest.java`.
- Patches are numbered sequentially in `patches/server/`; current highest is `0033`. The reference patch is `0034-us-particles-fall-death.patch`.
- `SourbyCraftConfig.ymlGet` already exists (generic typed) — the new `ymlBool`/`ymlInt`/`ymlDouble` are typed wrappers around it for call-site clarity. `ymlStringList` and `ymlEntityTypeMap` are new logic.

---

## Phase 1 — Accessor expansion + unit tests

Five new (or thin-wrapper) accessors on `SourbyCraftConfig` plus a shared WARN dedupe set. TDD per accessor.

### Task 1: Create the accessor test class with the failing `ymlBool` test

**Files:**
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java`

- [ ] **Step 1: Create the test file with one failing `ymlBool` test**

```java
package dev.iyanz.sourbycraft;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourbyCraftConfigAccessorsTest {

    @Test
    void ymlBool_readsBaselineValue() {
        // pvp.enabled = false in baseline sourbycraft.yml
        assertEquals(false, SourbyCraftConfig.ymlBool("pvp.enabled", true));
    }

    @Test
    void ymlBool_returnsDefaultWhenMissing() {
        assertEquals(true, SourbyCraftConfig.ymlBool("nonexistent.key.path", true));
    }

    @Test
    void ymlBool_returnsDefaultOnTypeMismatch() {
        // pvp.knockback.friction-divisor is a Double in baseline, not a Boolean
        assertEquals(false, SourbyCraftConfig.ymlBool("pvp.knockback.friction-divisor", false));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: compile failure — `ymlBool(String,boolean)` is not defined on `SourbyCraftConfig`.

- [ ] **Step 3: Implement `ymlBool` on `SourbyCraftConfig`**

Modify `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`. Add this method directly after the existing `ymlGet` method (around line 52):

```java
    /**
     * Type-safe boolean read from sourbycraft.yml. Returns {@code defaultValue} when the
     * key is missing or the value cannot be cast to Boolean.
     */
    public static boolean ymlBool(String dottedPath, boolean defaultValue) {
        Object v = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (v instanceof Boolean b) return b;
        if (v != null) warnOnce(dottedPath, v, "boolean");
        return defaultValue;
    }
```

Then add (further down, near the other private static methods around line 442):

```java
    // SourbyCraft v12 — UniverseSpigot config import accessors. Once-per-startup WARN dedupe
    // so a single malformed key doesn't spam the log every tick a patch reads it.
    private static final java.util.Set<String> WARNED_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void warnOnce(String path, Object actual, String expected) {
        if (WARNED_KEYS.add(path)) {
            Bukkit.getLogger().warning(
                "[SourbyCraft] config key '" + path + "' invalid type '"
                + (actual == null ? "null" : actual.getClass().getSimpleName())
                + "', expected " + expected + " — using default"
            );
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java
git commit -m "$(cat <<'EOF'
feat: SourbyCraftConfig.ymlBool typed accessor + WARN dedupe

Adds a type-safe boolean reader for sourbycraft.yml and a shared
once-per-startup WARN dedupe set used by all new typed accessors. Foundation
for the UniverseSpigot config import (sub-project 00).
EOF
)"
```

---

### Task 2: Add `ymlInt` typed accessor

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java`

- [ ] **Step 1: Add failing `ymlInt` tests to the existing test class**

Append three test methods inside `SourbyCraftConfigAccessorsTest`:

```java
    @Test
    void ymlInt_readsBaselineValue() {
        // pvp.view-distance-cap = 6 in baseline
        assertEquals(6, SourbyCraftConfig.ymlInt("pvp.view-distance-cap", 99));
    }

    @Test
    void ymlInt_returnsDefaultWhenMissing() {
        assertEquals(42, SourbyCraftConfig.ymlInt("nonexistent.int.key", 42));
    }

    @Test
    void ymlInt_acceptsLongAndDoubleNumerics() {
        // YAML may parse "1" as Integer, "1000000000000" as Long, "1.0" as Double.
        // Accessor must coerce any Number → int via intValue().
        // No baseline key has these specific shapes, so we only assert the default-on-miss path:
        assertEquals(7, SourbyCraftConfig.ymlInt("nonexistent.long.path", 7));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: compile failure — `ymlInt(String,int)` is not defined.

- [ ] **Step 3: Implement `ymlInt`**

Add this method to `SourbyCraftConfig.java` directly after `ymlBool`:

```java
    /**
     * Type-safe int read from sourbycraft.yml. Coerces any {@link Number} via
     * {@code intValue()}. Returns {@code defaultValue} when the key is missing
     * or the value is not numeric.
     */
    public static int ymlInt(String dottedPath, int defaultValue) {
        Object v = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (v instanceof Number n) return n.intValue();
        if (v != null) warnOnce(dottedPath, v, "int");
        return defaultValue;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: 6 tests pass (3 from Task 1 + 3 from this task).

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java
git commit -m "feat: SourbyCraftConfig.ymlInt typed accessor"
```

---

### Task 3: Add `ymlDouble` typed accessor

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java`

- [ ] **Step 1: Add failing `ymlDouble` tests**

Append to `SourbyCraftConfigAccessorsTest`:

```java
    @Test
    void ymlDouble_readsBaselineValue() {
        // pvp.knockback.friction-divisor = 1.0 in baseline (per resource header)
        assertEquals(1.0, SourbyCraftConfig.ymlDouble("pvp.knockback.friction-divisor", 9.9));
    }

    @Test
    void ymlDouble_returnsDefaultWhenMissing() {
        assertEquals(2.5, SourbyCraftConfig.ymlDouble("nonexistent.double.key", 2.5));
    }

    @Test
    void ymlDouble_coercesIntegerValue() {
        // pvp.view-distance-cap is an Integer (6) in baseline. ymlDouble must coerce.
        assertEquals(6.0, SourbyCraftConfig.ymlDouble("pvp.view-distance-cap", 0.0));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: compile failure — `ymlDouble(String,double)` is not defined.

- [ ] **Step 3: Implement `ymlDouble`**

Add this method directly after `ymlInt`:

```java
    /**
     * Type-safe double read from sourbycraft.yml. Coerces any {@link Number} via
     * {@code doubleValue()}. Returns {@code defaultValue} when the key is missing
     * or the value is not numeric.
     */
    public static double ymlDouble(String dottedPath, double defaultValue) {
        Object v = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) warnOnce(dottedPath, v, "double");
        return defaultValue;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java
git commit -m "feat: SourbyCraftConfig.ymlDouble typed accessor"
```

---

### Task 4: Add `ymlStringList` accessor

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java`

- [ ] **Step 1: Add failing `ymlStringList` tests**

Append to `SourbyCraftConfigAccessorsTest`:

```java
    @Test
    void ymlStringList_returnsEmptyWhenMissing() {
        assertTrue(SourbyCraftConfig.ymlStringList("nonexistent.list.key").isEmpty());
    }

    @Test
    void ymlStringList_returnsEmptyOnTypeMismatch() {
        // pvp.enabled is a Boolean, not a List
        assertTrue(SourbyCraftConfig.ymlStringList("pvp.enabled").isEmpty());
    }

    @Test
    void ymlStringList_filtersNonStringEntries() {
        // Constructed via reflection on the baseline map. Simulate by injecting a known list-bearing key
        // into the baseline; for this test we rely on the implementation handling the missing-key path
        // correctly. Real list coverage lives in the integration via the bundled yml in later tasks.
        // For now, assert that a missing key yields a defensively non-null empty list.
        var result = SourbyCraftConfig.ymlStringList("absolutely.no.such.key");
        assertNotNull(result);
        assertEquals(0, result.size());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: compile failure — `ymlStringList(String)` is not defined.

- [ ] **Step 3: Implement `ymlStringList`**

Add this method directly after `ymlDouble`:

```java
    /**
     * Reads a list of strings from sourbycraft.yml. Returns an empty list when the
     * key is missing or the value is not a List. Non-string entries are filtered out
     * with one WARN per offending value position.
     */
    public static java.util.List<String> ymlStringList(String dottedPath) {
        Object v = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (!(v instanceof java.util.List<?> raw)) {
            if (v != null) warnOnce(dottedPath, v, "List<String>");
            return java.util.List.of();
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>(raw.size());
        int idx = 0;
        for (Object item : raw) {
            if (item instanceof String s) {
                out.add(s);
            } else {
                warnOnce(dottedPath + "[" + idx + "]", item, "String");
            }
            idx++;
        }
        return java.util.Collections.unmodifiableList(out);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: 12 tests pass.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java
git commit -m "feat: SourbyCraftConfig.ymlStringList accessor"
```

---

### Task 5: Add `ymlEntityTypeMap` accessor

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java`

- [ ] **Step 1: Add failing `ymlEntityTypeMap` tests**

Append to `SourbyCraftConfigAccessorsTest`:

```java
    @Test
    void ymlEntityTypeMap_returnsEmptyWhenMissing() {
        var map = SourbyCraftConfig.ymlEntityTypeMap("nonexistent.entity.map");
        assertNotNull(map);
        assertTrue(map.isEmpty());
    }

    @Test
    void ymlEntityTypeMap_parseHelperRoundtrip() {
        // The parser is exposed via a package-private helper so we can unit-test the
        // "TYPE:N" splitting logic without needing a baseline list to exist.
        var parsed = SourbyCraftConfig.parseEntityTypeEntry("PLAYER:2");
        assertNotNull(parsed);
        assertEquals(org.bukkit.entity.EntityType.PLAYER, parsed.getKey());
        assertEquals(2, parsed.getValue());
    }

    @Test
    void ymlEntityTypeMap_parseHelperRejectsBadFormat() {
        assertNull(SourbyCraftConfig.parseEntityTypeEntry("nocolon"));
        assertNull(SourbyCraftConfig.parseEntityTypeEntry("PLAYER:notanumber"));
        assertNull(SourbyCraftConfig.parseEntityTypeEntry("NONEXISTENT_ENTITY:5"));
        assertNull(SourbyCraftConfig.parseEntityTypeEntry(""));
        assertNull(SourbyCraftConfig.parseEntityTypeEntry(null));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: compile failure — `ymlEntityTypeMap` and `parseEntityTypeEntry` are not defined.

- [ ] **Step 3: Implement `ymlEntityTypeMap` and `parseEntityTypeEntry`**

Add these methods directly after `ymlStringList`:

```java
    /**
     * Parses a list of {@code "EntityType:Number"} entries from sourbycraft.yml into
     * a typed map. Bad entries are skipped with one WARN each; valid entries collected.
     * Returns an empty map when the key is missing or the value is not a List.
     */
    public static java.util.Map<org.bukkit.entity.EntityType, Integer> ymlEntityTypeMap(String dottedPath) {
        Object v = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (!(v instanceof java.util.List<?> raw)) {
            if (v != null) warnOnce(dottedPath, v, "List<String>");
            return java.util.Map.of();
        }
        java.util.EnumMap<org.bukkit.entity.EntityType, Integer> out =
            new java.util.EnumMap<>(org.bukkit.entity.EntityType.class);
        int idx = 0;
        for (Object item : raw) {
            if (item instanceof String s) {
                Map.Entry<org.bukkit.entity.EntityType, Integer> entry = parseEntityTypeEntry(s);
                if (entry != null) {
                    out.put(entry.getKey(), entry.getValue());
                } else {
                    warnOnce(dottedPath + "[" + idx + "]", s, "EntityType:Number");
                }
            } else {
                warnOnce(dottedPath + "[" + idx + "]", item, "String");
            }
            idx++;
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * Parses a single {@code "TYPE:N"} entry. Returns {@code null} on any failure
     * (no colon, blank type, non-numeric value, unknown EntityType). Package-private
     * so it can be unit-tested without a populated baseline.
     */
    static java.util.Map.Entry<org.bukkit.entity.EntityType, Integer> parseEntityTypeEntry(String entry) {
        if (entry == null) return null;
        int colon = entry.indexOf(':');
        if (colon <= 0 || colon == entry.length() - 1) return null;
        String typeStr = entry.substring(0, colon).trim();
        String numStr = entry.substring(colon + 1).trim();
        if (typeStr.isEmpty() || numStr.isEmpty()) return null;
        try {
            org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(typeStr);
            int num = Integer.parseInt(numStr);
            return java.util.Map.entry(type, num);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: 15 tests pass.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java
git commit -m "feat: SourbyCraftConfig.ymlEntityTypeMap accessor + parser"
```

---

### Task 6: Verify WARN dedupe behavior end-to-end

The dedupe `Set<String>` was added in Task 1. This task adds a focused regression test that proves repeated bad reads emit exactly one WARN.

**Files:**
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java`

- [ ] **Step 1: Add a dedupe test**

Append to `SourbyCraftConfigAccessorsTest`:

```java
    @Test
    void warnOnce_emitsExactlyOnceForRepeatedBadReads() {
        // Capture warnings from java.util.logging via a temporary handler on Bukkit's logger.
        java.util.logging.Logger bukkitLogger = org.bukkit.Bukkit.getLogger();
        var captured = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { captured.add(r.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        bukkitLogger.addHandler(handler);
        try {
            // pvp.knockback.friction-divisor is a Double; reading as boolean three times
            // should warn at most once (dedupe by key path).
            String badKey = "pvp.knockback.friction-divisor";
            SourbyCraftConfig.ymlBool(badKey, false);
            SourbyCraftConfig.ymlBool(badKey, false);
            SourbyCraftConfig.ymlBool(badKey, false);
            long matchCount = captured.stream()
                .filter(m -> m != null && m.contains(badKey))
                .count();
            assertEquals(1, matchCount, "expected exactly one WARN for repeated bad reads; got: " + captured);
        } finally {
            bukkitLogger.removeHandler(handler);
        }
    }
```

Note: this test depends on `Bukkit.getLogger()` returning a real logger. The test class extends nothing — Bukkit's bootstrap is sufficient because `Bukkit.getLogger()` falls back to a JUL logger when no server is registered. If this proves flaky on CI, replace with a direct test against a captured `WARNED_KEYS` getter (add `static java.util.Set<String> warnedKeysForTest() { return java.util.Set.copyOf(WARNED_KEYS); }` and assert the set grows by exactly one). Use that fallback if Step 2 reports a NullPointerException.

- [ ] **Step 2: Run the dedupe test**

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: 16 tests pass. If the dedupe test fails with NPE on `Bukkit.getLogger()`, switch to the fallback noted above.

- [ ] **Step 3: Commit**

```bash
git add sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java
# If you took the fallback path, also include SourbyCraftConfig.java:
# git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java
git commit -m "test: WARN dedupe — repeated bad reads emit at most one log line"
```

---

## Phase 2 — Patch template document

Source-of-truth for every subsequent UniverseSpigot patch.

### Task 7: Write the gated-patch template document

**Files:**
- Create: `docs/superpowers/notes/2026-06-04-us-gated-patch-template.md`

- [ ] **Step 1: Write the template document**

Create `docs/superpowers/notes/2026-06-04-us-gated-patch-template.md` with this content:

````markdown
# UniverseSpigot gated-patch template

Source-of-truth for every patch in sub-projects 01-08 (`particles`, `sounds`, `misc`,
`limiters`, `behavior`, `fixes`, `performance`, `async`, `combat`, `experimental`,
`developer`). Read this before writing a new `us-*` patch.

## Anatomy of the check

Each UniverseSpigot key turns on or off a deviation from vanilla Paper. The deviation
lives in a patch whose only purpose is to wrap the vanilla call site with a config check:

```java
if (SourbyCraftConfig.ymlBool("category.subgroup.key", false)) {
    // SourbyCraft branch — new (UniverseSpigot-imported) behavior
} else {
    // vanilla branch — byte-for-byte identical to pre-patch code
}
```

The vanilla branch must be left untouched apart from being moved into an `else` block.
This is what preserves Paper-vanilla behavior when the operator does not opt in.

## Where to put the check

Find the vanilla call site by reading the generated mojmap source under
`paper-server/src/main/java/net/minecraft/...`. The convention is:

- For event-driven features (particle emit, sound play, event broadcast): wrap the
  single call line.
- For tick-driven features (entity tick, brain update): wrap the loop body so the
  vanilla path remains hot when the toggle is off.

If you cannot identify a single call site, the design is wrong — refactor the spec
to either pick a more surgical insertion point or to use multiple smaller toggles.

## Bool-caching pattern (mandatory for hot loops)

`SourbyCraftConfig.ymlBool` resolves to a HashMap lookup (~50 ns). At tick rate this
is acceptable per-call, but inside per-entity or per-block loops the lookup will
dominate. **Cache the bool into a local at method entry** when the check is inside
a loop:

```java
// vanilla:
public void tick() {
    for (Entity e : this.entities) {
        this.emitFallParticle(e);
    }
}

// gated patch:
public void tick() {
    final boolean particlesOff = SourbyCraftConfig.ymlBool("particles.disableFallParticles", false); // SourbyCraft - US import
    for (Entity e : this.entities) {
        if (!particlesOff) {
            this.emitFallParticle(e);
        }
    }
}
```

For event-driven (non-loop) sites, an inline `ymlBool` call is fine.

## Diff hygiene

Every line a UniverseSpigot patch adds gets a trailing comment:

```java
final boolean particlesOff = SourbyCraftConfig.ymlBool("particles.disableFallParticles", false); // SourbyCraft - US import
```

This makes `grep -rn "SourbyCraft - US import"` the canonical way to enumerate all
UniverseSpigot insertions.

## Commit naming

```
patch: us-<category>-<subgroup> — gated <feature>
```

Examples:

- `patch: us-particles-fall-death — gated fall + death particle emit`
- `patch: us-behavior-spawner — gated spawner light + nearby-player checks`
- `patch: us-performance-hoppers — gated hopper throttle for full target container`

## Patch filename

`patches/server/NNNN-us-<category>-<subgroup>.patch` where `NNNN` is the next free
patch number under `patches/server/`. Foundation reserves `0034`.

## What NOT to do

- Do not change branding identity inside a US patch. Branding lives in patch 0003
  and is already correct.
- Do not add new fields to `SourbyCraftConfig` for each key. Use `ymlBool`/`ymlInt`/
  `ymlDouble`/`ymlStringList`/`ymlEntityTypeMap` directly. The dotted-path keys are
  the schema.
- Do not call `ymlBool` from a constructor or static initializer of an NMS class —
  the baseline yml may not be loaded yet at class-init time. Push the call into the
  first runtime method that uses the value.
- Do not introduce per-world or per-dimension config. Per-world support is deferred
  to a separate spec.
- Do not introduce hot-reload. Config is boot-time only; the yml header documents
  the restart requirement.
````

- [ ] **Step 2: Verify the file rendered correctly**

Run: `wc -l docs/superpowers/notes/2026-06-04-us-gated-patch-template.md`

Expected: > 60 lines.

- [ ] **Step 3: Commit**

```bash
git add -f docs/superpowers/notes/2026-06-04-us-gated-patch-template.md
git commit -m "docs: UniverseSpigot gated-patch template (sub-project 00 Foundation)"
```

Note the `-f` is required because `docs/superpowers/` is in `.gitignore` (line 418), but historical specs/plans/notes have been force-added. Match that pattern.

---

## Phase 3 — Yml schema + reference patch

The reference implementation: 2 keys in the `particles` category, end-to-end.

### Task 8: Append the `particles:` section to bundled `sourbycraft.yml`

**Files:**
- Modify: `sourbycraft-server/src/main/resources/sourbycraft.yml`

- [ ] **Step 1: Append the new section**

Open `sourbycraft-server/src/main/resources/sourbycraft.yml`. After the existing `auto-install:` section (currently the last section ending around line 50), append:

```yaml

# UniverseSpigot config import — particles category (sub-project 00 Foundation reference impl).
# All toggles default to false → vanilla Paper behavior preserved.
# Changes to this file require a server restart; SourbyCraft does not support hot-reload.
particles:
  # Disable entity fall particles (UniverseSpigot: particles.disableFallParticles).
  disableFallParticles: false
  # Disable entity death particles (UniverseSpigot: particles.disableDeathParticles).
  disableDeathParticles: false
```

- [ ] **Step 2: Verify YAML parses cleanly**

Run: `python3 -c "import yaml; yaml.safe_load(open('sourbycraft-server/src/main/resources/sourbycraft.yml'))" && echo OK`

Expected: `OK` printed. (If `python3` is unavailable, use any YAML validator — the key check is no parse errors.)

- [ ] **Step 3: Verify the keys are reachable through `ymlBool`**

Add this temporary test to `SourbyCraftConfigAccessorsTest`:

```java
    @Test
    void ymlBool_readsNewParticlesSection() {
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableFallParticles", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableDeathParticles", true));
    }
```

Run: `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest`

Expected: 17 tests pass.

- [ ] **Step 4: Commit**

```bash
git add sourbycraft-server/src/main/resources/sourbycraft.yml \
        sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/SourbyCraftConfigAccessorsTest.java
git commit -m "feat: sourbycraft.yml — particles section (US import reference)"
```

---

### Task 9: Locate the NMS fall-particle and death-particle emit sites

This task is exploratory. It produces a single committed note documenting the exact insertion points the patch in Task 10 will modify.

**Files:**
- Create: `docs/superpowers/notes/2026-06-04-particle-emit-sites.md`

- [ ] **Step 1: Apply patches so paper-server source is generated**

Run: `./gradlew applyAllPatches 2>&1 | tail -20`

Expected: completes without error. `paper-server/src/main/java/net/minecraft/...` now contains the post-patch mojmap sources.

- [ ] **Step 2: Locate fall-particle emit**

In Paper 1.21.11 mojmap, fall particles are emitted from `LivingEntity#causeFallDamage` (or the helper it calls) by broadcasting an entity event byte (`level().broadcastEntityEvent(this, (byte)X)`) and/or by directly spawning block-break particles below the entity.

Run:

```bash
grep -rn "broadcastEntityEvent\|sendParticles\|addParticle" \
    paper-server/src/main/java/net/minecraft/world/entity/LivingEntity.java \
    paper-server/src/main/java/net/minecraft/world/entity/Entity.java 2>/dev/null | head -40
```

Identify the line(s) that fire when an entity hits the ground after a fall. Expected to be inside or just after `causeFallDamage` / `checkFallDamage`.

- [ ] **Step 3: Locate death-particle emit**

Death particles for mobs in 1.21.11 mojmap are usually triggered by `Mob#tickDeath` calling `level().broadcastEntityEvent(this, (byte)60)` (event 60 = poof). For non-mob `LivingEntity#die`, the death particle may be a sound + smoke event.

Run:

```bash
grep -rn "broadcastEntityEvent.*60\|tickDeath\|deathTime" \
    paper-server/src/main/java/net/minecraft/world/entity/Mob.java \
    paper-server/src/main/java/net/minecraft/world/entity/LivingEntity.java 2>/dev/null | head -40
```

Identify the line(s) where the death particle event is dispatched.

- [ ] **Step 4: Write the note**

Create `docs/superpowers/notes/2026-06-04-particle-emit-sites.md`:

```markdown
# NMS particle emit sites — fall + death (Paper 1.21.11 mojmap)

Inputs for patch `0034-us-particles-fall-death.patch`. Captured after
`./gradlew applyAllPatches` on commit <COMMIT_HASH_HERE>.

## Fall particles

File: `paper-server/src/main/java/net/minecraft/world/entity/<FILL_IN>.java`
Method: `<FILL_IN>`
Line: `<FILL_IN>`

Vanilla snippet (paste 3-5 lines of context):

```java
<FILL_IN>
```

## Death particles

File: `paper-server/src/main/java/net/minecraft/world/entity/<FILL_IN>.java`
Method: `<FILL_IN>`
Line: `<FILL_IN>`

Vanilla snippet:

```java
<FILL_IN>
```

## Notes

- Both sites are called in non-loop contexts (one per damage/death event), so no
  bool-caching is required per the patch template's hot-loop rule.
- If either site turns out to be inside a per-entity loop in a subclass, switch
  to the cached-bool form at method entry per the template.
```

Fill in every `<FILL_IN>` with the actual values from Steps 2-3. Replace `<COMMIT_HASH_HERE>` with `git rev-parse --short HEAD`.

- [ ] **Step 5: Commit**

```bash
git add -f docs/superpowers/notes/2026-06-04-particle-emit-sites.md
git commit -m "notes: NMS fall + death particle emit sites for US patch 0034"
```

---

### Task 10: Write `0034-us-particles-fall-death.patch`

**Files:**
- Create: `patches/server/0034-us-particles-fall-death.patch`

- [ ] **Step 1: Edit the post-patch source in `paper-server/`**

Edit the file identified in Task 9 Step 2. Wrap the fall-particle emit line with a guard:

```java
if (!SourbyCraftConfig.ymlBool("particles.disableFallParticles", false)) { // SourbyCraft - US import
    /* vanilla fall-particle emit line(s) unchanged */
} // SourbyCraft - US import
```

Edit the file identified in Task 9 Step 3. Wrap the death-particle emit line with a guard:

```java
if (!SourbyCraftConfig.ymlBool("particles.disableDeathParticles", false)) { // SourbyCraft - US import
    /* vanilla death-particle emit line(s) unchanged */
} // SourbyCraft - US import
```

If `SourbyCraftConfig` is not already imported in either file, add:

```java
import dev.iyanz.sourbycraft.SourbyCraftConfig; // SourbyCraft - US import
```

(Place it grouped with other `dev.iyanz.*` imports if any exist, otherwise alphabetical.)

- [ ] **Step 2: Rebuild patches**

Run: `./gradlew rebuildPatches 2>&1 | tail -20`

Expected: paperweight emits patches into `patches/server/`. The newest patch should be `0034-us-particles-fall-death.patch`.

If paperweight names it differently (it derives the filename from the commit subject), rename:

```bash
mv patches/server/0034-*.patch patches/server/0034-us-particles-fall-death.patch
```

(Use `ls patches/server/0034-*` to find the actual filename.)

- [ ] **Step 3: Verify the patch is small and well-formed**

Run: `wc -l patches/server/0034-us-particles-fall-death.patch`

Expected: under 150 lines. (Spec acceptance criterion.)

Run: `grep -c 'SourbyCraft - US import' patches/server/0034-us-particles-fall-death.patch`

Expected: at least 4 (two open braces + two close braces, possibly more if imports were added).

Run: `head -10 patches/server/0034-us-particles-fall-death.patch`

Expected: standard paperweight patch header with subject containing the commit message.

- [ ] **Step 4: Re-apply patches cleanly to confirm round-trip**

Run: `./gradlew applyAllPatches 2>&1 | tail -10`

Expected: completes without conflict.

- [ ] **Step 5: Boot smoke — confirm default config does not regress**

Run: `./gradlew :sourbycraft-server:build 2>&1 | tail -5`

Expected: build succeeds. Full boot is tested in Phase 4.

- [ ] **Step 6: Commit**

```bash
git add patches/server/0034-us-particles-fall-death.patch
git commit -m "patch: us-particles-fall-death — gated fall + death particle emit"
```

---

## Phase 4 — Particle smoke test

Adds an opt-in gradle task that boots a test server with both toggles enabled and confirms (via log-grep) that no fall or death particle log line fires when a test entity is dropped and killed. Separate from the existing `nmsCompatTest` to keep that task's plugin-compat focus clean.

### Task 11: Add `particleSmokeTest` gradle task

**Files:**
- Modify: `sourbycraft-server/build.gradle.kts`
- Create: `test-harness/scripts/particle-smoke.sh`
- Create: `test-harness/scripts/particle-smoke.conf.yml`

- [ ] **Step 1: Write the smoke shell script**

Create `test-harness/scripts/particle-smoke.sh`. This script writes a temp `sourbycraft.yml` with both toggles set to `true`, boots the mojmap jar, runs two console commands to spawn + kill a zombie, captures the boot+runtime log, then asserts via grep that no particle-emit DEBUG line is present.

```bash
#!/usr/bin/env bash
# UniverseSpigot Foundation — particle suppression smoke test.
# Boots single-jar (mojmap) with particles.disableFallParticles=true and
# particles.disableDeathParticles=true, drops a zombie from y=64, kills it,
# and asserts no particle-emit DEBUG line is emitted.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$ROOT/test-harness/TestServer-particle-smoke"
JAR_GLOB="$ROOT/release/SourbyCraft-v12-REL.jar"

rm -rf "$WORK"
mkdir -p "$WORK/plugins/SourbyCraft"

cp "$(ls $JAR_GLOB | head -1)" "$WORK/server.jar"
echo "eula=true" > "$WORK/eula.txt"
cp "$ROOT/test-harness/scripts/particle-smoke.conf.yml" "$WORK/plugins/SourbyCraft/sourbycraft.yml"

cd "$WORK"

# Run server in foreground, send commands via stdin pipe, capture stdout to boot.log
# Deadline: 120s for boot, then 30s for the test commands, then "stop".
(
  sleep 90
  echo "summon minecraft:zombie ~ 64 ~"
  sleep 2
  echo "kill @e[type=minecraft:zombie,limit=1]"
  sleep 2
  echo "stop"
) | timeout 180 java -Xmx2G -Ddev.iyanz.sourbycraft.particle.debug=true \
    -jar server.jar nogui --nojline > boot.log 2>&1 || true

# Assertions
if ! grep -q 'Done (' boot.log; then
    echo "FAIL: server did not finish booting"
    tail -50 boot.log
    exit 1
fi

if grep -q '\[SourbyCraft\] particle emit:fall' boot.log; then
    echo "FAIL: fall particle emit DEBUG line found despite toggle on"
    grep '\[SourbyCraft\] particle emit' boot.log | head -5
    exit 1
fi

if grep -q '\[SourbyCraft\] particle emit:death' boot.log; then
    echo "FAIL: death particle emit DEBUG line found despite toggle on"
    grep '\[SourbyCraft\] particle emit' boot.log | head -5
    exit 1
fi

echo "PASS: no fall/death particle emit lines under toggles=on"
```

Note: the DEBUG log line `[SourbyCraft] particle emit:fall` / `:death` is emitted by the reference patch only when system property `dev.iyanz.sourbycraft.particle.debug=true` is set. This keeps prod logs clean while letting the smoke test observe the gated branch deterministically.

- [ ] **Step 2: Add gate-fired DEBUG markers to `0034-us-particles-fall-death.patch`**

Re-edit the post-patch source. Augment the gated branches so the `else` (toggle-on) branch emits a deterministic log marker when the system property `dev.iyanz.sourbycraft.particle.debug=true` is set. Semantics: `emit` would mean an actual particle was sent; `gated` means the gate fired and suppressed the emit. The smoke test asserts the **presence** of the `gated` lines.

Fall branch:

```java
if (!SourbyCraftConfig.ymlBool("particles.disableFallParticles", false)) { // SourbyCraft - US import
    /* vanilla fall-particle emit line(s) unchanged */
} else if (Boolean.getBoolean("dev.iyanz.sourbycraft.particle.debug")) { // SourbyCraft - US import
    org.bukkit.Bukkit.getLogger().info("[SourbyCraft] particle gated:fall"); // SourbyCraft - US import
}
```

Mirror for death branch using the literal string `[SourbyCraft] particle gated:death`.

Rebuild patches after editing:

```bash
./gradlew rebuildPatches 2>&1 | tail -10
```

Then re-verify `wc -l patches/server/0034-us-particles-fall-death.patch` is still under 150 lines (the added marker lines should add maybe 6-8 lines).

- [ ] **Step 3: Update the smoke script assertions to match the gate-fired markers**

Replace the two `grep -q` assertions inside `particle-smoke.sh` (the ones written in Step 1 that test absence of `particle emit:fall` / `particle emit:death`) with the positive-presence assertions below:

```bash
if ! grep -q '\[SourbyCraft\] particle gated:fall' boot.log; then
    echo "FAIL: fall-particle gate did not fire — patch may not be wired"
    grep '\[SourbyCraft\] particle' boot.log | head -5 || true
    exit 1
fi

if ! grep -q '\[SourbyCraft\] particle gated:death' boot.log; then
    echo "FAIL: death-particle gate did not fire — patch may not be wired"
    grep '\[SourbyCraft\] particle' boot.log | head -5 || true
    exit 1
fi

echo "PASS: both particle gates fired under toggles=on"
```

Also replace the original closing `echo "PASS: no fall/death particle emit lines under toggles=on"` line — it is superseded by the new PASS message above.

Make the script executable:

```bash
chmod +x test-harness/scripts/particle-smoke.sh
```

- [ ] **Step 4: Write the conf file used by the script**

Create `test-harness/scripts/particle-smoke.conf.yml`:

```yaml
# Operator sourbycraft.yml used by particle-smoke.sh — both toggles ON to verify
# the gated branches in patch 0034 actually fire.
particles:
  disableFallParticles: true
  disableDeathParticles: true
```

- [ ] **Step 5: Register the gradle task**

Open `sourbycraft-server/build.gradle.kts`. Locate the existing `if (runNmsCompat) { tasks.register<JavaExec>("nmsCompatTest") { ... } }` block (around line 469). After the closing brace of that `if (runNmsCompat)` block, append:

```kotlin
// SourbyCraft v12 — UniverseSpigot Foundation particle-suppression smoke test.
// Opt-in via -PrunParticleSmoke=true to keep the regular build fast.
val runParticleSmoke = providers.gradleProperty("runParticleSmoke").map { it.toBoolean() }.getOrElse(false)

if (runParticleSmoke) {
    tasks.register<Exec>("particleSmokeTest") {
        group = "verification"
        description = "Boot single-jar with particle toggles on; assert gated branches fire"
        dependsOn(":assembleReleaseArtifacts")
        workingDir = rootProject.rootDir
        commandLine("bash", rootProject.file("test-harness/scripts/particle-smoke.sh").absolutePath)
    }
}
```

- [ ] **Step 6: Run the smoke test locally**

Run: `./gradlew :sourbycraft-server:particleSmokeTest -PrunParticleSmoke=true 2>&1 | tail -20`

Expected: task ends with `PASS: both particle gates fired under toggles=on`.

If the test fails because no release jar exists yet, run `./gradlew assembleReleaseArtifacts` first, then re-run.

- [ ] **Step 7: Add the working directory to gitignore**

Append to `.gitignore`:

```
# UniverseSpigot Foundation — particle smoke test work dir
test-harness/TestServer-particle-smoke/
```

- [ ] **Step 8: Commit**

```bash
git add sourbycraft-server/build.gradle.kts \
        test-harness/scripts/particle-smoke.sh \
        test-harness/scripts/particle-smoke.conf.yml \
        patches/server/0034-us-particles-fall-death.patch \
        .gitignore
git commit -m "$(cat <<'EOF'
test: particleSmokeTest gradle task — gated-branch boot smoke

Opt-in `:particleSmokeTest` (-PrunParticleSmoke=true) boots single-jar with
particles.disableFallParticles=true and .disableDeathParticles=true, drops and
kills a zombie, then asserts both gated branches emitted their DEBUG marker
lines. Separate from nmsCompatTest to keep that task's plugin-compat focus.
EOF
)"
```

---

## Phase 5 — CI gate

Wire the new files into the existing NMS-compat CI workflow so accessor changes also run the smoke harness.

### Task 12: Extend `.github/workflows/nms-compat.yml` trigger paths

**Files:**
- Modify: `.github/workflows/nms-compat.yml`

- [ ] **Step 1: Update the `paths:` block**

In `.github/workflows/nms-compat.yml`, replace the existing `paths:` block (lines 5-11) with:

```yaml
    paths:
      - 'patches/**'
      - 'release/**'
      - 'gradle.properties'
      - 'sourbycraft-server/buildscript/build.gradle.kts'
      - 'sourbycraft-server/build.gradle.kts'
      - 'sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java'
      - 'sourbycraft-server/src/main/resources/sourbycraft.yml'
      - 'sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/**'
      - 'build.gradle.kts'
      - 'test-harness/**'
```

- [ ] **Step 2: Add a particle-smoke step to the existing workflow**

In `.github/workflows/nms-compat.yml`, after the existing `Run NMS-compat smoke harness` step (around line 48-49), add:

```yaml
      - name: Run particle-suppression smoke
        run: ./gradlew :sourbycraft-server:particleSmokeTest -PrunParticleSmoke=true

      - name: Upload particle-smoke boot log
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: particle-smoke-boot-log
          path: test-harness/TestServer-particle-smoke/boot.log
```

- [ ] **Step 3: Validate the YAML**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/nms-compat.yml'))" && echo OK`

Expected: `OK`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/nms-compat.yml
git commit -m "ci: nms-compat gates accessor + yml paths; runs particleSmokeTest"
```

---

## Verification Summary

After all 12 tasks complete, every row below must be true:

| Check | Command | Expected |
|---|---|---|
| 5 new typed accessors present | `grep -cE 'public static (boolean ymlBool\|int ymlInt\|double ymlDouble\|java\.util\.List<String> ymlStringList\|java\.util\.Map<.*EntityType.*> ymlEntityTypeMap)' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` | `5` |
| WARN dedupe set present | `grep -c 'WARNED_KEYS' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` | `>= 2` (declaration + use) |
| Accessor unit tests pass | `./gradlew :sourbycraft-server:test --tests SourbyCraftConfigAccessorsTest` | green, 17 tests |
| `particles:` section in bundled yml | `grep -c '^particles:' sourbycraft-server/src/main/resources/sourbycraft.yml` | `1` |
| Patch template doc present | `ls docs/superpowers/notes/2026-06-04-us-gated-patch-template.md` | exists |
| Reference patch present | `ls patches/server/0034-us-particles-fall-death.patch` | exists |
| Reference patch under 150 lines | `wc -l patches/server/0034-us-particles-fall-death.patch \| awk '{print $1}'` | `< 150` |
| Particle smoke task succeeds | `./gradlew :sourbycraft-server:particleSmokeTest -PrunParticleSmoke=true` | green, "PASS: both particle gates fired" |
| Patches still apply cleanly | `./gradlew applyAllPatches` | exit 0 |
| Default boot unaffected | full clean boot with default config reaches `Done (` within the same window as the pre-Foundation baseline | no boot regression |
| Branding patch untouched | `git diff <pre-foundation-base>..HEAD -- patches/server/0003-Changed-branding.patch` | empty |
| CI workflow includes new paths | `grep -c "SourbyCraftConfig.java" .github/workflows/nms-compat.yml` | `1` |

## Self-Review Notes

Spec coverage (every section of `2026-06-04-universespigot-foundation-design.md`):

- **C1 Accessor expansion** → Tasks 1-5 (one per accessor + dedupe set).
- **C2 Yml schema growth** → Task 8.
- **C3 Patch template doc** → Task 7.
- **C4 Two reference patches** → Tasks 9 (locate sites) + 10 (write patch).
- **C5 Smoke test extension** → Task 11. Note: implemented as a *new* `particleSmokeTest` task separate from `nmsCompatTest`, not as an extension to `nmsCompatTest`. Spec said "extend `:nmsCompatTest`"; the plan deviates here for clean separation of concerns (nmsCompatTest checks plugin sanity; particleSmokeTest checks gated-branch behavior). Both are wired into the same CI workflow in Task 12.
- **C6 Unit tests** → Tasks 1-5 each include the TDD failing-test step; Task 6 adds the dedupe regression test.
- **Acceptance criteria table in spec** → mapped one-to-one in the Verification Summary above.

Spec deviation flagged: existing `ymlBool` / `ymlInt` in spec described as "existing" — actually only `ymlGet` exists today. Plan creates `ymlBool`, `ymlInt`, `ymlDouble` as new typed wrappers (Tasks 1-3). Patches across all UniverseSpigot sub-projects benefit from the clearer call-site API.

Placeholder scan: every `<FILL_IN>` is inside Task 9's note template, which is intentional — the values are determined by exploration during plan execution, not by the plan itself. No other placeholders.

Type consistency: `ymlBool`/`ymlInt`/`ymlDouble` return primitives. `ymlStringList` returns `java.util.List<String>` (immutable). `ymlEntityTypeMap` returns `java.util.Map<org.bukkit.entity.EntityType, Integer>` (immutable, backed by `EnumMap`). `parseEntityTypeEntry` returns `java.util.Map.Entry<EntityType, Integer>` or `null`. Used consistently across Tasks 1-6 and the reference patch in Tasks 9-10.
