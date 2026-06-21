# SourbyCraft v12 — NMS plugin operator smoke checklist

Manual checklist for verifying Citizens, NBTAPI, DecentHolograms, and
FastAsyncWorldEdit against a fresh SourbyCraft install. Use after each
SourbyCraft release. For automated coverage, run
`./gradlew :sourbycraft-server:nmsCompatTest -PrunNmsCompat=true`.

Single-jar shipping per 2026-06-04 spec revision. Reobf jar dropped.

## Setup

1. Download the jar from the release page: `SourbyCraft-v12-REL.jar` (mojmap).
2. Create a fresh server directory (`Smoke/`).
3. In the directory:
   - Copy the jar as `server.jar`.
   - Create `eula.txt` containing `eula=true`.
   - Create `plugins/`.
   - Download the latest 1.21.11-compatible builds of:
     - Citizens — https://ci.citizensnpcs.co/job/Citizens2/lastSuccessfulBuild/
     - NBTAPI — https://www.spigotmc.org/resources/nbtapi.7939/
     - DecentHolograms — https://www.spigotmc.org/resources/decentholograms.96927/
     - FastAsyncWorldEdit — https://ci.athion.net/job/FastAsyncWorldEdit/lastSuccessfulBuild/
   - Place all four jars in `plugins/`.

## Boot + verify

1. Start the server: `java -Xmx2G -jar server.jar nogui`.
2. Wait for `Done (XX.Xs)!` (expect under 60s on modern hardware after first-boot reobf remap; ~21s on warm cache).
3. Confirm no `FATAL` or `Caused by:` lines in the boot log.
4. In the console, run:

```text
/version Citizens
/version NBTAPI
/version DecentHolograms
/version FastAsyncWorldEdit
```
Each should print the plugin version + author + website. If any reports
"plugin not found" or shows an error, the jar is broken for that plugin.

5. Smoke-test commands:

```text
/npc create TestNPC               # Citizens — expect: "Created NPC ..." and a villager near you
/dh create test_holo Hello World  # DecentHolograms — expect: "Hologram created"
/fawe schem list                  # FAWE — expect: schematic list or "no schematics"
```

6. Shut down: `stop` in the console. Expect clean shutdown (no exception in log
   after `Stopping server`).

## What to report on failure

If any step fails, file an issue (or run
`./gradlew :sourbycraft-server:nmsCompatTest -PrunNmsCompat=true`
to capture machine-readable output). Include:

- Plugin name + version (`/version <name>`).
- Boot log excerpt (last 100 lines around the failure).
- `nms-compat-result.json` if present in the server's working directory.
