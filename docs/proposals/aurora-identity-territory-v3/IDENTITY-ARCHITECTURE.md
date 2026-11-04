# Aurora Identity Engine (AIE) — RFC

**Status:** proposed service, not existing implementation. Current interface: [AuroraProfiles.java](../../../sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/profile/AuroraProfiles.java); current create request has `accountId/type/metadata`, **no first-class unique character-name transaction**.

## Identity model and trust boundaries

| Identity | Example | Authority | Mutable? |
|---|---|---|---|
| `accountUuid` | original Mojang UUID | authenticated ingress/proxy, verified backend forwarding | No |
| `authenticatedName` | current Mojang account name at verification | authenticated login snapshot | Updated only at verified login/name refresh |
| `profileUuid` | UUID of character | profile repository | No |
| `characterName` / `canonicalName` | SkyIan / skyian | network identity registry | Rename through atomic operation |
| `sessionId`, `sessionEpoch` | random session and fencing epoch | trusted session coordinator | Renewed/replaced |
| `presentedGameProfile` | account UUID + character name + trusted textures | AIE presentation | Recreated per activation |

**Hard rule:** no authentication, ban, IP/account rate limit or network permission decision may read a mutable presented GameProfile name as proof of ownership. Backend must reject direct untrusted connections and verify proxy forwarding identity. Offline-mode name-derived UUID is not an acceptable identity source for this feature.

## Registry model (logical schema; not yet SQL DDL)

- `accounts(account_uuid PK, last_verified_name, created_at, updated_at, revision)`.
- `profiles(profile_uuid PK, account_uuid FK, type, character_name, canonical_name, status, revision, created_at)`.
- `character_names(canonical_name UNIQUE, profile_uuid UNIQUE, reservation_version, status)`.
- `name_history(profile_uuid, old_key, new_key, changed_at, actor, reason)`.
- `account_sessions(account_uuid UNIQUE, session_id, profile_uuid, owner_node, epoch, lease_state, expires_at)`.
- Name deletion/release is policy-controlled; any tombstone/hold must be respected before reuse. Define reserved namespaces for staff, system, Mojang impersonation policies and offensive names.

Canonicalize with an explicit versioned ASCII policy (initial design: `^[A-Za-z0-9_]{3,16}$` and `Locale.ROOT` lowercasing), no Unicode casefold ambiguity. Invalid or reserved strings fail before allocation. A name availability preview is advisory; **claim+profile creation** must be one authoritative atomic mutation. Rename needs equivalent CAS, uniqueness, transition-state validation and old-name history. Count/slot validation must be atomic under per-account revision to avoid concurrent slot overrun.

## GameProfile presentation, not account impersonation

Use the supported Paper `Player#setPlayerProfile(PlayerProfile)` on the owning entity thread, with the same real account UUID and a server-approved character name. This changes presentation name and properties, not the UUID. Snapshot the verified authentication profile **before** presentation; never recover it later from `player.getName()`.

Do not rely on `createProfileExact` to yield trustworthy arbitrary skin data: create/validate the profile and supply known/allowed texture properties deliberately. Validate skin signature/provenance. Re-registration, tab list, command completion, player lookup, secure/signed chat and texture behavior are explicit regression gates, *not automatic promises*. No reflection/Authlib injection in Atrium. Avoid presenting a staff/Mojang impersonation name.

Server-authoritative method `getIdentity(Player)` returns an immutable identity snapshot bound to a revision. Async commands/callbacks that outlive a profile switch must validate `(accountUuid, profileUuid, identityRevision, sessionEpoch)` before making mutations.

## Login and switch states

`UNAUTHENTICATED -> ACCOUNT_VERIFIED -> SESSION_CLAIMED -> PROFILE_RESOLVED -> STATE_READY -> PRESENTED -> ACTIVE`.

First login with no profile remains in a restricted onboarding state: blocked gameplay, server-side validated Dialog input, no accidental spawn/inventory rewards. Any fatal persistence failure enters `RECOVERY_REQUIRED`; do not create a new profile from error fallback.

Character switching requires journaled profile save/restore first. Presentation only changes after the target profile has been applied and committed; failure either restores the prior presentation after successful rollback or quarantines the session until recovery.

## Staff and security

- Default public views show *character name*; authenticated account name/UUID and linked characters are restricted to staff permissions with reason-coded audit.
- Account-wide moderation keys on account UUID; optional character-only sanctions key on profile UUID. Profile rename must not evade sanctions or audit correlation.
- Owner/staff actions on an offline character use a stable profile ID and revision, never stale name-only lookup.
- Detect cache staleness via identity revision and bounded invalidation. Never perform Redis/SQL lookup on Netty or Folia owner thread.

## Provisional API (not compiled)

~~~java
public record AuroraIdentitySnapshot(
    UUID accountUuid, String authenticatedName,
    UUID profileUuid, String characterName,
    long identityRevision, long sessionEpoch
) {}

interface AuroraIdentityService {
    Optional<AuroraIdentitySnapshot> activeSnapshot(UUID accountUuid); // local bounded snapshot
    CompletableFuture<Optional<AuroraIdentitySnapshot>> resolveCharacter(String characterName);
    CompletableFuture<CharacterCreateResult> createCharacter(CharacterCreateRequest request);
    CompletableFuture<IdentityTransitionResult> activate(UUID accountUuid, UUID profileUuid);
}
~~~

Exact types, threading annotations, permission contracts and service lifecycle will be finalized during implementation. Preserve binary-compatible existing `AuroraProfiles` APIs; add a separate capability service or default methods instead of changing existing record constructors in place.

## Data isolation implications

Many third-party plugins key persistent state on `Player#getUniqueId()`. Presented GameProfile alone does **not** make that state character-scoped. Each plugin must declare ACCOUNT_WIDE, PROFILE_SCOPED (adapter/provider) or UNSUPPORTED. Do not monkey-patch UUID to force alt-like behavior; see [PUBLIC-API-AND-COMPAT.md](PUBLIC-API-AND-COMPAT.md).
