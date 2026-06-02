# SourbyCraft v12.0 Smoke Checklist

## Normal jar
1. Drop `SourbyCraft-v12-REL.jar` into fresh server dir
2. Boot. Console expects:
   - Banner shows `Variant: NORMAL`
   - Log: `Loaded variant: NORMAL`
   - No GC advisor warning unless JVM args bad
3. `/ver` shows `Variant: NORMAL · "Lightning Fast Performance · Feature Rich"`
4. `/plugins` shows boxed format with `Lightning Fast · Feature Rich` header
5. `sourbycraft.yml` seeded with `pvp.enabled: false`
6. `server.properties` seeded with `allow-nether=true`, `online-mode=true`
7. `/stop` exits clean

## PVP jar
1. Drop `SourbyCraft-PVP-v12-REL.jar` into fresh server dir
2. Boot. Console expects:
   - Banner shows `Variant: PVP`
   - Log: `Loaded variant: PVP (pvp.enabled=true, view-dist=6, sim-dist=5)`
   - GC advisor warns if not ZGC/G1
   - WARN if `velocity.secret=CHANGE-ME-SEE-DOCS`
3. `/ver` shows `Variant: PVP`
4. `/reach` registered. After hitting dummy: `[reach] last hit: ... · 3.42 blocks · ...ms latency · window=150ms ✓`
5. `/sys` shows `Proxy: Velocity (modern forwarding) · secret OK` (or WARN if unchanged)
6. `sourbycraft.yml` seeded with `pvp.enabled: true`, `view-distance-cap: 6`
7. `server.properties` seeded with `allow-nether=false`, `online-mode=false`
8. `/stop`: 5s proxy-kick grace observed (if `network.proxy-mode` set)
9. `paper-global.yml` shows `velocity.enabled: true`

## Patch parity
- `bash scripts/verify-patch-parity.sh` → `OK`

## Build artifacts
- `release/SourbyCraft-v12-REL.jar` present
- `release/SourbyCraft-PVP-v12-REL.jar` present
- `release/checksums.txt` lists both
