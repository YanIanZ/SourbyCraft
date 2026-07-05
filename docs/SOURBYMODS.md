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
| `version` | yes      | Arbitrary version string (e.g. `1.0.0`). |
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
        // Safe: SourbyCraft config, Knobs, ModuleRegistry, class init.
        // NOT safe: Bukkit API, worlds, schedulers.
        ctx.logger().info("ExampleMod loading (data dir: " + ctx.dataDirectory() + ")");
    }

    @Override
    public void onEnable() {
        // Called when SourbyCraft's SWPlugin enables. Bukkit API is fully safe here.
    }

    @Override
    public void onDisable() {
        // Called on server shutdown. Clean up resources here.
    }
}
```

---

## ModContext API

`ModContext` is passed to `onLoad`. Methods:

| Method | Returns | Description |
|--------|---------|-------------|
| `modId()` | `String` | Mod id as declared in `sourbymod.yml`. |
| `version()` | `String` | Mod version string. |
| `dataDirectory()` | `java.nio.file.Path` | `mods/<id>/`, created lazily on first call. |
| `logger()` | `java.util.logging.Logger` | Logger prefixed `SourbyMod/<id>`. |
| `registerModule(SourbyModule)` | `void` | Enroll a `dev.iyanz.sourbycraft.core.SourbyModule` into MT1 ModuleRegistry. Its `enable(Plugin)` and `disable()` participate in the standard lifecycle alongside first-party SourbyCraft modules. |

---

## Lifecycle order

```
DedicatedServer.initServer
  └─ SourbyCraftSecurityConfig.init()
  └─ SourbyCraftConfig.init()
  └─ ModLoader.bootstrap()          ← all mod onLoad() calls happen here
       (before world load, before Bukkit plugins)

SWPlugin.onEnable
  └─ ModuleRegistry.enableAll()     ← mod onEnable() + first-party modules
       (Bukkit API fully available)

Server shutdown
  └─ ModuleRegistry.disableAll()    ← mod onDisable() in reverse order
```

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

- Duplicate `id` (two jars with the same id): second jar is **skipped** with WARN.
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
