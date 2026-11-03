# Security review

Build 50, PR #62 (2026-10-25): code written and statically reviewed; not built; not tested by the
owner, and not in the published `v26.2-r50` prerelease (tag 5f5a167, before PR #61 and PR #62).
`repository: redis` and `mode: distributed` are EXPERIMENTAL and not qualified; `mode: distributed`
with the file repository is refused. Evidence: [readiness](../../releases/BUILD-50-READINESS.md),
[PM matrix](../../releases/BUILD-50-PM-MATRIX.md) and [Tester Owner](../../tester%20owner.md).

Scope: the code added for AWF MultiProfile and the Aurora Redis Fabric. This is a static review, not a
penetration test or a qualification.

## Secrets

- Connection settings are reused from `aurora.awf.redis.*`; no new credential key exists, so a
  password cannot drift between two settings.
- `RedisClient.Endpoint.redact` masks everything between `://` and the last `@` of every URI the fabric
  logs, and a URI parse error does not repeat the URI. `describe()` and the startup line use it;
  `/awf redis` never prints the URI.
- `FabricHealth` reports reachability, version, latency and pool sizes only.
- A Redis ACL failure surfaces as the server's error text, which does not contain the password; the
  password is only ever in the `AUTH` command, not in the reply.

## Untrusted input

- Snapshot decoding is a fixed, bounded binary format: every length is checked before allocation, the
  record frame carries a SHA-256 of the payload and the snapshot carries a SHA-256 of the state, and
  `ItemStack.deserializeBytes` is the only object construction performed. No Java serialisation, no
  reflection. Redis records use the same frames and are verified on every read.
- Profile types, adapter ids, world and template names and effect keys are checked against explicit
  regexes and length bounds. Profile metadata has bounds only (at most 32 entries, keys 1–64
  characters, values up to 512), no regex.
- Stream events are a flat string map with a field count and value length limit; an unknown type is
  acknowledged, not interpreted.
- `multiprofile.directory` refuses absolute paths and `..`, so an operator cannot point the repository
  at an arbitrary location through the config file.
- RESP replies are bounded (a bulk string over 65 MiB, or an oversized aggregate, is refused).

## Exposure through commands

- `/awf multiprofile` requires `sourbycraft.command.awf.multiprofile` (status, list, inspect, owner).
  `create`, `switch`, `bind`, `unbind`, `lock`, `unlock`, `slots`, `recover`, `reclaim` and
  `migrate-repository` additionally require `sourbycraft.command.awf.multiprofile.admin`, which
  `sourbycraft.command.awf.admin` does not imply. The bind/unbind commands and the public binding API
  landed after the `v26.2-r50` tag and are absent from that published jar.
- `/awf redis` requires `sourbycraft.command.awf.redis`.
- No command prints inventory contents, item bytes or snapshot payloads. `inspect` prints metadata and
  revisions; `list` prints ids, types, revisions and world names.
- `/awf redis` diagnostics run on the administrative I/O executor (64 tasks, no queue) and answer
  `Busy: ...` when it is full; `/awf multiprofile` operations run through the engine's profile I/O
  queue (`migrate-repository` on the administrative I/O executor). None of them runs Redis I/O on the
  sender's region thread.

## Distributed boundary

With `repository: redis` the repository is the authoritative commit point: every write is one Lua
script that refuses it atomically when the claim it carries no longer holds the profile (session,
owner token, epoch and Redis-time expiry) or the stored revision moved. A node that lost its Redis
connection cannot write at all. This is the only split-brain claim made, and it is unqualified (MP-010
has not run); it does not cover writes that carry no claim (journal phases before a switch holds one,
failed-switch records, terminal-journal removal, profile creation and slot limits), nor durability
losses on the Redis side: `appendfsync everysec` can lose about 1 s of acknowledged writes, and a
failover to an asynchronous replica can lose acknowledged writes and move epochs back. See
[DISTRIBUTED-OWNERSHIP.md](DISTRIBUTED-OWNERSHIP.md). No claim is made for AWF world commits beyond
their existing per-world lease, and none for the file repository across servers: its revision check is
process-local.

## Co-op membership (Build 52)

Build 52 code written and compiled by an agent; not tested by the owner. Off by default.

- **Authority is checked against the records the commit compares.** Owner, manager and member permissions, the
  invitee identity of an accept or decline, the inviter's continued membership, the vote electorate and the
  size cap are checked in Java (`CoopMembership`) against the profile, account, invite and vote records the
  operation read, and the operation's version vector names each of those records at the revision it read;
  the repository compares that vector atomically at prepare and at commit (file: under the commit lock;
  Redis: in the script) and refuses `STALE_STATE` when any of them moved. Every membership plan reserves the
  profile's metadata record, so two membership changes of one profile cannot interleave between prepare and
  commit. What the store itself enforces, independent of the caller: member rows change only under a profile
  claim and inside an intent; an account gains or loses access to a co-op profile only inside an intent, and a
  co-op profile becomes an account's active profile only for its owner or a current member (Build 53; Build 52
refused members with `COOP_SWITCH_UNAVAILABLE`), never for a removed member;
  a co-op record is refused on a store whose marker was not raised to schema 2 / Build 52. A plugin must still
  decide which player may ask for what: the API takes the acting account id as an argument and does not
  authenticate it. `/coop` acts only as the sender itself (permission `sourbycraft.coop.use`, default true),
  shows a co-op's members and votes to its members only, and is registered only while co-op is enabled: at boot as
  `/sourbycraft:coop`, and the bare `/coop` only after the plugins enabled and only when none of them holds it (an
  INFO names the holder otherwise); `/awf multiprofile coop` acts on behalf of any
  named player and every change through it needs `sourbycraft.command.awf.admin`.
