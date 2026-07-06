# CS1+PG1 Config Registry + Perf-Key Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace SourbyCraft's dual config system (classpath `ymlGet` + operator YamlConfiguration) with one typed knob registry that renders a fully commented operator `sourbycraft.yml`, and formalize dead-key status (ACTIVE/SUPERSEDED/RESERVED).

**Architecture:** Generalize the existing `PerfKnob`/`KnobRegistry` pattern: knobs gain metadata (comment, status, aliases), new typed knobs cover all ~70 keys, a new `ConfigRegistry` loads operator values over Java-code defaults, and a `YmlWriter` renders the operator file deterministically with comments. Existing public statics stay as a bridge — zero NMS patch churn.

**Tech Stack:** Java 21+, snakeyaml (SafeConstructor, already shipped), Gradle (paperweight fork).

**Spec:** `docs/superpowers/specs/2026-07-06-config-registry-perf-key-audit-design.md`

## Global Constraints

- **NO new JUnit tests** (house rule). Verification per task = `./gradlew :sourbycraft-server:compileJava`. Existing tests (`SourbyCraftConfigAccessorsTest`, `SourbyCraftConfigYmlGetTest`) must keep compiling and passing.
- **Zero edits under `sourbycraft-server/src/minecraft/java`** (NMS patch surface). All work in `sourbycraft-server/src/main/java` (plain tracked files, no nested-git dance needed).
- **Keys never deleted** from operator files — SUPERSEDED/RESERVED/unknown keys are preserved with annotations.
- **All existing `public static` fields on `SourbyCraftConfig` keep existing** and keep their post-boot values (NMS patches read them).
- **Effective defaults are authoritative** (what the server actually runs today), NOT what stale comments/jar-yml claim. Divergences discovered in survey:
  - `antixray.raytrace.enabled` → effective **false** (jar resource says `true` but no code reads it there; operator bridge default is false)
  - `perf.ai.throttle-beyond-distance` / `throttle-tick-interval` → effective **80 / 2** (jar values win over `Knobs` declared 0/4 via `loadFromYml`)
  - `perf.sensor.warmup-ticks` → effective **200** (jar says 600 but `applyOperatorConfig` unconditionally applies the operator-bridge default 200)
- `SourbyCraftWorldConfig` reads `SourbyCraftConfig.config` (YamlConfiguration) for `world-settings.*` — that field and the YamlConfiguration load stay; only YamlConfiguration **saves** are removed.
- `config-version` bumps 7 → 8.
- Working branch: `release/26.2`.

---

### Task 1: Knob metadata model + new knob types

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/KeyStatus.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/KnobMeta.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/DoubleKnob.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/StringKnob.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/EnumKnob.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/StringListKnob.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/MapKnob.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/PerfKnob.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/BoolKnob.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/IntKnob.java`

**Interfaces:**
- Consumes: existing `KnobRegistry.register(PerfKnob)`, `KnobRegistry.warnOnce(String,int,int)`, `SourbyCraftConfig.ymlBool/ymlInt` (unchanged this task).
- Produces (later tasks rely on these exact signatures):
  - `KeyStatus` enum: `ACTIVE`, `SUPERSEDED`, `RESERVED`
  - `KnobMeta` record with static factories `KnobMeta.active(String... comment)`, `KnobMeta.superseded(String paperEquivalent, String... comment)`, `KnobMeta.reserved(String... comment)` and wither `meta.aliases(String... a)`
  - `PerfKnob.meta()` → `KnobMeta`; `PerfKnob.applyRaw(Object raw)` → `boolean` (false = type mismatch, default kept)
  - New knobs: `DoubleKnob(String key, double def, KnobMeta)` with `double get()`; `StringKnob(String key, String def, KnobMeta)` with `String get()`; `EnumKnob<E extends Enum<E>>(String key, Class<E> type, E def, KnobMeta)` with `E get()`; `StringListKnob(String key, List<String> def, KnobMeta)` with `List<String> get()`; `MapKnob(String key, Map<String,Object> def, KnobMeta)` with `Map<String,Object> get()`
  - New metadata ctor overloads: `BoolKnob(String key, boolean def, KnobMeta meta)`, `IntKnob(String key, int def, int min, int max, KnobMeta meta)`
  - `PerfKnob.typeName()` → `String` (for type-mismatch warnings)

- [ ] **Step 1: Create `KeyStatus.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

/**
 * Lifecycle status of a config key. Drives operator-yml rendering and the
 * boot-time superseded-key report.
 *
 * <ul>
 *   <li>{@code ACTIVE} — key drives engine behavior; rendered in fresh files.</li>
 *   <li>{@code SUPERSEDED} — key is loaded but drives nothing (moonrise / Paper
 *       owns the behavior). Never rendered fresh; preserved with annotation if
 *       the operator already has it; boot WARN when set non-default.</li>
 *   <li>{@code RESERVED} — key parks a future feature (e.g. item pool v2).
 *       Same rendering rules as SUPERSEDED.</li>
 * </ul>
 */
public enum KeyStatus {
    ACTIVE, SUPERSEDED, RESERVED
}
```

- [ ] **Step 2: Create `KnobMeta.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

import java.util.List;

/**
 * Declared metadata for one config key. Immutable; built via the static
 * factories + withers so declarations in {@code Knobs}/{@code ConfigKeys}
 * read as one expression.
 *
 * @param status          lifecycle status (drives rendering + superseded report)
 * @param reloadable      model-only flag for CS4 hot-reload (no behavior yet)
 * @param aliases         legacy dotted paths that resolve to this key when the
 *                        canonical path is absent in the operator file
 * @param comment         operator-facing comment lines rendered above the key
 * @param supersededBy    for SUPERSEDED keys: where the behavior lives now
 *                        (named in the boot WARN and the annotation comment)
 */
public record KnobMeta(
    KeyStatus status,
    boolean reloadable,
    List<String> aliases,
    List<String> comment,
    String supersededBy
) {

    public static KnobMeta active(String... comment) {
        return new KnobMeta(KeyStatus.ACTIVE, false, List.of(), List.of(comment), null);
    }

    public static KnobMeta superseded(String paperEquivalent, String... comment) {
        return new KnobMeta(KeyStatus.SUPERSEDED, false, List.of(), List.of(comment), paperEquivalent);
    }

    public static KnobMeta reserved(String... comment) {
        return new KnobMeta(KeyStatus.RESERVED, false, List.of(), List.of(comment), null);
    }

    public KnobMeta aliases(String... a) {
        return new KnobMeta(status, reloadable, List.of(a), comment, supersededBy);
    }

    public KnobMeta reloadable() {
        return new KnobMeta(status, true, aliases, comment, supersededBy);
    }

    /** Default meta for legacy no-meta constructors: ACTIVE, no comment. */
    static KnobMeta legacy() {
        return new KnobMeta(KeyStatus.ACTIVE, false, List.of(), List.of(), null);
    }
}
```

- [ ] **Step 3: Rewrite `PerfKnob.java`** (adds meta + applyRaw + typeName; keeps key/snapshot/loadFrom so existing subclasses and `KnobRegistry.loadAllFromYml` keep compiling until Task 4)

```java
package dev.iyanz.sourbycraft.perf.knob;

/**
 * Sealed base for a typed config knob. Subclasses own a typed value and a
 * clamp policy. Each instance auto-registers in KnobRegistry on construction.
 * Perf knob declarations live in {@link Knobs}; general config keys live in
 * {@code dev.iyanz.sourbycraft.config.ConfigKeys}.
 */
public sealed abstract class PerfKnob
    permits BoolKnob, IntKnob, DoubleKnob, StringKnob, EnumKnob, StringListKnob, MapKnob {

    protected final String key;
    protected final KnobMeta meta;

    protected PerfKnob(String key, KnobMeta meta) {
        this.key = key;
        this.meta = meta;
        KnobRegistry.register(this);
    }

    public final String key() { return key; }

    public final KnobMeta meta() { return meta; }

    /** Boxed snapshot value for {@link Knobs#snapshot()} and yml delegation. */
    public abstract Object snapshot();

    /** Boxed declared default (for the superseded report's non-default check). */
    public abstract Object defaultValue();

    /** Human-readable expected type for type-mismatch warnings. */
    public abstract String typeName();

    /**
     * Apply a raw value from the operator yml. Returns false when the raw
     * value's type does not fit this knob (value keeps its current state and
     * the caller emits the warn-once).
     */
    public abstract boolean applyRaw(Object raw);

    /** Read from sourbycraft.yml (jar-baked) and apply. Legacy boot path; removed in Task 4. */
    abstract void loadFrom();
}
```

- [ ] **Step 4: Rewrite `BoolKnob.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.SourbyCraftConfig;

public final class BoolKnob extends PerfKnob {

    private final boolean defaultValue;
    private volatile boolean value;

    public BoolKnob(String key, boolean defaultValue) {
        this(key, defaultValue, KnobMeta.legacy());
    }

    public BoolKnob(String key, boolean defaultValue, KnobMeta meta) {
        super(key, meta);
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public boolean get() { return value; }

    public void set(boolean v) { this.value = v; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "boolean"; }

    @Override public boolean applyRaw(Object raw) {
        if (raw instanceof Boolean b) { this.value = b; return true; }
        return false;
    }

    @Override void loadFrom() {
        this.value = SourbyCraftConfig.ymlBool(key, defaultValue);
    }
}
```

- [ ] **Step 5: Rewrite `IntKnob.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.SourbyCraftConfig;

public final class IntKnob extends PerfKnob {

    private final int defaultValue;
    private final int min;
    private final int max;
    private volatile int value;

    public IntKnob(String key, int defaultValue, int min, int max) {
        this(key, defaultValue, min, max, KnobMeta.legacy());
    }

    public IntKnob(String key, int defaultValue, int min, int max, KnobMeta meta) {
        super(key, meta);
        if (min > max) throw new IllegalArgumentException("min > max for " + key);
        this.min = min;
        this.max = max;
        this.defaultValue = clamp(defaultValue, min, max);
        this.value = this.defaultValue;
    }

    public int get() { return value; }

    public void set(int v) {
        int clamped = clamp(v, min, max);
        if (clamped != v) KnobRegistry.warnOnce(key, v, clamped);
        this.value = clamped;
    }

    public int min() { return min; }
    public int max() { return max; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "int"; }

    @Override public boolean applyRaw(Object raw) {
        if (raw instanceof Number n) { set(n.intValue()); return true; }
        return false;
    }

