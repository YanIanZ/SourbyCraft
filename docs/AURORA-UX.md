# Aurora UX — SourbyCraft Operator Experience

## Goal

Aurora must feel immediately different from a generic Paper/Canvas fork while remaining fast, readable, and operationally useful.

The UX target is **high-impact, cinematic, energetic, and unmistakably SourbyCraft** without turning the server console or commands into visual noise.

Aurora UX is not decoration layered on top of the engine. It is a presentation system for the engine's real state.

Core rule:

> Every visual effect must communicate useful state, complete quickly, and have negligible runtime cost.

---

## 1. Design language

Aurora uses four visual states consistently:

- **AURORA / cyan-blue** — SourbyCraft identity, normal engine state
- **HEALTHY / green** — stable, within budget
- **PRESSURE / gold-yellow** — approaching operational limits
- **CRITICAL / red** — genuine performance or stability concern

Use gradients sparingly for headers, bars, and major transitions. Do not gradient every line of output.

Preferred feel:

```text
premium terminal
+ game-engine telemetry
+ cinematic status transitions
+ very fast interaction
```

Avoid:

```text
rainbow spam
20-line ASCII art on every command
per-tick title animations
particle/sound spam
fake percentages
```

---

## 2. Aurora boot experience

Startup should have a distinctive but short sequence.

Example:

```text
╭────────────────────────────────────────────────────╮
│              SOURBYCRAFT · AURORA                 │
│        Java 25 · Minecraft 26.2 · Build44         │
╰────────────────────────────────────────────────────╯

[Aurora] Initializing runtime core...
[Aurora] Region engine ............... READY
[Aurora] Telemetry ................... READY
[Aurora] Diagnostics ................. READY
[Aurora] Spark bridge ................ READY
[Aurora] Configuration ............... READY

[Aurora] Engine online in 3.82s
```

Requirements:

- no artificial sleeps
- no fake loading animation
- each READY state corresponds to actual initialization
- failed optional systems display DEGRADED rather than pretending success
- startup timings come from real monotonic measurements

Optional ANSI color may be used only where the console supports it safely.

---

## 3. `/perf` becomes the hero command

`/perf` should feel like opening Aurora's control center.

Example compact view:

```text
╭─ SOURBYCRAFT · AURORA PERFORMANCE ───────────────╮
│ HEALTH      EXCELLENT                            │
│ TPS         20.00        MSPT       7.84 ms      │
│ P95         11.42 ms     P99       16.21 ms      │
│ CPU         36%          RAM       4.1 / 8.0 GB  │
│ REGIONS     42 active    WORST     18.7 ms       │
│ PLAYERS     86           UPTIME    7h 21m        │
╰───────────────────────────────────────────────────╯

[REGIONS] [MEMORY] [NETWORK] [SCHEDULER] [PROFILE]
```

The buttons should be clickable Adventure components where available.

Hover text can expose detail without bloating the main output.

---

## 4. Performance health transitions

Aurora should make state changes noticeable.

Example state transition:

```text
AURORA HEALTH  EXCELLENT → PRESSURE
Cause: world region 18,-7 reached p95 44.3 ms
```

When recovered:

```text
AURORA HEALTH  PRESSURE → HEALTHY
Region 18,-7 recovered below warning threshold.
```

Rules:

- transition notifications are rate-limited
- no repeated alert every second
- thresholds are explicit/read-only operator settings
- no automatic tuning is triggered by an alert

---

## 5. `/perf health`

Make health diagnosis visually strong but factual.

```text
⚡ AURORA HEALTH REPORT

✓ Tick Engine       EXCELLENT   8.1 ms p95
✓ Memory            HEALTHY     51%
✓ GC                EXCELLENT   0.7% overhead
! Region Engine     PRESSURE    1 hot region
✓ Network           HEALTHY
✓ Scheduler         HEALTHY

Primary pressure point
→ world / region 18,-7
  MSPT p95 44.3 ms
  317 entities · 21 ticking chunks

[INSPECT REGION]  [START PROFILE]
```

Do not claim a root cause unless telemetry actually supports it.

---

## 6. HUD design

Aurora HUD should look alive but remain cheap.

### `/perfbar`

Recommended default:

```text
AURORA  TPS 20.0  │  8.2 ms  │  RAM 51%  │  CPU 36%
```

Bossbar progress should represent the most meaningful active pressure metric rather than decorative animation.

Examples:

- normal: tick-budget headroom
- memory view: heap utilization
- region view: worst-region tick-budget utilization

Refresh target: once per second.

No per-player metric recomputation.

---

## 7. Actionbar pulse mode

Optional command:

```text
/perfbar mode pulse
```

Example actionbar:

```text
⚡ AURORA · 20.0 TPS · 8.2ms · CPU 36% · RAM 51%
```

Use a single shared snapshot.

No animation faster than operationally useful refresh cadence.

---

## 8. Command transitions

Commands should reveal information progressively.

