# sourbyapi

SourbyCraft's plugin API artifact — `dev.iyanz.sourbycraft:sourbyapi`. This is the module every
SourbyCraft plugin (and any Bukkit/Paper plugin running on SourbyCraft) compiles against.

## What this actually is

**`sourbyapi` republishes the Canvas/Paper API surface under the SourbyCraft group id and additionally
owns `dev.iyanz.sourbycraft.api.metrics`.** It is not a reimplementation of the upstream APIs. Its
`build.gradle.kts` (materialized from Canvas's own
`canvas-api/build.gradle.kts` by the weaver patcher — see `sourbyapi/build.gradle.kts.patch` and the
`upstreams.canvas { ... }` block in the root `build.gradle.kts`) points its Gradle source sets
directly at:

- `../paper-api/src/main/java` — upstream Paper's public API (`org.bukkit.*`, `io.papermc.paper.*`)
- `../canvas-api/src/main/java` — Canvas's additions on top of Paper's API
- `src/main/java` — SourbyCraft's read-only metrics service contract

Upstream classes retain their existing `org.bukkit`, `io.papermc.paper`, and Canvas package names.
The `paper-patches/` directory holds patches Canvas/SourbyCraft applies to the *upstream Paper API
sources* before they land in `paper-api/` (e.g. Javadoc/behavioral fixes); the metrics package is the
SourbyCraft-owned addition.

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

## Artifact coordinates

```
group:    dev.iyanz.sourbycraft
artifact: sourbyapi
version:  <releaseVersion>-<CHANNEL>   e.g. 26.2-REL on a release/* branch, 26.2-DEV otherwise
```

`version` is computed at configuration time from the current git branch (see the root
`settings.gradle.kts`), matching the same `REL`/`DEV`/`EXP` channel suffix reported by `/ver` and the
auto-updater. CI (`jitpack.yml`) builds and `publishToMavenLocal`s this module for external
consumption.

## Consuming it

**From inside this repo** (e.g. `test-plugin`), reference the Gradle project directly:

```kotlin
dependencies {
    implementation(project(":sourbyapi"))
}
```

**From an external plugin project**, depend on the published artifact:

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

- `build.gradle.kts` / `build.gradle.kts.patch` — the weaver-materialized build script + the patch
  that produced it from `canvas-api/build.gradle.kts`.
- `paper-patches/` — patches applied to the upstream `paper-api` sources before they land in the
  sibling `paper-api/` project that this module's source set reads from.
- `src/main/java/dev/iyanz/sourbycraft/api/metrics/` — SourbyCraft's public read-only metrics API.
- `src/test/` — API contract tests.