    @Override void loadFrom() {
        set(SourbyCraftConfig.ymlInt(key, defaultValue));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
```

- [ ] **Step 6: Create `DoubleKnob.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.SourbyCraftConfig;

public final class DoubleKnob extends PerfKnob {

    private final double defaultValue;
    private volatile double value;

    public DoubleKnob(String key, double defaultValue, KnobMeta meta) {
        super(key, meta);
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public double get() { return value; }

    public void set(double v) { this.value = v; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "double"; }

    @Override public boolean applyRaw(Object raw) {
        if (raw instanceof Number n) { this.value = n.doubleValue(); return true; }
        return false;
    }

    @Override void loadFrom() {
        this.value = SourbyCraftConfig.ymlDouble(key, defaultValue);
    }
}
```

- [ ] **Step 7: Create `StringKnob.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

public final class StringKnob extends PerfKnob {

    private final String defaultValue;
    private volatile String value;

    public StringKnob(String key, String defaultValue, KnobMeta meta) {
        super(key, meta);
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public String get() { return value; }

    public void set(String v) { this.value = v; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "String"; }

    @Override public boolean applyRaw(Object raw) {
        if (raw instanceof String s) { this.value = s; return true; }
        // Operators write unquoted scalars; a bare number/bool where a string
        // is expected is still usable as text (YamlConfiguration semantics).
        if (raw instanceof Number || raw instanceof Boolean) {
            this.value = String.valueOf(raw);
            return true;
        }
        return false;
    }

    @Override void loadFrom() {
        Object v = dev.iyanz.sourbycraft.SourbyCraftConfig.ymlGet(key, (Object) defaultValue);
        this.value = v instanceof String s ? s : defaultValue;
    }
}
```

- [ ] **Step 8: Create `EnumKnob.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

import java.util.Locale;

public final class EnumKnob<E extends Enum<E>> extends PerfKnob {

    private final Class<E> type;
    private final E defaultValue;
    private volatile E value;

    public EnumKnob(String key, Class<E> type, E defaultValue, KnobMeta meta) {
        super(key, meta);
        this.type = type;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public E get() { return value; }

    public void set(E v) { this.value = v; }

    /** Lowercase name — matches how operators write it in yml. */
    @Override public Object snapshot() { return value.name().toLowerCase(Locale.ROOT); }

    @Override public Object defaultValue() { return defaultValue.name().toLowerCase(Locale.ROOT); }

    @Override public String typeName() { return type.getSimpleName(); }

    @Override public boolean applyRaw(Object raw) {
        if (!(raw instanceof String s)) return false;
        try {
            this.value = Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override void loadFrom() { /* enum keys are operator-file only; no jar-baked read */ }
}
```

- [ ] **Step 9: Create `StringListKnob.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

import java.util.ArrayList;
import java.util.List;

public final class StringListKnob extends PerfKnob {

    private final List<String> defaultValue;
    private volatile List<String> value;

    public StringListKnob(String key, List<String> defaultValue, KnobMeta meta) {
        super(key, meta);
        this.defaultValue = List.copyOf(defaultValue);
        this.value = this.defaultValue;
    }

    public List<String> get() { return value; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "List<String>"; }

    @Override public boolean applyRaw(Object raw) {
        if (!(raw instanceof List<?> list)) return false;
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) out.add(o.toString());
        }
        this.value = List.copyOf(out);
        return true;
    }

    @Override void loadFrom() { /* list keys are operator-file only; no jar-baked read */ }
}
```

- [ ] **Step 10: Create `MapKnob.java`**

```java
package dev.iyanz.sourbycraft.perf.knob;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raw nested-map knob for structured sections (emoji.shortcodes.codes,
 * dab.entity-overrides). Values are deep-copied on apply; consumers parse
 * the raw structure in the SourbyCraftConfig bridge block.
 */
public final class MapKnob extends PerfKnob {

    private final Map<String, Object> defaultValue;
    private volatile Map<String, Object> value;

    public MapKnob(String key, Map<String, Object> defaultValue, KnobMeta meta) {
        super(key, meta);
        this.defaultValue = deepCopy(defaultValue);
        this.value = this.defaultValue;
    }

    public Map<String, Object> get() { return value; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "Map"; }

    @Override public boolean applyRaw(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return false;
        this.value = deepCopy(castKeysToString(m));
        return true;
    }

    @Override void loadFrom() { /* map keys are operator-file only; no jar-baked read */ }

    private static Map<String, Object> castKeysToString(Map<?, ?> in) {
        Map<String, Object> out = new LinkedHashMap<>(in.size());
        in.forEach((k, v) -> { if (k != null) out.put(k.toString(), v); });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>(in.size());
        in.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> m
            ? deepCopy(castKeysToString(m))
            : v));
        return Collections.unmodifiableMap(out);
    }
}
```

- [ ] **Step 11: Compile**

Run: `./gradlew :sourbycraft-server:compileJava`
Expected: BUILD SUCCESSFUL (no behavior change — new types unused, legacy ctors intact).

- [ ] **Step 12: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/
git commit -m "feat(config): knob metadata model + typed knob family

KeyStatus/KnobMeta on PerfKnob (comment, status, aliases, reloadable);
Double/String/Enum/StringList/Map knobs join sealed family. applyRaw
gives the upcoming ConfigRegistry a uniform typed setter. Legacy ctors
kept so Knobs declarations compile unchanged until Task 3."
```

---

### Task 2: ConfigRegistry + OperatorConfig; KnobRegistry becomes shim

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/config/ConfigRegistry.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/config/OperatorConfig.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/KnobRegistry.java`

**Interfaces:**
- Consumes: `PerfKnob` (`key()`, `meta()`, `applyRaw(Object)`, `snapshot()`, `defaultValue()`, `typeName()`), `KnobMeta.aliases()`, `SourbyLogger.info/warn`.
- Produces:
  - `ConfigRegistry.register(PerfKnob)` (throws `IllegalStateException` on duplicate canonical path or alias)
  - `ConfigRegistry.find(String path)` → `PerfKnob` or null (canonical first, then alias)
  - `ConfigRegistry.all()` → `List<PerfKnob>` in declaration order
  - `ConfigRegistry.loadAll(OperatorConfig op)` — alias-resolve + type-check + apply each knob
  - `ConfigRegistry.snapshot(String prefix)` → `Map<String,Object>` (keys starting with prefix)
  - `ConfigRegistry.logLoaded(String prefix, String context)`
  - `ConfigRegistry.warnOnceClamp(String key, int requested, int clamped)`
  - `OperatorConfig.load(java.io.File f)` → `OperatorConfig` (missing file → empty; IOException → WARN + empty; parse error → SEVERE log + `RuntimeException`)
  - `OperatorConfig.lookup(String dottedPath)` → `Object` or null
  - `OperatorConfig.root()` → `Map<String,Object>` (unmodifiable raw tree)

- [ ] **Step 1: Create `ConfigRegistry.java`**

```java
package dev.iyanz.sourbycraft.config;

import dev.iyanz.sourbycraft.perf.knob.PerfKnob;
import dev.iyanz.sourbycraft.util.SourbyLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE config registry (CS1). Every knob self-registers here on construction
 * (via the KnobRegistry shim). Owns canonical-path + alias indexes, the
 * operator-file load pass, warn-once dedupe, and snapshots.
 *
 * <p>Thread-safety: registration happens during class-init of the declaration
 * classes (Knobs, ConfigKeys) on the boot thread; lookups after boot are
 * read-only over effectively-final maps. ConcurrentHashMap keeps the class
 * safe if a mod or plugin classloads a declaration late.
 */
public final class ConfigRegistry {

    private static final Map<String, PerfKnob> BY_PATH = new ConcurrentHashMap<>();
    private static final Map<String, String> ALIAS_TO_CANONICAL = new ConcurrentHashMap<>();
    private static final List<PerfKnob> DECLARATION_ORDER = Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ConfigRegistry() {}

    public static void register(PerfKnob k) {
        if (BY_PATH.putIfAbsent(k.key(), k) != null) {
            throw new IllegalStateException("duplicate knob key: " + k.key());
        }
        for (String alias : k.meta().aliases()) {
            if (BY_PATH.containsKey(alias) || ALIAS_TO_CANONICAL.putIfAbsent(alias, k.key()) != null) {
                throw new IllegalStateException("duplicate alias: " + alias + " (for " + k.key() + ")");
            }
        }
        DECLARATION_ORDER.add(k);
    }

    /** Canonical path first, then alias. Null when the path is unknown to the registry. */
    public static PerfKnob find(String path) {
        PerfKnob k = BY_PATH.get(path);
        if (k != null) return k;
        String canonical = ALIAS_TO_CANONICAL.get(path);
        return canonical == null ? null : BY_PATH.get(canonical);
    }

    /** All knobs in declaration order. */
    public static List<PerfKnob> all() {
        synchronized (DECLARATION_ORDER) {
            return List.copyOf(DECLARATION_ORDER);
        }
    }

    /**
     * Load pass: for each knob, resolve the operator value (canonical path,
     * else first alias hit) and apply it. Type mismatch → warn-once + keep
     * the Java-declared default.
     */
    public static void loadAll(OperatorConfig op) {
        for (PerfKnob k : all()) {
            Object raw = op.lookup(k.key());
            if (raw == null) {
                for (String alias : k.meta().aliases()) {
                    raw = op.lookup(alias);
                    if (raw != null) break;
                }
            }
            if (raw == null) continue;
            if (!k.applyRaw(raw) && WARNED.add(k.key() + ":type")) {
                SourbyLogger.warn("[SourbyCraft] config key '" + k.key() + "' invalid type '"
                    + raw.getClass().getSimpleName() + "', expected " + k.typeName()
                    + " — using default " + k.defaultValue());
            }
        }
    }

    public static void warnOnceClamp(String key, int requested, int clamped) {
        String dedupeKey = key + ":" + (requested < clamped ? "lo" : "hi");
        if (WARNED.add(dedupeKey)) {
            SourbyLogger.warn("knob '" + key + "' value " + requested + " clamped to " + clamped);
        }
    }

    /** Snapshot of keys starting with {@code prefix} ("" = all), declaration order. */
    public static Map<String, Object> snapshot(String prefix) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (PerfKnob k : all()) {
            if (k.key().startsWith(prefix)) out.put(k.key(), k.snapshot());
        }
        return Collections.unmodifiableMap(out);
    }

    public static void logLoaded(String prefix, String context) {
        StringBuilder sb = new StringBuilder("perf knobs loaded [").append(context).append("]:");
        snapshot(prefix).forEach((key, v) -> sb.append(" ").append(key).append("=").append(v));
        SourbyLogger.info(sb.toString());
    }
}
```

- [ ] **Step 2: Create `OperatorConfig.java`**

```java
package dev.iyanz.sourbycraft.config;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

/**
 * Raw snakeyaml view of the operator's sourbycraft.yml. SafeConstructor —
 * never arbitrary object instantiation from operator input (Java analog of
 * yaml.safe_load). Dotted-path lookups mirror SourbyCraftConfig.lookupYml.
 */
public final class OperatorConfig {

    private final Map<String, Object> root;

    private OperatorConfig(Map<String, Object> root) {
        this.root = Collections.unmodifiableMap(root);
    }

    /**
     * Missing file → empty config (fresh install). Unreadable file → WARN +
     * empty (boot with defaults, matching pre-registry behavior). Parse error
     * → SEVERE + RuntimeException (boot abort — operator must fix syntax).
     */
    public static OperatorConfig load(File f) {
        if (!f.exists()) return new OperatorConfig(Map.of());
        try (InputStream in = Files.newInputStream(f.toPath())) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> parsed = yaml.load(in);
            return new OperatorConfig(parsed == null ? Map.of() : parsed);
        } catch (IOException e) {
            SourbyLogger.warn("Could not read " + f.getName() + ", starting with defaults: " + e.getMessage());
            return new OperatorConfig(Map.of());
        } catch (RuntimeException e) {
            SourbyLogger.error("Could not parse " + f.getName() + ", please correct your syntax errors", e);
            throw new RuntimeException(e);
        }
    }

    public Object lookup(String dottedPath) {
        Object cur = root;
        for (String seg : dottedPath.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(seg);
            if (cur == null) return null;
        }
        return cur;
    }

    public Map<String, Object> root() { return root; }
}
```

- [ ] **Step 3: Rewrite `KnobRegistry.java` as shim**

```java
package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.config.ConfigRegistry;

import java.util.Map;

/**
 * Thin shim over {@link ConfigRegistry} kept at this FQCN because knob call
 * sites (Knobs, IntKnob clamp warns) predate the unified registry. New code
 * should use ConfigRegistry directly.
 */
public final class KnobRegistry {

    private KnobRegistry() {}

    static void register(PerfKnob k) {
        ConfigRegistry.register(k);
    }

    static void warnOnce(String key, int requested, int clamped) {
        ConfigRegistry.warnOnceClamp(key, requested, clamped);
    }

    public static Map<String, Object> snapshot() {
        return ConfigRegistry.snapshot("perf.");
    }

    static void loadAllFromYml() {
        for (PerfKnob k : ConfigRegistry.all()) k.loadFrom();
    }

