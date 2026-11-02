# SourbyAPI and Plugin Compatibility — Contract Proposal

**Status:** proposed service signatures and capability taxonomy. Current public APIs remain unchanged until explicit versioned implementation. No guaranteed third-party plugin compatibility.

## API placement

- Existing `AuroraProfiles` remains authoritative for profile lifecycle. Introduce separate `AuroraIdentityService` with a capability/version negotiation interface, preferably registered through Bukkit ServicesManager before dependent plugins are enabled.
- Introduce `AuroraTerritoryService` for mapping and remote state; no synchronous network operations.
- Extend `ProfileStateAdapter` contract only through a backward-compatible interface or new descriptor API; never silently reclassify current optional adapters.
- Provide immutable records. API callbacks have no thread guarantee unless explicitly documented. Entity/world mutation must be rescheduled on the matching Folia owner scheduler.

## Proposed service sketch (non-compilable until added)

~~~java
record IdentityView(UUID accountUuid, String authenticatedName,
                    UUID profileUuid, String characterName,
                    long identityRevision, long sessionEpoch) {}
record TerritoryRef(UUID worldId, String dimensionKey,
                    long x, long z, int layoutVersion) {}

interface AuroraIdentityService {
    Optional<IdentityView> localActive(UUID accountUuid);
    CompletionStage<Optional<IdentityView>> lookupCharacter(String canonicalOrDisplayName);
    CompletionStage<ProfileSwitchResult> activateCharacter(UUID accountUuid, UUID profileUuid);
}
interface AuroraTerritoryService {
    TerritoryRef locate(UUID worldId, String dimensionKey, int chunkX, int chunkZ);
    CompletionStage<TerritoryOwnerView> resolveOwner(TerritoryRef key);
    CompletionStage<RemoteOperationResult> submit(RemoteOperationRequest authorizedRequest);
}
~~~

A remote operation needs caller principal, operation ID, capability, deadline, scope, expected epoch/revision, bounded payload and serialized response. No arbitrary remote code execution or automatic command dispatch through Redis.

## Compatibility tiers

| Tier | Expected plugin behavior | Treatment |
|---|---|---|
| L0 LEGACY | Reads player UUID; may cache login name and store account-wide data | Keep account-wide; profile-scoped use prohibited unless qualified |
| L1 ADAPTER | Registers capture/apply with scope and criticality | Allow scoped character gameplay after tests |
| L2 NATIVE | Uses AIE identity and scoped repository; supports rename/transition events | Full character support in one node |
| L3 DISTRIBUTED | Handles node handoff, fences async callbacks, remote owner operations | Eligible for territory worlds after multi-node tests |

Operator config should explicitly classify plugins with a fail-closed default for *important mutable profile data*, not silently assign L3. Plugins that touch NMS directly, block on region threads or assume remote chunks are local may be UNSUPPORTED for ARF.

## Existing Paper/Bukkit semantics

- `Player#getUniqueId()` must remain the authenticated account UUID.
- `Player#setPlayerProfile()` changes name and properties presentation, not UUID; its exact effect on `getName()`, `getPlayerExact()`, tab lookup, signed chat and plugin caches on target build must be tested. A name change is not a universal storage isolation layer.
- `Bukkit.getOfflinePlayer(String)` is not network character registry; do not repurpose globally without versioned opt-in and safety review.
- Permissions, account bans, staff audit and anticheat account enforcement use authenticated UUID. Character-only moderation uses profile UUID.
- Name ambiguity: reserve canonical character names and define whether matching real Mojang names are blocked/aliased; avoid impersonation.

## Event and context proposal

`IdentityPrepareEvent` (non-mutating, before apply), `IdentityActivatedEvent` (after durable commit; entity-owned callback), `IdentityRecoveryRequiredEvent` (gated) and `TerritoryOwnershipChangedEvent` (async immutable). Events carry origin account, old/new profile UUID and revision/tx ID. Events never imply access to remote world on caller thread.

Offer `IdentityContext` token for async tasks (account, profile, revision, session epoch); reject stale completion. No mutable public singleton storing current profile as only lookup.

## PlaceholderAPI and permissions (proposed)

Placeholders: `%aurora_character_name%`, `%aurora_profile_uuid%`, `%aurora_account_name%`, `%aurora_account_uuid%`, `%aurora_profile_type%`, `%aurora_session_node%`. Account identity and linked-character disclosures must be permission-gated and audit-aware; do not leak account mapping to ordinary player placeholders on public chat/scoreboard.

Permissions design: `sourbycraft.identity.inspect`, `sourbycraft.identity.rename.admin`, `sourbycraft.territory.admin`, `sourbycraft.territory.inspect`. These **do not exist yet** and are not instructions to run.

## Compatibility test matrix (required)

EssentialsX/CMI importer path, LuckPerms, Vault/Atrium Vault, PlaceholderAPI, ProtocolLib, PacketEvents, TAB, chat and signed chat, NPC/skins, inventories/quests, SSB/Skyblock plugins, WorldGuard/FAWE, Geyser/Floodgate and client rejoin. Record version, use case, data scope, thread safety, limitations, evidence SHA and owner verdict before advertising a plugin as supported.
