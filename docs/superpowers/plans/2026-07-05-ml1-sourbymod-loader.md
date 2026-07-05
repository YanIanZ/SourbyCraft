# ML1 SourbyMod Loader — Implementation Plan

Spec: docs/superpowers/specs/2026-07-05-ml1-sourbymod-loader-design.md
Repo: /Users/rheninxy/Sourby/SourbyCraft, branch release/26.1.2.
Task 1 = outer (API + loader). Task 2 = nested (1-line bootstrap hook). Task 3 = driver.

CRITICAL sequencing fact (verified):
- `DedicatedServer.initServer` line 293-294: SourbyCraftConfig.init then
  SourbyCraftSecurityConfig.init. ModLoader.bootstrap() goes right AFTER (line ~295),
  before world load + plugin load.
- `SWPlugin.onEnable` calls `ModuleRegistry.clear()` at the top (reload guard) THEN
  adds first-party modules THEN enableAll. Because clear() wipes everything, mod
  modules enrolled during bootstrap would be lost. SOLUTION: ModLoader keeps its own
  `List<LoadedMod>`; bootstrap does load + onLoad only (no ModuleRegistry.add).
  SWPlugin.onEnable, AFTER its first-party adds and BEFORE enableAll, calls
  `ModLoader.enrollInto()` which does `ModuleRegistry.add("mod:"+id, p -> mod.onEnable())`
  per loaded mod. This survives the clear() and gets onEnable/onDisable via the normal lifecycle.

## Task 1 (outer): API + loader

**New package `dev.iyanz.sourbycraft.mod`:**

- `SourbyMod.java`:

```java
package dev.iyanz.sourbycraft.mod;

/** A SourbyCraft server-side extension (native mod). Loaded from mods/ before plugins. */
public interface SourbyMod {
    /** After SourbyCraft config init, before world/plugin load. Config/Knobs/class-init safe; Bukkit API + worlds NOT ready. */
    void onLoad(ModContext ctx);
    /** Server started, enabling phase (via ModuleRegistry). Bukkit API safe. */
    default void onEnable() {}
    /** Server shutdown. */
    default void onDisable() {}
}
```

- `ModContext.java`: immutable; fields id, name, version, `java.nio.file.Path dataDirectory`
  (mods/<id>/, created lazily), `java.util.logging.Logger logger` (name "SourbyMod:"+id).
  Constructor package-private (only ModLoader builds it).

- `ModDescriptor.java`: parsed sourbymod.yml — id/name/version/main/api (int). Static
  `parse(InputStream)` using SnakeYAML SafeConstructor (mirror SourbyCraftSecurityConfig
  loader). Validate id regex `[a-z0-9_-]{1,32}`, main non-blank, api>=1. Return null +
  reason on invalid (loader logs).

- `ModLoader.java`:

