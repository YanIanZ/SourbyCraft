# SourbyMod — Native Mod Format for SourbyCraft

SourbyCraft ships ML1: a first-party native mod loader for `mods/*.jar`.

**Important:** SourbyCraft is a Paper fork. Fabric and Forge mods use different
mappings, mixin/classloading models, and lifecycles — they **cannot and will not
load here**. ML1 is a separate, lightweight format for server-side extensions that
want full NMS + Bukkit API access and an early-boot lifecycle.

---

## Mod jar format

A SourbyMod is any `.jar` placed in `mods/` that contains a `sourbymod.yml` at
the jar root.

### sourbymod.yml fields

| Field     | Required | Description |
|-----------|----------|-------------|
| `id`      | yes      | Unique mod identifier. Pattern: `[a-z0-9-_]{1,32}`. |
| `name`    | no       | Human-readable display name. Defaults to `id`. |
| `version` | yes      | Arbitrary version string (e.g. `1.0.0`); a YAML number such as `1.0` is accepted too. |
| `main`    | yes      | Fully-qualified class that implements `dev.iyanz.sourbycraft.mod.SourbyMod`. |
| `api`     | yes      | SourbyMod API generation (integer). Currently `1`. Loader rejects values newer than it knows. |

### Minimal example

```yaml
id: example-mod
name: Example Mod
version: 1.0.0
main: com.example.ExampleMod
api: 1
```

---

## Implementing SourbyMod

```java
package com.example;

import dev.iyanz.sourbycraft.mod.ModContext;
import dev.iyanz.sourbycraft.mod.SourbyMod;

public class ExampleMod implements SourbyMod {

    @Override
    public void onLoad(ModContext ctx) {
        // Called at bootstrap — BEFORE world load and Bukkit plugin enabling.
        // Safe: SourbyCraft config, class init, the mod's data directory.
        // NOT safe: Bukkit API, worlds, schedulers.
        ctx.logger().info("ExampleMod loading (data dir: " + ctx.dataDirectory() + ")");
    }

    @Override
    public void onEnable() {
        // Called on ServerLoadEvent(STARTUP): worlds loaded, plugins enabled.
        // World, chunk and entity work still goes through the region, entity or
        // global schedulers (see "Region threading" below).
    }

    @Override
    public void onDisable() {
        // Called at shutdown, only if onEnable ran. Undo what onEnable started.
    }

    @Override
    public void onUnload() {
        // Release what onLoad acquired; called even if the server never became ready.
    }
}
```

The interface lives in the server (`dev.iyanz.sourbycraft.mod`), not in the
[`sourbyapi`](../sourbyapi/README.md) API artifact, so a mod compiles against the server's classes.

---

## ModContext API

`ModContext` is passed to `onLoad`. Methods:

| Method | Returns | Description |
|--------|---------|-------------|
| `modId()` | `String` | Mod id as declared in `sourbymod.yml`. |
| `name()` | `String` | Display name as declared in `sourbymod.yml` (defaults to `id`). |
| `version()` | `String` | Mod version string. |
| `dataDirectory()` | `java.nio.file.Path` | `mods/<id>/`, created lazily on first call. |
| `logger()` | `java.util.logging.Logger` | Logger prefixed `SourbyMod/<id>`. |

---

## Lifecycle order

```
DedicatedServer.initServer (after Paper's command registration, before plugins load)
  └─ SourbyCraftBootstrap.init()
       ├─ stage "configuration"   SourbyCraftConfig.init()
       └─ stage "sourby mods"     ModLoader.bootstrap()
            ← scans mods/ in file-name order, then every onLoad()
              (before world load, before Bukkit plugins load)

ServerLoadEvent(STARTUP)
  └─ ModLoader.enableAll()        ← every onEnable(), in load order
       (worlds loaded and plugins enabled)

Server stop: plugins disabled, their async tasks drained, then
SourbyCraftBootstrap.close()      ← runtime state STOPPING; mods first, before the other services
  └─ ModLoader.shutdown()         ← per mod, in reverse load order:
                                    onDisable() (only if enableAll() ran), then onUnload();
                                    then the classloaders close
```

