# Region watchdog switch

2026-10-06: user requested disabling the Folia diagnostic watchdog. Source change
is written in Minecraft feature patch
`0026-SourbyCraft-disable-region-watchdog-by-default.patch` and applied to the two
materialized Java files. No Codex build, test, boot or deployment was performed.

- [-] Acceptance: Claude must test the final built artifact; source alone is not boot evidence.

## Status update 2026-10-07 (what the log and the tree show now)

Sources: [AGENT-COORDINATION.md](../../AGENT-COORDINATION.md) (entries "patch directory incident",
"OWNER DECISION 2026-10-07", "compat-vs-performance follow-up") and the materialized tree. The
2026-10-06 paragraph above is the state when the patch was written; this section supersedes its
"no build, test or boot" wording.

- **Applied and committed (verified 2026-10-07).** 0026 is materialized commit `952f1e1` in
  `sourbycraft-server/src/minecraft/java` (log: "Codex has now committed ONLY the two watchdog files
  (952f1e1)"). The tree reads `Boolean.getBoolean("sourbycraft.region-watchdog.enabled")` in
  `FoliaWatchdogThread` and guards `start`, `addTick` and `removeTick`; `TickRegionScheduler` starts the
  thread only when enabled and creates `RunningTick` only when enabled at both call sites (patch
  `features/0026-...`, inspected 2026-10-07; `runningTick` is used nowhere else in that file). The patch
  file is still untracked in the root repository (`git status` 2026-10-07).
- **Built and booted, not verified for this flag.** 0026 sits under 0027 (`0569a90`) and 0028 (`ade3b24`) in the
  materialized history, so the demo jars built 2026-10-07 00:00 and 00:36 (log, owner-decision entry) were
  compiled with it, and Sourby Demo ran on them, including the `plugins-10` bench. The 2026-10-06 22:40 demo
  build most likely contains it too (the incident entry, ~22:35, says 0026/0027 were already materialized
  commits); the log does not record inspecting that jar. Those runs used the default (watchdog off): the demo
  panel cannot pass `-D` flags (log, "new-terrain chunk delivery on Sourby Demo"), so the enabled path was not
  run there.
- **Policy suite.** `scripts/test_independence_policy.py` flagged the 0026/0028 pair on 2026-10-07 (308 run, 2
  failures); with 0028 parked the Python suite is 327/327 (log), and the log attributes both failures to 0028,
  so 0026 passes the independence policy as it stands.
- **No unit test exists for the watchdog switch**, and no run of the existing test suites is attributed to this
  patch alone.

Acceptance cases below, item by item (2026-10-07): **1** partly: a jar containing 0026 booted and ticked with the
flag off, but no thread dump or other check that no `Folia Watchdog Thread` exists is recorded, nor are world
saves or clean stop for the flag-off case specifically. **2** not done (no `-Dsourbycraft.region-watchdog.enabled=true`
run, no controlled stall). **3** not done (reading the patch shows both disabled paths return early, which is not
a test). **4** not done (no controlled wrong-owner access recorded). **5** done: 0026 is applied and was rebuilt into
the demo jars; both `RunningTick` call sites are in the patch (inspected 2026-10-07).

Still unverified: that the flag-off JVM has no watchdog thread, that the flag-on path still prints the
diagnostics, and that disabling the diagnostic leaves guard, shutdown and bridge-quarantine behaviour unchanged.
`TODO.md` and `SPEC.md` §158 are owned by another agent and are not edited here.

## Behavior and lifecycle

`sourbycraft.region-watchdog.enabled` is a JVM system property, default **false**.
It is **RESTART_REQUIRED**, read when `FoliaWatchdogThread` initializes before the
region scheduler starts. It is independent of Aurora TOML, Canvas engine YAML,
and bridge SAFE/OFF mode. A config reload cannot change it for this JVM.

With the default, the scheduler does not start the watchdog thread, allocate its
per-tick RunningTick records, or retain active ticks in the watchdog set. Direct
manual starts exit immediately. No region/global stall dumps come from this
watchdog while disabled. The public watchdog object remains available for existing
engine callers; its add/remove methods return without retaining data when disabled.

The inspected watchdog only prints stall diagnostics; it has no server-stop or
restart action. Thread ownership checks, TickThread exceptions, bridge quarantine,
scheduler `regionFailed` behavior, shutdown and persistence paths remain active.
The Paper/Spigot watchdog is a separate implementation and is not changed here.
The user's SuperiorSkyblock `Cannot read world asynchronously` log comes from
TickThread ownership checks; disabling this diagnostic thread does not suppress it.

After building these sources, ordinary startup leaves the region watchdog off.
To explicitly disable or re-enable it, put the property **before `-jar`**:

```bash
# Off (also the new default)
java -Dsourbycraft.region-watchdog.enabled=false -jar server.jar --nogui

# Enable stall diagnostics again
java -Dsourbycraft.region-watchdog.enabled=true -jar server.jar --nogui
```

An existing released JAR does not gain this switch from the documentation; use a
build containing patch 0026. The value uses Java Boolean property semantics: only
case-insensitive `true` enables it; unset or other values are false.

## Claude acceptance — partly done (status per item in the 2026-10-07 section above)

1. New JVM without the property and with explicit false: no live `Folia Watchdog
   Thread`, no RunningTick registrations or diagnostic dumps. Confirm normal region
   and intermediate-task ticking, metrics, world saves and clean stop still work.
2. New JVM with true: watchdog thread starts; a controlled stalled tick produces
   the existing diagnostic behavior. Do not attribute any separate watchdog's stop
   behavior to this implementation.
3. Disabled add/remove calls and direct/manual start retain no tick records and
   produce no diagnostic loop. Repeated ticks cannot accumulate references in the set.
4. A controlled wrong-owner world access still raises the existing ownership error;
   scheduler failure handling and bridge violation/quarantine behavior remain active.
5. Reapply feature patches and rebuild the server; retain unrelated materialized
   edits and confirm both RunningTick call sites are covered by patch 0026.

These are proposed acceptance cases; only items 1 (partly) and 5 have recorded evidence as of 2026-10-07 (see
above). No performance,
deadlock-prevention, plugin-compatibility or release qualification is claimed.