    static void logLoaded(String context) {
        ConfigRegistry.logLoaded("perf.", context);
    }
}
```

Behavior note: `snapshot()`/`logLoaded()` now filter to `perf.`-prefixed keys — today's registry contains exactly the `perf.*` knobs, so output is unchanged.

- [ ] **Step 4: Compile**

Run: `./gradlew :sourbycraft-server:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/config/ sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/KnobRegistry.java
git commit -m "feat(config): ConfigRegistry + OperatorConfig; KnobRegistry shims

Single registry with canonical+alias indexes, typed load pass over raw
snakeyaml operator map, warn-once for type/clamp violations. KnobRegistry
delegates so existing perf.* call sites and log shape stay identical."
```

---

### Task 3: Key declarations — Knobs metadata + full ConfigKeys

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/config/ConfigKeys.java`

**Interfaces:**
- Consumes: knob ctors + `KnobMeta` factories from Task 1.
- Produces: every `public static final` field below — Task 4's bridge block references them by these exact names. `ConfigKeys.bootstrap()` no-op forces class-init.

- [ ] **Step 1: Update `Knobs.java` declarations**

Change ONLY the two AI-throttle declarations to the **effective** defaults (jar values won over the old declared 0/4 via `loadFromYml` — Global Constraints):

```java
    /** Distance (blocks) beyond the nearest player at which mob aiStep gets throttled.
     *  0 = disabled (vanilla every-tick AI for all mobs). 26.2 high-pop default: mild
     *  baseline throttle — mobs >80 blocks from any player tick AI every 2nd tick. */
    public static final IntKnob AI_THROTTLE_BEYOND_DISTANCE =
        new IntKnob("perf.ai.throttle-beyond-distance", 80, 0, 256,
            KnobMeta.active(
                "P3 adaptive AI throttle. Mobs whose nearest player is beyond this distance",
                "run aiStep only every throttle-tick-interval ticks. 0 disables entirely.",
                "SelfTune escalates under load: ORANGE 64/2, RED 48/4, EMERGENCY 32/8."));

    /** When throttled, run aiStep only every N server ticks. Higher = cheaper but more visibly frozen. */
    public static final IntKnob AI_THROTTLE_TICK_INTERVAL =
        new IntKnob("perf.ai.throttle-tick-interval", 2, 1, 40,
            KnobMeta.active(
                "Tick interval for throttled mobs (see throttle-beyond-distance)."));
```

And add the operator alias + comment to `ENTITY_TICK_RATE`:

```java
    /** Skip-rate for entity ticking. 1 = tick every server tick (vanilla); 20 = once per second. */
    public static final IntKnob ENTITY_TICK_RATE =
        new IntKnob("perf.entity-tick-rate", 20, 1, 20,
            KnobMeta.active(
                "Skip-rate for INACTIVE entity ticking (gated by entity.tick-rate-limit).",
                "1 = tick every server tick (vanilla); higher = skip ticks.",
                "P7 SelfTuneController may override at runtime per PerfSensor tier.")
                .aliases("entity.tick-rate"));
```

Add metadata to the eight P2 lag-machine knobs the same way — each keeps its key/default/clamp EXACTLY as-is, gaining only a `KnobMeta.active(...)` argument whose text is copied from the existing javadoc line. Example for the first (repeat the pattern for the other seven):

```java
    public static final BoolKnob LAG_MACHINE_DISABLE_SAVING_SNOWBALLS =
        new BoolKnob("perf.lag-machine.disable-saving-snowballs", true,
            KnobMeta.active(
                "Disables NBT saving for Snowball entities. Saved snowballs are a known",
                "lag-machine vector (despawn-on-load thousands per chunk -> slow chunk load)."));
```

- [ ] **Step 2: Create `ConfigKeys.java`** — the full general-key inventory. Defaults are the surveyed EFFECTIVE values.

```java
package dev.iyanz.sourbycraft.config;

import dev.iyanz.sourbycraft.perf.CombatProfile;
import dev.iyanz.sourbycraft.perf.knob.BoolKnob;
import dev.iyanz.sourbycraft.perf.knob.DoubleKnob;
import dev.iyanz.sourbycraft.perf.knob.EnumKnob;
import dev.iyanz.sourbycraft.perf.knob.IntKnob;
import dev.iyanz.sourbycraft.perf.knob.KnobMeta;
import dev.iyanz.sourbycraft.perf.knob.MapKnob;
import dev.iyanz.sourbycraft.perf.knob.StringListKnob;
import dev.iyanz.sourbycraft.perf.knob.StringKnob;

import java.util.List;
import java.util.Map;

/**
 * Declaration site for all general (non-perf.*) sourbycraft.yml keys.
 * Perf knobs live in {@link dev.iyanz.sourbycraft.perf.knob.Knobs}.
 * Java defaults here are AUTHORITATIVE — the jar-baked resource yml is
 * only a fallback for keys unknown to the registry.
 */
public final class ConfigKeys {

    private ConfigKeys() {}

    /** Forces class-init (and thereby registration) from SourbyCraftConfig.init(). */
    public static void bootstrap() {}

    // === settings ===

    public static final BoolKnob VERBOSE =
        new BoolKnob("verbose", false, KnobMeta.active(
            "Log every config value as it is read (boot diagnostics)."));

    public static final BoolKnob DETAILED_BRAND =
        new BoolKnob("settings.detailed-brand-info", true, KnobMeta.active(
            "Include SourbyCraft version details in the client F3 brand line.")
            .aliases("settings.debug-version"));

    public static final BoolKnob TRANSLATE_ITEMS =
        new BoolKnob("settings.translate-items", true, KnobMeta.active(
            "Localize item names server-side via Adventure translation.")
            .aliases("settings.adventure.localize-items", "settings.localize.items"));

    public static final BoolKnob SURFACE_RULES_DEFAULT_FLUIDS =
        new BoolKnob("settings.allow-surface-rules-for-default-fluids", false, KnobMeta.active(
            "Allow custom worldgen surface rules to replace default fluids."));

    public static final BoolKnob DISABLE_COMMUNICATION_COMMANDS =
        new BoolKnob("settings.disable-communication-commands", false, KnobMeta.active(
            "Disable vanilla /msg /tell /me and friends (chat-plugin servers)."));

    // === server ===

    public static final IntKnob IDLE_TIMEOUT =
        new IntKnob("server.idle-timeout", 0, 0, 1440, KnobMeta.active(
            "Kick players idle for this many minutes. 0 = never."));

    // === combat ===

    public static final EnumKnob<CombatProfile> COMBAT_PROFILE =
        new EnumKnob<>("combat.profile", CombatProfile.class, CombatProfile.VANILLA, KnobMeta.active(
            "Knob-default bundle tuned per playstyle: vanilla | balanced | pvp.",
            "vanilla = Mojang parity. balanced = moderate AI throttle for SMP.",
            "pvp = aggressive throttle + vehicle sweepers for arena servers."));

    // === performance ===

    public static final BoolKnob VIRTUAL_THREADS =
        new BoolKnob("performance.virtual-threads", true, KnobMeta.active(
            "Use JVM virtual threads for SourbyCraft async helpers (VirtualExecutor)."));

    public static final IntKnob MAX_PLATFORM_THREADS =
        new IntKnob("performance.max-platform-threads", 4, 1, 1024, KnobMeta.active(
            "Sets the max.bg.threads system property for Minecraft's background pool.",
            "CAVEAT: Util.BACKGROUND_EXECUTOR is constructed before config load, so this",
            "only influences executors created after boot (tool reloads, sub-pools)."));

    public static final IntKnob CHUNK_WORKERS =
        new IntKnob("performance.threads.chunk-workers", -1, -1, 1024, KnobMeta.active(
            "Moonrise chunk-worker thread count. -1 = smart auto: min(8, max(2, cpus/2)),",
            "only when Paper chunk-system.worker-threads is also -1 (no operator fight).",
            "> 0 = exact count passed to MoonriseCommon.adjustWorkerThreads."));

    public static final IntKnob IO_WORKERS =
        new IntKnob("performance.threads.io-workers", -1, -1, 1024, KnobMeta.active(
            "Moonrise I/O thread count. -1 = Paper default."));

    public static final BoolKnob ASYNC_SPAWNING =
        new BoolKnob("performance.async-spawning", true, KnobMeta.active(
            "MT1 async mob-spawn density pipeline (pufferfish semantics). Spawn density",
            "state computed off-main; actual spawns + Bukkit events stay on main.",
            "Forced false if PufferfishConfig.enableAsyncMobSpawning is false."));

    // === network ===

    public static final BoolKnob AUTO_THROTTLE_VIEW =
        new BoolKnob("network.auto-throttle-view", true, KnobMeta.active(
            "Dynamically lower view distance under load (ViewThrottle)."));

    public static final IntKnob MIN_VIEW_DISTANCE =
        new IntKnob("network.min-view-distance", 4, 2, 32, KnobMeta.active(
            "Floor for the auto-throttled view distance."));

    public static final IntKnob COMPRESSION_LEVEL =
        new IntKnob("network.compression-level", 4, 0, 9, KnobMeta.active(
            "Packet deflater level bridged into Paper's live compression engine.",
            "0 = store, 9 = max. 4 = Paper default (leave unless CPU-bound on network)."));

    public static final DoubleKnob MAX_CHUNK_SEND_RATE =
        new DoubleKnob("network.max-chunk-send-rate", -1.0, KnobMeta.active(
            "Override Paper's playerMaxChunkSendRate (chunks/second/player).",
            "-1.0 = Paper default (75.0)."));

    // === entity ===

    public static final BoolKnob ENTITY_TICK_RATE_LIMIT =
        new BoolKnob("entity.tick-rate-limit", false, KnobMeta.active(
            "Gate for perf.entity-tick-rate: when true, INACTIVE entities (outside",
            "activation range) additionally skip ticks per the tick-rate knob.",
            "Wired in ActivationRange (S2). Active entities are never skipped."));

    public static final BoolKnob HOPPER_BATCH =
        new BoolKnob("entity.hopper-batch", true, KnobMeta.active(
            "Batch hopper item-transfer attempts to cut per-tick container scans."));

    public static final BoolKnob REDSTONE_OPTIMIZE =
        new BoolKnob("entity.redstone-optimize", true, KnobMeta.active(
            "Alternate-current style redstone wire evaluation (fewer block updates)."));

    public static final IntKnob MAX_ENTITY_PER_CHUNK =
        new IntKnob("entity.max-per-chunk", 10, 0, 10000, KnobMeta.active(
            "Natural-spawn cap per chunk. 0 = uncapped."));

    public static final IntKnob MAX_SPECIALS_PER_CHUNK =
        new IntKnob("entity.max-specials-per-chunk", 15, 0, 10000, KnobMeta.active(
            "Cap for special entities (TNT, falling blocks in motion, etc) per chunk."));

    public static final IntKnob MAX_FALLING_BLOCK_PER_CHUNK =
        new IntKnob("entity.max-falling-block-per-chunk", 20, 0, 10000, KnobMeta.active(
            "Cap for falling-block entities per chunk (sand-cannon protection)."));

    public static final IntKnob MAX_ARROWS_PER_WORLD =
        new IntKnob("entity.max-arrows-per-world", 5000, 0, 1000000, KnobMeta.active(
            "World-wide arrow entity cap."));

    public static final IntKnob MAX_REDSTONE_UPDATES_PER_TICK =
        new IntKnob("entity.max-redstone-updates-per-tick", 10000, 0, 1000000, KnobMeta.active(
            "Caps the vanilla chained neighbor-update budget (vanilla: 1M).",
            "10k spares legit contraptions; 2000 broke big doors/storage tech."));

    public static final BoolKnob ITEM_MERGE_OPTIMIZE =
        new BoolKnob("entity.item-merge-optimize", true, KnobMeta.active(
            "Cheaper ground-item merge scan."));

    public static final IntKnob ITEM_DESPAWN_RATE =
        new IntKnob("entity.item-despawn-rate", 6000, 20, 72000, KnobMeta.active(
            "Ticks before a ground item despawns. 6000 = 5 minutes (vanilla)."));

    public static final IntKnob ITEM_MERGE_RADIUS =
        new IntKnob("entity.item-merge-radius", 3, 0, 16, KnobMeta.active(
            "Ground-item merge radius (blocks). 3 reliably merges a dropped full",
            "inventory scattered 1-2 blocks apart into one entity."));

    public static final IntKnob MOB_TICK_DISTANCE =
        new IntKnob("entity.mob-tick-distance", 32, 0, 256, KnobMeta.active(
            "Distance (blocks) from a player within which mobs fully tick."));

    public static final IntKnob MOB_PATHFIND_INTERVAL =
        new IntKnob("entity.mob-pathfind-interval", 20, 1, 200, KnobMeta.active(
            "Minimum ticks between pathfinder recalculations per mob."));

    // === item ===

    public static final BoolKnob NO_DURABILITY_EXCEPT =
        new BoolKnob("item.no-durability-except", false, KnobMeta.active(
            "Disable durability loss for all items (except the configured exceptions)."));

    public static final BoolKnob UNLIMITED_DROP_STACK =
        new BoolKnob("item.unlimited-drop-stack", true, KnobMeta.active(
            "Allow ground-item stacks to exceed the vanilla max-stack-size on merge."));

    public static final IntKnob DROP_STACK_CAP =
        new IntKnob("item.drop-stack-cap", Integer.MAX_VALUE, 1, Integer.MAX_VALUE, KnobMeta.active(
            "Hard cap for merged ground-item stack counts."));

    public static final BoolKnob OWNER_PROTECTION_ENABLED =
        new BoolKnob("item.owner-protection-enabled", true, KnobMeta.active(
            "Dropped items are pickup-locked to their owner for owner-protection-time."));

    public static final IntKnob OWNER_PROTECTION_TIME =
        new IntKnob("item.owner-protection-time", 10, 0, 1638, KnobMeta.active(
            "Owner-protection window in seconds (max 1638 — NBT short-field limit)."));

    public static final BoolKnob ITEM_POOL_ENABLED =
        new BoolKnob("item.pool-enabled", false, KnobMeta.reserved(
            "RESERVED for item pool v2. The v1 ItemEntityPool engine is offline —",
            "recycled entities kept stale noGravity/velocity state (levitating items).",
            "Setting true does nothing except a boot WARN."));

    public static final IntKnob ITEM_POOL_SIZE =
        new IntKnob("item.pool-size", 256, 1, 65536, KnobMeta.reserved(
            "RESERVED for item pool v2."));

    public static final IntKnob ITEM_POOL_MAX_GROWTH =
        new IntKnob("item.pool-max-growth", 1024, 1, 65536, KnobMeta.reserved(
            "RESERVED for item pool v2."));

    public static final DoubleKnob ITEM_POOL_SHRINK_THRESHOLD =
        new DoubleKnob("item.pool-shrink-threshold", 0.5, KnobMeta.reserved(
            "RESERVED for item pool v2."));

    public static final IntKnob ITEM_MAX_PER_CHUNK =
        new IntKnob("item.max-per-chunk", 64, 0, 10000, KnobMeta.active(
            "Ground-item entity cap per chunk."));

    // === stacker ===

    public static final BoolKnob STACKER_ENABLED =
        new BoolKnob("stacker.enabled", false, KnobMeta.active(
            "Entity stacker (slim WildStacker re-port). Stacks same-type mobs in radius.")
            .aliases("performance.wildstacker.enabled"));

    public static final DoubleKnob STACKER_RADIUS =
        new DoubleKnob("stacker.radius", 10.0, KnobMeta.active(
            "Stack-merge search radius (blocks)."));

    public static final IntKnob STACKER_MAX_STACK =
        new IntKnob("stacker.max-stack", 100, 2, 100000, KnobMeta.active(
            "Maximum entities merged into one stack."));

    public static final BoolKnob STACKER_HOLOGRAM =
        new BoolKnob("stacker.hologram", true, KnobMeta.active(
            "Show stack-count hologram name above stacked entities.")
            .aliases("performance.wildstacker.hologram"));

    public static final BoolKnob STACKER_LOS_CHECK =
        new BoolKnob("stacker.los-check", true, KnobMeta.active(
            "Require line-of-sight between entities before merging.")
            .aliases("performance.wildstacker.los-check"));

    public static final StringListKnob STACKER_BLACKLIST =
        new StringListKnob("stacker.blacklist",
            List.of("PLAYER", "ARMOR_STAND", "ENDER_DRAGON", "WITHER"),
            KnobMeta.active("EntityType names never stacked."));

    // === antixray ===

    public static final BoolKnob ANTIXRAY_RAYTRACE_ENABLED =
        new BoolKnob("antixray.raytrace.enabled", false, KnobMeta.active(
            "SourbyCraft port of stonar96/RayTraceAntiXray. Hides CAVE-EXPOSED ores",
            "until the player has real line-of-sight (complements Paper engine-mode 1,",
            "which only hides enclosed ores). Line-of-sight checks run off-main on the",
            "VirtualExecutor pool. Recommended ON for resource-pack-proof ore hiding."));

    public static final IntKnob ANTIXRAY_RAYTRACE_INTERVAL_TICKS =
        new IntKnob("antixray.raytrace.interval-ticks", 10, 1, 1000000, KnobMeta.active(
            "Ticks between ray-trace cycles per player."));

    public static final IntKnob ANTIXRAY_RAYTRACE_DISTANCE =
        new IntKnob("antixray.raytrace.distance", 48, 8, 128, KnobMeta.active(
            "Max distance (blocks) at which ores are ray-trace checked."));

    public static final IntKnob ANTIXRAY_RAYTRACE_MAX_CHECKS =
        new IntKnob("antixray.raytrace.max-checks-per-cycle", 192, 16, 2048, KnobMeta.active(
            "Line-of-sight checks per player per cycle (CPU budget)."));

    public static final IntKnob ANTIXRAY_RAYTRACE_MAX_PENDING =
        new IntKnob("antixray.raytrace.max-pending-per-player", 8192, 512, 65536, KnobMeta.active(
            "Queued ore-check backlog cap per player."));

    public static final IntKnob ANTIXRAY_RAYTRACE_CACHE_TTL =
        new IntKnob("antixray.raytrace.cache-ttl-ticks", 600, 20, 12000, KnobMeta.active(
            "Per-chunk exposed-ore scan cache TTL (ticks). Scan is player-independent:",
            "computed once per chunk, reused for every send. Block-change + chunk-unload",
            "invalidate precisely; TTL only bounds staleness from non-event changes",
            "(worldgen finalize, fluid flow). 600t = 30s."));

    public static final BoolKnob ANTIXRAY_ENTITY_RAYTRACE =
        new BoolKnob("antixray.entity-raytrace.enabled", false, KnobMeta.active(
            "Entity-level occlusion: entities behind walls stay client-invisible",
            "(tracker-tick line-of-sight gate). Gameplay impact: entities pop in",
            "when line-of-sight is regained."));

    public static final BoolKnob ANTIXRAY_PARTICLE_RAYTRACE =
        new BoolKnob("antixray.particle-raytrace.enabled", false, KnobMeta.active(
            "Drop per-player particle packets whose origin is occluded from the",
            "receiver's eye (closes the wallhack particle signal)."));

    public static final BoolKnob ANTIXRAY_FLUID_OBSCURES =
        new BoolKnob("antixray.fluid-obscures", true, KnobMeta.active(
            "Treat fluids as obscuring for the exposed-ore scan. Liquid-surface",
            "obfuscation itself is Paper's anticheat.anti-xray in paper-world.yml."));

    // === emoji ===

    public static final BoolKnob EMOJI_ENABLED =
        new BoolKnob("emoji.shortcodes.enabled", true, KnobMeta.active(
            "Chat shortcode translation (:smile: -> emoji)."));

    public static final MapKnob EMOJI_CODES =
        new MapKnob("emoji.shortcodes.codes",
            Map.copyOf(dev.iyanz.sourbycraft.chat.EmojiShortcodes.map()),
            KnobMeta.active("Shortcode -> emoji map. Edit/extend freely; replaces defaults."));

    // === dab ===

    public static final MapKnob DAB_ENTITY_OVERRIDES =
        new MapKnob("dab.entity-overrides", Map.of(), KnobMeta.active(
            "Per-entity-type DAB overrides. Key: minecraft:zombie. Sub-keys:",
            "max-tick-freq (inactive wakeup cadence, floor 20), activation-dist-mod."));

    // === particles (UniverseSpigot import; jar-baked keys now canonical here) ===

    public static final BoolKnob DISABLE_FALL_PARTICLES =
        new BoolKnob("particles.disableFallParticles", false, KnobMeta.active(
            "Disable entity fall particles."));

    public static final BoolKnob DISABLE_DEATH_PARTICLES =
        new BoolKnob("particles.disableDeathParticles", false, KnobMeta.active(
            "Disable entity death particles."));

    public static final BoolKnob DISABLE_BLOCK_BREAK_PARTICLES =
        new BoolKnob("particles.disableBlockBreakParticles", false, KnobMeta.active(
            "Disable block-break particles."));

    public static final BoolKnob DISABLE_EFFECT_PARTICLES =
        new BoolKnob("particles.disableEffectParticles", false, KnobMeta.active(
            "Disable potion / status-effect ambient particles."));

    public static final BoolKnob DISABLE_WATER_SPLASH_PARTICLES =
        new BoolKnob("particles.disableWaterSplashParticles", false, KnobMeta.active(
            "Disable water-splash particles on water entry."));

    public static final BoolKnob DISABLE_NEW_COMBAT_PARTICLES =
        new BoolKnob("particles.disableNewCombatParticles", false, KnobMeta.active(
            "Disable sweep-attack and other 1.21+ combat particles."));

    // === sounds (UniverseSpigot import) ===

    public static final BoolKnob DISABLE_SHOULDER_AMBIENT_SOUND =
        new BoolKnob("sounds.disableShoulderEntityAmbientSound", false, KnobMeta.active(
            "Disable parrot shoulder-perched ambient mimicry."));

    public static final BoolKnob DISABLE_PIGLIN_ANGER_SOUND =
        new BoolKnob("sounds.disablePiglinAngerSound", false, KnobMeta.active(
            "Suppress only the piglin anger ambient variant."));

    public static final BoolKnob DISABLE_FOOTSTEP_SOUNDS =
        new BoolKnob("sounds.disableFootStepSounds", false, KnobMeta.active(
            "Disable entity footstep sounds (step + swim-step variants)."));

    public static final BoolKnob DISABLE_NEW_COMBAT_SOUNDS =
        new BoolKnob("sounds.disableNewCombatSounds", false, KnobMeta.active(
            "Disable PLAYER_ATTACK_* sound family from 1.9+ combat."));

    public static final BoolKnob DISABLE_SHIELD_SOUNDS =
        new BoolKnob("sounds.disableShieldSounds", false, KnobMeta.active(
            "Disable shield-block impact sound."));

    public static final BoolKnob DISABLE_PISTON_SOUNDS =
        new BoolKnob("sounds.disablePistonSounds", false, KnobMeta.active(
            "Disable piston extend / retract sounds."));

    // === branding ===

    public static final BoolKnob MOTD_SUFFIX =
        new BoolKnob("branding.motd-suffix", false, KnobMeta.active(
            "Append SourbyCraft suffix to the server MOTD."));

    public static final BoolKnob COMPACT_PLUGIN_LIST =
        new BoolKnob("branding.compact-plugin-list", true, KnobMeta.active(
            "Compact /plugins output."));

    public static final BoolKnob COMPACT_PLUGIN_LOG =
        new BoolKnob("branding.compact-plugin-log", true, KnobMeta.active(
            "Compact plugin enable/disable boot log lines."));

    public static final BoolKnob GC_ADVISOR =
        new BoolKnob("branding.gc-advisor.enabled", true, KnobMeta.active(
            "Boot-time GC/heap configuration advisory log."));

    // === spark ===

    public static final BoolKnob SPARK_ENABLED =
        new BoolKnob("spark.enabled", true, KnobMeta.active(
            "SourbyCraft spark bridge. false short-circuits SparkBridge so /sparkview",
            "never touches the spark API."));

    // === perf.sensor (effective defaults — warmup 200 per operator-bridge survey) ===

    public static final BoolKnob SENSOR_ENABLED =
        new BoolKnob("perf.sensor.enabled", true, KnobMeta.active(
            "Multi-signal load sensor feeding the 5-tier state machine",
            "(GREEN/YELLOW/ORANGE/RED/EMERGENCY). SelfTuneController reads the tier."));

    public static final IntKnob SENSOR_CADENCE_TICKS =
        new IntKnob("perf.sensor.cadence-ticks", 20, 1, 1200, KnobMeta.active(
            "Ticks between sensor samples. 20 = 1s at 20 TPS."));

    public static final IntKnob SENSOR_DWELL_SAMPLES =
        new IntKnob("perf.sensor.dwell-samples", 3, 1, 100, KnobMeta.active(
            "Samples in a candidate tier required before escalation."));

    public static final DoubleKnob SENSOR_RECOVERY_MULTIPLIER =
        new DoubleKnob("perf.sensor.recovery-dwell-multiplier", 2.0, KnobMeta.active(
            "Recovery requires dwell-samples * multiplier samples."));

    public static final IntKnob SENSOR_WARMUP_TICKS =
        new IntKnob("perf.sensor.warmup-ticks", 200, 0, 72000, KnobMeta.active(
            "Ticks skipped at startup before sampling (covers plugin-enable load)."));

    public static final DoubleKnob SENSOR_TPS_YELLOW =
        new DoubleKnob("perf.sensor.thresholds.tps.yellow", 19.5, KnobMeta.active(
            "TPS thresholds: LOWER value = worse tier. TPS below yellow -> at least YELLOW."));
    public static final DoubleKnob SENSOR_TPS_ORANGE =
        new DoubleKnob("perf.sensor.thresholds.tps.orange", 18.0, KnobMeta.active());
    public static final DoubleKnob SENSOR_TPS_RED =
        new DoubleKnob("perf.sensor.thresholds.tps.red", 15.0, KnobMeta.active());
    public static final DoubleKnob SENSOR_TPS_EMERGENCY =
        new DoubleKnob("perf.sensor.thresholds.tps.emergency", 10.0, KnobMeta.active());

    public static final DoubleKnob SENSOR_MSPT_YELLOW =
        new DoubleKnob("perf.sensor.thresholds.mspt.yellow", 30.0, KnobMeta.active(
            "MSPT / mem / GC thresholds: HIGHER value = worse tier."));
    public static final DoubleKnob SENSOR_MSPT_ORANGE =
        new DoubleKnob("perf.sensor.thresholds.mspt.orange", 40.0, KnobMeta.active());
    public static final DoubleKnob SENSOR_MSPT_RED =
        new DoubleKnob("perf.sensor.thresholds.mspt.red", 60.0, KnobMeta.active());
    public static final DoubleKnob SENSOR_MSPT_EMERGENCY =
        new DoubleKnob("perf.sensor.thresholds.mspt.emergency", 100.0, KnobMeta.active());

    public static final DoubleKnob SENSOR_MEM_YELLOW =
        new DoubleKnob("perf.sensor.thresholds.mem.yellow", 75.0, KnobMeta.active());
    public static final DoubleKnob SENSOR_MEM_ORANGE =
        new DoubleKnob("perf.sensor.thresholds.mem.orange", 85.0, KnobMeta.active());
    public static final DoubleKnob SENSOR_MEM_RED =
        new DoubleKnob("perf.sensor.thresholds.mem.red", 92.0, KnobMeta.active());
    public static final DoubleKnob SENSOR_MEM_EMERGENCY =
        new DoubleKnob("perf.sensor.thresholds.mem.emergency", 97.0, KnobMeta.active());

    public static final DoubleKnob SENSOR_GC_YELLOW =
        new DoubleKnob("perf.sensor.thresholds.gc-ms-per-min.yellow", 20.0, KnobMeta.active());
    public static final DoubleKnob SENSOR_GC_ORANGE =
        new DoubleKnob("perf.sensor.thresholds.gc-ms-per-min.orange", 50.0, KnobMeta.active());
    public static final DoubleKnob SENSOR_GC_RED =
        new DoubleKnob("perf.sensor.thresholds.gc-ms-per-min.red", 100.0, KnobMeta.active());
    public static final DoubleKnob SENSOR_GC_EMERGENCY =
        new DoubleKnob("perf.sensor.thresholds.gc-ms-per-min.emergency", 300.0, KnobMeta.active());

    // === SUPERSEDED keys (PG1 verdicts — loaded, drive nothing, WARN when non-default) ===

    public static final BoolKnob SUP_MULTITHREADING =
        new BoolKnob("multithreading.enabled", false, KnobMeta.superseded(
            "paper-global.yml async-chunks (moonrise owns async dispatch)"));

    public static final BoolKnob SUP_ASYNC_CHUNK_LOAD =
        new BoolKnob("performance.async-chunk-load", false, KnobMeta.superseded(
            "moonrise (async chunk loading is automatic)"));

    public static final BoolKnob SUP_ASYNC_PATHFINDING =
        new BoolKnob("performance.async-pathfinding", false, KnobMeta.superseded(
            "moonrise (async pathfinding is automatic)"));

    public static final BoolKnob SUP_STRUCTURED_CONCURRENCY =
        new BoolKnob("performance.structured-concurrency", true, KnobMeta.superseded(
            "JVM virtual threads (native on Java 21+; no custom dispatcher exists)"));

    public static final BoolKnob SUP_SKIP_EMPTY_SECTIONS =
        new BoolKnob("memory.skip-empty-sections", true, KnobMeta.superseded(
            "moonrise (empty-section optimisation is native)"));

    public static final BoolKnob SUP_POOL_ENTITY_DATA =
        new BoolKnob("memory.pool-entity-data", true, KnobMeta.superseded(
            "paper-world.yml entity-per-chunk-save-limit"));

    public static final BoolKnob SUP_PRE_SIZE_PACKETS =
        new BoolKnob("memory.pre-size-packets", false, KnobMeta.superseded(
            "Paper netty pipeline (internal packet buffer sizing)"));

    public static final BoolKnob SUP_CHUNK_COMPRESSION_CACHE =
        new BoolKnob("memory.chunk-compression-cache", false, KnobMeta.superseded(
            "Paper chunk serializer (own cache layer)"));

    public static final BoolKnob SUP_ASYNC_SAVE_BATCH =
        new BoolKnob("chunk.async-save-batch", true, KnobMeta.superseded(
            "moonrise (chunk saves batched automatically)"));

    public static final StringKnob SUP_PROXY_MODE =
        new StringKnob("network.proxy-mode", "velocity-modern", KnobMeta.superseded(
            "paper-global.yml proxies.*"));

    public static final IntKnob SUP_NETTY_SND_BUF =
        new IntKnob("network.netty.snd-buf-kb", 64, 0, 65536, KnobMeta.superseded(
            "paper-global.yml proxies / netty internals"));

    public static final IntKnob SUP_NETTY_RCV_BUF =
        new IntKnob("network.netty.rcv-buf-kb", 64, 0, 65536, KnobMeta.superseded(
            "paper-global.yml proxies / netty internals"));

    public static final IntKnob SUP_NETTY_MAX_PACKETS =
        new IntKnob("network.netty.max-packets-per-tick", 100, 0, 100000, KnobMeta.superseded(
            "Paper netty pipeline"));

    public static final StringKnob SUP_NETTY_THREADS =
        new StringKnob("network.netty.threads", "auto-doubled", KnobMeta.superseded(
            "Paper netty pipeline (io.netty.eventLoopThreads system property)"));

    public static final IntKnob SUP_PROXY_KICK_GRACE =
        new IntKnob("network.proxy-kick-grace-seconds", 5, 0, 3600, KnobMeta.superseded(
            "paper-global.yml proxies.*"));

    public static final StringKnob SUP_PROXY_KICK_FALLBACK =
        new StringKnob("network.proxy-kick-fallback", "lobby", KnobMeta.superseded(
            "proxy configuration (Velocity/Bungee fallback server)"));

    public static final IntKnob SUP_TRACKER_MOB_RANGE =
        new IntKnob("entity-tracker.mob-range", 32, 0, 1024, KnobMeta.superseded(
            "paper-world.yml entity-tracking-range.*"));

    public static final IntKnob SUP_TRACKER_ITEM_RANGE =
        new IntKnob("entity-tracker.item-range", 16, 0, 1024, KnobMeta.superseded(
            "paper-world.yml entity-tracking-range.*"));

    public static final IntKnob SUP_TRACKER_XP_RANGE =
        new IntKnob("entity-tracker.xp-orb-range", 16, 0, 1024, KnobMeta.superseded(
            "paper-world.yml entity-tracking-range.*"));

    public static final IntKnob SUP_TRACKER_PLAYER_INTERVAL =
        new IntKnob("entity-tracker.player-update-interval", 1, 1, 100, KnobMeta.superseded(
            "paper-world.yml entity-tracking-range.*"));

    public static final BoolKnob SUP_AUTO_INSTALL =
        new BoolKnob("auto-install.enabled", true, KnobMeta.superseded(
            "removed with SWM in the 26.2 survival fork"));
}
```

Note: `KnobMeta.active()` with zero args is valid (varargs) — threshold sub-keys inherit context from the first key's comment in the rendered block.

- [ ] **Step 3: Compile**

Run: `./gradlew :sourbycraft-server:compileJava`
Expected: BUILD SUCCESSFUL. (`ConfigKeys` is not yet class-loaded at runtime — no behavior change. `Knobs` AI-throttle defaults changed 0/4 → 80/2, matching what `loadFromYml` produced from the jar, so post-boot values are identical.)

- [ ] **Step 4: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/config/ConfigKeys.java
git commit -m "feat(config): declare all sourbycraft.yml keys in registry

ConfigKeys covers every general key with EFFECTIVE defaults from the
dual-system survey (antixray.raytrace.enabled=false, sensor warmup=200).
Knobs AI-throttle declarations fixed to effective 80/2 (jar values won
over the stale 0/4 via loadFromYml). PG1 statuses: item.pool-* RESERVED,
20 keys SUPERSEDED with their Paper/moonrise equivalent recorded."
```

---

### Task 4: Rewire SourbyCraftConfig.init() through the registry

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/PerfKnob.java` (delete `loadFrom`)
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/BoolKnob.java`, `IntKnob.java`, `DoubleKnob.java`, `StringKnob.java`, `EnumKnob.java`, `StringListKnob.java`, `MapKnob.java` (delete `loadFrom` overrides)
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/KnobRegistry.java` (delete `loadAllFromYml`)
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/knob/Knobs.java` (delete `loadFromYml`)

**Interfaces:**
- Consumes: `ConfigRegistry.loadAll/find/snapshot`, `OperatorConfig.load/lookup/root`, all `ConfigKeys.*` and `Knobs.*` fields from Task 3.
- Produces:
  - `SourbyCraftConfig.operatorConfig()` → `dev.iyanz.sourbycraft.config.OperatorConfig` (Task 6's writer reads it)
  - `SourbyCraftConfig.config` (YamlConfiguration) still loaded — `SourbyCraftWorldConfig` depends on it
  - All public statics populated from registry; `ymlBool/ymlInt/ymlDouble/ymlGet/ymlStringList/ymlEntityTypeMap` unchanged signatures, now registry-first

- [ ] **Step 1: Delete the legacy jar-load path from the knob family**

Remove `abstract void loadFrom();` from `PerfKnob`, all seven `loadFrom` overrides, `KnobRegistry.loadAllFromYml`, and `Knobs.loadFromYml` + its javadoc. `Knobs.snapshot/logLoaded` stay.

- [ ] **Step 2: Registry-first delegation in `lookupYml`**

In `SourbyCraftConfig`, replace the `lookupYml` method body:

```java
    private static Object lookupYml(Map<String, Object> root, String dottedPath) {
        // CS1: registry-first. Registered keys answer from the live knob (atomic
        // volatile read — no cache needed); the jar-baked resource map only backs
        // keys unknown to the registry.
        dev.iyanz.sourbycraft.perf.knob.PerfKnob k =
            dev.iyanz.sourbycraft.config.ConfigRegistry.find(dottedPath);
        if (k != null) return k.snapshot();
        Object cached = LOOKUP_CACHE.get(dottedPath);
        if (cached != null) {
            return cached == SENTINEL_ABSENT ? null : cached;
        }
        Object cur = root;
        for (String seg : dottedPath.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) { cur = null; break; }
            cur = m.get(seg);
            if (cur == null) break;
        }
        LOOKUP_CACHE.put(dottedPath, cur == null ? SENTINEL_ABSENT : cur);
        return cur;
    }
```

- [ ] **Step 3: Replace `init(File)` wholesale**

Delete from `SourbyCraftConfig`: the `version`/`currentVersion` fields, `set(...)`, `getBoolean/getDouble/getInt/getList/getString/getComponent`, `cfgBool/cfgInt/cfgDouble`, `clamp`, the private reflective methods `detailedBrand()/adventure()/surfaceRules()/villagerGossip()` — but **keep the three `public static` fields** they fed (`detailedBrand`, `localizeItems`, `srPlaceInDefaultFluid`; they are read by patches — only the private loader methods go). Keep: `CONFIG_FILE`, `config`, `verbose`, `log(...)`, `readConfig(...)` overloads (SourbyCraftWorldConfig uses them), all `ymlXxx` accessors, `parseEntityTypeEntry`, `warnOnce`, `warnedKeysForTest`, all public statics.

Add a field + accessor and the new `init`:

```java
    private static dev.iyanz.sourbycraft.config.OperatorConfig operatorConfig;

