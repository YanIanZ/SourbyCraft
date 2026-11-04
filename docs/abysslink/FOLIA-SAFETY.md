# Folia ownership

AbyssLink I/O threads own no world or entity state. They handle bytes, timers, immutable values and thread-safe counters. No world/chunk/entity work may originate directly from a network callback.

## Command flow

1. Check `sourbycraft.command.ping.other` before target lookup, scheduler work or proxy queries. Console without a target retains “Player not found”.
2. Schedule the target player's entity scheduler with `MinecraftInternalPlugin.INSTANCE`. Snapshot UUID, name, local latency, client metadata and any needed address value inside that callback.
3. Submit immutable values to asynchronous work. Never pass a live Player/World/Entity into the lookup or retain one in caches.
4. Return rendering and fallback messages through `SourbyReply.run(sender, ...)`. The target region does not own a different player or command-block sender. Entity senders use their entity scheduler; command blocks use their block region; console/RCON go direct.
5. Handle immediate scheduler rejection (`run` returns null; neither callback executes) separately from accepted-then-retired work. Retirement callbacks perform bookkeeping and enqueue replies only, without entity/world operations.

There is no global main-thread fallback: `MinecraftServer.execute()` is unsupported here. Existing examples are `command/PerfPlayerView.java` and `command/SourbyReply.java`.

## Disconnect and reconnect

Runtime tokens invalidate asynchronous replies on quit, world change and teleport. Bind delivery to the original sender scheduler; do not resolve a replacement Player by UUID after reconnect. A disconnected sender's reply may drop. Complete futures, release admission slots and clean pending state independently of delivery: SourbyReply uses a null retirement callback and ignores a null scheduling result.

Correlate request ID, UUID and connection/session generation. Remove/complete entries only if they still belong to that request; a late timeout or reply must not mutate a newer entry. Invalidate old cache/pending state on reconnect. Cache only bounded immutable samples; reject expired/future timestamps.

## Capacity and shutdown

Bound pending work, cache, frame lengths and writes. The v1 backend only echoes proxy heartbeat requests and originates no probes, so its control RTT remains unavailable. Echo once without creating a loop. A hard 64-query/second ping budget falls back locally when saturated.

Do not add blocking network work to region/boot/shutdown threads. If sharing an executor, account for its capacity and lifecycle; the existing VirtualExecutor is a 64-task administrative pool. Stop reconnect scheduling and owned I/O resources on shutdown. World unload/retirement must not wait for remote replies.

Metric snapshots may be read without touching world state. LongAdder sums are independent diagnostic observations, not an atomic state-machine check. Validation status and evidence are tracked in [TESTING.md](TESTING.md).
