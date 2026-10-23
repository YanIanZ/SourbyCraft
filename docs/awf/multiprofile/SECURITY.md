# Security review

Scope: the code added on this branch for AWF MultiProfile and the Aurora Redis Fabric.

## Secrets

- Connection settings are reused from `aurora.awf.redis.*`; no new credential key exists, so a
  password cannot drift between two settings.
- `RedisClient.Endpoint.redact` removes userinfo from every URI the fabric logs. `describe()` and the
  startup line use it; `/awf redis` never prints the URI.
- `FabricHealth` reports reachability, version, latency and pool sizes only.
- A Redis ACL failure surfaces as the server's error text, which does not contain the password; the
  password is only ever in the `AUTH` command, not in the reply.

## Untrusted input

- Snapshot decoding is a fixed, bounded binary format: every length is checked before allocation, the
  frame carries a SHA-256 of the payload and the snapshot carries a SHA-256 of the state, and
  `ItemStack.deserializeBytes` is the only object construction performed. No Java serialisation, no
  reflection.
- Profile metadata, adapter ids, world names, types and effect keys are validated against explicit
  regexes and length bounds.
- Stream events are a flat string map with a field count and value length limit; an unknown type is
  acknowledged, not interpreted.
- `multiprofile.directory` refuses absolute paths and `..`, so an operator cannot point the repository
  at an arbitrary location through the config file.

## Exposure through commands

- `/awf multiprofile` requires `sourbycraft.command.awf.multiprofile`; recovery additionally requires
  `sourbycraft.command.awf.multiprofile.admin`.
- `/awf redis` requires `sourbycraft.command.awf.redis`.
- No command prints inventory contents, item bytes or snapshot payloads. `inspect` prints metadata and
  revisions; `list` prints ids, types, revisions and world names.
- Redis administrative reads in the command run on the bounded storage lane, never on the sender's
  region thread.

## Distributed boundary

The ownership service is authoritative. With `fencing-enabled: true` a commit whose fence cannot be
validated is refused, so a Redis outage stops profile writes instead of allowing split-brain. This is
the only split-brain claim made; no claim is made for AWF world commits beyond their existing per-world
lease.

## Residual risks

- A malicious plugin with the storage lane can call the fabric directly; like every internal API, it
  is not a security boundary between plugins.
- File permissions of `awf/profiles` are those of the server process; snapshots contain player
  inventories and should be treated like `world/playerdata`.
- `single-active-session` rejects a duplicate login when enabled. A stale claim from a crashed node
  blocks a login until its TTL (45 s by default); the operator can clear it with recovery or by waiting.
