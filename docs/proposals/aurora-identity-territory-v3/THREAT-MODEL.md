# Threat Model — Aurora Identity / Region Fabric

**Status: design review.** Apply defense-in-depth; security of current code is **not** established by this document. Production activation requires review against candidate commit, code audit and owner-run network fault tests.

## Assets, actors, boundaries

**Assets:** authenticated account identity, profile and inventory state, unique names, session claim and epoch, staff identity lookup, world/territory manifests, snapshots, owner node admission, proxy backend routing and log integrity.

**Actors:** legitimate users, malicious users with valid Minecraft accounts, direct-to-backend attackers, malicious/unqualified plugins, compromised non-authority server, degraded coordinator, rogue replayed handoff message, and operators with elevated privileges.

**Trust zones:** external client < trusted Leviathan edge < private backend/network services < commit authority/storage. A GameProfile name is **presentation**, not trusted identity evidence. Redis/SQL/event transport is internal but still authenticate, authorize, encrypt and constrain every connection.

## Security review table

| Threat / failure | Impact | Mandatory mitigation | Acceptance evidence |
|---|---|---|---|
| Direct backend join bypasses proxy | attacker supplies identity | block direct public backend ingress, verify forwarded identity/secret, pinned proxy trust | direct-join rejection |
| Character name collides/impersonates staff | social engineering, command confusion | atomic canonical unique index, reserved names, rename audit, immutable ID on sanctions | concurrent create + impersonation fixture |
| Profile-based ban evasion | evades moderation | account-wide sanctions on authenticated UUID; optional character sanctions on profileUUID | switch does not evade ban |
| Duplicate account sessions | double-spend/item dupe | single active account session claim, monotonic fencing and fail-closed admission | two-node same-account race |
| Redis restart regresses epoch | stale writer may appear valid | monotonic durable epoch authority; verify token at manifest commit; quarantine on regression | restore/failover rehearsal |
| Network partition | split-brain tick/write | deny ambiguous owner, fence at authoritative commit and suspend unsafe mutation | partition test, exactly one owner |
| Lost commit response | repeated transaction creates duplicates | idempotency tx ID, authoritative readback/settlement | fault injection at publish |
| Adapter silently drops state | profile corruption | required adapters fail closed, schema/version migration, corruption alarms | forced adapter failure |
| Remote operation forged via Fabric | arbitrary actions | typed allowlisted operations, principal/permission, signed/mTLS transport, length & rate limits | tampering/fuzz cases |
| Stale read-only mirror accepted as writable | duplication or inconsistent world | enforce no tick/writes, owner epoch and revision validation, deny stale interactions | mirror replay/delta gaps |
| Staff account mapping leaked | deanonymization/abuse | separate permissions, reason code, least privilege, redacted audit, retention | permission negative tests |
| UUID/display-name API confusion | wrong target | stable IDs in business logic, versioned lookup, logged ambiguity errors | rename and offline lookup tests |
| Plugin writes wrong profile after async callback | leakage/duplication | context token (profileId + identityRevision + epoch), rejection of stale completion | A->B->A delayed mutation |
| Unbounded queues or snapshot frames | denial of service | capacity/admission limits, framed checksums, bounded parsing, no blocking owner threads | stress/oversize tests |

## Signed chat and textures

Paper's mutable presented PlayerProfile must not be treated as proof of Mojang login. Skin texture property must come from configured trusted provenance and be validated before use. Changing name mid-session may have client effects; secure/signed chat, tab list, entity re-registration, server logs and plugin caches are independent qualification gates. Do not suppress client signature verification to hide identity bugs.

## Authority and consistency

Administrative action scopes are explicit: account ban, character restriction, world membership, region ownership migration. Every mutation is authorized against an immutable account principal and stable target ID, never an untrusted display string. Stale/expired ownership tokens fail at final commit, not only at coordinator read.

Require mutual trust between proxy and backend (private network and verified forwarding); between coordinator and nodes (TLS/mTLS or equivalent application authentication); storage ACLs scoped to least privilege; encryption at rest for sensitive audit data where applicable. Never print session secrets, Redis credentials, signed texture payloads, IP+account mapping or full inventories to routine logs.

## Audit and privacy

Log correlation ID, actor ID, operation, target stable IDs, old/new epoch, outcome, source node and timestamp. Audit accesses to linked characters and authenticated account names. Retention, access control, data deletion and incident runbooks must be configured by owner; public placeholders must not disclose private mappings. Avoid general tracing of player chat or personal location.

## Security gates

SEC-01 direct-backend denial; SEC-02 forged-handoff/duplicate/replay; SEC-03 name races; SEC-04 account-ban consistency; SEC-05 stale snapshot/profile mutation; SEC-06 world commit fencing under partition; SEC-07 credential and PII log redaction; SEC-08 malicious plugins/remote RPC; SEC-09 schema downgrade refusal; SEC-10 signed chat + texture verification. All unresolved P0 findings block production.
