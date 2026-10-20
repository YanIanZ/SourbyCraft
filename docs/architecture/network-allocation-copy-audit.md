# Network packet allocation and copy audit

> **Role:** static code read of the per-packet buffer and object path, 2026-10-20 (closes the
> "inspect packet allocation/copy count" item in [AURORA-TASKS.md](../AURORA-TASKS.md) Network).
> **Status:** read from source and from the bundled jars with `javap`. **Nothing here was run or
> measured**: no byte counts, no allocation rates, no CPU cost. Counts are per packet and describe
> what the code does, not what it costs. Part of [engine-network.md](engine-network.md) §3.

Sources: `sourbycraft-server/src/minecraft/java/net/minecraft/network/` (materialized from the
patches; no SourbyCraft feature patch edits these classes), Netty 4.2.15.Final and
`velocity-native` 4.1.0 as resolved by `sourbycraft-server/build.gradle.kts`. Netty and Velocity
behaviour below was read from the 4.2.15.Final / 4.1.0 class files, not from memory.

## 1. Outbound pipeline (clientbound, one packet, non-local connection)

Handlers run tail to head: `packet_handler` -> `encoder` -> `compress` -> `prepender` -> `encrypt` ->
socket (`Connection.configureSerialization`, `setupCompression`, `setEncryptionKey`).

| Stage | Class | Buffer allocation | Copy of the body |
|---|---|---|---|
| Object -> bytes | `PacketEncoder` (`MessageToByteEncoder`, no `allocateBuffer` override) | 1 x `ioBuffer()`, **initial capacity 256 bytes**, grows as the codec writes | The codec serializes the packet fields into it (inherent). Each growth past 256 bytes reallocates and copies the bytes written so far |
| Compression | `CompressionEncoder` | 1 buffer sized `readable + 1`, from the compressor's preferred type | Below threshold: `VarInt.write(0)` + `writeBytes` = **full copy**. At/above threshold: libdeflate reads the input directly when it is direct (`ensureCompatible` returns `retain()`), so deflate is a transform, not an extra copy |
| Length prefix | `Varint21LengthFieldPrepender` (`MessageToByteEncoder`, default `allocateBuffer`) | 1 x `ioBuffer()` (256), then `ensureWritable(header + body)` reallocates once (still empty, so no bytes copied) | **Full copy** of the body behind a 1-3 byte header |
| Encryption (online mode only) | `CipherEncoder` | none when the buffer is already the cipher's preferred type | none; encrypts in place |
| Socket | Netty Epoll/Kqueue/NIO write | none for a direct buffer with a memory address | Epoll copies only if `isBufferCopyNeededForWrite` (heap buffer, or a direct buffer with more than `IOV_MAX` components) |

Per packet, natives loaded, compression on, encrypted, body at or above the threshold: **3 pooled
buffers** (4 incl. the empty-realloc one in the prepender), **1 pure memcpy of the whole body**
(prepender), plus the codec's own write and the growth copies of stage 1.

Variants (same code, different pipeline composition):

| Variant | Pooled buffers | Whole-body memcpys beyond the codec write |
|---|---|---|
| Natives, compressed, body >= threshold | 3 | 1 (prepender) |
| Natives, compressed, body < threshold | 3 | 2 (compress + prepender) |
| Compression disabled (threshold < 0, `compress` handler removed) | 2 | 1 (prepender) |
| Encrypted with the **Java** cipher (`JavaVelocityCipher` is `HEAP_REQUIRED`; the prepender output is direct, so `ensureCompatible` allocates a heap buffer and copies) | +1 | +1, and the Epoll write then copies heap -> direct again (+1 buffer, +1 copy) |

Natives available: the bundled `velocity-native` 4.1.0 jar contains Linux x86_64, Linux aarch64 and
macOS arm64 libraries only (no Windows, no macOS x86_64); on any other platform, or when the
library fails to load, `Natives.cipher`/`Natives.compress` fall back to the Java implementations
(`JavaVelocityCompressor` is `DIRECT_PREFERRED`, so only the cipher changes the table). Whether a
given server actually loaded the natives was not checked here.

## 2. Per-packet objects before the pipeline

