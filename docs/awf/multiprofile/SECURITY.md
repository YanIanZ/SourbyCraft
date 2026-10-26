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
