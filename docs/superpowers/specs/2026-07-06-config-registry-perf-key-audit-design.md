# CS1+PG1 — Unified Config Registry + Perf-Key Audit (Design)

**Date:** 2026-07-06
**Branch line:** release/26.2 (survival fork)
**Status:** Approved by operator (Yan), pending implementation plan

## Context / Problem

SourbyCraft config today is two parallel systems sharing one filename:

1. **Classpath-baked** `/sourbycraft.yml` resource, read via `SourbyCraftConfig.ymlBool/ymlInt/ymlGet`
   (static-init load, `LOOKUP_CACHE`, hot-path safe — 31 NMS call sites).
2. **Operator disk file** `sourbycraft.yml`, read via Bukkit `YamlConfiguration` in
   `SourbyCraftConfig.init()` — a ~380-line method mixing key reads, clamps, engine
   bridges (Paper GlobalConfiguration, Moonrise, Pufferfish), and migrations.

Three access patterns coexist (`getBoolean` persists default, `cfgBool` reads without
persisting, `ymlBool` reads classpath only). The generated operator file has zero
comments (YamlConfiguration strips them) — operators get a bare key dump. Version
migration is ad-hoc `if (version <= 3)` blocks. Several keys are dead
(superseded by Moonrise/Paper) and are only covered by the hand-written
`SupersededKeys` reporter, which drifts from reality (e.g. `entity.tick-rate-limit`
is documented as having "no consumer" but IS wired in `ActivationRange`).

This spec is the first of a decomposed roadmap:

- **CS1** typed key registry (this spec)
- **PG1** dead-key audit: per-key wire-or-retire verdicts (this spec)
- CS2 operator-UX polish, CS3 validation+migration hardening, CS4 hot-reload — later specs
- PG2 spark profile audit, PG3 fork-optimization ports, PG4 SelfTune expansion — later specs

## Goals

- One registry, one access pattern, one authoritative default per key.
- Generated operator `sourbycraft.yml` is fully commented, sectioned, deterministic.
- Every key carries declared metadata: type, default, clamp, comment, section,
  status, reload-safety, legacy aliases.
- Zero NMS patch churn: existing public statics and `ymlBool` call sites keep working.
- No new perf-feature code — PG1 formalizes status of existing keys only.

## Non-Goals

- Hot-reload behavior (CS4; only the `reloadable` flag exists in the model now).
- New performance features / ports (PG2/PG3).
- Item pool v2 (keys stay RESERVED).
- Rewriting jar-baked resource yml comments (file stays as fallback only).

## Architecture

New package `dev.iyanz.sourbycraft.config`:

| Unit | Purpose | Depends on |
|---|---|---|
| `ConfigRegistry` | THE registry: key map, alias index, warn-once, snapshot, load orchestration | knob classes |
| `OperatorConfig` | Loads operator yml via snakeyaml `SafeConstructor` into raw map; dotted-path lookup | snakeyaml |
| `YmlWriter` | Renders operator file from registry: sections, `#` comments, preservation rules; atomic write (temp + move) | ConfigRegistry |
| `ConfigKeys` | Declaration site for the ~70 general keys currently read inline in `init()` | knob classes |

Existing `dev.iyanz.sourbycraft.perf.knob` classes **stay at current FQCN**
(NMS patches reference `Knobs.*` directly; moving = pointless patch churn):

- `PerfKnob` (sealed base) gains metadata fields: `section`, `comment` (multi-line),
  `status` (`ACTIVE` / `SUPERSEDED` / `RESERVED`), `reloadable` (boolean, model-only
  for now), `aliases` (legacy dotted paths).
- Sealed permits list grows: `DoubleKnob`, `StringKnob`, `EnumKnob`,
  `StringListKnob`, `MapKnob` (covers `emoji.shortcodes.codes`,
  `dab.entity-overrides`, `stacker.blacklist`).
- `KnobRegistry` becomes a thin shim delegating to `ConfigRegistry` (kept so
  existing call sites — `Knobs.snapshot()`, `logLoaded()` — do not move).
- `Knobs` remains the declaration site for perf knobs; `ConfigKeys` holds the rest.

## Data Flow (boot)

`SourbyCraftConfig.init()` shrinks to orchestration:

1. Declaration classes load → all knobs self-register with **Java-code defaults
   as the single authoritative default source**. The jar-baked resource yml stays
   in the jar but only serves `ymlGet` fallback for keys not in the registry.