`onLoad` runs once per process. `onEnable` runs once, when the server becomes ready; `onDisable`
runs at shutdown only for a mod that was enabled. `onUnload` runs once per process for every mod
whose `onLoad` returned normally, also when the server stopped before it was ready (then
`onDisable` is skipped). A failed `onLoad` disables only that mod; a failed `onEnable`,
`onDisable` or `onUnload` is logged and the server and the other mods continue. There is no
plugin-style reload: changing a jar in `mods/` needs a server restart.

`onUnload` and the file-name load order are PR #62 changes: code written and statically reviewed;
not built; not tested by the owner. `onUnload` is a default method, so existing `api: 1` mods load
unchanged and the API generation stays `1`; a mod therefore cannot require it (whether to bump the
generation is an owner decision).

### What the server is doing at shutdown

When `onDisable` and `onUnload` run, Bukkit plugins are already disabled (feature patch 0081 calls
`SourbyCraftBootstrap.close()` after `disablePlugins()` and the async-task drain) and the runtime
state is already `STOPPING`. Every admission point that checks `AuroraRuntime.stopping()` refuses
new work from then on: `/awf`, `/speedtest`, `/update` check and apply, `/sourbycraft reload`,
async-path offload and the GeoIP download. `AuroraWorlds.unload(name, true)` skips its pre-save
while stopping and normally completes with `SERVER_STOPPING`. Persistence is deliberately not
gated: the storage lane, AWF commits and region saves keep draining. Do not start new Bukkit work
in either method.

## Region threading

SourbyCraft is region-threaded: a world, chunk or entity may only be touched by the thread that
owns its region. `onEnable` runs inside the server-ready event, not as a task of any region, so the
same rules as for a Folia-compatible plugin apply there and later:

- world, chunk and block work: `Bukkit.getRegionScheduler()` for the location's region;
- one entity or player: `entity.getScheduler()`, which follows the entity between regions;
- global state (time, weather, server-wide work): `Bukkit.getGlobalRegionScheduler()`;
- blocking I/O: an executor of the mod's own, never a region or the global tick.

These schedulers take a `Plugin` owner, and ML1 gives a mod none. The only always-enabled owner in
the server is `dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin.INSTANCE`, which is
server-internal, not part of a published API; the alternative is a companion Bukkit plugin that
owns the scheduled work. Mods are not Bukkit plugins, so the Aurora Bridge, which reroutes some
calls of admitted legacy plugins, does not apply to them.

---

## Classpath and visibility

Each mod gets its own `URLClassLoader` (parent = server classloader). Mods can see:

- NMS classes (net.minecraft.*)
- Bukkit / Paper API (org.bukkit.*, io.papermc.*)
- All SourbyCraft first-party APIs (dev.iyanz.sourbycraft.*)

**No cross-mod classpath in ML1.** There is no dependency graph — mod A cannot
declare a dependency on mod B. If two mods need to share code, ship it in both
jars or expose it via a Bukkit plugin that both can service-lookup.

---

## Loader behaviour

- Order: jars are processed in file-name order (Java `String` order, so case-sensitive: `B.jar`
  before `a.jar`) on every host. Load, enable and (reversed) shutdown order follow it.
- Duplicate `id` (two jars with the same id): the jar whose file name sorts first wins; the other
  is **skipped** with WARN.
- `api` newer than the loader knows (`api > 1` currently): jar is **skipped** with WARN.
- `onLoad` throws: that mod is **disabled** with WARN; other mods and the server continue normally.
- Main class missing or does not implement `SourbyMod`: jar is **skipped** with WARN.
- Jars with no `sourbymod.yml` (Fabric, Forge, random jars): logged with a WARN explaining why they are ignored.
- Zero mods in `mods/`: single INFO line `mods: (0 loaded)`, no scanning overhead beyond one directory listing.

---

## Security

Mods are arbitrary code running **in-process with full server privileges** — the
same trust level as plugins. The loader adds no sandbox; none is possible
in-JVM. Only deploy mods from sources you trust.

Descriptor parsing (`sourbymod.yml`) uses SnakeYAML `SafeConstructor` — YAML
gadget RCE via `!!` tags is blocked.
