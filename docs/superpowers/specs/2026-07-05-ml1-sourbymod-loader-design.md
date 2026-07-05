# ML1 — SourbyMod Loader (native modloader for mods/)

**Date:** 2026-07-05
**Status:** Approved (continuous execution authorized)
**Request:** "tambahkan modloader support" — make mods/ real.

## Honest scope statement

SourbyCraft is a Paper fork. Fabric/Forge mods use different mappings,
mixin/classloading models and lifecycles — they cannot and will not load here
(that is Mohist/Arclight-class work on a different architecture). ML1 ships a
**native SourbyMod loader**: server-side extension jars with a small first-party
API, loaded from `mods/` EARLIER than Bukkit plugins with full NMS visibility.
Jars without a SourbyMod descriptor keep the r47 "ignored" WARN, now worded to
say exactly why (probably a Fabric/Forge jar).

## Mod format

`mods/<anything>.jar` containing `sourbymod.yml`:

```yaml
id: example-mod          # [a-z0-9-_]{1,32}, unique
name: Example Mod
version: 1.0.0
main: com.example.ExampleMod   # implements dev.iyanz.sourbycraft.mod.SourbyMod
api: 1                   # SourbyMod API generation; loader rejects newer than it knows
```

## API (`dev.iyanz.sourbycraft.mod`)

```java
public interface SourbyMod {
    /** Bootstrap: after SourbyCraft config init, BEFORE world load and plugin load.
     *  Safe: config/Knobs/ModuleRegistry access, class init. NOT safe: Bukkit API, worlds. */
    void onLoad(ModContext ctx);
    /** Server started, plugins enabling (called via ModuleRegistry). Bukkit API safe. */
    default void onEnable() {}
    /** Server shutdown. */
    default void onDisable() {}
}
```

`ModContext`: mod id/version/dataDirectory (`mods/<id>/`), `Logger` (prefixed),
`registerModule(SourbyModule)` (enrolls into the MT1 ModuleRegistry so the
mod's runtime features get the same isolation/lifecycle as first-party ones).

## Loader semantics

- `ModLoader.bootstrap()` — nested one-line hook in `DedicatedServer.initServer`
  directly after `SourbyCraftSecurityConfig`/`SourbyCraftConfig.init` (~line 293):
  scan `mods/*.jar`, parse descriptors (SnakeYAML SafeConstructor — same
  hardening as security config), duplicate id → skip with WARN, `api` newer
  than supported → skip with WARN.
- One `URLClassLoader` PER MOD, parent = server classloader (mods see NMS +
  Bukkit + dev.iyanz API). No cross-mod classpath in v1 (no dependency graph —
  documented limitation).
- `onLoad` per mod in try/catch(Throwable) — a broken mod is skipped (server
  never dies from a mod).
- Loaded mods auto-enroll as ModuleRegistry modules (`mod:<id>`) so
  `onEnable`/`onDisable` ride the existing lifecycle + boot summary line.
- Boot INFO: `[SourbyCraft] mods: <id>@<version> ... (N loaded, M skipped)`;
  non-mod jars → the honesty WARN (reworded: "no sourbymod.yml — Fabric/Forge
  mods are not supported; SourbyMod format: docs/SOURBYMODS.md").
- Zero mods → zero cost beyond one directory listing at boot.

## Security note

Mods are arbitrary code running in-process with full server privileges — same
trust level as plugins. The loader adds no sandbox (none is possible in-JVM);
docs say so explicitly. Descriptor parsing uses SafeConstructor (no yaml
gadget RCE); jars are only loaded when the operator placed them in mods/.

## Files

- Outer: `mod/SourbyMod.java`, `mod/ModContext.java`, `mod/ModDescriptor.java`,
  `mod/ModLoader.java` (new); `SourbyCraftConfig.java` (replace r47 WARN block —
  delegate to ModLoader's report); `swm/plugin/SWPlugin.java` (nothing new —
  mods enrolled into ModuleRegistry during bootstrap are enabled by the
  existing enableAll), `docs/SOURBYMODS.md` (format + example + limits).
- Nested (1 feature patch): `DedicatedServer.java` one-liner
  `dev.iyanz.sourbycraft.mod.ModLoader.bootstrap();`.

## Verification (manual)

1. Boot with empty mods/ → single INFO `mods: (0 loaded)`; no WARN.
2. Drop a Fabric jar → WARN naming the jar, server boots clean.
3. Test mod jar (sourbymod.yml + class writing a log line in onLoad/onEnable)
   → both lines appear in the right phases; module summary shows `mod:example`.
4. Broken mod (main class missing) → WARN, other mods + server unaffected.
5. Shutdown → onDisable line.
