# Class Data Sharing (CDS) — opt-in JVM startup speed

SourbyCraft does **not** ship an automatic CDS layer. The legacy Auto-CDS launcher
(`SourbyBootstrap` / `CdsEnvironment`) that detected the deployment environment and forked a
second JVM was dead code — the slim jar's launcher is SourbyClip, which never ran it — and has
been removed. Class Data Sharing is now purely an explicit JVM flag the operator adds.

CDS lets the JVM load class metadata from a memory-mapped archive instead of parsing it every
boot, cutting startup substantially. The archive is written to `cache/sourbycraft.jsa` on the
server data directory and self-heals when the jar or JDK changes.

## The one flag that always works

```
-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=cache/sourbycraft.jsa
```

`-XX:+AutoCreateSharedArchive` (JDK 19+) creates the archive on first clean shutdown, uses it
afterwards, and recreates it automatically when it goes stale — one flag, one JVM. Add it in
front of `-jar` in your launch command.

## Per-environment setup

### Docker

Already baked in. The reference `docker/entrypoint.sh` launches the server as PID 1 (`exec java …`)
with the CDS flag above and Aikar G1 tuning. The archive lives on the `/data` volume, so it
persists across `docker restart`.

### Pterodactyl / Pelican

Edit the server's **Startup** command (or the egg's `STARTUP`) and add the CDS flag in front of
`-jar`:

```
java -Xms${SERVER_MEMORY}M -Xmx${SERVER_MEMORY}M \
  -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=cache/sourbycraft.jsa \
  -jar {{SERVER_JARFILE}} --nogui
```

Wings tracks the single `java` PID → memory graph and Stop work correctly. Add the usual
[Aikar flags](https://docs.papermc.io/paper/aikar-flags) too for large servers.

### systemd service

Add the flag to your `ExecStart` for single-JVM CDS:

```
ExecStart=/usr/bin/java -Xms8G -Xmx8G -XX:+AutoCreateSharedArchive \
  -XX:SharedArchiveFile=cache/sourbycraft.jsa <aikar flags> -jar SourbyCraft-26.2-REL.jar --nogui
```

### Bare metal (shell / dev box)

```
java -Xms8G -Xmx8G -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=cache/sourbycraft.jsa \
  <aikar flags> -jar SourbyCraft-26.2-REL.jar --nogui
```

## Archive path override

The archive defaults to `cache/sourbycraft.jsa` relative to the working directory. The path is
controlled entirely by the flag above; there is no SourbyCraft property that changes it.

## Advanced: AOT cache (JDK 24+)

For the fastest cold start, Java 25's **AOT cache** (Project Leyden) also links classes ahead of
time. It is a two-step, operator-managed flow — SourbyCraft does not automate it:

```bash
# 1. record a training run, then create the cache
java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -jar SourbyCraft-26.2-REL.jar --nogui   # stop after Done
java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot -jar SourbyCraft-26.2-REL.jar
# 2. run with it
java -XX:AOTCache=app.aot <flags> -jar SourbyCraft-26.2-REL.jar --nogui
```
