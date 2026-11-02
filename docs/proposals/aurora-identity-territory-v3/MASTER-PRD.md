# Product Requirements — Aurora Identity & Territory Fabric

**Classification:** future-state requirements, not implemented. Version: RFC v3.0, 2026-11-03.

## Problem and desired outcomes

A Minecraft account must be able to create multiple genuinely isolated characters with user-selected unique names while remaining securely bound to its authenticated Mojang UUID. Staff and plugins must be able to resolve both identities. An independent survival/skyblock world should eventually scale across several SourbyCraft JVMs with safe ownership of chunks, player state and world mutations.

The current AWF MultiProfile implements parts of local/distributed snapshot handling but does not deliver complete cross-server live state, transactional plugin isolation or territory-partitioned simulation.

## User journeys

- New Java player: authenticate through trusted proxy -> identity capability check -> first-join native dialog -> canonical-name reservation -> create profile + durable account binding -> choose gamemode -> load authoritative world -> unfreeze play.
- Returning player: authenticate -> reconcile interrupted handoff -> select/restore active character -> validate required plugin adapters -> apply presented GameProfile -> join gameplay.
- Existing player: /profile -> view slots -> create, select, rename, recover or delete with explicit confirmation and cooldowns; no cross-character item or balance leakage.
- Staff: /whois <character> -> authorized audit showing account, profile, session, linked characters and rename history; account-wide sanctions remain on trusted UUID.
- Operator: /arf status/plan/simulate/migrate only after dry run; world ownership trace, manifests, quota/budget and explicit recovery.

## Functional requirements

| ID | Requirement | Acceptance |
|---|---|---|
| ID-01 | Real UUID is immutable and preserved at connection/login | No account UUID changes across switches or node transfer |
| ID-02 | Character name registration is case-insensitive network-wide | Concurrent create/rename yields exactly one unique owner |
| ID-03 | Character names are displayed using supported Paper presentation APIs | Secure-chat/skin/lookup regression suite documented and passes |
| ID-04 | Every supported state category declares ACCOUNT or PROFILE scope | No silent fallthrough into unqualified shared data |
| ID-05 | Required profile adapter failure fails closed | Old profile remains active or journal enters recovery |
| ID-06 | Native Mojang/Paper Dialogs provide onboarding with server validation | GUI tests with real Java client; Bedrock is optional separate path |
| ID-07 | Staff can resolve original identity without public disclosure | Role permissions and audit logs enforced |
| ARF-01 | Territory ID is deterministic for world/dimension/chunk | Negative coordinates, world borders and dimensions correct |
| ARF-02 | Territory supports exclusive owner/fencing epoch | Forced stale writer cannot modify durable manifest |
| ARF-03 | World state is crash-consistent across chunk/entity/POI/tick data | Crash injection returns one valid checkpoint |
| ARF-04 | Proxy-assisted boundary handoff is recoverable | No double session, duped item or silent lost player state |
| ARF-05 | Remote visuals cannot mutate world or tick chunks | Stale mirror interaction is refused/routed safely |
| ARF-06 | Plugin support is expressed as tested capability tiers | Operator knows unsupported plugins and boundary limits |
| OPS-01 | Feature flags, audits, rollback and upgrade procedures are documented | Owner can enable in canary, disable without deleting data |

## Nonfunctional requirements

Correctness > security > durability > compatibility > reliability > latency > throughput. No universal compatibility claims. Deployment minimums and capacity ratios are benchmark-derived, not invented. Acceptable MSPT, reconnect/handoff latency and memory are defined as target envelopes by the owner after a repeatable baseline on the target CPU, player density, view/simulation distance and plugin stack.

Every transition must be observable with durable transaction IDs, bounded queues, backpressure, safe cancellation, explicit timeout outcomes and idempotent recovery. Avoid allocating unbounded maps, full-world eager loads and blocking callbacks on owner threads.

## Out of scope in first production candidate

- Seamless zero-reload cross-JVM gameplay, cross-server real-time redstone fidelity, full cross-border hopper/piston semantics and arbitrary third-party NMS compatibility.
- Automatic reinterpretation of every plugin's UUID-keyed database as character-scoped.
- Changing the authenticated Mojang identity, impersonating a Mojang username or issuing external alt accounts.
- Bedrock client UI parity unless separately scoped and tested.

## Acceptance stages

M0 local safety and Build 50 qualification -> M1 identity registry/API -> M2 Atrium dialogs, plugin compatibility -> M3 distributed account/profile handoff -> M4 island placement -> M5 fixed-territory partition/one-way migration -> M6 proxy territory handoff -> M7 mirrors/affinity -> M8 research-grade transparent simulation. No launch without phase-specific owner evidence.

## Decision record (proposed)

- D-01 use account UUID + profile UUID + canonical character name + session epoch, not modified login UUID.
- D-02 authoritative source separate from cache; choose transactional SQL for future global identity/ownership metadata after an explicit migration design; do **not** dual-write Redis+SQL without a single commit authority.
- D-03 default to one writer per *world instance* today, one writer per *territory* only after partitioned storage implementation.
- D-04 distribute closed/isolated islands before dividing one live SMP world.
- D-05 expose profile capabilities rather than claiming all legacy plugins are safe.
- D-06 use a proxy-assisted backend handoff before attempting packet-level zero-reconnect simulation.