- **Member state is bound to a membership epoch (Build 53).** A member's head and snapshots are keyed by profile,
  member and membership epoch. A capture ticket carries the session generation, the active profile, the epoch and
  the revision it started from, and a save is refused when any changed, so a capture cannot be written into another
  profile, another member's stream or a later membership of the same account. A removed member's rejoin has a new
  epoch and an empty state; the old epoch is never restored. The ticket is an integrity check against stale
  writers, not an authentication of the player.
- **Authoritative login does not fall back to vanilla data (Build 53).** While it applies (`login.authoritative`
  `auto` with co-op enabled, or `true`) a join that cannot read the committed head is refused (`HEAD_UNREADABLE`),
  as is one whose journal, claim or adapters cannot be settled; it never *loads* `players/data/<uuid>.dat` as a
  substitute, because that file could hold items already moved to another profile or member. It may *adopt* that
  file once, as the head's next snapshot, only when the per-account session marker shows the previous session was
  unclean (or absent: the upgrade from Build 52), the file is newer than the head, and the marker's provenance names
  the active profile on this node; the marker is written by the server only, never by a client. `false` keeps the
  Build 52 behaviour and its duplication window (an uncaptured quit leaves items in playerdata that the snapshot does
  not hold); it is a risk the operator accepts and the server logs a WARN for it with co-op on.
- **Adapters are trusted plugin code with bounded blobs (Build 53).** An adapter's bytes are stored verbatim and
  never deserialised by the server; limits (2 MiB per adapter, 6 MiB aggregate, 8 MiB snapshot, 32 adapters) are
  unchanged. A REQUIRED adapter cannot be skipped by a failure or by its absence, so a plugin cannot make a switch
  appear successful while its data was dropped; but an adapter's own `apply` is arbitrary plugin code and the
  server cannot verify what it restored beyond its returning without error. A plugin cannot lower its criticality
  below what the operator set in `adapters.required`. `descriptor().scope()` is a declaration the server does not
  verify.
- **Online removal captures before it removes (Build 53).** A leave or kick of a member who is playing the co-op
  switches the member out through the gated capture and removes the membership afterwards, so the member's items
  are in one place (their last co-op snapshot, which salvage will consume in Build 54, or their new state), not
  both.