```java
package dev.iyanz.sourbycraft.mod;
// static utility
public final class ModLoader {
    public static final int SUPPORTED_API = 1;
    private static final java.util.List<LoadedMod> LOADED = new java.util.ArrayList<>();
    private record LoadedMod(ModDescriptor desc, SourbyMod instance) {}

    /** Called once from DedicatedServer.initServer after config init. Scans mods/, loads, onLoad. */
    public static void bootstrap() {
        LOADED.clear();
        java.nio.file.Path dir = java.nio.file.Path.of("mods");
        try { java.nio.file.Files.createDirectories(dir); } catch (Exception e) { /* warn + return */ }
        // list *.jar; for each: open JarFile, read sourbymod.yml entry.
        //   no descriptor  -> nonModJars WARN list
        //   dup id / api>SUPPORTED_API / bad descriptor -> skip WARN
        //   else: URLClassLoader([jarUrl], ModLoader.class.getClassLoader());
        //         Class.forName(main, true, cl); require SourbyMod assignable; instantiate no-arg ctor;
        //         build ModContext; instance.onLoad(ctx) in try/catch(Throwable) -> skip on failure.
        //         LOADED.add(new LoadedMod(desc, instance));
        // Boot INFO: "[SourbyCraft] mods: <id>@<ver> ... (N loaded, M skipped)"
        // For nonMod jars: one WARN "<jar>: no sourbymod.yml — Fabric/Forge mods are not supported
        //   (SourbyMod format: docs/SOURBYMODS.md). Ignored."
    }

    /** Called from SWPlugin.onEnable AFTER first-party module adds, BEFORE enableAll. */
    public static void enrollInto() {
        for (LoadedMod m : LOADED) {
            dev.iyanz.sourbycraft.core.ModuleRegistry.add("mod:" + m.desc().id(), p -> m.instance().onEnable());
        }
        // Note: onDisable — register a shutdown path. Simplest: add a SourbyModule whose disable()
        // calls onDisable, OR rely on ModuleRegistry.disableAll already invoking module.disable().
        // Since ModuleRegistry.add(name, fn) builds a module with default disable() no-op, wrap
        // each mod as a full SourbyModule instead so disable() -> onDisable(). Use ModuleRegistry.add(SourbyModule).
    }
}
```

  REFINE enrollInto per the disable note: enroll a full anonymous `SourbyModule`
  (name `mod:<id>`, enable → onEnable, disable → onDisable) via `ModuleRegistry.add(SourbyModule)`
  so both lifecycle ends are covered by the existing enableAll/disableAll.

- Replace the r47 mods/ WARN block in `SourbyCraftConfig.init` (the
  `java.nio.file.Files.createDirectories(Path.of("mods"))` + jar-count WARN added in
  commit 0ce87d3): DELETE it — ModLoader.bootstrap now owns mods/ creation + reporting.
  (The other mods/ mkdir at ~line 451 inside the swm auto-install block can stay or go;
  prefer removing the duplicate since bootstrap creates it. Verify it's redundant.)

- `SWPlugin.onEnable`: after the last `ModuleRegistry.add(...)` first-party call and
  BEFORE `ModuleRegistry.enableAll(this)`, insert `dev.iyanz.sourbycraft.mod.ModLoader.enrollInto();`.

**docs/SOURBYMODS.md** (new, tracked normally): the sourbymod.yml format, the SourbyMod
interface, lifecycle phases, the "not Fabric/Forge" statement, the full-privilege security
note, and a minimal example mod.

**Steps:** compile `./gradlew :sourbycraft-server:compileJava -q` BUILD SUCCESSFUL →
outer commit EXACTLY:
`mod: ML1 SourbyMod loader — sourbymod.yml descriptor + per-mod classloader + ModuleRegistry lifecycle`
Report: .superpowers/sdd/ml1-task-1-report.md

## Task 2 (nested): bootstrap hook

`sourbycraft-server/src/minecraft/java/net/minecraft/server/dedicated/DedicatedServer.java`,
after line 294 (SourbyCraftSecurityConfig.init):

```java
        dev.iyanz.sourbycraft.mod.ModLoader.bootstrap(); // SourbyCraft - native mod loader (mods/)
```

Compile → nested commit EXACTLY:
`SourbyCraft ML1: ModLoader.bootstrap hook in DedicatedServer.initServer`
Report: .superpowers/sdd/ml1-task-2-report.md

## Task 3 (driver)

- rebuild patches → outer patch commit `mod: ML1 bootstrap hook (feature patch)`
- combined review (sonnet; focus: classloader leak/parent, descriptor parse hardening,
  clear()-vs-enroll sequencing, onLoad-too-early Bukkit misuse guard docs, dup-id, api gating,
  broken-mod isolation, security note present).
- After approval: build a tiny smoke mod jar in scratchpad to prove load path? (optional —
  project convention is manual boot; note it for operator instead.)
- artifact + ledger + user summary. NOTE: this is a NEW feature for the NEXT release
  (r48), not folded into the already-published r47.
