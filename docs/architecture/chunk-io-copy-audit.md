# Chunk save/load copy audit

> **Role:** static code read of the byte copies a chunk goes through between its `CompoundTag` and
> the disk, 2026-10-20 (the "identify duplicate serialization/copies before optimizing" item in
> DEVELOPMENT-TASKS.md G). Companion to
> [network-allocation-copy-audit.md](network-allocation-copy-audit.md).
> **Status:** read from source only. **Nothing here was run or measured**: no allocation profile, no
> byte counts, no CPU cost. A copy listed here is not shown to matter; that needs a JFR allocation
> profile of a save-heavy and a load-heavy workload (needs a server). Nothing was changed.
> **Code references re-checked 2026-10-25** against the current tree: the encode/decode helpers moved
> out of MC 0021 into `AwfChunkNbt`, and the line numbers below are the current ones. The copies
> themselves are unchanged; Build 50 (PR #62) changed how `AwfWorld` keeps its entries, not the
> defensive copies.

Sources: the materialized `sourbycraft-server/src/minecraft/java` (`RegionFile`, `RegionFileStorage`;
SourbyCraft hunks from MC feature patches 0021 (`count-chunk-system-storage-traffic`, which carries the
AWF attach and, since 2026-10-22, only calls `awf/RegionFileStorageHooks`; when this audit was written it held
`sourby$encode`/`sourby$decode` itself) and 0043 (save timing)), and `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/`
(`AwfChunkNbt.encode`/`decode` are those two helpers, moved there unchanged). N = raw NBT size of the
chunk, D = its deflated size. "Growth" = `ByteArrayOutputStream` doubling, which copies the bytes written
so far on every resize (amortized under 2 x the final size).

Not traced: how the chunk system builds the `CompoundTag` (`SerializableChunkData` snapshot on the owning
region, serialization off it). That copy exists for thread ownership and is not a duplicate candidate.

## 1. Region-file path (vanilla/Paper, AWF off)

| Step | Where | Copies |
|---|---|---|
| Save: NBT -> deflate -> buffer | `RegionFileStorage.moonrise$startWrite` -> `NbtIo.write` into `RegionFile.ChunkBuffer` (a `ByteArrayOutputStream`, initial 8096) through `version.wrap` | deflate writes D bytes into the buffer + growth copies past 8 KiB |
| Save: buffer -> file | `ChunkBuffer.moonrise$write` -> `RegionFile.write(pos, ByteBuffer.wrap(buf, 0, count))` | no Java copy (wrap); the JDK copies a heap buffer into a temporary direct buffer for `FileChannel.write` (D) |
| Load: file -> buffer | `RegionFile.getChunkDataInputStream`: `ByteBuffer.allocate(numSectors * 4096)` + `file.read` | sector-rounded heap buffer (zeroed) + JDK direct-to-heap copy |
| Load: inflate -> NBT | `createStream` = `ByteArrayInputStream` over the same array (no copy) -> `NbtIo.read` | inflate only |

No duplicate copy of the whole chunk found on this path beyond Paper's own buffer growth and the JDK's
heap-buffer channel copies. Not a SourbyCraft change target without a profile.

## 2. Aurora World Fabric path (AWF attached; FILE backend)

| Step | Where | Copies of the chunk |
|---|---|---|
| Save 1: encode | `AwfChunkNbt.encode` (called by `RegionFileStorageHooks.startWrite`/`write`, from MC 0021): `NbtIo.write` into a `ByteArrayOutputStream(16 KiB)` then `toByteArray()` | growth past 16 KiB + **one full copy (N)** by `toByteArray` |
| Save 2: hand-off | `AwfRegionStorage.write` -> `AwfWorld.write`: `put(key, serialized.clone())` (`AwfWorld.java:251`) | **one full copy (N)**, defensive; the array from step 1 is never reused by its caller |
| Commit: hash | `ObjectStore.put` -> `name(raw)` = SHA-256 of N | read only |
| Commit: deflate | `AwfFile.deflate`: `ByteArrayOutputStream(N/2)` + `toByteArray()` | growth + **one copy (D)** |
| Commit: frame | `ObjectStore.put`: `ByteBuffer.allocate(4 + D).putInt(N).put(deflated)` (`ObjectStore.java:143`) | **one copy (D)**; then the JDK heap-to-direct copy in `FileChannel.write` |
| Load (still in memory) | `AwfWorld.read`: `Optional.of(mine.bytes().clone())` (`AwfWorld.java:199`) | **one full copy (N)**, defensive; the caller (`AwfChunkNbt.decode`) only reads it |
| Load (from the store) | `ObjectStore.get`: `Files.readAllBytes` (4 + D), `Arrays.copyOfRange(stored, 4, len)` (`ObjectStore.java:253`), `AwfFile.inflate` into `new byte[N]`, SHA-256 verify | **one copy (D)** by `copyOfRange`, then inflate |
| Load: decode | `AwfChunkNbt.decode` (from `RegionFileStorageHooks.readData`/`read`): `NbtIo.read` over a `ByteArrayInputStream` | none; note it runs as `SYNC_READ` on the I/O thread, whereas the region-file path hands raw bytes back for a later decompress/parse |

## 3. Duplicate-copy candidates (unmeasured)

Ordered by how directly the code shows a copy with no consumer of the original. None is shown to cost
anything measurable; each would change an internal contract and needs its own test.

1. **Save: `toByteArray` + `clone` of the same N bytes** (steps Save 1 and 2). Two full copies where an
   ownership hand-off would need none: `AwfChunkNbt.encode`'s array has no other holder. Removing the clone
   changes `AwfRegionStorage.write`'s documented contract ("The array is copied") for every caller,
   including AWF import/copy paths, so each caller would have to be checked for reuse.
2. **Load: `clone` in `AwfWorld.read`** for chunks still in memory. Safe only if every reader treats the
   array as read-only; `ChunkPruning`/import paths would need the same check.
3. **Commit: deflate `toByteArray` then copy into the frame buffer** (`AwfFile.deflate` + `ObjectStore.put`):
   deflating straight into a buffer that already reserves the 4-byte length would drop one D-sized copy.
4. **Load: `copyOfRange` before inflate** (`ObjectStore.get`): `Inflater.setInput(byte[], off, len)` takes an
   offset, so the body copy is avoidable.

Candidates 3-4 change the FILE store's object path (`ObjectStore`, `AwfFile`), whose on-disk framing must
stay byte-identical. Before acting on any of them: a JFR allocation profile of an AWF save/load workload to
see whether these arrays are a visible share of allocation at all.
