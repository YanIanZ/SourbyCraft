# Aurora Compatibility Bridge

Aurora Bridge adapts supported classes of legacy Paper/Spigot plugin behavior to SourbyCraft's region-threaded runtime.

## States
- NATIVE — declares Folia or SourbyCraft support.
- BRIDGED — legacy plugin successfully enabled through supported mappings.
- FAILED — load/enable/fatal bridge failure.
- DISABLED — operator-disabled.

## `/plugins` color contract
- Native: `#4DA3FF`
- Bridged: `#57D68D`
- Failed: `#FF5C70`
- Disabled: `#8B949E`

Green means “running through Aurora Bridge”; it never means official Folia support.

## Safety
Default mode is SAFE. Unknown/unsafe world mutation is rejected and diagnosed rather than run on an arbitrary thread.

## Routing
Entity-owned -> entity owner. Location-owned -> region owner. Global-safe -> global region. I/O -> plugin/I/O lane. Unknown unsafe -> reject.

## Telemetry
Per-plugin compatibility state, scheduler redirects, owner handoffs, rejected operations, fatal violations, quarantine, startup duration/cache state and last failure.

## Implementation status (26.2 branch)

- Implemented: `dev.iyanz.sourbycraft.bridge.CompatibilityState` (palette above) and
  `CompatibilityClassifier`, which encodes the qualification rule below. `/plugins` renders every
  loaded plugin by state and lists captured load failures in red. `PluginLoadDiagnostics` now also
  captures Paper's `Error occurred while enabling NAME vX` line, so a plugin that loaded but failed
  to enable shows FAILED instead of DISABLED.
- Not implemented: any adapter. The base (`canvas-server` patch `0002-Region-Threading`) refuses to
  load plugins that declare neither `folia-supported` nor `canvas-supported`; nothing sets
  `bridgeInitialized`, so BRIDGED cannot appear. Routing, SAFE-mode rejection, quarantine and the
  per-plugin telemetry listed above do not exist yet.
- An enabled plugin that does not declare support and has no bridge classifies as FAILED, never
  green: there is no evidence it is safe under region threading.

## Qualification
A plugin cannot be presented as BRIDGED until load, enable and bridge initialization succeed and no fatal compatibility violation is present.
