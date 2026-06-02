# SourbyCraft v12.0-REL

**Tagline:** Lightning Fast Performance · Feature Rich

## Two JARs

- `SourbyCraft-v12-REL.jar` — general-purpose SMP (default)
- `SourbyCraft-PVP-v12-REL.jar` — PvP arena backend (Velocity-tuned, 1.8-style KB, allow-nether=false)

Both built from same codebase via `-Pvariant=normal|pvp`. PVP variant adds 5 PvP-only NMS patches (9001–9005), proxy backend defaults, plugin auto-installer manifest tuned for PvP, and distinct branding.

## Highlights

- Build-time variant split (`-Pvariant=pvp`)
- New PVP patches:
  - `9001-PVP-netty-tuning` — Netty event-loop + buffer sizes + max-packets-per-tick
  - `9002-PVP-entity-tracker-tightening` — tighter mob/item/xp ranges, faster player update
  - `9003-PVP-cpu-pin-gc-banner` — JVM advisory banner (ZGC/G1, Xms=Xmx, AlwaysPreTouch)
  - `9004-PVP-combat-completion` — sweep gate, hit-delay, /reach, fishing-rod KB
  - `9005-PVP-proxy-kick` — proxy-aware shutdown transfer-out + strict IP-forward
- Plugin auto-installer (variant-specific manifest)
- Reformatted `/plugins` + `/pl` — boxed, categorized, colored
- Compact startup plugin log
- Velocity/Bungee backend pre-tuning (PVP only)
- All config keys documented in baseline `sourbycraft.yml`

## New config keys (baseline)

```yaml
pvp:                       # extends v11.0 PvP block
  knockback:
    friction-divisor, vertical, extra-horizontal
network:
  proxy-mode, netty.{threads, snd-buf-kb, rcv-buf-kb, max-packets-per-tick}
  proxy-kick-grace-seconds, proxy-kick-fallback
entity-tracker:
  mob-range, item-range, xp-orb-range, player-update-interval
combat:
  sweep-enabled, hit-delay-ticks, hit-window-ms,
  fishing-rod-knockback, reach-debug-command
branding:
  motd-suffix, compact-plugin-list, compact-plugin-log,
  gc-advisor.enabled
auto-install:
  enabled
```

## Migration v11 → v12

- Single-jar v11 users: download `SourbyCraft-v12-REL.jar` (normal variant).
  Existing `sourbycraft.yml` preserved — variant overlay seeds only on fresh install.
- PvP-tuned v11 users: download `SourbyCraft-PVP-v12-REL.jar`. Operator may
  merge new defaults manually.

## Smoke checklist

See `docs/superpowers/specs/2026-06-02-sourbycraft-v12-smoke-checklist.md`.

## Build

```bash
./gradlew createMojmapPaperclipJar -Pvariant=normal
./gradlew createMojmapPaperclipJar -Pvariant=pvp
```

JAR outputs in `sourbycraft-server/build/libs/`.