    /** Raw operator-yml view captured at boot; YmlWriter renders from it (Task 6). */
    public static dev.iyanz.sourbycraft.config.OperatorConfig operatorConfig() { return operatorConfig; }

    public static void init(File configFile) {
        CONFIG_FILE = configFile;

        // CS1: raw registry load (snakeyaml SafeConstructor). Parse error aborts boot.
        operatorConfig = dev.iyanz.sourbycraft.config.OperatorConfig.load(configFile);

        // YamlConfiguration stays LOADED for SourbyCraftWorldConfig (world-settings.*),
        // but is never saved again — YmlWriter owns the file from Task 6 on.
        config = new YamlConfiguration();
        try {
            config.load(CONFIG_FILE);
        } catch (IOException e) {
            Bukkit.getLogger().warning("Could not load " + configFile.getName() + " into world-config view: " + e.getMessage());
        } catch (InvalidConfigurationException exception) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not load " + configFile.getName() + ", please correct your syntax errors", exception);
            throw new RuntimeException(exception);
        }

        // Force declaration class-init, then one registry load pass.
        dev.iyanz.sourbycraft.config.ConfigKeys.bootstrap();
        // Knobs class-inits via the ConfigKeys EnumKnob<CombatProfile> reference chain,
        // but touch it explicitly so declaration order never depends on javac layout.
        java.util.Objects.requireNonNull(dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE);
        dev.iyanz.sourbycraft.config.ConfigRegistry.loadAll(operatorConfig);