Examples:

```text
/perf
/perf region
/perf region world 18 -7
/perf player YanIanZ
```

Each deeper command should feel like drilling into the same control system, not switching to unrelated formatting.

All diagnostics share:

- same header language
- same status names
- same units
- same thresholds
- same metric source

---

## 9. Region heat presentation

Aurora should make region threading understandable to operators.

Example:

```text
AURORA REGION HEAT

#1 world       18,-7   ████████░░  41.3ms
#2 world       19,-7   ████░░░░░░  21.8ms
#3 world_nether 4,2    ███░░░░░░░  16.1ms
```

Bars are text rendering only; never infer data not collected.

Clickable region entries should run the corresponding `/perf region ...` command.

---

## 10. Profiler integration UX

Keep the upstream Spark web viewer for now.

Aurora enhances the in-server workflow around it.

Example start:

```text
⚡ AURORA PROFILER
Profiler backend: async-profiler
Scope: server
Status: RECORDING

[STOP & UPLOAD] [STATUS]
```

Example finish:

```text
✓ Aurora profiling complete
Duration: 16m 15s
Backend: async-profiler
Platform: SourbyCraft
Version: Build44 (MC:26.2)

[OPEN PROFILE]
```

Do not rename the underlying Spark viewer engine enum while still using the upstream viewer.

---

## 11. Aurora incident experience

When an actual spike occurs, record a bounded incident entry.

Example:

```text
⚠ AURORA INCIDENT #184
P99 MSPT spike: 78.4 ms
World: world
Region: 18,-7
Players: 7
Entities: 412
GC during window: no
```

Then `/perf history` can show:

```text
18:42:09  REGION PRESSURE   world 18,-7  78.4ms
18:38:44  GC PAUSE          41ms
18:20:17  SCHEDULER QUEUE   62 pending
```

Incident storage must be bounded.

---

## 12. `/aurora`

Introduce an Aurora identity command only if it provides real functionality.

Suggested tree:

```text
/aurora
/aurora status
/aurora config
/aurora reload
/aurora version
```

`/aurora status` can summarize architecture state:

```text
Aurora Engine      ONLINE
Telemetry          ONLINE
Region metrics     ONLINE
Spark bridge       ONLINE
HUD service        ONLINE
Configuration      VALID
```

Do not duplicate `/perf` metrics unnecessarily.

---

## 13. Config UX

Configuration messages should clearly explain lifecycle.

Example reload:

```text
✓ Aurora configuration reloaded

Applied live:       14 values
Restart required:    3 values
Invalid:             0 values
```

If restart-required values changed:

```text
Restart required for:
- aurora.scheduler.worker-count
- aurora.network.native-transport
```

Never silently restart or modify unrelated settings.

---

## 14. Error UX

Errors should be high-signal.

Bad:

```text
An error occurred.
```

Preferred:

```text
✕ AURORA CONFIG ERROR
Key: aurora.entity.async-pathfinding
Value: "fast"
Expected: boolean
Runtime fallback: false
File was not modified.
```

For severe runtime conditions:

```text
✕ AURORA RUNTIME DEGRADED
Region metrics collector stopped unexpectedly.
Core gameplay is still running.
Use /aurora status for details.
```

---

## 15. Sound and title effects

For operator/player-facing diagnostic UX, sounds and titles are allowed only as optional UX features.

Recommended defaults:

- command sounds: OFF
- warning sounds: OFF
- title alerts: OFF
- bossbar diagnostics: opt-in/permission-based

This preserves professional production behavior while allowing server owners to enable a more dramatic experience.

No performance feature should depend on sounds, particles, or client animation.

---

## 16. Explosive does not mean expensive

Aurora should appear intense because of composition and state presentation, not because it performs extra work.

Preferred techniques:

- Adventure text components
- gradients on limited headers
- unicode separators
- hover events
- click events
- shared metric snapshots
- status transitions
- bounded incident history

Avoid:

- per-tick rendering
- repeated full-world scans
- constantly changing random colors
- animated console spam
- packet-heavy HUD updates

UX overhead target should remain negligible compared with telemetry itself.

---

## 17. Acceptance criteria

Aurora UX is complete enough for release when:

- startup visually identifies SourbyCraft/Aurora
- `/perf` has one coherent dashboard style
- `/perf health` presents actionable health state
- `/tpsbar`, `/rambar`, and `/perfbar` share one rendering system
- status transitions are rate-limited
- region diagnostics are visually understandable
- Spark workflow is integrated cleanly without requiring a custom viewer
- config reload shows live vs restart-required changes
- error messages identify exact keys/subsystems
- all displayed metrics come from authoritative Sourby telemetry
- UX adds no meaningful measurable runtime regression

---

## Product feel

The desired reaction is:

> "This does not feel like another Paper fork. It feels like a server engine with its own control system."

Aurora's visual impact should come from **clarity, speed, live engine state, and strong identity** — not visual spam.
