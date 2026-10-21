# sourbyapi

SourbyCraft's plugin API artifact — `dev.iyanz.sourbycraft:sourbyapi`. This is the module every
SourbyCraft plugin (and any Bukkit/Paper plugin running on SourbyCraft) compiles against.

## What this actually is

**`sourbyapi` republishes the Paper API surface under the SourbyCraft group id, plus the
region-threading API additions (formerly Canvas's API, now `dev.iyanz.sourbycraft.api.*` since
Build 48) and `dev.iyanz.sourbycraft.api.metrics`.** It is not a reimplementation of the upstream APIs. Its
`build.gradle.kts` (materialized from Paper's `paper-api/build.gradle.kts` by paperweight — see
`sourbyapi/build.gradle.kts.patch` and the `upstreams.paper { ... }` block in the root
`build.gradle.kts`) points its Gradle source sets at:

- `../paper-api/src/main/java` — upstream Paper's public API (`org.bukkit.*`, `io.papermc.paper.*`)
- `src/main/java` — SourbyCraft-owned API: the region-threading additions
  (`dev.iyanz.sourbycraft.api.{event,region,simd,world}`, `Unsupported`) and the read-only metrics
  service contract

Upstream classes retain their existing `org.bukkit` and `io.papermc.paper` package names. `paper-patches/files/` holds the changes to the *upstream Paper API sources* that used to come
from Canvas (region-threading API hooks, javadoc); they apply before the sources land in `paper-api/`.

What SourbyCraft *does* change is the packaging:

- **Group id**: published as `dev.iyanz.sourbycraft:sourbyapi` instead of `io.papermc.paper:paper-api`.
- **Automatic-Module-Name**: still `org.bukkit` — deliberately, so the module name plugins see on the
  module path is unchanged.
- **Capability aliases**: the jar declares the same Gradle module capabilities as
  `io.papermc.paper:paper-mojangapi`, `com.destroystokyo.paper:paper-api`,
  `org.spigotmc:spigot-api` and `org.bukkit:bukkit`, so it can substitute for any of those
  coordinates in a dependency graph without a conflict.

In short: **this is the branded upstream API surface plus SourbyCraft's metrics contract.** A plugin
built against `sourbyapi` compiles against the exact same `org.bukkit`/`io.papermc.paper` classes it
would against upstream Paper and may also consume the SourbyCraft metrics service.

## Breaking change in Build 48: the Canvas API package was removed

Owner decision 2026-10-21: the region-threading plugin API that SourbyCraft inherited from Canvas
moved from `io.canvasmc.canvas` to `dev.iyanz.sourbycraft.api`, and the old package was **deleted**.
There is no deprecated shim and no bridge. Class names, members, event semantics, handler lists and
enum constants are unchanged; only the package moved.

What this means for a plugin:

- **Compiled against the old package:** it fails to link on Build 48 (`NoClassDefFoundError` /
  `NoSuchMethodError` as soon as a class touching `io.canvasmc` loads, which for a listener class is
  usually at enable). It has to be rebuilt.
- **Rebuilding:** change the imports per the table below and compile against `sourbyapi` Build 48.
  Nothing else changes. The Bukkit entry points keep their names (`World#getRegionizer()`,
  `Server#unloadWorldAsync(..., Consumer<WorldUnloadResult>)`, `Server#createRegionizedData`,
  `Server#getLocalRegionizedData`, `Server#getRegionThreadingTickRateManager()`); their signatures
  now name the new types.
- **Not affected:** the `canvas-supported` / `folia-supported` keys in `plugin.yml` /
  `paper-plugin.yml` are a descriptor contract, not a Java package, and are still read
  (`PluginDescriptionFile`). Plugins using only Bukkit/Paper/Folia API are unaffected.

