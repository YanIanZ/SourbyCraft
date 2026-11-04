# Profile types and the type registry (Build 54)

Build 54 code written; compiled by an agent; **not tested** (owner rule: the owner runs every test). Nothing here is
qualified. MultiProfile stays disabled by default; with `aurora.awf.multiprofile.enabled: false` the registry is not
registered as a service, nothing is read or written, and the engine's built-ins-only registry answers internal calls.

## What it is

Every profile has a **type id** chosen once at creation. The registry maps a type id to a
`ProfileTypeDefinition` (sourbyapi, `dev.iyanz.sourbycraft.api.profile`):

| Field | Meaning |
|---|---|
| `id` | `[a-z0-9_-]{1,32}`, the stored key |
| `displayName`, `description` | 1-32 and 0-256 characters, no control characters |
| `coopAllowed` | `false` denies `coop.convert`, `coop.invite`, `coop.accept` and (Build 55) `coop.kick`, `coop.label` whatever the rules say |
| `creatable` | `false` refuses new profiles of the type (`TYPE_NOT_ALLOWED`); existing profiles keep it |
| `maxMembers` | co-op cap override 2-8, or none (then `coop.max-members`) |
| `naming` | `server` (the fruit/vegetable pool) or `pool` (the type's own 1-256 names, see below) |
| `rules` | up to 64 action ids, each `allow` or `deny` with up to 8 string parameters |
| `defaultRule` | the rule of an action without its own (default `allow`); not applied to the Build 55 ids `profile.switch`, `coop.kick`, `coop.label`, which are allowed unless `rules` names them (see Rule ids) |

Action ids are free strings `[a-z0-9][a-z0-9_.:-]{0,63}`. The server reads only the allow/deny decision; parameters
are stored and returned for the consumer.

### Where a profile's type id is stored

A built-in type is the `ProfileType` enum already stored in the profile record since Build 51 (`classic`, `ironman`,
`stranded`, `bingo`). A registered type is stored as `ProfileType.CLASSIC` plus the profile metadata entry
`aurora:type = <id>` (`ProfileTypeRegistry.TYPE_METADATA_KEY`); metadata is fixed at creation, so the type never
changes. `ProfileTypeRegistry.typeId(profileType, metadata)` and `ProfileDetails.typeId()` return the effective id.
This adds no record kind and no record format change. Build 53 would read such a profile as plain `classic` (it
enforces no type rule at all, so the type's denies would be lifted) and refuses a name outside the server pool, so the
first profile of a registered type, or named from a type's own pool, raises the store to **minimum build 54**
(`StoreFormat.REGISTERED_TYPE_MINIMUM_BUILD`) before its record is prepared; Build 53 then refuses the store at start.

A create request names a registered type with `ProfileRequest.withTypeId("hardcore")` (a built-in id sets
`profileType` instead). The engine's create path resolves the request through `ProfileTypes.planCreate` and the name
through `ProfileTypes.chooseName`:

- a built-in id must be in `types.allowed` (`TYPE_NOT_ALLOWED`); a registered id is not subject to that list;
- an id with no definition is refused with `TYPE_UNKNOWN`; a definition with `creatable: false` with `TYPE_NOT_ALLOWED`
  (except the fresh solo profile the engine creates for a member leaving or removed from a co-op, which is not a
  player's create: a `creatable: false` default type does not keep the member in);
- `profileType` other than `classic` together with a registered id is `INVALID_REQUEST`.

## Rule ids

Engine-owned (the server checks them itself, `ProfileTypes.require(profile, action)` →
`ACTION_FORBIDDEN_BY_TYPE`, message naming the type and the action, nothing written):

| Id | Checked by (API method / command) |
|---|---|
| `bank.deposit` | `deposit`; `applyInterest` (an interest posting is a credit); `transfer` into the shared bank |
| `bank.withdraw` | `withdraw`; `transfer` out of the shared bank |
| `bank.personal` | `depositPersonal`, `withdrawPersonal`; `transfer` (both directions); the personal part of `applyInterest` (denied: only the shared balance earns) |
| `bank.transfer` | `transfer` (shared↔personal, together with `bank.personal` and the direction's shared rule above; the purse is refused regardless, `PURSE_TRANSFER_UNSUPPORTED`) |
| `collections.contribute` | `contribute` (Build 54; allowed unless the type denies it) |
| `coop.convert` | `convertToCoop` (also needs `coopAllowed` and `coop.allowed-types`) |
| `coop.invite` | `invite` (also needs `coopAllowed`) |
| `coop.accept` | `acceptInvite` (also needs `coopAllowed`) |
| `salvage.claim` | `claimSalvage` |
| `profile.switch` | Build 55: `switchProfile`, checked on the **target** at admission (before any claim or capture); a type that denies it can be created, read and administered but nobody plays it. Not applied to a target whose type has **no definition** on the server, whatever `types.unknown-type-policy` says (legacy free-form types stay playable; review P2-7), nor to the evacuation switch of a leave or kick |
| `coop.kick` | Build 55: `startKickVote` and `voteKick` (casting a ballot; also refused outright by a type with `coopAllowed: false`) |
| `coop.label` | Build 55: `setCoopLabel` (also refused outright by a type with `coopAllowed: false`) |

**The Build 55 ids and `defaultRule` (review N2).** `profile.switch`, `coop.kick` and `coop.label` are **allowed unless
the definition names them** in `rules` (`ProfileTypeRegistry.allowedUnlessNamed`, applied by
`ProfileTypeDefinition.rule`): a definition's `defaultRule` does not decide them. A type registered for Build 54 with
`defaultRule: deny` never named these ids, so after the upgrade its profiles stay playable and its kick votes and
labels are not refused; a type that wants to deny one names it (`.deny(ProfileTypeRegistry.PROFILE_SWITCH)`).
`coopAllowed: false` still refuses `coop.kick` and `coop.label`. The definition that stands for an unreadable stored
record denies all three by name, so a corrupt file never lifts a deny.

The `/coop` and `/awf multiprofile coop bank` commands call the same engine methods, so the same checks apply. Reads
(`bank`, `collections`, `salvage`, `getCoopDetails`, listings) and leave, decline and cancel are not type-gated (Build 55
audit of the remaining entry points: they move or close state the member already holds; a type rule there would trap a
member). Casting a ballot **is** type-gated (`coop.kick`, review P3-9).

**Order of the archived and type checks.** An archived profile refuses every mutation except a member's leave
(`PROFILE_ARCHIVED`, [OPERATIONS.md](OPERATIONS.md#archived-read-only-profiles)), but where that refusal comes relative
to the type rule differs by action:

- `switchProfile`: the archive is checked first, on the target; an archived target answers `PROFILE_ARCHIVED` before
  `profile.switch` is consulted.
- `convertToCoop`, `setCoopLabel`, `invite`, `acceptInvite`, `startKickVote`, `voteKick`, every bank operation
  (`deposit`, `withdraw`, personal, `transfer`, `applyInterest`, `contribute`) and `claimSalvage`: the type rule is
  checked first, so an archived profile whose type denies the action answers `ACTION_FORBIDDEN_BY_TYPE`; an archived
  profile whose type allows it answers `PROFILE_ARCHIVED`. Either way nothing is written.

This table and list are the code's call sites; they are not a test result.

### Seasonal types (Build 55)

`ProfileTypeDefinition.seasonal()` (`Builder.seasonal(true)`; the built-in `bingo` has it) marks a type whose
profiles are meant to be archived read-only when its event ends (owner decision, HYPIXEL-PARITY §6.4: never silently
deleted). The flag changes no rule and triggers nothing in the server: the consumer that runs the event archives its
profiles through `AuroraProfiles.archiveProfile` (or an operator with `/awf multiprofile archive`), and
`ProfileDetails.seasonal()` shows it. A stored definition with the flag uses record version 2 (one flags byte); a
definition without it keeps the version-1 record, so a Build 54 server reads every non-seasonal stored definition
unchanged and treats a seasonal one as unreadable — unknown, deny (never classic).

Consumer-owned examples (the server never checks them; the consumer calls `allows`): `trade`, `auction`, `bazaar`,
`island-bank`, `portal`, or any id the consumer invents.

## Built-in defaults

| Type | Denied | Co-op | Naming |
|---|---|---|---|
| `classic` | nothing | allowed | server pool |
| `ironman` | `trade`, `auction`, `bazaar` | allowed | server pool |
| `stranded` | `trade`, `auction`, `bazaar` | allowed | server pool |
| `bingo` | nothing | allowed | server pool |

Every built-in has `creatable: true`, no `maxMembers` override and `defaultRule: allow`, so the bank, co-op and
salvage actions are **allowed** for all four. This follows the Hypixel SkyBlock parity target (HYPIXEL-PARITY §2.3,
§6: Ironman keeps its bank; its restriction is trading, auction house and bazaar, which the server has no action
for). A server that wants "Ironman: no bank" says so in its consumer's definition (example below) — the built-in
defaults deny nothing new, so a Build 54 upgrade does not refuse an operation by surprise. Bingo's end-of-event
archiving and Stranded's single island are not rules of this registry. Since Build 55 a consumer or operator archives a
profile read-only with `AuroraProfiles.archiveProfile` or `/awf multiprofile archive` (see Seasonal types above and
[OPERATIONS.md](OPERATIONS.md#archived-read-only-profiles)); the server archives nothing on its own.

`coop.allowed-types` (default `[classic]`) still applies to conversion of a built-in type, in addition to the
registry rule.

## Registering types (consumer)

```java
ProfileTypeRegistry types = Bukkit.getServicesManager().load(ProfileTypeRegistry.class); // or profiles.typeRegistry()
types.register(this, ProfileTypeDefinition.builder("ironman")
    .displayName("Ironman")
    .deny("trade").deny("auction").deny("bazaar")
    .deny(ProfileTypeRegistry.BANK_DEPOSIT).deny(ProfileTypeRegistry.BANK_WITHDRAW).deny("island-bank")
    .build());
types.register(this, ProfileTypeDefinition.builder("hardcore")
    .displayName("Hardcore").coopAllowed(false).maxMembers(null)
    .naming(ProfileNaming.pool(List.of("Ember", "Cinder", "Ash")))
    .deny("trade")
    .build());
boolean ok = types.allows(details.typeId(), "island-bank");          // no I/O
profiles.allows(profileId, "island-bank").thenAccept(allowed -> ...); // reads the profile, async
```

- `register` puts the definition in force immediately; the store write runs afterwards on the registry's own thread
  (`SourbyCraft-AWF-profile-types`), never on the caller's thread, queued in the order the definitions took effect. A
  failed write is logged; the definition stays in force until restart and the store keeps its previous copy. After
  MultiProfile stopped, `register` fails with `DISABLED` (nothing is kept in memory only).
- An override of a built-in id that denies any action raises the store's minimum build to 54 (on the I/O lane, after the
  registration), so a downgrade to Build 53, which has no registry and would apply the built-in, is refused by the
  marker. On a format-1 Redis store the raise is refused (see Persistence) and a WARN says the deny is not protected
  against that downgrade.
- An id registered or stored by another plugin (compared by plugin name) is refused with `INVALID_REQUEST`; a
  built-in id is refused with `TYPE_NOT_ALLOWED` while `types.registry.allow-override-builtins` is `false`. At most 64
  non-built-in types.
- `register`, `unregister`, `definition`, `definitions`, `source`, `owner` and `allows(typeId, action)` do no I/O.
  `rules(profileId)` / `allows(profileId, action)` (also on `AuroraProfiles`) read the profile record and complete
  asynchronously; never join them on a region, entity or global-tick thread. `PROFILE_NOT_FOUND` for no such profile.
- There is no registration event in Build 54.

### Lifecycle: a deny is never silently lifted

`unregister(owner, id)` and disabling the plugin (`PluginDisableEvent`) end the live registration
(`ProfileTypeSource.REGISTERED`) but keep its definition in force as `STORED`. At the next start the stored
definitions are read before the engine opens the profile store, so profiles of that type keep their rules while the
plugin is absent. Registering the same id again replaces it. Nothing in the API deletes a stored definition; an
operator removes one only by deleting its record while the server is stopped (FILE: `types/<id>.awpt`; Redis: the
field `<id>` of `<prefix>mp:types`). Resolution order for an id: registered → stored → built-in → unknown (a stored
or registered override of a built-in id is used only while `allow-override-builtins` is true).

### Unknown types: fail-safe deny

A profile whose type id has no definition (not built in, never registered on this server, no stored copy; an
unreadable stored copy is a deny-all tombstone instead, see Persistence) is answered by
`aurora.awf.multiprofile.types.unknown-type-policy`:

- `deny` (default): every action is refused, the engine-owned ones with `ACTION_FORBIDDEN_BY_TYPE`, and one WARN names
  the type (at most 256 distinct ids get a WARN).
- `allow`: every action is allowed, as for `classic`; a WARN at every start says so.

Why deny: the only way to reach "unknown" is that the definition was lost (plugin never ran here, record deleted or
corrupt, a file→Redis repository migration, which does not copy type records). The lost definition may have denied the
bank or co-op; allowing would silently lift that. Deny keeps the profile playable for everything the engine does not
check (switching, saving, login) while bank, co-op and salvage operations wait for the plugin.

### Naming pools

With `naming: pool` a new profile rolls a free entry of the type's pool; a requested name must be an entry (ignoring
case) optionally followed by a space and 1-999; when every entry is taken in the account the first entry with the
smallest free suffix is used (`Ember 2`). Names stay account-unique (ignoring case) and immutable. Pool entries are
1-24 characters of ASCII letters in words separated by single spaces, with an inner `'` or `-` allowed; no digits, so
a suffix is never ambiguous. Entries are unique ignoring case; at most 256. Every pool the registry loads or registers
is admitted to the server's name check for the life of the process (it never shrinks), so a stored profile named from
a pool stays readable when its type is later replaced. A stored profile record is decoded with a lenient name check
(`AuroraProfile.validStoredName`: 1-32 printable characters without surrounding whitespace), so a profile named from a
pool whose definition is no longer stored anywhere still loads; only new names need the pool. Its type is then unknown
and, by default, denied every engine action until the definition is registered again.

## Persistence

| Backend | Where | Write |
|---|---|---|
| FILE | `<multiprofile.directory>/types/<id>.awpt` | temporary file, forced, atomic rename, forced directory |
| Redis | hash `<aurora.awf.redis key-prefix>mp:types`, field `<id>` | one `HSET` |

Record: magic `AWPT`, version 1, owner plugin name, the definition, CRC-32; at most 256 KiB. Startup reads at most
68 records (64 types plus built-in overrides), each bounded before it is decoded (Redis: paged `HSCAN`, a hash with
more fields leaves the surplus unread with a WARN instead of refusing startup). One that is unreadable, fails its
checksum or does not decode is kept as a **deny-all tombstone** with a WARN: every action of its profiles is refused
(`ACTION_FORBIDDEN_BY_TYPE`), it is not creatable and not co-op, until its plugin registers the id again. This also
holds for a stored override of a built-in id (while `allow-override-builtins` is true): the built-in definition does
not take over, so a deny is never lifted by a corrupt or half-written record. These records are not profile-store
record kinds: the format-2 migration, the backup verification and the store's minimum build ignore them (except the
raise described above for a denying built-in override), and Build 53 does not read them. The FILE backup copies the
directory with them, and the FILE→Redis import (`/awf multiprofile migrate-to-redis`) copies them into `mp:types`; an
unreadable record stops the import until it is restored or removed. Redis definitions are read once at startup: a
definition another node registers later is not seen until this node restarts or the plugin registers it here too
(known limit).

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `types.registry.allow-override-builtins` | `true` | RESTART_REQUIRED. Whether a plugin may replace a built-in type. `false`: refused, stored overrides ignored with a WARN |
| `types.unknown-type-policy` | `deny` | RESTART_REQUIRED. `deny` or `allow`, see above |

## Example consumer `types.yml` (SSB-AuroraProfiles addon)

The server does not read this file. It is the format proposed for the SSB-AuroraProfiles addon port (Build 55), which
turns each entry into one `register` call at enable. Shown so operators see how rules map to action ids.

```yaml
types:
  classic:
    display-name: "Classic"
  ironman:
    display-name: "Ironman"
    description: "No trading, auction house, bazaar or bank."
    rules:
      trade: deny
      auction: deny
      bazaar: deny
      bank.deposit: deny
      bank.withdraw: deny
      island-bank: deny
  hardcore:
    display-name: "Hardcore"
    creatable: true
    coop-allowed: false
    naming:
      policy: pool
      pool: ["Ember", "Cinder", "Ash"]
    rules:
      trade: deny
      salvage.claim: deny
    default-rule: allow
```

The short form `ironman: { rules: { bank.deposit: deny, bank.withdraw: deny, island-bank: deny } }` is the same
override with only those three rules (every other action falls to `default-rule: allow`; the built-in `trade`,
`auction`, `bazaar` denies are replaced, not merged, so list them if wanted).

## Not done / open

- No registration event, no command to list or delete types.
- Override of a built-in replaces the whole definition (no merge).
- The built-in bank defaults (allowed for Ironman) are a parity decision recorded here for owner review.
