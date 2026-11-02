# Aurora Identity + AWF Region Fabric — Design Package v3.0

**Status: PROPOSAL / RFC, documentation only.** Target: SourbyCraft branch `26.2`. Owner review and acceptance required for implementation. No new runtime functionality is implemented by this package. Do not interpret design examples as existing configuration, commands, APIs, measured results, or production qualification.

## Goal

Build an account-safe multi-character Minecraft network and a multi-server territory-sharded survival world. Preserve the authenticated Mojang account UUID while exposing a unique, network-owned character name and profile ID. Make Atrium the player/staff UX and Leviathan the authenticated proxy transport; SourbyCraft remains authoritative for identity, profile transitions, simulation and world state.

## Read in this order

1. [MASTER-PRD.md](MASTER-PRD.md) — objectives, scope, acceptance requirements, decisions.
2. [IDENTITY-ARCHITECTURE.md](IDENTITY-ARCHITECTURE.md) — real UUID, character identity, GameProfile and name reservation.
3. [MULTIPROFILE-TRANSACTIONS.md](MULTIPROFILE-TRANSACTIONS.md) — isolation, snapshots, recovery, adapter semantics.
4. [TERRITORY-ARCHITECTURE.md](TERRITORY-ARCHITECTURE.md) — mapping, owner model, simulation, boundaries.
5. [PARTITIONED-STORAGE.md](PARTITIONED-STORAGE.md) — durable manifests, fencing, commit contracts.
6. [HANDOFF-AND-MIRRORS.md](HANDOFF-AND-MIRRORS.md) — proxy handoff, remote view, entity migration.
7. [PUBLIC-API-AND-COMPAT.md](PUBLIC-API-AND-COMPAT.md) — proposed API, plugin capabilities, event contracts.
8. [THREAT-MODEL.md](THREAT-MODEL.md) — attack surfaces, failure isolation and security controls.
9. [MIGRATION-AND-OPERATIONS.md](MIGRATION-AND-OPERATIONS.md) — safe rollout, backout, runbooks.
10. [OWNER-ACCEPTANCE-PLAN.md](OWNER-ACCEPTANCE-PLAN.md) — objective verification and owner test ledger.
11. [ROADMAP-AND-EPICS.md](ROADMAP-AND-EPICS.md) — dependency-driven major upgrade plan.

## Current truth / verified baselines

- Branch `26.2` already has `AuroraProfiles`, `ProfileStateAdapter`, AWF, world templates, Redis Fabric and a Redis profile repository in code. The published `v26.2-r50` prerelease predates PR #61/#62; documentation warns that distributed mode on the published jar is unsafe with FILE storage and is not qualified. Read [Build 50 readiness](../../releases/BUILD-50-READINESS.md), [current MultiProfile architecture](../../awf/multiprofile/ARCHITECTURE.md), [distributed ownership](../../awf/multiprofile/DISTRIBUTED-OWNERSHIP.md) and [AWF architecture](../../architecture/aurora-world-fabric.md).
- Existing AWF Redis world ownership is **per world/store**, not per territory. `RoutingFabric` is a node directory, not a proxy handoff transport. Profile handoff between live servers and the proposed territory simulation are not implemented.
- Atrium currently supports MultiProfile-scoped homes/back/kits but exposes nickname, moderation and several other data categories account-wide. See [Atrium compatibility](https://github.com/YanIanZ/Atrium/blob/main/docs/SOURBYCRAFT.md).
- Leviathan's public repository has a multi-region edge runtime; that is **not** multi-node world simulation. Advanced proxy integration may be edition-gated; do not rely on private Premium code from this public RFC.

## Non-negotiable invariants

| ID | Invariant |
|---|---|
| INV-01 | `Player#getUniqueId()`, authentication and account sanctions use the original trusted account UUID. |
| INV-02 | Each character has its own immutable `profileUuid`; name is mutable and never an authority key. |
| INV-03 | A canonical character name has at most one live owner network-wide. |
| INV-04 | At most one valid mutable player session per authenticated account when that policy is enabled; profile ownership is also exclusive. |
| INV-05 | Required state that fails capture/apply/persist blocks activation or enters recovery; never report success. |
| INV-06 | No old node can commit after losing a fencing epoch. Fencing is enforced by the **authoritative commit backend**, not just a preflight check. |
| INV-07 | Each territory has at most one authoritative simulation writer; visual mirrors do not tick or write. |
| INV-08 | Neither Netty nor Folia entity/region/global tick threads block on Redis, SQL, network RPC, or world storage. |
| INV-09 | Cross-node handoff is journaled, idempotent, recoverable and keeps the same accountUuid/profileUuid. |
| INV-10 | Normal gameplay resumes only after identity, world, plugin-required state and session ownership are ready. |

## Release and evidence policy

This is a **mergeable documentation proposal**, not a completion claim. `CODE DONE`, `OWNER TESTED`, `QUALIFIED`, `STABLE` require independent, commit-specific evidence under [DEVELOPMENT.md](../../DEVELOPMENT.md). Proposed APIs, config keys, events, commands, throughput and timings are deliberately illustrative until implemented and verified. Never silently turn a proposal into a release note.

## Cross-repository companion work

- [Atrium](https://github.com/YanIanZ/Atrium): character creation, native Dialog UI, staff inspector, scoped data and provider compatibility.
- [Leviathan](https://github.com/YanIanZ/Leviathan): authenticated session handoff transport, node destination resolution, failure recovery and privacy-conscious telemetry.

All three reviews may merge independently as **documentation only**. The implementation dependencies are sequenced in ROADMAP-AND-EPICS.md.

## References

- Paper API `Player#setPlayerProfile`: https://javadocs.papermc.io/paper/1.21.11/org/bukkit/entity/Player.html
- Paper Dialog API: https://docs.papermc.io/paper/dev/dialogs/
- Folia regions: https://docs.papermc.io/folia/reference/overview/
- Folia regionizer: https://docs.papermc.io/folia/reference/region-logic/
- Minecraft 1.20.5 transfer: https://feedback.minecraft.net/hc/en-us/articles/26136167989005-Minecraft-Java-Edition-1-20-5-Armored-Paws