| Old class (removed) | New class |
| --- | --- |
| `io.canvasmc.canvas.Unsupported` | `dev.iyanz.sourbycraft.api.Unsupported` |
| `io.canvasmc.canvas.WorldUnloadResult` | `dev.iyanz.sourbycraft.api.world.WorldUnloadResult` |
| `io.canvasmc.canvas.event.EntityPortalAsyncEvent` | `dev.iyanz.sourbycraft.api.event.EntityPortalAsyncEvent` |
| `io.canvasmc.canvas.event.EntityPostPortalAsyncEvent` | `dev.iyanz.sourbycraft.api.event.EntityPostPortalAsyncEvent` |
| `io.canvasmc.canvas.event.EntityTeleportAsyncEvent` (and nested `TeleportType`) | `dev.iyanz.sourbycraft.api.event.EntityTeleportAsyncEvent` (and nested `TeleportType`) |
| `io.canvasmc.canvas.event.EntityPostTeleportAsyncEvent` | `dev.iyanz.sourbycraft.api.event.EntityPostTeleportAsyncEvent` |
| `io.canvasmc.canvas.event.PlayerRespawnAsyncEvent` | `dev.iyanz.sourbycraft.api.event.PlayerRespawnAsyncEvent` |
| `io.canvasmc.canvas.event.PlayerPostRespawnAsyncEvent` | `dev.iyanz.sourbycraft.api.event.PlayerPostRespawnAsyncEvent` |
| `io.canvasmc.canvas.event.PlayerSaveEvent` | `dev.iyanz.sourbycraft.api.event.PlayerSaveEvent` |
| `io.canvasmc.canvas.event.PlayerViewEndCreditsEvent` | `dev.iyanz.sourbycraft.api.event.PlayerViewEndCreditsEvent` |
| `io.canvasmc.canvas.event.world.WorldUnloadAsyncEvent` | `dev.iyanz.sourbycraft.api.event.world.WorldUnloadAsyncEvent` |
| `io.canvasmc.canvas.region.RegionThreadingTickManager` (and nested `RegionHandle`) | `dev.iyanz.sourbycraft.api.region.RegionThreadingTickManager` (and nested `RegionHandle`) |
| `io.canvasmc.canvas.region.RegionTickData` (and nested `Frame`, `IRegionizedData`, `IRegionizedData.IRegionizedCallback`) | `dev.iyanz.sourbycraft.api.region.RegionTickData` (same nested types) |
| `io.canvasmc.canvas.region.WorldRegionizer` (and nested `ChunkRegion`, `ChunkRegion.State`) | `dev.iyanz.sourbycraft.api.region.WorldRegionizer` (same nested types) |
| `io.canvasmc.canvas.simd.SIMDChecker` | `dev.iyanz.sourbycraft.api.simd.SIMDChecker` |
| `io.canvasmc.canvas.simd.SIMDDetection` | `dev.iyanz.sourbycraft.api.simd.SIMDDetection` |
| `io.canvasmc.canvas.simd.VectorMapPalette` | `dev.iyanz.sourbycraft.api.simd.VectorMapPalette` |

A mechanical rewrite is enough: `sed -e 's/io\.canvasmc\.canvas\.WorldUnloadResult/dev.iyanz.sourbycraft.api.world.WorldUnloadResult/g' -e 's/io\.canvasmc\.canvas/dev.iyanz.sourbycraft.api/g'`
over the plugin's sources (`WorldUnloadResult` is the one class that also gained a sub-package).

Why no shim: the owner chose a clean break over a deprecation window. For the record, a shim would
not have been free either — `WorldRegionizer.ChunkRegion#getState()` and `RegionTickData.Frame` return
or take nested enums, and a Java enum cannot extend another, so binary compatibility for those members
would have needed differently named methods or wrapper objects.

How the server is wired: the engine baseline (inherited Canvas/Folia patches) may not name a
SourbyCraft package (`scripts/baseline_policy.py`), so it names these types by simple name and three
SourbyCraft feature patches add the imports (Minecraft, Paper server, and this module's first API
feature patch, `paper-patches/features/0001-…`). `scripts/test_independence_policy.py` fails on any
`io.canvasmc` string in this module's sources, the server sources or any patch tree.

## Breaking change in Build 48: `ServerBuildInfo.BRAND_CANVAS_ID` was removed