        verbose = dev.iyanz.sourbycraft.config.ConfigKeys.VERBOSE.get();

        // ---- static bridge: registry -> legacy public statics (NMS patches read these) ----
        detailedBrand = dev.iyanz.sourbycraft.config.ConfigKeys.DETAILED_BRAND.get();
        localizeItems = dev.iyanz.sourbycraft.config.ConfigKeys.TRANSLATE_ITEMS.get();
        srPlaceInDefaultFluid = dev.iyanz.sourbycraft.config.ConfigKeys.SURFACE_RULES_DEFAULT_FLUIDS.get();
        disableCommunicationCommands = dev.iyanz.sourbycraft.config.ConfigKeys.DISABLE_COMMUNICATION_COMMANDS.get();
        idleTimeout = dev.iyanz.sourbycraft.config.ConfigKeys.IDLE_TIMEOUT.get();

        virtualThreads = dev.iyanz.sourbycraft.config.ConfigKeys.VIRTUAL_THREADS.get();
        maxPlatformThreads = dev.iyanz.sourbycraft.config.ConfigKeys.MAX_PLATFORM_THREADS.get();
        chunkWorkers = dev.iyanz.sourbycraft.config.ConfigKeys.CHUNK_WORKERS.get();
        ioWorkers = dev.iyanz.sourbycraft.config.ConfigKeys.IO_WORKERS.get();
        asyncSpawning = dev.iyanz.sourbycraft.config.ConfigKeys.ASYNC_SPAWNING.get();