`Connection.send` always goes through `pendingActions` in this fork (the Paper immediate-send branch
is disabled by `false &&`, Folia region-threading connection fixes). Each send allocates a
`PacketSendAction` (which is a `WrappedConsumer` holding an `AtomicBoolean`), the lambda capturing
`(packet, listener, flush)`, and (almost certainly) a `MultiThreadedQueue` node (the queue implementation was not read); `flushQueue` then polls under
`synchronized (queue)` and a `flushingQueue` CAS. `InnerUtil.buildExtraPackets` allocates a list
only when the packet has extra packets. The Aurora counters (`perf/NetworkMetrics`: `WireCounter`,
`PacketCounter`) allocate nothing per packet (`instanceof` + `LongAdder`).

## 3. Fan-out: the same packet is encoded once per connection

`ChunkMap.TrackedEntity.sendToTrackingPlayers*` loops `seenBy` and calls `connection.send(packet)`
with the **same** `Packet` object, but the Netty pipeline is per connection, so stages 1-4 above run
once per receiver. Nothing caches a serialized or compressed buffer across connections. A packet
sent to N viewers costs N times the Section 1 totals. (Per-connection compression and encryption
state would make sharing the final bytes impossible; sharing the pre-compression body is not done
and would need a patch.)

Bundles: `PacketBundleUnpacker` splits a bundle into delimiter + sub-packets + delimiter, and each
element goes through the whole pipeline separately (Section 1 per element).

## 4. Largest producers

* **Chunk send** (`PlayerChunkSender`): a `new ClientboundLevelChunkWithLightPacket` is built **per
  player per chunk** (no per-chunk cache). The constructor allocates the chunk data `byte[]`
  (`calculateChunkSize`), clones every heightmap `long[]`, and `ClientboundLightUpdatePacketData`
  does `data.copy()` per light section. On encode, `ClientboundLevelChunkPacketData.write` copies
  the whole `byte[]` into the 256-byte-initial encoder buffer, so a chunk packet of tens of KiB
  passes through several power-of-two growth steps in stage 1 on top of the copies in Section 1.
  Anti-Xray, when enabled for the player, adds its own pass in the constructor.
* **Entity updates**: small packets, so the fixed per-packet cost (Section 2 plus 3 pooled buffers,
  and for packets under the threshold 2 memcpys) dominates; multiplied by the viewer count.

## 5. Inbound pipeline (serverbound)

`decrypt` (in place, copy only if the buffer is the wrong type) -> `splitter`
(`Varint21FrameDecoder`: **`in.readBytes(length)` copies every frame into a new buffer**) ->
`decompress` (length 0: `readBytes(readableBytes)` copies again; otherwise one `preferredBuffer`
plus inflate) -> `decoder` (`PacketDecoder`, Aurora's `SecurityGuards` scope, then the codec
allocates the packet's own `byte[]`/`String`/collections) -> `packet_handler`. So 1-2 pooled buffers
and 1-2 whole-body copies per packet, before the packet's own objects. `ByteToMessageDecoder`'s
cumulation may add a merge copy for frames split across reads (not read in this audit).

## 6. What this does and does not establish

* It establishes the structure of the fixed per-packet cost and which stages are pure copies. It
  does **not** establish that any of it is a bottleneck: no `network-stress` run with clients exists
  ([engine-network.md](engine-network.md) §4), and pooled direct buffers are cheap relative to
  deflate/encrypt for large packets.
* ProtocolLib, ViaVersion/ViaBackwards and other plugins add their own pipeline handlers and
  per-packet wrappers when installed; they are not counted here.
* Candidate reductions (none implemented, none claimed to help): size the stage-1 buffer from a
  hint to avoid growth copies; fold the length prefix into the compress stage's output so the
  prepender copy disappears (both are Minecraft feature patches, vanilla/Paper-owned classes, and
  would need `needs-patch-slot`, a `network-stress` baseline first and the compression-setting
  rule in engine-network.md §3: operator-visible compression stays operator-owned); cache the
  per-chunk packet body across players (correctness: Anti-Xray and per-player light/ready state).
* To turn this into numbers: a certified `network-stress` run with clients reading
  `PooledByteBufAllocator.DEFAULT.metric()` (allocations, arena churn) and JFR allocation
  profiling, with hardware, config and noise state recorded per `DEVELOPMENT.md`.