Same owner decision (2026-10-21): `io.papermc.paper.ServerBuildInfo.BRAND_CANVAS_ID`
(`Key.key("canvasmc", "canvas")`) is **deleted**, with no compatibility constant. Use
`ServerBuildInfo.BRAND_SOURBYCRAFT_ID` (`Key.key("sourbycraft", "sourbycraft")`), which is the
`Brand-Id` a SourbyCraft server jar declares; `ServerBuildInfo.buildInfo().brandId()` returns it,
and `isBrandCompatible` accepts it, `BRAND_PAPER_ID` and `BRAND_FOLIA_ID` (unchanged). A plugin
compiled against the old constant fails with `NoSuchFieldError` when that code runs; a plugin that
compared `brandId()` with the Canvas key already got `false` on SourbyCraft. Source: this module's
second API feature patch, `paper-patches/features/0002-…`; pinned by `BrandIdTest`.

## Artifact coordinates

```
group:    dev.iyanz.sourbycraft
artifact: sourbyapi
version:  <releaseVersion>-<CHANNEL>   e.g. 26.2-REL with -PsourbyChannel=REL; 26.2-DEV on branch 26.2
```

`version` is computed at configuration time from the current git branch unless
`-PsourbyChannel=REL` is passed (used by CI for official `26.2` releases; see the root
`settings.gradle.kts` and `.github/workflows/build.yml`), matching the same `REL`/`DEV`/`EXP` channel suffix reported by `/ver` and the
auto-updater. No public Maven repository publishes this module: JitPack cannot build it because
settings evaluation requires the private SourbyPatcher toolchain. External consumers build it with
`./gradlew :sourbyapi:publishToMavenLocal` on a machine that has the pinned private toolchain
(see `docs/development/PRIVATE-TOOLCHAIN.md`).

The runtime `Bukkit.getBukkitVersion()` compatibility value is separate from this artifact
coordinate: `apiVersioning.json` reports `<apiVersion>-R0.1-SNAPSHOT` (currently
`26.2-R0.1-SNAPSHOT`). Plugins that parse Bukkit's API version need that format; the SourbyCraft
build channel remains in the artifact version and `/ver`.

## Consuming it

**From inside this repo** (e.g. `test-plugin`), reference the Gradle project directly:

```kotlin
dependencies {
    implementation(project(":sourbyapi"))
}
```

**From an external plugin project**, depend on the Maven Local artifact:

```kotlin
dependencies {
    compileOnly("dev.iyanz.sourbycraft:sourbyapi:26.2-REL")
}
```

Look up the read-only metrics service through Bukkit's existing `ServicesManager`:

```java
ServicesManager services = Bukkit.getServicesManager();
SourbyMetrics metrics = services.load(SourbyMetrics.class);
```

`SourbyMetrics` is the intentional process-wide read-only accessor. `snapshot().window(...)`
aggregates spatial region generations, while `snapshot().globalWindow(...)` returns the separately
measured global scheduler and does not affect active-region counts. Snapshots and their window values
are immutable and may be read safely from any thread.

### `api-version` in your plugin descriptor

The Bukkit API version a plugin declares in `paper-plugin.yml` / `plugin.yml` comes from the
`apiVersion` Gradle property in the root `gradle.properties`, currently:

```
apiVersion=26.2
```

so a plugin's descriptor should declare:

```yaml
api-version: '26.2'
```

(`test-plugin/src/main/resources/paper-plugin.yml` does exactly this, substituting
`${api_version}` at build time from the same property.)

## Directory contents

- `build.gradle.kts` / `build.gradle.kts.patch` — the paperweight-materialized build script + the
  patch that produced it from Paper's `paper-api/build.gradle.kts`.
- `paper-patches/` — patches applied to the upstream `paper-api` sources before they land in the
  sibling `paper-api/` project that this module's source set reads from.
- `src/main/java/dev/iyanz/sourbycraft/api/{event,region,simd,world}`, `api/Unsupported.java` —
  region-threading API additions (formerly Canvas's API; see *Breaking change in Build 48*).
- `src/main/java/dev/iyanz/sourbycraft/api/metrics/` — SourbyCraft's public read-only metrics API.
- `src/test/` — API contract tests.