        autoThrottleView = dev.iyanz.sourbycraft.config.ConfigKeys.AUTO_THROTTLE_VIEW.get();
        minViewDistance = dev.iyanz.sourbycraft.config.ConfigKeys.MIN_VIEW_DISTANCE.get();
        compressionLevel = dev.iyanz.sourbycraft.config.ConfigKeys.COMPRESSION_LEVEL.get();
        maxChunkSendRate = dev.iyanz.sourbycraft.config.ConfigKeys.MAX_CHUNK_SEND_RATE.get();

        entityTickRateLimit = dev.iyanz.sourbycraft.config.ConfigKeys.ENTITY_TICK_RATE_LIMIT.get();
        hopperBatch = dev.iyanz.sourbycraft.config.ConfigKeys.HOPPER_BATCH.get();
        redstoneOptimize = dev.iyanz.sourbycraft.config.ConfigKeys.REDSTONE_OPTIMIZE.get();
        maxEntityPerChunk = dev.iyanz.sourbycraft.config.ConfigKeys.MAX_ENTITY_PER_CHUNK.get();
        maxSpecialsPerChunk = dev.iyanz.sourbycraft.config.ConfigKeys.MAX_SPECIALS_PER_CHUNK.get();
        maxFallingBlockPerChunk = dev.iyanz.sourbycraft.config.ConfigKeys.MAX_FALLING_BLOCK_PER_CHUNK.get();
        maxArrowsPerWorld = dev.iyanz.sourbycraft.config.ConfigKeys.MAX_ARROWS_PER_WORLD.get();
        maxRedstoneUpdatesPerTick = dev.iyanz.sourbycraft.config.ConfigKeys.MAX_REDSTONE_UPDATES_PER_TICK.get();
        itemMergeOptimize = dev.iyanz.sourbycraft.config.ConfigKeys.ITEM_MERGE_OPTIMIZE.get();
        itemDespawnRate = dev.iyanz.sourbycraft.config.ConfigKeys.ITEM_DESPAWN_RATE.get();
        itemMergeRadius = dev.iyanz.sourbycraft.config.ConfigKeys.ITEM_MERGE_RADIUS.get();
        mobTickDistance = dev.iyanz.sourbycraft.config.ConfigKeys.MOB_TICK_DISTANCE.get();
        mobPathfindInterval = dev.iyanz.sourbycraft.config.ConfigKeys.MOB_PATHFIND_INTERVAL.get();

        noDurabilityExcept = dev.iyanz.sourbycraft.config.ConfigKeys.NO_DURABILITY_EXCEPT.get();
        unlimitedDropStack = dev.iyanz.sourbycraft.config.ConfigKeys.UNLIMITED_DROP_STACK.get();
        dropStackCap = dev.iyanz.sourbycraft.config.ConfigKeys.DROP_STACK_CAP.get();
        ownerProtectionEnabled = dev.iyanz.sourbycraft.config.ConfigKeys.OWNER_PROTECTION_ENABLED.get();
        ownerProtectionTime = dev.iyanz.sourbycraft.config.ConfigKeys.OWNER_PROTECTION_TIME.get();
        itemPoolEnabled = dev.iyanz.sourbycraft.config.ConfigKeys.ITEM_POOL_ENABLED.get();
        itemPoolSize = dev.iyanz.sourbycraft.config.ConfigKeys.ITEM_POOL_SIZE.get();
        itemPoolMaxGrowth = dev.iyanz.sourbycraft.config.ConfigKeys.ITEM_POOL_MAX_GROWTH.get();
        itemPoolShrinkThreshold = (float) dev.iyanz.sourbycraft.config.ConfigKeys.ITEM_POOL_SHRINK_THRESHOLD.get();
        itemMaxPerChunk = dev.iyanz.sourbycraft.config.ConfigKeys.ITEM_MAX_PER_CHUNK.get();

        stackerEnabled = dev.iyanz.sourbycraft.config.ConfigKeys.STACKER_ENABLED.get();
        stackerRadius = dev.iyanz.sourbycraft.config.ConfigKeys.STACKER_RADIUS.get();
        stackerMaxStack = dev.iyanz.sourbycraft.config.ConfigKeys.STACKER_MAX_STACK.get();
        stackerHologram = dev.iyanz.sourbycraft.config.ConfigKeys.STACKER_HOLOGRAM.get();
        stackerLosCheck = dev.iyanz.sourbycraft.config.ConfigKeys.STACKER_LOS_CHECK.get();
        stackerBlacklist = dev.iyanz.sourbycraft.config.ConfigKeys.STACKER_BLACKLIST.get();

        fluidObscures = dev.iyanz.sourbycraft.config.ConfigKeys.ANTIXRAY_FLUID_OBSCURES.get();
        raytraceIntervalTicks = dev.iyanz.sourbycraft.config.ConfigKeys.ANTIXRAY_RAYTRACE_INTERVAL_TICKS.get();
        raytraceDistance = dev.iyanz.sourbycraft.config.ConfigKeys.ANTIXRAY_RAYTRACE_DISTANCE.get();
        raytraceMaxChecksPerCycle = dev.iyanz.sourbycraft.config.ConfigKeys.ANTIXRAY_RAYTRACE_MAX_CHECKS.get();
        raytraceMaxPendingPerPlayer = dev.iyanz.sourbycraft.config.ConfigKeys.ANTIXRAY_RAYTRACE_MAX_PENDING.get();
        raytraceCacheTtlTicks = dev.iyanz.sourbycraft.config.ConfigKeys.ANTIXRAY_RAYTRACE_CACHE_TTL.get();

        // superseded statics (loaded so the report can compare, drive nothing)
        multithreadingEnabled = dev.iyanz.sourbycraft.config.ConfigKeys.SUP_MULTITHREADING.get();
        asyncChunkLoad = dev.iyanz.sourbycraft.config.ConfigKeys.SUP_ASYNC_CHUNK_LOAD.get();
        asyncPathfinding = dev.iyanz.sourbycraft.config.ConfigKeys.SUP_ASYNC_PATHFINDING.get();
        structuredConcurrency = dev.iyanz.sourbycraft.config.ConfigKeys.SUP_STRUCTURED_CONCURRENCY.get();
        skipEmptySections = dev.iyanz.sourbycraft.config.ConfigKeys.SUP_SKIP_EMPTY_SECTIONS.get();
        poolEntityData = dev.iyanz.sourbycraft.config.ConfigKeys.SUP_POOL_ENTITY_DATA.get();
        preSizePackets = dev.iyanz.sourbycraft.config.ConfigKeys.SUP_PRE_SIZE_PACKETS.get();
        chunkCompressionCache = dev.iyanz.sourbycraft.config.ConfigKeys.SUP_CHUNK_COMPRESSION_CACHE.get();
        asyncSaveBatch = dev.iyanz.sourbycraft.config.ConfigKeys.SUP_ASYNC_SAVE_BATCH.get();

        // ---- engine bridges (order preserved from the pre-registry init) ----

        try {
            dev.iyanz.sourbycraft.perf.JvmHeapAdvisor.init();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("JvmHeapAdvisor.init failed", t);
        }

        try {
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.loadFromYml();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("PerfSensor.loadFromYml failed; using defaults", t);
        }

        try {
            dev.iyanz.sourbycraft.config.ConfigKeys.COMBAT_PROFILE.get().apply();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("CombatProfile.apply failed; using P0 defaults", t);
        }

        try {
            dev.iyanz.sourbycraft.antixray.RayTraceWorker.ENABLED.set(
                dev.iyanz.sourbycraft.config.ConfigKeys.ANTIXRAY_RAYTRACE_ENABLED.get());
            dev.iyanz.sourbycraft.antixray.EntityVisibilityCheck.ENABLED.set(
                dev.iyanz.sourbycraft.config.ConfigKeys.ANTIXRAY_ENTITY_RAYTRACE.get());
            dev.iyanz.sourbycraft.antixray.ParticleVisibilityCheck.ENABLED.set(
                dev.iyanz.sourbycraft.config.ConfigKeys.ANTIXRAY_PARTICLE_RAYTRACE.get());
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("RayTrace antixray toggle bridge failed", t);
        }

        // S5: max.bg.threads system property (see MAX_PLATFORM_THREADS caveat).
        if (maxPlatformThreads != 4 && System.getProperty("max.bg.threads") == null) {
            System.setProperty("max.bg.threads", String.valueOf(maxPlatformThreads));
            dev.iyanz.sourbycraft.util.SourbyLogger.info(
                "[SourbyCraft] set max.bg.threads=" + maxPlatformThreads
                + " from performance.max-platform-threads (note: Util.BACKGROUND_EXECUTOR already created)");
        }

