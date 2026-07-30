# Brand-version root patch (paper-server layer)

`paper-server/src/main/java/io/papermc/paper/ServerBuildInfoImpl.java` is a **materialized
(gitignored) weaver source**, so this change cannot live in a normal git commit — it must be
re-applied as a weaver file-patch on a clean `applyAllPatches`. Recorded here so it is never lost.

**Why:** every version-reporting surface (Folia watchdog `Version:` line, `CraftServer.getVersion()`
/ `Bukkit.getVersion()`, `getVersionMessage()`, crash reports, `level.dat`, bStats) bottoms out in
`ServerBuildInfoImpl.asString(...)`, which renders the raw upstream `26.2-DEV-<gitBranch>@<gitHash>
(buildTime)`. Branding it at this single root replaces the dev/commit string with
`SourbyCraft build 40c` everywhere at once. The helper it calls,
`dev.iyanz.sourbycraft.brand.BuildInfo.serverVersionString()`, IS tracked (committed in the
sourbycraft-server module).

**The edit** — prepend to the top of `asString(...)`:

```java
    @Override
    public @NotNull String asString(final @NotNull StringRepresentation representation) {
        // SourbyCraft: brand the version string at its single root. Every reporting surface bottoms out
        // here, so returning the branded id replaces the raw upstream "26.2-DEV-<branch>@<gitHash>
        // (buildTime)" everywhere with a clean "SourbyCraft build 40c".
        // Defensive: any failure falls through to the original upstream builder below.
        try {
            final String branded = dev.iyanz.sourbycraft.brand.BuildInfo.serverVersionString();
            if (branded != null && !branded.isEmpty()) {
                return branded;
            }
        } catch (final Throwable ignored) {
            // fall through to the upstream version builder
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(this.minecraftVersionId);
        // ... (original upstream body unchanged) ...
    }
```

Verified live on build 41c: boot log shows `Server version: v26_2_0 - 26.2.0 - folia SourbyCraft
build 40c (MC: 26.2)` — no `26.2-DEV-<hash>` anywhere.

To persist into the tracked patch set on a clean checkout: re-apply this edit to the materialized
`ServerBuildInfoImpl.java`, then run the weaver paper-server file-patch rebuild.