- **Shutdown capture is bounded, not guaranteed (Build 53).** `shutdown.capture-deadline-ms` bounds the wait; a
  player not saved by then keeps the last committed state, and the process can still be killed before it runs.
- **Invitations and votes are bounded.** Open invitations per profile and per invitee, open votes and
  pending operations are store bounds; reaching one is `CAPACITY_EXCEEDED` before anything is written. An
  invitation expires `coop.invite-expiry-minutes` after issue and expiry is judged at commit against the
  repository clock, so a stale accept cannot succeed and a backwards clock does not revive one: Redis `TIME`
  with a Redis repository; with the file repository a clock that never runs backwards within the process and
  whose high-water mark is persisted (`clock.awfc`, written ahead of the clock by 30 s and exactly at a clean
  close) so a restart with the system clock set back resumes from the mark, not from the system clock.
- **No privilege from membership yet.** A member added in Build 52 cannot switch into the profile (Build
  53), so membership grants no world, inventory or bank access through MultiProfile in this build.
- **Removal cannot be replayed.** A leave or kick consumes the membership epoch durably; a rejoin is a new
  epoch with an empty personal state, so a removed member cannot restore an old membership, and a retried
  removal does not create a second salvage entry. The salvage entry is unavailable to delivery.
- **Owner protection.** The owner cannot leave, be kicked or transfer ownership. An accept or a removal
  cancels the profile's undecided vote in the same intent, and a removal revokes the open invitations the
  removed member issued (at most 16 per removal; an invitation of a member no longer present at the epoch
  that issued it is refused at accept in any case), so a vote cannot be executed against a changed electorate
  and a removed manager's invitations admit nobody. A kick holds the removed account's session claim; a
  session on another node is never taken over for it (`OWNERSHIP_CONFLICT`).
- **Names.** The shared label is owner-set, bounded text and is not used for uniqueness or authorisation;
  members' aliases are separate and are never derived from it.
- **Events** are asynchronous, carry ids only (no inventory data) and are fired after the commit on the
  service's own event thread (a bounded queue of 256; a full queue drops the event with a warning, it never
  blocks the storage lane); a handler runs outside region, entity, global tick and Netty threads and must not
  touch world or player state directly.
- **Downgrade.** The first co-op record raises the store to minimum build 52 before it is written, so Build 51
  refuses the store instead of reading co-op records as corrupt, and the auto-updater refuses a jar below 52. The
  first switch journal of a non-owner member raises it to 53 (Build 53), for the same reason against Build 52.
- **Unchanged limits.** Co-op does not widen the distributed boundary: the Redis repository fences each
  commit as described above, and OD-1 refuses joining a co-op profile on a node that does not hold its
  bound world rather than claiming a multi-writer world.

## Residual risks

- A malicious plugin with access to the storage lane or the fabric can call the fabric directly; like
  every internal API, it is not a security boundary between plugins.
- File permissions of `awf/profiles` (and of `awf/node-id`, `awf/incarnation`, `awf/node.lock`) are
  those of the server process; snapshots and `accounts/<player>.legacy*.awfp` archives contain player
  inventories and should be treated like `world/playerdata`. With `repository: redis`, anyone with
  access to the Redis keys can read them.
- The profile directory is held with an OS lock (`awf/profiles/repository.lock`, naming node and pid);
  a second server on it fails MultiProfile startup. Network filesystems without OS file locks may not
  enforce it.
- Unreadable journals are set aside (`transitions/*.corrupt`) or reported (Redis) and block only their
  player until an operator removes them.
- `single-active-session` rejects a duplicate login when enabled. A stale claim from a crashed node
  blocks a login until its TTL (45 s by default with Redis). Recovery cannot clear another process's
  claim; waiting for the TTL does, or `/awf multiprofile reclaim <profile> <epoch>` for a holder that is
  gone. A restart on the same host takes over its own previous claims at once only when
  `awf/node.lock` proves the predecessor stopped (same lock file, same boot).