2. `OperatorConfig` loads the operator file (snakeyaml SafeConstructor raw map —
   same mechanism as today's `lookupYml`).
3. Per key, `ConfigRegistry.loadAll()`:
   - alias resolution: canonical path first, then declared legacy aliases
     (e.g. `performance.wildstacker.enabled` → `stacker.enabled`; replaces the
     ad-hoc S3 seeding block and `if (version <= N)` migrations),
   - type check → warn-once + keep default on mismatch,
   - clamp → warn-once (existing KnobRegistry pattern),
   - atomic value set.
4. **Static bridge block**: one visible section in `SourbyCraftConfig.init()`
   assigns registry values to the existing public static fields. NMS patches
   keep reading statics — zero patch churn. Call-site migration to
   `Knobs.X.get()` happens opportunistically in later specs.
5. `ymlBool/ymlInt/ymlDouble/ymlGet/ymlStringList/ymlEntityTypeMap` delegate
   **registry-first**, jar-resource fallback second. Existing warn-once and
   `LOOKUP_CACHE` semantics preserved for the fallback path.
6. `YmlWriter` renders the operator file; atomic write.
7. Registry-generated superseded-key report replaces the `SupersededKeys` class
   (one INFO summary + per-key WARN when operator value differs from default —
   same operator-visible behavior, now driven by declared metadata).

`config-version` bumps to 8. Timing note: a `ymlBool` read before step 3 returns
the declared default (same observable behavior as today's knob boot sequence);
knob values are atomic so late overlay is safe.

## Operator File Rendering Rules

- **Fresh install:** only `ACTIVE` keys, grouped by declared section, deterministic
  order, Paper-style `#` comment block above each key/section from declarations.
- **SUPERSEDED / RESERVED keys:** never written to fresh files. If present in the
  operator's existing file, preserved in place with an annotation comment naming
  the status and the Paper/Moonrise equivalent (house rule: keys never deleted).
- **Unknown keys** (operator custom / from other builds): preserved verbatim under
  a trailing `# --- unrecognized keys (preserved) ---` header.
- `YamlConfiguration.save` is never called again; `YmlWriter` owns the file.
- Write is atomic: temp file + `Files.move(..., ATOMIC_MOVE)` so a crash mid-save
  cannot truncate the operator config.

## PG1 Per-Key Verdicts

| Key(s) | Verdict | Notes |
|---|---|---|
| `entity.tick-rate-limit` | **ACTIVE** | Already wired: `ActivationRange.java:351` S2 gate stretches inactive-entity tick cadence via `ENTITY_TICK_RATE`. The stale "no consumer" comment (v9.13) in `SourbyCraftConfig` is deleted; registry comment documents real behavior. |
| `performance.max-platform-threads` | **ACTIVE (caveat)** | Sets `max.bg.threads` system property; `Util.BACKGROUND_EXECUTOR` is already constructed by then. Caveat lives in the key comment. |
| `performance.async-chunk-load`, `performance.async-pathfinding`, `multithreading.enabled`, `performance.structured-concurrency`, `memory.skip-empty-sections`, `memory.pool-entity-data`, `memory.pre-size-packets`, `memory.chunk-compression-cache`, `chunk.async-save-batch`, `entity-tracker.*`, `network.netty.*`, `network.proxy-mode`, `network.proxy-kick-*` | **SUPERSEDED** | Owned by Moonrise / Paper config. Paper-equivalent named in each key's comment. Boot report generated from registry; hand-written `SupersededKeys` class deleted. |
| `item.pool-enabled`, `item.pool-size`, `item.pool-max-growth`, `item.pool-shrink-threshold` | **RESERVED** | Item pool engine offline (levitation bug); keys reserved for pool v2. Existing boot WARN when `pool-enabled: true` kept, driven by registry status. |

No new performance code ships in this spec.

## Error Handling

- Malformed operator yml → SEVERE + boot abort (unchanged from today).
- Type mismatch → warn-once + declared default.
- Out-of-range → clamp + warn-once (existing `KnobRegistry.warnOnce` pattern).
- `YmlWriter` failure → SEVERE, server continues with in-memory values.
- Duplicate key/alias registration → `IllegalStateException` at class-init
  (fail-fast, existing pattern).

## Verification

Per house rule (no JUnit, no smoke harness for new sub-specs): build passes
(`./gradlew build` on release/26.2), then operator boots
`test-harness/TestServer-mojmap` manually and checks:

1. Fresh boot (no existing yml): generated `sourbycraft.yml` is commented,
   sectioned, contains only ACTIVE keys.
2. Boot with a pre-existing 26.2 operator yml: values preserved, legacy alias
   keys honored, superseded/unknown keys preserved with annotations.
3. Boot log knob snapshot (`perf knobs loaded [boot]: ...`) shows identical
   values to a pre-change boot with the same operator file.
4. Superseded WARNs still fire when an operator sets a superseded key non-default.
5. `/sourbycraft` commands and NMS-patched features behave unchanged (statics
   bridge intact).

## Follow-on Specs (out of scope here)

CS2 operator-UX polish → CS3 validation/migration hardening → CS4 hot-reload
(uses `reloadable` flag) → PG2 spark-profile audit on TestServer → PG3 fork
optimization ports chosen from PG2 evidence → PG4 SelfTune coverage expansion.