        // Emoji shortcodes: registry map replaces defaults wholesale (default map == defaults).
        try {
            dev.iyanz.sourbycraft.chat.EmojiShortcodes.setEnabled(
                dev.iyanz.sourbycraft.config.ConfigKeys.EMOJI_ENABLED.get());
            java.util.Map<String, String> codes = new java.util.LinkedHashMap<>();
            dev.iyanz.sourbycraft.config.ConfigKeys.EMOJI_CODES.get()
                .forEach((k, v) -> { if (v != null) codes.put(k, v.toString()); });
            dev.iyanz.sourbycraft.chat.EmojiShortcodes.replaceAll(codes);
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("EmojiShortcodes init failed; defaults still active", t);
        }

        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of("plugins/SourbyCraft/speedtest")); } catch (java.io.IOException e) { Bukkit.getLogger().warning("Could not create speedtest directory: " + e.getMessage()); }

        // S5: bridge compression level to Paper's live engine (registry already clamped 0..9).
        if (compressionLevel != 4) {
            try {
                io.papermc.paper.configuration.GlobalConfiguration.get().misc.compressionLevel =
                    new io.papermc.paper.configuration.type.number.IntOr.Default(
                        java.util.OptionalInt.of(compressionLevel));
                dev.iyanz.sourbycraft.util.SourbyLogger.info(
                    "[SourbyCraft] network.compression-level=" + compressionLevel
                    + " bridged to Paper GlobalConfiguration.misc.compressionLevel");
            } catch (Throwable t) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                    "[SourbyCraft] compression bridge failed (GlobalConfiguration not ready?): "
                    + t.getMessage());
            }
        }

        // Pufferfish alias: force asyncSpawning false when PufferfishConfig disabled it.
        try {
            if (gg.pufferfish.pufferfish.PufferfishConfig.asyncMobSpawningInitialized
                    && !gg.pufferfish.pufferfish.PufferfishConfig.enableAsyncMobSpawning) {
                asyncSpawning = false;
                dev.iyanz.sourbycraft.util.SourbyLogger.info(
                    "[SourbyCraft] MT1 async-spawning forced false by PufferfishConfig.enableAsyncMobSpawning=false");
            }
        } catch (Throwable ignored) {
            // PufferfishConfig not yet initialized — asyncSpawning keeps its registry value.
        }

        // MT1 chunk-worker bridge (unchanged logic, values now come from the registry).
        String appliedWorkers;
        try {
            if (chunkWorkers > 0) {
                ca.spottedleaf.moonrise.common.util.MoonriseCommon.adjustWorkerThreads(chunkWorkers, ioWorkers);
                appliedWorkers = String.valueOf(chunkWorkers);
            } else {
                int paperWorkers = io.papermc.paper.configuration.GlobalConfiguration.get().chunkSystem.workerThreads;
                if (paperWorkers == -1) {
                    int smart = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
                    ca.spottedleaf.moonrise.common.util.MoonriseCommon.adjustWorkerThreads(smart, -1);
                    appliedWorkers = smart + " (smart-auto)";
                } else {
                    appliedWorkers = "auto skipped (Paper explicit workerThreads=" + paperWorkers + ")";
                }
            }
        } catch (Throwable t) {
            appliedWorkers = "bridge failed";
            dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                "[SourbyCraft] MT1 chunk-worker bridge failed (pool resize unsafe or not ready): " + t.getMessage());
        }
        if (maxChunkSendRate > 0.0) {
            try {
                io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkSendRate = maxChunkSendRate;
                dev.iyanz.sourbycraft.util.SourbyLogger.info(
                    "[SourbyCraft] MT1 network.max-chunk-send-rate=" + maxChunkSendRate
                    + " bridged to Paper GlobalConfiguration.chunkLoadingBasic.playerMaxChunkSendRate");
            } catch (Throwable t) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                    "[SourbyCraft] MT1 max-chunk-send-rate bridge failed: " + t.getMessage());
            }
        }
        dev.iyanz.sourbycraft.util.SourbyLogger.info(
            "[SourbyCraft] threads: chunk-workers=" + appliedWorkers
            + " io=" + ioWorkers
            + " send-rate=" + maxChunkSendRate
            + " async-spawning=" + asyncSpawning);

        // PerfSensor operator bridge — same call, values now from the registry.
        try {
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.applyOperatorConfig(
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_ENABLED.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_WARMUP_TICKS.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_CADENCE_TICKS.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_DWELL_SAMPLES.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_RECOVERY_MULTIPLIER.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_MSPT_YELLOW.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_MSPT_ORANGE.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_MSPT_RED.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_MSPT_EMERGENCY.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_TPS_YELLOW.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_TPS_ORANGE.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_TPS_RED.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_TPS_EMERGENCY.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_MEM_YELLOW.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_MEM_ORANGE.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_MEM_RED.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_MEM_EMERGENCY.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_GC_YELLOW.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_GC_ORANGE.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_GC_RED.get(),
                dev.iyanz.sourbycraft.config.ConfigKeys.SENSOR_GC_EMERGENCY.get()
            );
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("PerfSensor.applyOperatorConfig failed; using yml defaults", t);
        }

        // DAB entity overrides from the registry MapKnob.
        dabEntityOverrides.clear();
        dev.iyanz.sourbycraft.config.ConfigKeys.DAB_ENTITY_OVERRIDES.get().forEach((entityKey, rawVal) -> {
            if (rawVal instanceof Map<?, ?> m) {
                int freq = m.get("max-tick-freq") instanceof Number n ? n.intValue() : 20;
                int mod = m.get("activation-dist-mod") instanceof Number n ? n.intValue() : 8;
                dabEntityOverrides.put(entityKey, new int[]{freq, mod});
            }
        });

        // Item-pool RESERVED notice (registry status drives rendering; WARN kept verbatim).
        if (itemPoolEnabled) {
            Bukkit.getLogger().warning("[SourbyCraft] item.pool-enabled: true but the ItemEntityPool engine is offline "
                + "(removed for the levitation bug; keys reserved for pool v2). No pooling occurs.");
        }

        // S6 pvp.* fossil notice (operator files from the removed PvP variant).
        if (operatorConfig.lookup("pvp") != null) {
            Bukkit.getLogger().info("[SourbyCraft] pvp.* keys in sourbycraft.yml are from the removed PvP variant "
                + "and are ignored; combat tuning now lives in combat.profile (vanilla|balanced|pvp).");
        }

        // Legacy deprecation notice: dynamic-max-stack-size (v9 NBT codec mismatch).
        if (operatorConfig.lookup("dynamic-max-stack-size") != null || operatorConfig.lookup("item.max-stack-size") != null) {
            Bukkit.getLogger().warning(
                "[SourbyCraft] dynamic-max-stack-size (item.max-stack-size) is deprecated and ignored as of v9. " +
                "It caused ItemEntity NBT serialization failures (count > 99). " +
                "Wildstacker-style stacking will return via a separate patch in a future release."
            );
        }

        dev.iyanz.sourbycraft.perf.knob.Knobs.logLoaded();

        dev.iyanz.sourbycraft.util.VirtualExecutor.init();

        // S5 superseded-keys audit (registry-generated from Task 5 on).
        try {
            dev.iyanz.sourbycraft.perf.SupersededKeys.report(config);
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("SupersededKeys.report failed", t);
        }

        // Operator-file rendering lands in Task 6 (YmlWriter). Until then the
        // file is left untouched — no YamlConfiguration.save, ever again.
    }
```

Also fix the stale PG1 comment on the `entityTickRateLimit` field declaration — replace the v9.13 comment block with:

```java
    // PG1: gate for perf.entity-tick-rate. Wired in ActivationRange (S2): when true,
    // INACTIVE entities additionally skip ticks per the tick-rate knob. Default false.
    public static boolean entityTickRateLimit = false;
```

And delete the stale field-initializer comments that duplicated defaults now declared in ConfigKeys (the `// SourbyCraft v9.13 —` blocks above `itemMergeRadius` and `itemPoolEnabled` move into the ConfigKeys comments; keep the field declarations themselves).

- [ ] **Step 4: Compile + run existing config tests**

Run: `./gradlew :sourbycraft-server:compileJava`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :sourbycraft-server:test --tests "dev.iyanz.sourbycraft.*"`
Expected: existing `SourbyCraftConfigAccessorsTest` + `SourbyCraftConfigYmlGetTest` PASS. If a test asserts a jar-fallback value for a key now registered (registry answers with the same effective default), it still passes; if a test stubs the baseline map via reflection and now gets the registry value instead, adjust the TEST EXPECTATION only if the new value equals the documented effective default — never change a declaration to satisfy a stale expectation without checking the effective-defaults table in Global Constraints.

- [ ] **Step 5: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/
git commit -m "refactor(config): init() loads through ConfigRegistry

Operator yml parsed once via snakeyaml SafeConstructor; registry load
pass (alias resolve + type check + clamp) replaces ~40 inline getX
reads, three accessor families (getBoolean/cfgBool/ymlBool) collapse to
registry-first delegation. Public statics keep exact post-boot values
via one visible bridge block — NMS patches untouched. YamlConfiguration
stays load-only for world-settings.*; its saves are gone."
```

---

### Task 5: Registry-generated superseded report; delete SupersededKeys

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/config/ConfigRegistry.java`
- Delete: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/SupersededKeys.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` (swap the report call)

**Interfaces:**
- Consumes: `PerfKnob.meta().status()/supersededBy()`, `snapshot()`, `defaultValue()`, `OperatorConfig.lookup`.
- Produces: `ConfigRegistry.reportSuperseded(OperatorConfig op)` — one INFO summary + per-key WARN when the operator explicitly set a SUPERSEDED key to a non-default value.

- [ ] **Step 1: Add `reportSuperseded` to `ConfigRegistry`**

```java
    /**
     * PG1: boot report for SUPERSEDED keys, generated from declared metadata
     * (replaces the hand-written SupersededKeys class that drifted from
     * reality). One INFO summary naming every superseded key + its owner;
     * one WARN per key the operator explicitly set to a non-default value.
     */
    public static void reportSuperseded(OperatorConfig op) {
        StringBuilder info = new StringBuilder(
            "[SourbyCraft] Superseded config keys (loaded but drive no engine — behavior owned elsewhere):");
        boolean any = false;
        for (PerfKnob k : all()) {
            if (k.meta().status() != dev.iyanz.sourbycraft.perf.knob.KeyStatus.SUPERSEDED) continue;
            any = true;
            info.append(" ").append(k.key()).append(" -> ").append(k.meta().supersededBy()).append(";");
            boolean operatorSetIt = op.lookup(k.key()) != null;
            if (operatorSetIt && !String.valueOf(k.snapshot()).equals(String.valueOf(k.defaultValue()))) {
                SourbyLogger.warn("[SourbyCraft] " + k.key() + " is superseded and has no effect; "
                    + "re-apply your setting via: " + k.meta().supersededBy());
            }
        }
        if (any) SourbyLogger.info(info.toString());

        // performance.v9.* fossil block: section presence alone warrants a WARN
        // (no v9 fields were retained anywhere).
        if (op.lookup("performance.v9") != null) {
            SourbyLogger.warn("[SourbyCraft] performance.v9.* section detected in operator yml."
                + " These keys are fully superseded — moonrise owns async-lighting,"
                + " pathfinding and memory pools. Remove this section to silence this warning.");
        }
    }
```

Add the import for `KeyStatus` at the top of `ConfigRegistry` and use the short name in the loop.

- [ ] **Step 2: Swap the call in `SourbyCraftConfig.init()`**

Replace:

```java
        try {
            dev.iyanz.sourbycraft.perf.SupersededKeys.report(config);
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("SupersededKeys.report failed", t);
        }
```

with:

```java
        try {
            dev.iyanz.sourbycraft.config.ConfigRegistry.reportSuperseded(operatorConfig);
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("Superseded-key report failed", t);
        }
```

- [ ] **Step 3: Delete `SupersededKeys.java`**

```bash
git rm sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/SupersededKeys.java
```

- [ ] **Step 4: Compile**

Run: `./gradlew :sourbycraft-server:compileJava`
Expected: BUILD SUCCESSFUL (nothing else referenced SupersededKeys — verified by survey).

- [ ] **Step 5: Commit**

```bash
git add -A sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/
git commit -m "refactor(config): generate superseded-key report from registry

Hand-written SupersededKeys drifted (claimed entity.tick-rate-limit had
no consumer; it's wired in ActivationRange). Report now derives from
declared KeyStatus.SUPERSEDED metadata — one INFO summary, per-key WARN
only when the operator explicitly set a superseded key non-default."
```

---

### Task 6: YmlWriter — commented operator file, atomic write

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/config/YmlWriter.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java` (call writer at end of init)

**Interfaces:**
- Consumes: `ConfigRegistry.all()/find()`, `PerfKnob.key()/meta()/snapshot()`, `OperatorConfig.lookup()/root()`.
- Produces: `YmlWriter.write(java.io.File target, OperatorConfig op)` — renders and atomically replaces the operator file.

**Rendering rules (from spec):**
1. Header comment block + `config-version: 8` first.
2. ACTIVE keys always rendered (operator value if set, else default), declaration order, comments above each key.
3. SUPERSEDED/RESERVED keys rendered ONLY if present in the operator file, with a status annotation line prepended.
4. `world-settings.*` subtree preserved verbatim from the operator map (SourbyCraftWorldConfig owns it).
5. Unknown keys preserved under a trailing header.
6. Atomic write: temp file in same directory + `ATOMIC_MOVE` (fallback to `REPLACE_EXISTING` only).

- [ ] **Step 1: Create `YmlWriter.java`**

```java
package dev.iyanz.sourbycraft.config;

