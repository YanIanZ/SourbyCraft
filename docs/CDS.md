# Auto-CDS — faster JVM startup, everywhere

SourbyCraft ships **Class Data Sharing (CDS)** so the JVM loads its class metadata
from a memory-mapped archive instead of parsing it every boot. Startup drops roughly
**30–50%**. The archive is written to `cache/sourbycraft.jsa` on the server data
directory and self-heals when the jar or JDK changes.

The bootstrap picks a strategy per environment instead of blindly forking a second
JVM — because forking is actively harmful in containers and process-managed panels.

## Why not just fork a helper JVM?

The old model launched a tiny orchestrator JVM that re-execs the real server with a
CDS flag. That is fine on a bare-metal shell, but under Docker / Pterodactyl / Pelican
it breaks two things:

1. **Double-committed heap → OOM kill.** Panel eggs set `-Xms = -Xmx` (e.g. `-Xms8G`).
   `-Xms` commits the full heap immediately. The orchestrator commits 8G *and* the
   forked server commits 8G → 16G against an 8G cgroup limit → the kernel OOM-kills
   the container.
2. **Wrong tracked process.** The panel/daemon watches the orchestrator PID, so the
   memory graph shows ~30 MB (the orchestrator, not the server) and stop signals
   target the wrong process.

So on containers and panels SourbyCraft **does not fork**. It boots inline and, once,
prints the exact single-JVM CDS flag to add. That flag gives the same speedup with
zero extra process and correct panel accounting.

## The one flag that always works

```
-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=cache/sourbycraft.jsa
```

`-XX:+AutoCreateSharedArchive` (JDK 19+) creates the archive on first clean shutdown,
uses it afterwards, and recreates it automatically when it goes stale — one flag, one
JVM. When you pass it yourself, the bootstrap detects it and stays out of the way.

## Per-environment setup

### Docker

Already baked in. The reference `Dockerfile` + `docker/entrypoint.sh` launch the
server as PID 1 (`exec java …`) with the CDS flag above and Aikar G1 tuning. The
archive lives on the `/data` volume, so it persists across `docker restart`.

```bash
./gradlew applyAllPatches :sourbycraft-server:compileJava assembleReleaseArtifacts
docker compose up -d --build
```

### Pterodactyl

Edit the server's **Startup** command (or the egg's `STARTUP`) and add the CDS flag
in front of `-jar`:

```
java -Xms${SERVER_MEMORY}M -Xmx${SERVER_MEMORY}M \
  -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=cache/sourbycraft.jsa \
  -jar {{SERVER_JARFILE}} --nogui
```

Wings tracks the single `java` PID → memory graph and Stop work correctly. Add the
usual [Aikar flags](https://docs.papermc.io/paper/aikar-flags) too for large servers.

### Pelican

Same as Pterodactyl — Pelican reuses the `P_SERVER_*` / `SERVER_MEMORY` egg variables.
Add the CDS flag to the Startup command in front of `-jar`. SourbyCraft detects a
Pelican/Pterodactyl environment and will remind you once in the console if it is
missing.

### Bare metal / systemd

Two options:

- **Zero-config:** just run `java -jar SourbyCraft-26.2-REL.jar`. With no committed
  `-Xms`, the bootstrap forks one child using `-XX:+AutoCreateSharedArchive` and
  manages it (console + Stop forwarded). Good for a dev box.
- **Recommended for production:** add the flag yourself so there is a single JVM:
  ```
  java -Xms8G -Xmx8G -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=cache/sourbycraft.jsa \
    <aikar flags> -jar SourbyCraft-26.2-REL.jar --nogui
  ```

## Modes + overrides

Control the layer with a system property or env var:

| `sourbycraft.cds.mode` / `$SOURBYCRAFT_CDS_MODE` | Behaviour |
|---|---|
| `auto` *(default)* | Fork only when safe (no committed `-Xms`, not a container/panel); otherwise boot inline + hint the flag. |
| `flag` | Never fork; always print the flag hint and boot inline. |
| `fork` | Always fork (legacy bare-metal behaviour). |
| `off` | No CDS layer at all. |

Archive path override: `-Dsourbycraft.cds.path=/abs/path.jsa` or
`$SOURBYCRAFT_CDS_PATH` (useful for read-only working dirs).

## Advanced: AOT cache (JDK 24+)

For the fastest cold start, Java 25's **AOT cache** (Project Leyden) also links classes
ahead of time. It is a two-step, operator-managed flow, so SourbyCraft does not
automate it — but if you pass `-XX:AOTCache=…` / `-XX:AOTMode=…` the bootstrap detects
it and leaves the archive entirely to the JVM:

```bash
# 1. record a training run, then create the cache
java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -jar SourbyCraft-26.2-REL.jar --nogui   # stop after Done
java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot -jar SourbyCraft-26.2-REL.jar
# 2. run with it
java -XX:AOTCache=app.aot <flags> -jar SourbyCraft-26.2-REL.jar --nogui
```