import dev.iyanz.sourbycraft.perf.knob.KeyStatus;
import dev.iyanz.sourbycraft.perf.knob.PerfKnob;
import dev.iyanz.sourbycraft.util.SourbyLogger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CS1 operator-file renderer. Deterministic output: header, config-version,
 * every ACTIVE key (with declared comments) in declaration order, then
 * operator-present SUPERSEDED/RESERVED keys (annotated), the preserved
 * world-settings.* subtree, and finally unrecognized operator keys.
 *
 * <p>YamlConfiguration.save is never used — this class owns the file.
 */
public final class YmlWriter {

    public static final int CONFIG_VERSION = 8;

    private YmlWriter() {}

    public static void write(File target, OperatorConfig op) {
        StringBuilder out = new StringBuilder(16 * 1024);
        out.append("# SourbyCraft configuration.\n");
        out.append("# This file is regenerated on every boot: values are preserved, comments are\n");
        out.append("# restored from the build's key declarations. A server restart applies changes.\n");
        out.append("\n");
        out.append("config-version: ").append(CONFIG_VERSION).append("\n");

        // --- pass 1: build the render tree for ACTIVE + operator-present other keys ---
        Map<String, Object> tree = new LinkedHashMap<>();
        Map<String, PerfKnob> leafOwners = new LinkedHashMap<>();
        for (PerfKnob k : ConfigRegistry.all()) {
            boolean operatorHasIt = op.lookup(k.key()) != null;
            if (k.meta().status() != KeyStatus.ACTIVE && !operatorHasIt) continue;
            putPath(tree, k.key(), k.snapshot());
            leafOwners.put(k.key(), k);
        }

        emit(out, tree, leafOwners, 0, new ArrayDeque<>());

        // --- world-settings.* preserved verbatim (SourbyCraftWorldConfig owns it) ---
        Object worldSettings = op.lookup("world-settings");
        if (worldSettings instanceof Map<?, ?> ws) {
            out.append("\n# Per-world overrides (managed by SourbyCraft world config; preserved as-is).\n");
            out.append("world-settings:\n");
            emitRaw(out, ws, 1);
        }

        // --- unknown operator keys preserved at the end ---
        StringBuilder unknown = new StringBuilder();
        collectUnknown(op.root(), "", unknown);
        if (unknown.length() > 0) {
            out.append("\n# --- keys not recognized by this build (preserved) ---\n");
            out.append(unknown);
        }

        atomicWrite(target, out.toString());
    }

    private static void putPath(Map<String, Object> tree, String dotted, Object value) {
        String[] segs = dotted.split("\\.");
        Map<String, Object> cur = tree;
        for (int i = 0; i < segs.length - 1; i++) {
            Object next = cur.get(segs[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(segs[i], next);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> nextMap = (Map<String, Object>) next;
            cur = nextMap;
        }
        cur.put(segs[segs.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private static void emit(StringBuilder out, Map<String, Object> node,
                             Map<String, PerfKnob> leafOwners, int depth, Deque<String> path) {
        String indent = "  ".repeat(depth);
        for (Map.Entry<String, Object> e : node.entrySet()) {
            path.addLast(e.getKey());
            String dotted = String.join(".", path);
            if (e.getValue() instanceof Map<?, ?> childRaw) {
                if (depth == 0) out.append("\n");
                out.append(indent).append(e.getKey()).append(":\n");
                emit(out, (Map<String, Object>) childRaw, leafOwners, depth + 1, path);
            } else {
                PerfKnob owner = leafOwners.get(dotted);
                if (owner != null) {
                    for (String line : owner.meta().comment()) {
                        out.append(indent).append("# ").append(line).append("\n");
                    }
                    if (owner.meta().status() == KeyStatus.SUPERSEDED) {
                        out.append(indent).append("# SUPERSEDED — no effect; behavior owned by: ")
                           .append(owner.meta().supersededBy()).append("\n");
                    } else if (owner.meta().status() == KeyStatus.RESERVED) {
                        out.append(indent).append("# RESERVED — parked for a future feature; no effect today.\n");
                    }
                }
                emitValue(out, indent, e.getKey(), e.getValue(), depth);
            }
            path.removeLast();
        }
    }

    /** Emits key: value, recursing for list/map values (MapKnob/StringListKnob snapshots). */
    private static void emitValue(StringBuilder out, String indent, String key, Object value, int depth) {
        if (value instanceof Map<?, ?> m) {
            out.append(indent).append(key).append(":");
            out.append(m.isEmpty() ? " {}\n" : "\n");
            emitRaw(out, m, depth + 1);
        } else if (value instanceof List<?> list) {
            out.append(indent).append(key).append(":");
            out.append(list.isEmpty() ? " []\n" : "\n");
            for (Object item : list) {
                out.append(indent).append("- ").append(scalar(item)).append("\n");
            }
        } else {
            out.append(indent).append(key).append(": ").append(scalar(value)).append("\n");
        }
    }

    private static void emitRaw(StringBuilder out, Map<?, ?> node, int depth) {
        String indent = "  ".repeat(depth);
        for (Map.Entry<?, ?> e : node.entrySet()) {
            emitValue(out, indent, String.valueOf(e.getKey()), e.getValue(), depth);
        }
    }

    /** Scalar serialization: quote strings that yml would misparse; raw otherwise. */
    private static String scalar(Object v) {
        if (v == null) return "~";
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        String s = v.toString();
        boolean needsQuote = s.isEmpty()
            || s.matches("(?i)true|false|null|~|yes|no|on|off")
            || s.matches("[-+]?[0-9.].*")
            || s.chars().anyMatch(c -> c == ':' || c == '#' || c == '\'' || c == '"'
                || c == '{' || c == '}' || c == '[' || c == ']' || c == '&' || c == '*'
                || c == '\n' || c == '\t')
            || s.startsWith(" ") || s.endsWith(" ") || s.startsWith("- ");
        return needsQuote ? "'" + s.replace("'", "''") + "'" : s;
    }

    /** Depth-first walk of the operator tree collecting paths unknown to the registry. */
    private static void collectUnknown(Map<String, Object> node, String prefix, StringBuilder out) {
        for (Map.Entry<String, Object> e : node.entrySet()) {
            String dotted = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (dotted.equals("config-version") || dotted.equals("world-settings")
                || dotted.startsWith("world-settings.")) continue;
            if (ConfigRegistry.find(dotted) != null) continue;
            if (e.getValue() instanceof Map<?, ?> m) {
                // Recurse: a branch is only "unknown" at its unknown leaves.
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                boolean hasKnownDescendant = child.keySet().stream()
                    .anyMatch(k -> subtreeHasKnownKey(dotted + "." + k, child.get(k)));
                if (hasKnownDescendant) {
                    collectUnknown(child, dotted, out);
                } else {
                    emitUnknownLeaf(out, dotted, e.getValue());
                }
            } else {
                emitUnknownLeaf(out, dotted, e.getValue());
            }
        }
    }

    private static boolean subtreeHasKnownKey(String dotted, Object value) {
        if (ConfigRegistry.find(dotted) != null) return true;
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (subtreeHasKnownKey(dotted + "." + e.getKey(), e.getValue())) return true;
            }
        }
        return false;
    }

    /** Unknown keys render as full nested blocks so operators can move them back. */
    private static void emitUnknownLeaf(StringBuilder out, String dotted, Object value) {
        String[] segs = dotted.split("\\.");
        for (int i = 0; i < segs.length - 1; i++) {
            out.append("  ".repeat(i)).append(segs[i]).append(":\n");
        }
        emitValue(out, "  ".repeat(segs.length - 1), segs[segs.length - 1], value, segs.length - 1);
    }

    private static void atomicWrite(File target, String content) {
        Path dir = target.getAbsoluteFile().getParentFile().toPath();
        try {
            Path tmp = Files.createTempFile(dir, "sourbycraft", ".yml.tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            SourbyLogger.error("Could not save " + target.getName()
                + " — server continues with in-memory config", e);
        }
    }
}
```

Known limitation (accepted in spec): unknown-key blocks with a shared nested parent re-emit the parent per leaf; YAML tolerates repeated parent keys ONLY when they don't collide at the same level in one document — since each unknown leaf renders its own full path chain and yaml merges duplicate mapping keys last-wins on reload, sibling unknown leaves under one parent must be grouped. `collectUnknown` recursion already handles the common case (whole unknown section renders once via `emitUnknownLeaf` receiving the branch map). Verify with the manual boot checklist in Task 7; if a real operator file produces duplicate top-level keys, group leaves by their first segment before emitting.

- [ ] **Step 2: Call the writer at the end of `init()`**

Replace the trailing comment block (`// Operator-file rendering lands in Task 6 ...`) with:

```java
        // CS1: render the operator file — commented, sectioned, deterministic.
        dev.iyanz.sourbycraft.config.YmlWriter.write(CONFIG_FILE, operatorConfig);
```

- [ ] **Step 3: Compile + tests**

Run: `./gradlew :sourbycraft-server:compileJava`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :sourbycraft-server:test --tests "dev.iyanz.sourbycraft.*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/
git commit -m "feat(config): commented operator yml rendered from registry

YmlWriter emits every ACTIVE key with its declared comment block in
declaration order; SUPERSEDED/RESERVED keys only when the operator
already has them (annotated); world-settings.* and unknown keys
preserved. Atomic temp+move write so a crash mid-save cannot truncate
the file. config-version 7 -> 8."
```

---

### Task 7: Full build + manual boot verification (operator)

**Files:** none (verification only)

- [ ] **Step 1: Full server-module build**

Run: `./gradlew :sourbycraft-server:build`
Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 2: Assemble the mojmap jar for the test harness**

Run: `./gradlew assembleReleaseArtifacts`
Expected: BUILD SUCCESSFUL; jar lands in `release/` with regenerated checksums.

- [ ] **Step 3: Hand off to operator — manual TestServer boot checklist**

The operator (Yan) boots `test-harness/TestServer-mojmap` manually (house rule: no automated boot assertions) and checks:

1. **Fresh install:** delete/rename any existing `sourbycraft.yml`, boot. Generated file must be commented + sectioned, contain `config-version: 8`, contain NO `memory.*`/`multithreading.*`/`entity-tracker.*`/`network.netty.*`/`item.pool-*` keys.
2. **Existing file:** boot with a pre-change 26.2 operator yml. All operator values must survive; `performance.wildstacker.*` aliases must seed `stacker.*`; superseded keys present in the file must reappear WITH the `# SUPERSEDED — ...` annotation; any custom keys must appear under the preserved-keys trailer.
3. **Snapshot parity:** compare the `perf knobs loaded [boot]:` log line against a pre-change boot with the same operator file — values must be identical (incl. `perf.ai.throttle-beyond-distance=80`, `perf.ai.throttle-tick-interval=2`).
4. **Superseded WARN:** set `multithreading.enabled: true`, reboot, expect the per-key WARN naming `paper-global.yml async-chunks`.
5. **Behavior spot-checks:** `/sparkview` works (spark.enabled), emoji shortcode translates in chat, item merge + despawn behave, no new WARN/ERROR spam in the boot log.
6. **Crash-safety:** file after boot is valid YAML (server re-boots cleanly a second time with the freshly rendered file).

- [ ] **Step 4: Final commit / release notes**

No code changes expected. If checklist items fail, fix forward on `release/26.2` and re-run the affected checklist item.

---

## Self-Review Notes

- **Spec coverage:** registry+metadata (T1-T3), single load path + statics bridge + ymlXxx delegation (T4), PG1 statuses + generated report + stale-comment fix (T3/T5), commented writer + atomic write + preservation + config-version 8 (T6), verification checklist (T7). Alias migrations replace ad-hoc version blocks (T3 declarations + T4 deletion). Hot-reload = flag only (KnobMeta.reloadable, T1) — matches spec non-goal.
- **Type consistency:** `applyRaw(Object)→boolean`, `snapshot()`, `defaultValue()`, `typeName()` declared T1, consumed T2/T5/T6. `ConfigKeys` field names used in T4 bridge match T3 declarations 1:1.
- **Known judgment calls recorded:** effective-defaults table (Global Constraints); `snapshot("perf.")` filter keeps log shape; `OperatorConfig` javadoc typo flagged inline in T2 Step 2.
